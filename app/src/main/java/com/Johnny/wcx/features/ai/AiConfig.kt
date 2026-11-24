package com.Johnny.wcx.features.ai

import android.os.Looper
import com.Johnny.wcx.features.api.core.WeApi
import com.Johnny.wcx.features.api.core.WeDatabaseApi
import com.Johnny.wcx.features.items.chat.ReplyProfiles
import com.Johnny.wcx.features.items.chat.SmartReplyClient
import com.Johnny.wcx.preferences.WePrefs
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Message types used by the business modules adapted from AARCNoa/zhiyou (GPL-3.0). */
internal object AiConfig {
    enum class Purpose(val key: String, val owner: SmartReplyClient.Owner) {
        FRIEND_PROFILE("friend_profile", SmartReplyClient.Owner.FRIEND_ARCHIVE),
        FRIEND_SUGGEST("friend_profile", SmartReplyClient.Owner.FRIEND_SUGGEST),
        FRIEND_AUTO_REPLY("friend_profile", SmartReplyClient.Owner.FRIEND_AUTO_REPLY),
        GROUP_DIGEST("group_digest", SmartReplyClient.Owner.GROUP_DIGEST),
    }

    class Binding internal constructor(
        val id: String,
        val account: String,
        internal val purpose: Purpose,
        internal val providerName: String,
        internal val endpoint: HttpUrl,
        internal val apiKey: String,
        internal val model: String,
    )

    data class Selection(
        val id: String,
        val account: String,
        val providerName: String,
        val model: String,
        val endpointHost: String,
    )

    data class ToolCall(val id: String, val name: String, val arguments: String)

    data class ChatMessage(
        val role: String,
        val content: String? = null,
        val toolCalls: List<ToolCall>? = null,
        val toolCallId: String? = null,
        val imageUrl: String? = null,
    )

    data class ChatResult(
        val text: String?,
        val error: String?,
        val toolCalls: List<ToolCall> = emptyList(),
        val finishReason: String? = null,
        val reasoning: String? = null,
    )

    private fun account(): String? = runCatching { WeApi.selfWxId.takeIf { it.isNotBlank() } }.getOrNull()

    private fun pref(account: String, purpose: Purpose) = "ai_workbench_${purpose.key}_${fingerprint(account)}"

    fun providerId(purpose: Purpose): String = account()?.let {
        WePrefs.getStringOrDef(pref(it, purpose), "")
    }.orEmpty()

    fun chooseProvider(purpose: Purpose, id: String, expectedAccount: String? = null): Boolean {
        val account = account()?.takeIf { expectedAccount == null || it == expectedAccount } ?: return false
        if (id.isNotEmpty() && ReplyProfiles.read().providers.none { it.id == id }) return false
        if (account() != account) return false
        val key = pref(account, purpose)
        if (WePrefs.getStringOrDef(key, "") == id) return true
        WePrefs.putString(key, id)
        Purpose.entries.filter { it.key == purpose.key }.forEach { SmartReplyClient.cancel(it.owner) }
        return true
    }

    fun isConfigured(purpose: Purpose): Boolean = bindingId(purpose) != null

    fun bindingId(purpose: Purpose): String? = selection(purpose)?.id

    fun selection(purpose: Purpose): Selection? = account()?.let { resolve(purpose, it) }?.let {
        Selection(it.id, it.account, it.providerName, it.model, it.endpoint.host)
    }

    fun capture(purpose: Purpose): Binding? = WeDatabaseApi.currentAiAccount()?.let { resolve(purpose, it) }

    private fun resolve(purpose: Purpose, account: String): Binding? {
        val id = WePrefs.getStringOrDef(pref(account, purpose), "")
        val provider = ReplyProfiles.read().providers.firstOrNull { it.id == id } ?: return null
        if (provider.key.isBlank() || provider.model.isBlank()) return null
        val endpoint = runCatching { SmartReplyClient.endpoint(provider.url) }.getOrNull() ?: return null
        val fingerprint = fingerprint(provider.id, endpoint.toString(), provider.model)
        return Binding(fingerprint, account, purpose, provider.name, endpoint, provider.key, provider.model)
    }

    private fun fingerprint(vararg fields: String): String = MessageDigest.getInstance("SHA-256")
        .digest(fields.joinToString("\u0000").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun isCurrent(binding: Binding): Boolean {
        val current = capture(binding.purpose) ?: return false
        return current.account == binding.account && current.id == binding.id && current.apiKey == binding.apiKey
    }

    fun chatWithTools(
        messages: List<ChatMessage>,
        tools: JsonArray? = null,
        temperature: Double? = null,
        readTimeoutMs: Int = 60_000,
        purpose: Purpose = Purpose.FRIEND_PROFILE,
        binding: Binding? = null,
    ): ChatResult {
        if (Looper.myLooper() == Looper.getMainLooper()) return ChatResult(null, "请在后台运行 AI 整理")
        return runBlocking { requestWithTools(messages, tools, temperature, readTimeoutMs, purpose, binding) }
    }

    suspend fun requestWithTools(
        messages: List<ChatMessage>,
        tools: JsonArray? = null,
        temperature: Double? = null,
        readTimeoutMs: Int = 60_000,
        purpose: Purpose = Purpose.FRIEND_PROFILE,
        binding: Binding? = null,
    ): ChatResult = withContext(Dispatchers.IO) {
        val selected = binding ?: capture(purpose)
            ?: return@withContext ChatResult(null, "请先选择模型，等待当前微信账号的消息库就绪")
        if (selected.purpose != purpose || !isCurrent(selected)) {
            return@withContext ChatResult(null, "模型配置或微信账号已变更，请重新启动任务")
        }
        try {
            val replyOptions = when (purpose) {
                Purpose.FRIEND_SUGGEST, Purpose.FRIEND_AUTO_REPLY -> AiReplySettings.read()
                else -> null
            }
            val body = requestBody(selected.model, messages, tools, temperature, replyOptions)
            val request = Request.Builder().url(selected.endpoint)
                .header("Authorization", "Bearer ${selected.apiKey}")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val response = SmartReplyClient.request(request, purpose.owner, readTimeoutMs)
            if (!isCurrent(selected)) ChatResult(null, "模型配置或微信账号已变更，请重新启动任务")
            else parseResponse(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ChatResult(null, SmartReplyClient.errorText(e))
        }
    }

    private fun requestBody(
        model: String,
        messages: List<ChatMessage>,
        tools: JsonArray?,
        temperature: Double?,
        replyOptions: AiReplyOptions?,
    ) = buildJsonObject {
        put("model", model)
        put("stream", false)
        (replyOptions?.temperature ?: temperature)?.let { put("temperature", it) }
        replyOptions?.maxTokens?.takeIf { it > 0 }?.let { put("max_tokens", it) }
        val effort = replyOptions?.effort?.takeUnless { it == ReasoningEffort.OFF }
        if (effort != null) {
            if (model.substringAfterLast('/').startsWith("deepseek", ignoreCase = true)) {
                put("thinking", buildJsonObject { put("type", "enabled") })
            }
            put("reasoning_effort", effort.id)
        }
        tools?.takeUnless { it.isEmpty() }?.let { put("tools", it) }
        put("messages", buildJsonArray {
            messages.forEach { message ->
                add(buildJsonObject {
                    put("role", message.role)
                    put("content", message.encodedContent())
                    message.toolCallId?.let { put("tool_call_id", it) }
                    message.toolCalls?.takeUnless { it.isEmpty() }?.let { calls ->
                        put("tool_calls", buildJsonArray {
                            calls.forEach { call ->
                                add(buildJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", call.name)
                                        put("arguments", call.arguments)
                                    })
                                })
                            }
                        })
                    }
                })
            }
        })
    }

    private fun ChatMessage.encodedContent(): JsonElement {
        val image = imageUrl?.takeIf { role == "user" && it.startsWith("data:image/jpeg;base64,") }
            ?: return content?.let(::JsonPrimitive) ?: JsonNull
        return buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", content.orEmpty())
            })
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject { put("url", image) })
            })
        }
    }

    private fun parseResponse(raw: String): ChatResult {
        val response = Json.parseToJsonElement(raw) as? JsonObject
        val choice = (response?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return ChatResult(null, "AI 服务未返回消息内容")
        val message = choice["message"] as? JsonObject
            ?: return ChatResult(null, "AI 服务未返回消息内容")
        val text = message.string("content")?.trim()?.takeIf { it.isNotEmpty() }
        val calls = (message["tool_calls"] as? JsonArray)?.mapNotNull { value ->
            val call = value as? JsonObject ?: return@mapNotNull null
            val function = call["function"] as? JsonObject ?: return@mapNotNull null
            val id = call.string("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = function.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val arguments = function.string("arguments") ?: return@mapNotNull null
            ToolCall(id, name, arguments)
        }.orEmpty()
        val error = if (text == null && calls.isEmpty()) "AI 服务返回空消息" else null
        val reasoning = message.string("reasoning_content")?.trim()?.take(2_000)?.ifBlank { null }
        return ChatResult(text, error, calls, choice.string("finish_reason"), reasoning)
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
}
