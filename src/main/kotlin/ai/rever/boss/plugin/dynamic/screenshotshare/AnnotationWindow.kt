package ai.rever.boss.plugin.dynamic.screenshotshare

import ai.rever.boss.plugin.ui.BossCard
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossEmptyState
import ai.rever.boss.plugin.ui.BossPrimaryButton
import ai.rever.boss.plugin.ui.BossSearchBar
import ai.rever.boss.plugin.ui.BossSecondaryButton
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Slider
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Copy
import compose.icons.feathericons.CornerUpRight
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff
import compose.icons.feathericons.Lock
import compose.icons.feathericons.RotateCcw
import compose.icons.feathericons.Save
import compose.icons.feathericons.Send
import compose.icons.feathericons.Square
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Type
import compose.icons.feathericons.Users
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
    onSent: (Int) -> Unit,
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

        // Must run before the frame is realized (setVisible below): the native NSWindow
        // picks up its title-bar chrome at peer-creation time, so setting this property
        // from a Compose SideEffect -- which only fires once the first frame has composed,
        // i.e. after the peer already exists -- left the title bar stuck on macOS's default
        // light chrome regardless of app theme.
        if (System.getProperty("os.name").lowercase().contains("mac")) {
            // boss-plugin-api's BossTheme is dark-mode-only today - no light-mode API is
            // exposed to plugins - so the title bar always matches the dark chrome below.
            frame.rootPane.putClientProperty(
                "apple.awt.windowAppearance",
                "NSAppearanceNameDarkAqua",
            )
        }

        panel.setContent {
            BossTheme {
                AnnotationEditor(
                    capturedImage = capturedImage,
                    api = api,
                    scope = scope,
                    onCancel = { frame.dispose() },
                    onSent = { count ->
                        frame.dispose()
                        onSent(count)
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
    onSent: (Int) -> Unit,
) {
    var tool by remember { mutableStateOf(DrawTool.PEN) }
    var color by remember { mutableStateOf(PALETTE.first()) }
    var rainbow by remember { mutableStateOf(false) }
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
    // Deliberately lazy, not a remember(actions, texts): each call allocates a
    // full-size ARGB copy (~59MB for a 5K grab), so it runs on an explicit
    // Save/Copy/Send click rather than after every stroke.
    fun flattened() = flattenAnnotations(capturedImage, actions, texts)

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
                PALETTE.forEach { c -> ColorSwatch(c, selected = c == color && !rainbow) { color = c; rainbow = false } }
                RainbowSwatch(selected = rainbow) { rainbow = true }
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
                    rainbow = rainbow,
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
                                onValueChange = { if (it.length <= MAX_TEXT_LENGTH) pendingTextValue = it },
                                singleLine = true,
                                modifier = Modifier.width(180.dp),
                                trailingIcon = {
                                    IconButton(onClick = {
                                        if (pendingTextValue.isNotBlank() && texts.size < MAX_TEXTS) {
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
                    saveError = saveScreenshotToDisk(flattened())
                }
                ActionIconButton(FeatherIcons.Copy, "Copy") {
                    copyError = copyScreenshotToClipboard(flattened())
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
            onSend = { recipients, note, password ->
                sending = true
                errorText = null
                scope.launch {
                    val flattenedImage = flattened()
                    val bytes = ByteArrayOutputStream().also { ImageIO.write(flattenedImage, "png", it) }.toByteArray()
                    // Checked before base64 (which inflates ~1.33x, and ~2.66x again as a
                    // UTF-16 String) so an oversized capture fails here instead of after a
                    // long upload the server was always going to reject.
                    if (bytes.size > MAX_IMAGE_BYTES) {
                        sending = false
                        val mb = bytes.size / (1024.0 * 1024.0)
                        errorText = "Screenshot is too large to send (%.1f MB of %d MB) — capture a smaller region"
                            .format(mb, MAX_IMAGE_BYTES / (1024 * 1024))
                        return@launch
                    }
                    // Encoded once and reused: share_screenshot takes a single
                    // recipient, so a fan-out is N calls but must not be N encodes.
                    val base64 = Base64.getEncoder().encodeToString(bytes)
                    val failed = mutableListOf<String>()
                    for (recipient in recipients) {
                        api.shareScreenshot(
                            recipientId = recipient.userId,
                            imageBase64 = base64,
                            mimeType = "image/png",
                            width = flattenedImage.width,
                            height = flattenedImage.height,
                            note = note.ifBlank { null },
                            password = password,
                        ).onFailure { failed += recipient.displayName }
                    }
                    sending = false
                    when {
                        // Partial success is reported rather than swallowed: the
                        // recipients who did receive it keep their copy, so silently
                        // succeeding would leave the sender unaware of the gap.
                        failed.isEmpty() -> onSent(recipients.size)
                        failed.size == recipients.size ->
                            errorText = "Failed to send to ${failed.joinToString(", ")}"
                        else ->
                            errorText = "Sent to ${recipients.size - failed.size} of ${recipients.size} — failed for ${failed.joinToString(", ")}"
                    }
                }
            },
        )
    }
}

private val FRUIT_NAMES = listOf(
    "apple", "banana", "cherry", "dragonfruit", "elderberry", "fig", "grape", "honeydew",
    "kiwi", "lemon", "mango", "nectarine", "orange", "papaya", "quince", "raspberry",
    "starfruit", "tangerine", "watermelon",
)

/**
 * Shows a native save-file dialog and writes [image] to the chosen path as PNG.
 * Returns an error message on failure, or `null` on success or user cancellation.
 */
private fun saveScreenshotToDisk(image: BufferedImage): String? {
    val chooser = JFileChooser().apply {
        dialogTitle = "Save Screenshot"
        fileFilter = FileNameExtensionFilter("PNG Image", "png")
        selectedFile = File("${FRUIT_NAMES.random()}.png")
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

/** Shared shape behind [ToolButton] and [ActionIconButton]: a tooltipped, tappable square. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolbarIconTile(
    icon: ImageVector,
    label: String,
    size: Dp,
    iconSize: Dp,
    background: Color,
    tint: Color,
    border: BorderStroke?,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TooltipArea(tooltip = { Tooltip(label) }) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = background,
            border = border,
            modifier = Modifier.size(size).clickable(enabled = enabled, onClick = onClick),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(iconSize))
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    ToolbarIconTile(
        icon = icon,
        label = label,
        size = 40.dp,
        iconSize = 18.dp,
        background = if (selected) BossThemeColors.AccentColor else Color.Transparent,
        tint = if (selected) BossThemeColors.BackgroundColor else BossThemeColors.TextSecondary,
        border = null,
        onClick = onClick,
    )
}

@Composable
private fun ActionIconButton(icon: ImageVector, label: String, enabled: Boolean = true, filled: Boolean = false, onClick: () -> Unit) {
    ToolbarIconTile(
        icon = icon,
        label = label,
        size = 36.dp,
        iconSize = 16.dp,
        background = if (filled && enabled) BossThemeColors.AccentColor else Color.Transparent,
        tint = when {
            filled && enabled -> BossThemeColors.BackgroundColor
            enabled -> BossThemeColors.TextSecondary
            else -> BossThemeColors.TextMuted
        },
        border = if (filled) null else BorderStroke(1.dp, BossThemeColors.BorderColor),
        enabled = enabled,
        onClick = onClick,
    )
}

/** Small tooltip bubble, shared with [ScreenshotShareComponent]'s capture buttons. */
@Composable
internal fun Tooltip(text: String) {
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

/** Palette entry for [DrawTool.PEN]'s rainbow mode -- see [DrawAction.isRainbow]. */
@Composable
private fun RainbowSwatch(selected: Boolean, onClick: () -> Unit) {
    val brush = remember {
        Brush.sweepGradient(
            listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
        )
    }
    Box(
        Modifier
            .size(26.dp)
            .background(brush, shape = CircleShape)
            .border(1.dp, BossThemeColors.BorderColor, CircleShape)
            .then(if (selected) Modifier.border(2.dp, BossThemeColors.AccentColor, CircleShape) else Modifier)
            .clickable(onClick = onClick),
    )
}

/** Beyond this many recipients the list needs a filter to be usable. */
private const val RECIPIENT_SEARCH_THRESHOLD = 8

@Composable
private fun SendDialog(
    api: ScreenshotShareApi,
    sending: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSend: (List<Recipient>, String, String?) -> Unit,
) {
    var recipients by remember { mutableStateOf(listOf<Recipient>()) }
    var loading by remember { mutableStateOf(true) }
    // Keyed by userId, not the Recipient itself: list_shareable_recipients
    // returns one row per user, but org_id rides along on it, so identity
    // comparison would let the same person be picked twice.
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    var message by remember { mutableStateOf("") }
    var secure by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        api.listShareableRecipients()
            .onSuccess { recipients = it; loading = false }
            .onFailure { loadError = it.message; loading = false }
    }

    // Filtered client-side: the server already caps this list at p_limit (50), so
    // there is nothing to gain from a debounced RPC per keystroke. Past that cap
    // the server-side p_query would be needed -- it exists and now matches
    // display names as well as emails, but wiring it means per-keystroke calls.
    val visible = remember(recipients, query) {
        if (query.isBlank()) {
            recipients
        } else {
            recipients.filter {
                it.displayName.contains(query, ignoreCase = true) ||
                    it.email.contains(query, ignoreCase = true)
            }
        }
    }

    // Ticking Secure and leaving the field empty would otherwise send an
    // unprotected screenshot to someone the sender believes has to unlock it.
    val passwordMissing = secure && password.isBlank()

    BossDialog(onDismissRequest = onDismiss) {
        // Width on a wrapper: BossCard applies fillMaxWidth() after the modifier
        // it is handed, so sizing it directly would be overridden.
        Box(Modifier.width(420.dp)) {
            BossCard {
                SendDialogHeader(selectedCount = selectedIds.size, onClose = onDismiss)

                Spacer(Modifier.height(14.dp))
                Divider(color = BossThemeColors.BorderColor.copy(alpha = 0.6f))
                Spacer(Modifier.height(14.dp))

                SectionLabel("RECIPIENTS") {
                    if (selectedIds.isNotEmpty()) {
                        Text(
                            "Clear",
                            color = BossThemeColors.TextSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { selectedIds = emptySet() }
                                .padding(horizontal = 4.dp, vertical = 2.dp),
                        )
                    }
                }

                if (recipients.size > RECIPIENT_SEARCH_THRESHOLD) {
                    BossSearchBar(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = "Search people...",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }

                when {
                    loading ->
                        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    loadError != null ->
                        Text(loadError ?: "", color = BossThemeColors.ErrorColor, fontSize = 12.sp)
                    recipients.isEmpty() ->
                        BossEmptyState(
                            icon = FeatherIcons.Users,
                            message = "No teammates yet",
                            description = "You can only send to people who share an organisation with you.",
                        )
                    visible.isEmpty() ->
                        Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                            Text("No one matches \"$query\"", color = BossThemeColors.TextMuted, fontSize = 12.sp)
                        }
                    else ->
                        LazyColumn(Modifier.heightIn(max = 208.dp)) {
                            items(visible, key = { it.userId }) { r ->
                                RecipientRow(
                                    recipient = r,
                                    checked = r.userId in selectedIds,
                                    onToggle = {
                                        selectedIds = if (r.userId in selectedIds) {
                                            selectedIds - r.userId
                                        } else {
                                            selectedIds + r.userId
                                        }
                                    },
                                )
                            }
                        }
                }

                Spacer(Modifier.height(16.dp))

                SectionLabel("MESSAGE") {
                    Text(
                        "${message.length}/$MAX_NOTE_LENGTH",
                        color = BossThemeColors.TextMuted,
                        fontSize = 10.sp,
                    )
                }
                FlatField(
                    value = message,
                    // 500 is the screenshot_shares_note_length CHECK -- enforced here so a
                    // long message is a dropped keystroke, not a failed send.
                    onValueChange = { if (it.length <= MAX_NOTE_LENGTH) message = it },
                    placeholder = "Write a message...",
                    singleLine = false,
                    minLines = 3,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(14.dp))

                SecureSection(
                    secure = secure,
                    onSecureChange = { secure = it },
                    password = password,
                    onPasswordChange = { if (it.length <= MAX_PASSWORD_LENGTH) password = it },
                    showPassword = showPassword,
                    onToggleShowPassword = { showPassword = !showPassword },
                )

                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = BossThemeColors.ErrorColor, fontSize = 12.sp)
                }

                Spacer(Modifier.height(16.dp))
                Divider(color = BossThemeColors.BorderColor.copy(alpha = 0.6f))
                Spacer(Modifier.height(12.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (passwordMissing) {
                        Text(
                            "Enter a password to continue",
                            color = BossThemeColors.TextMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    BossSecondaryButton(text = "Cancel", onClick = onDismiss)
                    Spacer(Modifier.width(8.dp))
                    if (sending) {
                        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        BossPrimaryButton(
                            text = if (selectedIds.size > 1) "Send (${selectedIds.size})" else "Send",
                            enabled = selectedIds.isNotEmpty() && !passwordMissing,
                            icon = FeatherIcons.Send,
                            onClick = {
                                onSend(
                                    // Filtered from `recipients`, not `visible`: a search
                                    // term left in the box must not silently drop someone
                                    // already ticked.
                                    recipients.filter { it.userId in selectedIds },
                                    message,
                                    // Gated on `secure`, so unticking genuinely removes
                                    // protection even though the typed text is kept.
                                    if (secure) password.ifBlank { null } else null,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SendDialogHeader(selectedCount: Int, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // AccentColor is a fill, never a glyph colour -- it fails 4.5:1 as text.
        // A wash plus a TextPrimary icon is the house way to emphasise.
        Box(
            Modifier
                .size(34.dp)
                .background(BossThemeColors.AccentColor.copy(alpha = 0.16f), RoundedCornerShape(9.dp))
                .border(1.dp, BossThemeColors.AccentColor.copy(alpha = 0.45f), RoundedCornerShape(9.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(FeatherIcons.Send, null, Modifier.size(15.dp), tint = BossThemeColors.TextPrimary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "Send screenshot",
                color = BossThemeColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (selectedCount == 0) "Select who to send to" else "$selectedCount selected",
                color = BossThemeColors.TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(26.dp)) {
            Icon(FeatherIcons.X, "Close", Modifier.size(15.dp), tint = BossThemeColors.TextMuted)
        }
    }
}

/** Small caps-ish group label, with an optional trailing action or counter. */
@Composable
private fun SectionLabel(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = BossThemeColors.TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.8.sp,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

@Composable
private fun RecipientRow(recipient: Recipient, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            // 0.16f is the house selection wash (plugin-manager's OrgFilterRow).
            .background(if (checked) BossThemeColors.AccentColor.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(recipient.displayName)
        Spacer(Modifier.width(10.dp))
        Text(
            recipient.displayName,
            color = if (checked) BossThemeColors.TextPrimary else BossThemeColors.TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = BossThemeColors.AccentColor,
                uncheckedColor = BossThemeColors.TextSecondary,
            ),
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * Initials tile for a recipient, giving the rows a rail to scan down.
 *
 * Deliberately not colour-coded per person, mirroring the organisation plugin's
 * Monogram: there is one accent token, so a per-user colour would have to be
 * invented outside the palette.
 */
@Composable
private fun Monogram(displayName: String) {
    Box(
        Modifier
            .size(26.dp)
            .background(BossThemeColors.BackgroundColor, CircleShape)
            .border(1.dp, BossThemeColors.BorderColor, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initialsOf(displayName),
            color = BossThemeColors.TextSecondary,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * The (up to) two characters to show for a display name: one per word for
 * "Ada Lovelace", the first two for a mononym. Internal so the character
 * choice -- the part with rules -- is testable without a composition.
 */
internal fun initialsOf(displayName: String): String {
    val parts = displayName.trim().split(' ', '\t').filter { it.isNotBlank() }
    return when {
        parts.isEmpty() -> "??"
        parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}".uppercase()
        parts[0].length >= 2 -> parts[0].take(2).uppercase()
        else -> "${parts[0].first().uppercaseChar()}."
    }
}

/**
 * The opt-in password gate: a checkbox row that reveals the field only once
 * ticked, so an ordinary send is not shaped like it wants a password.
 *
 * Unticking keeps whatever was typed -- the value is gated on [secure] at the
 * send call site, not cleared here, so an accidental toggle costs nothing.
 */
@Composable
private fun SecureSection(
    secure: Boolean,
    onSecureChange: (Boolean) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    showPassword: Boolean,
    onToggleShowPassword: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(BossThemeColors.SurfaceColor)
            .border(
                1.dp,
                if (secure) BossThemeColors.AccentColor.copy(alpha = 0.45f) else BossThemeColors.BorderColor,
                RoundedCornerShape(6.dp),
            ),
    ) {
        // Layout mirrors BossToggle so this reads as the same family, but with a
        // Checkbox rather than its Switch -- it sits directly under the recipient
        // checkboxes and should match them.
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onSecureChange(!secure) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                FeatherIcons.Lock,
                null,
                Modifier.size(14.dp),
                tint = if (secure) BossThemeColors.TextPrimary else BossThemeColors.TextMuted,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Secure with password", color = BossThemeColors.TextPrimary, fontSize = 13.sp)
                Text(
                    "Recipients enter it to open the screenshot",
                    color = BossThemeColors.TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Checkbox(
                checked = secure,
                onCheckedChange = onSecureChange,
                colors = CheckboxDefaults.colors(
                    checkedColor = BossThemeColors.AccentColor,
                    uncheckedColor = BossThemeColors.TextSecondary,
                ),
                modifier = Modifier.size(18.dp),
            )
        }

        AnimatedVisibility(visible = secure) {
            Column {
                Divider(color = BossThemeColors.BorderColor.copy(alpha = 0.6f))
                FlatField(
                    value = password,
                    onValueChange = onPasswordChange,
                    placeholder = "Password",
                    visualTransformation =
                        if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
                        Icon(
                            if (showPassword) FeatherIcons.EyeOff else FeatherIcons.Eye,
                            contentDescription = "Toggle password visibility",
                            modifier = Modifier
                                .size(15.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .clickable(onClick = onToggleShowPassword),
                            tint = BossThemeColors.TextMuted,
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
            }
        }
    }
}

/**
 * Flat text field matching BossTextArea's box treatment (SurfaceColor fill, 6.dp
 * radius, BorderColor border, 13.sp text, accent cursor, muted placeholder).
 *
 * Hand-rolled rather than reusing BossTextField/BossTextArea because this dialog
 * needs the label and a character counter on one row -- those components always
 * draw their own label, which would leave a gap -- and the password variant
 * needs a visual transformation plus a trailing icon, which neither exposes.
 */
@Composable
private fun FlatField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = Int.MAX_VALUE,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null,
) {
    // Same height arithmetic BossTextArea uses, so a 3-line field here and one
    // there are the same size.
    val minHeight = (minLines * 20 + 16).dp
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        maxLines = maxLines,
        visualTransformation = visualTransformation,
        textStyle = TextStyle(color = BossThemeColors.TextPrimary, fontSize = 13.sp),
        cursorBrush = SolidColor(BossThemeColors.AccentColor),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .background(BossThemeColors.SurfaceColor, RoundedCornerShape(6.dp))
                    .border(1.dp, BossThemeColors.BorderColor, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            color = BossThemeColors.TextMuted.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                        )
                    }
                    inner()
                }
                trailing?.let {
                    Spacer(Modifier.width(8.dp))
                    it()
                }
            }
        },
        modifier = modifier,
    )
}
