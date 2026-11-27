package com.Johnny.wcx.activity.settings.ai

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.ai.AiAssistantTools
import com.Johnny.wcx.ai.AiDailyReport
import com.Johnny.wcx.ai.AiFriendEvent
import com.Johnny.wcx.ai.AiFriendEvents
import com.Johnny.wcx.ai.AiProfileStore
import com.Johnny.wcx.ai.AiProfileSummary
import com.Johnny.wcx.ai.AiTool
import com.Johnny.wcx.ai.group.GroupDigestSummary
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.features.items.chat.showAiTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

private data class OverviewSnapshot(
    val friends: List<AiProfileSummary>,
    val events: Map<String, List<AiFriendEvent>>,
    val day: LocalDate,
    val todayReport: String,
)

internal fun AiProfileSummary.overviewName(): String = remark.ifBlank { nickname.ifBlank { wxId } }

@Composable
internal fun AiOverviewSections(
    context: Context,
    account: String,
    onFriend: (AiProfileSummary) -> Unit,
    onGroup: (GroupDigestSummary) -> Unit,
) {
    var revision by remember(account) { mutableIntStateOf(0) }
    var todayOpen by remember(account) { mutableStateOf(false) }
    var friendsOpen by remember(account) { mutableStateOf(true) }
    var groupsOpen by remember(account) { mutableStateOf(true) }
    var inactiveOpen by remember(account) { mutableStateOf(false) }
    val overview by produceState<OverviewSnapshot?>(null, account, revision) {
        while (true) {
            value = withContext(Dispatchers.IO) {
                val day = LocalDate.now()
                OverviewSnapshot(
                    AiProfileStore.loadIndex(account).profiles.filter { AiAssistantTools.visible(it.wxId) },
                    AiFriendEvents.overview(account),
                    day,
                    AiDailyReport.read(account).history[day.toString()]?.text.orEmpty(),
                ).takeIf { AiConfig.isMasterEnabled && AiProfileStore.isCurrentAccount(account) }
            }
            delay(1500)
        }
    }
    val groups by loadDigestGroups(account)
    val snapshot = overview
    if (snapshot == null) {
        WorkbenchStatus("正在读取总览…")
        return
    }
    val (active, inactive) = snapshot.friends.partition { it.autoReplyEnabled }
    val friendUnread = snapshot.events.values.sumOf { items -> items.count { !it.read } }
    val groupUnread = groups.orEmpty().sumOf { it.unread }
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 460.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { OverviewHeading("今日总结", todayOpen) { todayOpen = !todayOpen } }
        if (todayOpen) item {
            Column {
                val todaysEvents = snapshot.friends.flatMap { friend ->
                    snapshot.events[friend.wxId].orEmpty().filter { it.recordedOn(snapshot.day) }
                        .map { friend.overviewName() to it }
                }.sortedByDescending { it.second.at }
                val todayStart = snapshot.day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val todaysGroups = groups.orEmpty().filter { it.lastDigestAt >= todayStart }
                if (snapshot.todayReport.isNotBlank()) Text(snapshot.todayReport)
                if (todaysEvents.isEmpty() && todaysGroups.isEmpty() && snapshot.todayReport.isBlank()) {
                    WorkbenchStatus("今天还没有记录。")
                }
                todaysEvents.take(5).forEach { (name, event) -> WorkbenchStatus("$name · ${event.title}") }
                todaysGroups.take(5).forEach { group ->
                    WorkbenchStatus("${group.name} · ${group.workTask.ifBlank { group.lastHeadline }}")
                }
                TextButton(
                    enabled = AiConfig.selection(AiConfig.Purpose.TOOLS)?.account == account,
                    onClick = { showAiTool(context, AiTool.DAILY_REPORT, expectedAccount = account, onResult = { revision++ }) },
                ) { Text("整理今天") }
            }
        }
        item { OverviewHeading(if (friendUnread == 0) "好友" else "好友 · $friendUnread 未读", friendsOpen) { friendsOpen = !friendsOpen } }
        if (friendsOpen) {
            if (snapshot.friends.isEmpty()) item { WorkbenchStatus("可在 AI 助手的「好友」中添加档案。") }
            items(active, key = { it.wxId }) { friend -> OverviewFriend(friend, snapshot.events[friend.wxId].orEmpty(), onFriend) }
            if (inactive.isNotEmpty()) {
                item { OverviewHeading("未开启自动回复 · ${inactive.size} 人", inactiveOpen) { inactiveOpen = !inactiveOpen } }
                if (inactiveOpen) items(inactive, key = { it.wxId }) { friend ->
                    OverviewFriend(friend, snapshot.events[friend.wxId].orEmpty(), onFriend)
                }
            }
        }
        item { OverviewHeading(if (groupUnread == 0) "群聊摘要" else "群聊摘要 · $groupUnread 未读", groupsOpen) { groupsOpen = !groupsOpen } }
        if (groupsOpen) {
            val currentGroups = groups
            when {
                currentGroups == null -> item { WorkbenchStatus("正在读取群聊…") }
                currentGroups.isEmpty() -> item { WorkbenchStatus("可在 AI 助手的「群组」中添加群聊。") }
                else -> digestGroupItems(currentGroups, onSelect = onGroup)
            }
        }
    }
}

@Composable
private fun OverviewHeading(title: String, expanded: Boolean, toggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = toggle).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(if (expanded) "▴" else "▾")
    }
}

@Composable
private fun OverviewFriend(friend: AiProfileSummary, events: List<AiFriendEvent>, onSelect: (AiProfileSummary) -> Unit) {
    OutlinedCard(onClick = { onSelect(friend) }, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(friend.overviewName(), style = MaterialTheme.typography.titleSmall)
                WorkbenchStatus(events.firstOrNull()?.title ?: if (friend.autoReplyEnabled) "暂无重要事件" else "自动回复已关闭")
            }
            val unread = events.count { !it.read }
            if (unread > 0) Badge { Text(unread.toString()) }
        }
    }
}

@Composable
internal fun AiOverviewFriendHistory(context: Context, account: String, friend: AiProfileSummary) {
    val scope = rememberCoroutineScope()
    var revision by remember(account, friend.wxId) { mutableIntStateOf(0) }
    var error by remember(account, friend.wxId) { mutableStateOf("") }
    val events by produceState<List<AiFriendEvent>?>(null, account, friend.wxId, revision) {
        while (true) {
            val displayed = withContext(Dispatchers.IO) {
                AiFriendEvents.overview(account)[friend.wxId].orEmpty()
            }
            value = displayed
            val unread = displayed.filterNot { it.read }.mapTo(HashSet()) { it.sourceId }
            if (unread.isNotEmpty()) withContext(Dispatchers.IO) { AiFriendEvents.markRead(account, friend.wxId, unread) }
            delay(1500)
        }
    }
    val history = events
    if (history == null) {
        WorkbenchStatus("正在读取好友事件…")
    } else if (history.isEmpty()) {
        WorkbenchStatus("暂无重要事件。")
    } else {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 450.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    WorkbenchStatus("共 ${history.size} 条")
                    TextButton(onClick = {
                        confirmAiAction(context, "清空好友事件", "清空「${friend.overviewName()}」的全部事件？好友档案会保留。", "清空") {
                            scope.launch {
                                val saved = withContext(Dispatchers.IO) { AiFriendEvents.clear(account, friend.wxId) }
                                error = if (saved) "" else "清空失败，请重新打开总览。"
                                if (saved) revision++
                            }
                        }
                    }) { Text("清空") }
                }
                if (error.isNotBlank()) WorkbenchStatus(error)
            }
            items(history.size) { index ->
                val event = history[index]
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${index + 1}. ${event.title}", style = MaterialTheme.typography.titleSmall)
                        WorkbenchStatus("记录于 ${timeLabel(event.at)}")
                        if (event.detail.isNotBlank()) Text(event.detail)
                    }
                }
            }
        }
    }
}
