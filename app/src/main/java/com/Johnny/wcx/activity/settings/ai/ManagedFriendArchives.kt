// Adapted from AARCNoa/zhiyou, AiPager.kt and AiAddFriendSheet.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.activity.settings.ai

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.ai.AiFriend
import com.Johnny.wcx.ai.AiFriends
import com.Johnny.wcx.ai.AiProfileStore
import com.Johnny.wcx.ai.AiProfileSummary
import com.Johnny.wcx.features.api.core.models.IWeContact
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.utils.showComposeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ManagedFriendArchives(context: Context, account: String, select: (IWeContact) -> Unit) {
    var search by remember(account) { mutableStateOf("") }
    val profiles by produceState<List<AiProfileSummary>?>(null, account) {
        while (true) {
            value = withContext(Dispatchers.IO) { AiProfileStore.loadIndex(account).profiles }
            delay(1500)
        }
    }
    val current = profiles
    val shown = current.orEmpty().filter {
        matchesFriend(search, it.nickname, it.remark, it.wxId, it.sortKey, it.pyInitial)
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            TextButton(onClick = { addFriendArchive(context, account, select) }) { Text("添加好友档案") }
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                label = { Text("搜索已添加的好友") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (shown.isEmpty()) item {
            WorkbenchStatus(when {
                current == null -> "正在读取档案…"
                current.isEmpty() -> "还没有好友档案"
                else -> "没有匹配的档案"
            })
        }
        items(shown, key = { it.wxId }) { profile ->
            val contact = ArchiveContact(profile.wxId, profile.nickname, profile.remark, profile.avatarUrl)
            ArchiveFriendRow(contact.displayName, profile.subtitle) {
                if (AiProfileStore.isCurrentAccount(account)) select(contact)
            }
        }
    }
}

private fun addFriendArchive(context: Context, account: String, select: (IWeContact) -> Unit) =
    showComposeDialog(context) {
        val scope = rememberCoroutineScope()
        var search by remember { mutableStateOf("") }
        var revision by remember { mutableIntStateOf(0) }
        var adding by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        val state by produceState<Pair<List<AiFriend>, Set<String>>?>(null, revision) {
            value = withContext(Dispatchers.IO) {
                val contacts = if (revision == 0) AiFriends.loadOrRefresh(account = account)
                else AiFriends.loadOrRefresh(maxAgeMs = 0, account = account)
                contacts to AiProfileStore.loadIndex(account).profiles.mapTo(HashSet()) { it.wxId }
            }
        }
        val loaded = state
        val options = loaded?.first.orEmpty().filter {
            matchesFriend(search, it.wxId, it.nickname, it.remark, it.pyInitial, it.quanPin)
        }
        AlertDialogContent(
            title = { Text("添加好友档案") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        OutlinedTextField(
                            value = search,
                            onValueChange = { search = it },
                            label = { Text("昵称、备注、微信 ID 或拼音") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (error.isNotBlank()) WorkbenchStatus(error)
                        if (options.isEmpty()) WorkbenchStatus(when {
                            loaded == null -> "正在读取微信通讯录…"
                            loaded.first.isEmpty() -> "未读取到好友，请刷新通讯录。"
                            else -> "没有匹配的好友"
                        })
                    }
                    items(options, key = { it.wxId }) { friend ->
                        val exists = friend.wxId in loaded?.second.orEmpty()
                        val contact = ArchiveContact(friend.wxId, friend.nickname, friend.remark, friend.avatarUrl)
                        ArchiveFriendRow(contact.displayName, if (exists) "已添加" else friend.wxId, !adding) {
                            adding = true
                            scope.launch {
                                val saved = withContext(Dispatchers.IO) {
                                    AiProfileStore.readProfile(friend.wxId, account) != null || AiFriends.add(friend, account)
                                }
                                if (saved && AiProfileStore.isCurrentAccount(account)) {
                                    select(contact)
                                    onDismiss()
                                } else {
                                    error = "无法保存档案，请确认微信账号后重试。"
                                    adding = false
                                }
                            }
                        }
                    }
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
            confirmButton = { TextButton(onClick = { revision++ }, enabled = !adding) { Text("刷新通讯录") } },
        )
    }

@Composable
private fun ArchiveFriendRow(title: String, subtitle: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        WorkbenchStatus(subtitle)
    }
}

private fun matchesFriend(search: String, vararg fields: String): Boolean {
    val query = search.trim()
    return query.isBlank() || fields.any { it.contains(query, ignoreCase = true) }
}

private class ArchiveContact(
    override val wxId: String,
    override val nickname: String,
    val remark: String,
    override val avatarUrl: String,
) : IWeContact {
    override val displayName: String get() = remark.ifBlank { nickname.ifBlank { wxId } }
}
