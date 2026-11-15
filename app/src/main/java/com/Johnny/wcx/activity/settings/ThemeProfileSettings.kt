package com.Johnny.wcx.activity.settings

import androidx.compose.foundation.layout.Row
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
import com.Johnny.wcx.features.items.beautify.ThemeStore
import com.Johnny.wcx.utils.android.showToast
import org.json.JSONArray
import org.json.JSONObject

/** 编辑选中主题的已接入配置，所有写入都带目标主题 ID。 */
@Composable
internal fun ThemeProfileSettings(id: String, revision: Int, onPickTab: (Int, Boolean) -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    val profile = remember(id, revision) { ThemeStore.profileSnapshot(id)?.profile } ?: return
    var labelIndex by remember(id) { mutableStateOf<Int?>(null) }
    fun update(section: String, key: String, value: Any) {
        updateThemeSetting(id, section, key, value).onSuccess { onChanged() }
            .onFailure { showToast(context, "保存失败：${it.message}") }
    }
    SwitchRow("列表文字", "关闭时设置保留，显示微信原生颜色", profile.enabled("lists")) { update("lists", "enabled", it) }
    Text("文字颜色", Modifier.padding(16.dp))
    listOf(
        Triple("列表标题", "lists", "title"), Triple("列表副标题", "lists", "subtitle"),
        Triple("列表时间", "lists", "time"), Triple("账号信息", "lists", "accountInfo"),
        Triple("新消息提示", "lists", "newMessage"),
    ).forEach { (title, section, key) -> ThemeColorSetting(id, revision, title, section, key, onChanged = onChanged) }
    SwitchRow("顶部与标签", "关闭时设置保留，显示微信原生外观", profile.enabled("chrome")) { update("chrome", "enabled", it) }
    listOf(
        Triple("顶栏文字", "chrome", "toolbarText"), Triple("顶栏图标", "chrome", "toolbarIcon"),
        Triple("普通标签文字", "chrome", "tabText"), Triple("选中标签文字", "chrome", "tabSelectedText"),
        Triple("普通标签图标", "chrome", "tabIcon"), Triple("选中标签图标", "chrome", "tabSelectedIcon"),
        Triple("标签未读数字", "chrome", "unreadText"),
    ).forEach { (title, section, key) -> ThemeColorSetting(id, revision, title, section, key, onChanged = onChanged) }
    Text("底板", Modifier.padding(16.dp))
    listOf(Triple("顶栏底板", "chrome", "toolbarBackground"), Triple("标签栏底板", "chrome", "tabBackground"),
        Triple("列表底板", "lists", "background")).forEach { (title, section, key) ->
        ThemeBackgroundSetting(id, revision, title, section, key, onChanged = onChanged)
    }
    Text("标签名称与图片", Modifier.padding(16.dp))
    val raw = profile.toJson()
    val chrome = raw.getJSONObject("appearance").optJSONObject("chrome")
    val storedLabels = chrome?.optJSONArray("tabLabels")
    fun storedLabel(index: Int): String? = (storedLabels?.opt(index) as? String)?.takeIf { it.isNotBlank() }
    listOf("微信", "通讯录", "发现", "我").forEachIndexed { index, title ->
        ThemeSettingRow("$title 标签名称", storedLabel(index) ?: "微信原生") { labelIndex = index }
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
