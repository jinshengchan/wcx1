// Adapted from AARCNoa/zhiyou, AiGroupDigestFeature.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.features.items.ai

import com.Johnny.wcx.ai.group.GroupDigestScheduler
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature

@Feature(
    name = "群聊总结",
    categories = ["API"],
    description = "按群聊设置生成摘要，在 AI 工作台中查看结果",
)
object AiGroupDigestFeature : ApiFeature() {
    override fun onEnable() = GroupDigestScheduler.onEnable()

    override fun onDisable() = GroupDigestScheduler.onDisable()
}
