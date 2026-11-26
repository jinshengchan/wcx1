package com.Johnny.wcx.features.items.chat

import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.Johnny.wcx.features.api.core.models.MessageInfo
import com.Johnny.wcx.features.api.core.models.MessageType
import com.Johnny.wcx.features.api.ui.WeChatMessageContextMenuApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.utils.ChatInfoIcon
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.android.copyToClipboard
import com.Johnny.wcx.utils.android.showToast
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Language
import kotlinx.coroutines.launch

@Feature(name = "消息翻译", categories = ["聊天"], description = "长按文字或引用消息，用 AI 或 Google 翻译成所选语言")
object MessageTranslate : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {
    private var engineValue by prefOption("msg_translate_engine", 0)
    private var targetLangLabel by prefOption("msg_translate_target_lang", "中文")

    override fun onEnable() = WeChatMessageContextMenuApi.addProvider(this)
    override fun onDisable() = WeChatMessageContextMenuApi.removeProvider(this)

    override fun getMenuItems() = listOf(
        WeChatMessageContextMenuApi.MenuItem(
            777101, "翻译", ChatInfoIcon, MaterialSymbols.Outlined.Language,
            isSupported = { msg -> isEnabled && runCatching { textOf(msg).isNotBlank() }.getOrDefault(false) },
            multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported,
        ) { view, _, msg ->
            if (isEnabled) runCatching { open(view, msg) }.onFailure {
                showToast(view.context, it.message ?: "无法打开翻译")
            }
        },
    )

    private fun textOf(msg: MessageInfo): String {
        val text = when {
            msg.type == MessageType.QUOTE -> msg.quoteMsgActualContent ?: msg.actualContent
            msg.type?.isText == true -> msg.actualContent
            else -> return ""
        }.trim()
        return text.takeUnless { it.startsWith("<") }.orEmpty()
    }

    private fun open(view: View, msg: MessageInfo) {
        val text = textOf(msg)
        if (text.isBlank()) return
        val session = TranslateSupport.session(TranslateSupport.Engine.fromValue(engineValue))
        val lang = TranslateSupport.Lang.fromLabel(targetLangLabel)
        showComposeDialog(view.context) { TranslationDialog(text, session, lang, onDismiss) }
    }

    @Composable
    override fun Ui() {
        var engine by remember { mutableStateOf(TranslateSupport.Engine.fromValue(engineValue)) }
        var showEngine by remember { mutableStateOf(false) }
        Column {
            TextButton(onClick = { showEngine = true }) { Text(engine.label) }
            DropdownMenu(showEngine, onDismissRequest = { showEngine = false }) {
                TranslateSupport.Engine.entries.forEach { option ->
                    DropdownMenuItem(text = { Text(option.label) }, onClick = {
                        engine = option
                        engineValue = option.value
                        showEngine = false
                    })
                }
            }
            TranslationLanguageChoice(targetLangLabel, onChange = { targetLangLabel = it })
        }
    }
}

@Composable
internal fun TranslationLanguageChoice(saved: String, onChange: (String) -> Unit) {
    var language by remember(saved) { mutableStateOf(TranslateSupport.Lang.fromLabel(saved)) }
    var expanded by remember { mutableStateOf(false) }
    Column {
        TextButton(onClick = { expanded = true }) { Text("目标语言：${language.label}") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            TranslateSupport.Lang.entries.forEach { option ->
                DropdownMenuItem(text = { Text(option.label) }, onClick = {
                    language = option
                    onChange(option.label)
                    expanded = false
                })
            }
        }
    }
}

@Composable
internal fun TranslationDialog(
    original: String,
    session: TranslateSupport.Session,
    lang: TranslateSupport.Lang,
    onDismiss: () -> Unit,
    applyDraft: ((String) -> Boolean)? = null,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialogContent(
        title = { Text("翻译成${lang.label}") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("确认翻译后，原文将发送给 ${session.host}。")
            Text(original)
            if (loading) Text("正在翻译…")
            if (output.isNotBlank()) Text(output)
            if (error.isNotBlank()) Text(error)
        } },
        dismissButton = { TextButton(onDismiss) { Text("关闭") } },
        confirmButton = {
            if (output.isNotBlank()) {
                TextButton(onClick = {
                    if (!TranslateSupport.isCurrent(session)) error = "账号或模型配置已变化"
                    else if (applyDraft == null) copyToClipboard(output)
                    else if (applyDraft(output)) onDismiss()
                    else error = "原草稿已变化，译文未写入"
                }) { Text(if (applyDraft == null) "复制译文" else "替换草稿") }
            } else {
                TextButton(enabled = !loading, onClick = {
                    loading = true
                    error = ""
                    scope.launch {
                        TranslateSupport.translate(original, session, lang).fold(
                            onSuccess = { output = it },
                            onFailure = { error = it.message ?: "翻译失败" },
                        )
                        loading = false
                    }
                }) { Text("翻译") }
            }
        },
    )
}
