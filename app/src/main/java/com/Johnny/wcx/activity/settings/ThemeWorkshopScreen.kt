package com.Johnny.wcx.activity.settings

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Build
import com.composables.icons.materialsymbols.outlined.Contrast
import com.composables.icons.materialsymbols.outlined.Download
import com.composables.icons.materialsymbols.outlined.Forum
import com.composables.icons.materialsymbols.outlined.Image as ImageIcon
import com.composables.icons.materialsymbols.outlined.Layers
import com.composables.icons.materialsymbols.outlined.Text_fields
import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.items.beautify.BackgroundRule
import com.Johnny.wcx.features.items.beautify.ThemeExtras
import com.Johnny.wcx.features.items.beautify.ThemePack
import com.Johnny.wcx.features.items.beautify.ThemeStore
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.restartHost
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/** 分类主题设置；所有主题编辑统一绑定 selectedId，显式应用才切换主题。 */
@Composable
fun ThemeWorkshopScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var packs by remember { mutableStateOf(ThemePack.list()) }
    var active by remember { mutableStateOf(ThemeStore.activeThemeIdOrEmpty()) }
    // 唯一编辑对象；只有明确应用时才切换活动主题。
    var selectedId by rememberSaveable { mutableStateOf(ThemeStore.activeThemeIdOrEmpty()) }
    var editing by remember { mutableStateOf<ThemePack.Pack?>(ThemePack.find(selectedId)) }
    var pendingRole by rememberSaveable { mutableStateOf("") }
    var pendingThemeId by rememberSaveable { mutableStateOf("") }
    var pendingMainThemeId by rememberSaveable { mutableStateOf("") }
    var pendingTabThemeId by rememberSaveable { mutableStateOf("") }
    var pendingTabIndex by rememberSaveable { mutableStateOf(-1) }
    var pendingTabSelected by rememberSaveable { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ThemePack.Pack?>(null) }
    var renameText by remember { mutableStateOf("") }
    var descTarget by remember { mutableStateOf<ThemePack.Pack?>(null) }
    var descText by remember { mutableStateOf("") }
    var longPressTarget by remember { mutableStateOf<ThemePack.Pack?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmClearCache by remember { mutableStateOf(false) }
    var appliedDialog by remember { mutableStateOf(false) }
    var enableConfirm by remember { mutableStateOf(false) }
    var showRandomPool by remember { mutableStateOf(false) }
    var poolFiles by remember { mutableStateOf(ThemePack.randomPoolFiles()) }

    var tick by remember { mutableStateOf(0) }
    var pendingMainIndex by rememberSaveable { mutableStateOf(-1) }
    var section by rememberSaveable { mutableStateOf("home") }
    var pageIndex by rememberSaveable { mutableStateOf(0) }
    var showRenderMethod by remember { mutableStateOf(false) }

    fun refreshAll() {
        packs = ThemePack.list()
        active = ThemeStore.activeThemeIdOrEmpty()
        poolFiles = ThemePack.randomPoolFiles()
        editing = ThemePack.find(selectedId)
        tick++
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshAll()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    fun loadDraft(pack: ThemePack.Pack?) {
        editing = pack
    }

    /** 选中主题（不应用）。 */
    fun select(id: String) {
        selectedId = id
        val pack = ThemePack.find(id)
        loadDraft(pack)
        showToast(context, "已选中：${pack?.name ?: id}，修改保存为草稿，应用后生效")
        tick++
    }

    /** 真正执行应用（写配置 → 提示划掉微信重开）。 */
    fun doApply(enableFeature: Boolean = false) {
        val id = selectedId
        if (id.isBlank()) {
            showToast(context, "先选一个主题")
            return
        }
        if (ThemeStore.profileSnapshot(id) == null) {
            showToast(context, "应用失败：主题配置无效")
            return
        }
        val pack = ThemePack.find(id)
        if (enableFeature) {
            val feature = findFeature("主题工坊")
            if (feature != null) {
                runCatching { WePrefs.putBool(feature.name, true); feature.isEnabled = true }
                    .onFailure { showToast(context, "打开失败：${it.message}"); return }
            }
        }
        runCatching { ThemeStore.applyThemeById(id) }
            .onFailure {
                WeLogger.w("ThemeWorkshop", "应用主题失败：${it.message}")
                showToast(context, "应用失败：${it.message}")
                return
            }
        if (pack != null) {
            runCatching { ThemeExtras.applySwitches(ThemePack.switches(ThemePack.find(id) ?: pack)) }
                .onFailure { WeLogger.w("ThemeWorkshop", "同步开关失败：${it.message}") }
        }
        refreshAll()
        // 应用后直接热重启微信（用微信自己的 KillProcessHelperActivity，等同用户手动重开）
        // 热重启；失败才走「请手动划掉微信」的兜底提示
        if (!restartHost()) appliedDialog = true
    }

    /**
     * 显式应用所选主题。
     *
     * 主题总开关默认是关闭的（和其它功能一致，需要用户手动开启）。
     * 这里如果发现它还没开，先弹确认，由用户点「打开并应用」才真正打开，
     * 不做静默自动开启。
     */
    fun applySelected() {
        if (selectedId.isBlank()) {
            showToast(context, "先选一个主题")
            return
        }
        val feature = findFeature("主题工坊")
        if (feature != null && !feature.isEnabled) {
            enableConfirm = true
            return
        }
        doApply()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val tmp = File(context.cacheDir, "theme_import.zip")
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取该文件")
            ThemePack.importZip(tmp).getOrThrow()
        }.onSuccess { pack ->
            File(context.cacheDir, "theme_import.zip").delete()
            refreshAll()
            select(pack.id)
            showToast(context, "已导入：${pack.name}")
        }.onFailure {
            WeLogger.w("ThemeWorkshop", "导入失败：${it.message}")
            showToast(context, "导入失败：${it.message}")
        }
    }

    val pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val targetId = pendingThemeId
        val role = pendingRole
        val pack = ThemePack.find(targetId)
        if (pack == null || role.isEmpty()) return@rememberLauncherForActivityResult
        val ninePatch = role.startsWith("bubble_")
        ThemePack.writeImage(pack, context, uri, role, ninePatch)?.let { updated ->
            if (selectedId == updated.id) editing = updated
            refreshAll()
            showToast(context, "已选择：${roleLabel(role)}")
        } ?: showToast(context, "素材保存失败，请检查图片格式和可用空间")
    }

    val pickTabIconLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val targetId = pendingTabThemeId
        val targetIndex = pendingTabIndex
        val targetSelected = pendingTabSelected
        runCatching {
            require(targetIndex in 0..3) { "标签序号无效" }
            val source = File.createTempFile("theme-tab-", ".tmp", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)?.use { input -> source.outputStream().use { input.copyTo(it) } }
                    ?: error("读不到这张图片")
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(source.absolutePath, options)
                require(options.outWidth > 0 && options.outHeight > 0) { "请选择有效的图片" }
                options.inJustDecodeBounds = false
                options.inSampleSize = 1
                while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2
                val preview = BitmapFactory.decodeFile(source.absolutePath, options) ?: error("这张图片无法解码")
                preview.recycle()
                saveThemeTabIcon(targetId, targetIndex, targetSelected, source).getOrThrow()
            } finally { source.delete() }
        }.onSuccess { refreshAll(); showToast(context, "图标已保存") }
            .onFailure { showToast(context, "保存失败：${it.message}") }
    }

    val pickPoolImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        ThemePack.addRandomPoolImage(context, uri)
            .onSuccess {
                refreshAll()
                showToast(context, "已添加（共 ${ThemePack.randomPoolFiles().size} 张）")
            }
            .onFailure { showToast(context, "添加失败：${it.message}") }
    }

    val pickMainImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val targetId = pendingMainThemeId
        val targetIndex = pendingMainIndex
        val result = runCatching {
            val source = File.createTempFile("theme-picker-", ".tmp", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)?.use { input -> source.outputStream().use { input.copyTo(it) } }
                    ?: error("读不到这张图片")
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(source.absolutePath, options)
                require(options.outWidth > 0 && options.outHeight > 0) { "请选择有效的图片" }
                options.inJustDecodeBounds = false
                options.inSampleSize = 1
                while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2
                val preview = BitmapFactory.decodeFile(source.absolutePath, options) ?: error("这张图片无法解码")
                preview.recycle()
                saveThemePageImage(targetId, targetIndex.takeUnless { it == -1 }, source).getOrThrow()
            } finally { source.delete() }
        }
        result
            .onSuccess {
                refreshAll()
                showToast(context, if (targetId == active) "主界面背景已更新，即时生效" else "原主题的背景草稿已更新")
            }
            .onFailure { showToast(context, "设置失败：${it.message}") }
    }

    fun pickMainImage(index: Int?) {
        if (ThemePack.find(selectedId) == null) {
            section = "themes"
            showToast(context, "请先新建或导入主题")
            return
        }
        pendingMainIndex = index ?: -1
        pendingMainThemeId = selectedId
        pickMainImageLauncher.launch("image/*")
    }

    fun resetMainImages() {
        val id = selectedId
        runCatching {
            val json = requireNotNull(ThemeStore.profileSnapshot(id)) { "请先新建或导入主题" }.profile.toJson()
            val pages = json.getJSONObject("appearance").getJSONObject("pages")
            listOf("chats", "contacts", "discover", "me").forEach {
                pages.put(it, org.json.JSONObject().put("kind", "native"))
            }
            ThemeStore.updateThemeProfile(json, id = id).getOrThrow()
        }.onSuccess { refreshAll(); showToast(context, "已恢复四页原生背景") }
            .onFailure { showToast(context, "恢复失败：${it.message}") }
    }

    fun goBack() {
        when (section) {
            "page" -> section = "backgrounds"
            "home" -> onBack()
            else -> section = "home"
        }
    }
    BackHandler(enabled = section != "home") { goBack() }
    val titles = mapOf("home" to "主题设置", "backgrounds" to "四页背景", "page" to "单页背景",
        "text" to "文字与图标", "chat" to "聊天与气泡", "mode" to "深浅模式",
        "owned" to "抽屉与悬浮按钮", "themes" to "我的主题", "diagnostics" to "诊断与恢复")
    val pageNames = listOf("微信", "通讯录", "发现", "我")
    val pageKeys = listOf("chats", "contacts", "discover", "me")
    val snapshot = remember(selectedId, tick) { ThemeStore.profileSnapshot(selectedId) }
    val profile = snapshot?.profile
    val savedPages = remember(selectedId, tick) { profile?.let { themeSettingsProfile(it, "pages") } }
    val screenTitle = if (section == "page") "${pageNames[pageIndex]}背景" else titles[section] ?: "主题设置"
    val listState = rememberLazyListState()
    LaunchedEffect(section, selectedId) { listState.scrollToItem(0) }

    ThemePageScaffold(screenTitle, onBack = { goBack() }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item {
                Text("正在编辑：${editing?.name ?: "未选择主题"} · ${if (selectedId.isNotBlank() && selectedId == active) "使用中" else "未应用"}",
                    fontSize = 13.sp, color = summaryColor(), modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
            }
            if (profile == null && section !in listOf("home", "themes", "diagnostics")) {
                item { GroupCard(null) {
                    HintRow("请先新建或导入主题，即可编辑草稿。")
                    NavRow("新建或导入主题", "前往我的主题") { section = "themes" }
                } }
            }
            if (profile != null || section in listOf("home", "themes", "diagnostics", "mode", "chat")) when (section) {
                "home" -> {
                    item { GroupCard(null) {
                        FeatureSwitchRow("主题工坊", "启用主题", "", tick) { refreshAll() }
                    } }
                    item { GroupCard(null) {
                        if (editing != null) {
                            ThemeIdentityRow(editing!!, tick, selectedId == active, onClick = { showPicker = true })
                            RowDivider()
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { showPicker = true }, modifier = Modifier.weight(1f)) { Text("切换主题", color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
                                Box(Modifier.width(1.dp).height(20.dp).background(summaryColor().copy(alpha = 0.25f)))
                                TextButton(onClick = { section = "themes" }, modifier = Modifier.weight(1f)) { Text("管理主题", color = MiuixTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold) }
                            }
                        } else {
                            HintRow("还没有选中主题。新建或导入后即可开始编辑。")
                            NavRow("新建或导入主题", "我的主题") { section = "themes" }
                        }
                        if (editing != null && selectedId != active) {
                            HintRow("此主题未应用，修改只保存草稿。")
                            PillButton("应用这套主题", Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), primary = true) { applySelected() }
                        }
                    } }
                    item { GroupCard(null) {
                        listOf(
                            Triple("backgrounds", "四页背景", MaterialSymbols.Outlined.ImageIcon),
                            Triple("text", "文字与图标", MaterialSymbols.Outlined.Text_fields),
                            Triple("chat", "聊天与气泡", MaterialSymbols.Outlined.Forum),
                            Triple("mode", "深浅模式", MaterialSymbols.Outlined.Contrast),
                            Triple("owned", "抽屉与悬浮按钮", MaterialSymbols.Outlined.Layers),
                        ).forEachIndexed { index, (route, title, icon) ->
                            if (index > 0) RowDivider()
                            NavRow(title, "", icon = icon) { section = route }
                        }
                    } }
                    item { GroupCard(null) {
                        NavRow("诊断与恢复", "", icon = MaterialSymbols.Outlined.Build, primary = true) { section = "diagnostics" }
                    } }
                }
                "backgrounds" -> {
                    item { ThemePreview(selectedId, tick) }
                    item { GroupCard(null) {
                        SwitchRow("启用四页背景", "", profile!!.enabled("pages")) {
                            updateThemeSetting(selectedId, "pages", "enabled", it).onSuccess { refreshAll() }
                                .onFailure { error -> showToast(context, "保存失败：${error.message}") }
                        }
                    } }
                    item { PillButton("一张图片应用四页", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), primary = true) { pickMainImage(null) } }
                    item { GroupCard(null) {
                        pageNames.forEachIndexed { index, title ->
                            if (index > 0) RowDivider()
                            val rule = savedPages!!.background(pageKeys[index])
                            val file = (rule as? BackgroundRule.Image)?.let { snapshot?.asset(it.asset) }
                            val status = (if (profile!!.enabled("pages")) "" else "已停用，设置保留 · ") + backgroundRuleLabel(rule)
                            ThemePageRow(title, status, file, tick) { pageIndex = index; section = "page" }
                        }
                    } }
                }
                "page" -> {
                    item { GroupCard(null) {
                        ThemeBackgroundSetting(selectedId, tick, "背景图片与模式", "pages", pageKeys[pageIndex],
                            onPick = { pickMainImage(pageIndex) }, onChanged = { refreshAll() })
                        HintRow("选择图片、纯色、透明、微信原生或主题默认；图片不透明度仅作用于此页。")
                    } }
                }
                "text" -> {
                    item { GroupCard(null) {
                        SwitchRow("启用颜色调整", "关闭后恢复原生文字与图标颜色", snapshot!!.mainTextColorEnabled) {
                            updateThemeSetting(selectedId, "compatibility", "mainTextColorEnabled", it).onSuccess { refreshAll() }
                                .onFailure { error -> showToast(context, "保存失败：${error.message}") }
                        }
                    } }
                    item { GroupCard(null) {
                        ThemeColorSetting(selectedId, tick, "默认文字与图标颜色", "palette", "normal", defaultOnly = true) { refreshAll() }
                        HintRow("默认颜色不会覆盖下方各角色的独立配色。")
                    } }
                    item {
                        ThemeProfileSettings(selectedId, tick, onPickTab = { index, selected ->
                            pendingTabThemeId = selectedId; pendingTabIndex = index; pendingTabSelected = selected
                            pickTabIconLauncher.launch("image/*")
                        }) { refreshAll() }
                    }
                }
                "chat" -> {
                    if (profile != null) {
                        item { GroupCard(null) {
                            SwitchRow("启用聊天主题", "关闭时沿用独立气泡设置", profile.enabled("chat")) {
                                updateThemeSetting(selectedId, "chat", "enabled", it).onSuccess { refreshAll() }
                                    .onFailure { error -> showToast(context, "保存失败：${error.message}") }
                            }
                        } }
                        item { ThemeChatPreview(selectedId, tick) }
                        item { GroupCard(null) {
                            listOf("左侧气泡（对方）" to "bubble_left", "右侧气泡（自己）" to "bubble_right").forEach { (title, role) ->
                                if (role == "bubble_right") RowDivider()
                                ThemeBackgroundSetting(selectedId, tick, title, "chat", if (role == "bubble_left") "leftBubble" else "rightBubble",
                                    onPick = { pendingRole = role; pendingThemeId = selectedId; pickImageLauncher.launch("*/*") },
                                    onChanged = { refreshAll() })
                            }
                            RowDivider()
                            ThemeColorSetting(selectedId, tick, "左侧正文颜色", "chat", "leftText") { refreshAll() }
                            RowDivider()
                            ThemeColorSetting(selectedId, tick, "右侧正文颜色", "chat", "rightText") { refreshAll() }
                        } }
                        item { GroupCard(null) {
                            HintRow("聊天主题关闭时沿用独立气泡；明确选择微信原生时使用微信气泡。")
                            NavRow("恢复微信原生气泡", "仅修改正在编辑的主题") {
                                val id = selectedId
                                runCatching {
                                    val json = requireNotNull(ThemeStore.profileSnapshot(id)).profile.toJson()
                                    resetBubbleRule(json, "bubble_left"); resetBubbleRule(json, "bubble_right")
                                    ThemeStore.updateThemeProfile(json, id = id).getOrThrow()
                                }.onSuccess { refreshAll() }.onFailure { showToast(context, "恢复失败：${it.message}") }
                            }
                        } }
                    }
                    item { GroupCard("本设备 · 随机聊天背景") {
                        SwitchRow("随机聊天背景", "每次进入聊天从图池随机选取", WePrefs.getBoolOrFalse(ThemeExtras.KEY_RANDOM_WALLPAPER)) {
                            WePrefs.putBool(ThemeExtras.KEY_RANDOM_WALLPAPER, it)
                            ThemeExtras.onPrefChanged(ThemeExtras.KEY_RANDOM_WALLPAPER, it)
                            refreshAll()
                        }
                        NavRow("随机背景图池", "已选 ${poolFiles.size}/${ThemePack.RANDOM_POOL_MAX} 张") { showRandomPool = true }
                    } }
                }
                "mode" -> item { GroupCard(null) { ThemeModeSettings(selectedId, tick) { refreshAll() } } }
                "owned" -> item { GroupCard(null) { ThemeOwnedSettings(selectedId, tick) { refreshAll() } } }
                "themes" -> {
                    item { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("新建", Modifier.weight(1f), primary = true, outlined = true, icon = MaterialSymbols.Outlined.Add) { createName = "我的主题"; showCreate = true }
                        PillButton("导入", Modifier.weight(1f), primary = true, outlined = true, icon = MaterialSymbols.Outlined.Download) { importLauncher.launch("*/*") }
                    } }
                    if (packs.isEmpty()) item { GroupCard(null) { HintRow("还没有主题，请新建或导入。") } }
                    items(packs.size, key = { packs[it].id }) { index ->
                        val pack = packs[index]
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                            .padding(top = if (index == 0) 6.dp else 0.dp, bottom = if (index == packs.lastIndex) 6.dp else 0.dp)
                            .clip(RoundedCornerShape(topStart = if (index == 0) 16.dp else 0.dp, topEnd = if (index == 0) 16.dp else 0.dp,
                                bottomStart = if (index == packs.lastIndex) 16.dp else 0.dp, bottomEnd = if (index == packs.lastIndex) 16.dp else 0.dp))
                            .background(themeCardColor())) {
                            if (index > 0) RowDivider()
                            ThemeIdentityRow(pack, tick, active == pack.id,
                                selected = selectedId == pack.id, onClick = { select(pack.id) },
                                onMenu = { longPressTarget = pack })
                        }
                    }
                    if (editing != null) {
                    item { GroupCard(null) {
                        HintRow(if (selectedId == active) "编辑使用中的主题即时刷新。" else "编辑未应用主题只保存草稿；应用后切换当前主题。")
                    } }
                    item { GroupCard(null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                            TextButton(onClick = {
                                ThemeStore.cloneTheme(selectedId).onSuccess { refreshAll(); select(it.id) }
                                    .onFailure { showToast(context, "复制失败：${it.message}") }
                            }, modifier = Modifier.weight(1f)) { Text("复制", color = MiuixTheme.colorScheme.primary) }
                            TextButton(onClick = {
                                ThemeStore.exportTheme(selectedId).onSuccess { showToast(context, "已导出：${it.absolutePath}") }
                                    .onFailure { showToast(context, "导出失败：${it.message}") }
                            }, modifier = Modifier.weight(1f)) { Text("导出", color = MiuixTheme.colorScheme.primary) }
                            TextButton(onClick = { renameText = editing!!.name; renameTarget = editing }, modifier = Modifier.weight(1f)) {
                                Text("重命名", color = MiuixTheme.colorScheme.primary)
                            }
                            TextButton(onClick = { longPressTarget = editing }, modifier = Modifier.weight(1f)) {
                                Text("删除", color = MiuixTheme.colorScheme.primary)
                            }
                        }
                    } }
                    item { GroupCard("更多操作") {
                        NavRow("编辑所选主题", editing!!.name) { section = "home" }
                        RowDivider()
                        NavRow(if (selectedId == active) "重新应用所选主题" else "应用所选主题", "应用后自动重启微信") { applySelected() }
                        RowDivider()
                        NavRow("修改描述", editing!!.manifest.optString("description").ifBlank { "未填写" }) {
                            descText = editing!!.manifest.optString("description"); descTarget = editing
                        }
                        RowDivider()
                        NavRow("分享主题", "") { shareSelectedTheme(context, selectedId) }
                        RowDivider()
                        NavRow("备份主题", "") {
                            editing?.let { ThemePack.backup(it).onSuccess { file -> showToast(context, "已备份：${file.absolutePath}") }
                                .onFailure { error -> showToast(context, "备份失败：${error.message}") } }
                        }
                    } }
                    }
                }
                "diagnostics" -> {
                    item { GroupCard(null) {
                        NavRow("主题诊断", "查看并复制当前运行诊断") {
                            val info = runCatching { ThemeStore.diagnose() }.getOrElse { "诊断失败：${it.message}" }
                            android.app.AlertDialog.Builder(context).setTitle("主题诊断").setMessage(info)
                                .setPositiveButton("知道了", null).setNeutralButton("复制") { _, _ ->
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("主题诊断", info))
                                    showToast(context, "已复制诊断")
                                }.show()
                        }
                        NavRow("重新应用使用中的主题", active.takeIf { it.isNotBlank() }?.let { themeName(it) } ?: "未应用主题") {
                            if (active.isBlank()) showToast(context, "还没有使用中的主题") else {
                                runCatching { ThemeStore.applyThemeById(active) }.onSuccess {
                                    refreshAll(); if (!restartHost()) appliedDialog = true
                                }.onFailure { showToast(context, "应用失败：${it.message}") }
                            }
                        }
                        NavRow("恢复微信原生外观", "撤销使用中的主题；保留主题包") { confirmReset = true }
                        NavRow("清除主题缓存", "撤销使用中的主题并清理缓存") { confirmClearCache = true }
                        NavRow("使用说明", "") { showGuide = true }
                    } }
                }
            }
            if (section == "backgrounds") {
                item { GroupCard("本设备 · 背景显示") {
                    NavRow("绘制方式", if (ThemeStore.usePageBackground()) "内容底图" else "兼容前景") { showRenderMethod = true }
                    RowDivider()
                    OpacityRow(tick)
                } }
                if (profile != null) item {
                    HintRow("更换图片、背景模式与不透明度在单页中设置。")
                    GroupCard(null) { NavRow("恢复四页原生背景", "仅修改正在编辑的主题", primary = true) { resetMainImages() } }
                }
            }
        }
    }

    // ── 弹窗区 ──────────────────────────────────────────────

    if (showRenderMethod) AlertDialog(onDismissRequest = { showRenderMethod = false },
        title = { Text("背景绘制方式 · 本设备") }, text = { Column {
            listOf(true to "内容底图", false to "兼容前景").forEach { (value, label) ->
                TextButton(onClick = { ThemeStore.setUsePageBackground(value); refreshAll(); showRenderMethod = false }) {
                    Text((if (ThemeStore.usePageBackground() == value) "✓ " else "") + label)
                }
            }
        } }, confirmButton = { TextButton(onClick = { showRenderMethod = false }) { Text("取消") } })

    if (enableConfirm) {
        AlertDialog(
            onDismissRequest = { enableConfirm = false },
            title = { Text("主题总开关未开启") },
            text = { Text("主题功能默认关闭。现在打开「主题」总开关并应用这套主题吗？") },
            confirmButton = {
                TextButton(onClick = {
                    enableConfirm = false
                    doApply(enableFeature = true)
                }) { Text("打开并应用") }
            },
            dismissButton = { TextButton(onClick = { enableConfirm = false }) { Text("取消") } },
        )
    }

    if (appliedDialog) {
        AlertDialog(
            onDismissRequest = { appliedDialog = false },
            title = { Text("已应用") },
            text = {
                Text("自动重启没有成功。请从最近任务把微信划掉，再重新打开，主题才会完全生效。")
            },
            confirmButton = { TextButton(onClick = { appliedDialog = false }) { Text("知道了") } },
        )
    }

    if (showRandomPool) {
        AlertDialog(
            onDismissRequest = { showRandomPool = false },
            title = { Text("随机背景图（${poolFiles.size}/${ThemePack.RANDOM_POOL_MAX}）") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (poolFiles.isEmpty()) {
                        Text(
                            text = "还没有图片。点下面「添加图片」选一张（最多 5 张，每次进聊天页随机取一张）。",
                            fontSize = 12.sp,
                            color = summaryColor(),
                        )
                    } else {
                        poolFiles.chunked(3).forEach { rowFiles ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                rowFiles.forEach { file ->
                                    PoolThumb(file) {
                                        ThemePack.removeRandomPoolImage(file)
                                        refreshAll()
                                    }
                                }
                                repeat(3 - rowFiles.size) { Spacer(Modifier.size(72.dp)) }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    PillButton(
                        text = if (poolFiles.size >= ThemePack.RANDOM_POOL_MAX) "已满 5 张" else "＋ 添加图片",
                        modifier = Modifier.fillMaxWidth(),
                        primary = true,
                    ) {
                        if (poolFiles.size >= ThemePack.RANDOM_POOL_MAX) {
                            showToast(context, "最多 ${ThemePack.RANDOM_POOL_MAX} 张，先删一张")
                        } else {
                            pickPoolImageLauncher.launch("image/*")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRandomPool = false }) { Text("关闭") } },
        )
    }

    if (showGuide) {
        AlertDialog(
            onDismissRequest = { showGuide = false },
            title = { Text("使用说明") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    GuideLine("1", "一套主题", "4 张界面背景（主页/通讯录/发现/我）+ 左右两个点九气泡。")
                    GuideLine("2", "必须点「应用」", "新建或导入主题后，选中并点「应用所选主题」切换到该主题。")
                    GuideLine("3", "应用后会自动重启", "点「应用」后会自动热重启微信，重开就能看到效果；重启失败时才需要手动划掉微信重开。")
                    GuideLine("4", "主界面即时设置", "所有设置都作用于正在编辑的主题；使用中的主题即时刷新，未应用主题只保存草稿。")
                    GuideLine("5", "自己做/分享", "新建或导入 → 编辑主题 → 应用所选主题；我的主题页可复制、导出、备份与分享。")
                    GuideLine("6", "聊天页规则", "四页背景与聊天外观分别配置；聊天外观组关闭时沿用独立气泡设置，明确选择原生气泡时使用微信样式。")
                    GuideLine("7", "透明度为什么封顶 70%", "背景越不透明越容易盖住文字、时间和分割线。70% 是「背景够明显、文字还看得清」的平衡点，默认 40%。")
                    GuideLine("8", "旧气泡没换掉怎么办", "诊断与恢复 →「清除主题缓存」，一键清掉已应用的气泡与背景，回到微信原生。")
                }
            },
            confirmButton = { TextButton(onClick = { showGuide = false }) { Text("知道了") } },
        )
    }

    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新建主题") },
            text = {
                OutlinedTextField(
                    value = createName,
                    onValueChange = { createName = it },
                    label = { Text("主题名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showCreate = false
                    ThemePack.createEmptyPack(createName.ifBlank { "我的主题" })
                        .onSuccess { pack ->
                            refreshAll()
                            select(pack.id)
                            showToast(context, "已创建：${pack.name}，选好素材后点「应用所选主题」")
                        }
                        .onFailure { showToast(context, "创建失败：${it.message}") }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } },
        )
    }

    // 主题下拉：列出自己新建或导入的主题。
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("选择我的主题") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (packs.isEmpty()) {
                        Text(
                            text = "还没有自己新建的主题。在「我的主题」中新建或导入。",
                            fontSize = 12.sp,
                            color = summaryColor(),
                        )
                    }
                    packs.forEach { pack ->
                        PickerRow(pack.name, selectedId == pack.id, active == pack.id) {
                            showPicker = false
                            select(pack.id)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("关闭") } },
        )
    }

    if (confirmClearCache) {
        AlertDialog(
            onDismissRequest = { confirmClearCache = false },
            title = { Text("清除主题缓存") },
            text = {
                Text(
                    "会撤销当前主题外观，清理解码和素材缓存；保留主题包、配置及独立气泡素材。\n" +
                        "不会删除你的主题包，也不会动备份。\n\n" +
                        "用于「换了主题但旧气泡还在」或「没在用主题却还有残留气泡」的情况。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearCache = false
                    runCatching { ThemeStore.clearThemeCache() }
                        .onSuccess {
                            refreshAll()
                            showToast(context, "已撤销主题并清理缓存，保留独立气泡设置")
                        }
                        .onFailure {
                            WeLogger.w("ThemeWorkshop", "清除缓存失败：${it.message}")
                            showToast(context, "清除失败：${it.message}")
                        }
                }) { Text("清除") }
            },
            dismissButton = { TextButton(onClick = { confirmClearCache = false }) { Text("取消") } },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("恢复默认") },
            text = { Text("撤销当前主题的背景、颜色与气泡覆盖；保留主题配置和独立功能设置。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    runCatching { ThemeStore.restoreDefaultTheme() }
                        .onSuccess {
                            selectedId = ""
                            loadDraft(null)
                            refreshAll()
                            showToast(context, "已撤销当前主题，保留独立功能设置")
                        }
                        .onFailure {
                            WeLogger.w("ThemeWorkshop", "恢复默认失败：${it.message}")
                            showToast(context, "恢复失败：${it.message}")
                        }
                }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
        )
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("主题名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val updated = ThemePack.find(target.id)?.let { ThemePack.updateMeta(it, renameText, null) }
                    if (updated == null) showToast(context, "重命名失败，请检查主题是否仍可用") else {
                        renameTarget = null
                        refreshAll()
                        showToast(context, "已重命名")
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    descTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { descTarget = null },
            title = { Text("修改描述") },
            text = {
                OutlinedTextField(
                    value = descText,
                    onValueChange = { descText = it },
                    label = { Text("描述（可留空）") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val updated = ThemePack.find(target.id)?.let { ThemePack.updateMeta(it, null, descText) }
                    if (updated == null) showToast(context, "修改描述失败，请检查主题是否仍可用") else {
                        descTarget = null
                        refreshAll()
                        showToast(context, "已修改描述")
                    }
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { descTarget = null }) { Text("取消") } },
        )
    }

    longPressTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { longPressTarget = null },
            title = { Text(target.name) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    PickerRow("重命名", false, selectedId == target.id) {
                        renameText = target.name
                        renameTarget = target
                        longPressTarget = null
                    }
                    PickerRow("修改描述", false, selectedId == target.id) {
                        descText = target.manifest.optString("description").orEmpty()
                        descTarget = target
                        longPressTarget = null
                    }
                    PickerRow("分享主题", false, selectedId == target.id) {
                        longPressTarget = null
                        shareSelectedTheme(context, target.id)
                    }
                    PickerRow("删除主题", false, selectedId == target.id) {
                        ThemeStore.deleteTheme(target.id).onSuccess {
                            if (editing?.id == target.id) loadDraft(null)
                            if (selectedId == target.id) selectedId = ""
                            longPressTarget = null
                            refreshAll()
                            showToast(context, "已删除：${target.name}")
                        }.onFailure { showToast(context, "删除失败：${it.message}") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { longPressTarget = null }) { Text("关闭") } },
        )
    }
}

// ══════════════════════════════════════════════════════════
// 通用组件
// ══════════════════════════════════════════════════════════

@Composable
private fun summaryColor(): Color = BasicComponentDefaults.summaryColor().color

@Composable
private fun titleColor(): Color = BasicComponentDefaults.titleColor().color

@Composable
private fun ThemePageScaffold(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    val dark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    Column(Modifier.fillMaxSize().background(if (dark) Color(0xff101014) else Color(0xfff3f4f6))
        .statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(MaterialSymbols.Outlined.Arrow_back, contentDescription = "返回", tint = titleColor(), modifier = Modifier.size(24.dp))
            }
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = titleColor())
        }
        Box(Modifier.weight(1f)) { content(PaddingValues(0.dp)) }
    }
}

@Composable
private fun themeCardColor(): Color = if (MiuixTheme.colorScheme.background.luminance() < 0.5f) Color(0xff1c1c1e) else Color.White

@Composable
internal fun GroupCard(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = summaryColor(),
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        val shape = RoundedCornerShape(16.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(themeCardColor()),
            content = content,
        )
    }
}

@Composable
internal fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(0.6.dp)
            .background(titleColor().copy(alpha = 0.08f)),
    )
}

@Composable
private fun FeatureSwitchRow(
    featureName: String,
    title: String,
    summary: String,
    tick: Int,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    val feature = remember(featureName) { findFeature(featureName) }
    if (feature == null) {
        HintRow("$title：当前版本未提供该功能")
        return
    }
    var checked by remember(feature, tick) { mutableStateOf(feature.isEnabled) }
    val reason = feature.unavailableReason
    SwitchRow(title = title, summary = reason ?: summary, checked = checked) { on ->
        if (reason != null) {
            showToast(context, reason)
            return@SwitchRow
        }
        runCatching {
            WePrefs.putBool(feature.name, on)
            feature.isEnabled = on
        }.onFailure { WeLogger.w("ThemeWorkshop", "切换 $title 失败：${it.message}") }
        checked = on
        onChanged()
    }
}

@Composable
internal fun SwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = titleColor())
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(summary, fontSize = 12.sp, color = summaryColor())
            }
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun OpacityRow(tick: Int) {
    var pct by remember(tick) { mutableStateOf(ThemeStore.overlayOpacity()) }
    val clearBackground = remember(tick) { ThemeStore.usePageBackground() }
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (clearBackground) "兼容/聊天壁纸透明度" else "壁纸透明度", fontSize = 15.sp,
                color = titleColor(), modifier = Modifier.weight(1f))
            Text("$pct%", fontSize = 13.sp, color = summaryColor())
        }
        Text(
            text = if (clearBackground) "四页图片使用各页不透明度；此项仅控制随机聊天背景和兼容遮罩。兼容模式会再乘以各页图片不透明度。" else
                "默认 40%，最高 70%。再往上背景会压住文字、时间、分割线，" +
                    "聊天列表和消息就看不清了，所以封在 70%。",
            fontSize = 11.sp,
            color = summaryColor(),
        )
        Slider(
            value = pct.toFloat(),
            onValueChange = { pct = it.toInt().coerceIn(5, 70) },
            onValueChangeFinished = { ThemeStore.setOverlayOpacity(pct) },
            valueRange = 5f..70f,
            colors = SliderDefaults.colors(
                thumbColor = MiuixTheme.colorScheme.primary,
                activeTrackColor = MiuixTheme.colorScheme.primary,
            ),
        )
    }
}

private fun rgbHex(color: Int): String = "#%06X".format(color and 0xFFFFFF)

@Composable
internal fun RgbColorDialog(initialColor: Int?, onDismiss: () -> Unit, onApply: (Int) -> Unit) {
    var color by remember { mutableStateOf(initialColor ?: android.graphics.Color.BLACK) }
    var hex by remember { mutableStateOf(rgbHex(color)) }
    val digits = hex.trim().removePrefix("#")
    val valid = digits.length == 6 && digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择颜色") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                            .background(Color(color)).border(1.dp, summaryColor(), RoundedCornerShape(10.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("颜色预览 · ${rgbHex(color)}", fontSize = 14.sp, color = titleColor())
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = hex,
                    onValueChange = { value ->
                        hex = value
                        val valueDigits = value.trim().removePrefix("#")
                        if (valueDigits.length == 6) {
                            valueDigits.toIntOrNull(16)?.let { color = it or 0xFF000000.toInt() }
                        }
                    },
                    label = { Text("RGB 颜色（#RRGGBB）") },
                    isError = !valid,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!valid) Text("请输入六位十六进制颜色，如 #336699", fontSize = 12.sp, color = summaryColor())
                listOf("R" to 16, "G" to 8, "B" to 0).forEach { (label, shift) ->
                    val channel = (color ushr shift) and 0xFF
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("$label  $channel", fontSize = 13.sp, color = titleColor(), modifier = Modifier.width(56.dp))
                        Slider(
                            value = channel.toFloat(),
                            onValueChange = { value ->
                                color = (color and (0xFF shl shift).inv()) or (value.toInt().coerceIn(0, 255) shl shift)
                                hex = rgbHex(color)
                            },
                            valueRange = 0f..255f,
                            modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(
                                thumbColor = MiuixTheme.colorScheme.primary,
                                activeTrackColor = MiuixTheme.colorScheme.primary,
                            ),
                        )
                    }
                }
                Text("当前主题即时生效；其他主题保存为草稿。", fontSize = 12.sp, color = summaryColor())
            }
        },
        confirmButton = { TextButton(onClick = { onApply(color) }, enabled = valid) { Text("应用") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun NavRow(title: String, summary: String, icon: ImageVector? = null, primary: Boolean = false, onClick: () -> Unit) {
    val foreground = if (primary) MiuixTheme.colorScheme.primary else titleColor()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = foreground, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = foreground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(summary, fontSize = 12.sp, color = summaryColor())
            }
        }
        Text("›", fontSize = 20.sp, color = summaryColor())
    }
}

@Composable
private fun HintRow(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = summaryColor(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

@Composable
private fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    outlined: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val bg = if (outlined) themeCardColor() else if (primary) MiuixTheme.colorScheme.primary
    else titleColor().copy(alpha = 0.07f)
    val fg = if (outlined) MiuixTheme.colorScheme.primary else if (primary) MiuixTheme.colorScheme.onPrimary else titleColor()
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = fg)
        }
    }
}

@Composable
private fun ThemeThumbnail(file: File?, revision: Int, modifier: Modifier, maxDimension: Int = 256) {
    val bitmap = remember(file?.absolutePath, file?.lastModified(), revision, maxDimension) {
        file?.let { runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(it.absolutePath, bounds)
            bounds.inJustDecodeBounds = false
            bounds.inSampleSize = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / bounds.inSampleSize > maxDimension) bounds.inSampleSize *= 2
            BitmapFactory.decodeFile(it.absolutePath, bounds)
        }.getOrNull() }
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(titleColor().copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text("无图片", fontSize = 11.sp, color = summaryColor())
    }
}

@Composable
private fun ThemePreview(id: String, revision: Int) {
    val snapshot = remember(id, revision) { ThemeStore.profileSnapshot(id) }
    val rule = remember(id, revision) { snapshot?.profile?.let { themeSettingsProfile(it, "pages").background("chats") } }
    val file = (rule as? BackgroundRule.Image)?.let { snapshot?.asset(it.asset) }
    ThemeThumbnail(file, revision, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(110.dp), maxDimension = 720)
}

@Composable
private fun ThemeIdentityRow(pack: ThemePack.Pack, revision: Int, active: Boolean, selected: Boolean = false,
    onClick: () -> Unit, onMenu: (() -> Unit)? = null) {
    val snapshot = remember(pack.id, revision) { ThemeStore.profileSnapshot(pack.id) }
    val rule = remember(pack.id, revision) { snapshot?.profile?.let { themeSettingsProfile(it, "pages").background("chats") } }
    val file = (rule as? BackgroundRule.Image)?.let { snapshot?.asset(it.asset) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        ThemeThumbnail(file, revision, Modifier.size(64.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(pack.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = titleColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            val badgeColor = if (active) MiuixTheme.colorScheme.primary else summaryColor()
            Text(if (active) "使用中" else "未应用", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = badgeColor,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(badgeColor.copy(alpha = 0.1f)).padding(horizontal = 9.dp, vertical = 3.dp))
            if (selected && !active) Text("正在编辑", fontSize = 11.sp, color = MiuixTheme.colorScheme.primary)
        }
        onMenu?.let { menu -> TextButton(onClick = menu) { Text("⋮", fontSize = 22.sp) } }
    }
}

@Composable
private fun ThemePageRow(title: String, status: String, file: File?, revision: Int, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        ThemeThumbnail(file, revision, Modifier.width(52.dp).height(40.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = titleColor())
            Text(status, fontSize = 12.sp, color = summaryColor())
        }
        Text("›", fontSize = 20.sp, color = summaryColor())
    }
}

@Composable
private fun PoolThumb(file: File, onRemove: () -> Unit) {
    Box(modifier = Modifier.size(72.dp)) {
        val bitmap = remember(file.absolutePath, file.lastModified()) {
            runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .background(titleColor().copy(alpha = 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            text = "✕",
            fontSize = 12.sp,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onRemove)
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun PickerRow(name: String, selected: Boolean, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            fontSize = 15.sp,
            color = titleColor(),
            modifier = Modifier.weight(1f),
        )
        val badge = when {
            selected && active -> "使用中"
            selected -> "已选"
            active -> "使用中"
            else -> ""
        }
        if (badge.isNotEmpty()) {
            Text(badge, fontSize = 11.sp, color = MiuixTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun GuideLine(index: String, title: String, detail: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("$index. $title", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = titleColor())
        Text(detail, fontSize = 12.sp, color = summaryColor())
    }
}

// ══════════════════════════════════════════════════════════
// 小工具
// ══════════════════════════════════════════════════════════

private fun themeName(id: String): String {
    if (id.isBlank()) return "未设置"
    ThemePack.find(id)?.let { return it.name }
    return id
}

private fun roleLabel(role: String): String = when (role) {
    "wallpaper_chats" -> "主页背景"
    "wallpaper_contacts" -> "通讯录背景"
    "wallpaper_discover" -> "发现页背景"
    "wallpaper_me" -> "我的背景"
    "bubble_left" -> "消息气泡·左"
    "bubble_right" -> "消息气泡·右"
    else -> role
}

/** 分享指定主题的完整配置与素材，不切换当前主题。 */
private fun shareSelectedTheme(context: Context, id: String) {
    val file = ThemeStore.exportTheme(id).getOrNull()
    if (file == null) {
        showToast(context, "导出失败，无法分享")
        return
    }
    val authority = "${HostInfo.packageName}.external.fileprovider"
    val uri = runCatching { FileProvider.getUriForFile(context, authority, file) }.getOrNull()
    if (uri == null) {
        WeLogger.w("ThemeWorkshop", "FileProvider 取不到 uri：${file.absolutePath}")
        showToast(context, "分享失败：无法读取该文件（已导出到 ${file.absolutePath}）")
        return
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/zip"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, "分享主题").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(chooser) }
        .onFailure {
            WeLogger.w("ThemeWorkshop", "拉起分享面板失败：${it.message}")
            showToast(context, "打开分享面板失败：${it.message}")
        }
}

private fun findFeature(name: String): SwitchFeature? =
    FeaturesProvider.ALL_HOOK_ITEMS.filterIsInstance<SwitchFeature>().firstOrNull { it.name == name }
