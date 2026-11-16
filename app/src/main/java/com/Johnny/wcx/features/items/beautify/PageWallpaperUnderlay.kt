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
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.NinePatchDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.widget.ImageView
import android.widget.ListView
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
        // A stable home shell needs a cheap per-frame check: host color changes may
        // happen without layout/scroll callbacks, when the full scan is not dirty.
        var firstScreen: WeakReference<View>? = null
        var firstScreenRepairs = 0
        var firstScreenBlockedRecolors = 0
        // The AppCompat ActionBarContainer paints its primary background in
        // onDraw (setPrimaryBackground), which View.getBackground() never
        // sees. Track the toolbar view so the per-frame pass can re-clear
        // it when the host repaints it.
        var toolbarView: WeakReference<View>? = null
        var lastPrimaryBarCheckAt = 0L
        var lastRowScanAt = 0L
        var rowRepairs = 0
        var rowRepairsContacts = 0
        var rowRepairsCard = 0
        var rowBgType = "?"
        // Me profile card row, kept for the 500ms maintenance in refresh():
        // the adapter re-binds it and the host may rebuild it, so a single
        // scan pass is not enough to keep it transparent.
        var cardRow: WeakReference<View>? = null
        var cardList: WeakReference<ListView>? = null
        var cardRebinds = 0
        var cardImmediateRepairs = 0
        var cardFocusSuppressed = 0
        var lastCardResolveAt = 0L
        var launcherReturnChecks = 0
        var launcherReturnScheduled = false
        var lastCardCheckAt = 0L
        var lastSweepAt = 0L
        var titleRegionRepairs = 0
        var cardFaintRepairs = 0
        var conversationHeaderRepairs = 0
        var conversationTitleAnchors = 0
        var conversationTitleRepairs = 0
        var conversationStripChecks = 0
        var conversationStripIdleDraws = 0
        var conversationStripSuppressions = 0
        var conversationHeaderState = "未观测"
        var lastConversationHeaderScanAt = 0L
        var cardDiag = "未观测"
        // Per-view-class host-write counts: the hostWrites total alone
        // cannot tell whether one view is rewritten every frame or many
        // views once each.
        val hostWritesByClass = LinkedHashMap<String, Int>()
        // Taskbar painter readouts for the next diagnostic.
        var taskBarGradientAlpha = -1f
        var taskBarHiddenCount = 0
        var taskBarPainterSamples = "无"
        var taskBarFullyExpandedAt = 0L
        // Backed-up ActionBar primary background (mBackground), restored
        // when wallpaper mode ends so the native title bar comes back.
        var toolbarPrimaryBackup: Drawable? = null
        var taskBarHeader: WeakReference<View>? = null
        var taskBarView: WeakReference<View>? = null
        val taskBarPainters = WeakHashMap<View, Boolean>()
        val mutedPainters = WeakHashMap<View, Float>()
        // Visibility is more stable than alpha for native-gradient painters:
        // the taskbar animation repeatedly writes alpha while the shelf moves.
        // INVISIBLE (never GONE) retains layout and avoids frame-by-frame alpha tug-of-war.
        val mutedPainterVisibility = WeakHashMap<View, Int>()
        var lastTaskBarScanAt = 0L
        var taskBarState = "未观测"
        val retainedRows = LinkedHashSet<String>()
        var observer: WeakReference<ViewTreeObserver>? = null
        var preDraw: ViewTreeObserver.OnPreDrawListener? = null
        var layout: ViewTreeObserver.OnGlobalLayoutListener? = null
        var scroll: ViewTreeObserver.OnScrollChangedListener? = null
        var attachListener: View.OnAttachStateChangeListener? = null
        var dirty = true
        var visited = 0
        var hostWrites = 0
        var lastGateAllowed: Boolean? = null
        var pagerShown: Boolean? = null
        var statusColor = window.statusBarColor
        var navColor = window.navigationBarColor
        var statusContrast = Build.VERSION.SDK_INT >= 29 && window.isStatusBarContrastEnforced
        var navContrast = Build.VERSION.SDK_INT >= 29 && window.isNavigationBarContrastEnforced
        var barsInstalled = false
    }

    private val states = WeakHashMap<ViewGroup, WeakReference<State>>()
    private val restoreReasons = WeakHashMap<ViewGroup, String>()

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

    fun apply(activity: Activity, bitmap: Bitmap, shouldDraw: () -> Boolean): String {
        return apply(activity, bitmap, 1f, shouldDraw)
    }

    fun apply(activity: Activity, bitmap: Bitmap, opacity: Float, shouldDraw: () -> Boolean): String {
        val window = activity.window ?: return "window 不可用"
        val decor = window.decorView as? ViewGroup ?: return "decorView 不可用"
        val content = decor.findViewById<ViewGroup>(android.R.id.content) ?: return "content 不可用"
        val pager = findPager(content) ?: return "主界面分页容器未就绪"
        if (decor.width <= 0 || decor.height <= 0) return "主窗口尚未布局"
        ninePatchChecks.clear()
        gradientChecks.clear()
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
            // A freshly reattached LauncherUI can finish binding Me's first
            // Preference row AFTER apply(). Do a few bounded one-shot checks
            // instead of waiting for another tab switch or list scroll.
            val installed = state
            if (!installed.launcherReturnScheduled) {
                installed.launcherReturnScheduled = true
                for (delay in longArrayOf(80L, 250L, 600L)) {
                    decor.postDelayed({
                        if (states[decor]?.get() === installed &&
                            decor.isShown && installed.wallpaper.shouldDraw()) {
                            installed.launcherReturnChecks++
                            installed.dirty = true
                            installed.lastCardResolveAt = 0L
                            refresh(installed)
                        }
                    }, delay)
                }
            }
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
        val allowed = state.wallpaper.shouldDraw()
        state.lastGateAllowed = allowed
        if (!allowed || !decor.isShown) {
            restoreReasons[decor] = if (!allowed) "主页面守卫关闭" else "窗口不可见"
            restore(state)
            state.dirty = true
            restoreBackdrop(state)
            restoreBars(state)
            return
        }
        // When the home mini-program shelf opens, ViewPager may be temporarily hidden.
        // The Launcher window is still on screen: keep repairing its wallpaper/structural
        // surfaces. Only the explicit chat/secondary-page draw gate may restore them.
        if (state.pagerShown != pager.isShown) {
            state.pagerShown = pager.isShown
            state.dirty = true
        }
        if (decor.background !== state.wallpaper) {
            if (state.wallpaper.drawCount > 0) state.hostWrites++
            state.originalBackdrop = decor.background
            MainPageAppearance.updateBackdrop(decor, (decor.background?.current as? ColorDrawable)?.takeIf { it.alpha == 255 }?.color)
            state.wallpaper.backdrop = decor.background
            setBackgroundPreservingPadding(decor, state.wallpaper)
        }
        transparentBars(state)
        val page = runCatching {
            pager.javaClass.getMethod("getCurrentItem").invoke(pager) as? Int
        }.getOrNull()?.takeIf { it in 0..3 }
        updateTaskBarPainters(state, decor, page)
        // The host sometimes rewrites the screen-wide neutral fill after the last
        // layout pass (particularly on returning from the mini-program pull-down).
        // Keep this check O(1); it never touches children, badges or touch handlers.
        state.firstScreen?.get()?.let { shell ->
            val color = (shell.background as? ColorDrawable)?.color
            if (shell.isShown && shell.width >= decor.width * 0.9f &&
                color != null && Color.alpha(color) == 255 && isNeutralColor(color) &&
                state.patches.none { it.view.get() === shell && isPatchInstalled(it, shell) }) {
                state.firstScreenRepairs++
                makeTransparent(state, shell, true)
            }
        }
        // Host background replacement need not trigger layout. Maintain known surfaces every frame.
        state.patches.toList().forEach { patch ->
            val view = patch.view.get() ?: return@forEach
            // Wallpaper owns the ListView surface even if a previous theme rule installed it.
            if (MainSurfaceBackgrounds.owns(view) && !(view is ListView && allowed)) return@forEach
            if (!isPatchInstalled(patch, view)) {
                // WeChat may hand us a NEW transparent ColorDrawable during a
                // scroll. Adopt it rather than replacing a transparent fill
                // with another transparent fill on every pre-draw.
                val live = view.background
                if (patch.structural && live is ColorDrawable && live.color == Color.TRANSPARENT) {
                    patch.installed = WeakReference(live)
                    return@forEach
                }
                state.hostWrites++
                val key = view.javaClass.simpleName
                state.hostWritesByClass[key] = (state.hostWritesByClass[key] ?: 0) + 1
                val structural = patch.structural
                if (structural || isNeutralSurface(view.background)) makeTransparent(state, view, structural)
                else state.patches.remove(patch)
            }
        }
        // The host repaints the title bar two ways on tab switches and
        // scrolls: setPrimaryBackground (onDraw, invisible to the patch
        // loop) and setBackgroundColor on the container itself (a View
        // background, which the walk only reaches on a dirty pass and the
        // patch loop only maintains when a patch record exists). Re-clear
        // both at most twice a second, plus the container's direct
        // children (custom title view / Toolbar), which carry their own
        // backgrounds. The same 500ms slot also re-checks the Me card row:
        // the adapter re-binds it and may rebuild it, so one scan pass
        // is not enough to keep it transparent.
        val toolbarView = state.toolbarView?.get()
        if (toolbarView != null && toolbarView.isShown) {
            val now = SystemClock.uptimeMillis()
            if (now - state.lastPrimaryBarCheckAt >= 500L) {
                state.lastPrimaryBarCheckAt = now
                if (actionBarPrimaryBackgroundSet(toolbarView)) {
                    clearActionBarPrimaryBackground(state, toolbarView)
                }
                clearToolbarViewBackground(state, toolbarView)
            }
        }
        // Keep the visible AccountInfoPreference row transparent in the SAME
        // frame after a ListView rebind. The old 140/500ms checks could expose
        // its native white selector briefly when WeChat recycled the first row.
        if (page == 3) refreshVisibleMeCard(state, decor)
        // The walk only runs on a dirty pass and skips narrow views, so a
        // white surface nested inside the title bar or the card row can
        // survive every targeted fix while the diagnostics above it read
        // clean. Sweep exactly those two subtrees twice a second and patch
        // any neutral surface at any depth. The list is deliberately not
        // swept here.
        val sweepNow = SystemClock.uptimeMillis()
        if (sweepNow - state.lastSweepAt >= 500L) {
            state.lastSweepAt = sweepNow
            sweepTitleBarAndCard(state)
        }
        refreshIdentifiedRows(state, decor, pager, page)
        refreshConversationHeaders(state, decor, pager, page)
        if (!state.dirty) return
        state.dirty = false
        state.visited = 0
        state.retainedRows.clear()
        state.patches.removeAll { it.view.get() == null }
        val structuralTargets = ThemeHostAdapter.structuralTargets(decor, pager)
        val activePage = runCatching {
            pager.javaClass.getMethod("getCurrentItem").invoke(pager) as? Int
        }.getOrNull()?.takeIf { it in 0..3 }
        val visibleRect = Rect()
        fun inNativeList(view: View): Boolean {
            var parent = view.parent as? View
            var depth = 0
            while (parent != null && depth++ < 16) {
                if (parent is ListView) {
                    val name = parent.javaClass.simpleName
                    return when (activePage) {
                        0 -> name == "ConversationListView"
                        1, 3 -> name == "PullDownListView"
                        else -> false
                    }
                }
                parent = parent.parent as? View
            }
            return false
        }
        fun walk(view: View, depth: Int) {
            if (depth > MAX_DEPTH || state.visited++ >= MAX_NODES || view.visibility != View.VISIBLE) return
            if (view is ImageView || !view.getGlobalVisibleRect(visibleRect)) return
            if (view !== decor) {
                val bottomTab = ThemeHostAdapter.isBottomTab(view)
                val toolbar = MainSurfaceBackgrounds.isToolbar(view)
                val firstScreen = view.javaClass.simpleName == "FirstScreenFrameLayout" &&
                    view.width >= decor.width * 0.90f
                if (firstScreen) state.firstScreen = WeakReference(view)
                if (toolbar) {
                    state.toolbarView = WeakReference(view)
                    // The title-bar color lives in the container's onDraw
                    // primary background, not in the View background the
                    // walk patches below.
                    clearActionBarPrimaryBackground(state, view)
                }
                // Only the home's full-width pull-down LIST surface, never its card rows.
                val homeShelf = activePage == 0 && view is ListView &&
                    view.javaClass.simpleName == "PullDownListView" &&
                    visibleRect.width() >= decor.width * 0.70f &&
                    visibleRect.height() >= decor.height * 0.18f
                // WeCom/Me rows and conversation unread-count ROW containers can be less
                // than 75% width. Keep TextView/ImageView/interactive badges untouched.
                val nativeRow = view is ViewGroup && inNativeList(view) &&
                    view.width >= decor.width * 0.45f &&
                    visibleRect.height() in 32..(decor.height / 3)
                val candidate = view.width >= decor.width * 0.75f ||
                    bottomTab || firstScreen || homeShelf || nativeRow || toolbar
                if (candidate) {
                    val structural = view in structuralTargets || bottomTab || firstScreen || homeShelf || toolbar
                    val background = view.background
                    if (structural || isNeutralSurface(background)) {
                        makeTransparent(state, view, structural)
                    } else if (background != null && background !is SurfaceBackground && state.retainedRows.size < 6) {
                        state.retainedRows.add("${view.javaClass.simpleName}/${background.javaClass.simpleName}${retainedDetail(background)}")
                    }
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                walk(view.getChildAt(i) ?: continue, depth + 1)
                if (state.visited >= MAX_NODES) break
            }
        }
        walk(decor, 0)
    }

    /**
     * WeChat 8.0.80: enterprise rows are under contact.t3/WxRecyclerView; the
     * Me profile is AccountInfoPreference's FIRST ADAPTER ROW, not a ListView header.
     * A small bounded pre-draw scan handles recycled rows without hooking RecyclerView.
     */
    private fun refreshVisibleMeCard(state: State, decor: ViewGroup) {
        var list = state.cardList?.get()
        // Clear() on an external Activity restores all patches and discards
        // their State. The next State initially has no cached Me list even
        // if its row has already finished binding before onResume.
        if (list == null || !list.isAttachedToWindow || !list.isShown) {
            val now = SystemClock.uptimeMillis()
            if (!state.dirty && now - state.lastCardResolveAt < 160L) return
            state.lastCardResolveAt = now
            val pager = state.pager.get() ?: return
            var checked = 0
            var resolved: ListView? = null
            fun find(view: View, depth: Int) {
                if (resolved != null || checked++ >= 850 || depth > MAX_DEPTH ||
                    view.visibility != View.VISIBLE) return
                if (view is ListView && view.javaClass.simpleName == "PullDownListView" &&
                    view.headerViewsCount == 0 && view.firstVisiblePosition == 0 &&
                    runCatching {
                        view.adapter?.getItem(0)?.javaClass?.simpleName == "AccountInfoPreference"
                    }.getOrDefault(false)) {
                    resolved = view
                    return
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) {
                    find(view.getChildAt(i), depth + 1)
                    if (resolved != null || checked >= 850) break
                }
            }
            find(pager, 0)
            list = resolved ?: return
            state.cardList = WeakReference(list)
        }
        if (!list.isShown || list.firstVisiblePosition != 0 ||
            list.headerViewsCount != 0 || list.childCount == 0) return
        val row = list.getChildAt(0) ?: return
        val visible = Rect()
        if (!row.isShown || !row.getGlobalVisibleRect(visible) ||
            visible.top > decor.height * 0.40f) return

        if (state.cardRow?.get() !== row) {
            state.cardRow = WeakReference(row)
            state.cardRebinds++
            state.dirty = true
        }
        var visited = 0
        fun repair(view: View, depth: Int) {
            if (depth > 4 || visited++ >= 48 || view.visibility != View.VISIBLE) return
            val bg = view.background
            // Returning from another screen can restore focus to the card,
            // leaving its original white selector DRAWING despite the patch
            // being installed. Ignore idle focus only on this card, but keep
            // pressed feedback and restore native behavior when theme exits.
            if (bg is SurfaceBackground && !bg.ignoreIdleFocus) {
                bg.ignoreIdleFocus = true
                state.cardFocusSuppressed++
                view.invalidate()
            }
            if (view !is android.widget.TextView &&
                bg != null && bg !is SurfaceBackground &&
                (whiteSelector(bg) || isNeutralSurface(bg))) {
                makeTransparent(state, view, false)
                if (view.background is SurfaceBackground) {
                    state.cardImmediateRepairs++
                    state.rowRepairs++
                    state.rowRepairsCard++
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                repair(view.getChildAt(i), depth + 1)
                if (visited >= 48) break
            }
        }
        repair(row, 0)
    }

    private fun refreshIdentifiedRows(state: State, decor: ViewGroup, pager: ViewGroup, page: Int?) {
        if (page != 1 && page != 3) return
        val now = SystemClock.uptimeMillis()
        if (!state.dirty && now - state.lastRowScanAt < 140L) return
        state.lastRowScanAt = now
        val rect = Rect()
        var visited = 0
        fun eligible(view: View): Boolean =
            view is ViewGroup && view.isShown &&
                view.width >= decor.width * 0.48f &&
                view.getGlobalVisibleRect(rect) &&
                rect.height() >= 36

        fun patchMeCard(row: View, depth: Int) {
            if (depth > 4 || !eligible(row)) return
            if (whiteSelector(row.background)) {
                makeTransparent(state, row, false)
                state.rowRepairs++
                state.rowRepairsCard++
                return
            }
            if (row is ViewGroup) for (i in 0 until row.childCount) {
                patchMeCard(row.getChildAt(i), depth + 1)
            }
        }

        fun walk(view: View, depth: Int, wecom: Boolean) {
            if (depth > MAX_DEPTH || visited++ >= 850 ||
                view.visibility != View.VISIBLE || !view.getGlobalVisibleRect(rect)) return
            val inWecom = wecom || view.javaClass.name == "com.tencent.mm.ui.contact.t3"
            if (page == 1 && inWecom && eligible(view) && whiteSelector(view.background)) {
                makeTransparent(state, view, false)
                state.rowRepairs++
                state.rowRepairsContacts++
            }
            if (page == 3 && view is ListView &&
                view.javaClass.simpleName == "PullDownListView" &&
                view.firstVisiblePosition == 0 && view.headerViewsCount == 0) {
                state.cardList = WeakReference(view)
                val row = view.getChildAt(0)
                if (row != null && row.getGlobalVisibleRect(rect) &&
                    rect.top < decor.height * 0.40f) {
                    // The adapter-type gate used to be required here, but it
                    // silently skipped the card whenever getItem(0) was not
                    // AccountInfoPreference at scan time (wrapper adapter,
                    // not-yet-bound screen). A white first row on the Me
                    // page is the card by construction: patch it on its
                    // background alone, and record what the gate saw so the
                    // diagnostic can tell us whether it would have passed.
                    val firstItem = runCatching { view.adapter?.getItem(0) }.getOrNull()
                    val firstType = firstItem?.javaClass?.simpleName ?: "null"
                    val rowBg = row.background
                    val patch = state.patches.firstOrNull { it.view.get() === row }
                    val patchState = when {
                        patch == null -> "补丁=无"
                        isPatchInstalled(patch, row) -> "补丁=已装"
                        else -> "补丁=未装"
                    }
                    state.cardDiag = "第0项=$firstType 行=${row.javaClass.simpleName}/" +
                        "${rowBg?.javaClass?.simpleName ?: "null"}${rowBg?.let { retainedDetail(it) } ?: ""} $patchState"
                    state.cardRow = WeakReference(row)
                    state.rowBgType = "${rowBg?.javaClass?.simpleName ?: "null"}${rowBg?.let { retainedDetail(it) } ?: ""}"
                    if (whiteSelector(rowBg)) {
                        patchMeCard(row, 0)
                    } else if (row is ViewGroup) {
                        // The row root may already be patched (its wrapper
                        // is not white); still walk the children, whose own
                        // backgrounds the host repaints on every bind.
                        for (i in 0 until row.childCount) {
                            patchMeCard(row.getChildAt(i), 1)
                        }
                    }
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                walk(view.getChildAt(i), depth + 1, inWecom)
                if (visited >= 850) break
            }
        }
        walk(pager, 0, false)
    }

    /**
     * The scrolling "微信" title is in ConversationListView's header rows,
     * not the stationary ActionBarContainer. Only inspect visible adapter
     * headers of tab 0. No reparenting, intercepting touches, or disabling
     * the list's native vertical scroll.
     */
    private fun refreshConversationHeaders(state: State, decor: ViewGroup, pager: ViewGroup, page: Int?) {
        if (page != 0) return
        val now = SystemClock.uptimeMillis()
        if (!state.dirty && now - state.lastConversationHeaderScanAt < 200L) return
        state.lastConversationHeaderScanAt = now
        var visited = 0
        var conversation: ListView? = null
        fun find(view: View, depth: Int) {
            if (conversation != null || depth > MAX_DEPTH || visited++ >= MAX_NODES ||
                view.visibility != View.VISIBLE) return
            if (view is ListView && view.javaClass.simpleName == "ConversationListView") {
                conversation = view
                return
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                find(view.getChildAt(i), depth + 1)
                if (conversation != null) break
            }
        }
        find(pager, 0)
        val list = conversation ?: run {
            state.conversationHeaderState = "会话列表未显示"
            return
        }
        val first = list.firstVisiblePosition
        val headerCount = list.headerViewsCount
        if (headerCount <= 0 || first >= headerCount) {
            state.conversationHeaderState = "Header=$headerCount 首项=$first（已滚出视野）"
            return
        }
        val rect = Rect()
        var shown = 0
        var repaired = 0
        var titleRows = 0
        val samples = ArrayList<String>()
        // Identify the SCROLLING "微信" title by its TextView inside actual
        // ListView headers. Only its ancestor backgrounds may be suppressed
        // even if their NinePatch or image-like drawable is non-neutral.
        // This never changes header layout, scroll handling, or text/icons.
        fun patchHeader(view: View, depth: Int, titleStrip: Boolean): Boolean {
            val bounds = Rect()
            if (depth > 6 || !view.isShown || !view.getGlobalVisibleRect(bounds) ||
                bounds.top >= decor.height * 0.36f) return false
            val background = view.background
            if (samples.size < 8 && (background != null || depth == 0)) {
                val fg = view.foreground
                samples.add("${view.javaClass.simpleName}@${bounds.top}-${bounds.bottom}/" +
                    "bg=${background?.javaClass?.simpleName ?: "null"}" +
                    "${background?.let { retainedDetail(it) } ?: ""}/" +
                    "fg=${fg?.javaClass?.simpleName ?: "null"}")
            }
            var hasTitle = if (view is android.widget.TextView) {
                val label = view.text?.toString()?.trim().orEmpty()
                label == "微信" || (label.length <= 15 &&
                    (label.startsWith("微信(") || label.startsWith("微信（")))
            } else false
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                if (patchHeader(view.getChildAt(i), depth + 1, titleStrip)) hasTitle = true
            }
            // WeChat 8.0.80: the scrollable title strip is header item 1,
            // a 144px-high LinearLayout at y=288..432 on a 2608px screen.
            // Its two children already have SurfaceBackground, but a wrapper
            // can still draw the original on focus or if its native drawable
            // state becomes non-neutral. Keep ONLY those header surfaces
            // idle-transparent while preserving native pressed feedback.
            if (titleStrip && view is ViewGroup &&
                bounds.width() >= decor.width * 0.58f && background is SurfaceBackground) {
                val drew = background.drawsBackground()
                if (drew) state.conversationStripIdleDraws++
                if (!background.suppressFaintIdle || !background.ignoreIdleFocus) {
                    background.suppressFaintIdle = true
                    background.ignoreIdleFocus = true
                    view.invalidate()
                    state.conversationStripSuppressions++
                }
            }
            if (view is ViewGroup && bounds.width() >= decor.width * 0.58f &&
                bounds.height() >= 12 && background != null) {
                val base = background.current
                val transparent = base is ColorDrawable && Color.alpha(base.color) == 0
                if (hasTitle) {
                    // When a title-specific background is not strictly neutral
                    // (e.g. a white nine-patch with a shaded border), the old
                    // neutral-only pass leaves the scrolling title opaque.
                    val wrapper = background as? SurfaceBackground
                    if (wrapper != null) {
                        if (!wrapper.suppressFaintIdle) {
                            wrapper.suppressFaintIdle = true
                            view.invalidate()
                            state.conversationTitleRepairs++
                        }
                    } else if (!transparent) {
                        makeTransparent(state, view, false, true)
                        if (view.background is SurfaceBackground) {
                            state.conversationTitleRepairs++
                            repaired++
                        }
                    }
                } else if (bounds.height() <= decor.height / 4 &&
                    background !is SurfaceBackground &&
                    !transparent && isNeutralSurface(background)) {
                    makeTransparent(state, view, false)
                    if (view.background is SurfaceBackground) {
                        repaired++
                        state.conversationHeaderRepairs++
                    }
                }
            }
            return hasTitle
        }
        for (index in 0 until list.childCount) {
            if (first + index >= headerCount) break
            val row = list.getChildAt(index) ?: continue
            if (!row.getGlobalVisibleRect(rect) || rect.top >= decor.height * 0.36f) continue
            shown++
            // Restrict the geometry fallback to the proven second header,
            // NOT the mini-program TaskBarContainer (header 0), nor any
            // subsequent content rows or other tabs.
            val titleStrip = first + index == 1 &&
                row.javaClass.simpleName == "LinearLayout" &&
                rect.width() >= decor.width * 0.65f &&
                rect.height() >= decor.height * 0.035f &&
                rect.height() <= decor.height * 0.085f &&
                rect.top < decor.height * 0.30f
            if (titleStrip) state.conversationStripChecks++
            if (patchHeader(row, 0, titleStrip)) titleRows++
        }
        if (titleRows > 0) state.conversationTitleAnchors++
        state.conversationHeaderState =
            "Header=$headerCount 首项=$first 可见=$shown 标题行=$titleRows 本轮修复=$repaired " +
            "背景=${samples.joinToString(";").ifEmpty { "无" }}"
    }

    private fun whiteSelector(drawable: Drawable?): Boolean {
        val color = when (drawable) {
            is StateListDrawable -> (drawable.current as? ColorDrawable)?.color
            is ColorDrawable -> drawable.color
            else -> return false
        } ?: return false
        return Color.alpha(color) == 255 &&
            Color.red(color) >= 248 && Color.green(color) >= 248 && Color.blue(color) >= 248
    }

    /**
     * Only suppress the taskbar's OWN painted gradient / bottom strip during a
     * PARTIAL home pull-down. Once the native header is fully expanded, restore
     * native painters; on collapse the wallpaper becomes visible again.
     * Never hide TaskBarContainer or the mini-program RecyclerView.
     */
    private fun updateTaskBarPainters(state: State, decor: ViewGroup, page: Int?) {
        if (page != 0) {
            restoreTaskBarPainters(state)
            state.taskBarState = "非首页"
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - state.lastTaskBarScanAt >= 180L &&
            (state.taskBarHeader?.get() == null || state.dirty || state.taskBarPainters.isEmpty())) {
            state.lastTaskBarScanAt = now
            var visited = 0
            fun scan(view: View, depth: Int) {
                if (depth > MAX_DEPTH || visited++ >= MAX_NODES) return
                val name = view.javaClass.simpleName
                when (name) {
                    "TaskBarContainer" -> {
                        if (view.javaClass.name == "com.tencent.mm.plugin.taskbar.ui.TaskBarContainer")
                            state.taskBarHeader = WeakReference(view)
                    }
                    "TaskBarView" -> state.taskBarView = WeakReference(view)
                    "GradientColorBackgroundView", "DynamicBackgroundGLSurfaceView",
                    "TaskBarBottomView", "DynamicBgContainer" ->
                        state.taskBarPainters[view] = true
                }
                if (view is ViewGroup) for (i in 0 until view.childCount) {
                    scan(view.getChildAt(i), depth + 1)
                    if (visited >= MAX_NODES) break
                }
            }
            scan(decor, 0)
        }
        val header = state.taskBarHeader?.get()
        val visible = Rect()
        val exposed = header != null && header.isShown &&
            header.height > 0 && header.getGlobalVisibleRect(visible) && visible.height() > 0
        if (!exposed) {
            // Keep the painters muted while the shelf is hidden: restoring here
            // left them at alpha 1.0, so any peek of the header while the chat
            // list scrolls flashed the purple gradient before the next mute.
            // They are only restored when the shelf is fully expanded below.
            muteTaskBarPainters(state)
            state.taskBarState = "收起/未显示"
            return
        }
        val fraction = (visible.height().toFloat() / header!!.height).coerceIn(0f, 1f)
        val nativeOpen = taskBarNativeOpen(state.taskBarView?.get())
        // The native open signal is unreliable (often null), so a bare
        // 98% frame could restore the gradient a moment too early. Only
        // restore when the shelf is at >=98% with a confirmed native open,
        // or when it has sat at >=98% for 300ms straight (debounce).
        val nowMs = SystemClock.uptimeMillis()
        if (fraction >= 0.98f) {
            if (state.taskBarFullyExpandedAt == 0L) state.taskBarFullyExpandedAt = nowMs
        } else {
            state.taskBarFullyExpandedAt = 0L
        }
        val heldFull = state.taskBarFullyExpandedAt != 0L && nowMs - state.taskBarFullyExpandedAt >= 300L
        val fullyExpanded = fraction >= 0.98f && (nativeOpen == true || heldFull)
        state.taskBarState = if (fullyExpanded) "完全展开" else "下拉中"
        state.taskBarState += "（可见=${(fraction * 100).toInt()}%，原生展开=${nativeOpen ?: "未知"}）"
        if (fullyExpanded) {
            restoreTaskBarPainters(state)
            return
        }
        muteTaskBarPainters(state)
    }

    /** Mute the shelf's gradient painters (alpha 0), remembering each one's
     *  original alpha so a full expansion can restore it. */
    private fun muteTaskBarPainters(state: State) {
        state.taskBarPainters.keys.toList().forEach { painter ->
            if (!painter.isAttachedToWindow) return@forEach
            val name = painter.javaClass.simpleName
            // These two classes ONLY paint the background. Native animation
            // writes alpha every frame; INVISIBLE avoids the opposing writes
            // while leaving geometry and interactive mini-program grid intact.
            if (name == "GradientColorBackgroundView" || name == "TaskBarBottomView") {
                if (!state.mutedPainterVisibility.containsKey(painter)) {
                    state.mutedPainterVisibility[painter] = painter.visibility
                }
                if (painter.visibility == View.VISIBLE) painter.visibility = View.INVISIBLE
                return@forEach
            }
            // Do not mute any container that encloses the interactive mini-program
            // grid; the native taskbar may reuse the same animation parent.
            if (name == "DynamicBgContainer") {
                var node: View? = state.taskBarView?.get()
                var inside = false
                var depth = 0
                while (node != null && depth++ < 20) {
                    if (node === painter) { inside = true; break }
                    node = node.parent as? View
                }
                if (inside) return@forEach
            }
            // GL/animated background containers keep visibility unchanged so
            // Android does not tear down or recreate a GLSurfaceView.
            if (!state.mutedPainters.containsKey(painter)) {
                val original = painter.alpha
                if (original <= 0f) return@forEach
                state.mutedPainters[painter] = original
            }
            if (painter.alpha != 0f) painter.alpha = 0f
        }
        recordTaskBarPainterState(state)
    }

    /** Read the shelf painters' current alpha into the diagnostic fields. */
    private fun recordTaskBarPainterState(state: State) {
        val painters = state.taskBarPainters.keys.toList()
        state.taskBarGradientAlpha = painters.maxOfOrNull { it.alpha } ?: -1f
        state.taskBarHiddenCount = painters.count {
            it.alpha == 0f || state.mutedPainterVisibility.containsKey(it) &&
                it.visibility == View.INVISIBLE
        }
        state.taskBarPainterSamples = painters.take(6).joinToString(",") { painter ->
            "${painter.javaClass.simpleName}:a=${painter.alpha}/v=${painter.visibility}/" +
                "${if (painter.isAttachedToWindow) "挂载" else "未挂载"}/" +
                "${if (painter.isShown) "可见" else "不可见"}"
        }.ifEmpty { "无" }
    }

    private class ActionBarBgAccess(
        val fields: List<java.lang.reflect.Field>,
        val setters: List<java.lang.reflect.Method>
    )

    private val actionBarBgAccessCache = HashMap<Class<*>, ActionBarBgAccess>()

    /**
     * WeChat colors the title bar through HomeUI.setActionBarColor, which
     * lands in ActionBarContainer.setPrimaryBackground: the color is painted
     * in onDraw and never appears as the View background, so the walk and
     * the patch loop cannot see or remove it. Locate the primary-background
     * slots by signature (Drawable fields / single-Drawable setters),
     * never by obfuscated name.
     */
    private fun actionBarBgAccess(view: View): ActionBarBgAccess {
        return actionBarBgAccessCache.getOrPut(view.javaClass) {
            val fields = ArrayList<java.lang.reflect.Field>()
            var type: Class<*>? = view.javaClass
            while (type != null && !type.name.startsWith("android.view.")) {
                for (field in type.declaredFields) {
                    if (field.type == Drawable::class.java) {
                        runCatching { field.isAccessible = true }
                        fields.add(field)
                    }
                }
                type = type.superclass
            }
            val setters = view.javaClass.methods.filter { method ->
                method.parameterTypes.size == 1 &&
                    method.parameterTypes[0] == Drawable::class.java &&
                    method.returnType == Void.TYPE &&
                    !method.declaringClass.name.startsWith("android.")
            }
            ActionBarBgAccess(fields, setters)
        }
    }

    /** The primary-background Drawable field (ActionBarContainer.mBackground).
     *  Falls back to the first Drawable field when the name is unavailable. */
    private fun primaryBackgroundField(view: View): java.lang.reflect.Field? {
        val access = actionBarBgAccess(view)
        return access.fields.firstOrNull { it.name == "mBackground" } ?: access.fields.firstOrNull()
    }

    /** The primary-background setter (ActionBarContainer.setPrimaryBackground).
     *  The stacked/split setters are deliberately not used: clearing them
     *  would touch surfaces the title-bar fix does not own. */
    private fun primaryBackgroundSetter(view: View): java.lang.reflect.Method? {
        val access = actionBarBgAccess(view)
        return access.setters.firstOrNull { it.name == "setPrimaryBackground" }
    }

    private fun actionBarPrimaryBackgroundSet(view: View): Boolean {
        val field = primaryBackgroundField(view) ?: return false
        return runCatching { field.get(view) as? Drawable }.getOrNull() != null
    }

    private fun clearActionBarPrimaryBackground(state: State, view: View) {
        val setter = primaryBackgroundSetter(view) ?: return
        if (state.toolbarPrimaryBackup == null) {
            state.toolbarPrimaryBackup = runCatching {
                primaryBackgroundField(view)?.get(view) as? Drawable
            }.getOrNull()
        }
        runCatching {
            setter.isAccessible = true
            setter.invoke(view, null)
        }
    }

    /** Restore the backed-up primary background when wallpaper mode ends,
     *  so the native title bar comes back exactly as it was. */
    private fun restoreActionBarPrimaryBackground(state: State) {
        val view = state.toolbarView?.get() ?: return
        val backup = state.toolbarPrimaryBackup ?: return
        val setter = primaryBackgroundSetter(view) ?: return
        runCatching {
            setter.isAccessible = true
            setter.invoke(view, backup)
        }
        state.toolbarPrimaryBackup = null
    }

    /** Re-clear the title-bar container's own View background and its
     *  direct children's backgrounds when the host repaints them opaque.
     *  Only non-transparent neutral ColorDrawables are touched, so themed
     *  images and translucent scrims are left alone. */
    private fun clearToolbarViewBackground(state: State, view: View) {
        val bg = view.background as? ColorDrawable
        if (bg != null && bg.color != Color.TRANSPARENT && isNeutralColor(bg.color)) {
            makeTransparent(state, view, true)
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            val child = view.getChildAt(i) ?: continue
            if (!child.isShown) continue
            val childBg = child.background as? ColorDrawable
            if (childBg != null && childBg.color != Color.TRANSPARENT && isNeutralColor(childBg.color)) {
                makeTransparent(state, child, false)
            }
        }
    }

    /** Sweep the two still-white subtrees (title-bar container and Me card
     *  row) and patch any neutral surface at any depth. Unlike the walk,
     *  this runs twice a second regardless of dirty state and has no width
     *  gate, so nested white surfaces the walk never reaches get patched.
     *  Views already wrapped in SurfaceBackground are skipped; themed
     *  images and non-neutral colors are left alone by isNeutralSurface. */
    private fun sweepTitleBarAndCard(state: State) {
        state.toolbarView?.get()?.let { toolbar ->
            if (toolbar.isShown) {
                sweepSubtree(state, toolbar, 0, false)
                state.decor.get()?.let { sweepTitleRegion(state, it, toolbar) }
            }
        }
        state.cardRow?.get()?.let { row ->
            if (row.isShown) sweepSubtree(state, row, 0, true)
        }
    }

    private fun sweepSubtree(state: State, view: View, depth: Int, card: Boolean) {
        if (depth > 6 || !view.isShown) return
        val bg = view.background
        // MoreTabUI's personal-info row uses translucent neutral bfj 9-patches
        // underneath its white selector. The generic neutral test deliberately
        // rejects translucent 9-patches, so only suppress those inside THIS card.
        val faint = card && bg is NinePatchDrawable && isFaintCardNinePatch(bg)
        if (bg != null && bg !is SurfaceBackground && (isNeutralSurface(bg) || faint)) {
            makeTransparent(state, view, false, faint)
            if (faint) state.cardFaintRepairs++
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            sweepSubtree(state, view.getChildAt(i) ?: continue, depth + 1, card)
        }
    }

    /** A bounded probe of a CLONED drawable; never samples or mutates the live view. */
    private fun isFaintCardNinePatch(drawable: NinePatchDrawable): Boolean = runCatching {
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

    /**
     * Title bars can have neutral paint on a sibling of ActionBarContainer
     * (not in its subtree). Search only the title's visible screen rectangle;
     * never touch text, icons, tab badges, or main-page list backgrounds.
     */
    private fun sweepTitleRegion(state: State, decor: ViewGroup, toolbar: View) {
        val target = Rect()
        if (!toolbar.getGlobalVisibleRect(target)) return
        if (target.height() <= 0 || target.top >= decor.height * 0.26f) return
        val visible = Rect()
        var checked = 0
        fun walk(view: View, depth: Int) {
            if (depth > 16 || checked++ >= 1100 || view.visibility != View.VISIBLE ||
                !view.getGlobalVisibleRect(visible) || !Rect.intersects(visible, target)) return
            if (view !== decor && view !is ImageView && view !is android.widget.TextView &&
                visible.width() >= decor.width * 0.60f && visible.height() > 8 &&
                visible.height() <= maxOf(target.height() * 2, (decor.height * 0.16f).toInt())) {
                val bg = view.background
                if (bg != null && bg !is SurfaceBackground && isNeutralSurface(bg) &&
                    !(bg is ColorDrawable && Color.alpha(bg.color) == 0)) {
                    makeTransparent(state, view, false)
                    if (view.background is SurfaceBackground) state.titleRegionRepairs++
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                walk(view.getChildAt(i) ?: continue, depth + 1)
                if (checked >= 1100) break
            }
        }
        walk(decor, 0)
    }

    private fun taskBarNativeOpen(view: View?): Boolean? {
        if (view == null) return null
        var type: Class<*>? = view.javaClass
        while (type != null) {
            val method = type.declaredMethods.firstOrNull {
                it.name == "I2" && it.parameterTypes.isEmpty() &&
                    (it.returnType == Boolean::class.javaPrimitiveType ||
                        it.returnType == java.lang.Boolean::class.java)
            }
            if (method != null) {
                return runCatching {
                    method.isAccessible = true
                    method.invoke(view) as? Boolean
                }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }

    private fun restoreTaskBarPainters(state: State) {
        state.mutedPainterVisibility.entries.toList().forEach { (view, original) ->
            if (view.visibility == View.INVISIBLE) view.visibility = original
        }
        state.mutedPainterVisibility.clear()
        state.mutedPainters.entries.toList().forEach { (view, original) ->
            if (view.alpha == 0f) view.alpha = original
        }
        state.mutedPainters.clear()
        recordTaskBarPainterState(state)
    }

    /** Called only for a row returned by the concrete adapter of a registered main-page list. */
    fun onRowBound(decor: ViewGroup, list: ViewGroup, row: View) {
        val state = states[decor]?.get() ?: return
        if (!state.wallpaper.shouldDraw() || !decor.isShown || !list.isShown) return
        state.dirty = true
        if (row !is ImageView && isNeutralSurface(row.background)) makeTransparent(state, row, false)
    }

    /** 切页后补一次重扫：切页瞬间那次 walk 可能赶在新页行绑定完之前；等动画落定后再脏一次，下次 pre-draw 即重走。 */
    fun markDirtyAfterSettle(decor: ViewGroup, delayMs: Long = 300L) {
        decor.postDelayed({ states[decor]?.get()?.let { it.dirty = true } }, delayMs)
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
    private fun makeTransparent(state: State, view: View, structural: Boolean = true, suppressFaintIdle: Boolean = false) {
        // 壁纸模式下 ListView 例外：它被 MainSurfaceBackgrounds 按 lists→background 接管（多为 Native，
        // 即微信默认灰），不例外就永远盖住壁纸，诊断也看不到。顶栏与 Tab 栏在壁纸模式 + Native/Inherit
        // 时已由 MainPageAppearance 释放归属（同款），这里照常打补丁；仍被接管的（包里配了纯色）不动。
        if (MainSurfaceBackgrounds.owns(view) && !(view is ListView && state.wallpaper.shouldDraw())) return
        val patch = state.patches.firstOrNull { it.view.get() === view }
        if (patch != null && isPatchInstalled(patch, view)) return
        val original = view.background
        if (original == null) {
            if (patch != null) state.patches.remove(patch)
            return
        }
        // WeChat calls setBackgroundColor() on FirstScreenFrameLayout
        // after layout/scroll. If its current background is ColorDrawable,
        // Android recolors the SAME drawable in-place, sometimes after our
        // pre-draw pass. Keep this specific full-screen shell transparent
        // until we restore the original on leaving the main page.
        val lockedFirstScreen = structural &&
            view.javaClass.simpleName == "FirstScreenFrameLayout" &&
            view.width >= (state.decor.get()?.width ?: Int.MAX_VALUE) * 0.90f
        val transparent = when {
            lockedFirstScreen -> LockedTransparentShell {
                state.firstScreenBlockedRecolors++
            }
            structural -> ColorDrawable(Color.TRANSPARENT)
            else -> SurfaceBackground(original, suppressFaintIdle)
        }
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

    /**
     * Only for the native FirstScreenFrameLayout (not every background).
     * View.setBackgroundColor() normally calls ColorDrawable.setColor() on
     * the existing instance. Ignore host recolors while the wallpaper owns
     * this shell; original native drawable is retained in Patch for restore.
     */
    private class LockedTransparentShell(private val onBlocked: () -> Unit) :
        ColorDrawable(Color.TRANSPARENT) {
        override fun setColor(color: Int) {
            if (color == Color.TRANSPARENT) super.setColor(color)
            else onBlocked()
        }
    }

    /** 单色是否中性：全透明；或近灰（RGB 三通道极差 ≤ 10）且全不透明或半透明（alpha ≤ 0x33）。半透明遮罩色偏极小，放掉只让底图亮一点，不改色相。 */
    private fun isNeutralColor(color: Int): Boolean {
        val spread = maxOf(Color.red(color), Color.green(color), Color.blue(color)) -
            minOf(Color.red(color), Color.green(color), Color.blue(color))
        return Color.alpha(color) == 0 || (spread <= 10 &&
            (Color.alpha(color) == 255 || Color.alpha(color) <= 0x33))
    }

    /** 保留原因诊断：纯色给色值+色偏，状态列表给当前态子类，图层列表逐层给类型+色值；进现有诊断的“保留行背景”里，不另起日志。最多展开两层，避免诊断爆炸。 */
    private fun retainedDetail(drawable: Drawable, depth: Int = 0): String = when (drawable) {
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

    /** 弹跳视图背景诊断：找类名以 WeUIBounceView(V2) 结尾的视图，报背景类型+色值；找不到报“无”。只读，不改任何背景。 */
    private fun bounceViewBackgrounds(decor: ViewGroup): String {
        val found = ArrayList<String>()
        var visited = 0
        fun visit(view: View, depth: Int) {
            if (depth > MAX_DEPTH || visited++ >= MAX_NODES || found.size >= 4) return
            val name = view.javaClass.name
            if (name.endsWith("WeUIBounceViewV2") || name.endsWith("WeUIBounceView")) {
                val background = view.background
                found.add("${view.javaClass.simpleName}/${background?.javaClass?.simpleName ?: "null"}${background?.let { retainedDetail(it) } ?: ""}")
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                visit(view.getChildAt(i) ?: continue, depth + 1)
                if (visited >= MAX_NODES || found.size >= 4) break
            }
        }
        runCatching { visit(decor, 0) }.onFailure { return "未知" }
        return if (found.isEmpty()) "无" else found.joinToString(",")
    }

    /** ListView 实况诊断：报每个可见 ListView 的实时背景（不是补丁状态）+ 补丁装没装 + 是否被接管。
     * 用来确认 MainPageAppearance 是否每帧把 ListView 刷回灰。只读，不改任何背景。 */
    private fun listViewLiveState(decor: ViewGroup, state: State): String {
        val found = ArrayList<String>()
        var visited = 0
        fun visit(view: View, depth: Int) {
            if (depth > MAX_DEPTH || visited++ >= MAX_NODES || found.size >= 4) return
            if (view is ListView && view.visibility == View.VISIBLE) {
                val background = view.background
                val patch = state.patches.firstOrNull { it.view.get() === view }
                val patchState = when {
                    patch == null -> "补丁=无"
                    isPatchInstalled(patch, view) -> "补丁=已装"
                    background != null && background === patch.installed.get() -> "补丁=被原地改写"
                    else -> "补丁=未装"
                }
                val owns = MainSurfaceBackgrounds.owns(view)
                found.add("${view.javaClass.simpleName}/实背景=${background?.javaClass?.simpleName ?: "null"}${background?.let { retainedDetail(it) } ?: ""} $patchState 接管=$owns headerViewsCount=${view.headerViewsCount} firstVisible=${view.firstVisiblePosition}")
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                visit(view.getChildAt(i) ?: continue, depth + 1)
                if (visited >= MAX_NODES || found.size >= 4) break
            }
        }
        runCatching { visit(decor, 0) }.onFailure { return "未知" }
        return if (found.isEmpty()) "无" else found.joinToString(";")
    }

    /** Top-bar live state: the action-bar container's real background + patch + ownership.
     * Confirms the wallpaper-mode release (Native/Inherit) took effect; if the pack sets
     * an explicit toolbar color the release never applies and this still shows owned. */
    private fun toolbarLiveState(decor: ViewGroup, state: State): String {
        var found: String? = null
        var visited = 0
        fun visit(view: View, depth: Int) {
            if (found != null || depth > MAX_DEPTH || visited++ >= MAX_NODES) return
            if (view.visibility == View.VISIBLE && MainSurfaceBackgrounds.isToolbar(view)) {
                val background = view.background
                val patch = state.patches.firstOrNull { it.view.get() === view }
                val patchState = when {
                    patch == null -> "补丁=无"
                    isPatchInstalled(patch, view) -> "补丁=已装"
                    background != null && background === patch.installed.get() -> "补丁=被原地改写"
                    else -> "补丁=未装"
                }
                val owns = MainSurfaceBackgrounds.owns(view)
                val primary = when {
                    primaryBackgroundSetter(view) == null -> "主底=?"
                    actionBarPrimaryBackgroundSet(view) -> "主底=有"
                    else -> "主底=无"
                }
                found = "${view.javaClass.simpleName}/实背景=${background?.javaClass?.simpleName ?: "null"}${background?.let { retainedDetail(it) } ?: ""} $patchState 接管=$owns $primary"
                return
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                visit(view.getChildAt(i) ?: continue, depth + 1)
                if (found != null || visited >= MAX_NODES) break
            }
        }
        runCatching { visit(decor, 0) }.onFailure { return "未知" }
        return found ?: "无"
    }

    /** Dump one problem subtree (title bar or Me card row): class, live
     *  background, patch state, pressed/focused, and whether a
     *  SurfaceBackground wrapper would currently draw its original
     *  (pressed/focused, or the wrapped drawable is not neutral).
     *  Read-only; bounded so the diagnostic stays small. */
    private fun subtreeDump(root: View?, state: State, maxDepth: Int = 4): String {
        if (root == null) return "无"
        val out = ArrayList<String>()
        fun visit(view: View, depth: Int) {
            if (depth > maxDepth || out.size >= 12) return
            val bg = view.background
            val patch = state.patches.firstOrNull { it.view.get() === view }
            val patchState = when {
                patch == null -> "无"
                isPatchInstalled(patch, view) -> "已装"
                else -> "未装"
            }
            val draws = if (bg is SurfaceBackground) {
                " 绘=${bg.drawsBackground()} 抑制闲置=${bg.suppressFaintIdle} 忽略焦点=${bg.ignoreIdleFocus}"
            } else ""
            out.add("${view.javaClass.simpleName}/" +
                "${bg?.javaClass?.simpleName ?: "null"}${bg?.let { retainedDetail(it) } ?: ""} " +
                "补丁=$patchState 按压=${view.isPressed} 聚焦=${view.isFocused}$draws")
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                visit(view.getChildAt(i) ?: continue, depth + 1)
            }
        }
        runCatching { visit(root, 0) }.onFailure { return "未知" }
        return out.joinToString(";").ifEmpty { "空" }
    }

    /** Views outside the two problem subtrees whose bounds intersect the
     *  given screen rect and whose background is an opaque neutral surface:
     *  a sibling painted over the title bar or the card. Read-only. */
    private fun coveringWhiteViews(decor: ViewGroup, rect: Rect, state: State): String {
        val found = ArrayList<String>()
        var visited = 0
        val hit = Rect()
        fun visit(view: View, depth: Int) {
            if (depth > MAX_DEPTH || visited++ >= MAX_NODES || found.size >= 6) return
            if (view.visibility == View.VISIBLE && view.getGlobalVisibleRect(hit) &&
                Rect.intersects(hit, rect)) {
                val bg = view.background
                val opaque = when (bg) {
                    null -> false
                    is ColorDrawable -> Color.alpha(bg.color) != 0 && isNeutralColor(bg.color)
                    else -> isNeutralSurface(bg)
                }
                if (opaque && bg !is SurfaceBackground &&
                    state.patches.none { it.view.get() === view && isPatchInstalled(it, view) }) {
                    found.add("${view.javaClass.simpleName}@${hit.top}-${hit.bottom}/" +
                        "${bg.javaClass.simpleName}${retainedDetail(bg)}")
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                visit(view.getChildAt(i) ?: continue, depth + 1)
            }
        }
        runCatching { visit(decor, 0) }.onFailure { return "未知" }
        return found.joinToString(";").ifEmpty { "无" }
    }

    private fun coveringDumps(decor: ViewGroup, state: State): String {
        val out = ArrayList<String>()
        val rect = Rect()
        state.toolbarView?.get()?.let { toolbar ->
            if (toolbar.getGlobalVisibleRect(rect)) {
                out.add("标题栏遮挡=${coveringWhiteViews(decor, rect, state)}")
            }
        }
        state.cardRow?.get()?.let { row ->
            if (row.getGlobalVisibleRect(rect)) {
                out.add("资料卡遮挡=${coveringWhiteViews(decor, rect, state)}")
            }
        }
        return out.joinToString(" ").ifEmpty { "无目标" }
    }

    /** Bounded, read-only samples for Me-header and Contacts/WeCom white surfaces. */
    private fun unresolvedSurfaceSamples(decor: ViewGroup, state: State): String {
        val pager = state.pager.get() ?: return "无"
        val page = runCatching {
            pager.javaClass.getMethod("getCurrentItem").invoke(pager) as? Int
        }.getOrNull()
        if (page != 0 && page != 1 && page != 3) return "非目标页"
        val samples = ArrayList<String>()
        val rect = Rect()
        var visited = 0
        fun visit(view: View, depth: Int) {
            if (depth > MAX_DEPTH || visited++ >= MAX_NODES || samples.size >= 6 ||
                view.visibility != View.VISIBLE || !view.getGlobalVisibleRect(rect)) return
            val inRegion = when (page) {
                3 -> rect.top < decor.height * 0.38f
                0 -> rect.top < decor.height * 0.88f
                else -> rect.top < decor.height * 0.85f && rect.bottom > decor.height * 0.12f
            }
            if (inRegion && view !== decor && view !is ImageView &&
                view.width >= decor.width * 0.7f && rect.height() >= 48 &&
                state.patches.none { it.view.get() === view && isPatchInstalled(it, view) }) {
                view.background?.let { bg ->
                    samples.add("${view.javaClass.simpleName}@${rect.top}-${rect.bottom}/" +
                        "${bg.javaClass.simpleName}${retainedDetail(bg)}")
                }
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                visit(view.getChildAt(i) ?: continue, depth + 1)
                if (visited >= MAX_NODES || samples.size >= 6) break
            }
        }
        runCatching { visit(decor, 0) }
        return samples.joinToString(";").ifEmpty { "未发现未接管的可见底板" }
    }

    /** Read-only view-layer evidence; embedded text in user wallpaper is not an error. */
    private fun visibleRegionLayers(decor: ViewGroup, state: State): String {
        val pager = state.pager.get() ?: return "无"
        val page = runCatching {
            pager.javaClass.getMethod("getCurrentItem").invoke(pager) as? Int
        }.getOrNull()
        if (page != 0 && page != 1 && page != 3) return "非目标页"
        val hits = ArrayList<String>()
        val rect = Rect()
        var visited = 0
        fun walk(view: View, depth: Int) {
            if (visited++ >= MAX_NODES || depth > MAX_DEPTH ||
                view.visibility != View.VISIBLE || !view.getGlobalVisibleRect(rect)) return
            val area = when (page) {
                3 -> rect.top < decor.height * 0.38f
                0 -> rect.top < decor.height * 0.88f
                else -> rect.top < decor.height * 0.80f && rect.bottom > decor.height * 0.12f
            }
            if (view !== decor && area && rect.width() >= decor.width * 0.65f &&
                rect.height() > 45 && hits.size < 18) {
                val patch = state.patches.firstOrNull { it.view.get() === view }
                val patchFlag = when {
                    patch == null -> "-"
                    isPatchInstalled(patch, view) -> "ok"
                    else -> "changed"
                }
                // A view may draw its own white fill or foreground despite transparent background.
                val background = view.background
                val foreground = view.foreground
                val bg = background?.let { "${it.javaClass.simpleName}${retainedDetail(it)}" } ?: "null"
                val fg = foreground?.let { "${it.javaClass.simpleName}${retainedDetail(it)}" } ?: "null"
                val parent = (view.parent as? View)?.javaClass?.simpleName ?: "none"
                hits.add("${view.javaClass.simpleName}@${rect.top}-${rect.bottom}" +
                    "(bg=$bg fg=$fg patch=$patchFlag parent=$parent)")
            }
            if (view is ViewGroup) for (i in 0 until view.childCount) {
                walk(view.getChildAt(i) ?: continue, depth + 1)
                if (visited >= MAX_NODES) break
            }
        }
        runCatching { walk(decor, 0) }
        return hits.joinToString(";").ifEmpty { "无" }
    }

    private fun isNeutralSurface(drawable: Drawable?, depth: Int = 0): Boolean {
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

    /** Suppress only idle neutral fill; the platform wrapper forwards padding, states and ripple callbacks. */
    private class SurfaceBackground(
        original: Drawable,
        var suppressFaintIdle: Boolean = false
    ) : DrawableWrapper(original) {
        var ignoreIdleFocus = false
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = if (drawsBackground()) PixelFormat.TRANSLUCENT else PixelFormat.TRANSPARENT

        override fun draw(canvas: Canvas) {
            if (drawsBackground()) super.draw(canvas)
        }

        fun drawsBackground(): Boolean =
            state.any {
                it == android.R.attr.state_pressed ||
                    (it == android.R.attr.state_focused && !ignoreIdleFocus)
            } || (!isNeutralSurface(drawable) && !suppressFaintIdle)
    }

    // View.setBackgroundColor may recolor our ColorDrawable in place without changing identity.
    private fun isPatchInstalled(patch: Patch, view: View): Boolean {
        val installed = patch.installed.get() ?: return false
        return view.background === installed &&
            (!patch.structural || installed !is ColorDrawable || installed.color == Color.TRANSPARENT)
    }

    private fun restore(state: State) {
        restoreTaskBarPainters(state)
        restoreActionBarPrimaryBackground(state)
        state.patches.asReversed().forEach { patch ->
            val view = patch.view.get() ?: return@forEach
            if (isPatchInstalled(patch, view)) {
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
                if (isPatchInstalled(patch, view)) return patch.original
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
        // Pager can hide during the home mini-program pull-down: the installed
        // window wallpaper still counts as attached until the explicit gate closes.
        return decor.background === state.wallpaper && decor.isShown
    }

    fun describe(activity: Activity, includeHistory: Boolean = true): String? {
        val decor = activity.window?.decorView as? ViewGroup ?: return null
        val state = states[decor]?.get() ?: return if (includeHistory) restoreReasons[decor]?.let { "最近恢复原因=$it" } else null
        return "全屏底图=${if (isAttached(activity)) "已挂载" else "已撤下或被覆盖"} " +
            "窗口=${decor.width}x${decor.height} 图片=${state.wallpaper.bitmap.width}x${state.wallpaper.bitmap.height} " +
            "窗口可见=${decor.isShown} 分页可见=${state.pager.get()?.isShown} 最近检查守卫=${state.lastGateAllowed} " +
            "绘制=${state.wallpaper.drawCount}次 跳过=${state.wallpaper.skipCount}次 " +
            "检查=${state.visited} 透明底色=${state.patches.size} " +
            "宿主回写=${state.hostWrites} 回写分布=${state.hostWritesByClass.entries.sortedByDescending { it.value }.take(4).joinToString(",") { "${it.key}=${it.value}" }.ifEmpty { "无" }} " +
            "首页外壳修复=${state.firstScreenRepairs} 外壳拦截改色=${state.firstScreenBlockedRecolors} " +
            "定点行处理=${state.rowRepairs}（通讯录=${state.rowRepairsContacts} 资料卡=${state.rowRepairsCard} 行底色类型=${state.rowBgType}） " +
            "资料卡=${state.cardDiag} 资料卡重绑=${state.cardRebinds} 立即修复=${state.cardImmediateRepairs} " +
            "资料卡焦点抑制=${state.cardFocusSuppressed} 返回主页复查=${state.launcherReturnChecks} " +
            "资料卡淡底修复=${state.cardFaintRepairs} 标题区底板修复=${state.titleRegionRepairs} " +
            "会话滚动标题=${state.conversationHeaderState} 标题锚点命中=${state.conversationTitleAnchors} " +
            "标题定点修复=${state.conversationTitleRepairs} 标题Header修复=${state.conversationHeaderRepairs} " +
            "滚动标题窄条命中=${state.conversationStripChecks} 修复前绘制=${state.conversationStripIdleDraws} " +
            "窄条闲置抑制=${state.conversationStripSuppressions} " +
            "小程序展开=${state.taskBarState} 渐变透明度=${state.taskBarGradientAlpha} 已藏画笔=${state.taskBarHiddenCount} 画笔实况=${state.taskBarPainterSamples} " +
            "最近恢复原因=${restoreReasons[decor] ?: "无"} " +
            "行底色=${state.patches.count { !it.structural }} " +
            "保留行背景=${state.retainedRows.joinToString(",").ifEmpty { "无" }} " +
            "弹跳视图=${bounceViewBackgrounds(decor)}" +
            " ListView实况=${listViewLiveState(decor, state)}" +
            " 顶栏=${toolbarLiveState(decor, state)}" +
            " 标题栏子树=${subtreeDump(state.toolbarView?.get(), state)}" +
            " 资料卡子树=${subtreeDump(state.cardRow?.get(), state)}" +
            " 遮挡排查=${coveringDumps(decor, state)}" +
            " 页区未处理底板=${unresolvedSurfaceSamples(decor, state)}" +
            " 页区绘制层=${visibleRegionLayers(decor, state)}"
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
