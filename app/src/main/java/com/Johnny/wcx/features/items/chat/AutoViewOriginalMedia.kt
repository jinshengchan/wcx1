package com.Johnny.wcx.features.items.chat

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexMethod
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge

@Feature(name = "自动查看原图", categories = ["聊天"], description = "在打开图片和视频时自动点击查看原图")
object AutoViewOriginalMedia : SwitchFeature(), IResolveDex {

    private const val TAG = "AutoViewOriginalMedia"
    private val BUTTON_KEYWORDS = listOf("查看原图", "Full Image", "查看原视频", "Original quality")
    private var lastScanAt = 0L
    private var lastClickAt = 0L

    private val methodSetImageHdImgBtnVisibility by dexMethod()
    private val methodCheckNeedShowOriginVideoBtn by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        val results = dexKit.findMethod {
            matcher {
                declaredClass = "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"
                usingEqStrings("setHdImageActionDownloadable")
            }
        }.ifEmpty {
            dexKit.findMethod {
                matcher {
                    declaredClass = "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"
                    usingEqStrings("setImageHdImgBtnVisibility")
                }
            }
        }
        // 原实现用 single()：results 为空抛 NoSuchElementException、多结果抛 IllegalArgumentException，
        // 均发生在 resolveDex 阶段且无兜底。改为空安全、多候选取首个。
        results.firstOrNull()?.let { methodSetImageHdImgBtnVisibility.setDescriptor(it) }

        methodCheckNeedShowOriginVideoBtn.find(dexKit) {
            matcher {
                declaredClass = "com.tencent.mm.ui.chatting.gallery.ImageGalleryUI"
                usingEqStrings("checkNeedShowOriginVideoBtn")
            }
        }
    }

    override fun onEnable() {
        listOf(
            methodSetImageHdImgBtnVisibility,
            methodCheckNeedShowOriginVideoBtn
        ).forEach { method ->
            if (method.isPlaceholder) {
                WeLogger.w(TAG, "原图按钮锚点未解析，跳过此挂点")
                return@forEach
            }

            method.hookAfter {
                val activity = thisObject as? Activity ?: return@hookAfter
                val now = SystemClock.elapsedRealtime()
                // 页面布局可能稍晚才出现按钮；空扫描只短暂节流，命中后才长节流。
                if (now - lastScanAt < 250L || now - lastClickAt < 1200L) return@hookAfter
                lastScanAt = now
                runCatching {
                    if (clickOriginalButton(activity.window.decorView)) {
                        lastClickAt = now
                        WeLogger.i(TAG, "已点击查看原图或原视频")
                    }
                }.onFailure { WeLogger.w(TAG, "查找原图按钮失败：${it.javaClass.simpleName}") }
            }
        }
    }

    /** 只扫描当前图片预览页的可见视图，最多 400 节点；不触碰其他页面。 */
    private fun clickOriginalButton(root: View): Boolean {
        var visited = 0
        fun walk(view: View): Boolean {
            if (++visited > 400 || !view.isShown) return false
            if (view is TextView) {
                val label = view.text?.toString()?.trim().orEmpty()
                if (BUTTON_KEYWORDS.any { label.startsWith(it, ignoreCase = true) }) {
                    val target = if (view.isClickable) view else
                        (view.parent as? View)?.takeIf { it.isClickable && it.isShown }
                    if (target != null && target.performClick()) return true
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    if (walk(view.getChildAt(index))) return true
                }
            }
            return false
        }
        return walk(root)
    }
}
