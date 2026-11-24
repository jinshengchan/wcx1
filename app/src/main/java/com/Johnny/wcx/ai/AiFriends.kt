// Adapted from AARCNoa/zhiyou, AiFriends.kt at 59f9083. SPDX-License-Identifier: GPL-3.0-only
package com.Johnny.wcx.ai

import com.Johnny.wcx.features.api.core.WeDatabaseApi

object AiFriends {

    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000

    fun refresh(account: String? = AiProfileStore.accountId()): Int {
        if (account == null || !AiProfileStore.isCurrentAccount(account)) return -1
        val list = runCatching { WeDatabaseApi.getFriends() }.getOrElse { return -1 }
            .asSequence()
            .filter { it.wxId.isNotBlank() && it.wxId != account }
            .map {
                AiFriend(it.wxId, it.nickname, it.remarkName, it.initialNickname, it.nicknamePinyin, it.avatarUrl)
            }
            .toList()
        return if (AiProfileStore.saveFriends(list, account)) list.size else -1
    }

    fun load(account: String? = AiProfileStore.accountId()): List<AiFriend> =
        AiProfileStore.loadFriends(account).friends

    fun add(friend: AiFriend, account: String): Boolean = AiProfileStore.saveProfile(
        AiProfile(
            wxId = friend.wxId,
            nickname = friend.nickname,
            remark = friend.remark,
            pyInitial = friend.pyInitial,
            quanPin = friend.quanPin,
            avatarUrl = friend.avatarUrl,
            createdAt = System.currentTimeMillis(),
        ),
        account,
    )

    fun loadOrRefresh(maxAgeMs: Long = MAX_AGE_MS, account: String? = AiProfileStore.accountId()): List<AiFriend> {
        val snapshot = AiProfileStore.loadFriends(account)
        val fresh = snapshot.exportedAt > 0 && System.currentTimeMillis() - snapshot.exportedAt < maxAgeMs
        val friends = if (!fresh && refresh(account) >= 0) load(account) else snapshot.friends
        return if (AiProfileStore.isCurrentAccount(account)) friends else emptyList()
    }
}
