package ai.rever.boss.plugin.dynamic.screenshotshare

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Tab
import androidx.compose.material.TabRow
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.ComponentContext
import kotlinx.coroutines.launch

class ScreenshotShareComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val pluginContext: PluginContext,
    private val viewModel: ScreenshotShareViewModel,
) : PanelComponentWithUI, ComponentContext by ctx {

    override fun onInitialized() {
        viewModel.startPolling()
    }

    @Composable
    override fun Content() {
        BossTheme {
            var tab by remember { mutableStateOf(0) }
            var capturing by remember { mutableStateOf(false) }
            val received by viewModel.received.collectAsState()
            val sent by viewModel.sent.collectAsState()
            val unread by viewModel.unreadCount.collectAsState()
            val error by viewModel.loadError.collectAsState()

            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Button(
                    enabled = !capturing,
                    onClick = {
                        capturing = true
                        val provider = pluginContext.screenCaptureProvider
                        pluginContext.pluginScope.launch {
                            if (provider != null && !provider.hasPermission()) {
                                provider.requestPermission()
                            }
                            val image = ScreenshotCapture.captureRegion()
                            capturing = false
                            if (image != null) {
                                openAnnotationWindow(
                                    api = ScreenshotShareApi(pluginContext),
                                    scope = pluginContext.pluginScope,
                                    capturedImage = image,
                                    onSent = { viewModel.refreshAsync() },
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (capturing) "Selecting region…" else "New Screenshot")
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
                        if (received.isEmpty()) {
                            EmptyState("No screenshots yet")
                        } else {
                            LazyColumn {
                                items(received, key = { it.id }) { item ->
                                    ReceivedRow(item) { viewModel.openReceived(item.id) }
                                }
                            }
                        }
                    } else {
                        if (sent.isEmpty()) {
                            EmptyState("You haven't sent anything yet")
                        } else {
                            LazyColumn {
                                items(sent, key = { it.id }) { item -> SentRow(item) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message, color = BossThemeColors.TextPrimary)
    }
}

@Composable
private fun ReceivedRow(item: ReceivedScreenshot, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.isUnread) {
                Box(Modifier.size(8.dp).background(Color(0xFF0A84FF), CircleShape))
                Spacer(Modifier.width(6.dp))
            }
            Text(item.senderEmail, style = MaterialTheme.typography.subtitle2, color = BossThemeColors.TextPrimary)
        }
        item.note?.let { Text(it, style = MaterialTheme.typography.caption, color = BossThemeColors.TextPrimary) }
        Text(item.createdAt, style = MaterialTheme.typography.caption, color = BossThemeColors.TextPrimary)
    }
    Divider()
}

@Composable
private fun SentRow(item: SentScreenshot) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            "To ${item.recipientEmail}" + if (item.readAt != null) " · read" else " · unread",
            style = MaterialTheme.typography.subtitle2,
            color = BossThemeColors.TextPrimary,
        )
        item.note?.let { Text(it, style = MaterialTheme.typography.caption, color = BossThemeColors.TextPrimary) }
        Text(item.createdAt, style = MaterialTheme.typography.caption, color = BossThemeColors.TextPrimary)
    }
    Divider()
}
