package ai.rever.boss.plugin.dynamic.screenshotshare

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.SwingUtilities

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
        val frame = JFrame("Annotate Screenshot")
        frame.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        val panel = ComposePanel()
        frame.contentPane.add(panel)
        frame.setSize(
            (capturedImage.width + 220).coerceIn(640, 1600),
            (capturedImage.height + 160).coerceIn(480, 1200),
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
        val frame = JFrame("Screenshot")
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

    val bitmap = remember(capturedImage) { capturedImage.toComposeImageBitmap() }
    val density = LocalDensity.current

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxHeight().width(200.dp).background(Color(0xFF2A2A2E)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Tool", color = BossThemeColors.TextPrimary, style = MaterialTheme.typography.subtitle2)
            DrawTool.entries.forEach { t -> ToolRow(t, selected = tool == t) { tool = t } }

            Spacer(Modifier.height(8.dp))
            Text("Color", color = BossThemeColors.TextPrimary, style = MaterialTheme.typography.subtitle2)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PALETTE.forEach { c -> ColorSwatch(c, selected = c == color) { color = c } }
            }

            Spacer(Modifier.height(8.dp))
            Text("Stroke: ${strokeWidth.toInt()}px", color = BossThemeColors.TextPrimary)
            Slider(value = strokeWidth, onValueChange = { strokeWidth = it }, valueRange = 1f..16f)

            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = { if (actions.isNotEmpty()) actions = actions.dropLast(1) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Undo") }
            OutlinedButton(
                onClick = { actions = emptyList(); texts = emptyList() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Clear") }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            Button(onClick = { showSendDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Send…") }
        }

        Box(
            Modifier.weight(1f).fillMaxHeight().background(Color(0xFF1C1C1E))
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
                        Surface(elevation = 4.dp) {
                            TextField(
                                value = pendingTextValue,
                                onValueChange = { pendingTextValue = it },
                                singleLine = true,
                                modifier = Modifier.width(180.dp),
                                trailingIcon = {
                                    TextButton(onClick = {
                                        if (pendingTextValue.isNotBlank()) {
                                            texts = texts + TextAnnotation(texts.size.toLong(), at.x, at.y, pendingTextValue, color)
                                        }
                                        pendingTextAt = null
                                    }) { Text("Add") }
                                },
                            )
                        }
                    }
                }
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

@Composable
private fun ToolRow(tool: DrawTool, selected: Boolean, onClick: () -> Unit) {
    val label = when (tool) {
        DrawTool.PEN -> "Pen"
        DrawTool.RECTANGLE -> "Rectangle"
        DrawTool.ARROW -> "Arrow"
        DrawTool.TEXT -> "Text"
    }
    Surface(
        color = if (selected) MaterialTheme.colors.primary.copy(alpha = 0.25f) else Color.Transparent,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Text(label, modifier = Modifier.padding(8.dp), color = BossThemeColors.TextPrimary)
    }
}

@Composable
private fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(24.dp)
            .background(color, shape = CircleShape)
            .then(if (selected) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
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
        Surface(elevation = 8.dp) {
            Column(Modifier.padding(16.dp).width(360.dp)) {
                Text("Send screenshot", style = MaterialTheme.typography.h6)
                Spacer(Modifier.height(12.dp))
                when {
                    loading -> CircularProgressIndicator()
                    loadError != null -> Text(loadError ?: "", color = Color.Red)
                    recipients.isEmpty() -> Text("No teammates found in your organisations yet.")
                    else ->
                        LazyColumn(Modifier.heightIn(max = 240.dp)) {
                            items(recipients, key = { it.userId + it.orgId }) { r ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { selected = r }.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = selected == r, onClick = { selected = r })
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(r.email)
                                        Text(r.orgName, style = MaterialTheme.typography.caption)
                                    }
                                }
                            }
                        }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = Color.Red, modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = selected != null && !sending, onClick = { selected?.let { onSend(it, note) } }) {
                        if (sending) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Send")
                        }
                    }
                }
            }
        }
    }
}
