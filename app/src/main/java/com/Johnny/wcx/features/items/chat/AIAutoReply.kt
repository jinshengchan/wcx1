package com.Johnny.wcx.features.items.chat

import android.annotation.SuppressLint
import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.strings.isGroupChatWxId
import android.content.Context
import android.os.PowerManager
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.More_vert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import androidx.compose.ui.text.input.PasswordVisualTransformation
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections

@SuppressLint("SetTextI18n")
@Feature(
    name = "AI 自动回复",
    categories = ["聊天"],
    description = "接入 AI 大模型自动回复消息，支持 OpenAI 兼容接口，可选择触发条件和指定群聊"
)
object AIAutoReply : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "AIAutoReply"

    private var apiUrl by prefOption("ai_reply_api_url", "https://api.deepseek.com/chat/completions")
    private var apiKey by prefOption("ai_reply_api_key", "")
    private var model by prefOption("ai_reply_model", "deepseek-flash")
    private var contextEnabled by prefOption("ai_reply_context", false)
    private var systemPrompt by prefOption(
        "ai_reply_system_prompt",
        "你是一个乐于助人的AI助手，请用简洁友好的语气回复用户的消息。"
    )
    private var enableForPrivate by prefOption("ai_reply_enable_private", true)
    private var enableForGroup by prefOption("ai_reply_enable_group", false)
    private var groupTriggerKeyword by prefOption("ai_reply_group_keyword", "@AI")
    private var replyPrefix by prefOption("ai_reply_prefix", "[AI回复] ")
    private var replyDelay by prefOption("ai_reply_delay", 1000)

    private var triggerMode by prefOption("ai_reply_trigger_mode", 0)
    private var enabledGroups by prefOption("ai_reply_enabled_groups", emptySet<String>())
    private var useWhitelist by prefOption("ai_reply_use_group_whitelist", true)

    // 私聊黑白名单 — 与群聊黑白名单完全独立存储
    private var privateChatMode by prefOption("ai_reply_private_mode", 0) // 0=全部, 1=白名单, 2=黑名单
    private var privateEnabledContacts by prefOption("ai_reply_private_contacts", emptySet<String>())
    private var allowStrangerPrivateReply by prefOption("ai_reply_allow_stranger", false)

    private val tasks = AutoReplyTasks()
    private val requestSlots = Semaphore(2)
    @Volatile private var enabledAt = Long.MAX_VALUE

    internal fun beginManual(talker: String): () -> Unit = tasks.beginManual(talker)

    // ── 调试日志 ────────────────────────────────────────────────────────────────
    data class DebugLogEntry(
        val timestamp: String,
        val requestUrl: String,
        val requestBody: String,
        val responseCode: Int,
        val responseBody: String,
        val error: String?
    )

    val debugLogs = Collections.synchronizedList(mutableListOf<DebugLogEntry>())
    private const val MAX_DEBUG_LOGS = 50

    private val json = Json { ignoreUnknownKeys = true }

    private sealed class ModelFetchResult {
        data class Success(val models: List<String>) : ModelFetchResult()
        data class Error(val message: String) : ModelFetchResult()
    }

    // ── 屏幕状态与用户干预检测 ──────────────────────────────────────────────────
    /**
     * 检查当前屏幕是否亮起。熄屏时不应该自动回复，避免暴露对话内容。
     * 使用 ActivityThread.currentApplication() 获取宿主 Application Context。
     */
    private fun isScreenOn(): Boolean {
        return try {
            val app = android.app.ActivityThread.currentApplication() ?: return false
            val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isInteractive == true
        } catch (_: Throwable) { false }
    }

    private suspend fun fetchModelList(apiUrl: String, apiKey: String): ModelFetchResult = try {
        val request = Request.Builder().url(SmartReplyClient.endpoint(apiUrl, models = true))
            .header("Authorization", "Bearer ${apiKey.trim()}").build()
        val ids = SmartReplyClient.parseModelIds(SmartReplyClient.request(request, SmartReplyClient.Owner.MODELS))
        if (ids.isEmpty()) ModelFetchResult.Error("接口未返回可用模型，可手动填写")
        else ModelFetchResult.Success(ids)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ModelFetchResult.Error(SmartReplyClient.errorText(e))
    }

    enum class TriggerMode(val value: Int, val description: String) {
        AT_ONLY(0, "仅被 @ 时回复"),
        KEYWORD(1, "包含关键词时回复"),
        ALL(2, "群内任何消息都回复")
    }

    enum class PrivateChatMode(val value: Int, val description: String) {
        ALL(0, "全部人员"),
        WHITELIST(1, "白名单模式"),
        BLACKLIST(2, "黑名单模式")
    }

    override fun onEnable() {
        enabledAt = System.currentTimeMillis()
        tasks.enable()
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        tasks.disable()
        SmartReplyClient.cancel(SmartReplyClient.Owner.AUTO)
        WeDatabaseListenerApi.removeListener(this)
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var localContext by remember { mutableStateOf(contextEnabled) }
            var localApiUrl by remember { mutableStateOf(apiUrl) }
            var localApiKey by remember { mutableStateOf(apiKey) }
            var localModel by remember { mutableStateOf(model) }
            var localPrompt by remember { mutableStateOf(systemPrompt) }
            var localEnablePrivate by remember { mutableStateOf(enableForPrivate) }
            var localEnableGroup by remember { mutableStateOf(enableForGroup) }
            var localKeyword by remember { mutableStateOf(groupTriggerKeyword) }
            var localPrefix by remember { mutableStateOf(replyPrefix) }
            var localDelayMs by remember { mutableStateOf(replyDelay) }
            var localTriggerMode by remember { mutableStateOf(triggerMode) }
            var localUseWhitelist by remember { mutableStateOf(useWhitelist) }
            var showGroupSelector by remember { mutableStateOf(false) }
            var showPrivateContactSelector by remember { mutableStateOf(false) }
            var showFavMenu by remember { mutableStateOf(false) }
            var localPrivateChatMode by remember { mutableStateOf(privateChatMode) }
            var localAllowStranger by remember { mutableStateOf(allowStrangerPrivateReply) }
            var showDebugLog by remember { mutableStateOf(false) }
            var fetchedModels by remember { mutableStateOf(listOf<String>()) }
            var fetchingModels by remember { mutableStateOf(false) }
            var modelFetchError by remember { mutableStateOf<String?>(null) }

            val scope = rememberCoroutineScope()

            fun doFetchModels(url: String, key: String) {
                val u = url.trim()
                val k = key.trim()
                if (u.isBlank() || k.isBlank()) return
                fetchingModels = true
                modelFetchError = null
                scope.launch(Dispatchers.IO) {
                    val result = fetchModelList(u, k)
                    withContext(Dispatchers.Main) {
                        fetchingModels = false
                        if (localApiUrl.trim() != u || localApiKey.trim() != k) return@withContext
                        when (result) {
                            is ModelFetchResult.Success -> {
                                fetchedModels = result.models
                                if (localModel.isBlank() && result.models.isNotEmpty()) {
                                    localModel = result.models.first()
                                }
                                showToast("已获取 ${result.models.size} 个可用模型")
                            }
                            is ModelFetchResult.Error -> {
                                modelFetchError = result.message
                                showToast(result.message)
                            }
                        }
                    }
                }
            }

            val delayPresets = remember {
                listOf(
                    0 to "立即",
                    1000 to "1秒",
                    2000 to "2秒",
                    3000 to "3秒",
                    5000 to "5秒",
                    10000 to "10秒"
                )
            }

            if (showGroupSelector) {
                GroupSelectorScreen(
                    onDismiss = { showGroupSelector = false },
                    useWhitelist = localUseWhitelist,
                    onSave = { groups ->
                        enabledGroups = groups
                        showToast("已保存 ${groups.size} 个群聊")
                        showGroupSelector = false
                    }
                )
            } else if (showPrivateContactSelector) {
                PrivateContactSelectorScreen(
                    onDismiss = { showPrivateContactSelector = false },
                    useWhitelist = localPrivateChatMode == 1,
                    onSave = { contacts ->
                        privateEnabledContacts = contacts
                        showToast("已保存 ${contacts.size} 个联系人")
                        showPrivateContactSelector = false
                    }
                )
            } else if (showDebugLog) {
                DebugLogViewerScreen(
                    onDismiss = { showDebugLog = false }
                )
            } else {
                AlertDialogContent(
                    title = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("AI 自动回复设置")
                            Box {
                                IconButton(onClick = { showFavMenu = true }) {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.More_vert,
                                        contentDescription = "更多"
                                    )
                                }
                                DropdownMenu(
                                    expanded = showFavMenu,
                                    onDismissRequest = { showFavMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("从收藏中选择回复") },
                                        onClick = {
                                            showFavMenu = false
                                            // TODO: 接入 WeMessageApi 或新增 FavApi 获取微信收藏列表
                                            //       当前项目中没有可用的微信收藏读取 API，待后续实现
                                            showToast("暂未接入微信收藏 API")
                                        }
                                    )
                                }
                            }
                        }
                    },
                    text = {
                        DefaultColumn(Modifier.padding(vertical = 8.dp), scrollable = true) {
                            Text("API 配置", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "启用后会将符合规则的文本发送到你配置的 API；模型列表只在点击拉取时请求。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = localApiUrl,
                                onValueChange = { localApiUrl = it },
                                label = { Text("API 地址") },
                                supportingText = { Text("请填写 HTTPS 地址，可填完整 /chat/completions 地址或 /v1 基址") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = localApiKey,
                                onValueChange = { localApiKey = it },
                                label = { Text("API Key") },
                                visualTransformation = PasswordVisualTransformation(),
                                supportingText = { Text("自动去除首尾空格和不可见字符") },
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = localModel,
                                onValueChange = { localModel = it },
                                label = { Text("模型名称") },
                                singleLine = true
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(
                                    onClick = { doFetchModels(localApiUrl, localApiKey) },
                                    enabled = !fetchingModels && localApiUrl.isNotBlank() && localApiKey.isNotBlank()
                                ) {
                                    Text(if (fetchingModels) "拉取中…" else "拉取可用模型")
                                }
                                if (fetchingModels) {
                                    Text("正在请求模型列表…", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            modelFetchError?.let { err ->
                                Text(
                                    err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            if (fetchedModels.isNotEmpty()) {
                                Text(
                                    "可用模型（点击选择）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    fetchedModels.forEach { m ->
                                        FilterChip(
                                            selected = localModel == m,
                                            onClick = { localModel = m },
                                            label = { Text(m) }
                                        )
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = localPrompt,
                                onValueChange = { localPrompt = it },
                                label = { Text("系统提示词") },
                                maxLines = 3
                            )

                            Spacer(Modifier.padding(top = 12.dp))
                            Text("回复设置", style = MaterialTheme.typography.titleSmall)

                            ListItem(
                                modifier = Modifier.clickable { localEnablePrivate = !localEnablePrivate },
                                trailingContent = {
                                    Switch(checked = localEnablePrivate, onCheckedChange = null)
                                },
                                headlineContent = { Text("私聊自动回复") }
                            )

                            if (localEnablePrivate) {
                                Text(
                                    "私聊模式",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                                PrivateChatMode.values().forEach { mode ->
                                    ListItem(
                                        modifier = Modifier.clickable { localPrivateChatMode = mode.value },
                                        trailingContent = {
                                            Text(if (localPrivateChatMode == mode.value) "✓" else "")
                                        },
                                        headlineContent = { Text(mode.description) },
                                        supportingContent = {
                                            Text(
                                                when (mode) {
                                                    PrivateChatMode.ALL -> "所有私聊正常触发回复"
                                                    PrivateChatMode.WHITELIST -> "仅名单内好友触发私聊自动回复"
                                                    PrivateChatMode.BLACKLIST -> "名单内好友不会触发回复，其余正常生效"
                                                }
                                            )
                                        }
                                    )
                                }

                                if (localPrivateChatMode != PrivateChatMode.ALL.value) {
                                    val modeLabel = if (localPrivateChatMode == PrivateChatMode.WHITELIST.value) "白名单" else "黑名单"
                                    ListItem(
                                        modifier = Modifier.clickable { showPrivateContactSelector = true },
                                        headlineContent = { Text("选择${modeLabel}联系人") },
                                        supportingContent = { Text("当前已选 ${privateEnabledContacts.size} 个联系人") }
                                    )
                                }

                                ListItem(
                                    modifier = Modifier.clickable { localAllowStranger = !localAllowStranger },
                                    trailingContent = {
                                        Switch(checked = localAllowStranger, onCheckedChange = null)
                                    },
                                    headlineContent = { Text("允许陌生人临时私聊触发") },
                                    supportingContent = { Text("开启后，非好友的临时会话也可触发自动回复") }
                                )
                            }
                            ListItem(
                                modifier = Modifier.clickable { localEnableGroup = !localEnableGroup },
                                trailingContent = {
                                    Switch(checked = localEnableGroup, onCheckedChange = null)
                                },
                                headlineContent = { Text("群聊自动回复") }
                            )

                            if (localEnableGroup) {
                                Text(
                                    "触发条件",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 8.dp)
                                )

                                TriggerMode.values().forEach { mode ->
                                    ListItem(
                                        modifier = Modifier.clickable { localTriggerMode = mode.value },
                                        trailingContent = {
                                            Text(if (localTriggerMode == mode.value) "✓" else "")
                                        },
                                        headlineContent = { Text(mode.description) }
                                    )
                                }

                                if (localTriggerMode == TriggerMode.KEYWORD.value) {
                                    OutlinedTextField(
                                        value = localKeyword,
                                        onValueChange = { localKeyword = it },
                                        label = { Text("触发关键词") },
                                        singleLine = true
                                    )
                                }

                                ListItem(
                                    modifier = Modifier.clickable { localUseWhitelist = !localUseWhitelist },
                                    trailingContent = {
                                        Switch(checked = localUseWhitelist, onCheckedChange = null)
                                    },
                                    headlineContent = { Text(if (localUseWhitelist) "白名单模式" else "黑名单模式") },
                                    supportingContent = { Text(if (localUseWhitelist) "仅在选中的群聊中回复" else "在除选中群聊外的所有群聊中回复") }
                                )

                                ListItem(
                                    modifier = Modifier.clickable { showGroupSelector = true },
                                    headlineContent = { Text("选择群聊") },
                                    supportingContent = { Text("当前已选 ${enabledGroups.size} 个群聊") }
                                )
                            }

                            OutlinedTextField(
                                value = localPrefix,
                                onValueChange = { localPrefix = it },
                                label = { Text("回复前缀（可留空）") },
                                singleLine = true
                            )

                            ListItem(
                                modifier = Modifier.clickable { localContext = !localContext },
                                headlineContent = { Text("携带最近 6 条文本上下文") },
                                supportingContent = { Text("仅发送触发消息之前的记录，默认关闭") },
                                trailingContent = { Switch(checked = localContext, onCheckedChange = null) },
                            )
                            Text("自动回复：每会话请求间隔至少 15 秒，全局每分钟最多 10 次；失败不自动重试。")
                            Text("回复延迟", style = MaterialTheme.typography.titleSmall)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                delayPresets.forEach { (ms, label) ->
                                    FilterChip(
                                        selected = localDelayMs == ms,
                                        onClick = { localDelayMs = ms },
                                        label = { Text(label) }
                                    )
                                }
                            }

                            Spacer(Modifier.padding(top = 12.dp))
                            Button(
                                onClick = { showDebugLog = true },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("调试日志") }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = onDismiss) { Text("取消") }
                    },
                    confirmButton = {
                        Button(onClick = {
                            tasks.disable()
                            if (configuredEnabled) tasks.enable()
                            contextEnabled = localContext
                            apiUrl = localApiUrl.trim()
                            apiKey = localApiKey.trim()
                            model = localModel
                            systemPrompt = localPrompt
                            enableForPrivate = localEnablePrivate
                            enableForGroup = localEnableGroup
                            groupTriggerKeyword = localKeyword
                            replyPrefix = localPrefix
                            replyDelay = localDelayMs.coerceIn(0, 10000)
                            triggerMode = localTriggerMode
                            useWhitelist = localUseWhitelist
                            privateChatMode = localPrivateChatMode
                            allowStrangerPrivateReply = localAllowStranger
                            showToast("设置已保存")
                            onDismiss()
                        }) { Text("保存") }
                    }
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun GroupSelectorScreen(
        onDismiss: () -> Unit,
        useWhitelist: Boolean,
        onSave: (Set<String>) -> Unit
    ) {
        val groups = remember {
            WeDatabaseApi.getGroups().filter { it.wxId.isNotBlank() }
        }
        val selected = remember { enabledGroups.toMutableSet() }
        val listState = rememberLazyListState()

        AlertDialogContent(
            title = { Text("选择群聊") },
            text = {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item {
                        Text(
                            if (useWhitelist) "选择需要开启 AI 自动回复的群聊" else "选择需要排除 AI 自动回复的群聊",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    items(groups, key = { it.wxId }) { group ->
                        val isSelected = remember { mutableStateOf(selected.contains(group.wxId)) }
                        ListItem(
                            modifier = Modifier.clickable {
                                isSelected.value = !isSelected.value
                                if (isSelected.value) {
                                    selected.add(group.wxId)
                                } else {
                                    selected.remove(group.wxId)
                                }
                            },
                            headlineContent = { Text(group.displayName) },
                            supportingContent = { Text(group.wxId) },
                            trailingContent = {
                                Text(if (isSelected.value) "✓" else "")
                            }
                        )
                    }
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
            confirmButton = {
                Button(onClick = {
                    onSave(selected)
                    onDismiss()
                }) { Text("保存") }
            }
        )}

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun PrivateContactSelectorScreen(
        onDismiss: () -> Unit,
        useWhitelist: Boolean,
        onSave: (Set<String>) -> Unit
    ) {
        val allContacts = remember {
            WeDatabaseApi.getFriends().filter { it.wxId.isNotBlank() }
        }
        var query by remember { mutableStateOf("") }
        val filteredContacts = remember(allContacts, query) {
            val q = query.trim()
            if (q.isEmpty()) allContacts
            else allContacts.filter {
                it.displayName.contains(q, ignoreCase = true) || it.wxId.contains(q, ignoreCase = true)
            }
        }
        val selected = remember { privateEnabledContacts.toMutableSet() }
        val listState = rememberLazyListState()

        AlertDialogContent(
            title = { Text("选择联系人") },
            text = {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item {
                        Text(
                            if (useWhitelist) "选择需要开启 AI 自动回复的联系人" else "选择需要排除 AI 自动回复的联系人",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("搜索昵称 / 微信号") },
                            singleLine = true
                        )
                    }
                    if (filteredContacts.isEmpty()) {
                        item {
                            Text(
                                "未找到匹配的联系人",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                    items(filteredContacts, key = { it.wxId }) { contact ->
                        val isSelected = remember { mutableStateOf(selected.contains(contact.wxId)) }
                        ListItem(
                            modifier = Modifier.clickable {
                                isSelected.value = !isSelected.value
                                if (isSelected.value) {
                                    selected.add(contact.wxId)
                                } else {
                                    selected.remove(contact.wxId)
                                }
                            },
                            headlineContent = { Text(contact.displayName) },
                            supportingContent = { Text(contact.wxId) },
                            trailingContent = {
                                Text(if (isSelected.value) "✓" else "")
                            }
                        )
                    }
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
            confirmButton = {
                Button(onClick = {
                    onSave(selected)
                    onDismiss()
                }) { Text("保存") }
            }
        )}

    // ── 调试日志查看器 ────────────────────────────────────────────────────────
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DebugLogViewerScreen(
        onDismiss: () -> Unit
    ) {
        val logs = remember { synchronized(debugLogs) { debugLogs.toList() } }
        val listState = rememberLazyListState()

        AlertDialogContent(
            title = { Text("调试日志 (最近 ${logs.size} 条)") },
            text = {
                if (logs.isEmpty()) {
                    Text("暂无日志记录", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(logs.size) { index ->
                            val log = logs[index]
                            DefaultColumn {
                                Text(
                                    "[${log.timestamp}]",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(log.error.orEmpty(), style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("关闭") }
            },
            confirmButton = {
                Button(onClick = {
                    debugLogs.clear()
                    showToast("日志已清除")
                    onDismiss()
                }) { Text("清除日志") }
            }
        )
    }

    private data class ApiConfig(val url: String, val key: String, val model: String, val prompt: String,
        val prefix: String, val context: Boolean)

    private fun config() = ApiConfig(apiUrl.trim(), apiKey.trim(), model.trim(), systemPrompt, replyPrefix, contextEnabled)

    private fun fresh(msg: MessageInfo): Boolean {
        val time = msg.createTime
        return time >= enabledAt - 2000 && System.currentTimeMillis() - time in -10_000L..120_000L
    }

    private fun allowed(msg: MessageInfo, stranger: Boolean): Boolean {
        if (!configuredEnabled || !isScreenOn() || !fresh(msg)) return false
        if (msg.isInGroupChat) {
            if (!enableForGroup || (useWhitelist && msg.talker !in enabledGroups) ||
                (!useWhitelist && msg.talker in enabledGroups)) return false
            return when (triggerMode) {
                TriggerMode.AT_ONLY.value -> runCatching { msg.isAtMe }.getOrDefault(false)
                TriggerMode.KEYWORD.value -> groupTriggerKeyword.isNotBlank() && msg.actualContent.contains(groupTriggerKeyword)
                TriggerMode.ALL.value -> true
                else -> false
            }
        }
        if (!enableForPrivate || msg.isOfficialAccount) return false
        if (stranger) return allowStrangerPrivateReply
        return when (privateChatMode) {
            PrivateChatMode.ALL.value -> true
            PrivateChatMode.WHITELIST.value -> msg.sender in privateEnabledContacts
            PrivateChatMode.BLACKLIST.value -> msg.sender !in privateEnabledContacts
            else -> false
        }
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message" || !configuredEnabled) return
        val msg = runCatching { MessageInfo.fromContentValues(values) }.getOrNull() ?: return
        if (msg.isSelfSender) {
            tasks.intervene(msg.talker)
            return
        }
        if (msg.type?.isText != true || !fresh(msg)) return
        if (msg.isOfficialAccount) return
        val cfg = config()
        if (cfg.key.isBlank() || cfg.model.isBlank() || msg.actualContent.length > 8000) return
        tasks.schedule(msg.talker, msg.id) { ticket ->
            try {
                fun permitted(): Boolean = tasks.current(ticket) && cfg == config() &&
                    allowed(msg, !msg.isInGroupChat && WeDatabaseApi.getFriend(msg.sender) == null)
                if (!permitted()) return@schedule
                delay(maxOf(replyDelay.coerceIn(0, 10_000).toLong(), tasks.cooldown(ticket)))
                requestSlots.withPermit {
                    if (!permitted()) return@withPermit
                    val clean = when {
                        msg.isInGroupChat && triggerMode == TriggerMode.AT_ONLY.value ->
                            msg.actualContent.replace("@${WeApi.selfWxId}", "")
                                .replace("@${WeDatabaseApi.getDisplayName(WeApi.selfWxId)}", "").trim()
                        msg.isInGroupChat && triggerMode == TriggerMode.KEYWORD.value ->
                            msg.actualContent.replace(groupTriggerKeyword, "").trim()
                        else -> msg.actualContent.trim()
                    }
                    if (clean.isBlank()) return@withPermit
                    val history = if (cfg.context) ReplyContext.load(msg.talker, msg.id, msg.createTime, 6) else emptyList()
                    if (!permitted() || !tasks.reserveRequest(ticket)) return@withPermit
                    val reply = callAI(cfg, clean, history)
                    if (reply.isBlank() || reply.length > 4000 || !permitted()) return@withPermit
                    // Final checks are adjacent to enqueueing. Do not retry uncertain send results.
                    val stranger = !msg.isInGroupChat && WeDatabaseApi.getFriend(msg.sender) == null
                    withContext(Dispatchers.Main) {
                        if (tasks.current(ticket) && cfg == config() && allowed(msg, stranger)) {
                            val sent = WeMessageApi.sendAiReply(msg.talker, cfg.prefix + reply)
                            addDebugLog(if (sent) "发送已入队" else "发送结果未确认，不补发")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addDebugLog(SmartReplyClient.errorText(e))
            }
        }
    }

    private suspend fun callAI(cfg: ApiConfig, text: String, history: List<Pair<String, String>>): String {
        val body = buildJsonObject {
            put("model", cfg.model)
            put("stream", false)
            put("messages", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject { put("role", "system"); put("content", cfg.prompt) })
                history.forEach { (role, content) ->
                    add(buildJsonObject { put("role", role); put("content", content) })
                }
                add(buildJsonObject { put("role", "user"); put("content", text) })
            })
        }
        val request = Request.Builder().url(SmartReplyClient.endpoint(cfg.url))
            .header("Authorization", "Bearer ${cfg.key}")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return parseAIResponse(SmartReplyClient.request(request, SmartReplyClient.Owner.AUTO))
    }

    private fun addDebugLog(status: String) {
        val entry = DebugLogEntry(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()),
            "已隐藏", "聊天内容不记录", 0, "", status,
        )
        synchronized(debugLogs) {
            debugLogs.add(entry)
            while (debugLogs.size > MAX_DEBUG_LOGS) debugLogs.removeAt(0)
        }
    }

    private fun parseAIResponse(response: String): String {
        return try {
            val jsonObj = json.parseToJsonElement(response).jsonObject
            val choices = jsonObj["choices"]?.jsonArray
            if (choices.isNullOrEmpty()) return ""

            val firstChoice = choices[0].jsonObject
            val message = firstChoice["message"]?.jsonObject
            message?.get("content")?.jsonPrimitive?.contentOrNull?.trim() ?: ""
        } catch (e: Exception) {
            WeLogger.w(TAG, "AI response format unsupported; content omitted")
            ""
        }
    }
}
