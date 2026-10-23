package com.Johnny.wcx.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import de.robv.android.xposed.XC_MethodHook
import android.content.ContentValues
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.WeDatabaseListenerApi
import com.Johnny.wcx.features.api.core.WeMessageApi
import com.Johnny.wcx.features.api.core.WeXmlParserApi
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import com.Johnny.wcx.utils.formatEpoch

@Feature(name = "防撤回", categories = ["聊天"], description = "阻止撤回消息")
object AntiMessageRecall : ClickableFeature(), WeXmlParserApi.IAfterParseListener,
    WeDatabaseListenerApi.IUpdateListener {

    private const val TAG = "AntiMessageRecall"

    private var recallOutgoing by prefOption("recall_outgoing", false)
    private var pattern by prefOption("recall_pattern", $$"「$sender」尝试撤回上一条消息 (已阻止)")
    private var timeFormat by prefOption("recall_time_format", "yyyy/MM/dd HH:mm:ss")

    /**
     * 撤回样式：0 = 灰色小字系统提示（原样式）；1 = 气泡右侧圆角标签（默认）。
     * 两种样式都保留，用户自己选。
     */
    private var recallStyle by prefOption("recall_style", 1)

    /** 圆角标签上的文字（默认「已撤回」）。 */
    private var tagText by prefOption("recall_tag_text", "已撤回")

    private val NAME_REGEX = Regex("([\"「])(.*?)([」\"])")

    override fun onEnable() {
        WeXmlParserApi.addListener(this)
        WeDatabaseListenerApi.addListener(this)
        if (recallStyle == 1) {
            RecallTagRenderer.labelText = tagText
            RecallTagRenderer.install()
        }
    }

    override fun onDisable() {
        WeXmlParserApi.removeListener(this)
        WeDatabaseListenerApi.removeListener(this)
        rawRowCache.clear()
    }

    private const val TYPE_KEY = $$".sysmsg.$type"

    override fun onParse(param: XC_MethodHook.MethodHookParam, result: MutableMap<String, Any?>) {
        val args = param.args
        val xmlContent = args[0] as? String ?: ""
        val rootTag = args[1] as? String ?: ""

        if (rootTag != "sysmsg" || !xmlContent.contains("revokemsg")) {
            return
        }

        if (result[TYPE_KEY] == "revokemsg") {
            val revokeMsgId = result[".sysmsg.revokemsg.newmsgid"] as? String ?: return
            val cursor = WeDatabaseApi.rawQuery(
                "SELECT type,content,talker,createTime,lvbuffer,msgId,msgSvrId,isSend FROM message WHERE msgSvrId = ?",
                arrayOf(revokeMsgId)
            )

            cursor.use { cursor ->
                if (cursor.moveToFirst()) {
                    val msgInfo = MessageInfo(WeMessageApi.convertMsgInfoInstanceFromCursor(cursor))
                    val talker = msgInfo.talker
                    val createTime = msgInfo.createTime

                    if (msgInfo.isSelfSender && !recallOutgoing) {
                        WeLogger.i(TAG, "sender is self and not recall outgoing, skipping")
                        return
                    }

                    result[TYPE_KEY] = null

                    val replaceMsg = result[".sysmsg.revokemsg.replacemsg"] as? String?
                        ?: return
                    val match = NAME_REGEX.find(replaceMsg)
                    // 粉色标签样式不依赖发送者名字，所以这里不再直接 return（否则该样式下部分撤回拦不住）。
                    val senderName = match?.groupValues?.get(2) ?: if (recallOutgoing) "自己" else ""
                    if (senderName.isEmpty() && recallStyle != 1) return

                    // 系统提示两种样式都要插：它承载时间与自定义文案，样式二只是"多"一个圆角标签。
                    val noticeText = pattern
                        .replace($$"$sender", senderName)
                        .replace($$"$sendTime", formatEpoch(createTime, timeFormat))
                        .replace($$"$recallTime", formatEpoch(System.currentTimeMillis(), timeFormat))
                        .replace($$"$content", msgInfo.humanReadableRepr)

                    if (recallStyle == 1) {
                        // 样式 2：在气泡右侧额外画一个圆角标签；原文保留在库里，所以标签挂在原消息上。
                        RecallTagRenderer.labelText = tagText
                        RecallTagRenderer.install()
                        RecallTagRenderer.rememberBlocked(revokeMsgId, msgInfo.humanReadableRepr)
                    }

                    WeMessageApi.createSimpleMsgInfoAndInsert(
                        MessageType.SYSTEM.code,
                        talker,
                        noticeText,
                        createTime + 1
                    )

                    WeLogger.i(TAG, "blocked message revoke")
                }
            }
        }
    }

    // ==================== 防撤回自己的消息（数据库更新层） ====================
    //
    // 自己撤回是本地库操作，不会产生 sysmsg.revokemsg，因此上面的 XML 分支对它无效。
    // 这里挂在 message 表的 update 上：识别撤回类更新，若该消息是自己发的，则把待写入的
    // ContentValues 还原为撤回前的原始值，等效阻止撤回。

    private val rawRowCache = java.util.concurrent.ConcurrentHashMap<Long, Map<String, Any?>>()

    override fun onUpdate(
        table: String,
        values: ContentValues,
        whereClause: String?,
        whereArgs: Array<String>?,
        conflictAlgorithm: Int
    ) {
        if (table != "message") return

        val content = runCatching { values.getAsString("content") }.getOrNull().orEmpty()
        if (!content.contains("撤回") && !content.contains("revokemsg", ignoreCase = true)) return

        val msgId = extractMsgId(whereClause, whereArgs) ?: return
        val raw = rawRowCache[msgId] ?: queryRawRow(msgId)?.also { rawRowCache[msgId] = it } ?: return

        val isSend = (raw["isSend"] as? Number)?.toInt() == 1
        if (!isSend) return
        if (!recallOutgoing) return

        restoreRow(values, raw)
        WeLogger.i(TAG, "blocked self recall (db update): msgId=$msgId")
    }

    private fun extractMsgId(whereClause: String?, whereArgs: Array<String>?): Long? {
        if (whereClause == null || whereArgs == null) return null
        if (!whereClause.contains("msgId", ignoreCase = true)) return null
        return whereArgs.firstOrNull()?.toLongOrNull()
    }

    private fun queryRawRow(msgId: Long): Map<String, Any?>? =
        runCatching {
            WeDatabaseApi.executeQuery(
                "SELECT * FROM message WHERE msgId = ?",
                arrayOf<Any>(msgId.toString())
            ).firstOrNull()
        }.getOrNull()

    private fun restoreRow(values: ContentValues, raw: Map<String, Any?>) {
        val keys = arrayOf(
            "content", "type", "isSend", "status",
            "imgPath", "reserved", "transContent", "msgSource", "flag"
        )
        for (key in keys) {
            val v = raw[key] ?: continue
            when (v) {
                is Long -> values.put(key, v)
                is Int -> values.put(key, v)
                is String -> values.put(key, v)
                is Double -> values.put(key, v)
                is Float -> values.put(key, v)
                is ByteArray -> values.put(key, v)
                is Boolean -> values.put(key, v)
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var recallOutgoingInput by remember { mutableStateOf(recallOutgoing) }
            var patternInput by remember { mutableStateOf(pattern) }
            var timeFormatInput by remember { mutableStateOf(timeFormat) }
            var recallStyleInput by remember { mutableStateOf(recallStyle) }
            var tagTextInput by remember { mutableStateOf(tagText) }
            AlertDialogContent(
                title = { Text("防撤回") },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable { recallOutgoingInput = !recallOutgoingInput },
                            trailingContent = {
                                Switch(checked = recallOutgoingInput, onCheckedChange = null)
                            },
                            supportingContent = { Text("是否对自己发出的消息也生效 (已支持: 在数据库更新层拦截自己撤回)") },
                            headlineContent = { Text("防撤回自己的消息") },
                        )

                        ListItem(
                            modifier = Modifier.clickable { recallStyleInput = 1 },
                            trailingContent = {
                                Text(if (recallStyleInput == 1) "✓ 已选" else "")
                            },
                            supportingContent = { Text("气泡右侧显示圆角「已撤回」标签，下方灰色小字提示（含时间）照常显示") },
                            headlineContent = { Text("样式二：气泡右侧圆角标签") },
                        )

                        TextField(
                            value = tagTextInput,
                            onValueChange = { tagTextInput = it.take(6) },
                            label = { Text("标签文字（默认「已撤回」）") },
                            supportingText = { Text("建议 2~4 个字，太长会挤压气泡") },
                            modifier = Modifier.fillMaxWidth()
                        )

                        ListItem(
                            modifier = Modifier.clickable { recallStyleInput = 0 },
                            trailingContent = {
                                Text(if (recallStyleInput == 0) "✓ 已选" else "")
                            },
                            supportingContent = { Text("只显示灰色小字系统提示，内容与时间格式由下面两项决定") },
                            headlineContent = { Text("样式一：灰色小字提示") },
                        )

                        TextField(
                            label = { Text("提示格式（两种样式都生效）") },
                            supportingText = { Text($$"可使用占位符 $sender, $sendTime, $recallTime, $content") },
                            value = patternInput,
                            onValueChange = { patternInput = it },
                            modifier = Modifier.fillMaxWidth()
                        )

                        TextField(
                            value = timeFormatInput,
                            onValueChange = { timeFormatInput = it },
                            label = { Text("时间格式（两种样式都生效）") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button({
                        recallOutgoing = recallOutgoingInput
                        pattern = patternInput
                        timeFormat = timeFormatInput
                        recallStyle = recallStyleInput
                        tagText = tagTextInput.ifBlank { "已撤回" }
                        RecallTagRenderer.labelText = tagText
                        if (recallStyle == 1) RecallTagRenderer.install()
                        onDismiss()
                    }) { Text("确定") }
                })
        }
    }
}
