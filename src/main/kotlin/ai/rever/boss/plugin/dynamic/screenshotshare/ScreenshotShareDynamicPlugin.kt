package ai.rever.boss.plugin.dynamic.screenshotshare

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext

class ScreenshotShareDynamicPlugin : DynamicPlugin {
    override val pluginId = "ai.rever.boss.plugin.dynamic.screenshotshare"
    override val displayName = "Secure Grab"
    override val version = "0.1.0"
    override val description = "Capture, annotate, and send screenshots to teammates in your BOSS organisation."
    override val author = "Your Name"
    override val url = "https://github.com/risa-labs-inc/boss-plugin-screenshot-share"

    override fun register(context: PluginContext) {
        val api = ScreenshotShareApi(context)
        val viewModel = ScreenshotShareViewModel(context, api, context.pluginScope)

        context.panelRegistry.registerPanel(ScreenshotShareInfo) { ctx, panelInfo ->
            ScreenshotShareComponent(ctx, panelInfo, context, viewModel)
        }
    }

    override fun dispose() {
        // pluginScope is cancelled by the host on unload, which stops the polling
        // loop; nothing else here holds a resource that outlives the plugin.
    }
}
