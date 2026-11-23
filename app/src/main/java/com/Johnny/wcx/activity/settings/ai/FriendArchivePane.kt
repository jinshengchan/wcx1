package com.Johnny.wcx.activity.settings.ai

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.ai.AiAuditPlan
import com.Johnny.wcx.ai.AiMemoryIndex
import com.Johnny.wcx.ai.AiProfile
import com.Johnny.wcx.ai.AiProfileAudit
import com.Johnny.wcx.ai.AiProfileInitializer
import com.Johnny.wcx.ai.AiProfileState
import com.Johnny.wcx.ai.AiProfileStore
import com.Johnny.wcx.ai.AiTools
import com.Johnny.wcx.ai.ProfileChange
import com.Johnny.wcx.ai.recorded
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.features.ai.AiConfig.Purpose
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.models.IWeContact
import com.Johnny.wcx.features.api.core.models.WeContact
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.utils.showComposeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

@Composable
internal fun FriendArchivePane(
    context: Context,
    account: String,
    friend: IWeContact?,
    select: (IWeContact) -> Unit,
) {
    var providerRevision by remember { mutableIntStateOf(0) }
    AiProviderChoice(Purpose.FRIEND_PROFILE, providerRevision) {
        changeAiProvider(context, account, Purpose.FRIEND_PROFILE, it) { providerRevision++ }
    }
    TextButton(onClick = {
        chooseAiContact(context, "选择好友", friend?.wxId, {
            if (WeDatabaseApi.currentAiAccount() == account) WeDatabaseApi.getFriends() else emptyList()
        }) {
            if (WeApi.selfWxId == account) select(it)
        }
    }) {
        Text(friend?.let { "好友：${it.displayName.ifBlank { it.wxId }}" } ?: "选择好友")
    }
    if (friend == null) return

    key(account, friend.wxId) {
        FriendArchiveDetail(context, account, friend, providerRevision)
    }
}

@Composable
private fun FriendArchiveDetail(
    context: Context,
    account: String,
    friend: IWeContact,
    providerRevision: Int,
) {
    val wxId = friend.wxId
    val name = friend.displayName.ifBlank { wxId }
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<AiProfile?>(null) }
    var loading by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var applying by remember { mutableStateOf(false) }
    var fromYear by remember { mutableStateOf<Int?>(null) }
    var onlyEvents by remember { mutableStateOf(false) }
    val configured = remember(providerRevision) { AiConfig.selection(Purpose.FRIEND_PROFILE)?.account == account }
    LaunchedEffect(revision) {
        while (true) {
            val state = withContext(Dispatchers.IO) {
                AiProfileStore.readProfile(wxId, account) to
                    (AiProfileInitializer.isRunning(wxId) || AiProfileAudit.isRunning(wxId))
            }
            profile = state.first
            running = state.second
            loading = false
            delay(if (running) 500 else 1500)
        }
    }

    val saved = profile
    val pendingAudit = saved?.pendingAudit
    var selectedAudit by remember(pendingAudit) { mutableStateOf(emptySet<String>()) }
    val busy = loading || running || applying || saved?.busy == true
    val ready = saved?.isReady == true
    val resumableAudit = ready && saved.auditBasis.isNotBlank() && saved.progressTotal > 0
    val hasCheckpoint = !busy && saved?.state == AiProfileState.FAILED
    val resume by produceState<AiProfileInitializer.Plan?>(null, hasCheckpoint, saved?.progressDone, revision) {
        value = if (hasCheckpoint) withContext(Dispatchers.IO) {
            AiProfileInitializer.resumePlan(wxId, account = account)
        } else null
    }
    val shouldPreview = !busy && !ready
    val preview by produceState<AiProfileInitializer.Plan?>(null, shouldPreview, fromYear, revision) {
        value = if (shouldPreview) withContext(Dispatchers.IO) {
            AiProfileInitializer.preview(wxId, fromYear, account = account)
        } else null
    }

    fun startInitialArchive(plan: AiProfileInitializer.Plan, binding: String): Boolean {
        if (AiProfileStore.readProfile(wxId, account) == null) {
            val contact = friend as? WeContact
            val initial = AiProfile(
                wxId = wxId,
                nickname = friend.nickname,
                remark = contact?.remarkName.orEmpty(),
                pyInitial = contact?.initialNickname.orEmpty(),
                quanPin = contact?.nicknamePinyin.orEmpty(),
                avatarUrl = friend.avatarUrl,
                createdAt = System.currentTimeMillis(),
            )
            if (!AiProfileStore.saveProfile(initial, account) && AiProfileStore.readProfile(wxId, account) == null) {
                return false
            }
        }
        return AiProfileInitializer.start(wxId, plan, account = account, expectedBinding = binding)
    }

    fun startRequest(title: String, description: String, action: (String) -> Boolean) {
        val purpose = Purpose.FRIEND_PROFILE
        val destination = AiConfig.selection(purpose)?.takeIf { it.account == account } ?: return
        val binding = destination.id
        confirmAiAction(context, title, "$description\n\n模型：${destination.displayName()}", "确认开始") {
            applying = true
            scope.launch {
                message = withContext(Dispatchers.IO) {
                    when {
                        AiProfileStore.accountId() != account -> "微信账号已切换，请重新打开工作台。"
                        AiConfig.bindingId(purpose) != binding -> "模型配置已变化，请重新确认。"
                        action(binding) -> ""
                        else -> "未能启动，档案或模型状态已变化，请重试。"
                    }
                }
                applying = false
                revision++
            }
        }
    }

    fun editProfile(transform: (AiProfile) -> AiProfile?) {
        applying = true
        scope.launch {
            var edited = false
            val result = withContext(Dispatchers.IO) {
                AiProfileStore.patchProfile(wxId, account = account) { before ->
                    if (before.busy || before.pendingAudit != null || !before.isReady) {
                        before
                    } else {
                        val after = transform(before)
                        if (after == null) {
                            before
                        } else {
                            edited = true
                            val change = ProfileChange.diffOf(before, after, "手动编辑", System.currentTimeMillis())
                            after.copy(changes = before.changes.recorded(change))
                        }
                    }
                }
            }
            message = if (result != null && edited) "档案已保存。" else "档案已变化或保存失败，请重新打开条目。"
            applying = false
            revision++
        }
    }

    fun applyAudit(plan: AiAuditPlan, accept: Boolean) {
        applying = true
        scope.launch {
            message = withContext(Dispatchers.IO) {
                if (accept) {
                    if (AiProfileAudit.apply(wxId, plan, account = account) != null) "审查建议已应用。"
                    else "审查建议已变化或无法保存，请重新查看。"
                } else {
                    AiProfileAudit.discard(wxId, plan, account = account)
                    if (AiProfileStore.readProfile(wxId, account)?.pendingAudit == null) "审查建议已舍弃。"
                    else "审查建议已变化或无法保存，请重新查看。"
                }
            }
            applying = false
            revision++
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            if (loading) WorkbenchStatus("正在读取档案…")
            if (running || saved?.busy == true) {
                WorkbenchStatus(saved?.progress?.ifBlank { "正在处理档案…" } ?: "正在处理档案…")
            } else if (!saved?.progress.isNullOrBlank()) {
                WorkbenchStatus(saved.progress)
            }
            saved?.lastError?.let { WorkbenchStatus(it) }
            if (message.isNotBlank()) WorkbenchStatus(message)
            if (!saved?.sourceRange.isNullOrBlank()) WorkbenchStatus("档案覆盖 ${saved.sourceRange}")
        }
        if (shouldPreview) {
            val plan = preview?.takeIf { it.fromYear == fromYear }
            item {
                if (plan == null) {
                    WorkbenchStatus("正在查询本地聊天记录…")
                } else {
                    YearChoice(fromYear, plan.years) { fromYear = it }
                    plan.emptyReason?.let { WorkbenchStatus(it) }
                    if (plan.totalLines > 0) {
                        WorkbenchStatus("${historyRange(plan)} · ${plan.totalLines} 条文字 · ${plan.slices.size} 批")
                    }
                    Button(
                        onClick = {
                            startRequest(
                                "整理好友档案",
                                "将你与好友「$name」在 ${historyRange(plan)} 的私聊记录（${plan.totalLines} 条文字，分 ${plan.slices.size} 批，$MEDIA_PLACEHOLDERS）发送到所选模型。整理时也会发送档案中的已有标签。",
                            ) { expected -> startInitialArchive(plan, expected) }
                        },
                        enabled = configured && plan.emptyReason == null && plan.slices.isNotEmpty(),
                    ) { Text("开始整理") }
                }
            }
        }
        val resumePlan = resume
        if (resumePlan != null && saved != null && !busy) {
            item {
                val done = saved.progressDone
                TextButton(enabled = configured, onClick = {
                    startRequest(
                        "继续整理",
                        "将与好友「$name」在 ${historyRange(resumePlan)} 内尚未处理的私聊记录（$MEDIA_PLACEHOLDERS）和已有标签发送到所选模型。已完成 $done / ${resumePlan.slices.size} 批。",
                    ) { expected -> AiProfileInitializer.start(wxId, resumePlan, done, account = account, expectedBinding = expected) }
                }) { Text("继续上次整理") }
            }
        }
        if (saved != null) {
            if (ready) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled = configured && !busy && pendingAudit == null, onClick = {
                            startRequest(
                                "增量整理",
                                "将与好友「$name」上次已整理范围之后新增的私聊记录（$MEDIA_PLACEHOLDERS）和已有标签发送到所选模型，用于更新档案。",
                            ) { expected -> AiProfileInitializer.startIncremental(wxId, account = account, expectedBinding = expected) }
                        }) { Text("增量整理") }
                        TextButton(enabled = configured && !busy && (pendingAudit == null || resumableAudit), onClick = {
                            startRequest(
                                if (resumableAudit) "继续审查" else "审查档案",
                                "将好友「$name」档案中的标签与记忆发送到所选模型，检查重复和需要修改的条目。审查建议由你确认。",
                            ) { expected -> AiProfileAudit.start(wxId, resume = resumableAudit, account = account, expectedBinding = expected) }
                        }) { Text(if (resumableAudit) "继续审查" else "审查档案") }
                    }
                }
            }
            if (pendingAudit != null) {
                auditItems(
                    plan = pendingAudit,
                    busy = busy,
                    selection = selectedAudit,
                    toggle = { id -> selectedAudit = if (id in selectedAudit) selectedAudit - id else selectedAudit + id },
                    discard = { applyAudit(pendingAudit, false) },
                    accept = {
                        val selected = pendingAudit.selectEntries(selectedAudit)
                        confirmAiAction(
                            context,
                            "应用审查建议",
                            "将根据已勾选的建议修改好友「$name」的档案，并舍弃本次未勾选的建议。",
                            "应用建议",
                        ) {
                            applyAudit(selected, true)
                        }
                    },
                )
            }
            profileItems(
                profile = saved,
                editable = ready && !busy && pendingAudit == null,
                onlyEvents = onlyEvents,
                toggleEvents = { onlyEvents = !onlyEvents },
            ) { title, current, maxLength, transform ->
                showArchiveEdit(context, title, current, maxLength) { value ->
                    editProfile { transform(it, value) }
                }
            }
        }
    }
}

@Composable
private fun YearChoice(year: Int?, options: List<Int>, choose: (Int?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = true }, enabled = options.isNotEmpty()) {
            Text("起始年份：${year ?: "全部历史"}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (listOf(null) + options).forEach {
                DropdownMenuItem(text = { Text(it?.toString() ?: "全部历史") }, onClick = {
                    expanded = false
                    choose(it)
                })
            }
        }
    }
}

private fun historyRange(plan: AiProfileInitializer.Plan): String {
    val zone = ZoneId.systemDefault()
    val from = Instant.ofEpochSecond(plan.fromSec).atZone(zone).toLocalDate()
    val to = Instant.ofEpochSecond(plan.toSec - 1).atZone(zone).toLocalDate()
    return "$from 至 $to"
}

private fun LazyListScope.auditItems(
    plan: AiAuditPlan,
    busy: Boolean,
    selection: Set<String>,
    toggle: (String) -> Unit,
    discard: () -> Unit,
    accept: () -> Unit,
) {
    item {
        HorizontalDivider()
        Text("待确认的审查建议", style = MaterialTheme.typography.titleSmall)
        if (plan.note.isNotBlank()) WorkbenchStatus(plan.note)
    }
    itemsIndexed(plan.mergeTags) { index, item ->
        AuditRow("mt$index", selection, busy, toggle, "合并标签 ${item.keys.joinToString("、")} → ${item.category}/${item.key}：${item.value}")
    }
    itemsIndexed(plan.deleteTags) { index, item ->
        AuditRow("dt$index", selection, busy, toggle, "删除标签 ${item.category}/${item.key} · ${item.reason}")
    }
    itemsIndexed(plan.mergeGroups) { index, item ->
        val originals = item.items.joinToString("、") { "${it.occurredAt} ${it.title}" }
        AuditRow("mm$index", selection, busy, toggle, "合并记忆 $originals → ${item.occurredAt} ${item.title}\n${item.detail}")
    }
    itemsIndexed(plan.deleteMemories) { index, item ->
        AuditRow("dm$index", selection, busy, toggle, "删除记忆 ${item.occurredAt} ${item.title} · ${item.reason}")
    }
    itemsIndexed(plan.markImportant) { index, item ->
        AuditRow("im$index", selection, busy, toggle, "标记关键事件 ${item.occurredAt} ${item.title} · ${item.reason}")
    }
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(enabled = !busy, onClick = discard) { Text("舍弃建议") }
            TextButton(enabled = !busy && selection.isNotEmpty(), onClick = accept) { Text("应用已选建议") }
        }
    }
}

@Composable
private fun AuditRow(id: String, selection: Set<String>, busy: Boolean, toggle: (String) -> Unit, text: String) {
    val checked = id in selection
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(
            value = checked,
            enabled = !busy,
            role = Role.Checkbox,
            onValueChange = { toggle(id) },
        ).padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, enabled = !busy, onCheckedChange = null)
        Text(text)
    }
}

private fun AiAuditPlan.selectEntries(selection: Set<String>) = copy(
    mergeTags = mergeTags.filterIndexed { index, _ -> "mt$index" in selection },
    deleteTags = deleteTags.filterIndexed { index, _ -> "dt$index" in selection },
    mergeGroups = mergeGroups.filterIndexed { index, _ -> "mm$index" in selection },
    deleteMemories = deleteMemories.filterIndexed { index, _ -> "dm$index" in selection },
    markImportant = markImportant.filterIndexed { index, _ -> "im$index" in selection },
)

private fun LazyListScope.profileItems(
    profile: AiProfile,
    editable: Boolean,
    onlyEvents: Boolean,
    toggleEvents: () -> Unit,
    edit: (String, String, Int, (AiProfile, String) -> AiProfile?) -> Unit,
) {
    val shownMemories = if (onlyEvents) profile.memories.filter { it.important } else profile.memories
    val memories = AiMemoryIndex.sorted(shownMemories).asReversed()
    item {
        HorizontalDivider()
        Text("标签（${profile.tags.size}）", style = MaterialTheme.typography.titleSmall)
        if (editable && (profile.tags.isNotEmpty() || memories.isNotEmpty())) WorkbenchStatus("点按标签或记忆可编辑。")
    }
    if (profile.tags.isEmpty()) item { WorkbenchStatus("暂无标签") }
    items(profile.tags) { tag ->
        Column(Modifier.fillMaxWidth().clickable(enabled = editable, onClickLabel = "编辑标签") {
            edit("${tag.category} / ${tag.key}", tag.value, AiTools.VALUE_MAX) { latest, value ->
                latest.takeIf { tag in it.tags }?.copy(tags = latest.tags.map { current ->
                    if (current == tag) current.copy(value = value) else current
                })
            }
        }.padding(vertical = 4.dp)) {
            Text("${tag.category} · ${tag.key}：${tag.value}")
            if (tag.evidence.isNotBlank()) WorkbenchStatus("依据：${tag.evidence}")
        }
    }
    item {
        Text("记忆（${profile.memories.size}）", style = MaterialTheme.typography.titleSmall)
        if (profile.memories.isNotEmpty()) {
            FilterChip(
                selected = onlyEvents,
                onClick = toggleEvents,
                label = { Text("只看关键事件") },
            )
        }
    }
    if (memories.isEmpty()) item { WorkbenchStatus(if (onlyEvents) "暂无关键事件" else "暂无记忆") }
    items(memories) { memory ->
        Column(Modifier.fillMaxWidth().clickable(enabled = editable, onClickLabel = "编辑记忆") {
            edit(memory.title, memory.detail, AiTools.DETAIL_MAX) { latest, value ->
                latest.takeIf { memory in it.memories }?.copy(memories = latest.memories.map { current ->
                    if (current == memory) current.copy(detail = value) else current
                })
            }
        }.padding(vertical = 4.dp)) {
            val important = if (memory.important) " · 关键事件" else ""
            Text("${memory.occurredAt} · ${memory.title}$important")
            WorkbenchStatus(memory.detail)
        }
    }
    if (profile.style.summary.isNotBlank()) item {
        Text("我的表达风格", style = MaterialTheme.typography.titleSmall)
        Text(profile.style.summary)
    }
    if (profile.changes.isNotEmpty()) {
        item { Text("档案变动", style = MaterialTheme.typography.titleSmall) }
        items(profile.changes.asReversed()) { change ->
            Text("${timeLabel(change.at)} · ${change.source}\n${change.detail()}")
        }
    }
}

private fun showArchiveEdit(
    context: Context,
    title: String,
    current: String,
    maxLength: Int,
    save: (String) -> Unit,
) = showComposeDialog(context) {
    var value by remember { mutableStateOf(current) }
    AlertDialogContent(
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text("内容") },
                supportingText = { Text("${value.trim().length} / $maxLength") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            Button(
                enabled = value.trim().length in 1..maxLength && value.trim() != current,
                onClick = {
                    save(value.trim())
                    onDismiss()
                },
            ) { Text("保存") }
        },
    )
}
