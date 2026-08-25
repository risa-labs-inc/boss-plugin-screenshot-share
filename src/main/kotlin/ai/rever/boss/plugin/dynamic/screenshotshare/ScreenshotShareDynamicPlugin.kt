package ai.rever.boss.plugin.dynamic.screenshotshare

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.KeyChordSpec
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.PluginShortcutSpec
import ai.rever.boss.plugin.api.ShortcutActionProvider

private const val ACTION_CAPTURE_REGION = "plugin.ai.rever.boss.plugin.dynamic.screenshotshare.captureRegion"
private const val ACTION_CAPTURE_FULL_SCREEN = "plugin.ai.rever.boss.plugin.dynamic.screenshotshare.captureFullScreen"

class ScreenshotShareDynamicPlugin : DynamicPlugin, ShortcutActionProvider {
    override val pluginId = "ai.rever.boss.plugin.dynamic.screenshotshare"
    override val displayName = "Secure Grab"
    override val version = "0.1.0"
    override val description = "Capture, annotate, and send screenshots to teammates in your BOSS organisation."
    override val author = "Your Name"
    override val url = "https://github.com/risa-labs-inc/boss-plugin-screenshot-share"

    override val providerId = pluginId

    private var viewModel: ScreenshotShareViewModel? = null

    override fun register(context: PluginContext) {
        val api = ScreenshotShareApi(context)
        val vm = ScreenshotShareViewModel(context, api, context.pluginScope)
        viewModel = vm

        context.panelRegistry.registerPanel(ScreenshotShareInfo) { ctx, panelInfo ->
            ScreenshotShareComponent(ctx, panelInfo, vm)
        }
        context.registerShortcutActionProvider(this)
    }

    // Cmd (Ctrl on Windows/Linux) + Shift so the interceptor's modifier gate lets
    // these through; "C"/"M" echo the panel's Crop/Monitor icons and land on keys
    // no built-in keymap preset claims.
    override fun shortcuts(): List<PluginShortcutSpec> = listOf(
        PluginShortcutSpec(
            actionId = ACTION_CAPTURE_REGION,
            displayName = "New Screenshot (Region)",
            description = "Select a region of the screen to capture and annotate.",
            defaultBinding = KeyChordSpec(key = "C", modifiers = setOf("Cmd", "Shift")),
        ),
        PluginShortcutSpec(
            actionId = ACTION_CAPTURE_FULL_SCREEN,
            displayName = "New Screenshot (Full Screen)",
            description = "Capture the entire screen and annotate it.",
            defaultBinding = KeyChordSpec(key = "M", modifiers = setOf("Cmd", "Shift")),
        ),
    )

    override fun onAction(actionId: String, windowId: String?) {
        when (actionId) {
            ACTION_CAPTURE_REGION -> viewModel?.captureRegion()
            ACTION_CAPTURE_FULL_SCREEN -> viewModel?.captureFullScreen()
        }
    }

    override fun dispose() {
        // pluginScope is cancelled by the host on unload, which stops the polling
        // loop; the shortcut provider is unregistered by the host's own plugin
        // lifecycle tracking, same as every other registerXxx call here.
        viewModel = null
    }
}
