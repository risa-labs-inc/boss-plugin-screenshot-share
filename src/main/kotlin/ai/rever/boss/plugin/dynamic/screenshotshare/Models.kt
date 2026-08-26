package ai.rever.boss.plugin.dynamic.screenshotshare

import androidx.compose.ui.graphics.Color

enum class DrawTool { PEN, RECTANGLE, ARROW, TEXT }

/** A single freehand/rectangle/arrow mark. TEXT is handled separately as [TextAnnotation] --
 * Compose Foundation's DrawScope has no text primitive without a TextMeasurer.
 * [isRainbow] only has an effect for [DrawTool.PEN] -- when set, [color] is ignored and each
 * segment is colored by its position along the stroke instead (see `rainbowColor` in
 * AnnotationCanvas.kt). */
data class DrawAction(
    val tool: DrawTool,
    val color: Color,
    val strokeWidthPx: Float,
    val points: List<Pair<Float, Float>>,
    val isRainbow: Boolean = false,
)

data class TextAnnotation(
    val id: Long,
    val x: Float,
    val y: Float,
    val text: String,
    val color: Color,
)

data class Recipient(
    val userId: String,
    val email: String,
    val displayName: String,
    val orgId: String,
    val orgName: String,
)

data class ReceivedScreenshot(
    val id: String,
    val senderId: String,
    val senderEmail: String,
    val note: String?,
    val width: Int?,
    val height: Int?,
    val createdAt: String,
    val readAt: String?,
) {
    val isUnread: Boolean get() = readAt == null
}

data class SentScreenshot(
    val id: String,
    val recipientId: String,
    val recipientEmail: String,
    val note: String?,
    val width: Int?,
    val height: Int?,
    val createdAt: String,
    val readAt: String?,
)
