// Adapted from AARCNoa/zhiyou, GroupTranscript.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.ai.group

import com.Johnny.wcx.ai.MessageRendering
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object GroupTranscript {
    data class Line(val atSec: Long, val senderName: String, val text: String, val isText: Boolean)

    data class Transcript(val lines: List<Line>, val sampled: Boolean)

    private val senderPrefix = Regex("^([A-Za-z0-9_@-]{1,64}):\\r?\\n")

    fun textCount(groupId: String, fromSec: Long, toSec: Long, account: String): Int =
        WeDatabaseApi.countMessagesBetween(groupId, fromSec, toSec, MessageRendering.TEXT_ONLY, account = account)

    fun load(groupId: String, fromSec: Long, toSec: Long, account: String): Transcript {
        val raw = WeDatabaseApi.getMessagesBetween(
            convId = groupId,
            fromSec = fromSec,
            toSec = toSec,
            limit = MAX_MESSAGES_PER_RUN + 1,
            typeCodes = MessageRendering.GROUP_CHAT_TYPES,
            newestFirst = true,
            account = account,
        )
        if (raw.isEmpty()) return Transcript(emptyList(), sampled = false)
        val cards = WeDatabaseApi.getGroupMemberDisplayNames(groupId, account = account)
        val nicknames = memberNicknames(groupId, account)
        val anonymousNames = mutableMapOf<String, String>()
        val lines = raw.take(MAX_MESSAGES_PER_RUN).asReversed().mapNotNull { message ->
            val fromMe = message.isSend == 1
            val prefix = senderPrefix.find(message.content)
            val senderId = prefix?.groupValues?.get(1)
            val body = if (prefix != null && (!fromMe || senderId == account)) {
                message.content.substring(prefix.range.last + 1)
            } else {
                message.content
            }
            val text = MessageRendering.render(message.typeCode, body) ?: return@mapNotNull null
            val senderName = when {
                fromMe -> "我"
                senderId == null -> "群成员"
                else -> MessageRendering.sanitize(cards[senderId].orEmpty())
                    .ifBlank { MessageRendering.sanitize(nicknames[senderId].orEmpty()) }
                    .take(60)
                    .ifBlank { anonymousNames.getOrPut(senderId) { "群成员 ${anonymousNames.size + 1}" } }
            }
            Line(
                atSec = MessageRendering.toSeconds(message.createTime),
                senderName = senderName,
                text = text,
                isText = message.typeCode == MessageRendering.TYPE_TEXT,
            )
        }
        return Transcript(lines, sampled = raw.size > MAX_MESSAGES_PER_RUN)
    }

    private fun memberNicknames(groupId: String, account: String): Map<String, String> {
        val database = WeDatabaseApi.db
        check(WeDatabaseApi.currentAiAccount() == account)
        val members = WeDatabaseApi.getGroupMembers(groupId)
        check(WeDatabaseApi.currentAiAccount() == account && WeDatabaseApi.db === database)
        return members.associate { it.wxId to it.nickname }
    }

    fun chunk(lines: List<Line>): List<List<Line>> {
        val chunks = mutableListOf<List<Line>>()
        var current = mutableListOf<Line>()
        var chars = 0
        for (line in lines) {
            val cost = line.text.length + line.senderName.length + 17
            if (current.isNotEmpty() && (current.size >= MAX_CHUNK_LINES || chars + cost > MAX_CHUNK_CHARS)) {
                chunks += current
                current = mutableListOf()
                chars = 0
            }
            current += line
            chars += cost
        }
        if (current.isNotEmpty()) chunks += current
        return chunks.takeLast(MAX_CHUNKS)
    }

    fun transcript(lines: List<Line>): String {
        val format = SimpleDateFormat("MM-dd HH:mm", Locale.US)
        return lines.joinToString("\n") { line ->
            "[${format.format(Date(line.atSec * 1000L))}] ${line.senderName}: ${line.text}"
        }
    }
}
