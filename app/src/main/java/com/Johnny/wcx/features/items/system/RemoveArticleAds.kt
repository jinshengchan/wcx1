package com.Johnny.wcx.features.items.system

import com.Johnny.wcx.features.api.net.WePacketManager
import com.Johnny.wcx.features.api.net.WeProtoData
import com.Johnny.wcx.features.api.net.abc.IWePacketInterceptor
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.utils.WeLogger
import org.json.JSONArray
import org.json.JSONObject

@Feature(name = "去除文章广告", categories = ["系统与隐私"], description = "数据层：过滤文章接口中的广告数据；关闭后新请求不再过滤，已有页面需重新打开")
object RemoveArticleAds : SwitchFeature(), IWePacketInterceptor {

    // Keep a lightweight listener registered in the host; every response reads the current
    // saved choice, so toggling from a different process cannot leave filtering enabled.
    override val shouldEnableOnStartup: Boolean = true

    override fun onBeforeToggle(newState: Boolean, context: android.content.Context): Boolean {
        com.Johnny.wcx.preferences.WePrefs.putBool(name, newState)
        _isEnabled = newState // UI must not tear down the cross-process control listener.
        return true
    }

    private const val TAG = "RemoveArticleAds"

    override fun onEnable() {
        WePacketManager.addInterceptor(this)
    }

    override fun onDisable() {
        WePacketManager.removeInterceptor(this)
    }

    override fun onResponse(uri: String, cgiId: Int, respBytes: ByteArray): ByteArray? {
        if (!configuredEnabled) return null
        if (cgiId != 21909) return null

        try {
            val data = WeProtoData.fromBytes(respBytes)
            val json = data.toJsonObject()

            // 获取字段2
            val field2 = json.optJSONObject("2") ?: return null
            // 获取字段3中的广告JSON字符串
            val adJsonStr = field2.optString("3") ?: return null

            // 解析广告JSON
            val adJson = runCatching { JSONObject(adJsonStr) }.getOrElse { return null }
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
                WeLogger.d(TAG, "cleared article ads")
                return data.toPacketBytes()
            }

        } catch (e: Exception) {
            WeLogger.e(TAG, "onResponse failed", e)
        }

        return null
    }
}
