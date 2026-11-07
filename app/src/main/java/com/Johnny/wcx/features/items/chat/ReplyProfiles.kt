package com.Johnny.wcx.features.items.chat

import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import kotlinx.serialization.json.*
import java.util.UUID

/** One versioned preference write commits profiles and bindings together. Legacy keys are untouched. */
internal object ReplyProfiles {
    enum class Mode { MANUAL, AUTO }
    data class Provider(val id: String, val name: String, val url: String, val key: String, val model: String)
    data class State(val providers: List<Provider>, val manual: String, val auto: String, val damaged: Boolean = false)
    private var document by prefOption("ai_reply_profiles_v1", "")

    private fun legacy(prefix: String, id: String, name: String) = Provider(id, name,
        WePrefs.getStringOrDef("${prefix}_api_url", "https://api.deepseek.com/chat/completions").trim(),
        WePrefs.getStringOrDef("${prefix}_api_key", "").trim(),
        WePrefs.getStringOrDef("${prefix}_model", "deepseek-flash").trim())

    @Synchronized fun read(): State {
        val saved = document
        if (saved.isEmpty()) {
            val manual = legacy("smart_reply", "manual", "原长按配置")
            val auto = legacy("ai_reply", "auto", "原自动回复配置")
            val same = manual.url == auto.url && manual.key == auto.key && manual.model == auto.model
            val state = State(if (same) listOf(manual.copy(name = "通用配置")) else listOf(manual, auto),
                manual.id, if (same) manual.id else auto.id)
            write(state)
            return state
        }
        return try {
            val obj = Json.parseToJsonElement(saved).jsonObject
            check(obj["version"]?.jsonPrimitive?.int == 1)
            val providers = obj["providers"]!!.jsonArray.map {
                val p = it.jsonObject
                fun field(k: String) = p[k]!!.jsonPrimitive.content
                Provider(field("id"), field("name"), field("url"), field("key"), field("model"))
            }
            val manual = obj["manual"]!!.jsonPrimitive.content
            val auto = obj["auto"]!!.jsonPrimitive.content
            check(providers.all { it.id.isNotBlank() } && providers.map { it.id }.distinct().size == providers.size)
            check((manual.isEmpty() || providers.any { it.id == manual }) &&
                (auto.isEmpty() || providers.any { it.id == auto }))
            State(providers, manual, auto)
        } catch (_: Exception) {
            // Fail closed; do not replace damaged/future-version data or silently fall back to an old key.
            State(emptyList(), "", "", damaged = true)
        }
    }

    fun provider(mode: Mode): Provider {
        val state = read()
        val id = if (mode == Mode.MANUAL) state.manual else state.auto
        return state.providers.firstOrNull { it.id == id } ?: Provider("", if (state.damaged) "配置不可用" else "未选择模型配置", "", "", "")
    }

    @Synchronized fun bind(mode: Mode, id: String) {
        val state = read()
        require(!state.damaged && state.providers.any { it.id == id })
        write(if (mode == Mode.MANUAL) state.copy(manual = id) else state.copy(auto = id))
    }

    @Synchronized fun save(id: String?, name: String, url: String, key: String, model: String) {
        val state = read()
        require(!state.damaged) { "模型配置损坏，已停止修改" }
        require(name.isNotBlank() && name.length <= 40) { "配置名称需为 1–40 个字符" }
        SmartReplyClient.endpoint(url)
        require(model.isNotBlank()) { "请填写模型名称" }
        require(key.none { it == '\r' || it == '\n' }) { "API Key 不能包含换行" }
        require(id == null || state.providers.any { it.id == id }) { "配置已变化，请重新打开" }
        require(id != null || state.providers.size < 8) { "最多保留 8 份配置" }
        val p = Provider(id ?: UUID.randomUUID().toString(), name.trim(), url.trim(), key.trim(), model.trim())
        write(state.copy(providers = if (id == null) state.providers + p else state.providers.map { if (it.id == id) p else it }))
    }

    /** Remove the profile and its bindings in one write; never choose a replacement implicitly. */
    @Synchronized fun delete(id: String): Set<Mode> {
        val state = read()
        require(!state.damaged && state.providers.any { it.id == id }) { "配置已变化，请重新打开" }
        val affected = buildSet {
            if (state.manual == id) add(Mode.MANUAL)
            if (state.auto == id) add(Mode.AUTO)
        }
        write(state.copy(providers = state.providers.filterNot { it.id == id },
            manual = if (Mode.MANUAL in affected) "" else state.manual,
            auto = if (Mode.AUTO in affected) "" else state.auto))
        return affected
    }

    private fun write(state: State) {
        document = buildJsonObject {
            put("version", 1)
            put("manual", state.manual); put("auto", state.auto)
            putJsonArray("providers") {
                state.providers.forEach { p -> addJsonObject {
                    put("id", p.id); put("name", p.name); put("url", p.url)
                    put("key", p.key); put("model", p.model)
                } }
            }
        }.toString()
    }
}
