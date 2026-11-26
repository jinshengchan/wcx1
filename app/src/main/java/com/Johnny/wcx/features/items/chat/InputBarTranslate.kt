package com.Johnny.wcx.features.items.chat

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.Johnny.wcx.features.api.ui.WeChatInputBarMenuApi
import com.Johnny.wcx.features.api.ui.WeCurrentConversationApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.android.showToast
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Language
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter

@Feature(
    name = "边写边译",
    categories = ["聊天"],
    description = "开启聊天输入栏增强后，从发送键或加号的长按菜单翻译当前草稿",
)
object InputBarTranslate : SwitchFeature(), WeChatInputBarMenuApi.IActionItemsProvider {
    private var targetLangLabel by prefOption("input_translate_target_lang", "中文")
    private val engineValue by prefOption("msg_translate_engine", 0)

    override fun onEnable() = WeChatInputBarMenuApi.addProvider(this)
    override fun onDisable() = WeChatInputBarMenuApi.removeProvider(this)

    override fun getActionItems() = listOf(
        WeChatInputBarMenuApi.ActionItem(
            id = "input_translate",
            icon = MaterialSymbols.Outlined.Language,
            label = "翻译输入框文本",
            isSupported = { _, footer -> isEnabled && WeCurrentConversationApi.chatFooter === footer },
            onClick = ::open,
        ),
    )

    private fun open(context: Context, footer: ChatFooter) {
        if (!isEnabled || WeCurrentConversationApi.chatFooter !== footer) return
        val talker = WeCurrentConversationApi.value
        val input = runCatching { footer.lastText }.getOrNull().orEmpty()
        if (input.isBlank()) {
            showToast(context, "请输入要翻译的文字")
            return
        }
        val engine = TranslateSupport.Engine.fromValue(engineValue)
        val language = TranslateSupport.Lang.fromLabel(targetLangLabel)
        val session = runCatching { TranslateSupport.session(engine) }.getOrElse {
            showToast(context, it.message ?: "翻译服务未配置")
            return
        }
        showComposeDialog(context) {
            TranslationDialog(input, session, language, onDismiss, applyDraft = { translated ->
                if (isEnabled && footer.isAttachedToWindow && WeCurrentConversationApi.chatFooter === footer &&
                    WeCurrentConversationApi.value == talker && TranslateSupport.isCurrent(session) &&
                    runCatching { footer.lastText == input }.getOrDefault(false)
                ) {
                    runCatching { footer.lastText = translated }.isSuccess
                } else false
            })
        }
    }

    @Composable
    override fun Ui() {
        Column {
            Text("目标语言", style = MaterialTheme.typography.titleSmall)
            TranslationLanguageChoice(targetLangLabel, onChange = { targetLangLabel = it })
            Text("翻译引擎与「消息翻译」共用配置。")
        }
    }
}
