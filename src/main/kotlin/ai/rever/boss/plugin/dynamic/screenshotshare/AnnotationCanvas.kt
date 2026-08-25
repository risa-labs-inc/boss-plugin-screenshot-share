package ai.rever.boss.plugin.dynamic.screenshotshare

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Drawing surface for pen/rectangle/arrow marks over [baseImage]. Text
 * annotations are handled by the caller as overlaid Composables (see
 * AnnotationWindow.kt) via [onTextTap] -- DrawScope has no text primitive
 * without a TextMeasurer, so text stays out of this Canvas.
 */
@Composable
fun AnnotationCanvas(
    baseImage: ImageBitmap,
    tool: DrawTool,
    color: Color,
    strokeWidthPx: Float,
    actions: List<DrawAction>,
    onActionsChange: (List<DrawAction>) -> Unit,
    onTextTap: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf<DrawAction?>(null) }

    Canvas(
        modifier.pointerInput(tool, color, strokeWidthPx) {
            if (tool == DrawTool.TEXT) {
                detectTapGestures { offset -> onTextTap(offset) }
            } else {
                detectDragGestures(
                    onDragStart = { offset ->
                        draft = DrawAction(tool, color, strokeWidthPx, listOf(offset.x to offset.y))
                    },
                    onDrag = { change, _ ->
                        val d = draft ?: return@detectDragGestures
                        val pt = change.position.x to change.position.y
                        draft = if (tool == DrawTool.PEN) d.copy(points = d.points + pt) else d.copy(points = listOf(d.points.first(), pt))
                    },
                    onDragEnd = {
                        draft?.let { onActionsChange(actions + it) }
                        draft = null
                    },
                    onDragCancel = { draft = null },
                )
            }
        },
    ) {
        drawImage(baseImage)
        actions.forEach { drawMark(it) }
        draft?.let { drawMark(it) }
    }
}

private fun DrawScope.drawMark(action: DrawAction) {
    val pts = action.points
    if (pts.isEmpty()) return
    when (action.tool) {
        DrawTool.PEN ->
            for (i in 0 until pts.size - 1) {
                drawLine(
                    color = action.color,
                    start = Offset(pts[i].first, pts[i].second),
                    end = Offset(pts[i + 1].first, pts[i + 1].second),
                    strokeWidth = action.strokeWidthPx,
                    cap = StrokeCap.Round,
                )
            }
        DrawTool.RECTANGLE ->
            if (pts.size >= 2) {
                val (s, e) = pts
                drawRect(
                    color = action.color,
                    topLeft = Offset(minOf(s.first, e.first), minOf(s.second, e.second)),
                    size = Size(abs(e.first - s.first), abs(e.second - s.second)),
                    style = Stroke(action.strokeWidthPx),
                )
            }
        DrawTool.ARROW -> if (pts.size >= 2) drawArrow(action, pts[0], pts[1])
        DrawTool.TEXT -> Unit
    }
}

private fun DrawScope.drawArrow(action: DrawAction, from: Pair<Float, Float>, to: Pair<Float, Float>) {
    val start = Offset(from.first, from.second)
    val end = Offset(to.first, to.second)
    drawLine(action.color, start, end, action.strokeWidthPx, cap = StrokeCap.Round)
    val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
    val headLen = 10.0 + action.strokeWidthPx * 2
    val a1 = Offset(
        (end.x - headLen * cos(angle - Math.PI / 7)).toFloat(),
        (end.y - headLen * sin(angle - Math.PI / 7)).toFloat(),
    )
    val a2 = Offset(
        (end.x - headLen * cos(angle + Math.PI / 7)).toFloat(),
        (end.y - headLen * sin(angle + Math.PI / 7)).toFloat(),
    )
    drawLine(action.color, end, a1, action.strokeWidthPx, cap = StrokeCap.Round)
    drawLine(action.color, end, a2, action.strokeWidthPx, cap = StrokeCap.Round)
}

/**
 * Flattens [actions] and [texts] onto a copy of [source] via AWT Graphics2D,
 * producing the final image that gets PNG-encoded and sent. Plain AWT, not a
 * Compose rasterization, so it needs no Skia bitmap export path.
 */
fun flattenAnnotations(
    source: java.awt.image.BufferedImage,
    actions: List<DrawAction>,
    texts: List<TextAnnotation>,
): java.awt.image.BufferedImage {
    val out = java.awt.image.BufferedImage(source.width, source.height, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
    g.drawImage(source, 0, 0, null)

    fun awtColor(c: Color) = java.awt.Color(c.red, c.green, c.blue, c.alpha)

    for (action in actions) {
        g.color = awtColor(action.color)
        g.stroke = java.awt.BasicStroke(action.strokeWidthPx, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
        val pts = action.points
        if (pts.isEmpty()) continue
        when (action.tool) {
            DrawTool.PEN ->
                for (i in 0 until pts.size - 1) {
                    g.drawLine(pts[i].first.toInt(), pts[i].second.toInt(), pts[i + 1].first.toInt(), pts[i + 1].second.toInt())
                }
            DrawTool.RECTANGLE ->
                if (pts.size >= 2) {
                    val (s, e) = pts
                    g.drawRect(
                        minOf(s.first, e.first).toInt(),
                        minOf(s.second, e.second).toInt(),
                        abs(e.first - s.first).toInt(),
                        abs(e.second - s.second).toInt(),
                    )
                }
            DrawTool.ARROW ->
                if (pts.size >= 2) {
                    val (s, e) = pts
                    g.drawLine(s.first.toInt(), s.second.toInt(), e.first.toInt(), e.second.toInt())
                    val angle = atan2((e.second - s.second).toDouble(), (e.first - s.first).toDouble())
                    val headLen = 10.0 + action.strokeWidthPx * 2
                    val a1x = (e.first - headLen * cos(angle - Math.PI / 7)).toInt()
                    val a1y = (e.second - headLen * sin(angle - Math.PI / 7)).toInt()
                    val a2x = (e.first - headLen * cos(angle + Math.PI / 7)).toInt()
                    val a2y = (e.second - headLen * sin(angle + Math.PI / 7)).toInt()
                    g.drawLine(e.first.toInt(), e.second.toInt(), a1x, a1y)
                    g.drawLine(e.first.toInt(), e.second.toInt(), a2x, a2y)
                }
            DrawTool.TEXT -> Unit
        }
    }

    g.font = g.font.deriveFont(16f)
    for (t in texts) {
        g.color = awtColor(t.color)
        g.drawString(t.text, t.x.toInt(), t.y.toInt())
    }

    g.dispose()
    return out
}
