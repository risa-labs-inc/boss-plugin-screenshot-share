package ai.rever.boss.plugin.dynamic.screenshotshare

import ai.rever.boss.plugin.api.PluginContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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

// Failed polls back off exponentially from the normal interval and cap out at 5
// minutes, so an outage (or broken auth) degrades to ~12 calls/hour instead of
// every client hammering at the healthy rate for as long as it lasts.
private const val MAX_POLL_BACKOFF_MS = 5 * 60_000L

/** State for the password-entry dialog shown when opening a protected [ReceivedScreenshot].
 * [errorMessage] is null on first prompt and set after a wrong-password retry. */
data class PasswordPrompt(val shareId: String, val errorMessage: String? = null)

/**
 * Poll delay after [consecutiveFailures] failed attempts: the healthy interval
 * while things work, then doubling up to [MAX_POLL_BACKOFF_MS].
 *
 * The shift distance is clamped before shifting because Kotlin masks it to 6
 * bits -- an unclamped `shl 64` is a no-op and would silently collapse the
 * backoff back to the base interval (same trap documented in plugin-manager's
 * `backoffMillis`).
 */
internal fun pollDelayMs(consecutiveFailures: Int): Long {
    if (consecutiveFailures <= 0) return POLL_INTERVAL_MS
    val step = (consecutiveFailures - 1).coerceAtMost(16)
    return (POLL_INTERVAL_MS shl step).coerceAtMost(MAX_POLL_BACKOFF_MS)
}

/**
 * Holds inbox/sent state and polls for new shares -- plugins get no realtime
 * channel, so polling is the only delivery signal available.
 *
 * The loop runs on [PluginContext.pluginScope], which outlives any single panel
 * composition, so the panel cancels it via [dispose] on destroy; otherwise it
 * would keep issuing RPCs for the plugin's whole lifetime with no panel open.
 */
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

    private var pollJob: Job? = null

    // Null until the first successful poll, so opening the inbox never toasts
    // for shares that were already sitting there before this session started.
    private var knownReceivedIds: Set<String>? = null

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            var consecutiveFailures = 0
            while (true) {
                val ok = refreshReceived()
                consecutiveFailures = if (ok) 0 else consecutiveFailures + 1
                delay(pollDelayMs(consecutiveFailures))
            }
        }
    }

    /**
     * Cancels the poll loop. Called from the panel's `doOnDestroy`: this
     * ViewModel is created once per plugin and shared by every panel instance,
     * so this only stops the loop -- state is left intact and [startPolling]
     * restarts cleanly when a panel is reopened.
     */
    fun dispose() {
        pollJob?.cancel()
        pollJob = null
    }

    fun refreshAsync() {
        scope.launch { refresh() }
    }

    /** Both lists. Used after sending, where the Sent tab's contents just changed. */
    suspend fun refresh() {
        refreshReceived()
        refreshSent()
    }

    /**
     * Polls the inbox only -- the Sent tab is fetched on demand by
     * [refreshSent], since polling it too doubled steady-state RPC volume for a
     * list that is only rendered on one of two tabs.
     *
     * Returns false if the call failed, which drives the poll backoff.
     */
    suspend fun refreshReceived(): Boolean {
        var ok = true
        api.listReceived()
            .onSuccess { list ->
                notifyNewArrivals(list)
                _received.value = list
                _unreadCount.value = list.count { s -> s.isUnread }
                _loadError.value = null
            }
            .onFailure {
                if (it is CancellationException) throw it
                _loadError.value = it.message
                ok = false
            }
        return ok
    }

    fun refreshSentAsync() {
        scope.launch { refreshSent() }
    }

    suspend fun refreshSent() {
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
                            // read_at just flipped server-side; re-read the inbox for the badge.
                            refreshReceived()
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
