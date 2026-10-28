package com.Johnny.wcx.features.items.miniapps

import com.Johnny.wcx.dexkit.abc.IResolveDex
import com.Johnny.wcx.dexkit.dsl.dexConstructor
import com.Johnny.wcx.features.core.Feature
import com.Johnny.wcx.features.core.SwitchFeature
import org.json.JSONObject

@Feature(name = "移除嵌入广告", categories = ["小程序"], description = "移除小程序嵌入广告")
object RemoveEmbeddedAds : SwitchFeature(), IResolveDex {

    private val ctorNetSceneJSOperateWxData by dexConstructor {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.NetSceneJSOperateWxData", "doScene hash=%d, funcid=%d")
            }
        }
    }
    override fun onEnable() {
        ctorNetSceneJSOperateWxData.hookBefore {
            if (!configuredEnabled) return@hookBefore
            // 从全部实参中定位承载 JSON 的那个（构造器参数顺序可能随版本变化）。
            // 原实现把 api_name 当字符串字面量比较（恒为 false），导致本功能从未生效。
            val idx = args.indexOfFirst { arg ->
                arg is String && runCatching {
                    JSONObject(arg).optString("api_name") == "webapi_getadvert"
                }.getOrDefault(false)
            }
            if (idx < 0) return@hookBefore
            val json = runCatching { JSONObject(args[idx] as String) }.getOrNull() ?: return@hookBefore
            val data = json.optJSONObject("data") ?: return@hookBefore
            data.put("ad_unit_id", "")
            args[idx] = json.toString()
        }

        // BaseTransferRequest is shared with non-miniapp traffic. An ad_slot_data
        // key alone does not identify a miniapp response; leave that channel untouched.
    }
}

