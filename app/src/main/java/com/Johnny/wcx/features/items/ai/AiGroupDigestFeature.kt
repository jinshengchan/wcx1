// Adapted from AARCNoa/zhiyou, AiGroupDigestFeature.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.features.items.ai

import com.Johnny.wcx.ai.group.GroupDigestScheduler
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.HostInfo

@Feature(
    name = "群聊总结",
    categories = ["API"],
    description = "按群聊设置生成摘要，在微信首页查看结果",
)
object AiGroupDigestFeature : ApiFeature() {
    private val wakeSignal by lazy { AiScheduleWakeSignal(HostInfo.application, "GROUP_DIGEST_CHECK", GroupDigestScheduler::wake) }

    override fun onEnable() {
        wakeSignal.register()
        GroupDigestScheduler.onEnable(wakeSignal::schedule)
        GroupDigestEntry.register(HostInfo.application)
    }

    override fun onDisable() {
        GroupDigestEntry.unregister()
        GroupDigestScheduler.onDisable()
        wakeSignal.unregister()
    }
}
