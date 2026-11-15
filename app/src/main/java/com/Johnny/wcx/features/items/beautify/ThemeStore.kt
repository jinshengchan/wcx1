package com.Johnny.wcx.features.items.beautify

import android.app.Activity
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.View
import androidx.activity.ComponentActivity
import com.Johnny.wcx.BuildConfig
import com.Johnny.wcx.activity.settings.SettingsNavigation
import com.Johnny.wcx.features.api.ui.WeChatMessageViewApi
import com.Johnny.wcx.features.api.ui.WeMainActivityBeautifyApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.utils.reflection.ClassLoaders
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.fs.createDirsSafe
import dev.ujhhgtg.reflekt.reflekt
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries

/**
 * 主题工坊 —— 功能门面（2026-10-27 整体重写）。
 *
 * ## 这一层只做什么
 * 保留对外的**全部公开入口**（设置页调用的 18 个 API）、Feature 生命周期与「应用 / 恢复」这条主线；
 * 具体实现全部下沉到下面几个组件，互不纠缠：
 *
 * | 组件 | 职责 |
 * |---|---|
 * | [BuiltinThemes] / [ThemePaths] / [ThemeAliases] | 主题定义、素材路径、别名描述持久化 |
 * | [ThemeMaterials] | APK 素材提取、4 页壁纸解析、「当前页面该用哪张图」 |
 * | [ThemeBubbleKit] | 气泡备份 / 原子替换 / 9-patch 套用 |
 * | [ThemePages] / [ThemeSurface] | 页面分类、沉浸式窗口、生命周期挂载与还原 |
 * | [TabWallpaperRenderer] | DecorView 前景遮罩渲染（不塞子 View、不依赖布局、不拦点击） |
 *
 * **外观 / 入口 / 布局完全不变**：主题工坊页面仍是原来那套 UI，只是它背后调的东西换了。
 *
 * 历史：v245 起的六款内置主题（Hello Kitty / 小熊 / 线条小狗 / 吉伊卡哇 / 美食小狗 / 小兔子）、
 * 主题包导入导出、透明度 5~70%、气泡写入模块 assets、恢复默认自动还原备份 —— 全部保留。
 */
@Feature(
    name = "主题工坊",
    categories = ["界面美化"],
    description = "内置 6 款全局主题，全局沉浸壁纸（主页/通讯录/发现/我的 等 4 个界面，或全部界面）+ 对应气泡，支持透明度调节与备份恢复"
)
object ThemeStore : ClickableFeature(), WeChatMessageViewApi.ICreateViewListener {

    private const val TAG = "ThemeStore"

    private const val CUSTOM_BUBBLES_PREF_KEY = "自定义消息气泡"

    /** 当前功能名（与 @Feature.name 保持一致，初始化期 name 还没注入）。 */
    private const val NEW_FEATURE_NAME = "主题工坊"

    /** 改名前的老开关 key，用于把用户的旧状态迁过来。 */
    private const val LEGACY_FEATURE_NAME = "主题商城"

    init {
        // 功能从「主题商城」改名「主题工坊」。开关在 WePrefs 里以 name 为 key，
        // 这里只清掉旧 key，**不继承旧值** —— 主题总开关必须默认关闭，
        // 要开只能靠用户主动点「应用」（那时才写 true，否则应用完不生效）。
        runCatching {
            if (WePrefs.containsKey(LEGACY_FEATURE_NAME)) {
                WePrefs.remove(LEGACY_FEATURE_NAME)
                WeLogger.i(TAG, "已清理旧开关「$LEGACY_FEATURE_NAME」（主题工坊默认关闭）")
            }
        }.onFailure { WeLogger.w(TAG, "清理旧主题开关失败：${it.message}") }
    }

    // ══════════════════════════════════════════════════════════
    // 持久化
    // ══════════════════════════════════════════════════════════

    /** 当前生效主题 id（内置主题与主题包共用）。 */
    private var activeThemeId by prefOption("theme_store_active_id", "")

    /** 壁纸透明度（%），5~70。 */
    private var overlayOpacityPct by prefOption("theme_store_overlay_opacity", 40)

    /** Optional four-tab background renderer. The previous foreground renderer remains a fallback. */
    private var pageBackgroundEnabled by prefOption("theme_store_page_background", false)

    private var mainWallpaperOn by prefOption("theme_store_main_wallpaper", true)
    private var mainTextColorOn by prefOption("theme_store_main_text_color", true)
    private var mainTitleColorHex by prefOption("theme_store_main_title_color", "")

    private val profileRepository by lazy { ThemeProfileRepository(File(ThemePaths.storeDir.toFile(), "profiles")) }

    init {
        ThemePack.snapshotProvider = { id -> profileSnapshot(id) }
        ThemePack.onChanged = { pack, role -> syncPackEdit(pack, role) }
    }

    /** 渲染只读已校验的快照；每套主题首次读取时才迁移旧文件。 */
    internal fun profileSnapshot(id: String = activeThemeId): ThemeProfileRepository.Snapshot? {
        if (id.isBlank()) return null
        return runCatching { profileRepository.read(id) { profileSeed(id) } }
            .onFailure { WeLogger.w(TAG, "主题配置读取失败：$id / ${it.message}") }.getOrNull()
    }

    internal fun updateThemeProfile(
        manifest: org.json.JSONObject,
        assets: Map<String, File> = emptyMap(),
        id: String = activeThemeId
    ): Result<Unit> = runCatching {
        requireNotNull(profileSnapshot(id)) { "主题配置不可用" }
        profileRepository.save(id, manifest, assets)
        if (id == activeThemeId) {
            runCatching {
                invalidateWallpaperCache()
                ThemeRuntime.invalidate()
                ThemeOwnedAppearance.invalidate()
                CustomMessageBubbles.refreshBoundViews()
                ThemeSurface.applyToAllLive()
            }.onFailure { WeLogger.w(TAG, "主题已保存，刷新失败：${it.message}") }
        }
    }

    private fun profileSeed(id: String): ThemeProfileRepository.Seed {
        val pack = ThemePack.find(id)
        val source: File
        val manifest: org.json.JSONObject
        if (pack != null) {
            source = pack.dir
            manifest = pack.profile.toJson()
        } else {
            val builtin = requireNotNull(BuiltinThemes.find(id)) { "未知主题：$id" }
            ThemeMaterials.ensureExtracted(builtin)
            source = ThemePaths.themeDir(builtin).toFile()
            manifest = org.json.JSONObject().put("name", builtinDisplayName(id, builtin.name))
                .put("author", "WCX").put("version", "1.0").put("description", builtinDesc(id))
            ThemePaths.wallpaperFile(builtin).toFile().takeIf { it.isFile }?.let { manifest.put("wallpaper", it.name) }
            ThemePaths.bubbleFile(builtin, ThemePaths.LEFT_BUBBLE_FILE).toFile().takeIf { it.isFile }?.let { manifest.put("bubble_left", it.name) }
            ThemePaths.bubbleFile(builtin, ThemePaths.RIGHT_BUBBLE_FILE).toFile().takeIf { it.isFile }?.let { manifest.put("bubble_right", it.name) }
        }
        val canonical = ThemeProfile.parse(manifest).toJson()
        val appearance = canonical.getJSONObject("appearance")
        val pages = appearance.getJSONObject("pages")
        val extras = mutableMapOf<String, File>()
        val overrides = mainWallpaperOverrideDir(id)
        mainWallpaperKeys.forEach { key ->
            val file = File(overrides, "$key.img")
            when {
                File(overrides, "$key.disabled").exists() -> pages.put(key, org.json.JSONObject().put("kind", "native"))
                file.isFile && file.length() > 0L -> {
                    val relative = "overrides/$key.img"
                    pages.put(key, org.json.JSONObject().put("kind", "image").put("asset", relative))
                    extras[relative] = file
                }
            }
        }
        // 仅迁移已有生效主题的全局旧偏好；以后应用其它主题以各自配置为准。
        if (id == activeThemeId) {
            pages.put("enabled", mainWallpaperOn)
            appearance.put("compatibility", org.json.JSONObject().put("mainTextColorEnabled", mainTextColorOn))
            if (mainTitleColorHex.matches(Regex("#[0-9a-fA-F]{6}"))) {
                for ((group, keys) in mapOf(
                    "chrome" to listOf("toolbarText", "toolbarIcon", "tabText", "tabSelectedText", "tabIcon", "tabSelectedIcon"),
                    "lists" to listOf("title")
                )) {
                    val values = appearance.optJSONObject(group) ?: org.json.JSONObject().also { appearance.put(group, it) }
                    keys.filterNot { values.has(it) }.forEach { key ->
                        values.put(key, org.json.JSONObject().put("mode", "value").put("value", mainTitleColorHex))
                    }
                }
            }
        }
        return ThemeProfileRepository.Seed(canonical) { extras[it] ?: ThemePack.safeAsset(source, it) }
    }

    /** 包编辑只同步被编辑的素材位，保留已保存的配色、分页和模式。 */
    private fun syncPackEdit(pack: ThemePack.Pack, role: String) {
        val snapshot = requireNotNull(profileSnapshot(pack.id)) { "无法同步主题配置" }
        val json = snapshot.profile.toJson()
        val assets = mutableMapOf<String, File>()
        when {
            role == "metadata" -> for (key in listOf("name", "description")) {
                if (pack.manifest.has(key)) json.put(key, pack.manifest.get(key))
            }
            role == "switches" -> json.put("switches", pack.manifest.optJSONObject("switches"))
            else -> {
                val original = pack.manifest.optString(role)
                val file = pack.asset(original)
                val relative = file?.let { "edits/${java.util.UUID.randomUUID()}/${it.name}" }
                if (relative == null) json.remove(role) else {
                    json.put(role, relative)
                    assets[relative] = file
                }
                val page = if (role == "wallpaper") "common" else role.removePrefix("wallpaper_")
                if (page == "common" || page in ThemeProfile.PAGE_KEYS) {
                    json.getJSONObject("appearance").getJSONObject("pages").put(page,
                        org.json.JSONObject().put("kind", if (relative == null) "native" else "image")
                            .also { if (relative != null) it.put("asset", relative) })
                }
                if (role == "bubble_left" || role == "bubble_right") {
                    val appearance = json.getJSONObject("appearance")
                    val chat = appearance.optJSONObject("chat") ?: org.json.JSONObject().also { appearance.put("chat", it) }
                    val rule = org.json.JSONObject().put("kind", if (relative == null) "native" else "image")
                    if (relative != null) rule.put("asset", relative)
                    chat.put(if (role == "bubble_left") "leftBubble" else "rightBubble", rule)
                }
            }
        }
        updateThemeProfile(json, assets, pack.id).getOrThrow()
    }

    fun cloneTheme(id: String, name: String? = null): Result<ThemePack.Pack> = runCatching {
        val snapshot = requireNotNull(profileSnapshot(id)) { "主题配置不可用" }
        ThemePack.cloneProfile(name ?: "${snapshot.profile.toJson().optString("name")} 副本", snapshot.profile, snapshot::asset).getOrThrow()
    }

    fun exportTheme(id: String): Result<File> = runCatching {
        val snapshot = requireNotNull(profileSnapshot(id)) { "主题配置不可用" }
        ThemePack.exportProfile(snapshot.profile.toJson().optString("name"), snapshot.profile, snapshot::asset).getOrThrow()
    }

    // ══════════════════════════════════════════════════════════
    // Feature 生命周期
    // ══════════════════════════════════════════════════════════

    /**
     * 主界面切 Tab 时立即挂 / 换壁纸。
     *
     * 必须写在 Feature 子类里：`hookAfter` / `hookBefore` 是 BaseFeature 的**成员扩展**，
     * 搬到独立 object 里会 Unresolved（2026-10-27 重写时踩过）。
     */
    private fun hookTabSwitch() {
        if (doOnCreateHooked) return
        val method = runCatching { WeMainActivityBeautifyApi.methodDoOnCreate.method }
            .onFailure { WeLogger.w(TAG, "解析主界面创建方法失败", it) }.getOrNull() ?: return
        method.hookAfter {
            ThemeHostAdapter.mainTabHit()
            val activity = runCatching {
                thisObject.reflekt().firstField { type = "com.tencent.mm.ui.MMFragmentActivity" }.get() as? Activity
            }.getOrNull() ?: (thisObject as? Activity) ?: return@hookAfter
            if (!ThemePages.isLauncher(activity)) return@hookAfter
            ThemeSurface.applyNow(activity)
            activity.window?.decorView?.post { ThemeSurface.applyNow(activity) }
            val tabsAdapter = runCatching {
                thisObject.reflekt().firstField { name = "mTabsAdapter" }.get()
            }.getOrNull() ?: ThemeHostAdapter.liveTabAdapter(activity.window?.decorView as? android.view.ViewGroup)
            if (tabsAdapter != null) hookTabsAdapter(activity, tabsAdapter)
        }
        ThemeHostAdapter.mainTabResolved(method)
        doOnCreateHooked = true
    }

    private fun hookTabsAdapter(activity: Activity, tabsAdapter: Any) {
        if (tabsAdapter in hookedTabsAdapters) return
        val selected = runCatching {
            tabsAdapter.javaClass.getMethod("onPageSelected", Int::class.javaPrimitiveType)
        }.getOrNull()
        val scrolled = runCatching {
            tabsAdapter.javaClass.getMethod("onPageScrolled", Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }.getOrNull()
        if (selected == null && scrolled == null) return
        val activityRef = java.lang.ref.WeakReference(activity)
        val adapterRef = java.lang.ref.WeakReference(tabsAdapter)
        selected?.hookAfter {
            if (thisObject !== adapterRef.get()) return@hookAfter
            val host = activityRef.get() ?: return@hookAfter
            val position = args.getOrNull(0) as? Int ?: return@hookAfter
            ThemeHostAdapter.tabCallbackHit(selected)
            ThemeSurface.onTabSelected(host, position)
        }
        scrolled?.hookBefore {
            if (thisObject !== adapterRef.get()) return@hookBefore
            val host = activityRef.get() ?: return@hookBefore
            val position = args.getOrNull(0) as? Int ?: return@hookBefore
            val offset = args.getOrNull(1) as? Float ?: return@hookBefore
            ThemeHostAdapter.tabCallbackHit(scrolled)
            ThemeSurface.onTabScrolled(host, position, offset)
        }
        selected?.let(ThemeHostAdapter::tabCallbackHooked)
        scrolled?.let(ThemeHostAdapter::tabCallbackHooked)
        hookSelectedOk = hookSelectedOk || selected != null
        hookScrolledOk = hookScrolledOk || scrolled != null
        hookedTabsAdapters.add(tabsAdapter)
    }

    private fun hookLiveTabAdapters() {
        ThemeSurface.liveActivities().filter { ThemePages.isLauncher(it) }.forEach { activity ->
            val adapter = ThemeHostAdapter.liveTabAdapter(activity.window?.decorView as? android.view.ViewGroup)
            if (adapter != null) hookTabsAdapter(activity, adapter)
        }
    }

    /**
     * 两条切页信号是否挂上（诊断页直接看这两行，一眼定位「4 页不换图」）。
     *
     * 旧版 TabTheme 之所以 4 张图各自都对，就是 onPageSelected 回调里直接按 position 切
     * —— position 是微信亲口报的，不依赖读 ViewPager 字段。
     */
    /** doOnCreate 是否已挂过（避免重复注册 lambda）。 */
    @Volatile private var doOnCreateHooked = false

    /** 聊天页 ChattingUI 是否已 hook。 */
    @Volatile private var chattingPageHooked = false

    @Volatile private var chattingHookOk = false

    /** 聊天页 Fragment（真正从会话列表进入时走的路径）是否已 hook。 */
    @Volatile private var chattingFragmentHooked = false

    @Volatile private var chattingFragmentHookOk = false

    @Volatile private var chattingFragmentHookCount = 0

    @Volatile private var hookScrolledOk = false
    @Volatile private var hookSelectedOk = false

    /** 已挂过切页监听的 adapter，避免每次进主页重复 hook。 */
    private val hookedTabsAdapters: MutableSet<Any> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())

    /**
     * 聊天页 Fragment 锚点（**从会话列表进入聊天页的真实路径**）。
     *
     * 8.0.79 dex 实测（明文、未被 R8 混淆）：
     * - `com.tencent.mm.ui.chatting.ChattingUIFragment` extends `BaseChattingUIFragment` extends `MMFragment`
     * - `com.tencent.mm.ui.chatting.ChattingUI` extends `MMSecDataFragmentActivity`（外部/通知进入时才用）
     *
     * 微信对这几个类一直保留明文（R8 只重命名自己的业务类），因此它们是跨版本相对稳定的锚点；
     * 即便如此，ChatPageGuard 的纯 View 特征识别仍然保留作为兜底，任一路命中即可。
     */
    private fun hookChattingFragment() {
        if (chattingFragmentHooked) return
        chattingFragmentHooked = true
        var hooked = 0
        listOf(
            "com.tencent.mm.ui.chatting.ChattingUIFragment",
            "com.tencent.mm.ui.chatting.BaseChattingUIFragment"
        ).forEach { clsName ->
            runCatching {
                val clazz = ClassLoaders.HOST.loadClass(clsName)
                listOf("onCreate", "onResume", "onViewCreated").forEach { methodName ->
                    runCatching {
                        val method = clazz.reflekt().firstMethodOrNull { name = methodName }
                            ?: return@runCatching
                        method.hookAfter {
                            val view = runCatching {
                                thisObject.javaClass.getMethod("getView").invoke(thisObject) as? View
                            }.getOrNull()
                            ThemeSurface.onChattingFragmentShown(view)
                        }
                        hooked++
                    }.onFailure { WeLogger.w(TAG, "hook $clsName.$methodName 失败", it) }
                }
                listOf("onPause", "onDestroyView").forEach { methodName ->
                    runCatching {
                        val method = clazz.reflekt().firstMethodOrNull { name = methodName }
                            ?: return@runCatching
                        method.hookAfter { ThemeSurface.onChattingFragmentHidden() }
                        hooked++
                    }.onFailure { WeLogger.w(TAG, "hook $clsName.$methodName 失败", it) }
                }
            }.onFailure { WeLogger.w(TAG, "加载聊天页 Fragment 失败（$clsName）：${it.message}") }
        }
        // 泛化兜底：MMFragment 是所有微信 Fragment 的基类。
        // 万一上面两个具体类的方法是被子类 override（父类声明 hook 不到），
        // 这里按「类名里带 Chatting」兜住私聊 / 群聊 / 半屏聊天 / 公众号聊天等全部变体。
        runCatching {
            val mmFragment = ClassLoaders.HOST.loadClass("com.tencent.mm.ui.MMFragment")
            listOf("onResume", "onCreate").forEach { methodName ->
                runCatching {
                    val method = mmFragment.reflekt().firstMethodOrNull { name = methodName }
                        ?: return@runCatching
                    method.hookAfter {
                        val cn = thisObject.javaClass.name.orEmpty()
                        if (cn.contains("Chatting", ignoreCase = true)) {
                            val view = runCatching {
                                thisObject.javaClass.getMethod("getView").invoke(thisObject) as? View
                            }.getOrNull()
                            ThemeSurface.onChattingFragmentShown(view)
                        }
                    }
                    hooked++
                }.onFailure { WeLogger.w(TAG, "hook MMFragment.$methodName 失败", it) }
            }
        }.onFailure { WeLogger.w(TAG, "加载 MMFragment 失败：${it.message}") }

        chattingFragmentHookCount = hooked
        chattingFragmentHookOk = hooked > 0
        // 锚点挂上 → 聊天页状态以锚点为权威，视图特征不再参与判定（防止残留卡死绘制）
        ThemeSurface.fragmentAnchorAvailable = hooked > 0
        WeLogger.i(TAG, "聊天页 Fragment 锚点：已 hook $hooked 个方法（进入即撤壁纸 + 容器兜底）")
    }

    /**
     * 聊天页锚点：`com.tencent.mm.ui.chatting.ChattingUI`（8.0.79 实测类名，多年未变）。
     *
     * 它一出现就把主界面壁纸遮罩整个撤掉 —— 这比在主界面侧等 onPause / 等失焦可靠得多，
     * 因为聊天页无论是不是半透明窗口，它自己的 onCreate/onResume 一定会执行。
     */
    private fun hookChattingPage() {
        if (chattingPageHooked) return
        chattingPageHooked = true
        runCatching {
            val clazz = ClassLoaders.HOST.loadClass("com.tencent.mm.ui.chatting.ChattingUI")
            var hooked = 0
            listOf("onCreate", "onResume").forEach { methodName ->
                runCatching {
                    val method = clazz.reflekt().firstMethodOrNull { name = methodName }
                    if (method == null) {
                        WeLogger.w(TAG, "ChattingUI 里没有 $methodName 方法，跳过")
                        return@runCatching
                    }
                    method.hookAfter {
                        ThemeSurface.onChattingPageShown(thisObject as? Activity)
                    }
                    hooked++
                }.onFailure { WeLogger.w(TAG, "hook ChattingUI.$methodName 失败", it) }
            }
            runCatching {
                clazz.reflekt().firstMethodOrNull { name = "onDestroy" }?.let { method ->
                    method.hookAfter { ThemeSurface.onChattingPageClosed() }
                    hooked++
                }
            }.onFailure { WeLogger.w(TAG, "hook ChattingUI.onDestroy 失败", it) }
            chattingHookOk = hooked > 0
            WeLogger.i(TAG, "聊天页锚点：已 hook ChattingUI 的 $hooked 个方法（进入即撤掉主界面壁纸）")
        }.onFailure { WeLogger.w(TAG, "hook 聊天页 ChattingUI 失败：${it.message}") }
    }

    override fun onEnable() {
        // 清掉历史遗留的持久化失败计数（老版本写进去的脏值会让主题被永久锁死）
        runCatching { WePrefs.remove("theme_store_wallpaper_fail") }
        ThemeHostAdapter.installListBindingHook = { method ->
            method.hookAfter {
                ThemeHostAdapter.onListBound(
                    method, thisObject, args.getOrNull(2) as? android.view.ViewGroup, result as? View
                )
            }
        }
        hookTabSwitch()
        hookLiveTabAdapters()
        hookChattingPage()
        hookChattingFragment()
        ThemeSurface.registerCallbacks(true)
        WeChatMessageViewApi.addListener(this)
        ThemeRuntime.invalidate()
        ThemeOwnedAppearance.invalidate()
        CustomMessageBubbles.refreshBoundViews()
        ThemeSurface.applyToAllLive()
        if (activeThemeId.isNotEmpty()) {
            // 进程重启后恢复：壁纸/沉浸式全局重挂（气泡文件已在 moduleAssets 持久存在）
            WeLogger.i(TAG, "已恢复主题：$activeThemeId")
        }
    }

    override fun onDisable() {
        // 只撤效果，**保留主题选择**：关开关不该清掉 activeThemeId，
        // 否则每次关掉再打开都得回主题工坊重新应用一次（实测用户反复测试全被这个清空）。
        // 重开时 onEnable 里的 applyToAllLive() 会按 activeThemeId 自动恢复。
        runCatching { ThemeSurface.removeAllLive() }
        ThemeSurface.registerCallbacks(false)
        ThemeHostAdapter.resetHooks()
        // BaseFeature.disable unhooks these too; enable must resolve and install them again.
        doOnCreateHooked = false
        hookedTabsAdapters.clear()
        hookSelectedOk = false
        hookScrolledOk = false
        chattingPageHooked = false
        chattingHookOk = false
        chattingFragmentHooked = false
        chattingFragmentHookOk = false
        chattingFragmentHookCount = 0
        ThemeSurface.fragmentAnchorAvailable = false
        WeChatMessageViewApi.removeListener(this)
        ChatThemeAppearance.restoreAll()
        ThemeRuntime.invalidate()
        ThemeOwnedAppearance.invalidate()
        CustomMessageBubbles.refreshBoundViews()
    }

    // ══════════════════════════════════════════════════════════
    // 入口
    // ══════════════════════════════════════════════════════════

    override fun onClick(context: ComponentActivity) {
        // 主题相关操作已全部并入「主题工坊」子页面，这里只做导航。
        SettingsNavigation.requestThemeWorkshop()
    }

    // ══════════════════════════════════════════════════════════
    // 应用主题
    // ══════════════════════════════════════════════════════════

    /**
     * 应用主题前确保相关 Feature 已启用。
     *
     * 曾经「点了内置主题没效果」的根因就在这里：只写了 activeThemeId，
     * 但「主题工坊」Feature 从未被开启 → hook 没装 → 页面上根本没人去画壁纸。
     */
    private fun ensureThemeFeaturesEnabled() {
        runCatching {
            if (!isEnabled) {
                WeLogger.i(TAG, "主题工坊未启用，随「应用主题」自动开启")
                // 必须同步写 WePrefs：Feature.startup() 是从 pref 读开关的，
                // 只改内存里的 isEnabled 的话，重启微信后开关又是关的 → 主题整片失效。
                WePrefs.putBool(NEW_FEATURE_NAME, true)
                isEnabled = true
            }
        }.onFailure { WeLogger.w(TAG, "自动启用主题相关功能失败：${it.message}") }
    }

    /** 自动切换只在总开关已开启时执行，绝不调用启用功能的路径。 */
    internal fun applyScheduledTheme(id: String): Boolean {
        if (!wallpaperSwitchOn() || !isEnabled || id == activeThemeId) return false
        val pack = ThemePack.find(id)
        val builtin = BuiltinThemes.find(id)
        if (pack == null && builtin == null) return false
        val snapshot = profileSnapshot(id) ?: return false
        ThemePack.validateAssets(snapshot.profile) { snapshot.asset(it) }
        if (builtin != null) ThemeMaterials.ensureExtracted(builtin)
        if (!wallpaperSwitchOn() || !isEnabled) return false
        ChatThemeAppearance.restoreAll()
        activeThemeId = id
        ThemePack.currentId = pack?.id ?: ""
        ThemeRuntime.invalidate()
        ThemeOwnedAppearance.invalidate()
        CustomMessageBubbles.refreshBoundViews()
        ThemeSurface.applyToAllLive()
        return true
    }

    internal fun refreshEffectiveTheme() {
        ThemeRuntime.invalidate()
        ThemeOwnedAppearance.invalidate()
        if (!wallpaperSwitchOn() || !isEnabled) return
        CustomMessageBubbles.refreshBoundViews()
        ThemeSurface.applyToAllLive()
    }

    /** 应用主题（内置或主题包），并同步一次壁纸透明度。 */
    fun applyThemeById(id: String) {
        requireNotNull(profileSnapshot(id)) { "主题配置无效：$id" }
        // 用户主动应用 = 重新给一次机会，清掉本进程内的连续失败计数
        ThemeSurface.resetFailStreak()
        // 顺手确认一次切页 hook（幂等）：万一 onEnable 时还没到主界面 doOnCreate，这里补上
        hookTabSwitch()
        hookChattingPage()
        hookChattingFragment()
        applyThemeInternal(id)
        setOverlayOpacity(overlayOpacityPct)
    }

    private fun applyThemeInternal(id: String) {
        ChatThemeAppearance.restoreAll()
        ensureThemeFeaturesEnabled()
        // 外部主题包优先：壁纸与左右气泡取自包内素材，缺哪项就跳过，不影响其它维度。
        ThemePack.find(id)?.let {
            applyPackInternal(it)
            return
        }
        val theme = BuiltinThemes.find(id) ?: run {
            WeLogger.w(TAG, "未知主题：$id")
            return
        }
        try {
            ThemeMaterials.ensureExtracted(theme)
            activeThemeId = id
            // 切到内置主题时清掉主题包游标，避免上一套主题包继续生效
            ThemePack.currentId = ""
            ThemeRuntime.invalidate()
            ThemeOwnedAppearance.invalidate()
            CustomMessageBubbles.refreshBoundViews()
            ThemeSurface.applyToAllLive()
            WeLogger.i(TAG, "已应用主题：${theme.name}")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "applyThemeInternal 异常", e)
        }
    }

    /** 应用外部主题包（ThemePack）：壁纸 + 左右气泡。 */
    private fun applyPackInternal(pack: ThemePack.Pack) {
        try {
            activeThemeId = pack.id
            // 关键：壁纸读取走 ThemePack.active()（读 currentId）。
            // 只写 activeThemeId 不写 currentId，重启微信后壁纸永远解析不出来。
            ThemePack.currentId = pack.id
            ThemeRuntime.invalidate()
            ThemeOwnedAppearance.invalidate()
            CustomMessageBubbles.refreshBoundViews()
            ThemeSurface.applyToAllLive()
            WeLogger.i(
                TAG,
                "已应用主题包：${pack.name}（壁纸=${pack.wallpaper() != null}）"
            )
        } catch (e: Throwable) {
            WeLogger.e(TAG, "applyPackInternal 异常", e)
        }
    }

    /**
     * 把当前生效的主题（内置或主题包）打包成 zip，可直接分享给别人导入。
     * 内置主题会先按同样格式生成一个主题包再打包，保证两边格式一致。
     */
    fun exportActiveTheme(): File? = exportTheme(activeThemeId).getOrNull()

    // ══════════════════════════════════════════════════════════
    // 恢复默认 / 清缓存
    // ══════════════════════════════════════════════════════════

    /** 恢复默认：清空主题 id、还原窗口与壁纸层、还原应用主题前的气泡文件。 */
    private fun restoreDefault() {
        try {
            ChatThemeAppearance.restoreAll()
            activeThemeId = ""
            ThemePack.currentId = ""
            ThemeRuntime.invalidate()
            ThemeOwnedAppearance.invalidate()
            CustomMessageBubbles.refreshBoundViews()
            ThemeSurface.removeAllLive()
        } catch (e: Throwable) {
            WeLogger.e(TAG, "restoreDefault 异常", e)
        }
    }

    fun restoreDefaultTheme() = restoreDefault()

    /**
     * 主题工坊总开关是否打开 —— **所有重挂 / 绘制入口的总闸**。
     *
     * 为什么必须单独有这道闸：[onDisable] 只撤效果、不清 activeThemeId
     * （否则关掉再打开得重新应用主题），于是那些「只看 activeId、不看开关」的
     * 路径（焦点补挂、切页 hook、锚点离开重挂）在总开关关掉后仍然会把壁纸挂回去。
     * 实测现象：关掉总开关后，4 个界面仍显示主页面的背景图片。
     */
    fun wallpaperSwitchOn(): Boolean =
        runCatching { WePrefs.getBoolOrDef(NEW_FEATURE_NAME, false) }.getOrDefault(false)

    /**
     * 清除主题相关缓存（应急用）。
     *
     * 场景：换主题后旧气泡没被替换、或没在用主题却还留着残留气泡时，
     * 撤销当前主题并清除解码与内置提取缓存；保留主题配置、主题包和独立气泡文件。
     */
    fun clearThemeCache() {
        runCatching {
            restoreDefault()
            // 内置素材提取缓存：下次应用主题会重新从 APK 提取
            runCatching {
                ThemePaths.builtinDir.toFile().deleteRecursively()
                ThemePaths.builtinDir.createDirsSafe()
            }
            ThemeSurface.resetWallpaperLog()
            ThemeSurface.resetFailStreak()
            TabWallpaperRenderer.invalidateCache()
            // 撤掉已经挂在页面上的背景
            ThemeSurface.removeAllLive()
            WeLogger.i(TAG, "已撤销当前主题并清除缓存，保留独立气泡设置")
        }.onFailure { WeLogger.w(TAG, "清除主题缓存失败：${it.message}") }
    }

    /**
     * 关掉「随机聊天背景」时，立刻把已经挂在聊天页上的壁纸撤掉，
     * 不用等用户退出再进一次聊天界面。
     */
    fun clearWallpaperOnChattingPages() = ThemeSurface.clearChattingWallpapers()

    // ══════════════════════════════════════════════════════════
    // 透明度
    // ══════════════════════════════════════════════════════════

    /** 壁纸透明度（5~70）。 */
    fun overlayOpacity(): Int = overlayOpacityPct.coerceIn(ThemeSurface.MIN_OPACITY, ThemeSurface.MAX_OPACITY)

    fun setOverlayOpacity(pct: Int) {
        overlayOpacityPct = pct.coerceIn(ThemeSurface.MIN_OPACITY, ThemeSurface.MAX_OPACITY)
        ThemeSurface.refreshAlpha()
    }

    fun usePageBackground(): Boolean = pageBackgroundEnabled

    fun setUsePageBackground(enabled: Boolean) {
        if (pageBackgroundEnabled == enabled) return
        pageBackgroundEnabled = enabled
        ThemeSurface.resetFailStreak()
        ThemeSurface.applyToAllLive()
    }

    private val mainWallpaperKeys = listOf("chats", "contacts", "discover", "me")

    fun mainWallpaperEnabled(): Boolean = profileSnapshot()?.profile?.enabled("pages") ?: mainWallpaperOn

    fun setMainWallpaperEnabled(enabled: Boolean) {
        profileSnapshot()?.let { snapshot ->
            val json = snapshot.profile.toJson()
            json.getJSONObject("appearance").getJSONObject("pages").put("enabled", enabled)
            updateThemeProfile(json).getOrThrow()
            return
        }
        mainWallpaperOn = enabled
        invalidateWallpaperCache()
        ThemeSurface.applyToAllLive()
    }

    fun mainTextColorEnabled(): Boolean = profileSnapshot()?.mainTextColorEnabled ?: mainTextColorOn

    fun setMainTextColorEnabled(enabled: Boolean) {
        profileSnapshot()?.let { snapshot ->
            val json = snapshot.profile.toJson()
            val appearance = json.getJSONObject("appearance")
            val compatibility = appearance.optJSONObject("compatibility") ?: org.json.JSONObject()
            appearance.put("compatibility", compatibility.put("mainTextColorEnabled", enabled))
            updateThemeProfile(json).getOrThrow()
            return
        }
        mainTextColorOn = enabled
        ThemeSurface.resetFailStreak()
        ThemeSurface.applyToAllLive()
    }

    /** null 表示按背景自动选择清晰的文字颜色。 */
    fun mainTitleColor(): Int? {
        profileSnapshot()?.let { return (it.profile.color("chrome", "toolbarText") as? ColorRule.Value)?.argb }
        val hex = mainTitleColorHex
        if (!hex.matches(Regex("#[0-9a-fA-F]{6}"))) return null
        return hex.substring(1).toInt(16) or 0xff000000.toInt()
    }

    fun setMainTitleColor(color: Int?) {
        profileSnapshot()?.let { snapshot ->
            val json = snapshot.profile.toJson()
            val appearance = json.getJSONObject("appearance")
            for ((group, keys) in mapOf(
                "chrome" to listOf("toolbarText", "toolbarIcon", "tabText", "tabSelectedText", "tabIcon", "tabSelectedIcon"),
                "lists" to listOf("title")
            )) {
                val values = appearance.optJSONObject(group) ?: org.json.JSONObject().also { appearance.put(group, it) }
                keys.forEach { key ->
                    val rule = org.json.JSONObject().put("mode", if (color == null) "auto" else "value")
                    if (color != null) rule.put("value", String.format("#%08X", color))
                    values.put(key, rule)
                }
            }
            updateThemeProfile(json).getOrThrow()
            return
        }
        mainTitleColorHex = color?.let { String.format("#%06X", it and 0xffffff) } ?: ""
        ThemeSurface.resetFailStreak()
        ThemeSurface.applyToAllLive()
    }

    private fun mainWallpaperOverrideDir(id: String): File {
        require(id.isNotBlank()) { "请先应用一套主题，再设置主页背景" }
        val root = File(ThemePaths.storeDir.toFile(), "overrides").canonicalFile
        val dir = File(root, id).canonicalFile
        require(dir.parentFile == root) { "主题标识无效" }
        return dir
    }

    /** 禁用标记优先于自选图片和主题自带图，避免「恢复」又落回全局图。 */
    internal fun resolveMainWallpapers(activeId: String, defaults: List<File?>): List<File?> {
        if (activeId.isBlank()) return defaults
        profileSnapshot(activeId)?.let { snapshot ->
            return mainWallpaperKeys.map { key ->
                (snapshot.profile.background(key) as? BackgroundRule.Image)?.let { snapshot.asset(it.asset) }
            }
        }
        val dir = mainWallpaperOverrideDir(activeId)
        return mainWallpaperKeys.mapIndexed { index, key ->
            when {
                File(dir, "$key.disabled").exists() -> null
                else -> File(dir, "$key.img").takeIf { it.isFile && it.length() > 0L }
                    ?: defaults.getOrNull(index)
            }
        }
    }

    fun mainWallpaperStatus(index: Int): String {
        require(index in mainWallpaperKeys.indices) { "页面序号无效" }
        val id = activeThemeId
        if (id.isBlank()) return "未应用主题"
        profileSnapshot(id)?.let { snapshot ->
            return when (val rule = snapshot.profile.background(mainWallpaperKeys[index])) {
                is BackgroundRule.Image -> if (rule.asset.startsWith("overrides/") || rule.asset.startsWith("edits/")) "自定义背景" else "当前主题背景"
                BackgroundRule.Transparent -> "透明背景"
                is BackgroundRule.Solid -> "纯色背景"
                else -> "微信原生背景"
            }
        }
        val dir = mainWallpaperOverrideDir(id)
        val key = mainWallpaperKeys[index]
        return when {
            File(dir, "$key.disabled").exists() -> "微信原生背景"
            File(dir, "$key.img").let { it.isFile && it.length() > 0L } -> "自定义背景"
            ThemeMaterials.tabWallpaperFiles(id).getOrNull(index) != null -> "当前主题背景"
            else -> "微信原生背景"
        }
    }

    /** 只覆盖当前主题的主页背景，不改主题选择、气泡或功能总开关。 */
    fun setMainWallpaper(context: Context, uri: Uri, index: Int?): Result<Unit> = runCatching {
        require(index == null || index in mainWallpaperKeys.indices) { "页面序号无效" }
        val snapshot = requireNotNull(profileSnapshot()) { "请先应用一套主题" }
        val dir = mainWallpaperOverrideDir(activeThemeId)
        check(dir.isDirectory || dir.mkdirs()) { "无法创建主页背景目录" }
        val source = File.createTempFile("wallpaper-", ".tmp", dir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                source.outputStream().use { output -> input.copyTo(output) }
            } ?: error("读不到这张图片")
            require(source.length() > 0L) { "这张图片是空的" }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.absolutePath, options)
            require(options.outWidth > 0 && options.outHeight > 0) { "请选择有效的图片" }
            options.inJustDecodeBounds = false
            options.inSampleSize = 1
            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2
            val preview = BitmapFactory.decodeFile(source.absolutePath, options) ?: error("这张图片无法解码")
            preview.recycle()
            val json = snapshot.profile.toJson()
            val pages = json.getJSONObject("appearance").getJSONObject("pages")
            val indices = index?.let { listOf(it) } ?: mainWallpaperKeys.indices.toList()
            val relative = "edits/" + java.util.UUID.randomUUID() + "/wallpaper.img"
            indices.forEach { page -> pages.put(mainWallpaperKeys[page],
                org.json.JSONObject().put("kind", "image").put("asset", relative)) }
            updateThemeProfile(json, mapOf(relative to source)).getOrThrow()
        } finally {
            source.delete()
        }
    }

    fun resetMainWallpapers(indices: Set<Int>): Result<Unit> {
        if (indices.isEmpty()) return Result.success(Unit)
        return runCatching {
            require(indices.all { it in mainWallpaperKeys.indices }) { "页面序号无效" }
            val snapshot = requireNotNull(profileSnapshot()) { "请先应用一套主题" }
            val json = snapshot.profile.toJson()
            val pages = json.getJSONObject("appearance").getJSONObject("pages")
            indices.forEach { index -> pages.put(mainWallpaperKeys[index], org.json.JSONObject().put("kind", "native")) }
            updateThemeProfile(json).getOrThrow()
        }
    }

    // ══════════════════════════════════════════════════════════
    // 渲染方式：四页底图为可选模式，原有 DecorView 前景遮罩用于兼容回退
    // ══════════════════════════════════════════════════════════

    fun renderModeName(): String = if (usePageBackground()) "全屏背景（实验）" else "前景遮罩（兼容）"

    /** 当前挂载和历史写入分开显示，不把上次成功当作当前生效。 */
    fun wallpaperBrief(): String = runCatching {
        val at = TabWallpaperRenderer.lastAt
        val attached = ThemeSurface.liveActivities().any { ThemePages.isLauncher(it) && ThemeSurface.isAttached(it) }
        when {
            attached -> "当前已挂载"
            at == 0L -> "尚未写入"
            else -> "当前未挂载 · 上次写入 ${((System.currentTimeMillis() - at) / 1000)} 秒前"
        }
    }.getOrDefault("未知")

    /** 设置页点「壁纸渲染方式」：立即重写一次前景遮罩。 */
    fun reapplyNow() {
        invalidateWallpaperCache()
        ThemeSurface.applyToAllLive()
        WeLogger.i(TAG, "手动重写主题背景：${TabWallpaperRenderer.lastResult}")
    }

    /** 换主题 / 改素材 / 改透明度后清图缓存，避免拿到旧图。 */
    fun invalidateWallpaperCache() {
        TabWallpaperRenderer.invalidateCache()
        ThemeSurface.resetWallpaperLog()
        ThemeSurface.resetFailStreak()
    }

    // ══════════════════════════════════════════════════════════
    // 供「主题工坊」页面调用的公开入口
    // ══════════════════════════════════════════════════════════

    /** 内置主题（id to 名称）。 */
    fun builtinThemeItems(): List<Pair<String, String>> = BuiltinThemes.all.map { it.id to it.name }

    /** 内置主题的自定义名称（长按重命名后存这里，为空则用内置原名）。 */
    fun builtinAlias(id: String): String = ThemeAliases.name(id)

    fun setBuiltinAlias(id: String, name: String) = ThemeAliases.setName(id, name)

    /** 内置主题的自定义描述（可选）。 */
    fun builtinDesc(id: String): String = ThemeAliases.desc(id)

    fun setBuiltinDesc(id: String, desc: String) = ThemeAliases.setDesc(id, desc)

    /** 内置主题展示名 = 自定义别名优先。 */
    fun builtinDisplayName(id: String, fallback: String): String = ThemeAliases.displayName(id, fallback)

    /** 默认（内置）主题的缩略图文件；首次调用会把素材从 APK 提取到模块数据目录。 */
    fun builtinThumbnail(id: String): File? = ThemeMaterials.thumbnail(id)

    /** 默认主题的主题色，壁纸缺失时用来兜底显示。 */
    fun builtinColor(id: String): Long = BuiltinThemes.previewColor(id)

    fun activeThemeIdOrEmpty(): String = activeThemeId

    // ══════════════════════════════════════════════════════════
    // 气泡：消息视图创建时按主题气泡替换背景（9-patch）
    // ══════════════════════════════════════════════════════════

    override fun onCreateView(
        param: de.robv.android.xposed.XC_MethodHook.MethodHookParam,
        view: View
    ) {
        if (!CustomMessageBubbles.isEnabled) CustomMessageBubbles.onCreateView(param, view)
    }

    // ══════════════════════════════════════════════════════════
    // 诊断
    // ══════════════════════════════════════════════════════════

    /**
     * 主题全链路状态，供「主题诊断」一键复制。
     *
     * 每一行都对应链路上的一环，出现「没图 / 没生效」直接按行定位，不需要再猜。
     */
    fun diagnose(): String = buildString {
        appendLine("== 主题工坊诊断（${BuildConfig.VERSION_NAME}）==")
        appendLine(
            "功能开关 = " + runCatching { WePrefs.getBoolOrFalse(NEW_FEATURE_NAME) }.getOrDefault(false) +
                "（key=$NEW_FEATURE_NAME）"
        )
        appendLine("activeThemeId = '$activeThemeId'")
        appendLine("ThemePack.currentId = '" + runCatching { ThemePack.currentId }.getOrDefault("异常") + "'")
        appendLine(
            "ThemePack.active() = " + runCatching {
                ThemePack.active()?.let { pack -> "${pack.id} / ${pack.name}" } ?: "无"
            }.getOrDefault("异常")
        )
        appendLine("渲染方式 = ${renderModeName()}")
        appendLine("背景图层状态 = ${wallpaperBrief()}")
        appendLine("最近绘制判定 = ${ThemeSurface.lastWallpaperDrawDecision}")
        appendLine(
            "最近一次写入 = tab=${TabWallpaperRenderer.lastTab} 结果=${TabWallpaperRenderer.lastResult}"
        )
        appendLine("  文件=${TabWallpaperRenderer.lastPath}")
        appendLine("壁纸透明度 = ${overlayOpacity()}%")
        appendLine("四页渲染方式 = ${renderModeName()}")
        appendLine("自定义气泡开关 = " + runCatching { WePrefs.getBoolOrFalse(CUSTOM_BUBBLES_PREF_KEY) }.getOrDefault(false))
        appendLine(
            "气泡文件存在 = left:" + ThemePaths.activeBubble(ThemePaths.LEFT_BUBBLE_FILE).exists() +
                " right:" + ThemePaths.activeBubble(ThemePaths.RIGHT_BUBBLE_FILE).exists() +
                " 备份=" + ThemePaths.hasBubbleBackup()
        )
        appendLine("连续挂载失败计数 = ${ThemeSurface.wallpaperFailStreak}")

        val files = runCatching { ThemeMaterials.tabWallpaperFiles(activeThemeId) }.getOrDefault(emptyList())
        appendLine("4 页素材：")
        listOf("主页", "通讯录", "发现", "我").forEachIndexed { index, label ->
            val file = files.getOrNull(index)
            appendLine(
                "  $label = " + if (file == null) {
                    "缺失"
                } else {
                    "${file.absolutePath} (${runCatching { file.length() }.getOrDefault(0L)} B)"
                }
            )
        }

        // 分页是否生效，就看这两行：页码读不到 / 切页 hook 没挂上，都会「4 页全是主页那张」
        appendLine("doOnCreate hook = ${if (doOnCreateHooked) "已注册" else "未注册（功能没开过）"}")
        appendLine(
            "聊天页 hook = ${if (chattingHookOk) "已挂 ChattingUI（进入即撤掉主界面壁纸）"
            else if (chattingPageHooked) "挂载失败（见日志）" else "未注册"}"
        )
        appendLine(
            "聊天页 Fragment 锚点 = " +
                (if (chattingFragmentHookOk) "已 hook $chattingFragmentHookCount 个方法（与实时特征取并集）"
                else "未挂上（仅靠实时 View 特征识别）")
        )
        appendLine(
            "切页 hook = adapter ${hookedTabsAdapters.size} 处" +
                "，onPageSelected=${if (hookSelectedOk) "已挂" else "未挂"}" +
                "，onPageScrolled=${if (hookScrolledOk) "已挂" else "未挂"}" +
                "（只要 onPageSelected 是「已挂」，4 张图就各归各页）"
        )
        runCatching { ThemeSurface.liveActivities() }.getOrDefault(emptyList())
            .firstOrNull { ThemePages.isLauncher(it) }
            ?.let { launcher ->
                appendLine(ThemeHostAdapter.describe(launcher.window?.decorView as? android.view.ViewGroup))
                appendLine(
                    "当前 Tab 实测 = ${ThemeSurface.currentTabIndex(launcher)}" +
                        "（0=主页 1=通讯录 2=发现 3=我；永远是 0 就是页码没读到）"
                )
                appendLine(
                    "页码可读 = " + (if (ThemeSurface.pagerReadable(launcher)) "是（轮询兜底可用）"
                    else "否（只能靠 onPageSelected 回调换图）")
                )
            }

        // 最近一次用图的那张图，同目录下 4 张分页图到底在不在
        runCatching {
            val lastFile = TabWallpaperRenderer.lastPath
            val dir = lastFile?.let { java.io.File(it).parentFile }
            if (dir != null && dir.isDirectory) {
                appendLine(
                    "该目录的 4 张分页图 = " + listOf("chats", "contacts", "discover", "me").joinToString("，") { key ->
                        val f = java.io.File(dir, "wallpaper_$key.png")
                        "$key:" + if (f.exists()) "${f.length()}B" else "无"
                    }
                )
            }
        }

        appendLine("当前前台 Activity = ${runCatching { ThemeSurface.foregroundName() }.getOrDefault("?")}")
        appendLine("聊天页识别 = ${runCatching { ThemeSurface.chatPageStatus() }.getOrDefault("?")}")
        appendLine("最近事件（时间线，从旧到新）：")
        runCatching { ThemeSurface.recentEvents() }.getOrDefault(emptyList()).forEach { appendLine("  $it") }

        val acts = runCatching { ThemeSurface.liveActivities() }.getOrDefault(emptyList())
        appendLine("存活 Activity = " + acts.joinToString("、") { it.javaClass.simpleName })
        acts.forEach { act ->
            appendLine("· ${act.javaClass.simpleName}")
            appendLine(runCatching { ThemeSurface.describe(act) }.getOrDefault("  诊断异常"))
        }
    }
}
