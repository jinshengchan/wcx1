package com.Johnny.wcx.features.items.system

import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
 *
 * v2：Jim 实测广告已消失，但偶尔能看到广告先出现再消失的过程——2 秒轮询
 * 有窗口期，广告先渲染出来才被藏掉。改成先往页面注入一条 CSS 规则，
 * 让含该路径的 iframe 在渲染时就直接不显示；2 秒循环保留作兜底
 * （盖页面加载前就已存在的 iframe）。
 *
 * v3：Jim 实测 3-4 篇文章后失效，重启微信又好——根因是捕获集合越积越多：
 * 日志里主进程捕获实例 45 秒内从 5 涨到 10（v2 日志里到过 21 个），每次扫
 * 都要挨个对已销毁的旧页面跑 JS，扫的线程越堆越多，活页面的藏跟不上。
 * 改两处：连续 2 次跑 JS 失败的实例直接踢出捕获集合（销毁的页面永远失败，
 * 活页面加载中最多失败 1 次，不会误踢）；同一时间只跑一个扫线程，跑不完
 * 的跳过，不再堆线程。
 *
 * v4：定性对照版。Jim 指明隐藏第一版没有 3-4 篇后失效的问题，而第一版
 * 与第二版之间只差 CSS 注入这一处——但 CSS 怎么导致多篇后失效，机制上
 * 尚未想通（按说 CSS 只会让藏得更彻底），也不能排除第一版那次测得少、
 * 没刷到阈值。因此 v4 只做一处改动：拿掉 CSS 注入，回到第一版的纯内联
 * 藏法，清旧实例与单飞扫线程保留。刷 5 篇以上：不失效则 CSS 有罪，
 * 还失效则 CSS 无罪，再查广告格式或进程那条线。闪一下会回来（第一版
 * 的老样子），这是定性用的版本，不是最终方案。
 *
 * v5：Jim 确认第四版不再出现 3-4 篇后失效，只剩有的藏得太慢——定性上
 * CSS 这条线可以先定罪（机制以后再深究），最终方案不用 CSS。慢的原因
 * 是 2 秒扫一轮，新广告 iframe 出来要等下一轮，忙时还要排队（实测有
 * 一处等了 6 秒）。改动只有一处：扫间隔 2 秒改 0.5 秒；清旧实例与单飞
 * 保护都在，线程不会堆起来。
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

    // v4 定性对照：拿掉 CSS 注入，回到第一版的纯内联藏法（只改这一处，
    // 其余 v3 逻辑不变）。藏 iframe 的 JS：只动来源含微信模板广告路径
    // 的 iframe，返回本轮新藏的数量。
    private const val HIDE_JS = """(function(){var n=0;var ifs=document.getElementsByTagName('iframe');for(var i=0;i<ifs.length;i++){var f=ifs[i];var s=f.src||'';if(s.indexOf('wxa.wxs.qq.com/tmpl/qe/')>=0&&f.style.display!=='none'){f.style.display='none';n++;}}return n;})()"""

    private val mainHandler = Handler(Looper.getMainLooper())

    // 构造方法捕获的真 WebView 实例（弱引用，实例回收后自动移除）。
    private val captured = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private val capturedLock = Any()

    // v3：连续跑 JS 失败的次数（弱引用键，随实例回收消失）。
    private val failCounts = Collections.synchronizedMap(WeakHashMap<Any, Int>())

    // v3：同一时间只跑一个扫线程，跑不完的跳过，不堆线程。
    private val sweepRunning = AtomicBoolean(false)

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

    // v5：每 0.5 秒扫一次捕获集合；空集合静默，不打日志。
    private fun scheduleHiderSweep() {
        mainHandler.postDelayed({
            runCatching { sweepHide() }
            scheduleHiderSweep()
        }, 500L)
    }

    private fun sweepHide() {
        val list = synchronized(capturedLock) { captured.filterNotNull() }
        if (list.isEmpty()) return
        if (!sweepRunning.compareAndSet(false, true)) return
        Thread {
            try {
                runCatching {
                    for (wv in list) {
                        val hid = runCatching {
                            WeWebViewApi.evaluateJavascript(wv, HIDE_JS, 8000L)
                        }.getOrNull()
                        if (hid == null) {
                            val c = (failCounts[wv] ?: 0) + 1
                            if (c >= 2) {
                                synchronized(capturedLock) { captured.remove(wv) }
                                failCounts.remove(wv)
                                WeLogger.i(
                                    TAG,
                                    "hider pruned dead webview: process=${TargetProcesses.currentName} " +
                                        "class=${wv.javaClass.name}"
                                )
                            } else {
                                failCounts[wv] = c
                            }
                            continue
                        }
                        failCounts.remove(wv)
                        val n = hid.trim().toIntOrNull() ?: 0
                        if (n > 0) {
                            WeLogger.i(
                                TAG,
                                "hider hid $n qe iframes: process=${TargetProcesses.currentName} " +
                                    "class=${wv.javaClass.name}"
                            )
                        }
                    }
                }.onFailure { WeLogger.w(TAG, "sweepHide failed", it) }
            } finally {
                sweepRunning.set(false)
            }
        }.start()
    }
}
