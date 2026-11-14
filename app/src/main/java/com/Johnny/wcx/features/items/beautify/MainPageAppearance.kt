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
        var titleColor: Int? = null
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
        if (!enabled || (bitmap?.takeUnless { it.isRecycled } == null && titleColor == null)) {
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
        state.titleColor = titleColor?.or(0xff000000.toInt())
        state.shouldDraw = shouldDraw
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
        if (bitmap == null && state.titleColor == null) {
            restore(state)
            return
        }
        state.generation++
        state.visited = 0
        val queue = ArrayDeque<Node>()
        queue.add(Node(decor, 0, false))
        val rect = Rect()
        while (queue.isNotEmpty() && state.visited < MAX_NODES) {
            val node = queue.removeFirst()
            val view = node.view
            state.visited++
            if (node.depth > MAX_DEPTH || view.visibility != View.VISIBLE || !view.getGlobalVisibleRect(rect)) continue
            val inTab = node.inTab || isNativeTab(view)
            if (view is TextView) styleText(state, view, rect, bounds, bitmap, inTab)
            if (view is ImageView && (inTab || isHeaderIcon(view, rect, bounds))) {
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
        styleSystemIcons(state, decor, bounds, bitmap)
    }

    private fun styleText(state: State, view: TextView, rect: Rect, bounds: Rect, bitmap: Bitmap?, inTab: Boolean) {
        val existing = state.texts[view]
        if (existing != null && view.textColors !== existing.installed) existing.original = view.textColors
        val original = existing?.original ?: view.textColors
        val nativeColor = original.getColorForState(view.drawableState, original.defaultColor)
        if (inTab && state.titleColor == null && hasFeedback(view)) return
        val sp = view.textSize / view.resources.displayMetrics.scaledDensity
        val tabLabel = inTab && view.text.toString().trim() in tabLabels
        if (sp < 14.5f && !isNeutral(nativeColor) && !(tabLabel && state.titleColor != null)) return
        val primary = sp >= 14.5f || tabLabel || rect.bottom <= bounds.top + bounds.height() * 0.13f
        val color = if (primary && state.titleColor != null) state.titleColor else automaticColor(state, view, bitmap, bounds, rect)
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
        if (isAvatar(view) || (inTab && state.titleColor == null && hasFeedback(view))) return
        val existing = state.icons[view]
        val native = if (existing != null) existing.native else nativeIcon(view)
        val currentColor = native?.color(view)
        if (native != null && currentColor == null) return
        if (existing != null) {
            if (native != null && currentColor != existing.installedColor) existing.originalColor = currentColor
            if (native == null && view.colorFilter !== existing.installedFilter) existing.originalFilter = view.colorFilter
        }
        if (state.titleColor == null) {
            val nativeColor = if (native != null) existing?.originalColor ?: currentColor else {
                val tint = view.imageTintList
                val originalFilter = if (existing != null) existing.originalFilter else view.colorFilter
                tint?.getColorForState(view.drawableState, tint.defaultColor) ?: filterColor(originalFilter)
            }
            if (nativeColor != null && !isNeutral(nativeColor)) return
        }
        val color = state.titleColor ?: automaticColor(state, view, bitmap, bounds, rect) ?: return
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
            if (current === decor && bitmap != null) return contrastColor(bitmap, bounds, region)
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
            if (current === decor) return contrastColor(bitmap, bounds, region)
            current = current.parent as? View
        }
        return null
    }

    /** 使用整张 decor 的 CENTER_CROP 映射，而不是把图片重新拉伸到每个控件。 */
    private fun contrastColor(bitmap: Bitmap?, bounds: Rect, region: Rect): Int? {
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
                val color = bitmap.getPixel(px, py)
                if (Color.alpha(color) >= 128) {
                    light += luminance(color)
                    samples++
                }
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
        val status = state.titleColor ?: contrastColor(bitmap, bounds, Rect(bounds.left, bounds.top, bounds.right, bounds.top + height))
        val nav = state.titleColor ?: contrastColor(bitmap, bounds, Rect(bounds.left, bounds.bottom - height, bounds.right, bounds.bottom))
        if (status == null && nav == null) {
            restoreSystemIcons(state, decor)
            return
        }
        val current = decor.systemUiVisibility
        rememberSystemBits(state, current)
        var bits = current and SYSTEM_ICON_MASK
        if (status != null) bits = if (luminance(status) < 0.5) bits or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else bits and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        if (nav != null) bits = if (luminance(nav) < 0.5) bits or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else bits and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
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
