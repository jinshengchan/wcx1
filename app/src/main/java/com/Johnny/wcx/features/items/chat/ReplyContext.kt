package com.Johnny.wcx.features.items.chat

import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.utils.strings.stripWxId

/** Only text in this conversation strictly before the selected message; no fallback to latest history. */
internal object ReplyContext {
    fun load(talker: String, id: Long, time: Long, limit: Int): List<Pair<String, String>> {
        if (id <= 0 || time <= 0 || talker.isBlank()) return emptyList()
        return WeDatabaseApi.rawQuery(
            """SELECT content, isSend FROM message
               WHERE talker = ? AND type = 1
                 AND (createTime < ? OR (createTime = ? AND msgId < ?))
               ORDER BY createTime DESC, msgId DESC LIMIT ?""".trimIndent(),
            arrayOf<Any>(talker, time, time, id, limit.coerceIn(1, 20)),
        ).use { cursor ->
            val messages = mutableListOf<Pair<String, String>>()
            while (cursor.moveToNext()) {
                var text = cursor.getString(0).orEmpty()
                if (talker.endsWith("@chatroom")) text = text.stripWxId()
                if (text.isNotBlank()) messages +=
                    (if (cursor.getInt(1) != 0) "assistant" else "user") to text.take(1000)
            }
            messages.asReversed()
        }
    }
}
