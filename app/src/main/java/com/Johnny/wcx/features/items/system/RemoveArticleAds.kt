package com.Johnny.wcx.features.items.system

import com.Johnny.wcx.features.api.net.WePacketManager
import com.Johnny.wcx.features.api.net.WeProtoData
import com.Johnny.wcx.features.api.net.abc.IWePacketInterceptor
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * 诊断版：只加 info 日志，不改过滤逻辑。
 *
 * 背景：开关开着、文章底部广告仍每次现取现填（元宝、传奇游戏轮换），说明底部广告位
 * 没被本功能的清理命中。诊断要回答三个问题：
 * 1. 打开文章时 cgiId 21909 是否触发；
 * 2. 触发时字段 2 / 字段 2.3 的广告 JSON 是否存在、含哪些广告键、是否被改；
 * 3. 打开文章期间还有哪些 mmbiz / appmsg 相关接口经过管线（找替代广告链路）。
 * 用法：装上后打开一篇带底部广告的文章，把日志发回；日志里搜 "diag articleAd"。
 * 诊断结束即废弃，不要合入 master。
 */
@Feature(name = "去除文章广告", categories = ["系统与隐私"], description = "清除文章中的广告数据")
object RemoveArticleAds : SwitchFeature(), IWePacketInterceptor {

    private const val TAG = "RemoveArticleAds"

    override fun onEnable() {
        WeLogger.i(TAG, "diag articleAd interceptor enabled")
        WePacketManager.addInterceptor(this)
    }

    override fun onDisable() {
        WePacketManager.removeInterceptor(this)
    }

    override fun onResponse(uri: String, cgiId: Int, respBytes: ByteArray): ByteArray? {
        // 全量诊断（第二版）：记下每一条经过包拦截管线的响应接口（不限 mmbiz / appmsg），
        // 只记 uri + cgiId，不记响应内容。用于判断底部广告请求是否经过本管线：
        // 若日志里找不到广告填充接口而广告仍在显示，则属 WebView 侧直连，不经过本管线。
        // 注意：全量日志量大，诊断时只打开一篇文章、尽快发日志。
        WeLogger.i(TAG, "diag articleAd all: uri=$uri cgiId=$cgiId")
        // 诊断：21909 命中记一条；其余 mmbiz / appmsg 相关接口也记一条（找替代链路）。
        // 注意：只记 uri + cgiId，不记响应内容。
        if (cgiId == 21909) {
            WeLogger.i(TAG, "diag articleAd hit: uri=$uri cgiId=$cgiId")
        } else if (uri.contains("mmbiz") || uri.contains("appmsg")) {
            WeLogger.i(TAG, "diag articleAd uri: uri=$uri cgiId=$cgiId")
        }
        if (cgiId != 21909) return null

        try {
            val data = WeProtoData.fromBytes(respBytes)
            val json = data.toJsonObject()

            // 获取字段2
            val field2 = json.optJSONObject("2")
            if (field2 == null) {
                WeLogger.i(TAG, "diag articleAd no field2: uri=$uri")
                return null
            }
            // 获取字段3中的广告JSON字符串
            val adJsonStr = field2.optString("3")
            if (adJsonStr.isEmpty()) {
                WeLogger.i(TAG, "diag articleAd no adJson: uri=$uri")
                return null
            }

            // 解析广告JSON
            val adJson = runCatching { JSONObject(adJsonStr) }.getOrElse {
                WeLogger.i(TAG, "diag articleAd parse failed: uri=$uri")
                return null
            }
            // 诊断：先记清理前含哪些广告键，再清理。
            val presentKeys = listOf(
                "ad_slot_data",
                "advertisement_info",
                "advertisement_num",
                "no_ad_indicator_info",
                "check_ad_resp"
            ).filter { adJson.has(it) }
            WeLogger.i(TAG, "diag articleAd keys: uri=$uri present=$presentKeys")
            // 清空广告字段
            var modified = false

            // 清空广告数组
            if (adJson.has("ad_slot_data")) {
                adJson.put("ad_slot_data", JSONArray())
                modified = true
            }

            if (adJson.has("advertisement_info")) {
                adJson.put("advertisement_info", JSONArray())
                modified = true
            }

            // 广告数量设置为0
            if (adJson.has("advertisement_num")) {
                adJson.put("advertisement_num", 0)
                modified = true
            }

            // 清空广告曝光相关
            if (adJson.has("no_ad_indicator_info")) {
                adJson.put("no_ad_indicator_info", JSONArray())
                modified = true
            }

            // 清空广告响应
            if (adJson.has("check_ad_resp")) {
                adJson.put("check_ad_resp", JSONObject().apply {
                    put("aid", "0")
                    put("del_aid", JSONArray())
                    put("offline_aid", JSONArray())
                    put("online_aid", JSONArray())
                })
                modified = true
            }

            if (modified) {
                // 放回修改后的广告JSON
                field2.put("3", adJson.toString())
                data.applyViewJson(json, true)
                WeLogger.i(TAG, "diag articleAd cleared: uri=$uri keys=$presentKeys")
                return data.toPacketBytes()
            }
            WeLogger.i(TAG, "diag articleAd no ad keys, untouched: uri=$uri")

        } catch (e: Exception) {
            WeLogger.e(TAG, "onResponse failed", e)
        }

        return null
    }
}
