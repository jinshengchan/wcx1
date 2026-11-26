package com.Johnny.wcx.features.items.ai

import com.Johnny.wcx.ai.AiDailyReport
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.utils.HostInfo

@Feature(name = "AI 每日总结", categories = ["API"], description = "定时生成已授权的每日总结")
object AiDailyReportFeature : ApiFeature() {
    private val wakeSignal by lazy {
        AiScheduleWakeSignal(HostInfo.application, "DAILY_REPORT_CHECK", AiDailyReport::wake)
    }

    override fun onEnable() {
        wakeSignal.register()
        AiDailyReport.start(wakeSignal::schedule)
    }

    override fun onDisable() {
        AiDailyReport.stop()
        wakeSignal.unregister()
    }
}
