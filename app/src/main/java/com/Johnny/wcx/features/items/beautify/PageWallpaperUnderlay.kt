package com.Johnny.wcx.features.items.beautify

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.ImageView
import com.Johnny.wcx.utils.WeLogger
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** One full-window image behind the native chrome and pages; no child insertion or reparenting. */
internal object PageWallpaperUnderlay {
    private const val TAG = "PageWallpaperUnderlay"
    private const val MAX_NODES = 1800
    private const val MAX_DEPTH = 20

    private class Patch(
        val view: WeakReference<View>,
        var original: Drawable?,
        var installed: WeakReference<Drawable>,
        var structural: Boolean
    )

    private class State(decor: ViewGroup, pager: ViewGroup, window: Window, bitmap: Bitmap, shouldDraw: () -> Boolean) {
        val decor = WeakReference(decor)
        val pager = WeakReference(pager)
        val window = WeakReference(window)
        val wallpaper = WindowWallpaper(bitmap, shouldDraw)
        var originalBackdrop = decor.background
        val patches = ArrayList<Patch>()
        val retainedRows = LinkedHashSet<String>()
        var observer: WeakReference<ViewTreeObserver>? = null
        var preDraw: ViewTreeObserver.OnPreDrawListener? = null
        var layout: ViewTreeObserver.OnGlobalLayoutListener? = null
        var scroll: ViewTreeObserver.OnScrollChangedListener? = null
        var attachListener: View.OnAttachStateChangeListener? = null
        var dirty = true
        var visited = 0
        var hostWrites = 0
        var statusColor = window.statusBarColor
        var navColor = window.navigationBarColor
        var statusContrast = Build.VERSION.SDK_INT >= 29 && window.isStatusBarContrastEnforced
        var navContrast = Build.VERSION.SDK_INT >= 29 && window.isNavigationBarContrastEnforced
        var barsInstalled = false
    }

    private val states = WeakHashMap<ViewGroup, WeakReference<State>>()
    private val restoreReasons = WeakHashMap<ViewGroup, String>()

    fun apply(activity: Activity, bitmap: Bitmap, shouldDraw: () -> Boolean): String {
        return apply(activity, bitmap, 1f, shouldDraw)
    }

    fun apply(activity: Activity, bitmap: Bitmap, opacity: Float, shouldDraw: () -> Boolean): String {
        val window = activity.window ?: return "window 不可用"
        val decor = window.decorView as? ViewGroup ?: return "decorView 不可用"
        val content = decor.findViewById<ViewGroup>(android.R.id.content) ?: return "content 不可用"
        val pager = findPager(content) ?: return "主界面分页容器未就绪"
        if (decor.width <= 0 || decor.height <= 0) return "主窗口尚未布局"
        try {
            var state = states[decor]?.get()
            if (state == null || state.pager.get() !== pager) {
                clear(activity)
                state = State(decor, pager, window, bitmap, shouldDraw)
                states[decor] = WeakReference(state)
                val installed = state
                val listener = object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(view: View) {
                        installed.pager.get()?.let { ThemeHostAdapter.track(decor, it, installed.wallpaper.shouldDraw) }
                        observe(installed, view)
                        installed.dirty = true
                        refresh(installed)
                    }

                    override fun onViewDetachedFromWindow(view: View) {
                        ThemeHostAdapter.forget(decor)
                        restoreReasons[decor] = "窗口 detach"
                        removeListeners(installed)
                        restore(installed)
                        restoreBackdrop(installed)
                        restoreBars(installed)
                    }
                }
                state.attachListener = listener
                decor.addOnAttachStateChangeListener(listener)
                WeLogger.i(TAG, "全屏底图已设置：${decor.width}x${decor.height}，未新增窗口子视图")
            }
            state.wallpaper.bitmap = bitmap
            state.wallpaper.setAlpha((opacity.coerceIn(0f, 1f) * 255).toInt())
            state.wallpaper.shouldDraw = shouldDraw
            ThemeHostAdapter.track(decor, pager, shouldDraw)
            observe(state, decor)
            state.wallpaper.invalidateSelf()
            state.dirty = true
            refresh(state)
        } catch (e: Throwable) {
            clear(activity)
            WeLogger.w(TAG, "全屏底图失败：${e.message}")
            return "全屏底图失败：${e.message}"
        }
        return "ok"
    }

    private fun observe(state: State, decor: View) {
        val observer = decor.viewTreeObserver
        if (state.observer?.get() === observer) return
        removeListeners(state)
        val preDraw = ViewTreeObserver.OnPreDrawListener { refresh(state); true }
        val layout = ViewTreeObserver.OnGlobalLayoutListener { state.dirty = true }
        val scroll = ViewTreeObserver.OnScrollChangedListener { state.dirty = true }
        state.preDraw = preDraw
        state.layout = layout
        state.scroll = scroll
        state.observer = WeakReference(observer)
        observer.addOnPreDrawListener(preDraw)
        observer.addOnGlobalLayoutListener(layout)
        observer.addOnScrollChangedListener(scroll)
    }

    private fun removeListeners(state: State) {
        state.observer?.get()?.takeIf { it.isAlive }?.let { observer ->
            state.preDraw?.let(observer::removeOnPreDrawListener)
            state.layout?.let(observer::removeOnGlobalLayoutListener)
            state.scroll?.let(observer::removeOnScrollChangedListener)
        }
        state.observer = null
        state.preDraw = null
        state.layout = null
        state.scroll = null
    }

    fun findPager(content: ViewGroup): ViewGroup? {
        var visited = 0
        fun walk(view: View, depth: Int): ViewGroup? {
            if (depth > MAX_DEPTH || visited++ >= MAX_NODES || view.visibility != View.VISIBLE) return null
            if (view !is ViewGroup) return null
            var type: Class<*>? = view.javaClass
            while (type != null) {
                if (type.simpleName.contains("ViewPager")) return view
                type = type.superclass
            }
            for (i in 0 until view.childCount) {
                walk(view.getChildAt(i) ?: continue, depth + 1)?.let { return it }
                if (visited >= MAX_NODES) break
            }
            return null
        }
        return walk(content, 0)
    }

    private fun refresh(state: State) {
        val decor = state.decor.get() ?: return
        val pager = state.pager.get() ?: return
        if (!state.wallpaper.shouldDraw() || !decor.isShown || !pager.isShown) {
            restoreReasons[decor] = "主页面守卫关闭或窗口不可见"
            restore(state)
            state.dirty = true
            restoreBackdrop(state)
            restoreBars(state)
            return
        }
        if (decor.background !== state.wallpaper) {
            if (state.wallpaper.drawCount > 0) state.hostWrites++
            state.originalBackdrop = decor.background
            MainPageAppearance.updateBackdrop(decor, (decor.background?.current as? ColorDrawable)?.takeIf { it.alpha == 255 }?.color)
            state.wallpaper.backdrop = decor.background
            setBackgroundPreservingPadding(decor, state.wallpaper)
        }
        transparentBars(state)
        // Host background replacement need not trigger layout. Maintain known surfaces every frame.
        state.patches.toList().forEach { patch ->
            val view = patch.view.get() ?: return@forEach
            if (MainSurfaceBackgrounds.owns(view)) return@forEach
            val installed = patch.installed.get()
            if (view.background !== installed) {
                state.hostWrites++
                val structural = patch.structural
                if (structural || isNeutralSurface(view.background)) makeTransparent(state, view, structural)
                else state.patches.remove(patch)
            }
        }
        if (!state.dirty) return
        state.dirty = false
        state.visited = 0
        state.retainedRows.clear()
        state.patches.removeAll { it.view.get() == null }
        val structuralTargets = ThemeHostAdapter.structuralTargets(decor, pager)
        val visibleRect = Rect()
        fun walk(view: View, depth: Int) {
            if (depth > MAX_DEPTH || state.visited++ >= MAX_NODES || view.visibility != View.VISIBLE) return
            if (view is ImageView || !view.getGlobalVisibleRect(visibleRect)) return
            if (view !== decor) {
                if (view.width < decor.width * 0.75f) return
                val structural = view in structuralTargets
                val background = view.background
                if (structural || isNeutralSurface(background)) {
                    makeTransparent(state, view, structural)
                } else if (background != null && background !is SurfaceBackground && state.retainedRows.size < 6) {
                    state.retainedRows.add("${view.javaClass.simpleName}/${background.javaClass.simpleName}")
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                walk(view.getChildAt(i) ?: continue, depth + 1)
                if (state.visited >= MAX_NODES) break
            }
        }
        walk(decor, 0)
    }

    /** Called only for a row returned by the concrete adapter of a registered main-page list. */
    fun onRowBound(decor: ViewGroup, list: ViewGroup, row: View) {
        val state = states[decor]?.get() ?: return
        if (!state.wallpaper.shouldDraw() || !decor.isShown || !list.isShown) return
        state.dirty = true
        if (row !is ImageView && isNeutralSurface(row.background)) makeTransparent(state, row, false)
    }

    @Suppress("DEPRECATION")
    private fun transparentBars(state: State) {
        val window = state.window.get() ?: return
        if (window.statusBarColor != Color.TRANSPARENT) {
            state.statusColor = window.statusBarColor
            window.statusBarColor = Color.TRANSPARENT
        }
        if (window.navigationBarColor != Color.TRANSPARENT) {
            state.navColor = window.navigationBarColor
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= 29) {
            if (!state.barsInstalled || window.isStatusBarContrastEnforced) {
                state.statusContrast = window.isStatusBarContrastEnforced
            }
            if (!state.barsInstalled || window.isNavigationBarContrastEnforced) {
                state.navContrast = window.isNavigationBarContrastEnforced
            }
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        state.barsInstalled = true
    }

    @Suppress("DEPRECATION")
    private fun restoreBars(state: State) {
        if (!state.barsInstalled) return
        val window = state.window.get() ?: return
        if (window.statusBarColor == Color.TRANSPARENT) window.statusBarColor = state.statusColor
        if (window.navigationBarColor == Color.TRANSPARENT) window.navigationBarColor = state.navColor
        if (Build.VERSION.SDK_INT >= 29) {
            if (!window.isStatusBarContrastEnforced) window.isStatusBarContrastEnforced = state.statusContrast
            if (!window.isNavigationBarContrastEnforced) window.isNavigationBarContrastEnforced = state.navContrast
        }
        state.barsInstalled = false
    }

    private fun restoreBackdrop(state: State) {
        val decor = state.decor.get() ?: return
        if (decor.background === state.wallpaper) setBackgroundPreservingPadding(decor, state.originalBackdrop)
    }

    fun setBackgroundPreservingPadding(view: View, drawable: Drawable?) {
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
        view.background = drawable
        if (view.paddingLeft != left || view.paddingTop != top || view.paddingRight != right || view.paddingBottom != bottom) {
            view.setPadding(left, top, right, bottom)
        }
    }
    private fun makeTransparent(state: State, view: View, structural: Boolean = true) {
        if (MainSurfaceBackgrounds.owns(view)) return
        val patch = state.patches.firstOrNull { it.view.get() === view }
        if (patch != null && view.background === patch.installed.get()) return
        val original = view.background
        if (original == null) {
            if (patch != null) state.patches.remove(patch)
            return
        }
        val transparent = if (structural) ColorDrawable(Color.TRANSPARENT) else SurfaceBackground(original)
        if (patch == null) {
            state.patches.add(Patch(WeakReference(view), original, WeakReference(transparent), structural))
        } else {
            patch.original = original
            patch.installed = WeakReference(transparent)
            patch.structural = structural
        }
        setBackgroundPreservingPadding(view, transparent)
        // View.setBackground clears the old drawable's callback after constructing the wrapper.
        if (transparent is SurfaceBackground) original.callback = transparent
    }

    private fun isNeutralSurface(drawable: Drawable?, depth: Int = 0): Boolean {
        if (drawable == null || depth > 4) return false
        val current = drawable.current
        if (current !== drawable) return isNeutralSurface(current, depth + 1)
        return when (drawable) {
            is ColorDrawable -> {
                val color = drawable.color
                Color.alpha(color) == 0 || (Color.alpha(color) == 255 &&
                    maxOf(Color.red(color), Color.green(color), Color.blue(color)) -
                    minOf(Color.red(color), Color.green(color), Color.blue(color)) <= 10)
            }
            is DrawableWrapper -> isNeutralSurface(drawable.drawable, depth + 1)
            is LayerDrawable -> {
                val layers = (0 until drawable.numberOfLayers).filter { drawable.getId(it) != android.R.id.mask }
                layers.isNotEmpty() && layers.all { isNeutralSurface(drawable.getDrawable(it), depth + 1) }
            }
            else -> false
        }
    }

    /** Suppress only idle neutral fill; the platform wrapper forwards padding, states and ripple callbacks. */
    private class SurfaceBackground(original: Drawable) : DrawableWrapper(original) {
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = if (drawsBackground()) PixelFormat.TRANSLUCENT else PixelFormat.TRANSPARENT

        override fun draw(canvas: Canvas) {
            if (drawsBackground()) super.draw(canvas)
        }

        private fun drawsBackground(): Boolean =
            state.any {
                it == android.R.attr.state_pressed || it == android.R.attr.state_focused
            } || !isNeutralSurface(drawable)
    }

    private fun restore(state: State) {
        state.patches.asReversed().forEach { patch ->
            val view = patch.view.get() ?: return@forEach
            if (view.background === patch.installed.get()) {
                runCatching { setBackgroundPreservingPadding(view, patch.original) }
                    .onFailure { WeLogger.w(TAG, "恢复页面底色失败：${it.message}") }
            }
        }
        state.patches.clear()
    }


    fun clear(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        ThemeHostAdapter.forget(decor)
        val state = states.remove(decor)?.get() ?: return
        restoreReasons[decor] = "clear（关闭、切换模式或离开主页）"
        removeListeners(state)
        state.attachListener?.let { decor.removeOnAttachStateChangeListener(it) }
        state.attachListener = null
        restore(state)
        restoreBackdrop(state)
        restoreBars(state)
    }

    fun originalBackground(view: View): Drawable? {
        states.values.forEach { ref ->
            ref.get()?.patches?.firstOrNull { it.view.get() === view }?.let { patch ->
                if (view.background === patch.installed.get()) return patch.original
            }
        }
        return view.background
    }

    fun backdropColor(activity: Activity): Int? {
        val decor = activity.window?.decorView as? ViewGroup ?: return null
        val original = states[decor]?.get()?.originalBackdrop ?: decor.background
        return (original?.current as? ColorDrawable)?.takeIf { it.alpha == 255 }?.color
    }

    fun isAttached(activity: Activity): Boolean {
        val decor = activity.window?.decorView as? ViewGroup ?: return false
        val state = states[decor]?.get() ?: return false
        return decor.background === state.wallpaper && state.pager.get()?.isShown == true && decor.isShown
    }

    fun describe(activity: Activity): String? {
        val decor = activity.window?.decorView as? ViewGroup ?: return null
        val state = states[decor]?.get() ?: return restoreReasons[decor]?.let { "最近恢复原因=$it" }
        return "全屏底图=${if (isAttached(activity)) "已挂载" else "已撤下或被覆盖"} " +
            "窗口=${decor.width}x${decor.height} 图片=${state.wallpaper.bitmap.width}x${state.wallpaper.bitmap.height} " +
            "绘制=${state.wallpaper.drawCount}次 跳过=${state.wallpaper.skipCount}次 " +
            "检查=${state.visited} 透明底色=${state.patches.size} " +
            "宿主回写=${state.hostWrites} 最近恢复原因=${restoreReasons[decor] ?: "无"} " +
            "行底色=${state.patches.count { !it.structural }} " +
            "保留行背景=${state.retainedRows.joinToString(",").ifEmpty { "无" }}"
    }

    private class WindowWallpaper(var bitmap: Bitmap, var shouldDraw: () -> Boolean) : Drawable() {
        var backdrop: Drawable? = null
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        private val source = Rect()
        var drawCount = 0
            private set
        var skipCount = 0
            private set

        override fun draw(canvas: Canvas) {
            if (!shouldDraw()) { skipCount++; return }
            val target = bounds
            if (bitmap.isRecycled || target.width() <= 0 || target.height() <= 0) return
            backdrop?.let { original ->
                original.setBounds(target.left, target.top, target.right, target.bottom)
                original.draw(canvas)
            }
            val ratio = target.width().toFloat() / target.height()
            if (bitmap.width.toFloat() / bitmap.height > ratio) {
                val width = (bitmap.height * ratio).toInt().coerceAtLeast(1)
                val left = (bitmap.width - width) / 2
                source.set(left, 0, left + width, bitmap.height)
            } else {
                val height = (bitmap.width / ratio).toInt().coerceAtLeast(1)
                val top = (bitmap.height - height) / 2
                source.set(0, top, bitmap.width, top + height)
            }
            drawCount++
            canvas.drawBitmap(bitmap, source, target, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha.coerceIn(0, 255); invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
