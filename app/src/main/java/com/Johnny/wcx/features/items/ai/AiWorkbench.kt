package com.Johnny.wcx.features.items.ai

import androidx.activity.ComponentActivity
import com.Johnny.wcx.activity.settings.ai.AiWorkbenchDialog
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature

@Feature(
    name = "AI 工作台",
    categories = ["聊天"],
    description = "按所选会话整理好友档案和群聊摘要，管理模型与定时摘要"
)
object AiWorkbench : ClickableFeature() {
    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) = AiWorkbenchDialog(context)
}
