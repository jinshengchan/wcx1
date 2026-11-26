package com.Johnny.wcx.features.items.payment

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast

// Configurable blessing text filters and list management adapted from Johnny520/wcx v348.
object RedPacketAdvanced {

    private const val TAG = "RedPacketAdvanced"

    // ── prefs key（与 AutoOpenRedPackets 共用同一份存储） ─────────────────────────
    const val KEY_KW_MODE = "red_packet_kw_mode"
    const val KEY_KW_INCLUDE = "red_packet_kw_include"
    const val KEY_KW_EXCLUDE = "red_packet_kw_exclude"
    const val KEY_WHITELIST = "red_packet_whitelist"
    const val KEY_BLACKLIST = "red_packet_blacklist"

    /** 关键词匹配模式。 */
    enum class KeywordMode(val label: String, val hint: String) {
        OFF("关闭", "不过滤，抢所有红包"),
        INCLUDE("仅抢含关键词", "祝福语包含任一关键词才抢"),
        EXCLUDE("排除含关键词", "祝福语包含任一关键词时跳过"),
    }

    private var kwModeValue by WePrefs.prefOption(KEY_KW_MODE, KeywordMode.OFF.name)
    private var kwIncludeValue by WePrefs.prefOption(KEY_KW_INCLUDE, "")
    private var kwExcludeValue by WePrefs.prefOption(KEY_KW_EXCLUDE, "")


    private var whitelistValue by WePrefs.prefOption(KEY_WHITELIST, emptySet<String>())
    private var blacklistValue by WePrefs.prefOption(KEY_BLACKLIST, emptySet<String>())

    // ── 关键词过滤 ───────────────────────────────────────────────────────────────

    val keywordMode: KeywordMode
        get() = runCatching { KeywordMode.valueOf(kwModeValue) }.getOrDefault(KeywordMode.OFF)

    var includeKeywordsRaw: String
        get() = kwIncludeValue
        set(value) {
            kwIncludeValue = value
        }

    var excludeKeywordsRaw: String
        get() = kwExcludeValue
        set(value) {
            kwExcludeValue = value
        }

    /** 关键词分隔符：换行 / 中英文逗号 / 中英文分号 / 顿号 / 竖线 / 制表符。空格不作分隔（祝福语常含空格）。 */
    private val keywordSeparators = charArrayOf(
        '\n', '\r', ',', '，', ';', '；', '、', '|', '\t'
    )

    private fun splitKeywords(raw: String): List<String> =
        raw.split(*keywordSeparators)
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.lowercase() }
            .distinct()
            .toList()

    fun setKeywordMode(mode: KeywordMode) {
        kwModeValue = mode.name
    }

    /**
     * 是否按关键词跳过这个红包。
     *
     * 仅抢模式需要祝福语或标题匹配；正文缺失时跳过。
     *
     * @param wishing appmsg XML 里的 `<wishing>`（祝福语）
     * @param title   appmsg XML 里的 `<title>`（没有 wishing 时的退化字段）
     */
    fun shouldSkipByKeyword(wishing: String, title: String): Boolean {
        val mode = keywordMode
        if (mode == KeywordMode.OFF) return false

        val text = (if (wishing.isNotBlank()) wishing else title).lowercase()
        if (text.isBlank()) return mode == KeywordMode.INCLUDE

        val keywords = if (mode == KeywordMode.INCLUDE) {
            splitKeywords(kwIncludeValue)
        } else {
            splitKeywords(kwExcludeValue)
        }
        if (keywords.isEmpty()) return mode == KeywordMode.INCLUDE

        val hit = keywords.any { text.contains(it) }
        return if (mode == KeywordMode.INCLUDE) !hit else hit
    }

    // ── 白/黑名单存取（与 AutoOpenRedPackets 的 prefs 同源） ──────────────────────

    fun currentList(useWhitelist: Boolean): Set<String> =
        if (useWhitelist) whitelistValue else blacklistValue

    fun saveList(useWhitelist: Boolean, ids: Set<String>) {
        val cleaned = ids.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (useWhitelist) whitelistValue = cleaned else blacklistValue = cleaned
        WeLogger.i(
            TAG,
            "saved ${if (useWhitelist) "whitelist" else "blacklist"} size=${cleaned.size}"
        )
    }

    fun addToList(useWhitelist: Boolean, id: String): Boolean {
        val trimmed = id.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return false
        val current = currentList(useWhitelist)
        if (current.contains(trimmed)) return false
        saveList(useWhitelist, current + trimmed)
        return true
    }

    fun removeFromList(useWhitelist: Boolean, id: String) {
        val current = currentList(useWhitelist)
        if (!current.contains(id)) return
        saveList(useWhitelist, current - id)
    }

    // ── 设置界面：关键词过滤 + 延时单位 ───────────────────────────────────────────

    fun showKeywordFilterDialog(context: Context) {
        showComposeDialog(context) {
            var mode by remember { mutableStateOf(keywordMode) }
            var includeText by remember { mutableStateOf(kwIncludeValue) }
            var excludeText by remember { mutableStateOf(kwExcludeValue) }

            AlertDialogContent(
                title = { Text("红包关键词过滤") },
                text = {
                    DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                        KeywordMode.entries.forEach { item ->
                            ListItem(
                                modifier = Modifier.clickable { mode = item },
                                headlineContent = {
                                    Text((if (mode == item) "● " else "○ ") + item.label)
                                },
                                supportingContent = { Text(item.hint) },
                            )
                        }
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = includeText,
                            onValueChange = { includeText = it },
                            label = { Text("仅抢包含的关键词") },
                            supportingText = { Text("多个关键词用换行/逗号/顿号分隔；当前模式至少填写一个关键词") },
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = excludeText,
                            onValueChange = { excludeText = it },
                            label = { Text("排除的关键词") },
                            supportingText = { Text("祝福语命中任一关键词时跳过该红包") },
                        )

                    }
                },
                confirmButton = {
                    Button(onClick = {
                        val activeTerms = when (mode) {
                            KeywordMode.OFF -> null
                            KeywordMode.INCLUDE -> includeText
                            KeywordMode.EXCLUDE -> excludeText
                        }
                        if (activeTerms != null && splitKeywords(activeTerms).isEmpty()) {
                            showToast("请填写当前模式的关键词")
                            return@Button
                        }
                        kwIncludeValue = includeText.take(4000)
                        kwExcludeValue = excludeText.take(4000)
                        setKeywordMode(mode)
                        showToast("已保存关键词过滤设置")
                        onDismiss()
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
            )
        }
    }

    // ── 设置界面：白/黑名单手动管理（增删） ────────────────────────────────────────

    fun showListManagerDialog(context: Context, useWhitelist: Boolean) {
        showComposeDialog(context) {
            var ids by remember { mutableStateOf(currentList(useWhitelist)) }
            var input by remember { mutableStateOf("") }
            var selecting by remember { mutableStateOf(false) }
            val titleText = if (useWhitelist) "白名单管理" else "黑名单管理"

            if (selecting) {
                ContactsSelector(
                    title = if (useWhitelist) "选择白名单" else "选择黑名单",
                    contacts = remember { runCatching { WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups() }.getOrDefault(emptyList()) },
                    initialSelectedWxIds = ids,
                    onDismiss = { selecting = false },
                ) { selected ->
                    ids = selected
                    saveList(useWhitelist, selected)
                    selecting = false
                }
            } else {
                AlertDialogContent(
                    title = { Text(titleText) },
                    text = {
                        DefaultColumn {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedTextField(
                                    modifier = Modifier.fillMaxWidth(0.62f),
                                    value = input,
                                    onValueChange = { input = it },
                                    label = { Text("手动添加 wxid / 群 ID") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                                )
                                Button(
                                    onClick = {
                                        if (addToList(useWhitelist, input)) {
                                            ids = currentList(useWhitelist)
                                            input = ""
                                            showToast("已添加，重启微信后生效")
                                        } else {
                                            showToast("ID 无效或已存在")
                                        }
                                    },
                                    enabled = input.isNotBlank(),
                                ) { Text("添加") }
                            }
                            Text("当前 ${ids.size} 项。群聊填群 ID（形如 xxxx@chatroom / xxxx@im.chatroom），个人填 wxid。重启微信后生效。")
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (ids.isEmpty()) {
                                    item { Text("暂无条目") }
                                }
                                items(ids.toList()) { id ->
                                    ListItem(
                                        headlineContent = {
                                            Text(remember(id) { runCatching { WeDatabaseApi.getDisplayName(id) }.getOrDefault(id) })
                                        },
                                        supportingContent = { Text(id) },
                                        trailingContent = {
                                            TextButton(onClick = {
                                                removeFromList(useWhitelist, id)
                                                ids = currentList(useWhitelist)
                                            }) { Text("删除") }
                                        },
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        Button(onClick = onDismiss) { Text("完成") }
                    },
                    dismissButton = {
                        TextButton(onClick = { selecting = true }) { Text("从联系人选择") }
                    },
                )
            }
        }
    }
}
