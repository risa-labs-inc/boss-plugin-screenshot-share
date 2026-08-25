package ai.rever.boss.plugin.dynamic.screenshotshare

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Slider
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Copy
import compose.icons.feathericons.CornerUpRight
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.RotateCcw
import compose.icons.feathericons.Save
import compose.icons.feathericons.Send
import compose.icons.feathericons.Square
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Type
import compose.icons.feathericons.X
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.HeadlessException
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

private val PALETTE = listOf(
    Color.Red, Color(0xFFFFA500), Color(0xFFFFD60A), Color(0xFF34C759), Color(0xFF0A84FF), Color.Black, Color.White,
)

/**
 * Opens a plain Swing JFrame hosting the Compose annotation editor for
 * [capturedImage]. A standalone window rather than a host tab/panel on
 * purpose: nothing documented lets a panel open a new host tab, and a
 * separate window gives the editor the whole screen instead of a sidebar's
 * width.
 */
fun openAnnotationWindow(
    api: ScreenshotShareApi,
    scope: CoroutineScope,
    capturedImage: BufferedImage,
    onSent: () -> Unit,
) {
    SwingUtilities.invokeLater {
        val frame = JFrame("Annotate Screenshot — Secure Grab")
        frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        val panel = ComposePanel()
        frame.contentPane.add(panel)
        frame.setSize(
            (capturedImage.width + 48).coerceIn(760, 1600),
            (capturedImage.height + 200).coerceIn(560, 1200),
        )
        frame.setLocationRelativeTo(null)

        panel.setContent {
            BossTheme {
                AnnotationEditor(
                    capturedImage = capturedImage,
                    api = api,
                    scope = scope,
                    onCancel = { frame.dispose() },
                    onSent = {
                        frame.dispose()
                        onSent()
                    },
                )
            }
        }
        frame.isVisible = true
    }
}

/** Opens a plain, read-only viewer window for an already-received screenshot. */
fun openViewerWindow(image: BufferedImage) {
    SwingUtilities.invokeLater {
        val frame = JFrame("Screenshot — Secure Grab")
        frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        val panel = ComposePanel()
        frame.contentPane.add(panel)
        frame.setSize((image.width + 40).coerceIn(400, 1600), (image.height + 80).coerceIn(300, 1200))
        frame.setLocationRelativeTo(null)
        val bitmap = image.toComposeImageBitmap()
        panel.setContent {
            BossTheme {
                Box(
                    Modifier.fillMaxSize().padding(16.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                ) {
                    Image(BitmapPainter(bitmap), contentDescription = null)
                }
            }
        }
        frame.isVisible = true
    }
}

@Composable
private fun AnnotationEditor(
    capturedImage: BufferedImage,
    api: ScreenshotShareApi,
    scope: CoroutineScope,
    onCancel: () -> Unit,
    onSent: () -> Unit,
) {
    var tool by remember { mutableStateOf(DrawTool.PEN) }
    var color by remember { mutableStateOf(PALETTE.first()) }
    var strokeWidth by remember { mutableStateOf(4f) }
    var actions by remember { mutableStateOf(listOf<DrawAction>()) }
    var texts by remember { mutableStateOf(listOf<TextAnnotation>()) }
    var pendingTextAt by remember { mutableStateOf<Offset?>(null) }
    var pendingTextValue by remember { mutableStateOf("") }
    var showSendDialog by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var copyError by remember { mutableStateOf<String?>(null) }

    val bitmap = remember(capturedImage) { capturedImage.toComposeImageBitmap() }
    val density = LocalDensity.current

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(BossThemeColors.SurfaceColor)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToolButton(FeatherIcons.Edit3, "Pen", selected = tool == DrawTool.PEN) { tool = DrawTool.PEN }
                ToolButton(FeatherIcons.Square, "Rectangle", selected = tool == DrawTool.RECTANGLE) { tool = DrawTool.RECTANGLE }
                ToolButton(FeatherIcons.CornerUpRight, "Arrow", selected = tool == DrawTool.ARROW) { tool = DrawTool.ARROW }
                ToolButton(FeatherIcons.Type, "Text", selected = tool == DrawTool.TEXT) { tool = DrawTool.TEXT }
            }

            VerticalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                PALETTE.forEach { c -> ColorSwatch(c, selected = c == color) { color = c } }
            }

            VerticalDivider()

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Stroke", style = MaterialTheme.typography.caption, color = BossThemeColors.TextMuted)
                Slider(
                    value = strokeWidth,
                    onValueChange = { strokeWidth = it },
                    valueRange = 1f..16f,
                    modifier = Modifier.width(90.dp),
                )
                Text("${strokeWidth.toInt()}px", style = MaterialTheme.typography.caption, color = BossThemeColors.TextMuted)
            }

            VerticalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionIconButton(FeatherIcons.RotateCcw, "Undo", enabled = actions.isNotEmpty()) {
                    actions = actions.dropLast(1)
                }
                ActionIconButton(FeatherIcons.Trash2, "Clear", enabled = actions.isNotEmpty() || texts.isNotEmpty()) {
                    actions = emptyList()
                    texts = emptyList()
                }
            }

            VerticalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionIconButton(FeatherIcons.X, "Cancel", onClick = onCancel)
            }
        }

        (saveError ?: copyError)?.let {
            Text(
                it,
                color = BossThemeColors.ErrorColor,
                style = MaterialTheme.typography.caption,
                modifier = Modifier.fillMaxWidth().background(BossThemeColors.SurfaceColor)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        Divider(color = BossThemeColors.BorderColor)

        Box(
            Modifier.weight(1f).fillMaxWidth().background(BossThemeColors.BackgroundColor)
                .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center,
        ) {
            Box {
                AnnotationCanvas(
                    baseImage = bitmap,
                    tool = tool,
                    color = color,
                    strokeWidthPx = strokeWidth,
                    actions = actions,
                    onActionsChange = { actions = it },
                    onTextTap = { offset -> pendingTextAt = offset; pendingTextValue = "" },
                    modifier = with(density) {
                        Modifier.size(capturedImage.width.toDp(), capturedImage.height.toDp())
                    },
                )

                texts.forEach { t ->
                    Text(
                        t.text,
                        color = t.color,
                        modifier = with(density) { Modifier.offset(t.x.toDp(), t.y.toDp()) },
                    )
                }

                pendingTextAt?.let { at ->
                    Popup(offset = IntOffset(at.x.toInt(), at.y.toInt())) {
                        Surface(shape = RoundedCornerShape(8.dp), elevation = 4.dp) {
                            TextField(
                                value = pendingTextValue,
                                onValueChange = { pendingTextValue = it },
                                singleLine = true,
                                modifier = Modifier.width(180.dp),
                                trailingIcon = {
                                    IconButton(onClick = {
                                        if (pendingTextValue.isNotBlank()) {
                                            texts = texts + TextAnnotation(texts.size.toLong(), at.x, at.y, pendingTextValue, color)
                                        }
                                        pendingTextAt = null
                                    }) {
                                        Icon(FeatherIcons.Check, contentDescription = "Add text")
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        Divider(color = BossThemeColors.BorderColor)

        Row(
            Modifier.fillMaxWidth().background(BossThemeColors.SurfaceColor)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionIconButton(FeatherIcons.Save, "Save…") {
                    saveError = saveScreenshotToDisk(flattenAnnotations(capturedImage, actions, texts))
                }
                ActionIconButton(FeatherIcons.Copy, "Copy") {
                    copyError = copyScreenshotToClipboard(flattenAnnotations(capturedImage, actions, texts))
                }
                ActionIconButton(FeatherIcons.Send, "Send", filled = true) { showSendDialog = true }
            }
        }
    }

    if (showSendDialog) {
        SendDialog(
            api = api,
            sending = sending,
            error = errorText,
            onDismiss = { showSendDialog = false },
            onSend = { recipient, note ->
                sending = true
                errorText = null
                scope.launch {
                    val flattened = flattenAnnotations(capturedImage, actions, texts)
                    val bytes = ByteArrayOutputStream().also { ImageIO.write(flattened, "png", it) }.toByteArray()
                    val base64 = Base64.getEncoder().encodeToString(bytes)
                    api.shareScreenshot(
                        recipientId = recipient.userId,
                        imageBase64 = base64,
                        mimeType = "image/png",
                        width = flattened.width,
                        height = flattened.height,
                        note = note.ifBlank { null },
                    ).onSuccess {
                        sending = false
                        onSent()
                    }.onFailure {
                        sending = false
                        errorText = it.message ?: "Failed to send screenshot"
                    }
                }
            },
        )
    }
}

/**
 * Shows a native save-file dialog and writes [image] to the chosen path as PNG.
 * Returns an error message on failure, or `null` on success or user cancellation.
 */
private fun saveScreenshotToDisk(image: BufferedImage): String? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Save Screenshot"
        fileFilter = FileNameExtensionFilter("PNG Image", "png")
        selectedFile = File("screenshot.png")
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return null
    val chosen = chooser.selectedFile
    val file = if (chosen.name.endsWith(".png", ignoreCase = true)) chosen else File(chosen.parentFile, "${chosen.name}.png")
    return try {
        ImageIO.write(image, "png", file)
        null
    } catch (e: IOException) {
        e.message ?: "Failed to save screenshot"
    }
}

/** Copies [image] to the system clipboard. Returns an error message on failure, or `null`. */
private fun copyScreenshotToClipboard(image: BufferedImage): String? = try {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(ImageTransferable(image), null)
    null
} catch (e: HeadlessException) {
    e.message ?: "Failed to copy screenshot"
} catch (e: IllegalStateException) {
    e.message ?: "Failed to copy screenshot"
}

private class ImageTransferable(private val image: BufferedImage) : Transferable {
    override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)

    override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor

    override fun getTransferData(flavor: DataFlavor): Any {
        if (flavor != DataFlavor.imageFlavor) throw UnsupportedFlavorException(flavor)
        return image
    }
}

@Composable
private fun VerticalDivider() {
    Box(Modifier.width(1.dp).height(32.dp).background(BossThemeColors.BorderColor))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    TooltipArea(tooltip = { Tooltip(label) }) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (selected) BossThemeColors.AccentColor else Color.Transparent,
            modifier = Modifier.size(40.dp).clickable(onClick = onClick),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = if (selected) BossThemeColors.BackgroundColor else BossThemeColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionIconButton(icon: ImageVector, label: String, enabled: Boolean = true, filled: Boolean = false, onClick: () -> Unit) {
    TooltipArea(tooltip = { Tooltip(label) }) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (filled && enabled) BossThemeColors.AccentColor else Color.Transparent,
            border = if (filled) null else BorderStroke(1.dp, BossThemeColors.BorderColor),
            modifier = Modifier.size(36.dp).clickable(enabled = enabled, onClick = onClick),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    contentDescription = label,
                    tint = when {
                        filled && enabled -> BossThemeColors.BackgroundColor
                        enabled -> BossThemeColors.TextSecondary
                        else -> BossThemeColors.TextMuted
                    },
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun Tooltip(text: String) {
    Surface(shape = RoundedCornerShape(6.dp), color = BossThemeColors.SurfaceColor, elevation = 4.dp) {
        Text(
            text,
            style = MaterialTheme.typography.caption,
            color = BossThemeColors.TextPrimary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(26.dp)
            .background(color, shape = CircleShape)
            .border(1.dp, BossThemeColors.BorderColor, CircleShape)
            .then(if (selected) Modifier.border(2.dp, BossThemeColors.AccentColor, CircleShape) else Modifier)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun SendDialog(
    api: ScreenshotShareApi,
    sending: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSend: (Recipient, String) -> Unit,
) {
    var recipients by remember { mutableStateOf(listOf<Recipient>()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf<Recipient?>(null) }
    var note by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        api.listShareableRecipients()
            .onSuccess { recipients = it; loading = false }
            .onFailure { loadError = it.message; loading = false }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), elevation = 8.dp) {
            Column(Modifier.padding(20.dp).width(360.dp)) {
                Text("Send screenshot", style = MaterialTheme.typography.h6)
                Spacer(Modifier.height(16.dp))
                when {
                    loading -> CircularProgressIndicator()
                    loadError != null -> Text(loadError ?: "", color = BossThemeColors.ErrorColor)
                    recipients.isEmpty() -> Text("No teammates found in your organisations yet.")
                    else ->
                        LazyColumn(Modifier.heightIn(max = 240.dp)) {
                            items(recipients, key = { it.userId + it.orgId }) { r ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selected == r) BossThemeColors.AccentColor.copy(alpha = 0.12f) else Color.Transparent,
                                    modifier = Modifier.fillMaxWidth().clickable { selected = r },
                                ) {
                                    Row(
                                        Modifier.padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(selected = selected == r, onClick = { selected = r })
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(r.email)
                                            Text(r.orgName, style = MaterialTheme.typography.caption, color = BossThemeColors.TextSecondary)
                                        }
                                    }
                                }
                            }
                        }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = BossThemeColors.ErrorColor, modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = selected != null && !sending,
                        shape = RoundedCornerShape(8.dp),
                        onClick = { selected?.let { onSend(it, note) } },
                    ) {
                        if (sending) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(FeatherIcons.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Send")
                        }
                    }
                }
            }
        }
    }
}
