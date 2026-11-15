package com.Johnny.wcx.features.items.beautify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.widget.Toast
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.WeLogger
import org.json.JSONObject
import java.util.Calendar

/** 仅在微信进程中监听系统变化，不启动后台服务。 */
internal object ThemeScheduleController {
    private const val KEY = "theme_schedule_device"
    private var registered = false
    private var checking = false
    private var missingNotified = false
    private var lastEffective: Triple<String, Long, Boolean>? = null

    fun rule(): ThemeSchedule.Rule? = runCatching {
        val json = JSONObject(WePrefs.getStringOrDef(KEY, "{}"))
        if (!json.optBoolean("enabled", false)) return null
        ThemeSchedule.Rule(json.getString("day"), json.getString("night"),
            ThemeSchedule.parseTime(json.getString("dayStart")), ThemeSchedule.parseTime(json.getString("nightStart")))
    }.getOrNull()

    fun save(rule: ThemeSchedule.Rule?) {
        val json = JSONObject().put("enabled", rule != null)
        if (rule != null) json.put("day", rule.dayThemeId).put("night", rule.nightThemeId)
            .put("dayStart", ThemeSchedule.formatTime(rule.dayStart)).put("nightStart", ThemeSchedule.formatTime(rule.nightStart))
        WePrefs.putString(KEY, json.toString())
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { check(context) }
    }

    fun onLauncherEntry(context: Context) {
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
            }
            val app = context.applicationContext
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(receiver, filter)
            registered = true
        }
        check(context, true)
    }

    private fun check(context: Context, notifyMissing: Boolean = false) {
        if (checking) return
        if (!ThemeStore.wallpaperSwitchOn()) { lastEffective = null; ThemeRuntime.invalidate(); return }
        checking = true
        runCatching {
            val now = Calendar.getInstance()
            val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            val rule = rule()
            val selected = ThemeSchedule.selectTheme(rule, minute) {
                (BuiltinThemes.find(it) != null || ThemePack.find(it) != null) && ThemeStore.profileSnapshot(it) != null
            }
            if (rule != null && selected == null) {
                if (notifyMissing && !missingNotified) {
                    Toast.makeText(context, "自动主题不可用，请检查日夜主题设置", Toast.LENGTH_LONG).show()
                    missingNotified = true
                }
            }
            val changed = notifyMissing && selected != null && ThemeStore.applyScheduledTheme(selected)
            if (rule == null || selected != null) missingNotified = false
            ThemeRuntime.invalidate()
            val effective = ThemeRuntime.current(context)
            val key = effective?.let { Triple(it.themeId, it.revision, it.dark) }
            if (!changed && key != lastEffective) ThemeStore.refreshEffectiveTheme()
            lastEffective = key
        }.onFailure {
            WeLogger.w("ThemeSchedule", "自动主题检查失败：${it.message}")
            if (notifyMissing && !missingNotified) {
                Toast.makeText(context, "自动主题不可用，请检查日夜主题设置", Toast.LENGTH_LONG).show()
                missingNotified = true
            }
        }
        checking = false
    }
}
