package com.Johnny.wcx.features.items.beautify

import android.app.Activity
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsets
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Owns the temporary chat footer and navigation surfaces for one visible chat. */
internal object ChatFooterWallpaper {
    private val sessions = WeakHashMap<Activity, Session>()

    @Volatile
    var diagnosis = "未观测"
        private set

    private class LockedBottomDrawable : ColorDrawable(AndroidColor.TRANSPARENT) {
        override fun setColor(color: Int) {
            super.setColor(AndroidColor.TRANSPARENT)
        }
    }

    fun ownsBackground(view: View): Boolean = view.background is LockedBottomDrawable

    private class NavigationViewPatch(val alpha: Float, val visibility: Int)

    private class Session(window: Window, decor: ViewGroup) {
        val window = WeakReference(window)
        val decor = WeakReference(decor)
        val backgrounds = WeakHashMap<View, Drawable>()
        val paints = WeakHashMap<Paint, Int>()
        private val navigationViews = WeakHashMap<View, NavigationViewPatch>()
        var pending: Runnable? = null
        var navigationPreDraw: ViewTreeObserver.OnPreDrawListener? = null
        var navigationAttach: View.OnAttachStateChangeListener? = null
        private var navigationObserver: WeakReference<ViewTreeObserver>? = null
        private var unclippedChatLayout: WeakReference<ViewGroup>? = null
        @Suppress("DEPRECATION")
        private var navigationColor = window.navigationBarColor
        @Suppress("DEPRECATION")
        private var navigationDivider = window.navigationBarDividerColor
        private var navigationContrast = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            window.isNavigationBarContrastEnforced

        fun observeNavigation(root: View) {
            if (!root.isAttachedToWindow) return
            val observer = root.viewTreeObserver
            if (navigationObserver?.get() === observer) return
            stopObservingNavigation()
            navigationPreDraw?.let(observer::addOnPreDrawListener)
            navigationObserver = WeakReference(observer)
        }

        fun stopObservingNavigation() {
            val observer = navigationObserver?.get()
            if (observer?.isAlive == true) navigationPreDraw?.let(observer::removeOnPreDrawListener)
            navigationObserver = null
        }

        fun updateBottomClip(layout: ViewGroup?): Boolean {
            val previous = unclippedChatLayout?.get()
            if (previous !== layout) {
                previous?.clipToPadding = true
                unclippedChatLayout = null
            }
            if (layout?.clipToPadding == true) {
                layout.clipToPadding = false
                unclippedChatLayout = WeakReference(layout)
            }
            return unclippedChatLayout?.get() != null
        }

        @Suppress("DEPRECATION")
        fun clearNavigation(window: Window, decor: ViewGroup) {
            if (window.navigationBarColor != AndroidColor.TRANSPARENT) {
                navigationColor = window.navigationBarColor
                window.navigationBarColor = AndroidColor.TRANSPARENT
            }
            if (window.navigationBarDividerColor != AndroidColor.TRANSPARENT) {
                navigationDivider = window.navigationBarDividerColor
                window.navigationBarDividerColor = AndroidColor.TRANSPARENT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && window.isNavigationBarContrastEnforced) {
                navigationContrast = true
                window.isNavigationBarContrastEnforced = false
            }
            // This framework color view is a direct DecorView child. Keep its Drawable
            // installed because Android can recolor its divider layers in place.
            val view = (0 until decor.childCount).firstNotNullOfOrNull { index ->
                decor.getChildAt(index)?.takeIf { it.id == android.R.id.navigationBarBackground }
            } ?: return
            val previous = navigationViews[view]
            if (previous == null || view.alpha != 0f || view.visibility != View.INVISIBLE) {
                navigationViews[view] = NavigationViewPatch(
                    if (previous != null && view.alpha == 0f) previous.alpha else view.alpha,
                    if (previous != null && view.visibility == View.INVISIBLE) previous.visibility else view.visibility
                )
                view.alpha = 0f
                view.visibility = View.INVISIBLE
            }
        }

        @Suppress("DEPRECATION")
        fun restore() {
            updateBottomClip(null)
            pending?.let { decor.get()?.removeCallbacks(it) }
            pending = null
            stopObservingNavigation()
            navigationPreDraw = null
            navigationAttach?.let { decor.get()?.removeOnAttachStateChangeListener(it) }
            navigationAttach = null
            backgrounds.forEach { (view, original) ->
                if (view.background is LockedBottomDrawable) {
                    PageWallpaperUnderlay.setBackgroundPreservingPadding(view, original)
                }
            }
            paints.forEach { (paint, original) ->
                if (paint.color == AndroidColor.TRANSPARENT) paint.color = original
            }
            navigationViews.forEach { (view, original) ->
                if (view.alpha == 0f) view.alpha = original.alpha
                if (view.visibility == View.INVISIBLE) view.visibility = original.visibility
            }
            window.get()?.let { window ->
                if (window.navigationBarColor == AndroidColor.TRANSPARENT) window.navigationBarColor = navigationColor
                if (window.navigationBarDividerColor == AndroidColor.TRANSPARENT) {
                    window.navigationBarDividerColor = navigationDivider
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !window.isNavigationBarContrastEnforced) {
                    window.isNavigationBarContrastEnforced = navigationContrast
                }
            }
            decor.get()?.invalidate()
        }
    }

    fun apply(activity: Activity) {
        if (!ThemeStore.wallpaperSwitchOn() || !ThemeSurface.ownsForegroundThemeWindow(activity)) return
        val window = activity.window ?: return
        val decor = window.decorView as? ViewGroup ?: return
        val existing = sessions[activity]
        if (existing?.decor?.get() === decor && existing.window.get() === window) {
            existing.observeNavigation(decor)
            sweep(existing, window, decor)
            return
        }
        restore(activity)
        val session = Session(window, decor)
        sessions[activity] = session
        sweep(session, window, decor)

        val activityRef = WeakReference(activity)
        val navigation = ViewTreeObserver.OnPreDrawListener {
            val owner = activityRef.get()
            val root = session.decor.get()
            val liveWindow = session.window.get()
            if (owner != null && root != null && liveWindow != null && sessions[owner] === session &&
                ThemeStore.wallpaperSwitchOn() && ThemeSurface.ownsForegroundThemeWindow(owner)) {
                session.clearNavigation(liveWindow, root)
            }
            true
        }
        session.navigationPreDraw = navigation
        val attach = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = session.observeNavigation(view)
            override fun onViewDetachedFromWindow(view: View) = session.stopObservingNavigation()
        }
        session.navigationAttach = attach
        decor.addOnAttachStateChangeListener(attach)
        session.observeNavigation(decor)

        // The native footer can be laid out after the entry callback. Queue once per chat.
        val retry = Runnable {
            val owner = activityRef.get() ?: return@Runnable
            if (sessions[owner] !== session || !ThemeStore.wallpaperSwitchOn() ||
                !ThemeSurface.ownsForegroundThemeWindow(owner)) return@Runnable
            val liveDecor = session.decor.get()?.takeIf { it.isAttachedToWindow } ?: return@Runnable
            val liveWindow = session.window.get() ?: return@Runnable
            sweep(session, liveWindow, liveDecor)
        }
        session.pending = retry
        decor.post(retry)
        for (delay in longArrayOf(120L, 320L, 650L, 1100L)) decor.postDelayed(retry, delay)
    }

    fun restore(activity: Activity): Boolean {
        val session = sessions.remove(activity) ?: return false
        session.restore()
        return true
    }

    fun restoreLaunchers() {
        sessions.keys.toList().filter(ThemePages::isLauncher).forEach { restore(it) }
    }

    fun restoreAll() {
        sessions.keys.toList().forEach { restore(it) }
    }

    private fun sweep(session: Session, window: Window, decor: ViewGroup) {
        if (decor.height <= 0) return
        session.clearNavigation(window, decor)
        val loc = IntArray(2)
        val rootLoc = IntArray(2)
        decor.getLocationOnScreen(rootLoc)
        val insets = decor.rootWindowInsets
        @Suppress("DEPRECATION")
        val navigationInset = insets?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) it.getInsets(WindowInsets.Type.navigationBars()).bottom
            else it.stableInsetBottom
        }?.coerceIn(0, decor.height) ?: 0
        val imeVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && insets?.isVisible(WindowInsets.Type.ime()) == true
        val contentBottom = decor.height - navigationInset
        var visited = 0
        var cleared = 0
        var chatLayout: ViewGroup? = null
        var nativeImage: String? = null
        val chatHits = ArrayList<String>(14)
        fun bgDesc(bg: android.graphics.drawable.Drawable?): String {
            if (bg == null) return "null"
            if (bg is LockedBottomDrawable) return "LockedTrans"
            val cur = bg.current
            return if (cur is android.graphics.drawable.ColorDrawable) {
                String.format("Color(#%08X)", cur.color)
            } else {
                cur.javaClass.simpleName
            }
        }
        fun isInputBox(view: View, bottom: Int): Boolean {
            if (view is android.widget.EditText) return true
            val s = view.javaClass.simpleName
            if (s.contains("EditText") || s.contains("MMEditText")) return true
            return view.width in 100..(decor.width * 0.72f).toInt() && bottom < decor.height - 20
        }
        fun walk(view: View, depth: Int, inChat: Boolean, inFooter: Boolean) {
            if (depth > 28 || visited++ >= 2000 || view.visibility != View.VISIBLE) return
            val cls = view.javaClass.name
            val simple = view.javaClass.simpleName
            // 直接跳过主界面 ViewPager 与底部 Tab 栏，专注入聊天页子树（TestTimeForChatting / ChattingUILayout）
            if (simple.contains("ViewPager") || simple.contains("LauncherUIBottomTabView") || simple == "MainUIView") {
                return
            }
            val insideChat = inChat ||
                simple == "TestTimeForChatting" ||
                simple == "SwipeBackLayout" ||
                simple == "ChattingUILayout" ||
                cls.contains(".chatting.")
            val insideFooter = inFooter || cls.contains("ChatFooter") || simple.contains("ChatFooter")
            if (view.width > 0 && view.height > 0) {
                view.getLocationOnScreen(loc)
                val top = loc[1] - rootLoc[1]
                val bottom = top + view.height
                val lp = view.layoutParams as? ViewGroup.MarginLayoutParams
                val marB = lp?.bottomMargin ?: 0
                val padB = view.paddingBottom

                if (insideChat || insideFooter) {
                    // 截图中的白条与原生聊天根布局的底部 padding 重合。只放行这个区域的绘制，保留 padding 供输入区避让。
                    if (simple == "ChattingUILayout" && view is ViewGroup && top == 0 && bottom == decor.height &&
                        loc[0] == rootLoc[0] && view.width == decor.width && navigationInset > 0 && padB == navigationInset &&
                        view.paddingLeft == 0 && view.paddingTop == 0 && view.paddingRight == 0 && !imeVisible
                    ) {
                        chatLayout = view
                    }
                    if (simple == "ChattingImageBGView") {
                        val drawable = (view as? ImageView)?.drawable
                        nativeImage = "$top-$bottom/${drawable?.javaClass?.simpleName ?: "无 ImageView drawable"}"
                    }
                    // 1. 屏幕最底端 48px 垫片（top >= decor.height - 100 && bottom >= decor.height - 4 && width >= 75%）
                    val atWindowEdge = bottom >= decor.height - 4 && top >= decor.height - 100
                    val atContentEdge = navigationInset > 0 &&
                        bottom in (contentBottom - 4)..(contentBottom + 4) && top >= contentBottom - 100 &&
                        (view !is ViewGroup || view.childCount == 0) &&
                        !view.isClickable && !view.isLongClickable && !view.isFocusable &&
                        WallpaperDrawables.isNeutralSurface(view.background)
                    val isBottomInsetStrip = (atWindowEdge || atContentEdge) &&
                        view.width >= decor.width * 0.75f && !isInputBox(view, bottom) &&
                        view !is android.widget.ImageView
                    // 2. 聊天页全屏根容器（SwipeBackLayout@0-2608 #FFFFFFFF、ChattingUILayout@0-2608 #FFEDEDED + padB=48 等）
                    val isChatRootContainer = view is ViewGroup &&
                        bottom >= decor.height - 4 &&
                        view.width >= decor.width * 0.75f &&
                        (simple == "ChattingUILayout" || simple == "SwipeBackLayout" || simple == "FrostedContentView" || top == 0)
                    // 3. ChatFooter 或聊天底部容器（top >= decor.height - 260 && bottom >= decor.height - 4）
                    val isFooterShell = (insideFooter || top >= decor.height - 260 || isChatRootContainer) &&
                        bottom >= decor.height - 4 &&
                        view.width >= decor.width * 0.75f &&
                        !isInputBox(view, bottom) &&
                        view !is android.widget.ImageView &&
                        !simple.contains("WeImageView")
                    if (isBottomInsetStrip || isFooterShell) {
                        val bg = view.background
                        if (bg != null && bg !is LockedBottomDrawable) {
                            session.backgrounds[view] = bg
                            PageWallpaperUnderlay.setBackgroundPreservingPadding(view, LockedBottomDrawable())
                            cleared++
                        }
                        var type: Class<*>? = view.javaClass
                        while (type != null && !type.name.startsWith("android.")) {
                            for (field in type.declaredFields) {
                                if (android.graphics.Paint::class.java.isAssignableFrom(field.type)) {
                                    runCatching {
                                        field.isAccessible = true
                                        val p = field.get(view) as? android.graphics.Paint
                                        if (p != null) {
                                            val c = p.color
                                            val r = AndroidColor.red(c)
                                            val g = AndroidColor.green(c)
                                            val b = AndroidColor.blue(c)
                                            if (AndroidColor.alpha(c) > 0 && r >= 210 && g >= 210 && b >= 210 && maxOf(r, g, b) - minOf(r, g, b) <= 12) {
                                                session.paints[p] = c
                                                p.color = AndroidColor.TRANSPARENT
                                                p.alpha = 0
                                                view.invalidate()
                                                cleared++
                                            }
                                        }
                                    }
                                }
                            }
                            type = type.superclass
                        }
                    }
                    // 记录聊天页根容器及底部视图（用于诊断 bottom 是否停在 2560、是否有 padB=48/marB=48 或特殊背景）
                    if (view.width >= decor.width * 0.75f &&
                        (bottom >= decor.height - 96 || simple == "TestTimeForChatting" || simple == "SwipeBackLayout" || simple == "ChattingUILayout" || insideFooter || view is android.widget.ImageView)) {
                        val bg = view.background
                        if (chatHits.size < 14 && (bg != null || padB > 0 || marB > 0 || top >= decor.height - 260 || simple == "TestTimeForChatting" || simple == "ChattingUILayout" || view is android.widget.ImageView)) {
                            val clip = if (simple == "ChattingUILayout" && view is ViewGroup) {
                                " clip=${view.clipToPadding} width=${view.width} padLTR=${view.paddingLeft},${view.paddingTop},${view.paddingRight}"
                            } else ""
                            chatHits.add("$simple@$top-$bottom(bg=${bgDesc(bg)} padB=$padB marB=$marB$clip)")
                        }
                    }
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    walk(view.getChildAt(i) ?: continue, depth + 1, insideChat, insideFooter)
                }
            }
        }
        runCatching { walk(decor, 0, false, false) }
        val unclipped = session.updateBottomClip(chatLayout.takeIf { nativeImage != null })
        if (chatHits.isNotEmpty()) {
            diagnosis = "导航栏 inset=$navigationInset windowFlags=0x${window.attributes.flags.toUInt().toString(16)} " +
                "底部裁剪解除=$unclipped 键盘=$imeVisible " +
                "原生底图=${nativeImage ?: "未观测"} 清=$cleared[${chatHits.joinToString(";")}]"
        }
    }
}
