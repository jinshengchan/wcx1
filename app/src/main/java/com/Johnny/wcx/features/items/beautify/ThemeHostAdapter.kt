package com.Johnny.wcx.features.items.beautify

import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.WeakHashMap

/** Runtime targets only: no obfuscated class guesses or process-wide background hooks. */
internal object ThemeHostAdapter {
    private class Session(val pager: WeakReference<ViewGroup>, gate: () -> Boolean) {
        var gate = WeakReference(gate)
        val lists = WeakHashMap<ListView, Boolean>()
        var actualPage: Int? = null
        var pageSource = "未知"
        var roots = 0
        var rows = 0
        var binds = 0
        var lastRole = "未识别"
        val seenPages = LinkedHashSet<Int>()
    }

    private val sessions = WeakHashMap<ViewGroup, Session>()
    private val bindings = LinkedHashMap<Method, Int>()
    private val tabCallbacks = LinkedHashMap<Method, Int>()
    private val observedPages = WeakHashMap<ViewGroup, Int>()
    private var mainTab = "未解析（MainTabUI + doOnCreate 字符串）"
    private var mainTabHits = 0
    var installListBindingHook: ((Method) -> Unit)? = null

    fun mainTabResolved(method: Method) { mainTab = method.toGenericString() }
    fun mainTabHit() { mainTabHits++ }
    fun tabCallbackHooked(method: Method) { tabCallbacks.putIfAbsent(method, 0) }
    fun tabCallbackHit(method: Method) { tabCallbacks[method] = (tabCallbacks[method] ?: 0) + 1 }

    /** A surviving LauncherUI need not emit MainTabUI.doOnCreate after feature re-enable. */
    fun liveTabAdapter(decor: ViewGroup?): Any? {
        val content = decor?.findViewById<ViewGroup>(android.R.id.content) ?: return null
        val pager = PageWallpaperUnderlay.findPager(content) ?: return null
        return runCatching { pager.javaClass.getMethod("getAdapter").invoke(pager) }.getOrNull()
    }

    fun track(decor: ViewGroup, pager: ViewGroup, gate: () -> Boolean) {
        val previous = sessions[decor]
        if (previous?.pager?.get() !== pager) sessions[decor] = Session(WeakReference(pager), gate)
        else previous.gate = WeakReference(gate)
    }

    fun trackAppearance(decor: ViewGroup, gate: () -> Boolean) {
        val content = decor.findViewById<ViewGroup>(android.R.id.content) ?: return
        val pager = PageWallpaperUnderlay.findPager(content) ?: return
        track(decor, pager, gate)
        structuralTargets(decor, pager)
    }

    fun pageObserved(decor: ViewGroup, page: Int?) {
        if (page in 0..3) observedPages[decor] = page!!
    }

    /** The real LauncherUI tab strip, not arbitrary bottom-aligned controls. */
    fun isBottomTab(view: View): Boolean {
        var type: Class<*>? = view.javaClass
        while (type != null) {
            if (type.name == "com.tencent.mm.ui.LauncherUIBottomTabView") return true
            type = type.superclass
        }
        return false
    }

    /** Only pager ancestors, its actual page roots and concrete visible lists are structural. */
    fun structuralTargets(decor: ViewGroup, pager: ViewGroup): Set<View> {
        val session = sessions[decor] ?: return emptySet()
        session.lists.clear()
        session.roots = 0
        session.rows = 0
        val pagerPage = runCatching {
            pager.javaClass.getMethod("getCurrentItem").invoke(pager) as? Int
        }.getOrNull()?.takeIf { it in 0..3 }
        session.actualPage = pagerPage ?: observedPages[decor]
        session.pageSource = if (pagerPage != null) "ViewPager.getCurrentItem" else "切页回调/真实页码读取"
        session.actualPage?.let(session.seenPages::add)
        val targets = LinkedHashSet<View>()
        var ancestor: View? = pager
        while (ancestor != null && ancestor !== decor) {
            targets.add(ancestor)
            ancestor = ancestor.parent as? View
        }
        var visited = 0
        val visible = android.graphics.Rect()
        fun lists(view: View, depth: Int) {
            if (depth > 20 || visited++ >= 1800 || !view.getGlobalVisibleRect(visible)) return
            if (view is ListView) {
                targets.add(view)
                session.lists[view] = true
                session.rows += view.childCount
                // Me's profile header is a ListView header, not an adapter-bound row.
                // Only take the first visible header at the top of the Me tab.
                if (session.actualPage == 3 && view.headerViewsCount > 0 && view.firstVisiblePosition == 0) {
                    val header = view.getChildAt(0)
                    val headerRect = android.graphics.Rect()
                    if (header != null && header.width >= decor.width * 0.7f &&
                        header.getGlobalVisibleRect(headerRect) &&
                        headerRect.top <= decor.height * 0.28f &&
                        headerRect.height() >= decor.height * 0.08f) {
                        targets.add(header)
                    }
                }
                val adapter = view.adapter
                val method = adapter?.let {
                    runCatching {
                        it.javaClass.getMethod("getView", Int::class.javaPrimitiveType, View::class.java, ViewGroup::class.java)
                    }.getOrNull()
                }
                val installer = installListBindingHook
                if (method != null && !Modifier.isAbstract(method.modifiers) &&
                    View::class.java.isAssignableFrom(method.returnType) && method !in bindings && installer != null) {
                    installer(method)
                    bindings[method] = 0
                }
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                lists(view.getChildAt(index) ?: continue, depth + 1)
                if (visited >= 1800) break
            }
        }
        for (index in 0 until pager.childCount) {
            val root = pager.getChildAt(index) ?: continue
            if (!root.getGlobalVisibleRect(visible)) continue
            targets.add(root)
            session.roots++
            lists(root, 0)
        }
        return targets
    }

    /** getView may return a row before attach; its real parent + adapter establish scope. */
    fun onListBound(method: Method, adapter: Any?, parent: ViewGroup?, row: View?) {
        val list = parent as? ListView ?: return
        if (row == null || list.adapter !== adapter || !list.getGlobalVisibleRect(android.graphics.Rect())) return
        val entry = sessions.entries.firstOrNull { (_, session) ->
            session.lists.containsKey(list) && session.gate.get()?.invoke() == true &&
                list.isShown && session.pager.get()?.isShown == true
        } ?: return
        val session = entry.value
        session.binds++
        session.lastRole = "ListView 行/底板（页=${session.actualPage ?: "未知"}）"
        bindings[method] = (bindings[method] ?: 0) + 1
        PageWallpaperUnderlay.onRowBound(entry.key, list, row)
    }

    fun forget(decor: ViewGroup) { sessions.remove(decor) }

    /** Feature.disable() removes hooks; permit installation again after enable. */
    fun resetHooks() {
        bindings.clear()
        tabCallbacks.clear()
        sessions.clear()
        observedPages.clear()
        installListBindingHook = null
        mainTab = "未解析（功能关闭）"
    }

    fun describe(decor: ViewGroup?): String = buildString {
        appendLine("宿主适配 MainTabUI = $mainTab；实际命中=$mainTabHits")
        tabCallbacks.forEach { (method, hits) -> appendLine("切页能力 ${method.toGenericString()} 命中=$hits") }
        val session = sessions[decor]
        appendLine("结构角色 = 实际页=${session?.actualPage ?: "未知"} 页根=${session?.roots ?: 0} " +
            "页码来源=${session?.pageSource ?: "未知"} 目标页=${observedPages[decor] ?: "未知"} " +
            "ListView=${session?.lists?.size ?: 0} 当前行=${session?.rows ?: 0} 绑定命中=${session?.binds ?: 0} " +
            "最近角色=${session?.lastRole ?: "无"} 已观测页=${session?.seenPages?.joinToString() ?: "无"}")
        appendLine("会话/Me 绑定 = 可见 ListView adapter 精确 getView(int,View,ViewGroup)；" +
            "已挂=${bindings.size}（未命中不算宿主支持）")
        bindings.forEach { (method, hits) -> appendLine("  ${method.toGenericString()} 命中=$hits") }
        append("Preference 专用绑定/RecyclerView/原生顶栏/Tab hook = 未解析；中性底色扫描回退，不宣称完整适配")
    }
}
