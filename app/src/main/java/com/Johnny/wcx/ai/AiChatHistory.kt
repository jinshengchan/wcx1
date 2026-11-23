// Adapted from AARCNoa/zhiyou, AiChatHistory.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.ai

import com.Johnny.wcx.features.api.core.WeDatabaseApi
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object AiChatHistory {

    @Serializable
    data class TimeWindow(val fromSec: Long, val toSec: Long)

    data class Line(val at: Long, val fromMe: Boolean, val text: String, val isText: Boolean)

    fun startOfMonth(year: Int, month: Int = 1): Long =
        LocalDate.of(year, month, 1).atStartOfDay(ZoneId.systemDefault()).toEpochSecond()

    fun yearOf(sec: Long): Int = Instant.ofEpochSecond(sec).atZone(ZoneId.systemDefault()).year

    fun earliestSec(account: String, wxId: String, toSec: Long): Long? = WeDatabaseApi.getMessagesBetween(
        wxId, 0, toSec, 1, MessageRendering.TEXT_ONLY, account = account,
    ).firstOrNull()?.let { MessageRendering.toSeconds(it.createTime) }

    fun count(account: String, wxId: String, window: TimeWindow, types: Set<Int>): Int =
        WeDatabaseApi.countMessagesBetween(wxId, window.fromSec, window.toSec, types, account = account)

    fun load(account: String, wxId: String, window: TimeWindow, limit: Int): List<Line>? {
        val raw = WeDatabaseApi.getMessagesBetween(
            wxId, window.fromSec, window.toSec, limit + 1, MessageRendering.CHAT_TYPES, account = account,
        )
        if (!AiProfileStore.isCurrentAccount(account) || raw.size > limit) return null
        return raw.mapNotNull { message ->
            val text = MessageRendering.render(message.typeCode, message.content) ?: return@mapNotNull null
            Line(
                at = MessageRendering.toSeconds(message.createTime),
                fromMe = message.isSend == 1,
                text = text,
                isText = message.typeCode == MessageRendering.TYPE_TEXT,
            )
        }
    }

    fun transcript(lines: List<Line>): String {
        val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        return lines.joinToString("\n") { line ->
            val who = if (line.fromMe) "我" else "对方"
            "[${timeFormat.format(Instant.ofEpochSecond(line.at))}] $who: ${line.text}"
        }
    }
}
