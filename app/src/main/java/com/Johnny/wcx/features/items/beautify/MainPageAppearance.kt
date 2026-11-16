package com.Johnny.wcx.features.items.beautify

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.ArrayDeque
import java.util.WeakHashMap
import kotlin.math.max
import kotlin.math.pow

/** 主页面局部文字适配；保留宿主原值，离开主页面时逐项还原。 */
internal object MainPageAppearance {
    private const val MAX_NODES = 600
    private const val MAX_DEPTH = 20
    private const val BOTTOM_TAB_CLASS = "com.tencent.mm.ui.LauncherUIBottomTabView"
    private const val SYSTEM_ICON_MASK = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
        View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    private val tabLabels = setOf("微信", "通讯录", "发现", "我", "WeChat", "Contacts", "Discover", "Me")

    private class TextPatch(var original: ColorStateList) {
        var installed: ColorStateList? = null
        var color: Int? = null
        var seen = 0
    }

    private class NativeIcon(val getter: Method, val setter: Method) {
        fun color(view: ImageView): Int? = runCatching { getter.invoke(view) as? Int }.getOrNull()
        fun set(view: ImageView, color: Int): Boolean = runCatching { setter.invoke(view, color) }.isSuccess
    }

    private class IconPatch(
        val native: NativeIcon?,
        var originalColor: Int?,
        var originalFilter: ColorFilter?
    ) {
        var installedColor: Int? = null
        var installedFilter: ColorFilter? = null
        var seen = 0
    }

    private class State(decor: View, var shouldDraw: () -> Boolean) {
        val decor = WeakReference(decor)
        val texts = WeakHashMap<TextView, TextPatch>()
        val icons = WeakHashMap<ImageView, IconPatch>()
        var bitmap: Bitmap? = null
        var opacity = 1f
        var backdrop: Int? = null
        var titleColor: Int? = null
        var profile: ThemeProfile? = null
        var textEnabled = true
        val tabAssets = MainTabAssets()
        val backgrounds = MainSurfaceBackgrounds()
        var observer: WeakReference<ViewTreeObserver>? = null
        var listener: ViewTreeObserver.OnPreDrawListener? = null
        var attachListener: View.OnAttachStateChangeListener? = null
        var originalSystemBits: Int? = null
        var installedSystemBits: Int? = null
        var generation = 0
        var visited = 0
    }

    // The decor's observer owns State. Weak values also permit a gate closure that captures Activity.
    private val states = WeakHashMap<View, WeakReference<State>>()

    fun apply(activity: Activity, bitmap: Bitmap?, enabled: Boolean, titleColor: Int?, shouldDraw: () -> Boolean) {
        apply(activity, bitmap, enabled, titleColor, null, shouldDraw)
    }

    fun apply(activity: Activity, bitmap: Bitmap?, enabled: Boolean, titleColor: Int?, profile: ThemeProfile?, shouldDraw: () -> Boolean) {
        apply(activity, bitmap, enabled, titleColor, profile, 1f, null, ThemeStore.mainWallpaperEnabled(), shouldDraw)
    }

    fun apply(activity: Activity, bitmap: Bitmap?, enabled: Boolean, titleColor: Int?, profile: ThemeProfile?, opacity: Float, backdrop: Int?, backgroundsEnabled: Boolean, shouldDraw: () -> Boolean) {
        if ((!enabled || (bitmap?.takeUnless { it.isRecycled } == null && titleColor == null && !hasProfileColors(profile))) && !MainTabAssets.configured(profile) && !MainSurfaceBackgrounds.configured(profile)) {
            clear(activity)
            return
        }
        val decor = activity.window?.decorView ?: return
        val state = states[decor]?.get() ?: State(decor, shouldDraw).also { created ->
            states[decor] = WeakReference(created)
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    observe(created, view)
                    refresh(created)
                }

                override fun onViewDetachedFromWindow(view: View) {
                    removeListener(created)
                    restore(created)
                }
            }
            created.attachListener = listener
            decor.addOnAttachStateChangeListener(listener)
        }
        state.bitmap = bitmap?.takeUnless { it.isRecycled }
        state.opacity = opacity.coerceIn(0f, 1f)
        state.backdrop = backdrop
        state.titleColor = titleColor?.or(0xff000000.toInt())
        state.profile = profile
        state.textEnabled = enabled
        state.tabAssets.configure(profile)
        state.tabAssets.resolve = ThemeRuntime.current(activity)?.let { runtime -> { path -> runtime.asset(path) } }
        state.backgrounds.configure(if (backgroundsEnabled) profile else null)
        state.backgrounds.resolve = state.tabAssets.resolve
        state.shouldDraw = shouldDraw
        (decor as? ViewGroup)?.let { ThemeHostAdapter.trackAppearance(it, state.shouldDraw) }
        observe(state, decor)
        refresh(state)
    }

    private fun observe(state: State, decor: View) {
        val observer = decor.viewTreeObserver
        if (state.observer?.get() !== observer) {
            removeListener(state)
            val listener = ViewTreeObserver.OnPreDrawListener {
                refresh(state)
                true
            }
            state.listener = listener
            state.observer = WeakReference(observer)
            observer.addOnPreDrawListener(listener)
        }
    }

    private class Node(val view: View, val depth: Int, val inTab: Boolean)

    private fun refresh(state: State) {
        val decor = state.decor.get() ?: return
        if (!decor.isShown || !runCatching { state.shouldDraw() }.getOrDefault(false)) {
            restore(state)
            return
        }
        if (decor.width <= 0 || decor.height <= 0) return
        val location = IntArray(2)
        decor.getLocationOnScreen(location)
        val bounds = Rect(location[0], location[1], location[0] + decor.width, location[1] + decor.height)
        val bitmap = state.bitmap?.takeUnless { it.isRecycled }
        if (bitmap == null && state.titleColor == null && !hasProfileColors(state.profile) && !MainTabAssets.configured(state.profile) && !MainSurfaceBackgrounds.configured(state.profile)) {
            restore(state)
            return
        }
        state.generation++
        state.visited = 0
        val wallpaperActive = bitmap != null && runCatching { state.shouldDraw() }.getOrDefault(false)
        val queue = ArrayDeque<Node>()
        queue.add(Node(decor, 0, false))
        val rect = Rect()
        while (queue.isNotEmpty() && state.visited < MAX_NODES) {
            val node = queue.removeFirst()
            val view = node.view
            state.visited++
            if (node.depth > MAX_DEPTH || view.visibility != View.VISIBLE || !view.getGlobalVisibleRect(rect)) continue
            val inTab = node.inTab || isNativeTab(view)
            // 全屏壁纸模式下 ListView 底板交给 underlay（透明补丁）：这里每帧按主题规则重刷，
            // 注册顺序又在 underlay 之后，会把 ListView 灰底刷回来，壁纸永远透不出来。
            // 只跳 ListView；底栏与顶栏在规则为 Native/Inherit 时释放归属给 underlay，
            // 包里配了纯色的不动；壁纸关掉后下次刷新恢复原规则。
            val nativeTabChrome = ThemeHostAdapter.isBottomTab(view) &&
                (state.profile?.background("chrome", "tabBackground") ?: BackgroundRule.Inherit)
                    .let { it === BackgroundRule.Native || it === BackgroundRule.Inherit }
            val nativeToolbarChrome = MainSurfaceBackgrounds.isToolbar(view) &&
                (state.profile?.background("chrome", "toolbarBackground") ?: BackgroundRule.Inherit)
                    .let { it === BackgroundRule.Native || it === BackgroundRule.Inherit }
            if (wallpaperActive && (nativeTabChrome || nativeToolbarChrome)) {
                if (state.backgrounds.releaseForWallpaper(view)) {
                    (decor as? ViewGroup)?.let { PageWallpaperUnderlay.markDirtyAfterSettle(it, 0L) }
                }
            } else if (!(view is ListView && wallpaperActive)) state.backgrounds.apply(view)
            if (view is TextView && inTab) state.tabAssets.apply(view, selectedTab(view))
            if (state.textEnabled && view is TextView) styleText(state, view, rect, bounds, bitmap, inTab)
            if (state.textEnabled && view is ImageView && (inTab || isHeaderIcon(view, rect, bounds))) {
                styleIcon(state, view, rect, bounds, bitmap, inTab)
            }
            if (view is ViewGroup && node.depth < MAX_DEPTH) {
                for (i in 0 until view.childCount) {
                    if (queue.size + state.visited >= MAX_NODES) break
                    val child = view.getChildAt(i) ?: continue
                    if (child.visibility == View.VISIBLE) queue.add(Node(child, node.depth + 1, inTab))
                }
            }
        }
        val texts = state.texts.entries.iterator()
        while (texts.hasNext()) {
            val entry = texts.next()
            if (entry.value.seen != state.generation) {
                restoreText(entry.key, entry.value)
                texts.remove()
            }
        }
        val icons = state.icons.entries.iterator()
        while (icons.hasNext()) {
            val entry = icons.next()
            if (entry.value.seen != state.generation) {
                restoreIcon(entry.key, entry.value)
                icons.remove()
            }
        }
        if (state.textEnabled) styleSystemIcons(state, decor, bounds, bitmap) else restoreSystemIcons(state, decor)
    }

    private fun styleText(state: State, view: TextView, rect: Rect, bounds: Rect, bitmap: Bitmap?, inTab: Boolean) {
        val existing = state.texts[view]
        if (existing != null && view.textColors !== existing.installed) existing.original = view.textColors
        val original = existing?.original ?: view.textColors
        val nativeColor = original.getColorForState(view.drawableState, original.defaultColor)
        val sp = view.textSize / view.resources.displayMetrics.scaledDensity
        val text = view.text.toString().trim()
        val tabLabel = inTab && (text in tabLabels || (0..3).any { state.profile?.tabLabel(it) == text })
        val primary = sp >= 14.5f || tabLabel || rect.bottom <= bounds.top + bounds.height() * 0.13f
        val slot = textSlot(view, rect, bounds, inTab, primary)
        val rule = if (inTab && !tabLabel) {
            if (text.matches(Regex("[0-9]+\\+?"))) {
                (state.profile?.color("chrome", "unreadText") ?: ColorRule.Native).let {
                    if (it === ColorRule.Inherit) ColorRule.Native else it
                }
            } else ColorRule.Native
        } else profileColor(state.profile, slot.first, slot.second, if (inTab && selectedTab(view)) "focused" else if (primary) "normal" else "middle")
        if (rule === ColorRule.Inherit) {
            if (inTab && state.titleColor == null && hasFeedback(view)) return
            if (sp < 14.5f && !isNeutral(nativeColor) && !(tabLabel && state.titleColor != null)) return
        }
        val color = ruleColor(rule, state, view, bitmap, bounds, rect) {
            if (primary && state.titleColor != null) state.titleColor else automaticColor(state, view, bitmap, bounds, rect)
        }
        if (color == null) return
        val patch = existing ?: TextPatch(original).also { state.texts[view] = it }
        patch.seen = state.generation
        if (patch.color == color && view.textColors === patch.installed) return
        val colors = ColorStateList.valueOf(color)
        view.setTextColor(colors)
        patch.installed = colors
        patch.color = color
    }

    private fun styleIcon(state: State, view: ImageView, rect: Rect, bounds: Rect, bitmap: Bitmap?, inTab: Boolean) {
        if (isAvatar(view)) return
        val slot = if (inTab) if (selectedTab(view)) "tabSelectedIcon" else "tabIcon" else "toolbarIcon"
        val rule = profileColor(state.profile, "chrome", slot, if (inTab && selectedTab(view)) "focused" else "normal")
        if (state.tabAssets.ownsIcon(view) && rule === ColorRule.Inherit) return
        if (rule === ColorRule.Inherit && inTab && state.titleColor == null && hasFeedback(view)) return
        val existing = state.icons[view]
        val native = if (existing != null) existing.native else nativeIcon(view)
        val currentColor = native?.color(view)
        if (native != null && currentColor == null) return
        if (existing != null) {
            if (native != null && currentColor != existing.installedColor) existing.originalColor = currentColor
            if (native == null && view.colorFilter !== existing.installedFilter) existing.originalFilter = view.colorFilter
        }
        if (rule === ColorRule.Inherit && state.titleColor == null) {
            val nativeColor = if (native != null) existing?.originalColor ?: currentColor else {
                val tint = view.imageTintList
                val originalFilter = if (existing != null) existing.originalFilter else view.colorFilter
                tint?.getColorForState(view.drawableState, tint.defaultColor) ?: filterColor(originalFilter)
            }
            if (nativeColor != null && !isNeutral(nativeColor)) return
        }
        val color = ruleColor(rule, state, view, bitmap, bounds, rect) {
            state.titleColor ?: automaticColor(state, view, bitmap, bounds, rect)
        } ?: return
        val patch = existing ?: IconPatch(native, currentColor, view.colorFilter).also { state.icons[view] = it }
        patch.seen = state.generation
        if (native != null) {
            if (currentColor != color && !native.set(view, color)) return
            patch.installedColor = color
        } else {
            if (patch.installedColor == color && view.colorFilter === patch.installedFilter) return
            val filter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            view.colorFilter = filter
            patch.installedFilter = filter
            patch.installedColor = color
        }
    }

    private val profileSlots = mapOf(
        "chrome" to listOf("toolbarText", "toolbarIcon", "tabText", "tabSelectedText", "tabIcon", "tabSelectedIcon", "unreadText"),
        "lists" to listOf("title", "subtitle", "time", "accountInfo", "newMessage"),
        "palette" to listOf("normal", "middle", "focused")
    )

    fun hasProfileColors(profile: ThemeProfile?): Boolean =
        profile != null && profileSlots.any { (section, keys) ->
            keys.any { key -> profile.color(section, key).let { it is ColorRule.Value || it === ColorRule.Auto } }
        }

    fun updateBackdrop(decor: View, color: Int?) { states[decor]?.get()?.backdrop = color }

    private fun profileColor(profile: ThemeProfile?, section: String, key: String, palette: String): ColorRule {
        val specific = profile?.color(section, key) ?: ColorRule.Inherit
        return if (specific === ColorRule.Inherit) profile?.color("palette", palette) ?: ColorRule.Inherit else specific
    }

    private fun ruleColor(rule: ColorRule, state: State, view: View, bitmap: Bitmap?, bounds: Rect, rect: Rect, fallback: () -> Int?): Int? =
        when (rule) {
            ColorRule.Native -> null
            ColorRule.Auto -> automaticColor(state, view, bitmap, bounds, rect)
            ColorRule.Inherit -> fallback()
            is ColorRule.Value -> rule.argb
        }

    private fun textSlot(view: TextView, rect: Rect, bounds: Rect, inTab: Boolean, primary: Boolean): Pair<String, String> {
        if (inTab) return "chrome" to if (selectedTab(view)) "tabSelectedText" else "tabText"
        if (rect.bottom <= bounds.top + bounds.height() * 0.13f) return "chrome" to "toolbarText"
        val entry = if (view.id > 0) runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull() else null
        val key = when (entry) {
            "time", "update_time", "last_update_time", "msg_time", "conversation_time", "datetime" -> "time"
            "summary", "subtitle", "description", "content_summary" -> "subtitle"
            "account_info", "wechat_id", "username_info" -> "accountInfo"
            "new_message", "unread_text" -> "newMessage"
            else -> if (primary) "title" else "subtitle"
        }
        return "lists" to key
    }

    private fun selectedTab(view: View): Boolean {
        var current: View? = view
        var depth = 0
        while (current != null && depth++ < MAX_DEPTH) {
            if (current.drawableState.any { it == android.R.attr.state_selected ||
                    it == android.R.attr.state_activated || it == android.R.attr.state_checked }) return true
            if (isNativeTab(current)) break
            current = current.parent as? View
        }
        return false
    }

    private fun nativeIcon(view: ImageView): NativeIcon? = runCatching {
        val methods = view.javaClass.methods
        val getter = methods.firstOrNull { it.name == "getIconColor" && it.parameterTypes.isEmpty() && it.returnType == Int::class.javaPrimitiveType }
            ?: return@runCatching null
        val setter = methods.firstOrNull { it.name == "setIconColor" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) }
            ?: return@runCatching null
        NativeIcon(getter, setter).takeIf { it.color(view) != null }
    }.getOrNull()

    private fun filterColor(filter: ColorFilter?): Int? = filter?.let {
        runCatching { it.javaClass.getMethod("getColor").invoke(it) as? Int }.getOrNull()
    }

    private fun isNativeTab(view: View): Boolean {
        var type: Class<*>? = view.javaClass
        while (type != null) {
            if (type.name == BOTTOM_TAB_CLASS) return true
            type = type.superclass
        }
        return false
    }

    private fun hasFeedback(view: View): Boolean {
        var current: View? = view
        var depth = 0
        while (current != null && depth++ < MAX_DEPTH) {
            if (current.drawableState.any {
                it == android.R.attr.state_selected || it == android.R.attr.state_activated ||
                    it == android.R.attr.state_checked || it == android.R.attr.state_pressed || it == android.R.attr.state_focused
            }) return true
            if (isNativeTab(current)) break
            current = current.parent as? View
        }
        return false
    }

    private fun isAvatar(view: ImageView): Boolean =
        view.javaClass.simpleName.contains("Avatar", true) || view.javaClass.simpleName.contains("HeadImage", true) ||
            view.contentDescription?.toString()?.let { it.contains("头像") || it.contains("avatar", true) } == true

    private fun isHeaderIcon(view: ImageView, rect: Rect, bounds: Rect): Boolean {
        val density = view.resources.displayMetrics.density
        return !isAvatar(view) && rect.left >= bounds.left + bounds.width() * 0.55f &&
            rect.bottom <= bounds.top + bounds.height() * 0.13f &&
            view.width in (12 * density).toInt()..(36 * density).toInt() &&
            view.height in (12 * density).toInt()..(36 * density).toInt()
    }

    private fun isNeutral(color: Int): Boolean =
        maxOf(Color.red(color), Color.green(color), Color.blue(color)) -
            minOf(Color.red(color), Color.green(color), Color.blue(color)) <= 40

    /** 保留卡片优先于壁纸；无法确定实际底色时沿用原生文字和图标。 */
    private fun automaticColor(state: State, view: View, bitmap: Bitmap?, bounds: Rect, region: Rect): Int? {
        val decor = state.decor.get()
        var current: View? = view
        var depth = 0
        while (current != null && depth++ <= MAX_DEPTH) {
            // The supplied bitmap is the decor's full-window backdrop, not a covering host card.
            if (current === decor && bitmap != null) return contrastColor(bitmap, bounds, region, state.opacity, state.backdrop)
            val background = current.background
            if (background != null && background.opacity != PixelFormat.TRANSPARENT) {
                val active = background.current
                if (active.opacity != PixelFormat.TRANSPARENT) {
                    if ((background is ColorDrawable || background is StateListDrawable) && active is ColorDrawable) {
                        if (background.alpha != 255 || active.alpha != 255) return null
                        return if (luminance(active.color) > 0.179) Color.BLACK else Color.WHITE
                    }
                    return null
                }
            }
            if (current === decor) return contrastColor(bitmap, bounds, region, state.opacity, state.backdrop)
            current = current.parent as? View
        }
        return null
    }

    /** 使用整张 decor 的 CENTER_CROP 映射，而不是把图片重新拉伸到每个控件。 */
    private fun contrastColor(bitmap: Bitmap?, bounds: Rect, region: Rect, opacity: Float = 1f, backdrop: Int? = null): Int? {
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val scale = max(bounds.width().toFloat() / bitmap.width, bounds.height().toFloat() / bitmap.height)
        val left = bounds.left + (bounds.width() - bitmap.width * scale) / 2f
        val top = bounds.top + (bounds.height() - bitmap.height * scale) / 2f
        return runCatching {
            var light = 0.0
            var samples = 0
            for (y in 0..2) for (x in 0..2) {
                val px = ((region.left + region.width() * (x + 0.5f) / 3 - left) / scale).toInt().coerceIn(0, bitmap.width - 1)
                val py = ((region.top + region.height() * (y + 0.5f) / 3 - top) / scale).toInt().coerceIn(0, bitmap.height - 1)
                val source = bitmap.getPixel(px, py)
                val alpha = Color.alpha(source) / 255.0 * opacity
                if (alpha < 1.0 && (backdrop == null || Color.alpha(backdrop) != 255)) return@runCatching null
                fun channel(value: Int, base: Int) = (value * alpha + base * (1 - alpha)).toInt()
                val base = backdrop ?: source
                val color = (0xff000000.toInt() or (channel(Color.red(source), Color.red(base)) shl 16) or
                    (channel(Color.green(source), Color.green(base)) shl 8) or channel(Color.blue(source), Color.blue(base)))
                light += luminance(color)
                samples++
            }
            if (samples == 0) null else if (light / samples > 0.179) Color.BLACK else Color.WHITE
        }.getOrNull()
    }

    private fun luminance(color: Int): Double {
        fun linear(value: Int): Double {
            val channel = value / 255.0
            return if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear(Color.red(color)) + 0.7152 * linear(Color.green(color)) + 0.0722 * linear(Color.blue(color))
    }

    private fun styleSystemIcons(state: State, decor: View, bounds: Rect, bitmap: Bitmap?) {
        val height = (bounds.height() * 0.04f).toInt().coerceAtLeast(1)
        fun iconColor(rule: ColorRule, region: Rect): Int? = when (rule) {
            ColorRule.Native -> null
            ColorRule.Auto -> contrastColor(bitmap, bounds, region, state.opacity, state.backdrop)
            ColorRule.Inherit -> state.titleColor ?: contrastColor(bitmap, bounds, region, state.opacity, state.backdrop)
            is ColorRule.Value -> rule.argb
        }
        val status = iconColor(profileColor(state.profile, "chrome", "toolbarText", "normal"),
            Rect(bounds.left, bounds.top, bounds.right, bounds.top + height))
        val nav = iconColor(profileColor(state.profile, "chrome", "tabText", "normal"),
            Rect(bounds.left, bounds.bottom - height, bounds.right, bounds.bottom))
        if (status == null && nav == null) {
            restoreSystemIcons(state, decor)
            return
        }
        val current = decor.systemUiVisibility
        rememberSystemBits(state, current)
        var bits = current and SYSTEM_ICON_MASK
        if (status != null) bits = if (luminance(status) < 0.5) bits or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else bits and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        else bits = (bits and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()) or ((state.originalSystemBits ?: 0) and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)
        if (nav != null) bits = if (luminance(nav) < 0.5) bits or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else bits and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
        else bits = (bits and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()) or ((state.originalSystemBits ?: 0) and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        state.installedSystemBits = bits
        val desired = (current and SYSTEM_ICON_MASK.inv()) or bits
        if (current != desired) decor.systemUiVisibility = desired
    }

    private fun rememberSystemBits(state: State, current: Int) {
        val original = state.originalSystemBits
        val installed = state.installedSystemBits
        if (original == null || installed == null) {
            state.originalSystemBits = current and SYSTEM_ICON_MASK
        } else {
            val changed = (current xor installed) and SYSTEM_ICON_MASK
            state.originalSystemBits = (original and changed.inv()) or (current and changed)
        }
    }

    private fun restoreSystemIcons(state: State, decor: View) {
        if (state.originalSystemBits == null) return
        val current = decor.systemUiVisibility
        rememberSystemBits(state, current)
        val desired = (current and SYSTEM_ICON_MASK.inv()) or (state.originalSystemBits ?: 0)
        if (current != desired) decor.systemUiVisibility = desired
        state.originalSystemBits = null
        state.installedSystemBits = null
    }

    private fun restoreText(view: TextView, patch: TextPatch) {
        if (view.textColors === patch.installed) view.setTextColor(patch.original)
    }

    private fun restoreIcon(view: ImageView, patch: IconPatch) {
        val native = patch.native
        if (native != null) {
            val original = patch.originalColor ?: return
            if (native.color(view) == patch.installedColor) native.set(view, original)
        } else if (view.colorFilter === patch.installedFilter) {
            view.colorFilter = patch.originalFilter
        }
    }

    private fun restore(state: State) {
        state.tabAssets.clear()
        state.backgrounds.clear()
        state.texts.forEach { (view, patch) -> restoreText(view, patch) }
        state.texts.clear()
        state.icons.forEach { (view, patch) -> restoreIcon(view, patch) }
        state.icons.clear()
        state.decor.get()?.let { restoreSystemIcons(state, it) }
        state.visited = 0
    }

    private fun removeListener(state: State) {
        val observer = state.observer?.get()
        state.listener?.let { if (observer?.isAlive == true) observer.removeOnPreDrawListener(it) }
        state.listener = null
        state.observer = null
    }

    fun clear(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        val state = states.remove(decor)?.get() ?: return
        removeListener(state)
        state.attachListener?.let { decor.removeOnAttachStateChangeListener(it) }
        state.attachListener = null
        restore(state)
    }

    fun describe(activity: Activity): String? {
        val decor = activity.window?.decorView ?: return null
        val state = states[decor]?.get() ?: return null
        return "文字=${state.texts.size} 图标=${state.icons.size} 扫描=${state.visited}"
    }

    fun isApplied(activity: Activity): Boolean {
        val decor = activity.window?.decorView ?: return false
        return states[decor]?.get() != null
    }
}
