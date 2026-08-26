package ai.rever.boss.plugin.dynamic.screenshotshare

import ai.rever.boss.plugin.api.PluginContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO

private const val POLL_INTERVAL_MS = 25_000L

/** State for the password-entry dialog shown when opening a protected [ReceivedScreenshot].
 * [errorMessage] is null on first prompt and set after a wrong-password retry. */
data class PasswordPrompt(val shareId: String, val errorMessage: String? = null)

/** Holds inbox/sent state and polls for new shares -- plugins get no realtime
 * channel, so polling on [PluginContext.pluginScope] (lifecycle-tied, outlives
 * any single panel composition) is the only delivery signal available. */
class ScreenshotShareViewModel(
    private val context: PluginContext,
    private val api: ScreenshotShareApi,
    private val scope: CoroutineScope,
) {
    private val _received = MutableStateFlow<List<ReceivedScreenshot>>(emptyList())
    val received: StateFlow<List<ReceivedScreenshot>> = _received.asStateFlow()

    private val _sent = MutableStateFlow<List<SentScreenshot>>(emptyList())
    val sent: StateFlow<List<SentScreenshot>> = _sent.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private val _capturing = MutableStateFlow(false)
    val capturing: StateFlow<Boolean> = _capturing.asStateFlow()

    private val _passwordPrompt = MutableStateFlow<PasswordPrompt?>(null)
    val passwordPrompt: StateFlow<PasswordPrompt?> = _passwordPrompt.asStateFlow()

    private var started = false

    // Null until the first successful poll, so opening the inbox never toasts
    // for shares that were already sitting there before this session started.
    private var knownReceivedIds: Set<String>? = null

    fun startPolling() {
        if (started) return
        started = true
        scope.launch {
            while (true) {
                refresh()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun refreshAsync() {
        scope.launch { refresh() }
    }

    suspend fun refresh() {
        api.listReceived()
            .onSuccess { list ->
                notifyNewArrivals(list)
                _received.value = list
                _unreadCount.value = list.count { s -> s.isUnread }
                _loadError.value = null
            }
            .onFailure { _loadError.value = it.message }

        api.listSent().onSuccess { _sent.value = it }
    }

    private fun notifyNewArrivals(list: List<ReceivedScreenshot>) {
        val previouslyKnown = knownReceivedIds
        knownReceivedIds = list.mapTo(mutableSetOf()) { it.id }
        if (previouslyKnown == null) return

        val arrived = list.filter { it.id !in previouslyKnown }
        if (arrived.isEmpty()) return

        val message = if (arrived.size == 1) {
            "New screenshot from ${arrived.first().senderEmail}"
        } else {
            "${arrived.size} new screenshots received"
        }
        context.notificationProvider?.showInfo(message, title = "Secure Grab")
    }

    /** Selects a region of the screen, then opens it for annotation/send. */
    fun captureRegion() = capture { ScreenshotCapture.captureRegion() }

    /** Captures the whole screen, then opens it for annotation/send. */
    fun captureFullScreen() = capture { ScreenshotCapture.captureFullScreen() }

    /**
     * Shared by the panel's capture buttons and the global hotkey
     * (see [ScreenshotShareDynamicPlugin]) so both paths request permission,
     * grab the image and hand it to the annotation window the same way.
     */
    private fun capture(grab: suspend () -> BufferedImage?) {
        if (_capturing.value) return
        _capturing.value = true
        scope.launch {
            val provider = context.screenCaptureProvider
            if (provider != null && !provider.hasPermission()) {
                provider.requestPermission()
            }
            val image = grab()
            _capturing.value = false
            if (image != null) {
                openAnnotationWindow(
                    api = api,
                    scope = scope,
                    capturedImage = image,
                    onSent = {
                        context.notificationProvider?.showSuccess("Screenshot sent", title = "Secure Grab")
                        refreshAsync()
                    },
                )
            }
        }
    }

    fun openReceived(shareId: String, password: String? = null) {
        scope.launch {
            api.getImage(shareId, password)
                .onSuccess { result ->
                    when (result) {
                        is ImageFetchResult.Success -> {
                            val bytes = Base64.getDecoder().decode(result.imageBase64)
                            val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
                            if (image != null) openViewerWindow(image)
                            _passwordPrompt.value = null
                            refresh()
                        }
                        ImageFetchResult.PasswordRequired ->
                            _passwordPrompt.value = PasswordPrompt(shareId)
                        is ImageFetchResult.InvalidPassword -> {
                            val attempts = result.attemptsRemaining
                            _passwordPrompt.value = PasswordPrompt(
                                shareId,
                                errorMessage = "Incorrect password ($attempts attempt${if (attempts == 1) "" else "s"} left)",
                            )
                        }
                        ImageFetchResult.Locked -> {
                            _passwordPrompt.value = null
                            context.notificationProvider?.showError(
                                "Screenshot locked",
                                "Too many incorrect password attempts",
                            )
                        }
                    }
                }
                .onFailure {
                    context.notificationProvider?.showError("Couldn't open screenshot", it.message ?: "Unknown error")
                }
        }
    }

    fun submitPassword(shareId: String, password: String) = openReceived(shareId, password)

    fun dismissPasswordPrompt() {
        _passwordPrompt.value = null
    }
}
