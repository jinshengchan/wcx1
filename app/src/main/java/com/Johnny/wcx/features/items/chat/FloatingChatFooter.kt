package com.Johnny.wcx.features.items.chat

import android.app.Activity
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.activity.ComponentActivity
import androidx.compose.material3.ListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.tencent.mm.ui.chatting.ChattingUI
import dev.ujhhgtg.reflekt.reflekt
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.ClickableFeature
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.items.chat.FloatingChatFooter.bottomGapDp
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import com.Johnny.wcx.ui.content.AlertDialogContent
import com.Johnny.wcx.ui.content.Button
import com.Johnny.wcx.ui.content.DefaultColumn
import com.Johnny.wcx.ui.content.TextButton
import com.Johnny.wcx.ui.utils.showComposeDialog
import com.Johnny.wcx.utils.WeLogger
import kotlin.math.roundToInt

@Feature(
    name = "悬浮输入框",
    categories = ["聊天"],
    description = "将聊天输入框改为悬浮卡片形式, 带有圆角、阴影和侧边距"
)
object FloatingChatFooter : ClickableFeature(), IResolveDex {

    private const val TAG = "FloatingChatFooter"

    private const val DEFAULT_CORNER_RADIUS = 24
    private const val DEFAULT_SIDE_MARGIN = 12
    private const val DEFAULT_BOTTOM_GAP = 4
    private const val DEFAULT_ELEVATION = 4

    private const val MIN_CORNER_RADIUS = 0
    private const val MAX_CORNER_RADIUS = 32
    private const val MIN_SIDE_MARGIN = 0
    private const val MAX_SIDE_MARGIN = 32
    private const val MIN_BOTTOM_GAP = 0
    private const val MAX_BOTTOM_GAP = 24
    private const val MIN_ELEVATION = 0
    private const val MAX_ELEVATION = 16

    private var cornerRadiusDp by prefOption("floating_chat_footer_corner_radius", DEFAULT_CORNER_RADIUS)
    private var sideMarginDp by prefOption("floating_chat_footer_side_margin", DEFAULT_SIDE_MARGIN)
    private var bottomGapDp by prefOption("floating_chat_footer_bottom_gap", DEFAULT_BOTTOM_GAP)
    private var elevationDp by prefOption("floating_chat_footer_elevation", DEFAULT_ELEVATION)

    /**
     * Locates ChatFooter.refreshBottomHeight() by the unique log string WeChat emits at the
     * start of the method. The intentional typo "keyborPx" is WeChat's own, copied faithfully.
     */
    private val methodRefreshBottomHeight by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.pluginsdk.ui.chat")
        matcher {
            usingEqStrings("MicroMsg.ChatFooter", "[refreshBottomHeight] keyborPx:%d")
            returnType = "void"
        }
    }

    override fun onEnable() {
        val reflekt = ChatFooter::class.reflekt()

        // Cover constructor overloads without requiring one specific signature.
        ChatFooter::class.java.declaredConstructors.forEach { constructor ->
            constructor.hookAfter {
                if (configuredEnabled) applyDrawingStyle(thisObject as ChatFooter)
            }
        }

        reflekt.firstMethodOrNull {
            name = "onAttachedToWindow"
            superclass()
        }?.hookAfter {
            val footer = thisObject as? ChatFooter ?: return@hookAfter
            if (!configuredEnabled) return@hookAfter
            applyDrawingStyle(footer)
            applySideMargins(footer)
        }

        // Existing/preloaded chat views may have been constructed and attached before
        // the switch was enabled. Reconcile on layout, including inherited overrides.
        reflekt.firstMethodOrNull {
            name = "onLayout"
            superclass()
        }?.hookAfter {
            val footer = thisObject as? ChatFooter ?: return@hookAfter
            if (!configuredEnabled) return@hookAfter
            applyDrawingStyle(footer)
            applySideMargins(footer)
        } ?: WeLogger.w(TAG, "ChatFooter.onLayout unavailable; using constructor/attach hooks")

        // A version-specific gap adjustment must not disable the base card styling.
        // Validate the actual method rather than the shared delegate's placeholder flag.
        val refresh = runCatching { methodRefreshBottomHeight.method }.getOrNull()
        if (refresh != null && refresh.returnType == Void.TYPE &&
            ChatFooter::class.java.isAssignableFrom(refresh.declaringClass)
        ) {
            refresh.hookAfter {
                val footer = thisObject as? ChatFooter ?: return@hookAfter
                if (configuredEnabled) applyBottomGap(footer)
            }
        } else {
            WeLogger.w(TAG, "refreshBottomHeight unavailable; bottom gap skipped, card styling retained")
        }

        ChattingUI::class.reflekt().firstMethodOrNull {
            name = "onResume"
            superclass()
        }?.hookAfter {
            val activity = thisObject as? ChattingUI ?: return@hookAfter
            if (!configuredEnabled) return@hookAfter
            val footer = findChatFooter(activity) ?: return@hookAfter
            applyDrawingStyle(footer)
            applySideMargins(footer)
        }
    }

    private val floatingOutlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            val r = view.resources.displayMetrics.density * cornerRadiusDp
            outline.setRoundRect(0, 0, view.width, view.height.coerceAtLeast(1), r)
        }
    }

    /** Sets the outline provider, corner clipping, and elevation — all drawing properties
     *  that don't require LayoutParams. Safe to call from the constructor hook. */
    private fun applyDrawingStyle(footer: ChatFooter) {
        val density = footer.resources.displayMetrics.density
        if (footer.outlineProvider !== floatingOutlineProvider) {
            footer.outlineProvider = floatingOutlineProvider
        }
        footer.clipToOutline = true
        val elevationPx = elevationDp * density
        if (footer.elevation != elevationPx) footer.elevation = elevationPx
        footer.invalidateOutline()
    }

    /** Applies left/right margins so the footer appears as a floating card inset from the
     *  screen edges. Called from onAttachedToWindow where LayoutParams is guaranteed non-null. */
    private fun applySideMargins(footer: ChatFooter) {
        val lp = footer.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val sideMarginPx = (sideMarginDp * footer.resources.displayMetrics.density).toInt()
        if (lp.leftMargin == sideMarginPx && lp.rightMargin == sideMarginPx) return
        lp.leftMargin = sideMarginPx
        lp.rightMargin = sideMarginPx
        footer.requestLayout()
        WeLogger.d(TAG, "applied side margins: side=${sideMarginDp}dp")
    }

    /**
     * WeChat's refreshBottomHeight sets bottomMargin = –keyboardHeight so the footer
     * rides flush against the IME top. We add [bottomGapDp] to lift it slightly so the
     * bottom rounded corners are visible above the keyboard surface.
     *
     * Guard: skip when bottomMargin > 0 — WeChat is not managing the position here.
     */
    private fun applyBottomGap(footer: ChatFooter) {
        val gapDp = bottomGapDp
        if (gapDp == 0) return
        val lp = footer.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.bottomMargin > 0) return
        val gapPx = (gapDp * footer.resources.displayMetrics.density).toInt()
        val adjusted = lp.bottomMargin + gapPx
        if (lp.bottomMargin != adjusted) {
            lp.bottomMargin = adjusted
            footer.requestLayout()
        }
    }

    /** Walks the view hierarchy of [activity] to find the ChatFooter instance. */
    private fun findChatFooter(activity: Activity): ChatFooter? {
        val root = activity.window?.decorView ?: return null
        return findChatFooterInView(root)
    }

    private fun findChatFooterInView(view: View): ChatFooter? {
        if (view is ChatFooter) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val result = findChatFooterInView(view.getChildAt(i))
                if (result != null) return result
            }
        }
        return null
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var cornerInput by remember { mutableFloatStateOf(cornerRadiusDp.toFloat()) }
            var sideInput by remember { mutableFloatStateOf(sideMarginDp.toFloat()) }
            var gapInput by remember { mutableFloatStateOf(bottomGapDp.toFloat()) }
            var elevInput by remember { mutableFloatStateOf(elevationDp.toFloat()) }

            AlertDialogContent(
                title = { Text("悬浮输入框") },
                text = {
                    DefaultColumn {
                        ListItem(
                            headlineContent = { Text("圆角半径: ${cornerInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = cornerInput,
                                    onValueChange = { cornerInput = it },
                                    valueRange = MIN_CORNER_RADIUS.toFloat()..MAX_CORNER_RADIUS.toFloat(),
                                    steps = MAX_CORNER_RADIUS - MIN_CORNER_RADIUS - 1
                                )
                            }
                        )
                        ListItem(
                            headlineContent = { Text("侧边距: ${sideInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = sideInput,
                                    onValueChange = { sideInput = it },
                                    valueRange = MIN_SIDE_MARGIN.toFloat()..MAX_SIDE_MARGIN.toFloat(),
                                    steps = MAX_SIDE_MARGIN - MIN_SIDE_MARGIN - 1
                                )
                            }
                        )
                        ListItem(
                            headlineContent = { Text("底部间距: ${gapInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = gapInput,
                                    onValueChange = { gapInput = it },
                                    valueRange = MIN_BOTTOM_GAP.toFloat()..MAX_BOTTOM_GAP.toFloat(),
                                    steps = MAX_BOTTOM_GAP - MIN_BOTTOM_GAP - 1
                                )
                            }
                        )
                        ListItem(
                            headlineContent = { Text("阴影强度: ${elevInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = elevInput,
                                    onValueChange = { elevInput = it },
                                    valueRange = MIN_ELEVATION.toFloat()..MAX_ELEVATION.toFloat(),
                                    steps = MAX_ELEVATION - MIN_ELEVATION - 1
                                )
                            }
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        cornerRadiusDp = cornerInput.roundToInt()
                        sideMarginDp = sideInput.roundToInt()
                        bottomGapDp = gapInput.roundToInt()
                        elevationDp = elevInput.roundToInt()
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }
}

