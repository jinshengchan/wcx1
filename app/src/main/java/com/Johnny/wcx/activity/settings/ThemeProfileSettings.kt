package com.Johnny.wcx.activity.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import com.Johnny.wcx.features.items.beautify.BackgroundRule
import com.Johnny.wcx.features.items.beautify.ColorRule
import com.Johnny.wcx.features.items.beautify.ThemeStore
import com.Johnny.wcx.utils.android.showToast
import org.json.JSONArray
import org.json.JSONObject
import top.yukonga.miuix.kmp.basic.Switch

/** 编辑选中主题的已接入配置，所有写入都带目标主题 ID。 */
@Composable
internal fun ThemeProfileSettings(id: String, revision: Int, onPickTab: (Int, Boolean) -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val profile = remember(id, revision) { ThemeStore.profileSnapshot(id)?.profile } ?: return
    var colorTarget by remember(id) { mutableStateOf<Pair<String, String>?>(null) }
    var backgroundTarget by remember(id) { mutableStateOf<Pair<String, String>?>(null) }
    var labelIndex by remember(id) { mutableStateOf<Int?>(null) }
    fun update(section: String, key: String, value: Any): Boolean {
        val json = ThemeStore.profileSnapshot(id)?.profile?.toJson() ?: return false
        val appearance = json.getJSONObject("appearance")
        val group = appearance.optJSONObject(section) ?: JSONObject().also { appearance.put(section, it) }
        group.put(key, value)
        val result = ThemeStore.updateThemeProfile(json, id = id)
            .onSuccess { onChanged() }
            .onFailure { showToast(context, "保存失败：${it.message}") }
        return result.isSuccess
    }
    Text("主题配置", modifier = Modifier.padding(16.dp))
    listOf("pages" to "四页背景", "chrome" to "顶部和标签", "lists" to "列表文字", "chat" to "聊天外观")
        .forEach { (section, title) ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(title, Modifier.weight(1f))
                Switch(checked = profile.enabled(section), onCheckedChange = { update(section, "enabled", it) })
            }
        }
    listOf(
        Triple("顶栏文字", "chrome", "toolbarText"), Triple("顶栏图标", "chrome", "toolbarIcon"),
        Triple("标签文字", "chrome", "tabText"), Triple("选中标签文字", "chrome", "tabSelectedText"),
        Triple("标签图标", "chrome", "tabIcon"), Triple("选中标签图标", "chrome", "tabSelectedIcon"),
        Triple("列表标题", "lists", "title"), Triple("列表副标题", "lists", "subtitle"),
        Triple("列表时间", "lists", "time"), Triple("账号信息", "lists", "accountInfo"),
        Triple("新消息提示", "lists", "newMessage"), Triple("标签未读数字", "chrome", "unreadText"),
        Triple("左侧消息正文", "chat", "leftText"), Triple("右侧消息正文", "chat", "rightText"),
    ).forEach { (title, section, key) ->
        val rule = profile.color(section, key)
        val label = when (rule) {
            is ColorRule.Value -> "#%06X".format(rule.argb and 0xffffff)
            ColorRule.Native -> "微信原生"
            ColorRule.Auto -> "自动"
            else -> "主题默认"
        }
        TextButton(onClick = { colorTarget = section to key }) { Text("$title：$label") }
    }
    listOf(
        Triple("主页背景", "pages", "chats"), Triple("通讯录背景", "pages", "contacts"),
        Triple("发现背景", "pages", "discover"), Triple("我的背景", "pages", "me"),
        Triple("顶栏底板", "chrome", "toolbarBackground"), Triple("标签栏底板", "chrome", "tabBackground"),
        Triple("列表底板", "lists", "background"),
        Triple("左侧气泡", "chat", "leftBubble"), Triple("右侧气泡", "chat", "rightBubble"),
    ).forEach { (title, section, key) ->
        val rule = if (section == "pages") profile.background(key) else profile.background(section, key)
        val label = when (rule) {
            is BackgroundRule.Solid -> "#%06X".format(rule.argb and 0xffffff)
            is BackgroundRule.Image -> "图片"
            BackgroundRule.Native -> "微信原生"
            BackgroundRule.Transparent -> "透明"
            else -> "主题默认"
        }
        TextButton(onClick = { backgroundTarget = section to key }) { Text("$title：$label") }
        if (section == "pages" && rule is BackgroundRule.Image) {
            var opacity by remember(id, key, rule.asset, rule.opacity) { mutableStateOf(rule.opacity) }
            Text("图片不透明度：${(opacity * 100).toInt()}%", Modifier.padding(horizontal = 16.dp))
            Slider(value = opacity, onValueChange = { opacity = it }, onValueChangeFinished = {
                if (opacity != rule.opacity) {
                    if (!update("pages", key, JSONObject().put("kind", "image").put("asset", rule.asset).put("opacity", opacity.toDouble()))) opacity = rule.opacity
                }
            }, modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
    val raw = profile.toJson()
    val chrome = raw.getJSONObject("appearance").optJSONObject("chrome")
    val storedLabels = chrome?.optJSONArray("tabLabels")
    fun storedLabel(index: Int): String? = (storedLabels?.opt(index) as? String)?.takeIf { it.isNotBlank() }
    listOf("微信", "通讯录", "发现", "我").forEachIndexed { index, title ->
        TextButton(onClick = { labelIndex = index }) { Text("$title 标签：${storedLabel(index) ?: "微信原生"}") }
        Row {
            TextButton(onClick = { onPickTab(index, false) }) { Text("普通图标") }
            TextButton(onClick = { onPickTab(index, true) }) { Text("选中图标") }
            TextButton(onClick = {
                val latest = ThemeStore.profileSnapshot(id)?.profile?.toJson() ?: return@TextButton
                val appearance = latest.getJSONObject("appearance")
                val group = appearance.optJSONObject("chrome") ?: JSONObject().also { appearance.put("chrome", it) }
                val icons = group.optJSONObject("tabIcons") ?: JSONObject().also { group.put("tabIcons", it) }
                for (state in listOf("normal", "selected")) {
                    val existing = icons.optJSONArray(state) ?: latest.optJSONArray("tab")
                    val values = JSONArray()
                    repeat(4) { slot -> values.put(if (slot == index) JSONObject.NULL else existing?.opt(slot) ?: JSONObject.NULL) }
                    icons.put(state, values)
                }
                ThemeStore.updateThemeProfile(latest, id = id).onSuccess { onChanged() }
                    .onFailure { showToast(context, "保存失败：${it.message}") }
            }) { Text("恢复图标") }
        }
    }
    Text("聊天时间、昵称、输入栏等尚未识别，暂不可设置。", Modifier.padding(16.dp))

    colorTarget?.let { (section, key) ->
        var choose by remember(section, key) { mutableStateOf(false) }
        fun set(mode: String, value: Int? = null) {
            val rule = JSONObject().put("mode", mode)
            value?.let { rule.put("value", "#%06X".format(it and 0xffffff)) }
            update(section, key, rule)
            colorTarget = null
        }
        if (choose) {
            RgbColorDialog((profile.color(section, key) as? ColorRule.Value)?.argb,
                onDismiss = { colorTarget = null }, onApply = { set("value", it) })
        } else AlertDialog(
            onDismissRequest = { colorTarget = null }, title = { Text("选择颜色模式") },
            text = { Column {
                TextButton(onClick = { choose = true }) { Text("自定义 RGB") }
                if (section != "chat") TextButton(onClick = { set("auto") }) { Text("自动") }
                TextButton(onClick = { set("native") }) { Text("微信原生") }
                TextButton(onClick = { set("inherit") }) { Text("主题默认") }
            } }, confirmButton = { TextButton(onClick = { colorTarget = null }) { Text("取消") } },
        )
    }
    backgroundTarget?.let { (section, key) ->
        var choose by remember(section, key) { mutableStateOf(false) }
        fun set(kind: String, value: Int? = null) {
            val rule = JSONObject().put("kind", kind)
            value?.let { rule.put("value", "#%06X".format(it and 0xffffff)) }
            update(section, key, rule)
            backgroundTarget = null
        }
        if (choose) {
            val current = if (section == "pages") profile.background(key) else profile.background(section, key)
            RgbColorDialog((current as? BackgroundRule.Solid)?.argb,
                onDismiss = { backgroundTarget = null }, onApply = { set("color", it) })
        } else AlertDialog(
            onDismissRequest = { backgroundTarget = null }, title = { Text("选择背景模式") },
            text = { Column {
                TextButton(onClick = { choose = true }) { Text("纯色") }
                if (section != "chat") TextButton(onClick = { set("transparent") }) { Text("透明") }
                TextButton(onClick = { set("native") }) { Text("微信原生") }
                TextButton(onClick = { set("inherit") }) { Text("主题默认") }
                if (section == "pages" || section == "chat") Text("图片请在下方素材中选择。")
            } }, confirmButton = { TextButton(onClick = { backgroundTarget = null }) { Text("取消") } },
        )
    }
    labelIndex?.let { index ->
        var label by remember(id, index) { mutableStateOf(storedLabel(index).orEmpty()) }
        AlertDialog(
            onDismissRequest = { labelIndex = null }, title = { Text("编辑标签") },
            text = { OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("留空恢复微信原生") }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                val labels = JSONArray()
                repeat(4) { labels.put(if (it == index) label.takeIf { it.isNotBlank() } ?: JSONObject.NULL else storedLabel(it) ?: JSONObject.NULL) }
                update("chrome", "tabLabels", labels)
                labelIndex = null
            }) { Text("保存") } },
            dismissButton = { TextButton(onClick = { labelIndex = null }) { Text("取消") } },
        )
    }
}
