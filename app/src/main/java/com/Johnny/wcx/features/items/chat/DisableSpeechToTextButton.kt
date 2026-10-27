package com.Johnny.wcx.features.items.chat

import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature

@Feature(name = "禁用输入框快捷语音转文字", categories = ["聊天"], description = "隐藏输入框右侧的语音转文字按钮")
object DisableSpeechToTextButton : SwitchFeature() {

    override fun onEnable() {
        // 上游 WeKit 实现：hook ChatFooter.getV2TBtnLayout，使其返回 null，
        // 语音转文字按钮布局不再提供，按钮即隐藏；不再走构造器 hook + 删视图树
        // （构造器 hook 在本机环境不触发，见 2026-10-27 诊断）。
        // 直接用 Java 反射取 Method，不走 ::getV2TBtnLayout.fastJavaMethod，
        // 避免引入 kotlin-reflect 运行时依赖；方法在微信 8.0.79 中真实存在且未混淆。
        ChatFooter::class.java.getMethod("getV2TBtnLayout").hookBefore {
            result = null
        }
    }
}
