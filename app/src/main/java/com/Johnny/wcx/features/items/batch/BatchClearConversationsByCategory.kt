package com.Johnny.wcx.features.items.batch

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.ContactsSelector
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

@Feature(
    name = "按类别批量清理会话",
    categories = ["批量操作"],
    description = "按公众号、订阅号、群聊或单聊筛选，再确认隐藏会话或删除记录",
)
object BatchClearConversationsByCategory : ClickableFeature() {
    override val noSwitchWidget = true
    private val running = AtomicBoolean(false)

    override fun onClick(context: ComponentActivity) = showComposeDialog(context) {
        val scope = rememberCoroutineScope()
        var categories by remember { mutableStateOf(emptySet<ConversationCategory>()) }
        var mutedOnly by remember { mutableStateOf(false) }
        var noUnreadOnly by remember { mutableStateOf(true) }
        var permanent by remember { mutableStateOf(false) }
        var loading by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        AlertDialogContent(
            title = { Text("按类别批量清理会话") },
            text = {
                DefaultColumn(scrollable = true) {
                    for (category in ConversationCategory.entries) {
                        ListItem(
                            headlineContent = { Text(category.label) },
                            trailingContent = {
                                Checkbox(category in categories, onCheckedChange = { checked ->
                                    categories = if (checked) categories + category else categories - category
                                }, enabled = !loading)
                            },
                        )
                    }
                    ListItem(headlineContent = { Text("仅消息免打扰") }, trailingContent = {
                        Switch(mutedOnly, { mutedOnly = it }, enabled = !loading)
                    })
                    ListItem(headlineContent = { Text("仅无未读会话") }, trailingContent = {
                        Switch(noUnreadOnly, { noUnreadOnly = it }, enabled = !loading)
                    })
                    ListItem(
                        headlineContent = { Text("同时删除聊天记录") },
                        supportingContent = { Text(if (permanent) "删除后无法恢复" else "只隐藏会话，保留聊天记录") },
                        trailingContent = { Switch(permanent, { permanent = it }, enabled = !loading) },
                    )
                    if (error.isNotBlank()) Text(error)
                }
            },
            dismissButton = { TextButton(onDismiss) { Text("取消") } },
            confirmButton = {
                Button(enabled = categories.isNotEmpty() && !loading && !running.get(), onClick = {
                    val criteria = ConversationCriteria(categories, mutedOnly, noUnreadOnly)
                    val deleteRecords = permanent
                    loading = true
                    error = ""
                    scope.launch {
                        try {
                            val (source, rows) = withContext(Dispatchers.IO) {
                                val source = ConversationBatchData.capture()
                                source to source.loadConversations().filter(criteria::accepts)
                            }
                            if (rows.isEmpty()) {
                                error = "没有符合筛选条件的会话"
                            } else {
                                showSelection(context, source, rows, criteria, deleteRecords)
                                onDismiss()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            WeLogger.w("BatchClearConversations", "读取候选会话失败", e)
                            error = "读取会话失败，请确认微信已登录"
                        } finally {
                            loading = false
                        }
                    }
                }) { Text(if (loading) "正在读取…" else "预览会话") }
            },
        )
    }

    private fun showSelection(
        context: Context, source: ConversationBatchData, rows: List<BatchConversation>,
        criteria: ConversationCriteria, permanent: Boolean,
    ) = showComposeDialog(context) {
        ContactsSelector(
            title = "选择要${if (permanent) "删除" else "隐藏"}的会话",
            contacts = rows,
            initialSelectedWxIds = emptySet(),
            onDismiss = onDismiss,
            onConfirm = { ids ->
                val selected = rows.filter { it.wxId in ids }
                if (selected.isEmpty()) {
                    showToast(context, "请至少选择一个会话")
                } else {
                    val closeSelection = onDismiss
                    confirmConversationBatch(context, if (permanent) "删除聊天记录" else "隐藏会话", selected,
                        if (permanent) "所选会话及聊天记录将删除，无法恢复。" else "聊天记录会保留，再收到消息时会话可重新出现。",
                        if (permanent) "删除记录" else "隐藏会话",
                    ) {
                        closeSelection()
                        runBatch(context, source, selected, criteria, permanent)
                    }
                }
            },
        )
    }

    private fun runBatch(
        context: Context, source: ConversationBatchData, rows: List<BatchConversation>,
        criteria: ConversationCriteria, permanent: Boolean,
    ) {
        if (!running.compareAndSet(false, true)) return
        try {
            showComposeDialog(context, directlyDismissable = false) {
                var stop by remember { mutableStateOf(false) }
                var done by remember { mutableStateOf(false) }
                var submitted by remember { mutableStateOf(0) }
                var skipped by remember { mutableStateOf(0) }
                var failed by remember { mutableStateOf(0) }
                var status by remember { mutableStateOf("正在处理") }
                LaunchedEffect(Unit) {
                    try {
                        for (row in rows) {
                            if (stop) break
                            val stillEligible = withContext(Dispatchers.IO) {
                                check(source.isCurrent())
                                source.loadConversations(row.wxId).singleOrNull()?.let(criteria::accepts) == true
                            }
                            if (stop) break
                            check(source.isCurrent())
                            if (!stillEligible || !source.isVisible(row.wxId)) {
                                skipped++
                            } else {
                                val invoked = if (permanent) WeConversationApi.deleteConversation(row.wxId)
                                    else WeConversationApi.hideConversation(row.wxId)
                                if (invoked) submitted++ else failed++
                            }
                            delay(60L)
                        }
                        status = if (stop) "已停止提交剩余会话" else "处理结束"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        status = "当前账号或会话状态已变化，已停止"
                        WeLogger.w("BatchClearConversations", "批量处理停止", e)
                    } finally {
                        if (submitted > 0 && source.isCurrent()) WeConversationApi.reloadConversations()
                        running.set(false)
                        done = true
                    }
                }
                AlertDialogContent(
                    title = { Text(if (permanent) "删除聊天记录" else "隐藏会话") },
                    text = { Column {
                        Text(status)
                        Text("已提交 $submitted，已跳过 $skipped，调用失败 $failed，未处理 ${rows.size - submitted - skipped - failed}")
                    } },
                    confirmButton = {
                        if (done) Button(onDismiss) { Text("完成") }
                        else TextButton(onClick = { stop = true }, enabled = !stop) { Text("停止剩余操作") }
                    },
                )
            }
        } catch (e: Exception) {
            running.set(false)
            WeLogger.w("BatchClearConversations", "无法显示处理结果", e)
        }
    }
}
