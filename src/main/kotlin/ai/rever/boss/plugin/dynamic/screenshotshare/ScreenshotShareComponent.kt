package ai.rever.boss.plugin.dynamic.screenshotshare

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.arkivanov.decompose.ComponentContext
import compose.icons.FeatherIcons
import compose.icons.feathericons.Crop
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff
import compose.icons.feathericons.Lock
import compose.icons.feathericons.Monitor

// Mirrors the default bindings registered in ScreenshotShareDynamicPlugin.shortcuts() -- shown
// as a hint only, so it doesn't track a user's rebind/unbind of those shortcuts in Settings.
private val CAPTURE_REGION_SHORTCUT = if (isMacOs()) "⌘⇧C" else "Ctrl+Shift+C"
private val CAPTURE_FULL_SCREEN_SHORTCUT = if (isMacOs()) "⌘⇧M" else "Ctrl+Shift+M"
private fun isMacOs() = System.getProperty("os.name").lowercase().contains("mac")

class ScreenshotShareComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val viewModel: ScreenshotShareViewModel,
) : PanelComponentWithUI, ComponentContext by ctx {

    override fun onInitialized() {
        viewModel.startPolling()
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    override fun Content() {
        BossTheme {
            var tab by remember { mutableStateOf(0) }
            val capturing by viewModel.capturing.collectAsState()
            val received by viewModel.received.collectAsState()
            val sent by viewModel.sent.collectAsState()
            val unread by viewModel.unreadCount.collectAsState()
            val error by viewModel.loadError.collectAsState()
            val passwordPrompt by viewModel.passwordPrompt.collectAsState()

            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TooltipArea(
                        tooltip = { Tooltip(if (capturing) "Selecting…" else "New Screenshot: select a region of your screen to capture") },
                        modifier = Modifier.weight(1f),
                    ) {
                        Button(
                            enabled = !capturing,
                            onClick = { viewModel.captureRegion() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(FeatherIcons.Crop, contentDescription = "New Screenshot", modifier = Modifier.size(16.dp))
                        }
                    }
                    TooltipArea(
                        tooltip = { Tooltip("Full Screen: capture your entire screen") },
                        modifier = Modifier.weight(1f),
                    ) {
                        Button(
                            enabled = !capturing,
                            onClick = { viewModel.captureFullScreen() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(FeatherIcons.Monitor, contentDescription = "Full Screen", modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }) {
                        Text("Inbox" + if (unread > 0) " ($unread)" else "", modifier = Modifier.padding(8.dp))
                    }
                    Tab(selected = tab == 1, onClick = { tab = 1 }) {
                        Text("Sent", modifier = Modifier.padding(8.dp))
                    }
                }

                error?.let { Text(it, color = Color.Red, modifier = Modifier.padding(top = 8.dp)) }

                Box(Modifier.fillMaxSize()) {
                    if (tab == 0) {
                        ScreenshotList(
                            received,
                            "No screenshots yet",
                            emptySubtext = "$CAPTURE_REGION_SHORTCUT to capture a region, $CAPTURE_FULL_SCREEN_SHORTCUT for the full screen",
                            key = { it.id },
                        ) { item ->
                            ReceivedRow(item) { viewModel.openReceived(item.id) }
                        }
                    } else {
                        ScreenshotList(sent, "You haven't sent anything yet", key = { it.id }) { item ->
                            SentRow(item)
                        }
                    }
                }
            }

            passwordPrompt?.let { prompt ->
                PasswordPromptDialog(
                    error = prompt.errorMessage,
                    onDismiss = { viewModel.dismissPasswordPrompt() },
                    onSubmit = { password -> viewModel.submitPassword(prompt.shareId, password) },
                )
            }
        }
    }
}

@Composable
private fun PasswordPromptDialog(error: String?, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp), elevation = 8.dp) {
            Column(Modifier.padding(20.dp).width(320.dp)) {
                Text("Password required", style = MaterialTheme.typography.h6)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) FeatherIcons.EyeOff else FeatherIcons.Eye, contentDescription = "Toggle password visibility")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = BossThemeColors.ErrorColor, modifier = Modifier.padding(top = 8.dp)) }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = password.isNotEmpty(), onClick = { onSubmit(password) }) { Text("Unlock") }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(message: String, subtext: String? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, color = BossThemeColors.TextPrimary)
            subtext?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.caption,
                    color = BossThemeColors.TextMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Inbox/Sent tab body: an empty-state message (with an optional [emptySubtext]), or the list keyed by [key]. */
@Composable
private fun <T> ScreenshotList(
    items: List<T>,
    emptyMessage: String,
    emptySubtext: String? = null,
    key: (T) -> Any,
    row: @Composable (T) -> Unit,
) {
    if (items.isEmpty()) {
        EmptyState(emptyMessage, emptySubtext)
    } else {
        LazyColumn { items(items, key = key) { row(it) } }
    }
}

/** Shared layout for an inbox/sent list entry: a headline, an optional note, the timestamp, then a divider. */
@Composable
private fun ScreenshotRow(note: String?, createdAt: String, onClick: (() -> Unit)? = null, headline: @Composable () -> Unit) {
    val rowModifier = Modifier.fillMaxWidth()
        .let { if (onClick != null) it.clickable(onClick = onClick) else it }
        .padding(vertical = 8.dp)
    Column(rowModifier) {
        headline()
        note?.let { Text(it, style = MaterialTheme.typography.caption, color = BossThemeColors.TextPrimary) }
        Text(createdAt, style = MaterialTheme.typography.caption, color = BossThemeColors.TextPrimary)
    }
    Divider()
}

@Composable
private fun ReceivedRow(item: ReceivedScreenshot, onClick: () -> Unit) {
    ScreenshotRow(note = item.note, createdAt = item.createdAt, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.isUnread) {
                Box(Modifier.size(8.dp).background(Color(0xFF0A84FF), CircleShape))
                Spacer(Modifier.width(6.dp))
            }
            Text(item.senderEmail, style = MaterialTheme.typography.subtitle2, color = BossThemeColors.TextPrimary)
            if (item.hasPassword) {
                Spacer(Modifier.width(6.dp))
                Icon(FeatherIcons.Lock, contentDescription = "Password protected", modifier = Modifier.size(12.dp), tint = BossThemeColors.TextMuted)
            }
        }
    }
}

@Composable
private fun SentRow(item: SentScreenshot) {
    ScreenshotRow(note = item.note, createdAt = item.createdAt) {
        Text(
            "To ${item.recipientEmail}" + if (item.readAt != null) " · read" else " · unread",
            style = MaterialTheme.typography.subtitle2,
            color = BossThemeColors.TextPrimary,
        )
    }
}
