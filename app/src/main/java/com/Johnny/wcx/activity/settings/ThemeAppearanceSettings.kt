package com.Johnny.wcx.activity.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.NinePatch
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Image as ImageIcon
import com.Johnny.wcx.features.items.beautify.BackgroundRule
import com.Johnny.wcx.features.items.beautify.ColorRule
import com.Johnny.wcx.features.items.beautify.ThemeProfile
import com.Johnny.wcx.features.items.beautify.ThemeStore
import com.Johnny.wcx.utils.android.showToast
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults

/** 读取指定主题的最新配置，不切换使用中的主题。 */
internal fun updateThemeSetting(id: String, section: String, key: String, value: Any): Result<Unit> = runCatching {
    val json = requireNotNull(ThemeStore.profileSnapshot(id)) { "主题已不可用，请新建或导入" }.profile.toJson()
    val appearance = json.getJSONObject("appearance")
    val group = appearance.optJSONObject(section) ?: JSONObject().also { appearance.put(section, it) }
    group.put(key, value)
    if (section == "chat" && key in listOf("leftBubble", "rightBubble") && value is JSONObject && value.optString("kind") == "native") {
        resetBubbleRule(json, if (key == "leftBubble") "bubble_left" else "bubble_right")
    }
    ThemeStore.updateThemeProfile(json, id = id).getOrThrow()
}

internal fun saveThemePageImage(id: String, index: Int?, source: File): Result<Unit> = runCatching {
    require(index == null || index in 0..3) { "页面序号无效" }
    val json = requireNotNull(ThemeStore.profileSnapshot(id)) { "原主题已不可用" }.profile.toJson()
    val pages = json.getJSONObject("appearance").getJSONObject("pages")
    val keys = listOf("chats", "contacts", "discover", "me")
    val relative = "edits/${UUID.randomUUID()}/wallpaper.img"
    val indices = index?.let { listOf(it) } ?: keys.indices.toList()
    indices.forEach { pages.put(keys[it], JSONObject().put("kind", "image").put("asset", relative)) }
    ThemeStore.updateThemeProfile(json, mapOf(relative to source), id).getOrThrow()
}

internal fun saveThemeTabIcon(id: String, index: Int, selected: Boolean, source: File): Result<Unit> = runCatching {
    require(index in 0..3) { "标签序号无效" }
    val json = requireNotNull(ThemeStore.profileSnapshot(id)) { "原主题已不可用" }.profile.toJson()
    val appearance = json.getJSONObject("appearance")
    val chrome = appearance.optJSONObject("chrome") ?: JSONObject().also { appearance.put("chrome", it) }
    val icons = chrome.optJSONObject("tabIcons") ?: JSONObject().also { chrome.put("tabIcons", it) }
    val state = if (selected) "selected" else "normal"
    val existing = icons.optJSONArray(state) ?: json.optJSONArray("tab")
    val relative = "edits/${UUID.randomUUID()}/tab.img"
    val values = JSONArray()
    repeat(4) { slot -> values.put(if (slot == index) relative else existing?.opt(slot) ?: JSONObject.NULL) }
    icons.put(state, values)
    ThemeStore.updateThemeProfile(json, mapOf(relative to source), id).getOrThrow()
}

/** 停用分组仍显示已保存的设置；此显示副本不参与写入。 */
internal fun themeSettingsProfile(profile: ThemeProfile, section: String): ThemeProfile {
    if (profile.enabled(section)) return profile
    val json = profile.toJson()
    val appearance = json.getJSONObject("appearance")
    val group = appearance.optJSONObject(section) ?: JSONObject().also { appearance.put(section, it) }
    group.put("enabled", true)
    return ThemeProfile.parse(json)
}

/** 展示所选主题保存的气泡；显式原生阻断旧素材，默认规则才回退到旧字段。 */
internal fun themeChatPreviewBubble(profile: ThemeProfile, self: Boolean): BackgroundRule {
    val rule = themeSettingsProfile(profile, "chat").background("chat", if (self) "rightBubble" else "leftBubble")
    if (rule !== BackgroundRule.Inherit) return rule
    return profile.toJson().optString(if (self) "bubble_right" else "bubble_left")
        .takeIf { it.isNotBlank() }?.let { BackgroundRule.Image(it) } ?: BackgroundRule.Native
}

@Composable
internal fun ThemeChatPreview(id: String, revision: Int) {
    val snapshot = remember(id, revision) { ThemeStore.profileSnapshot(id) } ?: return
    val saved = remember(id, revision) { themeSettingsProfile(snapshot.profile, "chat") }
    val background = saved.background("chat", "background")
    val backgroundFile = (background as? BackgroundRule.Image)?.let { snapshot.asset(it.asset) }
    val backgroundBitmap = themePreviewBitmap(backgroundFile, revision)
    val backgroundColor = (background as? BackgroundRule.Solid)?.argb?.let { Color(it) } ?: Color(0xffededed)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 150.dp).clip(RoundedCornerShape(14.dp)).background(backgroundColor)) {
            if (backgroundBitmap != null) Image(backgroundBitmap.asImageBitmap(), null, Modifier.matchParentSize(),
                contentScale = ContentScale.Crop, alpha = (background as BackgroundRule.Image).opacity)
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(false to "你好", true to "收到").forEach { (self, text) ->
                    val bubble = remember(id, revision, self) { themeChatPreviewBubble(snapshot.profile, self) }
                    val bubbleFile = (bubble as? BackgroundRule.Image)?.let { snapshot.asset(it.asset) }
                    val bitmap = themePreviewBitmap(bubbleFile, revision, bubble = true)
                    val nativeColor = if (self) Color(0xff95ec69) else Color.White
                    val color = when (bubble) {
                        is BackgroundRule.Solid -> Color(bubble.argb)
                        BackgroundRule.Transparent -> Color.Transparent
                        else -> if (bitmap == null) nativeColor else Color.Transparent
                    }
                    val textColor = (saved.color("chat", if (self) "rightText" else "leftText") as? ColorRule.Value)?.argb
                    Box(Modifier.align(if (self) Alignment.End else Alignment.Start).widthIn(min = 64.dp, max = 190.dp)
                        .background(color, RoundedCornerShape(9.dp))) {
                        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.matchParentSize(),
                            contentScale = ContentScale.FillBounds, alpha = (bubble as BackgroundRule.Image).opacity)
                        Text(text, Modifier.padding(horizontal = 18.dp, vertical = 10.dp), color = textColor?.let { Color(it) } ?: Color.Black)
                    }
                }
            }
        }
        Text((if (snapshot.profile.enabled("chat")) "已保存设置 · " else "聊天主题已停用 · ") + "素材缩放示意；原生/默认使用示例样式。聊天背景独立于四页背景，实际效果以微信为准。",
            fontSize = 11.sp, color = BasicComponentDefaults.summaryColor().color, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun themePreviewBitmap(file: File?, revision: Int, bubble: Boolean = false): Bitmap? = remember(file?.absolutePath, revision, bubble) {
    file?.let { runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(it.absolutePath, options)
        options.inJustDecodeBounds = false
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1024) options.inSampleSize *= 2
        val bitmap = BitmapFactory.decodeFile(it.absolutePath, options) ?: return@runCatching null
        val compiled = bitmap.ninePatchChunk?.let { chunk -> NinePatch.isNinePatchChunk(chunk) } == true
        if (bubble && !compiled && it.name.endsWith(".9.png") && bitmap.width > 2 && bitmap.height > 2) {
            Bitmap.createBitmap(bitmap, 1, 1, bitmap.width - 2, bitmap.height - 2).also { cropped -> if (cropped !== bitmap) bitmap.recycle() }
        } else bitmap
    }.getOrNull() }
}

@Composable
internal fun ThemeSettingRow(title: String, summary: String, swatch: Int? = null, icon: ImageVector? = null, onClick: () -> Unit) {
    val trailingSummary = swatch == null && summary.length in 1..10
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = BasicComponentDefaults.titleColor().color, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = BasicComponentDefaults.titleColor().color)
            if (summary.isNotBlank() && !trailingSummary) Text(summary, fontSize = 12.sp, color = BasicComponentDefaults.summaryColor().color)
        }
        if (trailingSummary) Text(summary, Modifier.padding(horizontal = 8.dp), fontSize = 12.sp, color = BasicComponentDefaults.summaryColor().color)
        if (swatch != null) {
            BoxSwatch(swatch)
            Spacer(Modifier.width(12.dp))
        }
        Text("›", fontSize = 20.sp, color = BasicComponentDefaults.summaryColor().color)
    }
}

@Composable
private fun BoxSwatch(color: Int) {
    Spacer(Modifier.size(25.dp).clip(RoundedCornerShape(7.dp)).background(Color(color))
        .border(0.6.dp, BasicComponentDefaults.summaryColor().color.copy(alpha = 0.4f), RoundedCornerShape(7.dp)))
}

@Composable
internal fun ThemeColorSetting(id: String, revision: Int, title: String, section: String, key: String,
    defaultOnly: Boolean = false, onChanged: () -> Unit) {
    val context = LocalContext.current
    val profile = remember(id, revision) { ThemeStore.profileSnapshot(id)?.profile } ?: return
    val rule = remember(id, revision, section, key) { themeSettingsProfile(profile, section).color(section, key) }
    var show by remember(id, section, key) { mutableStateOf(false) }
    var rgb by remember(id, section, key) { mutableStateOf(false) }
    fun set(mode: String, color: Int? = null) {
        val value = JSONObject().put("mode", mode)
        color?.let { value.put("value", "#%06X".format(it and 0xffffff)) }
        updateThemeSetting(id, section, key, value).onSuccess { onChanged(); show = false; rgb = false }
            .onFailure { showToast(context, "保存失败：${it.message}") }
    }
    ThemeSettingRow(title, (if (profile.enabled(section)) "" else "已停用，设置保留 · ") + when (rule) {
        is ColorRule.Value -> "#%06X".format(rule.argb and 0xffffff)
        ColorRule.Native -> "微信原生"
        ColorRule.Auto -> "自动适配背景"
        else -> "主题默认"
    }, (rule as? ColorRule.Value)?.argb) { show = true }
    if (show) {
        if (rgb) RgbColorDialog((rule as? ColorRule.Value)?.argb,
            onDismiss = { show = false; rgb = false }, onApply = { set("value", it) })
        else AlertDialog(onDismissRequest = { show = false }, title = { Text(title) }, text = {
            Column {
                TextButton(onClick = { rgb = true }) { Text("自定义 RGB") }
                if (section !in listOf("chat", "drawer", "fab")) TextButton(onClick = { set("auto") }) { Text("自动适配背景") }
                if (!defaultOnly) {
                    TextButton(onClick = { set("native") }) { Text("微信原生") }
                    TextButton(onClick = { set("inherit") }) { Text("主题默认") }
                }
            }
        }, confirmButton = { TextButton(onClick = { show = false }) { Text("取消") } })
    }
}

internal fun backgroundRuleLabel(rule: BackgroundRule): String = when (rule) {
    is BackgroundRule.Image -> "图片 · ${(rule.opacity * 100).toInt()}%"
    is BackgroundRule.Solid -> "纯色 · #%06X".format(rule.argb and 0xffffff)
    BackgroundRule.Transparent -> "透明"
    BackgroundRule.Native -> "微信原生"
    else -> "主题默认"
}

@Composable
internal fun ThemeBackgroundSetting(id: String, revision: Int, title: String, section: String, key: String,
    onPick: (() -> Unit)? = null, onChanged: () -> Unit) {
    val context = LocalContext.current
    val profile = remember(id, revision) { ThemeStore.profileSnapshot(id)?.profile } ?: return
    val rule = remember(id, revision, section, key) {
        val saved = themeSettingsProfile(profile, section)
        if (section == "pages") saved.background(key) else saved.background(section, key)
    }
    var show by remember(id, section, key) { mutableStateOf(false) }
    var rgb by remember(id, section, key) { mutableStateOf(false) }
    fun set(kind: String, color: Int? = null) {
        val value = JSONObject().put("kind", kind)
        color?.let { value.put("value", "#%06X".format(it and 0xffffff)) }
        updateThemeSetting(id, section, key, value).onSuccess { onChanged(); show = false; rgb = false }
            .onFailure { showToast(context, "保存失败：${it.message}") }
    }
    ThemeSettingRow(title, (if (profile.enabled(section)) "" else "已停用，设置保留 · ") + backgroundRuleLabel(rule),
        (rule as? BackgroundRule.Solid)?.argb, icon = if (onPick != null) MaterialSymbols.Outlined.ImageIcon else null) { show = true }
    if (section == "pages" && rule is BackgroundRule.Image) {
        var opacity by remember(id, key, rule.asset, rule.opacity) { mutableStateOf(rule.opacity) }
        Text("图片不透明度：${(opacity * 100).toInt()}%", Modifier.padding(horizontal = 16.dp), fontSize = 12.sp)
        Slider(value = opacity, onValueChange = { opacity = it }, onValueChangeFinished = {
            updateThemeSetting(id, section, key, JSONObject().put("kind", "image").put("asset", rule.asset).put("opacity", opacity.toDouble()))
                .onSuccess { onChanged() }.onFailure { opacity = rule.opacity; showToast(context, "保存失败：${it.message}") }
        }, modifier = Modifier.padding(horizontal = 16.dp))
    }
    if (show) {
        if (rgb) RgbColorDialog((rule as? BackgroundRule.Solid)?.argb,
            onDismiss = { show = false; rgb = false }, onApply = { set("color", it) })
        else AlertDialog(onDismissRequest = { show = false }, title = { Text(title) }, text = {
            Column {
                onPick?.let { pick -> TextButton(onClick = { show = false; pick() }) { Text("选择图片") } }
                TextButton(onClick = { rgb = true }) { Text("纯色") }
                if (section != "chat" && section != "fab") TextButton(onClick = { set("transparent") }) { Text("透明") }
                TextButton(onClick = { set("native") }) { Text("微信原生") }
                TextButton(onClick = { set("inherit") }) { Text("主题默认") }
            }
        }, confirmButton = { TextButton(onClick = { show = false }) { Text("取消") } })
    }
}
