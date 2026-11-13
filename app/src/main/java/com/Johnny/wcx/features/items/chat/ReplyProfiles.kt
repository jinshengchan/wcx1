package com.Johnny.wcx.features.items.chat

import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.preferences.WePrefs.Companion.prefOption
import kotlinx.serialization.json.*
import java.util.UUID

/** One versioned preference write commits profiles and bindings together. Legacy keys are untouched. */
internal object ReplyProfiles {
    enum class Mode { MANUAL, AUTO }
    data class Provider(val id: String, val name: String, val url: String, val key: String, val model: String, val models: List<String> = emptyList())
    data class State(val providers: List<Provider>, val manual: String, val auto: String, val damaged: Boolean = false,
        val manualModel: String = "", val autoModel: String = "")
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
                Provider(field("id"), field("name"), field("url"), field("key"), field("model"),
                    p["models"]?.jsonArray?.map { it.jsonPrimitive.content }?.take(200).orEmpty())
            }
            val manual = obj["manual"]!!.jsonPrimitive.content
            val auto = obj["auto"]!!.jsonPrimitive.content
            check(providers.all { it.id.isNotBlank() } && providers.map { it.id }.distinct().size == providers.size)
            check((manual.isEmpty() || providers.any { it.id == manual }) &&
                (auto.isEmpty() || providers.any { it.id == auto }))
            State(providers, manual, auto,
                manualModel = obj["manualModel"]?.jsonPrimitive?.content.orEmpty(),
                autoModel = obj["autoModel"]?.jsonPrimitive?.content.orEmpty())
        } catch (_: Exception) {
            // Fail closed; do not replace damaged/future-version data or silently fall back to an old key.
            State(emptyList(), "", "", damaged = true)
        }
    }

    fun provider(mode: Mode): Provider {
        val state = read()
        val id = if (mode == Mode.MANUAL) state.manual else state.auto
        val model = if (mode == Mode.MANUAL) state.manualModel else state.autoModel
        return state.providers.firstOrNull { it.id == id }?.let { if (model.isEmpty()) it else it.copy(model = model) } ?: Provider("", if (state.damaged) "配置不可用" else "未选择模型配置", "", "", "")
    }

    @Synchronized fun bind(mode: Mode, id: String) {
        val state = read()
        require(!state.damaged && state.providers.any { it.id == id })
        if ((if (mode == Mode.MANUAL) state.manual else state.auto) == id) return
        write(if (mode == Mode.MANUAL) state.copy(manual = id, manualModel = "") else state.copy(auto = id, autoModel = ""))
    }

    @Synchronized fun save(id: String?, name: String, url: String, key: String, model: String, models: List<String>? = null) {
        val state = read()
        require(!state.damaged) { "模型配置损坏，已停止修改" }
        require(name.isNotBlank() && name.length <= 40) { "配置名称需为 1–40 个字符" }
        SmartReplyClient.endpoint(url)
        require(model.isNotBlank()) { "请填写模型名称" }
        require(key.none { it == '\r' || it == '\n' }) { "API Key 不能包含换行" }
        require(id == null || state.providers.any { it.id == id }) { "配置已变化，请重新打开" }
        require(id != null || state.providers.size < 8) { "最多保留 8 份配置" }
        val old = state.providers.firstOrNull { it.id == id }
        val changedServer = old != null && (old.url != url.trim() || old.key != key.trim())
        val available = (models ?: if (changedServer) emptyList() else old?.models.orEmpty())
            .map(String::trim).filter(String::isNotEmpty).distinct().take(200)
        val p = Provider(id ?: UUID.randomUUID().toString(), name.trim(), url.trim(), key.trim(), model.trim(), available)
        write(state.copy(providers = if (id == null) state.providers + p else state.providers.map { if (it.id == id) p else it },
            manualModel = if (changedServer && state.manual == id) "" else state.manualModel,
            autoModel = if (changedServer && state.auto == id) "" else state.autoModel))
    }

    @Synchronized fun selectModel(mode: Mode, model: String) {
        val state = read()
        val id = if (mode == Mode.MANUAL) state.manual else state.auto
        val p = state.providers.firstOrNull { it.id == id }
        require(!state.damaged && p != null && (model == p.model || model in p.models)) { "模型列表已变化，请重新选择" }
        write(if (mode == Mode.MANUAL) state.copy(manualModel = model) else state.copy(autoModel = model))
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
            auto = if (Mode.AUTO in affected) "" else state.auto,
            manualModel = if (Mode.MANUAL in affected) "" else state.manualModel,
            autoModel = if (Mode.AUTO in affected) "" else state.autoModel))
        return affected
    }

    private fun write(state: State) {
        document = buildJsonObject {
            put("version", 1)
            put("manual", state.manual); put("auto", state.auto)
            put("manualModel", state.manualModel); put("autoModel", state.autoModel)
            putJsonArray("providers") {
                state.providers.forEach { p -> addJsonObject {
                    put("id", p.id); put("name", p.name); put("url", p.url)
                    put("key", p.key); put("model", p.model)
                    putJsonArray("models") { p.models.forEach { add(JsonPrimitive(it)) } }
                } }
            }
        }.toString()
    }
}
