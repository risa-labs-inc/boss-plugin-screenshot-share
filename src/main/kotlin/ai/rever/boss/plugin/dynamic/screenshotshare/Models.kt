package ai.rever.boss.plugin.dynamic.screenshotshare

import androidx.compose.ui.graphics.Color

/** Mirrors the 8MB `screenshot_shares_image_size` CHECK and share_screenshot()'s
 * own guard, so an oversized capture fails locally before it is base64-inflated
 * and shipped only to be refused server-side. */
const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

/** Matches share_screenshot()'s server-side length guard. */
const val MAX_PASSWORD_LENGTH = 128

/** Matches the `screenshot_shares_note_length` CHECK. */
const val MAX_NOTE_LENGTH = 500

/** The largest page list_shareable_recipients will serve (its own LEAST(...,200) clamp). */
const val RECIPIENT_PAGE_SIZE = 200

/** Baked into the flattened image, so this only bounds the in-memory string. */
const val MAX_TEXT_LENGTH = 200

/** Ceilings on annotation growth. Every mark is re-walked on each export
 * ([flattenAnnotations]) and re-drawn each frame, so these bound both. */
const val MAX_STROKE_POINTS = 2_000
const val MAX_ACTIONS = 500
const val MAX_TEXTS = 200

/** Pen samples closer than this to the previous point are dropped: a drag emits
 * far more events than the stroke needs, and each one used to copy the whole
 * point list. */
const val MIN_POINT_DISTANCE_PX = 2f

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
    val expiresAt: String?,
    val hasPassword: Boolean,
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
    val expiresAt: String?,
    val hasPassword: Boolean,
)

/** Outcome of [ScreenshotShareApi.getImage] -- the password states are legitimate
 * outcomes of a successful RPC call, not failures, so they're modeled here rather
 * than as exceptions through [Result.failure]. */
sealed class ImageFetchResult {
    data class Success(val imageBase64: String, val mimeType: String) : ImageFetchResult()
    object PasswordRequired : ImageFetchResult()
    data class InvalidPassword(val attemptsRemaining: Int) : ImageFetchResult()
    object Locked : ImageFetchResult()
}
