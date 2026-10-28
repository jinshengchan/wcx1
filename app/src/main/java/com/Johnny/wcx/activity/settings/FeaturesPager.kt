package com.Johnny.wcx.activity.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch as Material3Switch
import androidx.compose.material3.Text as Material3Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Settings
import com.Johnny.wcx.features.core.FeaturesProvider
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.features.items.easter_egg.AprilFools
import com.Johnny.wcx.features.items.easter_egg.isAprilFools
import com.Johnny.wcx.features.items.secret_friend.SecretFriendManager
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.Johnny.wcx.constants.Preferences
import top.yukonga.miuix.kmp.basic.Switch


// ---------------------------------------------------------------------------
//  Page 1 — Features (search bar + category list)
// ---------------------------------------------------------------------------

@Composable
fun FeaturesPager(onOpenCategory: (String) -> Unit) {
    val showAprilFools = remember { LocalDate.now().isAprilFools }

    val queryState = rememberTextFieldState()
    val query = queryState.text.toString()
    val searching = query.isNotBlank()

    // 设置页搜索**显式排除密友功能**：避免输入任意关键词（如"隐藏"）就把密友功能暴露出来。
    // 密友入口 = 设置分类列表最后一项；分类详情页仍按 categories 正常显示全部密友开关。
    val searchableItems = remember {
        FeaturesProvider.ALL_HOOK_ITEMS
            .filterIsInstance<SwitchFeature>()
            .filter { item -> "密友功能" !in item.categories }
    }
    val filteredItems = remember(query) {
        if (!searching) emptyList()
        else searchableItems.filter {
            it.name.contains(query, ignoreCase = true) ||
                    it.description.contains(query, ignoreCase = true)
        }
    }
    val switchStates = remember { mutableStateMapOf<String, Boolean>() }

    // A back press while searching clears the query first (after the IME's own
    // back has dismissed the keyboard) rather than exiting the module settings.
    BackHandler(enabled = searching) { queryState.clearText() }

    MiuixListScaffold(title = "功能") {
        item {
            TextField(
                state = queryState,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth(),
                label = "搜索功能",
                leadingIcon = {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.padding(horizontal = 12.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                },
                trailingIcon = {
                    if (searching) {
                        IconButton(onClick = { queryState.clearText() }) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Close,
                                contentDescription = "Clear query",
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                },
            )
        }

        if (searching) {
            // Search results replace the category list while a query is active
            if (filteredItems.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "未匹配到任何相关功能",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            } else {
                itemsIndexed(filteredItems, key = { _, item -> item.name }) { index, item ->
                    Column(
                        modifier = Modifier
                            .then(if (index == 0) Modifier.padding(top = 12.dp) else Modifier)
                            .groupedCardItem(index, filteredItems.size),
                    ) {
                        FeatureRow(
                            item = item,
                            checked = switchStates[item.name] ?: item.configuredEnabled,
                            onCheckedChange = { switchStates[item.name] = it },
                        )
                    }
                }
            }
        } else {
            if (showAprilFools) {
                item {
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth()
                    ) {
                        ArrowPreference(
                            title = "🏳",
                            summary = "投降喵投降喵",
                            onClick = {
                                WePrefs.putBool(AprilFools.KEY_SURRENDER, true)
                                CoroutineScope(Dispatchers.Main).launch { showToastSuspend("重启生效") }
                            },
                        )
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                ) {
                    FEATURE_CATEGORIES.forEach { (name, icon) ->
                        ArrowPreference(
                            title = name,
                            startAction = {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 6.dp),
                                    tint = MiuixTheme.colorScheme.onBackground,
                                )
                            },
                            onClick = { onOpenCategory(name) },
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}

// ---------------------------------------------------------------------------
//  Category detail (replaces CategorySettingsScreen)
// ---------------------------------------------------------------------------

@Composable
fun CategoryDetailScreen(
    categoryName: String,
    onBack: () -> Unit,
    onOpenSecretFriendDiagnostics: () -> Unit,
) {
    val items = remember(categoryName) {
        FeaturesProvider.ALL_HOOK_ITEMS.filter { categoryName in it.categories }
    }
    val switchStates = remember(categoryName) {
        mutableStateMapOf<String, Boolean>().apply {
            items.forEach { put(it.name, (it as? SwitchFeature)?.configuredEnabled ?: false) }
        }
    }

    val isSecretFriendCategory = categoryName == SwitchFeature.SECRET_FRIEND_CATEGORY
    // 「文章去广告」总开关组住在「公众号」分类里：只管辖组内两个功能，分类里其他功能不受影响。
    val hasArticleAdMaster = items.any { (it as? SwitchFeature)?.underArticleAdMaster == true }
    var masterEnabled by remember(categoryName) {
        mutableStateOf(
            if (isSecretFriendCategory) Preferences.secretFriendMasterEnabled
            else Preferences.articleAdMasterEnabled
        )
    }

    // 「文章去广告」组内两个子功能：只在弹窗里出现，不再平铺在分类列表里。
    val governedItems = items.filterIsInstance<SwitchFeature>().filter { it.underArticleAdMaster }
    val visibleItems = if (hasArticleAdMaster) {
        items.filterNot { (it as? SwitchFeature)?.underArticleAdMaster == true }
    } else {
        items
    }

    val activity = LocalComponentActivity.current

    // 点总开关行（开关本身除外）弹出「文章去广告」弹窗，两个子开关在弹窗里分别拨动。
    // 与 Markdown 渲染弹窗同款：showComposeDialog + AlertDialogContent + Material3 行。
    fun openArticleAdGroupDialog() {
        showComposeDialog(activity) {
            AlertDialogContent(
                title = { Material3Text("文章去广告") },
                text = {
                    Column {
                        governedItems.forEach { feature ->
                            val checked = switchStates[feature.name] ?: feature.configuredEnabled
                            ListItem(
                                modifier = Modifier.clickable {
                                    val requested = !checked
                                    if (feature.onBeforeToggle(requested, activity)) {
                                        WePrefs.putBool(feature.name, requested)
                                        feature.isEnabled = requested
                                        switchStates[feature.name] = requested
                                    }
                                },
                                trailingContent = { Material3Switch(checked, null) },
                                supportingContent = { Material3Text(feature.description) },
                                headlineContent = { Material3Text(feature.name) },
                            )
                        }
                    }
                },
                confirmButton = { TextButton(onDismiss) { Material3Text("关闭") } }
            )
        }
    }

    // 总开关：切换后立即对该分类下所有开关生效（各功能自身开关状态保留）。
    fun applyMasterSwitch(next: Boolean) {
        masterEnabled = next
        if (isSecretFriendCategory) {
            Preferences.secretFriendMasterEnabled = next
            items.filterIsInstance<SwitchFeature>().forEach { feature ->
                // 「密友名单管理」是密友名单编辑入口（无独立开关），直接跟随总开关；
                // 其余功能按各自原有开关状态恢复。
                feature.isEnabled = if (feature.name == SecretFriendManager.name) {
                    next
                } else {
                    next && WePrefs.getBoolOrFalse(feature.name)
                }
            }
            switchStates.keys.forEach { key ->
                switchStates[key] = next && WePrefs.getBoolOrFalse(key)
            }
        } else {
            // 「文章去广告」组：只动组内两个子功能，分类里其他功能不受影响。
            Preferences.articleAdMasterEnabled = next
            items.filterIsInstance<SwitchFeature>().filter { it.underArticleAdMaster }.forEach { feature ->
                feature.isEnabled = next && feature.configuredEnabled
                switchStates[feature.name] = next && feature.configuredEnabled
            }
        }
    }

    MiuixListScaffold(
        title = categoryName,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Arrow_back,
                    contentDescription = "返回",
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
    ) {
        if (items.isEmpty()) return@MiuixListScaffold

        if (isSecretFriendCategory || hasArticleAdMaster) {
            item {
                Column(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .groupedCardItem(0, 1),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (hasArticleAdMaster) Modifier.clickable { openArticleAdGroupDialog() }
                                else Modifier
                            )
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (isSecretFriendCategory) "密友功能总开关" else "文章去广告总开关",
                                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                                    fontWeight = FontWeight.Medium,
                                    color = MiuixTheme.colorScheme.onSurface,
                                )
                                if (hasArticleAdMaster) {
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Settings,
                                        contentDescription = "点开设置两个子开关",
                                        modifier = Modifier.size(20.dp),
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            }
                            Text(
                                text = if (isSecretFriendCategory) {
                                    "关闭后本分类下所有功能都不生效（各开关状态保留）"
                                } else {
                                    "关闭后两个文章去广告功能不生效（各开关状态保留）"
                                },
                                fontSize = MiuixTheme.textStyles.body2.fontSize,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        Switch(checked = masterEnabled, onCheckedChange = ::applyMasterSwitch)
                    }
                }
            }
        }

        if (isSecretFriendCategory) {
            item {
                Card(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth(),
                ) {
                    ArrowPreference(
                        title = "密友功能诊断",
                        startAction = {},
                        onClick = { onOpenSecretFriendDiagnostics() },
                    )
                }
            }
        }

        itemsIndexed(visibleItems, key = { _, item -> item.name }) { index, item ->
            Column(
                modifier = Modifier
                    .then(if (index == 0) Modifier.padding(top = 12.dp) else Modifier)
                    .groupedCardItem(index, visibleItems.size),
            ) {
                FeatureRow(
                    item = item,
                    checked = switchStates[item.name] ?: false,
                    onCheckedChange = { switchStates[item.name] = it },
                )
                item.Ui()
            }
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}
