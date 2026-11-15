package com.Johnny.wcx.features.items.beautify

import org.json.JSONArray
import org.json.JSONObject

internal sealed interface BackgroundRule {
    data object Inherit : BackgroundRule
    data object Native : BackgroundRule
    data object Transparent : BackgroundRule
    data class Image(val asset: String, val opacity: Float = 1f) : BackgroundRule
    data class Solid(val argb: Int) : BackgroundRule
}

internal sealed interface ColorRule {
    data object Inherit : ColorRule
    data object Native : ColorRule
    data object Auto : ColorRule
    data class Value(val argb: Int) : ColorRule
}

/** 校验后的不可变主题快照；渲染读规则时不解析 JSON 或访问磁盘。 */
internal class ThemeProfile private constructor(
    private val manifest: String,
    private val pages: Map<String, BackgroundRule>,
    private val backgrounds: Map<String, Map<String, BackgroundRule>>,
    private val colors: Map<String, Map<String, ColorRule>>,
    private val enabledGroups: Map<String, Boolean>,
    private val labels: List<String?>,
    private val normalIcons: List<String?>,
    private val selectedIcons: List<String?>,
    val mode: Mode,
    private val darkVariant: ThemeProfile?
) {
    data class Mode(val policy: String, val dayStart: Int, val nightStart: Int, val disableOnDark: Set<String>)

    fun background(page: String): BackgroundRule {
        require(page in PAGE_KEYS || page == "common") { "未知页面：$page" }
        if (!enabled("pages")) return BackgroundRule.Native
        val rule = pages[page] ?: BackgroundRule.Inherit
        return if (rule === BackgroundRule.Inherit) pages["common"]?.takeUnless { it === BackgroundRule.Inherit }
            ?: BackgroundRule.Native else rule
    }

    fun background(section: String, key: String): BackgroundRule =
        if (!enabled(section)) BackgroundRule.Native else backgrounds[section]?.get(key) ?: BackgroundRule.Inherit

    fun color(section: String, key: String): ColorRule =
        if (!enabled(section)) ColorRule.Native else colors[section]?.get(key) ?: ColorRule.Inherit

    fun enabled(section: String): Boolean = enabledGroups[section] != false

    fun tabLabel(index: Int): String? = if (enabled("chrome")) labels.getOrNull(index) else null
    fun tabIcon(index: Int, selected: Boolean): String? =
        if (enabled("chrome")) (if (selected) selectedIcons else normalIcons).getOrNull(index) else null
    private val fabIconAsset: String? = JSONObject(manifest).optJSONObject("appearance")?.optJSONObject("fab")?.optString("iconAsset")?.takeIf { it.isNotBlank() }?.let(::assetPath)
    fun fabIcon(): String? = if (enabled("fab")) fabIconAsset else null
    fun effective(dark: Boolean): ThemeProfile = if (dark) darkVariant ?: this else this
    fun toJson(): JSONObject = JSONObject(manifest)

    companion object {
        val PAGE_KEYS = listOf("chats", "contacts", "discover", "me", "chatting")
        private val GROUPS = setOf("pages", "chrome", "lists", "chat", "drawer", "fab", "palette", "layout")
        private val BACKGROUNDS = mapOf(
            "chrome" to setOf("toolbarBackground", "tabBackground", "statusBarBackground"),
            "lists" to setOf("background", "stickyBackground", "highlightBackground"),
            "chat" to setOf("background", "leftBubble", "rightBubble", "leftTimeBackground", "rightTimeBackground", "nicknameBackground", "inputBackground"),
            "drawer" to setOf("whole", "header", "list", "highlightBackground"),
            "fab" to setOf("background")
        )
        private val COLORS = mapOf(
            "chrome" to setOf("toolbarText", "toolbarIcon", "tabText", "tabSelectedText", "tabIcon", "tabSelectedIcon", "unreadText", "unreadBackground"),
            "lists" to setOf("title", "subtitle", "time", "stickyTitle", "highlightText", "newMessage", "accountInfo", "separator"),
            "chat" to setOf("leftText", "rightText", "leftTimeText", "rightTimeText", "nicknameText", "inputText", "inputIcon", "quoteText"),
            "drawer" to setOf("title", "subtitle", "accountInfo", "newMessage", "highlightText"),
            "fab" to setOf("label", "icon"),
            "palette" to setOf("focused", "middle", "normal", "overlay")
        )

        fun parse(manifest: JSONObject): ThemeProfile = parseInternal(JSONObject(manifest.toString()), true)

        private fun parseInternal(root: JSONObject, makeDark: Boolean): ThemeProfile {
            val version = root.opt("schemaVersion")
            require(version == null || (version is Number && version.toDouble() in setOf(1.0, 2.0))) { "不支持的主题格式版本" }
            root.put("schemaVersion", 2)
            val appearance = objectOrEmpty(root, "appearance")
            root.put("appearance", appearance)
            val pageJson = objectOrEmpty(appearance, "pages")
            val legacy = root.opt("wallpaper")
            if (!pageJson.has("common") && legacy is String && legacy.isNotBlank()) {
                pageJson.put("common", JSONObject().put("kind", "image").put("asset", legacy))
            }
            for (page in PAGE_KEYS) {
                val old = root.opt("wallpaper_$page")
                if (!pageJson.has(page) && old is String && old.isNotBlank()) {
                    pageJson.put(page, JSONObject().put("kind", "image").put("asset", old))
                }
            }
            appearance.put("pages", pageJson)
            val pages = (PAGE_KEYS + "common").mapNotNull { key ->
                pageJson.opt(key)?.let { key to parseBackground(requireObject(it, "pages.$key")) }
            }.toMap()
            val backgrounds = BACKGROUNDS.mapValues { (group, keys) ->
                val values = objectOrEmpty(appearance, group)
                keys.mapNotNull { key -> values.opt(key)?.let { key to parseBackground(requireObject(it, "$group.$key")) } }.toMap()
            }
            val colors = COLORS.mapValues { (group, keys) ->
                val values = objectOrEmpty(appearance, group)
                keys.mapNotNull { key -> values.opt(key)?.let { key to parseColor(requireObject(it, "$group.$key")) } }.toMap()
            }
            val enabled = GROUPS.associateWith { group ->
                val value = objectOrEmpty(appearance, group).opt("enabled")
                require(value == null || value is Boolean) { "$group.enabled 必须是布尔值" }
                value != false
            }
            objectOrEmpty(appearance, "fab").opt("iconAsset")?.let { require(it is String) { "fab.iconAsset 必须是文字" }; assetPath(it) }
            val modeJson = objectOrEmpty(appearance, "mode")
            val policy = modeJson.opt("policy") ?: "system"
            require(policy in setOf("system", "light", "dark", "time")) { "未知深浅色策略" }
            val day = ThemeSchedule.parseTime(stringOrDefault(modeJson, "dayStart", "07:00"))
            val night = ThemeSchedule.parseTime(stringOrDefault(modeJson, "nightStart", "22:00"))
            require(day != night) { "日间和夜间起点不能相同" }
            val disabled = stringList(modeJson, "disableOnDark", null).filterNotNull().toSet()
            require(disabled.all { it in GROUPS }) { "深色禁用分组无效" }
            val mode = Mode(policy as String, day, night, disabled)
            val chrome = objectOrEmpty(appearance, "chrome")
            val labels = stringList(chrome, "tabLabels", 4)
            val icons = objectOrEmpty(chrome, "tabIcons")
            val legacyIcons = stringList(root, "tab", 4).map { it?.let(::assetPath) }
            val normal = if (icons.has("normal")) stringList(icons, "normal", 4).map { it?.let(::assetPath) } else legacyIcons
            val selected = if (icons.has("selected")) stringList(icons, "selected", 4).map { it?.let(::assetPath) } else legacyIcons
            val overrides = objectOrEmpty(modeJson, "darkOverrides")
            val dark = if (makeDark && (overrides.length() > 0 || disabled.isNotEmpty())) {
                val changed = JSONObject(root.toString())
                val effective = changed.getJSONObject("appearance")
                merge(effective, overrides)
                for (group in disabled) {
                    objectOrEmpty(effective, group).also { values ->
                        values.put("enabled", false)
                        effective.put(group, values)
                    }
                }
                parseInternal(changed, false)
            } else null
            return ThemeProfile(root.toString(), pages, backgrounds, colors, enabled, labels, normal, selected, mode, dark)
        }

        private fun parseBackground(value: JSONObject): BackgroundRule = when (value.opt("kind")) {
            "inherit" -> BackgroundRule.Inherit
            "native" -> BackgroundRule.Native
            "transparent" -> BackgroundRule.Transparent
            "image" -> {
                val opacity = value.opt("opacity") ?: 1.0
                require(opacity is Number && opacity.toDouble().isFinite() && opacity.toDouble() in 0.0..1.0) { "图片透明度应在 0～1 之间" }
                BackgroundRule.Image(assetPath(requiredString(value, "asset")), opacity.toFloat())
            }
            "color" -> BackgroundRule.Solid(argb(requiredString(value, "value")))
            else -> throw IllegalArgumentException("未知背景规则")
        }

        private fun parseColor(value: JSONObject): ColorRule = when (value.opt("mode")) {
            "inherit" -> ColorRule.Inherit
            "native" -> ColorRule.Native
            "auto" -> ColorRule.Auto
            "value" -> ColorRule.Value(argb(requiredString(value, "value")))
            else -> throw IllegalArgumentException("未知颜色规则")
        }

        private fun argb(value: String): Int {
            require(value.matches(Regex("#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})"))) { "颜色应为 #RRGGBB 或 #AARRGGBB" }
            val color = value.substring(1).toLong(16).toInt()
            return if (value.length == 7) color or 0xff000000.toInt() else color
        }

        internal fun assetPath(value: String): String {
            val normalized = value.replace('\\', '/')
            val parts = normalized.split('/')
            require(normalized.isNotBlank() && ':' !in normalized && '\u0000' !in normalized &&
                parts.none { it.isBlank() || it == "." || it == ".." }) { "素材路径必须是包内相对路径" }
            return normalized
        }

        private fun objectOrEmpty(parent: JSONObject, key: String): JSONObject =
            parent.opt(key)?.let { requireObject(it, key) } ?: JSONObject()

        private fun requireObject(value: Any, label: String): JSONObject {
            require(value is JSONObject) { "$label 必须是对象" }
            return value
        }

        private fun requiredString(parent: JSONObject, key: String): String {
            val value = parent.opt(key)
            require(value is String) { "$key 必须是文字" }
            return value
        }

        private fun stringOrDefault(parent: JSONObject, key: String, fallback: String): String =
            if (parent.has(key)) requiredString(parent, key) else fallback

        private fun stringList(parent: JSONObject, key: String, maximum: Int?): List<String?> {
            val raw = parent.opt(key) ?: return emptyList()
            require(raw is JSONArray) { "$key 必须是列表" }
            require(maximum == null || raw.length() <= maximum) { "$key 最多 $maximum 项" }
            return (0 until raw.length()).map { index ->
                val value = raw.opt(index)
                require(value == JSONObject.NULL || value is String) { "$key 包含无效项目" }
                value as? String
            }
        }

        private fun merge(destination: JSONObject, changes: JSONObject) {
            for (key in changes.keys()) {
                val value = changes.get(key)
                val old = destination.opt(key)
                if (old is JSONObject && value is JSONObject) merge(old, value)
                else destination.put(key, value)
            }
        }
    }
}
