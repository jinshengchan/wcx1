// Adapted from AARCNoa/zhiyou, AiAutoIncrementalFeature.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.features.items.ai

import com.Johnny.wcx.ai.AiAutoIncrementalScheduler
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.HostInfo

@Feature(
    name = "自动增量整理",
    categories = ["API"],
    description = "每天为已授权的好友档案整理新增聊天记录",
)
object AiAutoIncrementalFeature : ApiFeature() {
    private val wakeSignal by lazy {
        AiScheduleWakeSignal(HostInfo.application, "FRIEND_ARCHIVE_CHECK", AiAutoIncrementalScheduler::wake)
    }

    override fun onEnable() {
        wakeSignal.register()
        AiAutoIncrementalScheduler.onEnable(wakeSignal::schedule)
    }

    override fun onDisable() {
        AiAutoIncrementalScheduler.onDisable()
        wakeSignal.unregister()
    }
}
