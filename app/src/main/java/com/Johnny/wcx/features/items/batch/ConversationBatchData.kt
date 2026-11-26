// Conversation selection adapted from Johnny520/wcx v348 (GPL-3.0).
package com.Johnny.wcx.features.items.batch

import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeConversationApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.api.core.models.IWeContact
import com.Johnny.wcx.features.items.chat.ConversationAggregation
import com.Johnny.wcx.features.items.contacts.HideContacts
import com.Johnny.wcx.features.items.secret_friend.SecretFriendState
import com.tencent.wcdb.database.SQLiteDatabase

internal enum class ConversationCategory(val label: String) {
    OFFICIAL("公众号（全部）"),
    SUBSCRIPTION("订阅号"),
    GROUP("群聊"),
    DIRECT("单聊"),
}

internal data class BatchConversation(
    override val wxId: String,
    override val nickname: String,
    val remark: String,
    val unread: Int,
    val lastActive: Long,
    val category: ConversationCategory,
) : IWeContact {
    override val displayName: String get() = remark.ifBlank { nickname }.ifBlank { wxId }
    override val avatarUrl: String get() = ""
}

internal data class ConversationCriteria(
    val categories: Set<ConversationCategory>,
    val mutedOnly: Boolean,
    val noUnreadOnly: Boolean,
) {
    fun accepts(row: BatchConversation): Boolean =
        (row.category in categories ||
            (row.category == ConversationCategory.SUBSCRIPTION && ConversationCategory.OFFICIAL in categories)) &&
            (!noUnreadOnly || row.unread == 0) &&
            (!mutedOnly || WeConversationApi.isDnd(row.wxId))
}

/** Binds a preview and its later actions to the verified local database and account. */
internal class ConversationBatchData private constructor(private val account: String, private val database: SQLiteDatabase) {
    fun isCurrent(): Boolean = WeDatabaseApi.isReady && WeDatabaseApi.db === database && WeApi.selfWxId == account

    fun isVisible(id: String): Boolean = id.isNotBlank() && id != account && id !in SYSTEM_ACCOUNTS &&
        !id.startsWith(ConversationAggregation.FOLDER_PREFIX) &&
        id !in HideContacts.hiddenContacts && !SecretFriendState.isStoredSecret(id)

    // An absent bizinfo table leaves subscriptions unclassified while still allowing the all-official category.
    private val bizType by lazy {
        val columns = database.rawQuery("PRAGMA table_info(bizinfo)", null).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(1).lowercase())
            }
        }
        if ("username" in columns && "type" in columns) "(SELECT b.type FROM bizinfo b WHERE b.username = c.username LIMIT 1)" else "NULL"
    }

    fun loadConversations(id: String? = null, unreadOnly: Boolean = false): List<BatchConversation> {
        val conditions = buildList {
            if (id != null) add("c.username = ?")
            if (unreadOnly) add("($CONVERSATION_UNREAD) > 0")
        }
        val filter = if (conditions.isEmpty()) "" else conditions.joinToString(" AND ", prefix = "WHERE ")
        return readConversations(
            """
            SELECT c.username, r.nickname, r.conRemark, c.conversationTime,
                   $CONVERSATION_UNREAD,
                   $bizType, r.username IS NOT NULL
            FROM rconversation c
            LEFT JOIN rcontact r ON r.username = c.username
            $filter
            ORDER BY c.conversationTime DESC
            """.trimIndent(),
            id?.let { arrayOf<Any>(it) },
        )
    }

    fun loadFollowed(): List<BatchConversation> = readConversations(
        """
        SELECT r.username, r.nickname, r.conRemark, IFNULL(c.conversationTime, 0), 0, NULL, 1
        FROM rcontact r
        LEFT JOIN rconversation c ON c.username = r.username
        WHERE substr(r.username, 1, 3) = 'gh_' AND (r.type & 1) != 0 AND (r.type & 32) = 0
        ORDER BY c.conversationTime DESC
        """.trimIndent(),
        null,
    )

    fun stillFollowed(ids: Set<String>): Set<String> =
        matching(ids) { params ->
            "SELECT username FROM rcontact WHERE username IN ($params) AND (type & 1) != 0 AND (type & 32) = 0"
        }

    fun stillUnread(ids: Set<String>): Set<String> =
        matching(ids) { params ->
            "SELECT c.username FROM rconversation c WHERE c.username IN ($params) AND ($CONVERSATION_UNREAD) > 0"
        }

    private fun matching(ids: Set<String>, statement: (String) -> String): Set<String> {
        check(isCurrent()) { "微信账号已切换" }
        val result = linkedSetOf<String>()
        for (page in ids.chunked(200)) {
            val params = page.joinToString(",") { "?" }
            database.rawQuery(statement(params), page.map<String, Any> { it }.toTypedArray()).use { cursor ->
                while (cursor.moveToNext()) result += cursor.getString(0)
            }
        }
        check(isCurrent()) { "微信账号已切换" }
        return result
    }

    private fun readConversations(sql: String, args: Array<Any>?): List<BatchConversation> {
        check(isCurrent()) { "微信账号已切换" }
        val rows = database.rawQuery(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0).orEmpty()
                    if (!isVisible(id)) continue
                    val category = when {
                        id.startsWith("gh_") && !cursor.isNull(5) && cursor.getInt(5) == 0 -> ConversationCategory.SUBSCRIPTION
                        id.startsWith("gh_") -> ConversationCategory.OFFICIAL
                        id.endsWith("@chatroom") -> ConversationCategory.GROUP
                        !id.contains('@') && cursor.getInt(6) != 0 -> ConversationCategory.DIRECT
                        else -> continue
                    }
                    add(BatchConversation(
                        id, cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(),
                        cursor.getInt(4), cursor.getLong(3), category,
                    ))
                }
            }
        }
        check(isCurrent()) { "微信账号已切换" }
        return rows.distinctBy { it.wxId }
    }

    companion object {
        private const val CONVERSATION_UNREAD =
            "MAX(IFNULL(c.unReadCount, 0), IFNULL(c.unReadMuteCount, 0), IFNULL(c.atCount, 0))"

        private val SYSTEM_ACCOUNTS = setOf(
            "officialaccounts", "notification_messages", "notifymessage", "fmessage", "medianote",
            "floatbottle", "qqmail", "tmessage", "blogapp", "weixin", "helper_entry",
            "brandsessionholder", "brandservicesessionholder", "filehelper", "mphelper",
            "qqsafe", "weibo", "voiceassist", "exmail_tool", "qqfriend", "newsapp",
        )

        fun capture(): ConversationBatchData {
            val account = checkNotNull(WeDatabaseApi.currentAiAccount()) { "微信账号或消息库尚未就绪" }
            return ConversationBatchData(account, WeDatabaseApi.db).also {
                check(it.isCurrent() && WeDatabaseApi.currentAiAccount() == account) { "微信账号已切换" }
            }
        }
    }
}
