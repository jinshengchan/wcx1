package com.Johnny.wcx.features.items.beautify

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.NinePatchDrawable
import android.graphics.drawable.StateListDrawable
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Classifies native wallpaper surfaces without mutating their live Drawable. */
internal object WallpaperDrawables {
    private class NinePatchCheck(drawable: NinePatchDrawable, source: Drawable.ConstantState, val neutral: Boolean) {
        val source = WeakReference(source)
        val state = drawable.state.copyOf()
        val level = drawable.level
        val alpha = drawable.alpha
        val filter = drawable.colorFilter
    }

    private val ninePatchChecks = WeakHashMap<NinePatchDrawable, NinePatchCheck>()

    private class GradientCheck(drawable: GradientDrawable, source: Drawable.ConstantState, val neutral: Boolean, val sample: Int) {
        val source = WeakReference(source)
        val state = drawable.state.copyOf()
        val level = drawable.level
        val alpha = drawable.alpha
        val filter = drawable.colorFilter
    }

    private val gradientChecks = WeakHashMap<GradientDrawable, GradientCheck>()

    fun whiteSelector(drawable: Drawable?): Boolean {
        val color = when (drawable) {
            is StateListDrawable -> (drawable.current as? ColorDrawable)?.color
            is ColorDrawable -> drawable.color
            else -> return false
        } ?: return false
        return Color.alpha(color) == 255 &&
            Color.red(color) >= 248 && Color.green(color) >= 248 && Color.blue(color) >= 248
    }

    /** A bounded probe of a CLONED drawable; never samples or mutates the live view. */
    fun isFaintCardNinePatch(drawable: NinePatchDrawable): Boolean = runCatching {
        val source = drawable.constantState ?: return@runCatching false
        val probe = source.newDrawable().mutate()
        probe.state = drawable.state
        probe.level = drawable.level
        probe.setAlpha(drawable.alpha)
        val bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888)
        try {
            probe.setBounds(0, 0, 12, 12)
            probe.draw(Canvas(bitmap))
            var nonEmpty = false
            for (y in 3..8) for (x in 3..8) {
                val c = bitmap.getPixel(x, y)
                val alpha = Color.alpha(c)
                if (alpha > 0) nonEmpty = true
                if (alpha > 0x33) return@runCatching false
                val spread = maxOf(Color.red(c), Color.green(c), Color.blue(c)) -
                    minOf(Color.red(c), Color.green(c), Color.blue(c))
                if (alpha > 0 && spread > 12) return@runCatching false
            }
            nonEmpty
        } finally { bitmap.recycle() }
    }.getOrDefault(false)

    /** 单色是否中性：全透明；或近灰（RGB 三通道极差 ≤ 10）且全不透明或半透明（alpha ≤ 0x33）。半透明遮罩色偏极小，放掉只让底图亮一点，不改色相。 */
    fun isNeutralColor(color: Int): Boolean {
        val spread = maxOf(Color.red(color), Color.green(color), Color.blue(color)) -
            minOf(Color.red(color), Color.green(color), Color.blue(color))
        return Color.alpha(color) == 0 || (spread <= 10 &&
            (Color.alpha(color) == 255 || Color.alpha(color) <= 0x33))
    }

    /** 保留原因诊断：纯色给色值+色偏，状态列表给当前态子类，图层列表逐层给类型+色值；进现有诊断的“保留行背景”里，不另起日志。最多展开两层，避免诊断爆炸。 */
    fun retainedDetail(drawable: Drawable, depth: Int = 0): String = when (drawable) {
        is ColorDrawable -> {
            val color = drawable.color
            String.format("(#%08X~%d)", color,
                maxOf(Color.red(color), Color.green(color), Color.blue(color)) -
                minOf(Color.red(color), Color.green(color), Color.blue(color)))
        }
        is StateListDrawable -> {
            val current = drawable.current
            "（current=${current?.javaClass?.simpleName ?: "null"}${if (depth < 2 && current != null) retainedDetail(current, depth + 1) else ""}）"
        }
        is LayerDrawable -> {
            if (depth >= 2) "" else {
                val layers = (0 until drawable.numberOfLayers)
                    .filter { drawable.getId(it) != android.R.id.mask }
                val shown = layers.take(4).joinToString(",") { i ->
                    val layer = drawable.getDrawable(i)
                    "${layer?.javaClass?.simpleName ?: "null"}${layer?.let { retainedDetail(it, depth + 1) } ?: ""}"
                }
                val more = if (layers.size > 4) ",+${layers.size - 4}" else ""
                "（layers=[$shown$more]）"
            }
        }
        is GradientDrawable -> runCatching {
            val colors = drawable.colors
            if (colors != null && colors.isNotEmpty()) "（stops=${colors.size}）"
            else "（stops=0 采样=${String.format("#%08X", gradientChecks[drawable]?.sample ?: 0)}）"
        }.getOrDefault("")
        else -> ""
    }

    fun isNeutralSurface(drawable: Drawable?, depth: Int = 0): Boolean {
        if (drawable == null || depth > 4) return false
        val current = drawable.current
        if (current !== drawable) return isNeutralSurface(current, depth + 1)
        return when (drawable) {
            is ColorDrawable -> isNeutralColor(drawable.color)
            is DrawableWrapper -> isNeutralSurface(drawable.drawable, depth + 1)
            is NinePatchDrawable -> isNeutralNinePatch(drawable)
            is GradientDrawable -> isNeutralGradient(drawable)
            is LayerDrawable -> {
                val layers = (0 until drawable.numberOfLayers).filter { drawable.getId(it) != android.R.id.mask }
                layers.isNotEmpty() && layers.all { isNeutralSurface(drawable.getDrawable(it), depth + 1) }
            }
            else -> false
        }
    }

    /**
     * 渐变是否中性：有色档时按各 stop 的近灰规则判；无色档时（setColor 纯色填充等，
     * getColors() 为 null/空）渲到小图上按像素判，与九宫格探针同款规则（只采中心，
     * 全透明跳过，半透明即非中性，RGB 极差 ≤ 10 才算中性）。探针结果按源+状态缓存，
     * 避免每次绘制都重渲；采样中心像素记下来供诊断用。
     */
    private fun isNeutralGradient(drawable: GradientDrawable): Boolean {
        val colors = runCatching { drawable.colors }.getOrNull()
        if (colors != null && colors.isNotEmpty()) return colors.all { isNeutralColor(it) }
        val source = drawable.constantState ?: return false
        gradientChecks[drawable]?.let { check ->
            if (check.source.get() === source && check.state.contentEquals(drawable.state) &&
                check.level == drawable.level && check.alpha == drawable.alpha && check.filter === drawable.colorFilter) {
                return check.neutral
            }
        }
        var sample = 0
        val neutral = runCatching {
            val probe = source.newDrawable().mutate() as? GradientDrawable ?: return@runCatching false
            probe.state = drawable.state
            probe.level = drawable.level
            probe.setAlpha(drawable.alpha)
            probe.setColorFilter(drawable.colorFilter)
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            try {
                probe.setBounds(0, 0, bitmap.width, bitmap.height)
                probe.draw(Canvas(bitmap))
                sample = bitmap.getPixel(8, 8)
                var darkest = 255
                var lightest = 0
                val inset = 4
                for (y in inset until bitmap.height - inset) for (x in inset until bitmap.width - inset) {
                    val color = bitmap.getPixel(x, y)
                    if (Color.alpha(color) == 0) continue
                    if (Color.alpha(color) != 255) return@runCatching false
                    darkest = minOf(darkest, Color.red(color), Color.green(color), Color.blue(color))
                    lightest = maxOf(lightest, Color.red(color), Color.green(color), Color.blue(color))
                    if (lightest - darkest > 10) return@runCatching false
                }
                true
            } finally { bitmap.recycle() }
        }.getOrDefault(false)
        gradientChecks[drawable] = GradientCheck(drawable, source, neutral, sample)
        return neutral
    }

    /**
     * Inspect a copy so probing never changes the host's bounds, callback or drawable state.
     * 只采中心填充区：九宫格边缘常带 1px 分割线/阴影/半透明描边，全图采样会把整张底色
     * 误判为非中性；真正盖住底图的是中间填充，边缘色偏不影响透底。
     */
    private fun isNeutralNinePatch(drawable: NinePatchDrawable): Boolean {
        val source = drawable.constantState ?: return false
        ninePatchChecks[drawable]?.let { check ->
            if (check.source.get() === source && check.state.contentEquals(drawable.state) &&
                check.level == drawable.level && check.alpha == drawable.alpha && check.filter === drawable.colorFilter) {
                return check.neutral
            }
        }
        val neutral = runCatching {
            val probe = source.newDrawable().mutate()
            probe.state = drawable.state
            probe.level = drawable.level
            probe.setAlpha(drawable.alpha)
            probe.setColorFilter(drawable.colorFilter)
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            try {
                probe.setBounds(0, 0, bitmap.width, bitmap.height)
                probe.draw(Canvas(bitmap))
                var darkest = 255
                var lightest = 0
                val inset = 4
                for (y in inset until bitmap.height - inset) for (x in inset until bitmap.width - inset) {
                    val color = bitmap.getPixel(x, y)
                    if (Color.alpha(color) == 0) continue
                    if (Color.alpha(color) != 255) return@runCatching false
                    darkest = minOf(darkest, Color.red(color), Color.green(color), Color.blue(color))
                    lightest = maxOf(lightest, Color.red(color), Color.green(color), Color.blue(color))
                    if (lightest - darkest > 10) return@runCatching false
                }
                true
            } finally { bitmap.recycle() }
        }.getOrDefault(false)
        ninePatchChecks[drawable] = NinePatchCheck(drawable, source, neutral)
        return neutral
    }

    fun resetProbes() {
        ninePatchChecks.clear()
        gradientChecks.clear()
    }
}
