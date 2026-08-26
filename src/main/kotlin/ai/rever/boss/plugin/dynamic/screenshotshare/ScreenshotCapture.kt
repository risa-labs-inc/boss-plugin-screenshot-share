package ai.rever.boss.plugin.dynamic.screenshotshare

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.awt.Color
import java.awt.Cursor
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingUtilities

/**
 * Screen capture and drag-to-select region picking in plain AWT/Swing.
 * `java.awt.Robot` and AWT windowing are parent-first shared classes in every
 * plugin classloader (see docs/plugin-api.md's shared-package allowlist), so
 * this needs nothing from boss-plugin-api beyond the ScreenCaptureProvider
 * permission gate a caller should check first (see ScreenshotShareComponent).
 */
object ScreenshotCapture {

    // Lazy, not eager: a headless environment throws AWTException on construction, and a
    // failed init isn't cached by `by lazy`, so the next capture attempt retries cleanly.
    private val robot by lazy { Robot() }

    fun captureFullScreen(): BufferedImage {
        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        return robot.createScreenCapture(bounds)
    }

    /**
     * Shows a click-drag overlay spanning the virtual desktop and suspends until
     * the user selects a region, or cancels with Escape / a too-small drag
     * (resolves null either way).
     */
    suspend fun captureRegion(): BufferedImage? = suspendCancellableCoroutine { cont ->
        SwingUtilities.invokeLater {
            val virtualBounds = Rectangle()
            for (device in GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices) {
                virtualBounds.add(device.defaultConfiguration.bounds)
            }

            val window = JWindow()
            window.bounds = virtualBounds
            window.isAlwaysOnTop = true
            window.focusableWindowState = true
            window.cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
            // Per-pixel translucency isn't guaranteed on every platform/GraphicsDevice;
            // where unsupported this just renders as an opaque dark overlay, which
            // still works fine as a selection surface.
            runCatching { window.background = Color(0, 0, 0, 60) }

            var start: Point? = null
            var current: Rectangle? = null
            var resolved = false

            fun finish(result: BufferedImage?) {
                if (resolved) return
                resolved = true
                window.isVisible = false
                window.dispose()
                if (cont.isActive) cont.resume(result)
            }

            val overlayPanel = object : JPanel() {
                override fun paintComponent(g: java.awt.Graphics) {
                    super.paintComponent(g)
                    val r = current ?: return
                    g.color = Color.WHITE
                    g.drawRect(r.x, r.y, r.width, r.height)
                }
            }
            overlayPanel.isOpaque = false
            overlayPanel.isFocusable = true

            overlayPanel.addMouseListener(
                object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        start = e.point
                        current = Rectangle(e.point)
                    }

                    override fun mouseReleased(e: MouseEvent) {
                        val s = start
                        if (s == null) {
                            finish(null)
                            return
                        }
                        val local = Rectangle(s).apply { add(e.point) }
                        if (local.width < 4 || local.height < 4) {
                            finish(null)
                            return
                        }
                        val screenRect = Rectangle(
                            local.x + virtualBounds.x,
                            local.y + virtualBounds.y,
                            local.width,
                            local.height,
                        )
                        // Robot reads the actual screen buffer, and this translucent overlay is
                        // still part of it until the window manager has actually removed it --
                        // hiding it in `finish()` (called with the capture already in hand) was
                        // too late, so every capture came out tinted by the selection dimming.
                        window.isVisible = false
                        Toolkit.getDefaultToolkit().sync()
                        Thread.sleep(60)
                        finish(runCatching { robot.createScreenCapture(screenRect) }.getOrNull())
                    }
                },
            )
            overlayPanel.addMouseMotionListener(
                object : MouseMotionAdapter() {
                    override fun mouseDragged(e: MouseEvent) {
                        val s = start ?: return
                        current = Rectangle(s).apply { add(e.point) }
                        overlayPanel.repaint()
                    }
                },
            )
            overlayPanel.addKeyListener(
                object : KeyAdapter() {
                    override fun keyPressed(e: KeyEvent) {
                        if (e.keyCode == KeyEvent.VK_ESCAPE) finish(null)
                    }
                },
            )

            window.contentPane.add(overlayPanel)
            window.isVisible = true
            window.toFront()
            window.requestFocus()
            overlayPanel.requestFocusInWindow()

            cont.invokeOnCancellation { finish(null) }
        }
    }
}
