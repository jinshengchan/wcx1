package com.Johnny.wcx.features.items.system

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
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
        WeLogger.i(TAG, "probe enabled in process ${TargetProcesses.currentName}")
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
                for (wv in webViews) {
                    val url = runCatching { WeWebViewApi.getUrl(wv, 5000L) }.getOrNull()
                        ?: continue
                    if (!url.contains("mp.weixin.qq.com")) continue
                    val key = System.identityHashCode(wv).toString() + "|" + url
                    synchronized(lastResultsLock) {
                        if (!lastResults.containsKey(key)) {
                            WeLogger.i(
                                TAG,
                                "probe found article webview: process=${TargetProcesses.currentName} " +
                                    "activity=${activity.javaClass.name} url=$url"
                            )
                            lastResults[key] = ""
                        }
                    }
                    val result = runCatching {
                        WeWebViewApi.evaluateJavascript(wv, PROBE_JS, 8000L)
                    }.getOrNull()
                    if (result == null) {
                        WeLogger.i(TAG, "probe eval failed: delay=${delay}ms url=$url")
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

    private fun collectWebViews(root: View, out: MutableList<Any>) {
        val stack = ArrayDeque<View>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val v = stack.removeFirst()
            if (v.javaClass.name.contains("WebView")) {
                out.add(v)
                continue
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    stack.add(v.getChildAt(i))
                }
            }
        }
    }
}
