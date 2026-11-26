package com.Johnny.wcx.activity.settings.ai

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.Johnny.wcx.ai.AiProfileInitializer
import com.Johnny.wcx.ai.AiProfileStore
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.features.ai.AiConfig.Purpose
import com.Johnny.wcx.features.api.core.models.IWeContact
import com.Johnny.wcx.features.items.chat.ReplyProfiles
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.SingleContactSelector
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

internal fun AiWorkbenchDialog(context: Context) = showComposeDialog(context) {
    var enabled by remember { mutableStateOf(AiConfig.isMasterEnabled) }
    var friend by remember { mutableStateOf<IWeContact?>(null) }
    val originalAccount by produceState<String?>(null) {
        while (value == null) {
            value = withContext(Dispatchers.IO) { AiProfileStore.accountId() }
            if (value == null) delay(1000)
        }
    }
    val account = originalAccount
    var accountChanged by remember { mutableStateOf(false) }
    if (account != null) {
        LaunchedEffect(account) {
            withContext(Dispatchers.IO) { AiProfileInitializer.recoverInterrupted(account) }
            while (withContext(Dispatchers.IO) { AiProfileStore.accountId() } == account) delay(1000)
            accountChanged = true
        }
    }

    AlertDialogContent(
        title = { Text("AI 助手") },
        text = {
            when {
                accountChanged -> Text("微信账号已切换，请关闭后重新打开工作台。")
                else -> {
                    val pages = if (enabled) listOf("好友", "群组", "总结", "说明", "设置") else listOf("说明", "设置")
                    val pager = key(enabled) {
                        rememberPagerState(initialPage = pages.lastIndex) { pages.size }
                    }
                    val scope = rememberCoroutineScope()
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            pages.forEachIndexed { index, label ->
                                Tab(
                                    selected = pager.currentPage == index,
                                    modifier = Modifier.width(58.dp),
                                    onClick = { scope.launch { pager.animateScrollToPage(index) } },
                                    text = { Text(label) },
                                )
                            }
                        }
                        HorizontalPager(state = pager, verticalAlignment = Alignment.Top) { page ->
                            when (pages[page]) {
                                "设置" -> AiAssistantSettings(context, account, enabled) {
                                    AiConfig.setMasterEnabled(it)
                                    enabled = it
                                }
                                "说明" -> AiAssistantHelp()
                                else -> if (account == null) {
                                    Text("请先打开微信主界面，等待当前账号消息库就绪。")
                                } else when (pages[page]) {
                                    "好友" -> FriendArchivePane(context, account, friend) { friend = it }
                                    "群组" -> GroupDigestPane(context, account)
                                    "总结" -> AiAssistantReports(context, account)
                                    else -> Unit
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
internal fun AiProviderChoice(purpose: Purpose, revision: Int, onSelection: (ReplyProfiles.Provider?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val profiles = remember(revision) { ReplyProfiles.read() }
    val selected = remember(revision) { profiles.providers.firstOrNull { it.id == AiConfig.providerId(purpose) } }
    Column {
        TextButton(onClick = { expanded = true }, enabled = profiles.providers.isNotEmpty()) {
            Text(selected?.let { "模型：${it.displayName()}" } ?: "选择模型配置")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (listOf(null) + profiles.providers).forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider?.displayName() ?: "清除选择") },
                    onClick = {
                        expanded = false
                        onSelection(provider)
                    },
                )
            }
        }
        when {
            profiles.damaged -> Text("模型配置无法读取，请在「AI 回复助手」中检查。")
            profiles.providers.isEmpty() -> Text("请先在「AI 回复助手」中新增模型配置。")
            selected == null -> Text("请选择用于此功能的模型配置。")
            !AiConfig.isConfigured(purpose) -> Text("当前模型配置缺少所需信息，请在「AI 回复助手」中检查。")
        }
    }
}

internal fun changeAiProvider(
    context: Context,
    account: String,
    purpose: Purpose,
    provider: ReplyProfiles.Provider?,
    onChanged: () -> Unit,
) {
    val id = provider?.id.orEmpty()
    val updated = runCatching {
        AiConfig.chooseProvider(purpose, id, expectedAccount = account)
    }.getOrDefault(false)
    if (updated) onChanged()
    else showToast(context, "模型配置或微信账号已变化，请重新打开工作台。")
}

private fun ReplyProfiles.Provider.displayName() = "$name · $model"

internal fun AiConfig.Selection.displayName() = "$providerName · $model（$endpointHost）"

internal const val MEDIA_PLACEHOLDERS = "图片、语音等以占位文字表示"

internal fun chooseAiContact(
    context: Context,
    title: String,
    selectedId: String?,
    load: () -> List<IWeContact>,
    onSelection: (IWeContact) -> Unit,
) = showComposeDialog(context) {
    var failed by remember { mutableStateOf(false) }
    val contacts by produceState<List<IWeContact>?>(null) {
        val loaded = withContext(Dispatchers.IO) { runCatching(load) }
        failed = loaded.isFailure
        value = loaded.getOrNull()
    }
    val options = contacts
    if (!options.isNullOrEmpty()) {
        SingleContactSelector(title, options, selectedId?.takeIf { id -> options.any { it.wxId == id } }, onDismiss) { id ->
            options.firstOrNull { it.wxId == id }?.let(onSelection)
            onDismiss()
        }
    } else {
        AlertDialogContent(
            title = { Text(title) },
            text = { Text(if (failed || options != null) "未读取到会话，请确认微信已登录后重试。" else "正在读取会话…") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        )
    }
}

internal fun confirmAiAction(
    context: Context,
    title: String,
    description: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
) = showComposeDialog(context) {
    AlertDialogContent(
        title = { Text(title) },
        text = { Text(description) },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            Button(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(confirmLabel) }
        },
    )
}

@Composable
internal fun WorkbenchStatus(message: String) {
    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun timeLabel(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    .format(Date(ms))
