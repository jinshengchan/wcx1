// Adapted from AARCNoa/zhiyou, GroupDigestPanel.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.activity.settings.ai

import android.content.Context
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.Johnny.wcx.ai.AiProfileSummary
import com.Johnny.wcx.ai.group.GroupDigestConfig
import com.Johnny.wcx.ai.group.GroupDigestStatus
import com.Johnny.wcx.ai.group.GroupDigestStore
import com.Johnny.wcx.ai.group.GroupDigestSummary
import com.Johnny.wcx.ai.group.nextRunAtOf
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.utils.showComposeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal fun showAiOverview(context: Context) = showComposeDialog(context) {
    val owner by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) { GroupDigestStore.accountId() }
    }
    val account = owner
    val accountChanged by produceState(false, account) {
        if (account != null) {
            while (withContext(Dispatchers.IO) { GroupDigestStore.accountId() } == account) delay(500)
            value = true
        }
    }
    var selected by remember { mutableStateOf<GroupDigestSummary?>(null) }
    var selectedFriend by remember { mutableStateOf<AiProfileSummary?>(null) }
    val group = selected
    val friend = selectedFriend
    AlertDialogContent(
        title = { Text(group?.name ?: friend?.overviewName() ?: "AI 总览") },
        text = {
            when {
                accountChanged -> WorkbenchStatus("微信账号已切换，请重新打开 AI 总览。")
                account == null -> WorkbenchStatus("请登录微信后重新打开 AI 总览。")
                group != null -> GroupDigestHistory(context, account, group.groupId)
                friend != null -> AiOverviewFriendHistory(context, account, friend)
                else -> AiOverviewSections(context, account, onFriend = { selectedFriend = it }, onGroup = { selected = it })
            }
        },
        dismissButton = {
            if ((group != null || friend != null) && !accountChanged) {
                TextButton(onClick = { selected = null; selectedFriend = null }) { Text("返回总览") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
internal fun loadDigestGroups(account: String, revision: Int = 0): State<List<GroupDigestSummary>?> =
    produceState<List<GroupDigestSummary>?>(null, account, revision) {
        while (true) {
            value = withContext(Dispatchers.IO) { GroupDigestStore.loadGroups(account) }
            delay(1500)
        }
    }

@Composable
internal fun GroupDigestGroupList(
    groups: List<GroupDigestSummary>?,
    emptyMessage: String,
    onRemove: ((GroupDigestSummary) -> Unit)? = null,
    onSelect: (GroupDigestSummary) -> Unit,
) {
    when {
        groups == null -> WorkbenchStatus("正在读取群聊…")
        groups.isEmpty() -> WorkbenchStatus(emptyMessage)
        else -> LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 480.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            digestGroupItems(groups, onRemove, onSelect)
        }
    }
}

internal fun LazyListScope.digestGroupItems(
    groups: List<GroupDigestSummary>,
    onRemove: ((GroupDigestSummary) -> Unit)? = null,
    onSelect: (GroupDigestSummary) -> Unit,
) {
    items(groups, key = { it.groupId }) { group ->
        OutlinedCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().combinedClickable(
                    onClick = { onSelect(group) },
                    onLongClick = onRemove?.let { { it(group) } },
                    onLongClickLabel = if (onRemove != null) "移除群聊" else null,
                ).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (group.avatarUrl.isNotBlank()) {
                    AsyncImage(
                        model = group.avatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        group.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    listOfNotNull(
                        group.workTask.takeIf { it.isNotBlank() },
                        group.workPercent?.let { "${it}%" },
                    ).takeIf { it.isNotEmpty() }?.let { WorkbenchStatus(it.joinToString(" · ")) }
                    val subtitle = group.lastHeadline.takeIf { onRemove == null && it.isNotBlank() }
                        ?: group.statusLabel()
                    Text(
                        subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (group.unread > 0) Badge { Text(group.unread.toString()) }
            }
        }
    }
}

private fun GroupDigestSummary.statusLabel(): String = when {
    lastStatus == GroupDigestStatus.PAUSED -> lastError.ifBlank { "定时已暂停" }
    lastError.isNotBlank() -> lastError
    !enabled -> "定时已关闭"
    else -> {
        val next = nextRunAtOf(anchorAt, intervalMinutes, System.currentTimeMillis())
        "每 ${intervalLabel(intervalMinutes)} · 下次 ${timeLabel(next)}"
    }
}

@Composable
private fun GroupDigestHistory(context: Context, account: String, id: String) {
    var loaded by remember(account, id) { mutableStateOf(false) }
    val config by produceState<GroupDigestConfig?>(null, account, id) {
        while (true) {
            value = withContext(Dispatchers.IO) { GroupDigestStore.markRead(id, account) }
            loaded = true
            delay(1500)
        }
    }
    val saved = config
    when {
        !loaded -> WorkbenchStatus("正在读取历史摘要…")
        saved == null -> WorkbenchStatus("群聊已移除或暂时无法读取。")
        saved.digests.isEmpty() -> WorkbenchStatus("暂无摘要")
        else -> Column(Modifier.heightIn(max = 480.dp)) {
            if (saved.workGroup) WorkGroupReportButton(context, account, saved)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(saved.digests.asReversed(), key = { it.id }) { DigestCard(it, collapsible = true) }
            }
        }
    }
}
