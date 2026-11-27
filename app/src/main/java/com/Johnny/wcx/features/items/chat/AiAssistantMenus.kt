package com.Johnny.wcx.features.items.chat

import android.content.Context
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.ai.AiAssistantTools
import com.Johnny.wcx.ai.AiDailyReport
import com.Johnny.wcx.ai.AiMomentsComment
import com.Johnny.wcx.ai.AiProfileStore
import com.Johnny.wcx.ai.AiTool
import com.Johnny.wcx.ai.MessageRendering
import com.Johnny.wcx.ai.group.GroupDigestConfig
import com.Johnny.wcx.features.ai.AiConfig
import com.Johnny.wcx.features.api.ui.WeChatInputBarMenuApi
import com.Johnny.wcx.features.api.ui.WeChatMessageContextMenuApi
import com.Johnny.wcx.features.api.ui.WeCurrentConversationApi
import com.Johnny.wcx.features.api.ui.WeMomentsApi
import com.Johnny.wcx.features.api.ui.WeMomentsContextMenuApi
import com.Johnny.wcx.features.core.ApiFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.utils.SendIcon
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.android.copyToClipboard
import com.Johnny.wcx.utils.android.showToast
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Send
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal object AiToolSettings {
    var doubleTapEnabled: Boolean
        get() = WePrefs.getBoolOrDef("ai_workbench_double_tap", true)
        set(value) { WePrefs.putBool("ai_workbench_double_tap", value) }

    fun enabled(tool: AiTool): Boolean = WePrefs.getBoolOrDef("ai_workbench_tool_${tool.name}", true)

    fun setEnabled(tool: AiTool, enabled: Boolean) =
        WePrefs.putBool("ai_workbench_tool_${tool.name}", enabled)
}

@Feature(name = "AI 助手菜单", categories = ["API"], description = "注册输入栏、消息和朋友圈里的 AI 工具")
object AiAssistantMenus : ApiFeature() {
    internal val inputTools = listOf(
        AiTool.CHAT_SUMMARY, AiTool.TODO, AiTool.REPLY, AiTool.Q_AND_A, AiTool.ANNOUNCEMENT,
        AiTool.UNREAD, AiTool.TODAY, AiTool.WEEKLY, AiTool.SEARCH, AiTool.MINUTES, AiTool.NEED_REPLY,
    )
    internal val messageTools = listOf(AiTool.REMARK, AiTool.IMAGE, AiTool.POLISH, AiTool.LONG_TEXT)

    private fun available(tool: AiTool): Boolean = AiConfig.isMasterEnabled && AiToolSettings.enabled(tool)

    private val inputProvider = WeChatInputBarMenuApi.IActionItemsProvider {
        val talker = WeCurrentConversationApi.value
        inputTools.map { tool ->
            WeChatInputBarMenuApi.ActionItem(
                id = "wcx_ai_${tool.name}",
                icon = MaterialSymbols.Outlined.Send,
                label = "AI ${tool.title}",
                isSupported = { _, footer ->
                    available(tool) && WeCurrentConversationApi.chatFooter === footer && AiAssistantTools.visible(talker) &&
                        (tool !in listOf(AiTool.ANNOUNCEMENT, AiTool.MINUTES) || talker.endsWith("@chatroom"))
                },
                onClick = { context, footer ->
                    if (WeCurrentConversationApi.chatFooter === footer && WeCurrentConversationApi.value == talker) {
                        showAiTool(context, tool, talker = talker)
                    }
                },
            )
        }
    }

    private val messageProvider = WeChatMessageContextMenuApi.IMenuItemsProvider {
        messageTools.mapIndexed { index, tool ->
            WeChatMessageContextMenuApi.MenuItem(
                id = 777071 + index,
                text = "AI ${tool.title}",
                drawable = SendIcon,
                imageVector = MaterialSymbols.Outlined.Send,
                isSupported = { msg ->
                    available(tool) && AiAssistantTools.visible(msg.talker) && when (tool) {
                        AiTool.IMAGE -> msg.typeCode == MessageRendering.TYPE_IMAGE && msg.serverId > 0
                        AiTool.REMARK -> msg.type?.isText == true && !msg.isInGroupChat
                        AiTool.LONG_TEXT -> msg.type?.isText == true && msg.actualContent.length >= 80
                        else -> msg.type?.isText == true
                    }
                },
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported,
                onClick = { view, _, msg ->
                    if (tool == AiTool.IMAGE) showAiTool(view.context, tool, talker = msg.talker, imageId = msg.serverId)
                    else showAiTool(view.context, tool, talker = msg.talker, selectedText = msg.actualContent)
                },
            )
        }
    }

    private val momentsProvider = WeMomentsContextMenuApi.IMenuItemsProvider {
        listOf(WeMomentsContextMenuApi.MenuItem(
            id = 777075,
            text = "AI 帮我评论",
            drawable = SendIcon,
            shouldShow = { _, _ -> available(AiTool.MOMENTS_COMMENT) && AiConfig.isConfigured(AiConfig.Purpose.TOOLS) },
            onClick = { moment ->
                if (available(AiTool.MOMENTS_COMMENT)) {
                    val account = AiConfig.account()
                    val proto = runCatching { WeMomentsApi.getTimelineProto(moment.snsInfo) }.getOrNull()
                    val author = (proto?.userName ?: WeMomentsApi.getOwnerWxId(moment)).orEmpty()
                    val text = (proto?.contentDesc ?: WeMomentsApi.getContentText(moment.snsInfo)).orEmpty().trim()
                    val media = proto?.contentObj?.mediaList.orEmpty()
                    if (account == null || author == account || !AiAssistantTools.visible(author)) {
                        showToast(moment.activity, "这条朋友圈不适用 AI 评论")
                    } else if (text.isBlank() && media.isEmpty()) {
                        showToast(moment.activity, "这条朋友圈没有可用的正文或图片，可打开详情后再试")
                    } else {
                        val timeline = moment.timelineObject ?: WeMomentsApi.getNativeTimeline(moment.snsInfo)
                        val native = timeline?.let { runCatching { WeMomentsApi.getNativeMediaList(it) }.getOrNull() }
                        val post = AiMomentsComment.Post(author, text, media, native, proto?.contentObj?.type in listOf(5, 15))
                        showAiTool(moment.activity, AiTool.MOMENTS_COMMENT, talker = author, expectedAccount = account, momentsPost = post)
                    }
                }
            },
        ))
    }

    internal fun inputActions(context: Context, footer: ChatFooter): List<WeChatInputBarMenuApi.ActionItem> =
        if (AiConfig.isMasterEnabled) {
            inputProvider.getActionItems().filter { it.isSupported(context, footer) }
        } else emptyList()

    override fun onEnable() {
        WeChatInputBarMenuApi.addProvider(inputProvider)
        WeChatMessageContextMenuApi.addProvider(messageProvider)
        WeMomentsContextMenuApi.addProvider(momentsProvider)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(inputProvider)
        WeChatMessageContextMenuApi.removeProvider(messageProvider)
        WeMomentsContextMenuApi.removeProvider(momentsProvider)
    }
}

internal fun showAiTool(
    context: Context,
    tool: AiTool,
    talker: String? = null,
    selectedText: String? = null,
    imageId: Long? = null,
    workGroup: GroupDigestConfig? = null,
    expectedAccount: String? = null,
    onResult: ((String) -> Unit)? = null,
    momentsPost: AiMomentsComment.Post? = null,
) {
    if (!AiConfig.isMasterEnabled || !AiToolSettings.enabled(tool)) return
    val selection = AiConfig.selection(AiConfig.Purpose.TOOLS)
    if (selection == null) {
        showToast(context, "请在「AI 助手 → 设置」选择工具模型")
        return
    }
    if (expectedAccount != null && selection.account != expectedAccount) {
        showToast(context, "微信账号已切换，请重新打开 AI 助手")
        return
    }
    showComposeDialog(context) {
        val scope = rememberCoroutineScope()
        var input by remember { mutableStateOf("") }
        var result by remember { mutableStateOf("") }
        var errorMessage by remember { mutableStateOf("") }
        var loading by remember { mutableStateOf(false) }
        var includeProfile by remember { mutableStateOf(false) }
        var includeImages by remember { mutableStateOf(momentsPost?.hasImages == true) }
        var comment by remember { mutableStateOf<AiMomentsComment.Result?>(null) }
        var copiedIndex by remember { mutableStateOf<Int?>(null) }
        var showReasoning by remember { mutableStateOf(false) }
        val canIncludeProfile by androidx.compose.runtime.produceState(false, talker) {
            value = tool == AiTool.MOMENTS_COMMENT && talker != null && withContext(Dispatchers.IO) {
                AiProfileStore.readProfile(talker, selection.account)?.isReady == true
            }
        }

        AlertDialogContent(
            title = { Text(tool.title) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val scopeDescription = when (tool) {
                        AiTool.UNREAD -> "近期未读会话的部分消息"
                        AiTool.NEED_REPLY -> "最近七天内活跃会话的部分消息"
                        AiTool.TODAY, AiTool.DAILY_REPORT -> "今天活跃会话的部分消息"
                        AiTool.WEEKLY -> "本周活跃会话的部分消息"
                        AiTool.SEARCH -> "最近三十天内匹配关键词的消息片段"
                        AiTool.IMAGE -> "选中的图片"
                        AiTool.WORK_PROGRESS -> "该群最近七天的部分消息和已保存的工作目标"
                        AiTool.ANNOUNCEMENT, AiTool.MOMENTS_COPY -> "你填写的主题"
                        AiTool.MOMENTS_COMMENT -> "这条朋友圈的正文和下方勾选的素材"
                        AiTool.REMARK, AiTool.POLISH, AiTool.LONG_TEXT -> "选中的消息"
                        else -> "当前聊天的部分近期消息"
                    }
                    Text("生成时会发送$scopeDescription 至 ${selection.endpointHost}。")
                    if (momentsPost?.hasImages == true) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = includeImages, onCheckedChange = { includeImages = it }, enabled = !loading && comment == null)
                            Text(if (momentsPost.video) "同时发送视频封面（如可用）" else "同时发送最多 3 张图片")
                        }
                    }
                    if (canIncludeProfile) {
                        androidx.compose.foundation.layout.Row(
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = includeProfile, onCheckedChange = { includeProfile = it }, enabled = !loading && comment == null)
                            Text("同时发送好友档案摘要和近期事件标题")
                        }
                    }
                    tool.inputLabel?.let { label ->
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it.take(300) },
                            label = { Text(label) },
                            enabled = !loading,
                        )
                    }
                    if (loading) Text("正在生成…")
                    if (errorMessage.isNotBlank()) Text(errorMessage)
                    if (result.isNotBlank()) Text(result)
                    comment?.let { generated ->
                        generated.note?.let { Text(it) }
                        if (generated.imagesUsed > 0) Text("已参考 ${generated.imagesUsed} 张图片")
                        generated.replies.forEachIndexed { index, reply ->
                            Text(reply)
                            TextButton(onClick = {
                                if (AiConfig.account() == selection.account && AiConfig.isMasterEnabled &&
                                    momentsPost?.author?.let(AiAssistantTools::visible) == true
                                ) {
                                    copyToClipboard(reply)
                                    copiedIndex = index
                                }
                            }) { Text(if (copiedIndex == index) "已复制" else "复制这条") }
                        }
                        generated.reasoning?.let { reasoning ->
                            TextButton(onClick = { showReasoning = !showReasoning }) {
                                Text(if (showReasoning) "收起思考过程" else "查看思考过程")
                            }
                            if (showReasoning) Text(reasoning)
                        }
                    }
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
            confirmButton = {
                if (result.isNotBlank()) {
                    TextButton(onClick = {
                        if (AiConfig.account() == selection.account && AiConfig.isMasterEnabled) copyToClipboard(result)
                    }) { Text("复制") }
                } else if (comment == null) {
                    TextButton(
                        enabled = !loading && (tool.inputLabel == null || input.isNotBlank()) &&
                            (momentsPost == null || momentsPost.text.isNotBlank() || includeImages),
                        onClick = {
                            if (loading) return@TextButton
                            val reportDate = if (tool == AiTool.DAILY_REPORT) LocalDate.now() else null
                            loading = true
                            errorMessage = ""
                            scope.launch {
                                try {
                                    val generated = withContext(Dispatchers.IO) {
                                        val binding = checkNotNull(AiConfig.capture(AiConfig.Purpose.TOOLS)) {
                                            "AI 助手已关闭或模型不可用"
                                        }
                                        check(binding.id == selection.id && binding.account == selection.account) {
                                            "总开关、模型配置或微信账号已变化"
                                        }
                                        if (momentsPost != null) return@withContext AiMomentsComment.generate(
                                            momentsPost, binding, includeImages, includeProfile,
                                        )
                                        val response = AiAssistantTools.run(
                                            tool, binding, input.trim(), talker, selectedText, imageId, workGroup, reportDate,
                                        )
                                        if (reportDate != null) {
                                            check(AiDailyReport.saveReport(binding, reportDate, response)) {
                                                "总结保存失败，请检查模型和微信账号"
                                            }
                                        }
                                        response
                                    }
                                    check(AiConfig.isMasterEnabled && AiConfig.account() == selection.account) {
                                        "总开关或微信账号已变化"
                                    }
                                    when (generated) {
                                        is AiMomentsComment.Result -> comment = generated
                                        is String -> {
                                            result = generated
                                            onResult?.invoke(generated)
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    errorMessage = e.message ?: "生成失败"
                                } finally {
                                    loading = false
                                }
                            }
                        },
                    ) { Text("生成") }
                }
            },
        )
    }
}
