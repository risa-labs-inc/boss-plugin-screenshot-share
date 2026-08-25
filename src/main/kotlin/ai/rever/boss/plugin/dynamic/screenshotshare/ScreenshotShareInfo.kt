package ai.rever.boss.plugin.dynamic.screenshotshare

import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.Panel.Companion.top
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import compose.icons.FeatherIcons
import compose.icons.feathericons.Camera

object ScreenshotShareInfo : PanelInfo {
    override val id = PanelId("screenshot-share", 60)
    override val displayName = "Screenshot Share"
    override val icon = FeatherIcons.Camera
    override val defaultSlotPosition = right.top
}
