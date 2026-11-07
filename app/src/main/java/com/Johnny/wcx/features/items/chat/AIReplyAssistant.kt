package com.Johnny.wcx.features.items.chat

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.Request

@Feature(name = "AI 回复助手", categories = ["聊天"],
    description = "长按智能回复、AI 自动回复：独立开关，统一模型配置、候选风格及自动回复规则")
object AIReplyAssistant : ClickableFeature() {
    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var revision by remember { mutableStateOf(0) }
            var manualOn by remember { mutableStateOf(SmartLongPressReply.configuredEnabled) }
            var autoOn by remember { mutableStateOf(AIAutoReply.configuredEnabled) }
            val state = remember(revision) { ReplyProfiles.read() }
            fun toggle(feature: SwitchFeature, next: Boolean) {
                if (feature.onBeforeToggle(next, context)) feature.applyToggle(next)
                revision++
            }
            AlertDialogContent(
                title = { Text("AI 回复助手") },
                text = {
                    DefaultColumn(scrollable = true) {
                        Text("两种模式分别开启。长按回复只填入草稿；自动回复会按名单和规则发送。")
                        if (state.damaged) Text("模型配置读取失败，回复请求已暂停。原配置保留，请恢复配置后使用。")
                        ListItem(headlineContent = { Text("长按智能回复") },
                            supportingContent = { Text("选择候选后手动发送，保留已有草稿") },
                            trailingContent = { Switch(checked = manualOn, onCheckedChange = {
                                toggle(SmartLongPressReply, it); manualOn = SmartLongPressReply.configuredEnabled
                            }) })
                        ProfileChoices(ReplyProfiles.Mode.MANUAL, state) { revision++ }
                        TextButton(onClick = { SmartLongPressReply.onClick(context) }) { Text("候选数量、回复风格与上下文") }
                        ListItem(headlineContent = { Text("AI 自动回复") },
                            supportingContent = { Text("仅在你开启后处理符合规则的新消息") },
                            trailingContent = { Switch(checked = autoOn, onCheckedChange = {
                                toggle(AIAutoReply, it); autoOn = AIAutoReply.configuredEnabled
                            }) })
                        ProfileChoices(ReplyProfiles.Mode.AUTO, state) { revision++ }
                        TextButton(onClick = { AIAutoReply.onClick(context) }) { Text("私聊、群聊、触发条件及回复规则") }
                        Text("模型配置")
                        state.providers.forEach { provider ->
                            TextButton(onClick = { editProfile(context, provider, state) { revision++ } }) {
                                Text("编辑 ${provider.name} · ${provider.model}")
                            }
                        }
                        if (!state.damaged) TextButton(onClick = { editProfile(context, null, state) { revision++ } }) { Text("新增模型配置") }
                    }
                },
                confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
            )
        }
    }

    @Composable
    private fun ProfileChoices(mode: ReplyProfiles.Mode, state: ReplyProfiles.State, changed: () -> Unit) {
        Text("使用模型配置")
        val selected = if (mode == ReplyProfiles.Mode.MANUAL) state.manual else state.auto
        state.providers.forEach { p ->
            ListItem(modifier = Modifier.clickable {
                ReplyProfiles.bind(mode, p.id)
                if (mode == ReplyProfiles.Mode.AUTO) AIAutoReply.cancelPending()
                changed()
            }, headlineContent = { Text("${if (p.id == selected) "✓ " else ""}${p.name} · ${p.model}") })
        }
    }

    private fun editProfile(context: ComponentActivity, profile: ReplyProfiles.Provider?, state: ReplyProfiles.State, changed: () -> Unit) {
        showComposeDialog(context) {
            val scope = rememberCoroutineScope()
            var name by remember { mutableStateOf(profile?.name ?: "新配置") }
            var url by remember { mutableStateOf(profile?.url ?: "https://api.deepseek.com/chat/completions") }
            var key by remember { mutableStateOf(profile?.key ?: "") }
            var model by remember { mutableStateOf(profile?.model ?: "deepseek-flash") }
            var loading by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            var models by remember { mutableStateOf<List<String>>(emptyList()) }
            val usage = listOfNotNull(
                "长按回复".takeIf { profile != null && state.manual == profile.id },
                "自动回复".takeIf { profile != null && state.auto == profile.id },
            ).joinToString("、").ifEmpty { "暂未使用" }
            AlertDialogContent(
                title = { Text("模型配置") },
                text = { DefaultColumn(scrollable = true) {
                    Text("当前用于：$usage。保存后同时影响使用此配置的模式。")
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("配置名称") }, singleLine = true)
                    OutlinedTextField(value = url, onValueChange = { url = it; models = emptyList(); error = null }, label = { Text("HTTPS API 地址") }, singleLine = true)
                    OutlinedTextField(value = key, onValueChange = { key = it; models = emptyList(); error = null }, label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("模型名称") }, singleLine = true)
                    TextButton(enabled = !loading && key.isNotBlank(), onClick = {
                        loading = true; error = null
                        val requestedUrl = url; val requestedKey = key.trim()
                        scope.launch {
                            try {
                                val request = Request.Builder().url(SmartReplyClient.endpoint(requestedUrl, models = true))
                                    .header("Authorization", "Bearer $requestedKey").build()
                                val list = SmartReplyClient.parseModelIds(SmartReplyClient.request(request, SmartReplyClient.Owner.MODELS))
                                if (url == requestedUrl && key.trim() == requestedKey) {
                                    models = list
                                    if (list.isEmpty()) error = "未返回模型，可手动填写"
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { if (url == requestedUrl && key.trim() == requestedKey) error = SmartReplyClient.errorText(e) }
                            finally { loading = false }
                        }
                    }) { Text(if (loading) "读取中…" else "读取可用模型") }
                    models.forEach { id -> TextButton(onClick = { model = id }) { Text(id) } }
                    error?.let { Text(it) }
                    Text("打开设置、保存和切换配置均不发送测试请求。密钥仅保存在本机模块配置中。")
                } },
                confirmButton = { TextButton(onClick = {
                    try {
                        ReplyProfiles.save(profile?.id, name, url, key, model)
                        AIAutoReply.cancelPending()
                        changed(); onDismiss()
                    } catch (e: Exception) {
                        error = if (e is IllegalArgumentException) "请检查配置名称、密钥格式及模型；最多保留 8 份配置" else SmartReplyClient.errorText(e)
                    }
                }) { Text("保存") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
            )
        }
    }
}
