package com.Johnny.wcx.features.items.beautify

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import com.Johnny.wcx.utils.WeLogger
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** A local wallpaper layer behind the main pager; never insert into DecorView or content. */
internal object PageWallpaperUnderlay {
    private const val TAG = "PageWallpaperUnderlay"
    private const val MAX_NODES = 240
    private const val MAX_DEPTH = 12

    private class Patch(
        val view: WeakReference<View>,
        var original: Drawable?,
        var installed: WeakReference<Drawable>
    )

    private class State(host: FrameLayout, pager: ViewGroup, image: WallpaperView) {
        val host = WeakReference(host)
        val pager = WeakReference(pager)
        val image = WeakReference(image)
        val patches = ArrayList<Patch>()
        var observer: WeakReference<ViewTreeObserver>? = null
        var listener: ViewTreeObserver.OnPreDrawListener? = null
        var visited = 0
    }

    private val states = WeakHashMap<ViewGroup, State>()

    fun apply(activity: Activity, bitmap: Bitmap, shouldDraw: () -> Boolean): String {
        val decor = activity.window?.decorView as? ViewGroup ?: return "decorView 不可用"
        val content = decor.findViewById<ViewGroup>(android.R.id.content) ?: return "content 不可用"
        val pager = findPager(content) ?: return "主界面分页容器未就绪"
        val host = findHost(pager, content) ?: return "分页区域没有安全的 FrameLayout"
        if (host.width <= 0 || host.height <= 0) return "分页区域尚未布局"

        try {
            var state = states[decor]
            if (state == null || state.host.get() !== host || state.pager.get() !== pager || state.image.get()?.parent !== host) {
                clear(activity)
                val image = WallpaperView(host, shouldDraw)
                state = State(host, pager, image)
                states[decor] = state
                host.addView(image, 0, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                ))
                val installed = state
                val listener = ViewTreeObserver.OnPreDrawListener {
                    refresh(installed)
                    true
                }
                state.listener = listener
                state.observer = WeakReference(host.viewTreeObserver)
                host.viewTreeObserver.addOnPreDrawListener(listener)
                WeLogger.i(TAG, "分页底图 ImageView 已插入：${host.javaClass.name} ${host.width}x${host.height}")
            }
            state.image.get()?.apply {
                this.shouldDraw = shouldDraw
                setImageBitmap(bitmap)
            }
            refresh(state)
        } catch (e: Throwable) {
            clear(activity)
            WeLogger.w(TAG, "页面底图失败：${e.message}")
            return "页面底图失败：${e.message}"
        }
        return "ok"
    }

    private fun findPager(content: ViewGroup): ViewGroup? {
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

    private fun findHost(pager: ViewGroup, content: ViewGroup): FrameLayout? {
        var parent = pager.parent as? ViewGroup
        while (parent != null && parent !== content) {
            if (parent is FrameLayout) return parent
            parent = parent.parent as? ViewGroup
        }
        return null
    }

    private fun refresh(state: State) {
        val host = state.host.get() ?: return
        val pager = state.pager.get() ?: return
        val image = state.image.get() ?: return
        if (image.parent !== host) return
        // Visibility also invalidates cached hardware display lists; onDraw alone is not a gate.
        val draw = image.shouldDraw() && pager.isShown && host.isShown
        val visibility = if (draw) View.VISIBLE else View.INVISIBLE
        if (image.visibility != visibility) {
            image.visibility = visibility
            image.invalidate()
        }
        if (!draw) {
            restore(state)
            return
        }
        state.visited = 0
        state.patches.removeAll { it.view.get() == null }
        fun walk(view: View, depth: Int) {
            if (depth > MAX_DEPTH || state.visited++ >= MAX_NODES || view.visibility != View.VISIBLE) return
            if (view !is ViewGroup) return
            // Structural page/list surfaces only; never clear rows, cards, text or bubbles.
            if (view.width < host.width * 0.75f || view.height < host.height * 0.45f) return
            makeTransparent(state, view)
            for (i in 0 until view.childCount) {
                walk(view.getChildAt(i) ?: continue, depth + 1)
                if (state.visited >= MAX_NODES) break
            }
        }
        // Clear the short ancestor chain between the image and pager as well as page surfaces.
        var ancestor = pager.parent as? ViewGroup
        while (ancestor != null && ancestor !== host) {
            makeTransparent(state, ancestor)
            ancestor = ancestor.parent as? ViewGroup
        }
        walk(pager, 0)
    }

    private fun makeTransparent(state: State, view: View) {
        val patch = state.patches.firstOrNull { it.view.get() === view }
        if (patch != null && view.background === patch.installed.get()) return
        val original = view.background
        if (original == null) {
            if (patch != null) state.patches.remove(patch)
            return
        }
        val transparent = ColorDrawable(Color.TRANSPARENT)
        if (patch == null) {
            state.patches.add(Patch(WeakReference(view), original, WeakReference(transparent)))
        } else {
            patch.original = original
            patch.installed = WeakReference(transparent)
        }
        view.background = transparent
    }

    private fun restore(state: State) {
        state.patches.asReversed().forEach { patch ->
            val view = patch.view.get() ?: return@forEach
            if (view.background === patch.installed.get()) {
                runCatching { view.background = patch.original }
                    .onFailure { WeLogger.w(TAG, "恢复页面底色失败：${it.message}") }
            }
        }
        state.patches.clear()
    }

    fun clear(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val state = states.remove(decor) ?: return
        state.listener?.let { listener ->
            state.observer?.get()?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
        }
        state.image.get()?.let { image ->
            (image.parent as? ViewGroup)?.removeView(image)
            image.setImageBitmap(null)
        }
        restore(state)
    }

    fun isAttached(activity: Activity): Boolean {
        val decor = activity.window?.decorView as? ViewGroup ?: return false
        val state = states[decor] ?: return false
        val host = state.host.get() ?: return false
        return state.image.get()?.parent === host && state.pager.get()?.isShown == true && host.isShown
    }

    fun describe(activity: Activity): String? {
        val decor = activity.window?.decorView as? ViewGroup ?: return null
        val state = states[decor] ?: return null
        val host = state.host.get()
        val image = state.image.get()
        return "页面底图=${if (isAttached(activity)) "已挂载" else "图层已脱离"} " +
            "容器=${host?.javaClass?.name} 尺寸=${image?.width}x${image?.height} 可见=${image?.isShown} " +
            "绘制=${image?.drawCount}次 跳过=${image?.skipCount}次 " +
            "检查=${state.visited} 透明底色=${state.patches.size}"
    }

    private class WallpaperView(host: FrameLayout, var shouldDraw: () -> Boolean) : ImageView(host.context) {
        var drawCount = 0
            private set
        var skipCount = 0
            private set

        init {
            scaleType = ScaleType.CENTER_CROP
            isClickable = false
            isLongClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            if (!shouldDraw()) {
                skipCount++
                return
            }
            if (width <= 0 || height <= 0) return
            drawCount++
            super.onDraw(canvas)
        }
    }
}
