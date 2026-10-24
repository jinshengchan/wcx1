package com.Johnny.wcx.features.items.system

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import com.Johnny.wcx.features.api.ui.WeWebViewApi
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.TargetProcesses
import com.Johnny.wcx.utils.WeLogger

/**
 * 文章广告探针（诊断版，只读）。
 *
 * 背景：包拦截已证实清不掉文章底部与留言流里的广告（广告由文章页 WebView 内的 JS
 * 直连广告服务器填充，不经过微信 CGI 管线）。本探针回答动手前必须知道的三件事：
 * 1. 文章页 WebView 到底在哪个进程（主进程还是 :tools）；
 * 2. 文章页广告在 DOM 里长什么样（底部固定位与留言流懒加载位各用什么标记）；
 * 3. 底部广告卡在 DOM 里是否真与正文、留言区独立嵌套。
 *
 * 做法：挂 Activity.onResume，遍历窗口找 WebView，只对 mp.weixin.qq.com 的页面
 * 用 WeWebViewApi.evaluateJavascript 跑一段只读 JS，把含“广告”标记的最内层元素
 * （标签、class、id、文本片段）与数量打到 info 日志。什么都不隐藏、不改页面。
 * 多次采样（1s / 3s / 6s / 12s / 25s / 45s / 75s / 120s）覆盖滚动懒加载；
 * 同一页面结果不变时不重复打日志。诊断结束即废弃，不要合入 master。
 *
 * v2：每次采样都打一行 probe sample（不管有没有文章页），含 Activity 名、
 * WebView 数量与全部 WebView 的 URL——阴性结果也能定位：是没打开文章、
 * 还是文章页在探针够不到的地方。
 *
 * v3：v2 实测文章页在主进程 TmplWebViewMMUI，WebView 每次都找到 1 个，
 * 但 getUrl 一直返回空，导致 mp 域名过滤把采样全扔掉。因此 v3 不再依赖 URL：
 * 只要 Activity 是 TmplWebViewMMUI，就对每个 WebView 节点直接跑 JS；
 * 采样行同时打节点类名（区分真 WebView 还是名字含 WebView 的容器）。
 * 遍历也不再在 WebView 节点处停下，容器里的真 WebView 也能被收到。
 *
 * v4：v3 实测文章页树里只有 4 个界面壳，没有真 WebView——爬树这条路到头了。
 * 改钩三类 WebView 的全部构造方法，真 WebView 一创建就抓住实例直接跑 JS，
 * 不管它挂在哪个窗口、哪个进程；另加文章页完整视图树类名清单，看正文到底
 * 由什么渲染。Jim 实测是快速连刷多篇文章不停留，因此捕获与树清单都放在
 * 打开后 1s/3s 的早期采样，不依赖 2 分钟停留。
 */
@Feature(
    name = "文章广告探针",
    categories = ["系统与隐私"],
    description = "诊断用：记录文章页广告 DOM 标记，不隐藏任何东西"
)
object ArticleAdProbe : SwitchFeature() {

    private const val TAG = "ArticleAdProbe"

    // 探针默认开启，装上即工作，省去再去设置里找开关。
    override val defaultEnabled: Boolean = true

    // 探针要自己发现文章页在哪个进程，因此全部进程都加载。
    override val shouldLoadInCurrentProcess: Boolean = true

    // JS 探针：返回含“广告”标记的最内层元素（标签、class、id、文本片段）与总数。
    // 只读，不改动页面。文本片段截 24 字，避免日志过长。
    private const val PROBE_JS = """(function(){var nodes=[];var total=0;var all=document.getElementsByTagName('*');for(var i=0;i<all.length;i++){var el=all[i];var t=el.textContent||'';if(t.indexOf('广告')<0)continue;total++;var childHas=false;for(var j=0;j<el.children.length;j++){if((el.children[j].textContent||'').indexOf('广告')>=0){childHas=true;break;}}if(!childHas){var cls=(typeof el.className==='string')?el.className:'';var txt=t.replace(/\s+/g,' ').substring(0,24);nodes.push(el.tagName+'|'+cls+'|'+(el.id||'')+'|'+txt);if(nodes.length>=30)break;}}return JSON.stringify({href:location.href,title:document.title,adRelated:total,adNodes:nodes});})()"""

    // 采样延迟（毫秒）：覆盖打开后滚动懒加载的时间窗。
    private val SAMPLE_DELAYS = longArrayOf(1000L, 3000L, 6000L, 12000L, 25000L, 45000L, 75000L, 120000L)

    private val mainHandler = Handler(Looper.getMainLooper())

    // key：WebView 身份 + 页面 URL；value：上次打过的 adNodes 结果。结果不变不重复打日志。
    private val lastResults = mutableMapOf<String, String>()
    private val lastResultsLock = Any()

    // v4：构造方法捕获的真 WebView 实例（弱引用，实例回收后自动移除）。
    private val captured = Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private val capturedLock = Any()

    override fun onEnable() {
        registerUnhook(
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        runCatching { scheduleProbe(activity) }
                            .onFailure { WeLogger.w(TAG, "scheduleProbe failed", it) }
                    }
                }
            )
        )
        // v4：钩三类 WebView 的全部构造方法，真 WebView 一创建就抓住实例，
        // 不管它挂在哪个窗口、哪个进程。类不存在（如 smtt）就记一行跳过。
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
                        WeLogger.i(
                            TAG,
                            "probe webview created: process=${TargetProcesses.currentName} class=$className"
                        )
                    }
                }
                XposedBridge.hookAllConstructors(clazz, hook).forEach { registerUnhook(it) }
            }.onFailure {
                WeLogger.i(TAG, "probe ctor hook unavailable: $className")
            }
        }
        // v4：无 Activity 进程（如 xweb 特权进程）里的捕获实例也定期扫一次。
        scheduleCapturedSweep()
        WeLogger.i(TAG, "probe enabled in process ${TargetProcesses.currentName}")
    }

    // v4：每 15 秒扫一次捕获集合；空集合静默，不打日志。
    private fun scheduleCapturedSweep() {
        mainHandler.postDelayed({
            runCatching { sweepCaptured("sweep") }
            scheduleCapturedSweep()
        }, 15000L)
    }

    // v4：对捕获的真 WebView 跑 JS，广告 DOM 结果去重后打日志。
    private fun sweepCaptured(tag: String) {
        val list = synchronized(capturedLock) { captured.filterNotNull() }
        if (list.isEmpty()) return
        Thread {
            runCatching {
                val classes = list.map { it.javaClass.name.substringAfterLast('.') }
                WeLogger.i(
                    TAG,
                    "probe captured $tag: process=${TargetProcesses.currentName} count=${list.size} " +
                        "classes=[" + classes.joinToString("; ") + "]"
                )
                for (wv in list) {
                    val url = runCatching { WeWebViewApi.getUrl(wv, 5000L) }.getOrNull()
                    val result = runCatching {
                        WeWebViewApi.evaluateJavascript(wv, PROBE_JS, 8000L)
                    }.getOrNull()
                    if (result == null) {
                        WeLogger.i(
                            TAG,
                            "probe captured eval failed: $tag url=${url ?: "?"} class=${wv.javaClass.name}"
                        )
                        continue
                    }
                    logAdDomIfChanged(
                        System.identityHashCode(wv).toString() + "|" + (url ?: "captured"),
                        "probe captured adDom: $tag url=${url ?: "?"} result=$result"
                    )
                }
            }.onFailure { WeLogger.w(TAG, "sweepCaptured failed", it) }
        }.start()
    }

    // 广告 DOM 结果去重：同一实例同一结果只打一次。
    private fun logAdDomIfChanged(key: String, line: String) {
        val changed = synchronized(lastResultsLock) {
            val prev = lastResults[key]
            if (prev != line) {
                lastResults[key] = line
                true
            } else {
                false
            }
        }
        if (changed) WeLogger.i(TAG, line)
    }

    private fun scheduleProbe(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        for (delay in SAMPLE_DELAYS) {
            mainHandler.postDelayed({
                runCatching { probeOnce(activity, decor, delay) }
                    .onFailure { WeLogger.w(TAG, "probeOnce failed", it) }
            }, delay)
        }
    }

    private fun probeOnce(activity: Activity, decor: View, delay: Long) {
        // 后台线程跑：WeWebViewApi.evaluateJavascript 会阻塞等 JS 回调，不能占主线程。
        Thread {
            runCatching {
                val webViews = mutableListOf<Any>()
                collectWebViews(decor, webViews)
                val urls = webViews.map { wv ->
                    runCatching { WeWebViewApi.getUrl(wv, 5000L) }.getOrNull()
                }
                // v2 无条件采样日志：每次采样都打，不管有没有文章页。
                // v3 加节点类名：区分真 WebView 还是名字含 WebView 的容器。
                val classes = webViews.map { it.javaClass.name.substringAfterLast('.') }
                WeLogger.i(
                    TAG,
                    "probe sample: delay=${delay}ms process=${TargetProcesses.currentName} " +
                        "activity=${activity.javaClass.simpleName} webviews=${webViews.size} " +
                        "classes=[" + classes.joinToString("; ") + "] " +
                        "urls=[" + urls.joinToString("; ") { (it ?: "?").take(100) } + "]"
                )
                // v3：文章页不依赖 URL。TmplWebViewMMUI 就是文章页 Activity，
                // 对它的每个 WebView 节点都直接跑 JS；其它页面仍用 mp 域名过滤。
                val isArticlePage = activity.javaClass.name.contains("TmplWebViewMMUI")
                // v4：文章页打开后 1 秒就扫一次捕获集合（Jim 刷文章不停留，等不到长采样）；
                // 3 秒时再打一张完整视图树类名清单，看正文到底由什么渲染。
                if (isArticlePage && delay == 1000L) {
                    sweepCaptured("resume-1s")
                }
                if (isArticlePage && delay == 3000L) {
                    val counts = LinkedHashMap<String, Int>()
                    var total = 0
                    val stack = ArrayDeque<View>()
                    stack.add(decor)
                    while (stack.isNotEmpty()) {
                        val v = stack.removeFirst()
                        total++
                        val name = v.javaClass.name.substringAfterLast('.')
                        counts[name] = (counts[name] ?: 0) + 1
                        if (v is ViewGroup) {
                            for (i in 0 until v.childCount) {
                                stack.add(v.getChildAt(i))
                            }
                        }
                    }
                    val top = counts.entries.sortedByDescending { it.value }.take(80)
                        .joinToString("; ") { "${it.key}x${it.value}" }
                    WeLogger.i(
                        TAG,
                        "probe tree: activity=TmplWebViewMMUI total=$total classes=[$top]"
                    )
                }
                for ((wv, url) in webViews.zip(urls)) {
                    if (!isArticlePage && (url == null || !url.contains("mp.weixin.qq.com"))) continue
                    val key = System.identityHashCode(wv).toString() + "|" + (url ?: "tmpl")
                    synchronized(lastResultsLock) {
                        if (!lastResults.containsKey(key)) {
                            WeLogger.i(
                                TAG,
                                "probe found article webview: process=${TargetProcesses.currentName} " +
                                    "activity=${activity.javaClass.name} url=${url ?: "?"} " +
                                    "class=${wv.javaClass.name}"
                            )
                            lastResults[key] = ""
                        }
                    }
                    val result = runCatching {
                        WeWebViewApi.evaluateJavascript(wv, PROBE_JS, 8000L)
                    }.getOrNull()
                    if (result == null) {
                        WeLogger.i(
                            TAG,
                            "probe eval failed: delay=${delay}ms url=${url ?: "?"} " +
                                "class=${wv.javaClass.name}"
                        )
                        continue
                    }
                    val changed = synchronized(lastResultsLock) {
                        val prev = lastResults[key]
                        if (prev != result) {
                            lastResults[key] = result
                            true
                        } else {
                            false
                        }
                    }
                    if (changed) {
                        WeLogger.i(TAG, "probe adDom: delay=${delay}ms url=$url result=$result")
                    }
                }
            }.onFailure { WeLogger.w(TAG, "probe thread failed", it) }
        }.start()
    }

    // v3：名字含 WebView 的节点都收下，同时继续往里走——容器节点里可能还包着真 WebView，
    // v2 在第一个 WebView 节点处停下，可能只收到容器而错过里面的真 WebView。
    private fun collectWebViews(root: View, out: MutableList<Any>) {
        val stack = ArrayDeque<View>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val v = stack.removeFirst()
            if (v.javaClass.name.contains("WebView")) {
                out.add(v)
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    stack.add(v.getChildAt(i))
                }
            }
        }
    }
}
