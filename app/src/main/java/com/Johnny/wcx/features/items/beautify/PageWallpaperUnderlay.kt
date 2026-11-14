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
import android.view.View
import android.view.ViewGroup
import com.Johnny.wcx.utils.WeLogger
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Four-tab wallpaper behind the host content, without adding or reordering WeChat views. */
internal object PageWallpaperUnderlay {
    private const val TAG = "PageWallpaperUnderlay"
    private const val MAX_NODES = 240
    private const val MAX_DEPTH = 8

    private class Patch(
        val view: WeakReference<View>,
        var original: Drawable?,
        var installed: WeakReference<Drawable>
    )

    private class State(val patches: MutableList<Patch> = ArrayList()) {
        var cleared = 0
        var visited = 0
    }

    // A state holds only weak references to views; installed drawables are released on clear.
    private val states = WeakHashMap<ViewGroup, State>()

    fun apply(activity: Activity, bitmap: Bitmap, shouldDraw: () -> Boolean): String {
        val decor = activity.window?.decorView as? ViewGroup ?: return "decorView 不可用"
        val content = decor.findViewById<ViewGroup>(android.R.id.content) ?: return "content 不可用"
        if (content.width <= 0 || content.height <= 0) return "content 尚未布局"

        val state = states.getOrPut(decor) { State() }
        val wallpaper = CenterCropWallpaper(bitmap, shouldDraw)
        try {
            replace(state, content, wallpaper)
            clearCoveringSurfaces(content, state)
        } catch (e: Throwable) {
            clear(activity)
            WeLogger.w(TAG, "页面底图失败：${e.message}")
            return "页面底图失败：${e.message}"
        }
        WeLogger.d(TAG, "页面底图已写入，检查=${state.visited}，透明底色=${state.cleared}")
        return "ok"
    }

    private fun replace(state: State, view: View, drawable: Drawable) {
        val patch = state.patches.firstOrNull { it.view.get() === view }
        if (patch == null) {
            state.patches.add(Patch(WeakReference(view), view.background, WeakReference(drawable)))
        } else {
            // A host replacement is the newest original, and must be restored on disable.
            if (view.background !== patch.installed.get()) patch.original = view.background
            patch.installed = WeakReference(drawable)
        }
        view.background = drawable
    }

    private fun clearCoveringSurfaces(content: ViewGroup, state: State) {
        state.visited = 0
        val width = content.width
        val height = content.height

        fun walk(view: View, depth: Int) {
            if (depth > MAX_DEPTH || state.visited++ >= MAX_NODES || view.visibility != View.VISIBLE) return
            if (view is ViewGroup && view !== content) {
                val broad = view.width >= width * 0.85f && view.height >= height * 0.60f
                // Only structural, screen-wide containers. Leave message rows, cards and their
                // selected/unread backgrounds intact even if they use the same surface color.
                if (broad && isPlainSurface(view.background)) {
                    replace(state, view, ColorDrawable(Color.TRANSPARENT))
                }
            }
            // Never walk rows: the pager and each full-page panel are enough. This also avoids
            // a long conversation list exhausting the budget before the other tabs are reached.
            if (view is ViewGroup && (view === content ||
                    (view.width >= width * 0.75f && view.height >= height * 0.45f))) {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i) ?: continue
                    walk(child, depth + 1)
                    if (state.visited >= MAX_NODES) break
                }
            }
        }
        walk(content, 0)
        state.cleared = state.patches.count { patch ->
            val view = patch.view.get()
            view !== content && view?.background === patch.installed.get()
        }
    }

    private fun isPlainSurface(drawable: Drawable?): Boolean {
        val color = (drawable as? ColorDrawable)?.color ?: return false
        if (Color.alpha(color) != 255) return false
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        // Neutral page surfaces only; never clear colored cards, selection or custom artwork.
        return maxOf(red, green, blue) - minOf(red, green, blue) <= 10
    }

    fun clear(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val state = states.remove(decor) ?: return
        state.patches.asReversed().forEach { patch ->
            val view = patch.view.get() ?: return@forEach
            if (view.background === patch.installed.get()) {
                runCatching { view.background = patch.original }
                    .onFailure { WeLogger.w(TAG, "恢复页面底色失败：${it.message}") }
            }
        }
        state.patches.clear()
    }

    fun isAttached(activity: Activity): Boolean =
        runCatching { states.containsKey(activity.window?.decorView as? ViewGroup) }.getOrDefault(false)

    fun describe(activity: Activity): String {
        val decor = activity.window?.decorView as? ViewGroup ?: return "页面底图=decorView 不可用"
        val state = states[decor] ?: return "页面底图=未挂载"
        val root = decor.findViewById<ViewGroup>(android.R.id.content)
        val patch = state.patches.firstOrNull { it.view.get() === root }
        val active = patch != null && root?.background === patch.installed.get()
        return "页面底图=${if (active) "已挂载" else "被宿主覆盖"} " +
            "尺寸=${root?.width}x${root?.height} 检查=${state.visited} 透明底色=${state.cleared}"
    }

    private class CenterCropWallpaper(
        private val bitmap: Bitmap,
        private val shouldDraw: () -> Boolean
    ) : Drawable() {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        private val source = Rect()

        override fun draw(canvas: Canvas) {
            if (!shouldDraw() || bitmap.isRecycled) return
            val target = bounds
            if (target.width() <= 0 || target.height() <= 0) return
            val sourceRatio = bitmap.width.toFloat() / bitmap.height
            val targetRatio = target.width().toFloat() / target.height()
            if (sourceRatio > targetRatio) {
                val cropWidth = (bitmap.height * targetRatio).toInt().coerceAtLeast(1)
                val left = (bitmap.width - cropWidth) / 2
                source.set(left, 0, left + cropWidth, bitmap.height)
            } else {
                val cropHeight = (bitmap.width / targetRatio).toInt().coerceAtLeast(1)
                val top = (bitmap.height - cropHeight) / 2
                source.set(0, top, bitmap.width, top + cropHeight)
            }
            canvas.drawBitmap(bitmap, source, target, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha.coerceIn(0, 255); invalidateSelf() }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
