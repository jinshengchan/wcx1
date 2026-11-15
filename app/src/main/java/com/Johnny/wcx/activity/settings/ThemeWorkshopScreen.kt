package com.Johnny.wcx.activity.settings

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.items.beautify.ThemeExtras
import com.Johnny.wcx.features.items.beautify.ThemePack
import com.Johnny.wcx.features.items.beautify.ThemeStore
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.HostInfo
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.restartHost
import h.Hchat.ui.miuix.PageScaffold
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 主题设置（独立子页面）。
 *
 * 一套主题 = 4 张界面背景（主页 / 通讯录 / 发现 / 我）+ 2 个点九气泡（左·对方 / 右·自己）。
 *
 * 交互约定（用户明确要求）：
 * - 点卡片/下拉只是「选中」，不生效；必须点右上角「应用」才算应用，且要划掉微信重开
 * - 内置主题的气泡素材只读；长按可重命名/改描述，但不能分享
 * - 主界面背景与文字颜色直接修改正在使用的主题，即时生效
 */
@Composable
fun ThemeWorkshopScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    val builtinThemes = remember { ThemeStore.builtinThemeItems() }
    var packs by remember { mutableStateOf(ThemePack.list()) }
    var active by remember { mutableStateOf(ThemeStore.activeThemeIdOrEmpty()) }
    // 待应用的主题（内置 id 或主题包 id）；点「应用」才写进 ThemeStore
    var selectedId by rememberSaveable { mutableStateOf(ThemeStore.activeThemeIdOrEmpty()) }
    var editing by remember { mutableStateOf<ThemePack.Pack?>(ThemePack.find(selectedId)) }
    var pendingRole by rememberSaveable { mutableStateOf("") }
    var pendingThemeId by rememberSaveable { mutableStateOf("") }
    var pendingMainThemeId by rememberSaveable { mutableStateOf("") }
    var pendingTabThemeId by rememberSaveable { mutableStateOf("") }
    var pendingTabIndex by rememberSaveable { mutableStateOf(-1) }
    var pendingTabSelected by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var showCreate by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<ThemePack.Pack?>(null) }
    var renameText by remember { mutableStateOf("") }
    var descTarget by remember { mutableStateOf<ThemePack.Pack?>(null) }
    var descText by remember { mutableStateOf("") }
    var builtinRenameId by remember { mutableStateOf("") }
    var builtinRenameText by remember { mutableStateOf("") }
    var builtinDescId by remember { mutableStateOf("") }
    var builtinDescText by remember { mutableStateOf("") }
    var builtinLongPress by remember { mutableStateOf("") }
    var longPressTarget by remember { mutableStateOf<ThemePack.Pack?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmClearCache by remember { mutableStateOf(false) }
    var appliedDialog by remember { mutableStateOf(false) }
    var enableConfirm by remember { mutableStateOf(false) }
    var showRandomPool by remember { mutableStateOf(false) }
    var poolFiles by remember { mutableStateOf(ThemePack.randomPoolFiles()) }

    var draftName by rememberSaveable { mutableStateOf(editing?.name.orEmpty()) }
    var draftDesc by rememberSaveable { mutableStateOf(editing?.manifest?.optString("description").orEmpty()) }
    var descVisible by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var pendingMainIndex by rememberSaveable { mutableStateOf(-1) }
    var showTitleColor by remember { mutableStateOf(false) }

    fun refreshAll() {
        packs = ThemePack.list()
        active = ThemeStore.activeThemeIdOrEmpty()
        poolFiles = ThemePack.randomPoolFiles()
        tick++
    }

    fun loadDraft(pack: ThemePack.Pack?) {
        editing = pack
        draftName = pack?.name.orEmpty()
        draftDesc = pack?.manifest?.optString("description").orEmpty()
        descVisible = draftDesc.isNotBlank()
    }

    /** 选中主题（不应用）。 */
    fun select(id: String) {
        selectedId = id
        val pack = ThemePack.find(id)
        loadDraft(pack)
        if (pack == null) {
            val name = builtinThemes.firstOrNull { it.first == id }?.second
            showToast(context, "已选中：${name?.let { ThemeStore.builtinDisplayName(id, it) } ?: id}，点右上角「应用」生效")
        } else {
            showToast(context, "已选中：${pack.name}，点右上角「应用」生效")
        }
        tick++
    }

    fun editableActive(): Boolean {
        val id = ThemeStore.activeThemeIdOrEmpty()
        if (id.isBlank() || ThemePack.find(id) != null) return true
        ThemeStore.cloneTheme(id)
            .onSuccess { copy -> refreshAll(); select(copy.id); showToast(context, "已复制内置主题，请先应用副本再编辑当前主题") }
            .onFailure { showToast(context, "复制失败：${it.message}") }
        return false
    }

    fun saveCurrent(): Boolean {
        // editing 只在「手动点选主题」时才有值。直接点右上「应用」时它是 null，
        // 原来会直接 return false → 弹「保存失败」（用户必须重新点一下主题才能应用）。
        // 这里用当前选中的主题兜底。
        val pack = editing ?: ThemePack.find(selectedId) ?: return false
        if (ThemePack.updateMeta(pack, draftName, draftDesc.trim()) == null) return false
        refreshAll()
        ThemePack.find(pack.id)?.let { loadDraft(it) }
        return true
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
        if (pack != null) {
            if (!saveCurrent()) {
                showToast(context, "保存失败")
                return
            }
        }
        if (ThemeStore.profileSnapshot(id) == null) {
            showToast(context, "应用失败：主题配置无效")
            return
        }
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
     * 右上角「应用」。
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
        val pack = ThemePack.find(pendingThemeId)
        if (pack == null || pendingRole.isEmpty()) return@rememberLauncherForActivityResult
        val ninePatch = pendingRole.startsWith("bubble_")
        ThemePack.writeImage(pack, context, uri, pendingRole, ninePatch)?.let { updated ->
            if (selectedId == updated.id) editing = updated
            refreshAll()
            showToast(context, "已选择：${roleLabel(pendingRole)}")
        } ?: showToast(context, "素材保存失败，请检查图片格式和可用空间")
    }

    val pickTabIconLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            require(pendingTabIndex in 0..3) { "标签序号无效" }
            val snapshot = requireNotNull(ThemeStore.profileSnapshot(pendingTabThemeId)) { "原主题已不可用" }
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
                val json = snapshot.profile.toJson()
                val appearance = json.getJSONObject("appearance")
                val chrome = appearance.optJSONObject("chrome") ?: org.json.JSONObject().also { appearance.put("chrome", it) }
                val icons = chrome.optJSONObject("tabIcons") ?: org.json.JSONObject().also { chrome.put("tabIcons", it) }
                val state = if (pendingTabSelected) "selected" else "normal"
                val existing = icons.optJSONArray(state) ?: json.optJSONArray("tab")
                val values = org.json.JSONArray()
                val relative = "edits/${java.util.UUID.randomUUID()}/tab.img"
                repeat(4) { index -> values.put(if (index == pendingTabIndex) relative else existing?.opt(index) ?: org.json.JSONObject.NULL) }
                icons.put(state, values)
                ThemeStore.updateThemeProfile(json, mapOf(relative to source), pendingTabThemeId).getOrThrow()
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
        val result = if (ThemeStore.activeThemeIdOrEmpty() == targetId) {
            ThemeStore.setMainWallpaper(context, uri, pendingMainIndex.takeIf { it in 0..3 })
        } else runCatching {
            val snapshot = requireNotNull(ThemeStore.profileSnapshot(targetId)) { "原主题已不可用" }
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
                val json = snapshot.profile.toJson()
                val pages = json.getJSONObject("appearance").getJSONObject("pages")
                val keys = listOf("chats", "contacts", "discover", "me")
                val indices = pendingMainIndex.takeIf { it in 0..3 }?.let { listOf(it) } ?: keys.indices.toList()
                val relative = "edits/${java.util.UUID.randomUUID()}/wallpaper.img"
                indices.forEach { pages.put(keys[it], org.json.JSONObject().put("kind", "image").put("asset", relative)) }
                ThemeStore.updateThemeProfile(json, mapOf(relative to source), targetId).getOrThrow()
            } finally { source.delete() }
        }
        result
            .onSuccess {
                refreshAll()
                if (editing?.id == active) editing = ThemePack.find(active)
                showToast(context, if (targetId == active) "主界面背景已更新，即时生效" else "原主题的背景草稿已更新")
            }
            .onFailure { showToast(context, "设置失败：${it.message}") }
    }

    fun pickMainImage(index: Int?) {
        if (!editableActive()) return
        if (ThemeStore.activeThemeIdOrEmpty().isBlank()) {
            showToast(context, "先选择并应用一套主题，再设置主界面背景")
            return
        }
        pendingMainIndex = index ?: -1
        pendingMainThemeId = ThemeStore.activeThemeIdOrEmpty()
        pickMainImageLauncher.launch("image/*")
    }

    fun resetMainImages(indices: Set<Int>) {
        if (!editableActive()) return
        ThemeStore.resetMainWallpapers(indices)
            .onSuccess {
                refreshAll()
                if (editing?.id == active) editing = ThemePack.find(active)
                showToast(context, "已恢复微信原生背景")
            }
            .onFailure { showToast(context, "恢复失败：${it.message}") }
    }

    PageScaffold(
        title = "主题设置",
        largeTitle = "主题设置",
        onBack = onBack,
        topBarActions = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 应用：右上角、⋮ 左边。只有点它才真正生效（并提示划掉微信重开）
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.14f))
                        .clickable { applySelected() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "应用",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Box {
                    Text(
                        text = "⋮",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = BasicComponentDefaults.titleColor().color,
                        modifier = Modifier
                            .clickable { menuOpen = true }
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                    )
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("导入主题") },
                            onClick = {
                                menuOpen = false
                                importLauncher.launch("*/*")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("复制为新主题") },
                            onClick = {
                                menuOpen = false
                                ThemeStore.cloneTheme(selectedId)
                                    .onSuccess { pack ->
                                        refreshAll()
                                        select(pack.id)
                                        showToast(context, "已复制：${pack.name}，编辑后点「应用」")
                                    }
                                    .onFailure { showToast(context, "复制失败：${it.message}") }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导出主题") },
                            onClick = {
                                menuOpen = false
                                ThemeStore.exportTheme(selectedId)
                                    .onSuccess { showToast(context, "已导出：${it.absolutePath}") }
                                    .onFailure { showToast(context, "导出失败：${it.message}") }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("备份主题") },
                            onClick = {
                                menuOpen = false
                                val pack = ThemePack.find(selectedId)
                                if (pack == null) {
                                    showToast(context, "内置主题不需要备份，请选自己的主题")
                                } else {
                                    ThemePack.backup(pack)
                                        .onSuccess { showToast(context, "已备份：${it.absolutePath}") }
                                        .onFailure { showToast(context, "备份失败：${it.message}") }
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("恢复默认") },
                            onClick = {
                                menuOpen = false
                                confirmReset = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("清除主题缓存") },
                            onClick = {
                                menuOpen = false
                                confirmClearCache = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("主题诊断") },
                            onClick = {
                                menuOpen = false
                                val info = runCatching { ThemeStore.diagnose() }
                                    .getOrElse { "诊断失败：" + (it.message ?: it.javaClass.simpleName) }
                                android.app.AlertDialog.Builder(context)
                                    .setTitle("主题诊断")
                                    .setMessage(info)
                                    .setPositiveButton("知道了", null)
                                    .setNeutralButton("复制") { _, _ ->
                                        runCatching {
                                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                                as? android.content.ClipboardManager
                                            cm?.setPrimaryClip(android.content.ClipData.newPlainText("主题诊断", info))
                                        }
                                        showToast(context, "已复制，直接发给我即可")
                                    }
                                    .show()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("使用说明") },
                            onClick = {
                                menuOpen = false
                                showGuide = true
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 90.dp,
            ),
        ) {
            item {
                GroupCard(title = "主界面背景 · 正在使用的主题") {
                    if (remember(tick) { findFeature("主题工坊")?.isEnabled != true }) {
                        HintRow("主题功能总开关已关闭。可先设置，下方开启总开关后显示效果。")
                    }
                    val backgroundOn = remember(tick) { ThemeStore.mainWallpaperEnabled() }
                    SwitchRow(
                        title = "开启主界面背景",
                        summary = "控制正在使用主题的四页背景；选图与恢复均即时生效",
                        checked = backgroundOn,
                    ) { enabled ->
                        if (!editableActive()) return@SwitchRow
                        ThemeStore.setMainWallpaperEnabled(enabled)
                        refreshAll()
                    }
                    RowDivider()
                    SwitchRow(
                        title = "背景绘制方式",
                        summary = "开启：背景图绘制在页面内容下方。关闭：使用半透明前景叠加方式兼容显示。此项只切换绘制方式。",
                        checked = remember(tick) { ThemeStore.usePageBackground() },
                    ) { enabled ->
                        ThemeStore.setUsePageBackground(enabled)
                        refreshAll()
                    }
                    RowDivider()
                    HintRow(
                        if (active.isBlank()) "请先在下方选择并应用一套主题。背景设置会即时作用于正在使用的主题。"
                        else "正在使用：${themeName(active)}。以下选图与恢复即时生效，无需点右上角「应用」。"
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PillButton("一张图片应用四页", Modifier.weight(1f), primary = true) { pickMainImage(null) }
                        PillButton("恢复全部", Modifier.weight(1f)) { resetMainImages(setOf(0, 1, 2, 3)) }
                    }
                    listOf("最近聊天", "通讯录", "发现", "我").forEachIndexed { index, label ->
                        RowDivider()
                        val status = remember(tick, index) { ThemeStore.mainWallpaperStatus(index) }
                        MainWallpaperRow(
                            title = label,
                            summary = status,
                            onPick = { pickMainImage(index) },
                            onReset = { resetMainImages(setOf(index)) },
                        )
                    }
                }
            }
            item {
                GroupCard(title = "主界面文字颜色") {
                    val textColorOn = remember(tick) { ThemeStore.mainTextColorEnabled() }
                    val customTitleColor = remember(tick) { ThemeStore.mainTitleColor() }
                    SwitchRow(
                        title = "主界面文字总开关（当前主题）",
                        summary = "随主题功能总开关生效；关闭后恢复微信原生文字和图标颜色，开启后即时应用下方设置",
                        checked = textColorOn,
                    ) { enabled ->
                        if (!editableActive()) return@SwitchRow
                        ThemeStore.setMainTextColorEnabled(enabled)
                        refreshAll()
                    }
                    RowDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f).clickable { if (editableActive()) showTitleColor = true }.padding(vertical = 4.dp),
                        ) {
                            Text("默认文字与图标颜色", fontSize = 15.sp, color = titleColor())
                            Spacer(Modifier.height(2.dp))
                            Text(
                                customTitleColor?.let { rgbHex(it) } ?: "自动（按背景调整清晰文字）",
                                fontSize = 12.sp,
                                color = summaryColor(),
                            )
                        }
                        Box(
                            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
                                .background(customTitleColor?.let { Color(it) } ?: MiuixTheme.colorScheme.primary)
                                .clickable { if (editableActive()) showTitleColor = true },
                        )
                        TextButton(onClick = { if (editableActive()) showTitleColor = true }) { Text("选择") }
                        TextButton(onClick = {
                            if (!editableActive()) return@TextButton
                            ThemeStore.setMainTitleColor(null)
                            refreshAll()
                        }) { Text("恢复") }
                    }
                    HintRow("只设置当前主题的默认颜色；各角色的独立配色优先，不会被此项覆盖。下方所选主题的分组关闭或深色禁用时，该分组不着色。")
                }
            }

            // ── 卡片1 主题基础 ───────────────────────────────
            item {
                GroupCard(title = "主题基础") {
                    ThemeModeSettings(selectedId, tick, onEditCopy = { refreshAll(); select(it.id) }) { refreshAll() }
                    RowDivider()
                    // 随机聊天背景：开关 + 齿轮（管理图池，最多 5 张）
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("随机聊天背景", fontSize = 15.sp, color = titleColor())
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "每次进聊天界面从图池随机换一张，只作用于聊天页" +
                                    "（已选 ${poolFiles.size}/${ThemePack.RANDOM_POOL_MAX} 张）",
                                fontSize = 12.sp,
                                color = summaryColor(),
                            )
                        }
                        var randomOn by remember(tick) {
                            mutableStateOf(WePrefs.getBoolOrFalse(ThemeExtras.KEY_RANDOM_WALLPAPER))
                        }
                        Switch(
                            checked = randomOn,
                            onCheckedChange = { on ->
                                WePrefs.putBool(ThemeExtras.KEY_RANDOM_WALLPAPER, on)
                                randomOn = on
                                ThemeExtras.onPrefChanged(ThemeExtras.KEY_RANDOM_WALLPAPER, on)
                            },
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = "⚙",
                            fontSize = 18.sp,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable {
                                    poolFiles = ThemePack.randomPoolFiles()
                                    showRandomPool = true
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                    RowDivider()
                    FeatureSwitchRow(
                        featureName = "主题工坊",
                        title = "主题功能总开关",
                        summary = "控制整套主题，关闭后还原背景、文字颜色与气泡",
                        tick = tick,
                        onChanged = { refreshAll() },
                    )
                    RowDivider()
                    NavRow(
                        // 这一行只负责「自己的主题」；选中的是内置主题时这里保持未选择
                        title = ThemePack.find(selectedId)?.name ?: "未选择",
                        summary = "只列你自己新建/导入的主题，点这里选择",
                        onClick = { showPicker = true },
                    )
                    RowDivider()
                    // 当前选中的主题：跟着上面/下面的选择实时变（未选则显示微信原生）
                    InfoRow(
                        label = "待应用主题",
                        value = if (selectedId.isBlank()) "未选择（微信原生）" else themeName(selectedId),
                    )
                    RowDivider()
                    OpacityRow(tick)
                }
            }

            // ── 卡片2 快捷功能 ───────────────────────────────
            item {
                GroupCard(title = "快捷功能") {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PillButton("保存", Modifier.weight(1f)) {
                                if (editing == null) showToast(context, "内置主题无需保存，上方主界面设置即时生效")
                                else if (saveCurrent()) showToast(context, "已保存：$draftName")
                            }
                            PillButton("分享", Modifier.weight(1f)) {
                                shareSelectedTheme(context, selectedId)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        PillButton("恢复默认", Modifier.fillMaxWidth()) { confirmReset = true }
                    }
                }
            }

            // ── 卡片3 主题编辑 ───────────────────────────────
            item {
                GroupCard(title = "所选主题编辑") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PillButton("新建主题", Modifier.weight(1f)) {
                            createName = "我的主题"
                            showCreate = true
                        }
                    }
                    RowDivider()
                    val pack = editing
                    when {
                        selectedId.isBlank() -> HintRow("先在下面「默认主题」或「我的主题」里点一张卡片选中它。")
                        // 内置主题：素材只读，主界面即时设置在上方
                        pack == null -> {
                            HintRow("内置主题只读，复制后可编辑副本。")
                            BuiltinPreviewStrip(selectedId)
                            PillButton("复制并编辑", Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                                ThemeStore.cloneTheme(selectedId)
                                    .onSuccess { copy -> refreshAll(); select(copy.id) }
                                    .onFailure { showToast(context, "复制失败：${it.message}") }
                            }
                        }
                        else -> {
                            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                                OutlinedTextField(
                                    value = draftName,
                                    onValueChange = { draftName = it },
                                    label = { Text("主题名称") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                if (descVisible) {
                                    Spacer(Modifier.height(8.dp))
                                    OutlinedTextField(
                                        value = draftDesc,
                                        onValueChange = { draftDesc = it },
                                        label = { Text("主题描述") },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                } else {
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        text = "＋ 添加描述（选填）",
                                        fontSize = 12.sp,
                                        color = MiuixTheme.colorScheme.primary,
                                        modifier = Modifier.clickable { descVisible = true },
                                    )
                                }
                            }
                            RowDivider()
                            ThemePreview(pack)
                            RowDivider()
                            HintRow(if (pack.id == active) "正在编辑已应用主题：${pack.name}。四页图片统一在上方「主界面背景」设置。"
                                else "正在编辑未应用主题：${pack.name}。修改保存在此主题中，点右上角「应用」后才显示。")
                            ThemeProfileSettings(pack.id, tick, onPickTab = { index, selected ->
                                pendingTabThemeId = pack.id
                                pendingTabIndex = index
                                pendingTabSelected = selected
                                pickTabIconLauncher.launch("image/*")
                            }) { refreshAll() }
                            RowDivider()
                            ThemeOwnedSettings(pack.id, tick) { refreshAll() }
                            if (pack.id != active) {
                                HintRow("未应用主题的四页图片（${pack.name}）")
                                listOf("主页背景" to "wallpaper_chats", "通讯录背景" to "wallpaper_contacts",
                                    "发现页背景" to "wallpaper_discover", "我的背景" to "wallpaper_me").forEach { (title, role) ->
                                    MaterialRow(title, role, pack, pendingRole,
                                        { pickedRole -> pendingRole = pickedRole; pendingThemeId = pack.id; pickImageLauncher.launch("image/*") },
                                        { updated -> editing = updated; tick++ },
                                    )
                                }
                            }
                            MaterialRow("消息气泡 · 左（对方）", "bubble_left", pack, pendingRole,
                                { role -> pendingRole = role; pendingThemeId = pack.id; pickImageLauncher.launch("*/*") },
                                { updated -> editing = updated; tick++ },
                            )
                            MaterialRow("消息气泡 · 右（自己）", "bubble_right", pack, pendingRole,
                                { role -> pendingRole = role; pendingThemeId = pack.id; pickImageLauncher.launch("*/*") },
                                { updated -> editing = updated; tick++ },
                            )
                        }
                    }
                }
            }

            // ── 卡片4 默认主题（内置 6 款，全局）───────────────
            item { GroupTitleRow("默认主题", "全局 · 不可分享") }
            items((builtinThemes.size + 1) / 2) { rowIndex ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (col in 0..1) {
                        val index = rowIndex * 2 + col
                        if (index < builtinThemes.size) {
                            val (id, name) = builtinThemes[index]
                            BuiltinGridCard(
                                id = id,
                                name = ThemeStore.builtinDisplayName(id, name),
                                active = active == id,
                                selected = selectedId == id,
                                modifier = Modifier.weight(1f),
                                onClick = { select(id) },
                                onLongClick = { builtinLongPress = id },
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            // ── 卡片5 我的主题（自己新建/导入）──────────────────
            item {
                GroupTitleRow("我的主题", if (packs.isEmpty()) "" else "${packs.size} 个")
            }
            if (packs.isEmpty()) {
                item {
                    GroupCard(title = null) {
                        HintRow("还没有自己的主题。点上面「新建主题」，或右上角「⋮ → 导入主题」。")
                    }
                }
            } else {
                items((packs.size + 1) / 2) { rowIndex ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        for (col in 0..1) {
                            val index = rowIndex * 2 + col
                            if (index < packs.size) {
                                val pack = packs[index]
                                ThemeGridCard(
                                    pack = pack,
                                    active = active == pack.id,
                                    selected = selectedId == pack.id,
                                    modifier = Modifier.weight(1f),
                                    onClick = { select(pack.id) },
                                    onLongClick = { longPressTarget = pack },
                                )
                            } else {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    // ── 弹窗区 ──────────────────────────────────────────────

    if (showTitleColor) {
        RgbColorDialog(
            initialColor = ThemeStore.mainTitleColor(),
            onDismiss = { showTitleColor = false },
            onApply = { color ->
                ThemeStore.setMainTitleColor(color)
                refreshAll()
                showTitleColor = false
            },
        )
    }

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
                    GuideLine("2", "必须点「应用」", "无论是内置主题还是自己新建的主题，选中后都要点右上角「应用」才会生效。")
                    GuideLine("3", "应用后会自动重启", "点「应用」后会自动热重启微信，重开就能看到效果；重启失败时才需要手动划掉微信重开。")
                    GuideLine("4", "主界面即时设置", "上方背景与文字颜色设置只作用于正在使用的主题，无需重启。四页可共用一张图片，也可单独选择或恢复；内置主题同样适用。")
                    GuideLine("5", "自己做/分享", "新建主题 → 选图片 → 保存 → 右上角应用；分享按钮只分享自己的主题。")
                    GuideLine("6", "聊天页规则", "四页背景与聊天外观分别配置；聊天外观组关闭时沿用独立气泡设置，明确选择原生气泡时使用微信样式。")
                    GuideLine("7", "透明度为什么封顶 70%", "背景越不透明越容易盖住文字、时间和分割线。70% 是「背景够明显、文字还看得清」的平衡点，默认 40%。")
                    GuideLine("8", "旧气泡没换掉怎么办", "右上角 ⋮ →「清除主题缓存」，一键清掉已应用的气泡与背景，回到微信原生。")
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
                            showToast(context, "已创建：${pack.name}，选好素材后点右上角「应用」")
                        }
                        .onFailure { showToast(context, "创建失败：${it.message}") }
                }) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("取消") } },
        )
    }

    // 主题下拉：只列自己新建/导入的主题（内置主题在下面网格里选）
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("选择我的主题") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    if (packs.isEmpty()) {
                        Text(
                            text = "还没有自己新建的主题。点「新建主题」或从右上角导入。",
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
                    "会撤销当前主题外观，清理解码和内置素材缓存；保留主题包、配置及独立气泡素材。\n" +
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

    // 内置主题长按：只能重命名 / 改描述，没有分享
    if (builtinLongPress.isNotEmpty()) {
        val originalName = builtinThemes.firstOrNull { it.first == builtinLongPress }?.second ?: builtinLongPress
        AlertDialog(
            onDismissRequest = { builtinLongPress = "" },
            title = { Text(ThemeStore.builtinDisplayName(builtinLongPress, originalName)) },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    PickerRow("重命名", false, false) {
                        builtinRenameText = ThemeStore.builtinDisplayName(builtinLongPress, originalName)
                        builtinRenameId = builtinLongPress
                        builtinLongPress = ""
                    }
                    PickerRow("修改描述", false, false) {
                        builtinDescText = ThemeStore.builtinDesc(builtinLongPress)
                        builtinDescId = builtinLongPress
                        builtinLongPress = ""
                    }
                    Text(
                        text = "内置主题不能分享、不能改素材，只能调壁纸透明度。",
                        fontSize = 12.sp,
                        color = summaryColor(),
                        modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { builtinLongPress = "" }) { Text("关闭") } },
        )
    }

    if (builtinRenameId.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { builtinRenameId = "" },
            title = { Text("重命名内置主题") },
            text = {
                OutlinedTextField(
                    value = builtinRenameText,
                    onValueChange = { builtinRenameText = it },
                    label = { Text("名称") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ThemeStore.setBuiltinAlias(builtinRenameId, builtinRenameText)
                    builtinRenameId = ""
                    tick++
                    showToast(context, "已重命名")
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { builtinRenameId = "" }) { Text("取消") } },
        )
    }

    if (builtinDescId.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { builtinDescId = "" },
            title = { Text("修改描述") },
            text = {
                OutlinedTextField(
                    value = builtinDescText,
                    onValueChange = { builtinDescText = it },
                    label = { Text("描述（可留空）") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ThemeStore.setBuiltinDesc(builtinDescId, builtinDescText)
                    builtinDescId = ""
                    tick++
                    showToast(context, "已修改描述")
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { builtinDescId = "" }) { Text("取消") } },
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
                    ThemePack.updateMeta(target, renameText, null)
                    renameTarget = null
                    refreshAll()
                    if (editing?.id == target.id) ThemePack.find(target.id)?.let { loadDraft(it) }
                    showToast(context, "已重命名")
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
                    ThemePack.updateMeta(target, null, descText)
                    descTarget = null
                    refreshAll()
                    if (editing?.id == target.id) ThemePack.find(target.id)?.let { loadDraft(it) }
                    showToast(context, "已修改描述")
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
private fun GroupCard(title: String?, content: @Composable ColumnScope.() -> Unit) {
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
        val shape = RoundedCornerShape(22.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(elevation = 2.dp, shape = shape, ambientColor = Color.Black.copy(alpha = 0.06f))
                .clip(shape)
                .background(MiuixTheme.colorScheme.surface),
            content = content,
        )
    }
}

@Composable
private fun GroupTitleRow(title: String, trailing: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = summaryColor())
        Spacer(Modifier.weight(1f))
        if (trailing.isNotBlank()) {
            Text(trailing, fontSize = 12.sp, color = summaryColor())
        }
    }
}

@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp)
            .height(0.6.dp)
            .background(titleColor().copy(alpha = 0.08f)),
    )
}

@Composable
private fun PrefSwitchRow(key: String, title: String, summary: String, tick: Int) {
    var checked by remember(key, tick) { mutableStateOf(WePrefs.getBoolOrFalse(key)) }
    SwitchRow(title = title, summary = summary, checked = checked) { on ->
        WePrefs.putBool(key, on)
        checked = on
        ThemeExtras.onPrefChanged(key, on)
    }
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
private fun SwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = titleColor())
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

@Composable
private fun MainWallpaperRow(title: String, summary: String, onPick: () -> Unit, onReset: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).clickable(onClick = onPick).padding(vertical = 4.dp)) {
            Text(title, fontSize = 15.sp, color = titleColor())
            Spacer(Modifier.height(2.dp))
            Text(summary, fontSize = 12.sp, color = summaryColor(), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onPick) { Text("选择图片") }
        TextButton(onClick = onReset) { Text("恢复") }
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
private fun NavRow(title: String, summary: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = titleColor(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (summary.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(summary, fontSize = 12.sp, color = summaryColor())
            }
        }
        Text("›", fontSize = 20.sp, color = summaryColor())
    }
}

/** 只读信息行（右侧不显示箭头，表示不可点）。 */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp, color = titleColor(), modifier = Modifier.weight(1f))
        Text(
            text = value,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    onClick: () -> Unit,
) {
    val bg = if (primary) MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
    else titleColor().copy(alpha = 0.07f)
    val fg = if (primary) MiuixTheme.colorScheme.primary else titleColor()
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

/** 主题包预览（用主页背景）。 */
@Composable
private fun ThemePreview(pack: ThemePack.Pack) {
    val file = pack.wallpaperFor("chats")
    val bitmap = remember(pack.id, file?.absolutePath) {
        file?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .height(120.dp)
            .clip(RoundedCornerShape(16.dp))
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
        } else {
            Text("预览图（选一张主页背景后显示）", fontSize = 12.sp, color = summaryColor())
        }
    }
}

/** 内置主题的只读预览条。 */
@Composable
private fun BuiltinPreviewStrip(id: String) {
    val thumb = remember(id) { ThemeStore.builtinThumbnail(id) }
    val bitmap = remember(id, thumb?.absolutePath) {
        thumb?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .height(120.dp)
            .clip(RoundedCornerShape(16.dp))
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
        } else {
            Text("内置主题预览", fontSize = 12.sp, color = summaryColor())
        }
    }
}

@Composable
private fun MaterialRow(
    title: String,
    role: String,
    editing: ThemePack.Pack?,
    pendingRole: String,
    onPick: (String) -> Unit,
    onReset: (ThemePack.Pack) -> Unit,
) {
    val context = LocalContext.current
    val snapshot = editing?.let { ThemeStore.profileSnapshot(it.id) }
    val pageKey = role.removePrefix("wallpaper_").takeIf { it in listOf("chats", "contacts", "discover", "me") }
    val pageRule = pageKey?.let { snapshot?.profile?.background(it) }
    val set = if (role.startsWith("bubble_") && snapshot != null) {
        val key = if (role == "bubble_left") "leftBubble" else "rightBubble"
        when (val rule = snapshot.profile.background("chat", key)) {
            is com.Johnny.wcx.features.items.beautify.BackgroundRule.Image -> rule.asset
            com.Johnny.wcx.features.items.beautify.BackgroundRule.Inherit -> snapshot.profile.toJson().optString(role).takeIf { it.isNotBlank() }
            else -> null
        }
    } else if (pageKey != null && snapshot != null) {
        (pageRule as? com.Johnny.wcx.features.items.beautify.BackgroundRule.Image)?.asset
    } else editing?.manifest?.optString(role)?.takeIf { it.isNotBlank() }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, fontSize = 15.sp, color = titleColor())
        Spacer(Modifier.height(2.dp))
        Text(
            text = when {
                set != null -> "已选择：$set"
                pageRule is com.Johnny.wcx.features.items.beautify.BackgroundRule.Solid -> "纯色背景"
                pageRule === com.Johnny.wcx.features.items.beautify.BackgroundRule.Transparent -> "透明背景"
                pageKey != null && snapshot != null -> "微信原生背景"
                pendingRole == role -> "正在选择…"
                else -> "未选择（保持微信原样）"
            },
            fontSize = 12.sp,
            color = summaryColor(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("选择图片", Modifier.weight(1f)) {
                if (editing == null) showToast(context, "先新建或选择一个主题") else onPick(role)
            }
            PillButton("重置默认", Modifier.weight(1f)) {
                val pack = editing
                if (pack == null) {
                    showToast(context, "先新建或选择一个主题")
                } else {
                    val result = if (role.startsWith("bubble_")) {
                        runCatching {
                            val json = requireNotNull(ThemeStore.profileSnapshot(pack.id)) { "主题配置不可用" }.profile.toJson()
                            resetBubbleRule(json, role)
                            ThemeStore.updateThemeProfile(json, id = pack.id).getOrThrow()
                            pack
                        }
                    } else runCatching { requireNotNull(ThemePack.resetRole(pack, role)) { "恢复失败" } }
                    result.onSuccess { updated ->
                        onReset(updated)
                        showToast(context, "已恢复默认：$title")
                    }.onFailure { showToast(context, "恢复失败：${it.message}") }
                }
            }
        }
    }
    RowDivider()
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
private fun BuiltinGridCard(
    id: String,
    name: String,
    active: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val thumb = remember(id) { ThemeStore.builtinThumbnail(id) }
    val bitmap = remember(id, thumb?.absolutePath) {
        thumb?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
    }
    val fallback = remember(id) { Color(ThemeStore.builtinColor(id).toInt()) }
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .shadow(elevation = 1.dp, shape = shape, ambientColor = Color.Black.copy(alpha = 0.06f))
            .clip(shape)
            .then(
                if (selected) Modifier.border(2.dp, MiuixTheme.colorScheme.primary, shape)
                else Modifier
            )
            .background(MiuixTheme.colorScheme.surface)
            .pointerInput(id) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() },
                )
            },
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(84.dp).background(fallback),
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
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
                    fontSize = 13.sp,
                    color = titleColor(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(" 全局", fontSize = 10.sp, color = summaryColor())
            }
            val badge = when {
                selected && active -> "使用中"
                selected -> "当前选择"
                active -> "使用中"
                else -> ""
            }
            if (badge.isNotEmpty()) {
                Text(badge, fontSize = 10.sp, color = MiuixTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun ThemeGridCard(
    pack: ThemePack.Pack,
    active: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val thumb = pack.wallpaperFor("chats")
    val bitmap = remember(pack.id, thumb?.absolutePath) {
        thumb?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }
    }
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .shadow(elevation = 1.dp, shape = shape, ambientColor = Color.Black.copy(alpha = 0.06f))
            .clip(shape)
            .then(
                if (selected) Modifier.border(2.dp, MiuixTheme.colorScheme.primary, shape)
                else Modifier
            )
            .background(MiuixTheme.colorScheme.surface)
            .pointerInput(pack.id) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() },
                )
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .background(titleColor().copy(alpha = 0.05f)),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("无预览", fontSize = 11.sp, color = summaryColor())
            }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = pack.name,
                fontSize = 13.sp,
                color = titleColor(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val badge = when {
                selected && active -> "使用中"
                selected -> "当前选择"
                active -> "使用中"
                else -> ""
            }
            if (badge.isNotEmpty()) {
                Text(badge, fontSize = 10.sp, color = MiuixTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun PickerSectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = summaryColor(),
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 2.dp),
    )
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
    ThemeStore.builtinThemeItems().firstOrNull { it.first == id }?.let {
        return ThemeStore.builtinDisplayName(id, it.second)
    }
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
