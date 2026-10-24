package com.Johnny.wcx.features.items.system

import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.WeakHashMap
import com.Johnny.wcx.features.api.ui.WeWebViewApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.WeLogger

/**
 * 隐藏文章内广告卡（实验版，第一版）。
 *
 * 背景：探针 v6 定性——可见的三处广告卡（底部、留言流、正文中部）都不在顶层
 * 文档文字里；一篇文章里出现过 3 个同源 iframe（wxa.wxs.qq.com/tmpl/qe/
 * base_tmpl.html，全宽约 300 高），数量与三处广告位对得上，最大可能就是
 * 广告槽本身。跨域 iframe 里面读不到，但顶层文档能把 iframe 元素本身藏掉。
 *
 * 做法：复用探针 v4 的构造捕获（钩三类 WebView 构造方法），每 2 秒对捕获实例
 * 跑一段 JS，把来源含 wxa.wxs.qq.com/tmpl/qe/ 的 iframe 置 display none。
 * 2 秒循环就是为了盖住留言流那种滚动懒加载的广告。只藏这一个来源模式，
 * 不动其它 iframe、不动正文。藏了几个打一条 info 日志，方便下一份日志验证。
 *
 * 已知局限：只藏 iframe 本身；如果广告卡外层容器还带留白，藏完会留一个
 * 空框，下一版再收容器。测试分支专用，不要合入 master。
 */
@Feature(
    name = "隐藏文章内广告卡",
    categories = ["系统与隐私"],
    description = "实验版：隐藏文章页内嵌广告 iframe（微信模板域名）"
)
object ArticleAdHider : SwitchFeature() {

    private const val TAG = "ArticleAdHider"

    // 测试分支默认开启，装上即工作。
    override val defaultEnabled: Boolean = true

    // 要自己发现文章页在哪个进程，因此全部进程都加载。
    override val shouldLoadInCurrentProcess: Boolean = true

    // 藏 iframe 的 JS：只动来源含微信模板广告路径的 iframe，返回本轮新藏的数量。
    private const val HIDE_JS = """(function(){var n=0;var ifs=document.getElementsByTagName('iframe');for(var i=0;i<ifs.length;i++){var f=ifs[i];var s=f.src||'';if(s.indexOf('wxa.wxs.qq.com/tmpl/qe/')>=0&&f.style.display!=='none'){f.style.display='none';n++;}}return n;})()"""

    private val mainHandler = Handler(Looper.getMainLooper())

    // 构造方法捕获的真 WebView 实例（弱引用，实例回收后自动移除）。
    private val captured = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private val capturedLock = Any()

    override fun onEnable() {
        for (className in listOf(
            "android.webkit.WebView",
            "com.tencent.xweb.WebView",
            "com.tencent.smtt.sdk.WebView"
        )) {
            runCatching {
                val clazz = Class.forName(className)
                val hook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val wv = param.thisObject ?: return
                        synchronized(capturedLock) { captured.add(wv) }
                    }
                }
                XposedBridge.hookAllConstructors(clazz, hook).forEach { registerUnhook(it) }
            }.onFailure {
                WeLogger.i(TAG, "hider ctor hook unavailable: $className")
            }
        }
        scheduleHiderSweep()
        WeLogger.i(TAG, "hider enabled in process ${TargetProcesses.currentName}")
    }

    // 每 2 秒扫一次捕获集合；空集合静默，不打日志。
    private fun scheduleHiderSweep() {
        mainHandler.postDelayed({
            runCatching { sweepHide() }
            scheduleHiderSweep()
        }, 2000L)
    }

    private fun sweepHide() {
        val list = synchronized(capturedLock) { captured.filterNotNull() }
        if (list.isEmpty()) return
        Thread {
            runCatching {
                for (wv in list) {
                    val hid = runCatching {
                        WeWebViewApi.evaluateJavascript(wv, HIDE_JS, 8000L)
                    }.getOrNull()
                    val n = hid?.trim()?.toIntOrNull() ?: 0
                    if (n > 0) {
                        WeLogger.i(
                            TAG,
                            "hider hid $n qe iframes: process=${TargetProcesses.currentName} " +
                                "class=${wv.javaClass.name}"
                        )
                    }
                }
            }.onFailure { WeLogger.w(TAG, "sweepHide failed", it) }
        }.start()
    }
}
