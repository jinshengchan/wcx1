package com.Johnny.wcx.features.items.ai

import androidx.activity.ComponentActivity
import com.Johnny.wcx.activity.settings.ai.AiWorkbenchDialog
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature

@Feature(
    name = "AI 助手",
    categories = ["聊天"],
    description = "聊天 AI 工具、好友档案、群聊摘要与每日总结"
)
object AiWorkbench : ClickableFeature() {
    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) = AiWorkbenchDialog(context)
}
