package com.Johnny.wcx.activity.settings

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.items.beautify.ThemePack
import com.Johnny.wcx.features.items.beautify.ThemeSchedule
import com.Johnny.wcx.features.items.beautify.ThemeScheduleController
import com.Johnny.wcx.features.items.beautify.ThemeStore
import com.Johnny.wcx.utils.android.showToast
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Switch

/** 模式跟随主题；自动切换规则只保存在当前设备。 */
@Composable
internal fun ThemeModeSettings(id: String, revision: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    var showMode by remember { mutableStateOf(false) }
    var showSchedule by remember { mutableStateOf(false) }
    val profile = remember(id, revision) { ThemeStore.profileSnapshot(id)?.profile }
    val policy = when (profile?.mode?.policy) {
        "light" -> "浅色"; "dark" -> "深色"; "time" -> "跟随时间"; else -> "跟随系统"
    }
    if (profile != null) ThemeSettingRow("所选主题的深浅模式", policy) { showMode = true }
    ThemeSettingRow("日夜自动切换", "本设备 · 分别选择日间与夜间主题") { showSchedule = true }
    if (showMode && profile != null) {
        var policy by remember(id) { mutableStateOf(profile.mode.policy) }
        var day by remember(id) { mutableStateOf(ThemeSchedule.formatTime(profile.mode.dayStart)) }
        var night by remember(id) { mutableStateOf(ThemeSchedule.formatTime(profile.mode.nightStart)) }
        var disabled by remember(id) { mutableStateOf(profile.mode.disableOnDark) }
        AlertDialog(onDismissRequest = { showMode = false }, title = { Text("深浅模式") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色", "time" to "跟随时间").forEach { (value, title) ->
                TextButton(onClick = { policy = value }) { Text((if (policy == value) "✓ " else "") + title) }
            }
            OutlinedTextField(day, { day = it }, label = { Text("日间开始 HH:mm") }, singleLine = true)
            OutlinedTextField(night, { night = it }, label = { Text("夜间开始 HH:mm") }, singleLine = true)
            listOf("pages" to "四页背景", "chrome" to "顶部和标签", "lists" to "列表文字", "chat" to "聊天外观").forEach { (key, title) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("深色禁用$title", Modifier.weight(1f))
                    Switch(checked = key in disabled, onCheckedChange = { disabled = if (it) disabled + key else disabled - key })
                }
            }
        } }, confirmButton = { TextButton(onClick = {
            runCatching {
                val d = ThemeSchedule.parseTime(day); val n = ThemeSchedule.parseTime(night)
                require(d != n) { "日间和夜间起点不能相同" }
                val json = requireNotNull(ThemeStore.profileSnapshot(id)).profile.toJson()
                val appearance = json.getJSONObject("appearance")
                val mode = appearance.optJSONObject("mode") ?: JSONObject().also { appearance.put("mode", it) }
                mode.put("policy", policy).put("dayStart", day).put("nightStart", night).put("disableOnDark", JSONArray(disabled.toList()))
                ThemeStore.updateThemeProfile(json, id = id).getOrThrow()
            }.onSuccess { showMode = false; onChanged() }.onFailure { showToast(context, "保存失败：${it.message}") }
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { showMode = false }) { Text("取消") } })
    }
    if (showSchedule) {
        val existing = remember { ThemeScheduleController.rule() }
        val themes = remember(revision) { ThemePack.list().map { it.id to it.name } }
        var enabled by remember { mutableStateOf(existing != null) }
        var dayId by remember { mutableStateOf(existing?.dayThemeId.orEmpty()) }
        var nightId by remember { mutableStateOf(existing?.nightThemeId.orEmpty()) }
        var day by remember { mutableStateOf(existing?.let { ThemeSchedule.formatTime(it.dayStart) } ?: "07:00") }
        var night by remember { mutableStateOf(existing?.let { ThemeSchedule.formatTime(it.nightStart) } ?: "22:00") }
        var selecting by remember { mutableStateOf(0) }
        AlertDialog(onDismissRequest = { showSchedule = false }, title = { Text("日夜自动切换") }, text = { Column {
            Row { Text("启用", Modifier.weight(1f)); Switch(checked = enabled, onCheckedChange = { enabled = it }) }
            Text("进入主页或系统时间变化时检查；主题缺失时保留当前主题。")
            TextButton(onClick = { selecting = 1 }) { Text("日间主题：${themes.firstOrNull { it.first == dayId }?.second ?: "未选择或已失效"}") }
            TextButton(onClick = { selecting = 2 }) { Text("夜间主题：${themes.firstOrNull { it.first == nightId }?.second ?: "未选择或已失效"}") }
            OutlinedTextField(day, { day = it }, label = { Text("日间开始 HH:mm") }, singleLine = true)
            OutlinedTextField(night, { night = it }, label = { Text("夜间开始 HH:mm") }, singleLine = true)
        } }, confirmButton = { TextButton(onClick = {
            runCatching {
                val rule = if (enabled) ThemeSchedule.Rule(dayId, nightId, ThemeSchedule.parseTime(day), ThemeSchedule.parseTime(night)) else null
                if (rule != null) require(themes.any { it.first == dayId } && themes.any { it.first == nightId }) { "请选择可用的日夜主题" }
                ThemeScheduleController.save(rule)
            }.onSuccess { showSchedule = false; onChanged() }.onFailure { showToast(context, "保存失败：${it.message}") }
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { showSchedule = false }) { Text("取消") } })
        if (selecting != 0) AlertDialog(onDismissRequest = { selecting = 0 }, title = { Text("选择主题") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            themes.forEach { (themeId, name) -> TextButton(onClick = { if (selecting == 1) dayId = themeId else nightId = themeId; selecting = 0 }) { Text(name) } }
        } }, confirmButton = { TextButton(onClick = { selecting = 0 }) { Text("取消") } })
    }
}
