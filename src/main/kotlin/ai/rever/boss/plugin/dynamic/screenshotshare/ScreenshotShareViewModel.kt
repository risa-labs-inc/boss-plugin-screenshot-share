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

    private var started = false

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
            .onSuccess {
                _received.value = it
                _unreadCount.value = it.count { s -> s.isUnread }
                _loadError.value = null
            }
            .onFailure { _loadError.value = it.message }

        api.listSent().onSuccess { _sent.value = it }
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
                    onSent = { refreshAsync() },
                )
            }
        }
    }

    fun openReceived(shareId: String) {
        scope.launch {
            api.getImage(shareId)
                .onSuccess { (base64, _) ->
                    val bytes = Base64.getDecoder().decode(base64)
                    val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
                    if (image != null) openViewerWindow(image)
                    refresh()
                }
                .onFailure {
                    context.notificationProvider?.showError("Couldn't open screenshot", it.message ?: "Unknown error")
                }
        }
    }
}
