package com.Johnny.wcx.features.items.beautify

import android.content.Context
import android.content.res.Configuration
import java.io.File
import java.util.Calendar

/** 将当前配置解析成唯一的深浅变体；不持有窗口，也不在控件绘制中读取配置文件。 */
internal object ThemeRuntime {
    class Effective internal constructor(
        val saved: ThemeProfileRepository.Snapshot,
        val profile: ThemeProfile,
        val dark: Boolean,
        internal val systemDark: Boolean,
        internal val minute: Int
    ) {
        val revision: Long get() = saved.revision
        val themeId: String get() = saved.themeId
        fun asset(relative: String): File? = saved.asset(relative)
    }

    @Volatile private var cached: Effective? = null

    /** 进入页面、时间/时区或配置变化时使按时间模式重新判定。 */
    fun invalidate() { cached = null }

    fun current(context: Context): Effective? {
        if (!ThemeStore.wallpaperSwitchOn()) return null
        val saved = ThemeStore.profileSnapshot() ?: return null
        val systemDark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val mode = saved.profile.mode
        val minute = if (mode.policy == "time") Calendar.getInstance().let {
            it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
        } else 0
        cached?.takeIf { it.saved === saved && it.systemDark == systemDark && it.minute == minute }?.let { return it }
        val dark = ThemeSchedule.isDark(mode.policy, systemDark, minute, mode.dayStart, mode.nightStart)
        return Effective(saved, saved.profile.effective(dark), dark, systemDark, minute).also { cached = it }
    }
}
