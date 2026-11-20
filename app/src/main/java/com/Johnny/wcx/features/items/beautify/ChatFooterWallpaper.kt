package com.Johnny.wcx.features.items.beautify

import android.app.Activity
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
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

    private class NavigationViewPatch(val background: Drawable?, val alpha: Float, val visibility: Int)

    private class Session(window: Window, decor: ViewGroup) {
        val window = WeakReference(window)
        val decor = WeakReference(decor)
        val backgrounds = WeakHashMap<View, Drawable>()
        val paints = WeakHashMap<Paint, Int>()
        private val navigationViews = WeakHashMap<View, NavigationViewPatch>()
        var pending: Runnable? = null
        @Suppress("DEPRECATION")
        private var navigationColor = window.navigationBarColor
        private var navigationContrast = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            window.isNavigationBarContrastEnforced

        @Suppress("DEPRECATION")
        fun clearNavigation(window: Window, decor: ViewGroup) {
            if (window.navigationBarColor != AndroidColor.TRANSPARENT) {
                navigationColor = window.navigationBarColor
                window.navigationBarColor = AndroidColor.TRANSPARENT
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && window.isNavigationBarContrastEnforced) {
                navigationContrast = true
                window.isNavigationBarContrastEnforced = false
            }
            val view = decor.findViewById<View>(android.R.id.navigationBarBackground) ?: return
            val previous = navigationViews[view]
            if (previous == null || view.background != null || view.alpha != 0f || view.visibility != View.INVISIBLE) {
                navigationViews[view] = NavigationViewPatch(
                    view.background ?: previous?.background,
                    if (previous != null && view.alpha == 0f) previous.alpha else view.alpha,
                    if (previous != null && view.visibility == View.INVISIBLE) previous.visibility else view.visibility
                )
                PageWallpaperUnderlay.setBackgroundPreservingPadding(view, null)
                view.alpha = 0f
                view.visibility = View.INVISIBLE
            }
        }

        @Suppress("DEPRECATION")
        fun restore() {
            pending?.let { decor.get()?.removeCallbacks(it) }
            pending = null
            backgrounds.forEach { (view, original) ->
                if (view.background is LockedBottomDrawable) {
                    PageWallpaperUnderlay.setBackgroundPreservingPadding(view, original)
                }
            }
            paints.forEach { (paint, original) ->
                if (paint.color == AndroidColor.TRANSPARENT) paint.color = original
            }
            navigationViews.forEach { (view, original) ->
                if (view.background == null) PageWallpaperUnderlay.setBackgroundPreservingPadding(view, original.background)
                if (view.alpha == 0f) view.alpha = original.alpha
                if (view.visibility == View.INVISIBLE) view.visibility = original.visibility
            }
            window.get()?.let { window ->
                if (window.navigationBarColor == AndroidColor.TRANSPARENT) window.navigationBarColor = navigationColor
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
            sweep(existing, window, decor)
            return
        }
        restore(activity)
        val session = Session(window, decor)
        sessions[activity] = session
        sweep(session, window, decor)

        // The native footer can be laid out after the entry callback. Queue once per chat.
        val activityRef = WeakReference(activity)
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
        var visited = 0
        var cleared = 0
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
                    // 1. 屏幕最底端 48px 垫片（top >= decor.height - 100 && bottom >= decor.height - 4 && width >= 75%）
                    val isBottomInsetStrip = bottom >= decor.height - 4 &&
                        top >= decor.height - 100 &&
                        view.width >= decor.width * 0.75f
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
                            chatHits.add("$simple@$top-$bottom(bg=${bgDesc(bg)} padB=$padB marB=$marB)")
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
        if (chatHits.isNotEmpty()) {
            diagnosis = "清=$cleared[${chatHits.joinToString(";")}]"
        }
    }
}
