package com.Johnny.wcx.features.items.contacts

import android.content.ContentValues
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.items.secret_friend.SecretFriendState
import com.Johnny.wcx.utils.strings.isGroupChatWxId

internal fun groupAutomationAllowed(account: String, group: String): Boolean =
    account.isNotBlank() && WeApi.selfWxId == account && group.isGroupChatWxId &&
        group !in HideContacts.hiddenContacts && !SecretFriendState.isStoredSecret(group)

internal fun freshGroupMessage(values: ContentValues): Boolean {
    val raw = values.getAsLong("createTime") ?: return false
    val ms = if (raw in 1..9_999_999_999L) raw * 1000 else raw
    return ms > 0 && System.currentTimeMillis() - ms in -10_000L..120_000L
}

/**
 * 群系统消息解析器（`message.type == 10000`）。
 *
 * ## 为什么是「文案匹配」而不是 dex 锚点
 * 群系统消息的正文由**微信服务器下发**（例如 `你邀请"张三"加入了群聊`），
 * 8.0.80 的 dex 字符串池里**不存在** `加入了群聊` / `退出了群聊` 这类常量
 * （已用 `apk_strings80.txt` 全量 grep 复核，零命中），因此无法用 DexKit 锚点定位，
 * 只能按服务端文案做多模式匹配——这也是同类模块（微x、WeKit 等）的通行做法。
 *
 * 为了尽量稳，这里：
 * 1. 关键词多套并列（含历史文案与英文文案），任一命中即算；
 * 2. 昵称只从成对引号里取（服务端对昵称统一加引号），取不到就**放弃**该事件，
 *    宁可漏发也不发错人；
 * 3. 先判「移出」再判「退出」再判「加入」，三条文案互不为子串，顺序仅为确定性。
 *
 * ## 已覆盖的文案（8.0.80 实测样例）
 * - 加入：`"张三"加入了群聊` / `你邀请"张三"加入了群聊` / `"张三"邀请"李四"加入了群聊`
 *   / `"张三"通过扫描"李四"分享的二维码加入群聊` / `"张三"通过群聊邀请确认加入群聊`
 * - 退出：`"张三"退出了群聊`
 * - 移出：`你已将"张三"移出了群聊` / `"张三"将"李四"移出了群聊`
 *
 * ## 已知限制
 * - 一次邀请多人（`你邀请"张三"和"李四"加入了群聊`）在「加入」场景会尽量取全部昵称，
 *   但「移出/退出」场景只取首个；
 * - 服务端改文案（极罕见）会导致匹配失败，此时功能静默跳过，不会误发。
 */
internal enum class GroupSystemEventKind {
    /** 有新成员入群 */
    JOIN,

    /** 成员主动退群 */
    LEAVE,

    /** 成员被移出（踢人） */
    KICK,
}

internal data class GroupSystemEvent(
    /** 变动类型 */
    val kind: GroupSystemEventKind,
    /** 变动成员昵称；判定失败为空列表（调用方应跳过） */
    val targets: List<String>,
    /** 操作者昵称（`你` / 另一位成员），无法判定时为 null */
    val operator: String?,
    /** 原始正文，便于日志与模板兜底 */
    val raw: String,
) {
    /** 主昵称，多人时用「、」连接 */
    val displayNames: String
        get() = targets.joinToString("、")

    /** 动作中文描述，供通知模板使用 */
    val actionText: String
        get() = when (kind) {
            GroupSystemEventKind.JOIN -> "加入"
            GroupSystemEventKind.LEAVE -> "退出"
            GroupSystemEventKind.KICK -> "被移出"
        }
}

internal object GroupSystemMsgParser {

    /** 微信「系统消息」类型码；与 `MessageType.SYSTEM.code` 一致（这里写字面量避免弃用告警） */
    const val SYSTEM_MSG_TYPE = 10000

    /** 群文本消息类型码；与 `MessageType.TEXT.code` 一致 */
    const val GROUP_TEXT_MSG_TYPE = 1

    /** 防御：异常超长正文直接忽略 */
    private const val MAX_CONTENT_LENGTH = 512

    /** 昵称统一被服务端包在成对引号里（全角、直角、半角都出现过） */
    private val QUOTED_NAME = Regex("[“\"「『]([^”\"」』]{1,64})[”\"」』]")

    private val JOIN_KEYWORDS = listOf(
        "加入了群聊",
        "加入群聊",
        "加入了该群聊",
        "通过扫描",
        "通过群聊邀请",
        "joined the group",
    )

    private val KICK_KEYWORDS = listOf(
        "移出了群聊",
        "移出群聊",
        "被移出群",
        "removed from the group",
    )

    private val LEAVE_KEYWORDS = listOf(
        "退出了群聊",
        "退出群聊",
        "left the group",
    )

    private val ENGLISH_INVITE = Regex("\\binvited\\b.*\\bgroup\\b", RegexOption.IGNORE_CASE)
    private val ENGLISH_KICK = Regex("\\bkicked\\b.*\\bgroup\\b", RegexOption.IGNORE_CASE)
    private val PASSIVE_ACTION = Regex("\\b(?:was|were) (?:invited|kicked|removed)\\b", RegexOption.IGNORE_CASE)
    private val SELF_OPERATOR = Regex("^(?:你|you\\b)", RegexOption.IGNORE_CASE)

    fun isSystemMessage(typeCode: Int): Boolean = typeCode == SYSTEM_MSG_TYPE

    /**
     * 解析群系统消息正文；不是成员变动类消息时返回 null。
     */
    fun parse(content: String?): GroupSystemEvent? {
        val raw = content?.trim().orEmpty()
        if (raw.isEmpty() || raw.length > MAX_CONTENT_LENGTH) return null

        val actionText = QUOTED_NAME.replace(raw, "").trim()
        val englishInvite = ENGLISH_INVITE.containsMatchIn(actionText)
        val kind = when {
            KICK_KEYWORDS.any { actionText.contains(it, ignoreCase = true) } ||
                ENGLISH_KICK.containsMatchIn(actionText) -> GroupSystemEventKind.KICK
            LEAVE_KEYWORDS.any { actionText.contains(it, ignoreCase = true) } -> GroupSystemEventKind.LEAVE
            JOIN_KEYWORDS.any { actionText.contains(it, ignoreCase = true) } ||
                englishInvite -> GroupSystemEventKind.JOIN
            else -> return null
        }

        val names = QUOTED_NAME.findAll(raw)
            .mapNotNull { it.groupValues.getOrNull(1)?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .toList()
        if (names.isEmpty()) return null

        val selfOperated = SELF_OPERATOR.containsMatchIn(raw)
        val targets = when {
            names.size == 1 -> names
            selfOperated -> if (kind == GroupSystemEventKind.JOIN) names else names.take(1)
            PASSIVE_ACTION.containsMatchIn(actionText) -> names.take(1)
            kind == GroupSystemEventKind.JOIN && actionText.contains("通过") -> names.take(1)
            kind == GroupSystemEventKind.JOIN && (actionText.contains("邀请") || englishInvite) -> names.drop(1)
            else -> names.takeLast(1)
        }
        val operator = if (selfOperated) "你" else names.firstOrNull { it !in targets }
        return GroupSystemEvent(kind, targets, operator, raw)
    }
}
