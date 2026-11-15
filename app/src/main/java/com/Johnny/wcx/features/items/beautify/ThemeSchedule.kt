package com.Johnny.wcx.features.items.beautify

import java.util.Locale

/** 纯本地时间规则；调用方在进入主界面或系统模式变化时决定是否应用。 */
internal object ThemeSchedule {
    data class Rule(
        val dayThemeId: String,
        val nightThemeId: String,
        val dayStart: Int,
        val nightStart: Int
    ) {
        init {
            require(dayThemeId.isNotBlank() && nightThemeId.isNotBlank()) { "请选择日间和夜间主题" }
            validateTimes(dayStart, nightStart)
        }
    }

    fun parseTime(value: String): Int {
        require(value.matches(Regex("[0-9]{2}:[0-9]{2}"))) { "时间格式应为 HH:mm" }
        val hour = value.substring(0, 2).toInt()
        val minute = value.substring(3, 5).toInt()
        require(hour in 0..23 && minute in 0..59) { "时间超出范围" }
        return hour * 60 + minute
    }

    fun formatTime(minute: Int): String {
        require(minute in 0..1439) { "时间超出范围" }
        return String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)
    }

    private fun validateTimes(dayStart: Int, nightStart: Int) {
        require(dayStart in 0..1439 && nightStart in 0..1439) { "时间超出范围" }
        require(dayStart != nightStart) { "日间和夜间起点不能相同" }
    }

    fun isDay(minute: Int, dayStart: Int, nightStart: Int): Boolean {
        require(minute in 0..1439) { "时间超出范围" }
        validateTimes(dayStart, nightStart)
        val elapsed = (minute - dayStart + 1440) % 1440
        val duration = (nightStart - dayStart + 1440) % 1440
        return elapsed < duration
    }

    /** null 表示不切换，保留当前可用主题。 */
    fun selectTheme(rule: Rule?, minute: Int, available: (String) -> Boolean): String? {
        if (rule == null) return null
        val id = if (isDay(minute, rule.dayStart, rule.nightStart)) rule.dayThemeId else rule.nightThemeId
        return id.takeIf(available)
    }

    fun isDark(policy: String, systemDark: Boolean, minute: Int, dayStart: Int, nightStart: Int): Boolean =
        when (policy) {
            "system" -> systemDark
            "light" -> false
            "dark" -> true
            "time" -> !isDay(minute, dayStart, nightStart)
            else -> throw IllegalArgumentException("未知深浅色策略：$policy")
        }
}
