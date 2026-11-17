package com.Johnny.wcx.activity.settings

import org.json.JSONObject

/** 将气泡恢复为原生，并保留其他主题字段。 */
internal fun resetBubbleRule(json: JSONObject, role: String) {
    val key = when (role) {
        "bubble_left" -> "leftBubble"
        "bubble_right" -> "rightBubble"
        else -> throw IllegalArgumentException("未知气泡角色：$role")
    }
    json.remove(role)
    val appearance = json.getJSONObject("appearance")
    val chat = appearance.optJSONObject("chat") ?: JSONObject().also { appearance.put("chat", it) }
    chat.put(key, JSONObject().put("kind", "native"))
}
