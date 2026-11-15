package com.Johnny.wcx.features.items.beautify

import android.content.res.ColorStateList
import android.view.View
import android.widget.TextView
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatMessageViewApi
import com.Johnny.wcx.ui.utils.findViewWhich
import com.Johnny.wcx.utils.WeLogger
import com.tencent.mm.ui.widget.MMNeat7extView
import de.robv.android.xposed.XC_MethodHook
import java.util.WeakHashMap

/** Applies only roles whose host view can be identified and restored safely. */
internal object ChatThemeAppearance {
    private data class Saved(val owner: java.lang.ref.WeakReference<View>, val original: ColorStateList, val applied: ColorStateList)
    private val saved = WeakHashMap<TextView, Saved>()
    private var warnedUnsupported = false
    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) { restoreRow(view) }
    }

    internal sealed interface Bubble {
        data object Inherit : Bubble
        data object Native : Bubble
        data class Solid(val color: Int) : Bubble
        data class Image(val file: java.io.File, val opacity: Float = 1f) : Bubble
    }
    private var cachedId = ""
    private var cachedRevision = -1L
    private var cachedDark = false
    private var legacyLeft: java.io.File? = null
    private var legacyRight: java.io.File? = null

    fun bubble(context: android.content.Context, self: Boolean): Bubble {
        val snapshot = ThemeRuntime.current(context) ?: return Bubble.Inherit
        val profile = snapshot.profile
        if (!profile.enabled("chat")) return Bubble.Inherit
        val rule = profile.background("chat", if (self) "rightBubble" else "leftBubble")
        return when (rule) {
            BackgroundRule.Native -> Bubble.Native
            BackgroundRule.Transparent -> Bubble.Solid(android.graphics.Color.TRANSPARENT)
            is BackgroundRule.Solid -> Bubble.Solid(rule.argb)
            is BackgroundRule.Image -> snapshot.asset(rule.asset)?.let { Bubble.Image(it, rule.opacity) } ?: Bubble.Native
            BackgroundRule.Inherit -> {
                if (cachedId != snapshot.themeId || cachedRevision != snapshot.revision || cachedDark != snapshot.dark) {
                    val manifest = snapshot.saved.profile.toJson()
                    legacyLeft = manifest.optString("bubble_left").takeIf { it.isNotBlank() }?.let { snapshot.asset(it) }
                    legacyRight = manifest.optString("bubble_right").takeIf { it.isNotBlank() }?.let { snapshot.asset(it) }
                    cachedId = snapshot.themeId
                    cachedRevision = snapshot.revision
                    cachedDark = snapshot.dark
                }
                (if (self) legacyRight else legacyLeft)?.let { Bubble.Image(it) } ?: Bubble.Inherit
            }
        }
    }

    private data class BubbleSaved(val original: android.graphics.drawable.Drawable?, val applied: android.graphics.drawable.Drawable?, val padding: IntArray)
    private val bubbles = WeakHashMap<View, BubbleSaved>()
    private val bubbleDetach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) { restoreBubble(view) }
    }

    fun rememberBubble(view: View, original: android.graphics.drawable.Drawable?, padding: IntArray) {
        bubbles[view] = BubbleSaved(original, view.background, padding)
        view.addOnAttachStateChangeListener(bubbleDetach)
    }

    private fun restoreBubble(view: View) {
        val state = bubbles.remove(view) ?: return
        if (view.background === state.applied) {
            view.background = state.original
            view.setPadding(state.padding[0], state.padding[1], state.padding[2], state.padding[3])
        }
        view.removeOnAttachStateChangeListener(bubbleDetach)
    }
    fun restoreAll() {
        saved.keys.toList().forEach { restoreRow(it) }
        bubbles.keys.toList().forEach { restoreBubble(it) }
        icons.keys.toList().forEach { restoreIcon(it) }
    }

    fun restoreRow(row: View) {
        bubbles.keys.toList().forEach { bubble ->
            var current: View? = bubble
            while (current != null && current !== row) current = current.parent as? View
            if (current === row) restoreBubble(bubble)
        }
        saved.entries.toList().filter { (text, state) ->
            var current: View? = state.owner.get() ?: text
            while (current != null && current !== row) current = current.parent as? View
            current === row || text === row
        }.forEach { (text, _) -> restoreText(text) }
    }

    private fun restoreText(text: TextView) {
        val state = saved.remove(text) ?: return
        if (text.textColors == state.applied) text.setTextColor(state.original)
        state.owner.get()?.let { owner ->
            if (saved.values.none { it.owner.get() === owner }) owner.removeOnAttachStateChangeListener(detach)
        }
    }

    fun applyText(view: TextView, color: Int, owner: View = view) {
        val previous = saved[view]
        val original = previous?.takeIf { view.textColors === it.applied }?.original ?: view.textColors
        previous?.owner?.get()?.takeIf { it !== owner }?.let { old ->
            if (saved.entries.none { it.key !== view && it.value.owner.get() === old }) old.removeOnAttachStateChangeListener(detach)
        }
        val applied = ColorStateList.valueOf(color)
        val observing = saved.values.any { it.owner.get() === owner }
        saved[view] = Saved(java.lang.ref.WeakReference(owner), original, applied)
        if (!observing) owner.addOnAttachStateChangeListener(detach)
        view.setTextColor(applied)
    }
    private data class IconSaved(val original: ColorStateList?, val applied: ColorStateList)
    private val icons = WeakHashMap<android.widget.ImageView, IconSaved>()
    private val iconDetach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = Unit
        override fun onViewDetachedFromWindow(view: View) { restoreIcon(view as android.widget.ImageView) }
    }

    private fun restoreIcon(view: android.widget.ImageView) {
        val state = icons.remove(view) ?: return
        if (view.imageTintList == state.applied) view.imageTintList = state.original
        view.removeOnAttachStateChangeListener(iconDetach)
    }

    fun applyInputIcon(view: android.widget.ImageView) {
        restoreIcon(view)
        val profile = ThemeRuntime.current(view.context)?.profile ?: return
        val color = (profile.color("chat", "inputIcon") as? ColorRule.Value)?.argb ?: return
        val applied = ColorStateList.valueOf(color)
        icons[view] = IconSaved(view.imageTintList, applied)
        view.addOnAttachStateChangeListener(iconDetach)
        view.imageTintList = applied
    }
    /** The host adapter must register an identified role; unknown views are never inferred by position. */
    enum class Role(val colorKey: String, val backgroundKey: String?) {
        LEFT_TIME("leftTimeText", "leftTimeBackground"),
        RIGHT_TIME("rightTimeText", "rightTimeBackground"),
        NICKNAME("nicknameText", "nicknameBackground"),
        INPUT("inputText", "inputBackground"),
        QUOTE("quoteText", null)
    }

    fun applyRole(view: TextView, role: Role) {
        restoreRow(view)
        val profile = ThemeRuntime.current(view.context)?.profile ?: return
        val color = (profile.color("chat", role.colorKey) as? ColorRule.Value)?.argb
        val background = role.backgroundKey?.let { profile.background("chat", it) }
        if (background is BackgroundRule.Solid || background === BackgroundRule.Transparent) {
            val original = view.background
            val padding = intArrayOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom)
            val value = if (background is BackgroundRule.Solid) background.argb else android.graphics.Color.TRANSPARENT
            view.background = android.graphics.drawable.ColorDrawable(value)
            view.setPadding(padding[0], padding[1], padding[2], padding[3])
            rememberBubble(view, original, padding)
        }
        if (color != null) {
            applyText(view, color)
        }
    }
    fun apply(param: XC_MethodHook.MethodHookParam, row: View) {
        val message = WeChatMessageViewApi.getMsgInfoFromParam(param)
        applyBody(row, message.type, message.isSelfSender)
    }

    fun applyBody(row: View, type: MessageType?, self: Boolean) {
        if (type != MessageType.TEXT) return
        val neat = row.findViewWhich<MMNeat7extView> { it is MMNeat7extView } ?: return
        val text = runCatching { neat.wrappedTextView }.getOrNull()
        if (text == null) {
            if (!warnedUnsupported) {
                warnedUnsupported = true
                WeLogger.w("ChatThemeAppearance", "正文原始颜色不可读取，跳过聊天文字主题")
            }
            return
        }
        val profile = ThemeRuntime.current(row.context)?.profile ?: return
        val rule = profile.color("chat", if (self) "rightText" else "leftText")
        val color = (rule as? ColorRule.Value)?.argb ?: return
        applyText(text, color, row)
    }
}
