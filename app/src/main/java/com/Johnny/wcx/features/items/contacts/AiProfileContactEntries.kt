// Adapted from AARCNoa/zhiyou, AiProfileContactEntries.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.features.items.contacts

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.ai.AiDates
import com.Johnny.wcx.ai.AiProfileState
import com.Johnny.wcx.ai.AiProfileStore
import com.Johnny.wcx.features.api.ui.WeContactPrefsScreenApi
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.android.currentWxId
import com.Johnny.wcx.utils.android.showToast
import com.Johnny.wcx.utils.strings.isGroupChatWxId

@Feature(name = "AI 档案入口", categories = ["API"], description = "在好友资料页查看 AI 标签和记忆")
object AiProfileContactEntries : ApiFeature(), WeContactPrefsScreenApi.IContactInfoProvider {
    private const val KEY_TAGS = "wcx_ai_tags"
    private const val KEY_MEMORIES = "wcx_ai_memories"

    override fun onEnable() = WeContactPrefsScreenApi.addProvider(this)
    override fun onDisable() = WeContactPrefsScreenApi.removeProvider(this)

    override fun getContactInfoItem(activity: Activity): List<WeContactPrefsScreenApi.PreferenceItem> {
        val profile = readyProfile(activity) ?: return emptyList()
        return listOf(
            WeContactPrefsScreenApi.PreferenceItem(KEY_TAGS, "AI 标签", "${profile.tags.size} 个"),
            WeContactPrefsScreenApi.PreferenceItem(KEY_MEMORIES, "AI 记忆", "${profile.memories.size} 条"),
        )
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (key != KEY_TAGS && key != KEY_MEMORIES) return false
        val profile = readyProfile(activity)
        if (profile == null) {
            showToast(activity, "这位好友还没有 AI 档案")
            return true
        }
        val tags = key == KEY_TAGS
        val title = if (tags) "AI 标签" else "AI 记忆"
        val entries = if (tags) {
            profile.tags.sortedBy { it.category }.map { "${it.category} · ${it.key}" to it.value }
        } else {
            profile.memories.sortedByDescending { AiDates.sortKey(it.occurredAt) }
                .map { "${AiDates.display(it.occurredAt)} · ${it.title}" to it.detail }
        }
        showComposeDialog(activity) {
            AlertDialogContent(
                title = { Text(title) },
                text = {
                    if (entries.isEmpty()) {
                        Text(if (tags) "还没有标签" else "还没有记忆")
                    } else {
                        LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(entries) { (label, detail) ->
                                Column {
                                    Text(label, style = MaterialTheme.typography.titleSmall)
                                    if (detail.isNotBlank()) Text(detail)
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
            )
        }
        return true
    }

    private fun readyProfile(activity: Activity) = activity.currentWxId
        ?.takeIf { it.isNotBlank() && !it.isGroupChatWxId }
        ?.let { AiProfileStore.readProfile(it) }
        ?.takeIf { it.state == AiProfileState.READY }
}
