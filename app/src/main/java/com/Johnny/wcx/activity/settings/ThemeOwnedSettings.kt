package com.Johnny.wcx.activity.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.items.beautify.BackgroundRule
import com.Johnny.wcx.features.items.beautify.ColorRule
import com.Johnny.wcx.features.items.beautify.ThemeStore
import com.Johnny.wcx.utils.android.showToast
import org.json.JSONObject
import java.io.File
import java.util.UUID
import top.yukonga.miuix.kmp.basic.Switch

@Composable
internal fun ThemeOwnedSettings(id: String, revision: Int, onChanged: () -> Unit) {
    val context = LocalContext.current
    val profile = remember(id, revision) { ThemeStore.profileSnapshot(id)?.profile } ?: return
    var target by remember(id) { mutableStateOf<Triple<String, String, Boolean>?>(null) }
    var choosing by remember(id) { mutableStateOf(false) }
    var pendingId by rememberSaveable { mutableStateOf("") }
    var pendingKey by rememberSaveable { mutableStateOf("iconAsset") }
    fun update(section: String, key: String, value: Any) {
        val json = ThemeStore.profileSnapshot(id)?.profile?.toJson() ?: return
        val appearance = json.getJSONObject("appearance")
        val group = appearance.optJSONObject(section) ?: JSONObject().also { appearance.put(section, it) }
        group.put(key, value)
        ThemeStore.updateThemeProfile(json, id = id).onSuccess { onChanged() }
            .onFailure { showToast(context, "保存失败：${it.message}") }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runCatching {
            val snapshot = requireNotNull(ThemeStore.profileSnapshot(pendingId)) { "原主题已不可用" }
            val temp = File.createTempFile("theme-fab-", ".tmp", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } } ?: error("读不到图片")
                val json = snapshot.profile.toJson()
                val appearance = json.getJSONObject("appearance")
                val section = if (pendingKey == "iconAsset") "fab" else "drawer"
                val group = appearance.optJSONObject(section) ?: JSONObject().also { appearance.put(section, it) }
                val relative = "edits/${UUID.randomUUID()}/fab.img"
                if (pendingKey == "iconAsset") group.put("iconAsset", relative)
                else group.put(pendingKey, JSONObject().put("kind", "image").put("asset", relative))
                ThemeStore.updateThemeProfile(json, mapOf(relative to temp), pendingId).getOrThrow()
            } finally { temp.delete() }
        }.onSuccess { onChanged() }.onFailure { showToast(context, "保存失败：${it.message}") }
    }
    Text("抽屉与悬浮按钮", Modifier.padding(16.dp))
    listOf("drawer" to "抽屉", "fab" to "悬浮按钮").forEach { (section, title) ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text(title, Modifier.weight(1f))
            Switch(checked = profile.enabled(section), onCheckedChange = { update(section, "enabled", it) })
        }
    }
    listOf(Triple("抽屉整体背景", "drawer", "whole"), Triple("抽屉头部背景", "drawer", "header"), Triple("抽屉列表背景", "drawer", "list"), Triple("悬浮按钮背景", "fab", "background")).forEach { (title, section, key) ->
        TextButton(onClick = { target = Triple(section, key, true) }) { Text(title) }
    }
    listOf(Triple("抽屉列表标题", "drawer", "title"), Triple("抽屉账号文字", "drawer", "accountInfo"), Triple("悬浮按钮标签", "fab", "label"), Triple("悬浮按钮图标颜色", "fab", "icon")).forEach { (title, section, key) ->
        TextButton(onClick = { target = Triple(section, key, false) }) { Text(title) }
    }
    listOf("whole" to "整体", "header" to "头部", "list" to "列表").forEach { (key, title) ->
        TextButton(onClick = { pendingId = id; pendingKey = key; picker.launch("image/*") }) { Text("选择抽屉${title}图片") }
        val rule = profile.background("drawer", key)
        if (rule is BackgroundRule.Image) {
            var opacity by remember(id, key, rule.asset, rule.opacity) { mutableStateOf(rule.opacity) }
            Text("${title}图片不透明度：${(opacity * 100).toInt()}%", Modifier.padding(horizontal = 16.dp))
            Slider(value = opacity, onValueChange = { opacity = it }, onValueChangeFinished = {
                update("drawer", key, JSONObject().put("kind", "image").put("asset", rule.asset).put("opacity", opacity.toDouble()))
            }, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
    Row {
        TextButton(onClick = { pendingId = id; pendingKey = "iconAsset"; picker.launch("image/*") }) { Text("选择悬浮按钮图标") }
        TextButton(onClick = {
            val json = ThemeStore.profileSnapshot(id)?.profile?.toJson() ?: return@TextButton
            json.getJSONObject("appearance").optJSONObject("fab")?.remove("iconAsset")
            ThemeStore.updateThemeProfile(json, id = id).onSuccess { onChanged() }.onFailure { showToast(context, "保存失败：${it.message}") }
        }) { Text("恢复图标") }
    }
    target?.let { (section, key, background) ->
        fun set(mode: String, color: Int? = null) {
            val rule = JSONObject().put(if (background) "kind" else "mode", mode)
            color?.let { rule.put("value", "#%06X".format(it and 0xffffff)) }
            update(section, key, rule)
            target = null
            choosing = false
        }
        if (choosing) {
            val value = if (background) (profile.background(section, key) as? BackgroundRule.Solid)?.argb else (profile.color(section, key) as? ColorRule.Value)?.argb
            RgbColorDialog(value, onDismiss = { target = null; choosing = false }, onApply = { set(if (background) "color" else "value", it) })
        } else AlertDialog(onDismissRequest = { target = null }, title = { Text("选择外观") }, text = {
            Column {
                TextButton(onClick = { choosing = true }) { Text("自定义 RGB") }
                if (background && section == "drawer") TextButton(onClick = { set("transparent") }) { Text("透明") }
                TextButton(onClick = { set("native") }) { Text("恢复原有外观") }
                TextButton(onClick = { set("inherit") }) { Text("主题默认") }
            }
        }, confirmButton = { TextButton(onClick = { target = null }) { Text("取消") } })
    }
}
