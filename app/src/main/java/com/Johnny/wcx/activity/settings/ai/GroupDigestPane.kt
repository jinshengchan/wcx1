package com.Johnny.wcx.activity.settings.ai

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.ai.group.GroupDigest
import com.Johnny.wcx.ai.group.GroupDigestConfig
import com.Johnny.wcx.ai.group.GroupDigestRunner
import com.Johnny.wcx.ai.group.GroupDigestScheduler
import com.Johnny.wcx.ai.group.GroupDigestStore
import com.Johnny.wcx.ai.group.MAX_INTERVAL_MINUTES
import com.Johnny.wcx.ai.group.MIN_INTERVAL_MINUTES
import com.Johnny.wcx.ai.group.PRESET_INTERVALS
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.features.ai.AiConfig.Purpose
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.models.IWeContact
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.utils.showComposeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun GroupDigestPane(
    context: Context,
    account: String,
) {
    var groupId by remember(account) { mutableStateOf<String?>(null) }
    var providerRevision by remember { mutableIntStateOf(0) }
    var listRevision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    AiProviderChoice(Purpose.GROUP_DIGEST, providerRevision) {
        changeAiProvider(context, account, Purpose.GROUP_DIGEST, it) { providerRevision++ }
    }
    val selected = groupId
    if (selected != null) {
        TextButton(onClick = { groupId = null }) { Text("返回群组列表") }
        key(account, selected) {
            GroupDigestDetail(context, account, selected, providerRevision) { groupId = null }
        }
        return
    }

    val groups by loadDigestGroups(account, listRevision)
    val scope = rememberCoroutineScope()
    TextButton(onClick = {
        showAddDigestGroups(context, account) { added, requested ->
            message = if (added == requested) "已添加 $added 个群聊" else "已添加 $added 个群聊，部分群聊保存失败。"
            listRevision++
        }
    }) { Text("添加群聊") }
    if (message.isNotBlank()) WorkbenchStatus(message)
    GroupDigestGroupList(
        groups, "请先添加群聊。",
        onRemove = { group ->
            confirmRemoveGroup(context, group.name) {
                scope.launch {
                    val removed = withContext(Dispatchers.IO) { GroupDigestStore.deleteConfig(group.groupId, account) }
                    message = if (removed) "已移除「${group.name}」" else "无法移除群聊。"
                    listRevision++
                }
            }
        },
    ) { groupId = it.groupId }
}

private fun confirmRemoveGroup(context: Context, name: String, onConfirm: () -> Unit) =
    confirmAiAction(context, "移除群聊", "移除「$name」会删除其摘要设置和历史。", "移除", onConfirm)

private fun showAddDigestGroups(
    context: Context,
    account: String,
    onAdded: (Int, Int) -> Unit,
) = showComposeDialog(context) {
    val groups by produceState<List<IWeContact>?>(null, account) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                if (GroupDigestStore.accountId() != account) return@runCatching emptyList()
                val existing = GroupDigestStore.loadGroups(account).mapTo(HashSet()) { it.groupId }
                WeDatabaseApi.getGroups().filterNot { it.wxId in existing }
                    .takeIf { GroupDigestStore.accountId() == account }.orEmpty()
            }.getOrDefault(emptyList())
        }
    }
    val options = groups
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    when {
        options.isNullOrEmpty() || saving -> AlertDialogContent(
            title = { Text("添加群聊") },
            text = {
                Text(when {
                    saving -> "正在添加群聊…"
                    options == null -> "正在读取群聊…"
                    else -> "没有可添加的群聊。"
                })
            },
            confirmButton = { if (!saving) TextButton(onClick = onDismiss) { Text("关闭") } },
        )
        else -> ContactsSelector("添加群聊", options, emptySet(), onDismiss) { ids ->
            if (ids.isNotEmpty() && !saving) {
                saving = true
                scope.launch {
                    val added = withContext(Dispatchers.IO) {
                        options.count {
                            it.wxId in ids && GroupDigestStore.saveConfig(
                                GroupDigestConfig(
                                    groupId = it.wxId,
                                    name = it.displayName,
                                    avatarUrl = it.avatarUrl,
                                ),
                                account,
                            )
                        }
                    }
                    onAdded(added, ids.size)
                    onDismiss()
                }
            }
        }
    }
}

@Composable
private fun GroupDigestDetail(
    context: Context,
    account: String,
    id: String,
    providerRevision: Int,
    onRemoved: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var config by remember { mutableStateOf<GroupDigestConfig?>(null) }
    var loading by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    LaunchedEffect(revision) {
        while (true) {
            val state = withContext(Dispatchers.IO) {
                GroupDigestStore.markRead(id, account) to GroupDigestRunner.isRunning(id, account)
            }
            config = state.first
            running = state.second
            loading = false
            delay(if (running) 500 else 1500)
        }
    }
    val saved = config
    if (saved == null) {
        WorkbenchStatus(if (loading) "正在读取群聊摘要…" else "群聊已移除或暂时无法读取。")
        return
    }
    val name = saved.name.ifBlank { id }
    val binding = remember(providerRevision) { AiConfig.selection(Purpose.GROUP_DIGEST)?.takeIf { it.account == account }?.id }
    val authorized = binding != null && saved.scheduleBinding == binding

    fun changeSchedule(enabled: Boolean, intervalMinutes: Int, expected: String? = null) {
        saving = true
        scope.launch {
            message = withContext(Dispatchers.IO) {
                val currentBinding = AiConfig.bindingId(Purpose.GROUP_DIGEST)
                when {
                    GroupDigestStore.accountId() != account -> "微信账号已切换，请重新打开工作台。"
                    enabled && (expected == null || expected != currentBinding) -> "模型配置已变化，请重新确认。"
                    else -> {
                        val config = GroupDigestStore.update(id, account = account) {
                            it.copy(
                                enabled = enabled,
                                intervalMinutes = intervalMinutes,
                                anchorAt = if (enabled) System.currentTimeMillis() else 0,
                                scheduleBinding = expected.orEmpty(),
                            )
                        }
                        if (config == null) {
                            "定时设置保存失败。"
                        } else {
                            if (!enabled) GroupDigestScheduler.cancel(id, account)
                            ""
                        }
                    }
                }
            }
            saving = false
            revision++
        }
    }

    fun enableSchedule(intervalMinutes: Int) {
        val destination = AiConfig.selection(Purpose.GROUP_DIGEST)?.takeIf { it.account == account } ?: return
        val sameAuthorization = saved.enabled && saved.scheduleBinding == destination.id
        val start = if (sameAuthorization) "沿用原授权的起点" else "从本次授权起"
        confirmAiAction(
            context,
            if (sameAuthorization) "更改摘要频率" else "启用定时摘要",
            "每 ${intervalLabel(intervalMinutes)}处理群聊「$name」的消息。${start}读取尚未覆盖的群聊记录，最多回看 7 天。范围内的文字、消息时间和发言人名称会自动发送给 ${destination.displayName()}，$MEDIA_PLACEHOLDERS。可随时关闭定时。",
            if (sameAuthorization) "保存频率" else "启用定时",
        ) { changeSchedule(true, intervalMinutes, destination.id) }
    }

    fun runNow() {
        val destination = AiConfig.selection(Purpose.GROUP_DIGEST)?.takeIf { it.account == account } ?: return
        val expected = destination.id
        val window = saved
        val fromSec = window.windowSec(System.currentTimeMillis()).first
        val since = timeLabel(fromSec * 1000L)
        confirmAiAction(
            context,
            "生成群聊摘要",
            "将群聊「$name」从约 $since 到开始生成时的文字、消息时间和发言人名称发送给 ${destination.displayName()}，$MEDIA_PLACEHOLDERS。",
            "开始生成",
        ) {
            saving = true
            scope.launch {
                message = withContext(Dispatchers.IO) {
                    val current = GroupDigestStore.readConfig(id, account)
                    when {
                        GroupDigestStore.accountId() != account -> "微信账号已切换，请重新打开工作台。"
                        AiConfig.bindingId(Purpose.GROUP_DIGEST) != expected -> "模型配置已变化，请重新确认。"
                        current == null -> "群聊已移除或暂时无法读取。"
                        current.recordId != window.recordId ||
                            current.lastSlotEnd != window.lastSlotEnd || current.intervalMinutes != window.intervalMinutes ->
                            "摘要的消息范围已变化，请重新确认。"
                        GroupDigestRunner.start(
                            id, manual = true, account = account, expectedBinding = expected, notBeforeSec = fromSec
                        ) -> ""
                        else -> "已有摘要任务正在运行，或当前无法开始。"
                    }
                }
                saving = false
                revision++
            }
        }
    }

    fun removeGroup() {
        confirmRemoveGroup(context, name) {
            saving = true
            scope.launch {
                val removed = withContext(Dispatchers.IO) { GroupDigestStore.deleteConfig(id, account) }
                saving = false
                if (removed) onRemoved() else message = "无法移除群聊。"
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(name, style = MaterialTheme.typography.titleMedium)
            if (message.isNotBlank()) WorkbenchStatus(message)
            if (!loading) {
                val idle = !saving && !running
                if (running) WorkbenchStatus("正在生成摘要…")
                if (saved.lastError.isNotBlank() && !running) WorkbenchStatus(saved.lastError)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = ::runNow, enabled = idle && binding != null) { Text("生成摘要") }
                    if (running) {
                        TextButton(onClick = {
                            GroupDigestRunner.cancel(id, account)
                            revision++
                        }, enabled = !saving) { Text("取消生成") }
                    }
                }
                FrequencyChoice(saved.intervalMinutes, idle && (!saved.enabled || binding != null)) { minutes ->
                    if (saved.enabled) enableSchedule(minutes) else changeSchedule(false, minutes)
                }
                when {
                    saved.enabled && authorized ->
                        WorkbenchStatus("定时已开启 · 下次 ${timeLabel(saved.nextRunAt(System.currentTimeMillis()))}")
                    saved.enabled -> WorkbenchStatus("定时已暂停。请选择模型并重新确认。")
                    else -> WorkbenchStatus("定时已关闭")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!saved.enabled || !authorized) {
                        TextButton(enabled = idle && binding != null, onClick = { enableSchedule(saved.intervalMinutes) }) {
                            Text(if (saved.enabled) "重新确认定时" else "开启定时")
                        }
                    }
                    if (saved.enabled) {
                        TextButton(enabled = !saving, onClick = { changeSchedule(false, saved.intervalMinutes) }) {
                            Text("关闭定时")
                        }
                    }
                }
                AiWorkGroupSettings(context, account, saved)
                TextButton(enabled = !saving, onClick = ::removeGroup) { Text("移除群聊") }
            }
        }
        if (!loading) {
            item {
                HorizontalDivider()
                Text("历史摘要", style = MaterialTheme.typography.titleSmall)
                if (saved.digests.isEmpty()) WorkbenchStatus("暂无摘要")
            }
            items(saved.digests.asReversed(), key = { it.id }) { digest -> DigestCard(digest, collapsible = true) }
        }
    }
}

@Composable
private fun FrequencyChoice(current: Int, enabled: Boolean, choose: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    var hours by remember { mutableStateOf("") }
    Column {
        TextButton(enabled = enabled, onClick = { expanded = true }) {
            Text("频率：每 ${intervalLabel(current)}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PRESET_INTERVALS.forEach { minutes ->
                DropdownMenuItem(
                    text = { Text("每 ${intervalLabel(minutes)}") },
                    onClick = {
                        expanded = false
                        custom = false
                        if (minutes != current) choose(minutes)
                    },
                )
            }
            DropdownMenuItem(text = { Text("自定义频率") }, onClick = {
                expanded = false
                custom = true
                hours = (current / 60).toString()
            })
        }
        if (custom) {
            OutlinedTextField(
                value = hours,
                onValueChange = { hours = it.filter(Char::isDigit).take(3) },
                label = { Text("小时（1～168）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = enabled,
            )
            val minutes = hours.toIntOrNull()?.times(60)
                ?.takeIf { it in MIN_INTERVAL_MINUTES..MAX_INTERVAL_MINUTES }
            TextButton(enabled = enabled && minutes != null, onClick = {
                custom = false
                if (minutes != null && minutes != current) choose(minutes)
            }) { Text("确认频率") }
        }
    }
}

@Composable
internal fun DigestCard(digest: GroupDigest, collapsible: Boolean = false) {
    var expanded by remember(digest.id) { mutableStateOf(!collapsible) }
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(digest.headline, style = MaterialTheme.typography.titleSmall)
            WorkbenchStatus(timeLabel(digest.createdAt) + if (digest.manual) " · 手动" else "")
            WorkbenchStatus("${timeLabel(digest.fromSec * 1000L)} 至 ${timeLabel(digest.toSec * 1000L)} · ${digest.messageCount} 条")
            if (expanded) {
                if (digest.points.isNotEmpty()) {
                    Text("要点", style = MaterialTheme.typography.labelLarge)
                    digest.points.forEach { Text("• $it") }
                }
                if (digest.todos.isNotEmpty()) {
                    Text("待办", style = MaterialTheme.typography.labelLarge)
                    digest.todos.forEach { Text("• $it") }
                }
            }
            if (digest.sampled) WorkbenchStatus("消息较多，本条摘要按最近的部分消息生成。")
        }
    }
    if (collapsible) {
        OutlinedCard(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) { content() }
    } else {
        OutlinedCard(Modifier.fillMaxWidth()) { content() }
    }
}

internal fun intervalLabel(minutes: Int): String = when {
    minutes % 1440 == 0 -> "${minutes / 1440} 天"
    minutes % 60 == 0 -> "${minutes / 60} 小时"
    else -> "$minutes 分钟"
}
