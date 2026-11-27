package com.Johnny.wcx.features.items.chat

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.net.ConnectException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Shared transport. Each call makes at most one attempt and never follows redirects or logs the payload. */
internal object SmartReplyClient {
    class Failure(
        message: String,
        val rejectedParameters: Set<String> = emptySet(),
        val rejectedImageInput: Boolean = false,
        val rejectedToolInput: Boolean = false,
    ) : IOException(message)
    /** Only fixed descriptions and exception type names; never include exception.message. */
    fun errorText(error: Throwable): String {
        if (error is Failure) return error.message ?: "请求失败 [API]"
        val causes = generateSequence(error) { it.cause }.take(6).toList()
        return when {
            causes.any { it is UnknownHostException } -> "域名解析失败，请检查 API 域名与网络 [DNS]"
            causes.any { it is SSLException } -> "HTTPS 证书或握手失败，请检查服务证书及代理设置 [TLS]"
            causes.any { it is SocketTimeoutException || it is InterruptedIOException } -> "连接或响应超时，请稍后手动重试 [TIMEOUT]"
            causes.any { it is ConnectException } -> "无法连接 API 服务，请检查地址、端口及网络 [CONNECT]"
            causes.any { it.javaClass.simpleName == "NetworkOnMainThreadException" } -> "请求触发主线程网络限制 [THREAD]"
            error is kotlinx.serialization.SerializationException -> "API 响应不是预期的 JSON 格式，请核对接口是否兼容 [JSON]"
            error is IllegalArgumentException -> "请求参数或返回结构无效，请核对地址、密钥格式及模型 [FORMAT]"
            error is IOException -> "网络传输中断，请检查连接后手动重试 [IO]"
            else -> "处理失败 [${error.javaClass.simpleName.take(64)}]"
        }
    }

    private class Attempt { val sent = AtomicBoolean(false) }
    private val http by lazy {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .callTimeout(100, TimeUnit.SECONDS)
            // OkHttp may follow up a 503 Retry-After: 0 even with connection retries off.
            // Keep the guard on this Call's request, before any second request reaches the wire.
            .addNetworkInterceptor { chain ->
                val attempt = chain.request().tag(Attempt::class.java)
                if (attempt == null || !attempt.sent.compareAndSet(false, true)) {
                    throw Failure("服务暂时不可用，已停止重复请求，请稍后手动重试")
                }
                chain.proceed(chain.request())
            }
            .build()
    }
    enum class Owner { MANUAL, AUTO, MODELS, FRIEND_ARCHIVE, FRIEND_SUGGEST, FRIEND_AUTO_REPLY, GROUP_DIGEST, TOOLS, TRANSLATION }
    private val calls = ConcurrentHashMap<Call, Owner>()

    fun cancel(owner: Owner) = calls.forEach { (call, caller) -> if (caller == owner) call.cancel() }

    fun endpoint(raw: String, models: Boolean = false): HttpUrl {
        val text = raw.trim().let { if ("://" in it) it else "https://$it" }
        val url = try { text.toHttpUrl() } catch (_: IllegalArgumentException) {
            throw Failure("API 地址无效")
        }
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null) {
            throw Failure("请填写 HTTPS API 地址，不含账号、查询参数或片段")
        }
        val path = url.encodedPath.trimEnd('/')
        val base = when {
            path.endsWith("/chat/completions") -> path.removeSuffix("/chat/completions")
            path.endsWith("/models") -> path.removeSuffix("/models")
            path.isEmpty() -> "/v1"
            else -> path
        }
        return url.newBuilder().encodedPath(base + if (models) "/models" else "/chat/completions").build()
    }

    suspend fun request(
        request: Request,
        owner: Owner = Owner.MANUAL,
        readTimeoutMs: Int? = null,
        optionalParameters: Set<String> = emptySet(),
        hasImageInput: Boolean = false,
        hasToolInput: Boolean = false,
    ): String = suspendCancellableCoroutine { continuation ->
        val client = if (readTimeoutMs == null) http else {
            val timeout = readTimeoutMs.coerceIn(1000, 180_000).toLong()
            http.newBuilder().readTimeout(timeout, TimeUnit.MILLISECONDS)
                .callTimeout(timeout + 20_000L, TimeUnit.MILLISECONDS).build()
        }
        val call = client.newCall(request.newBuilder().tag(Attempt::class.java, Attempt()).build())
        calls[call] = owner
        if (owner != Owner.MODELS && owner != Owner.TRANSLATION && !com.Johnny.wcx.features.ai.AiConfig.isMasterEnabled) {
            calls.remove(call)
            continuation.resumeWithException(Failure("AI 助手总开关已关闭"))
            return@suspendCancellableCoroutine
        }
        continuation.invokeOnCancellation { call.cancel(); calls.remove(call) }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                calls.remove(call)
                if (continuation.isActive) continuation.resumeWithException(
                    if (e is Failure) e else Failure(errorText(e)),
                )
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw it.requestFailure(optionalParameters, hasImageInput, hasToolInput)
                        val source = it.body.source()
                        if (source.request(256 * 1024L + 1)) throw Failure("API 响应过大")
                        source.readUtf8()
                    }
                }
                calls.remove(call)
                if (continuation.isActive) result.fold(
                    onSuccess = { continuation.resume(it) },
                    onFailure = { continuation.resumeWithException(it) },
                )
            }
        })
    }

    private val parameterRejectionCodes = setOf(
        "unsupported_parameter", "unsupported_value", "unknown_parameter", "unrecognized_parameter",
    )
    private val genericRequestCodes = setOf("invalid_request_error", "invalid_request", "bad_request", "400", "422")
    private val imageRejectionCodes = setOf(
        "invalid_image", "invalid_image_url", "invalid_image_format", "invalid_image_data", "invalid_base64_image",
        "image_parse_error", "image_decode_failed", "unsupported_image", "unsupported_image_type",
        "unsupported_image_format", "unsupported_image_input", "vision_not_supported",
    )
    private const val IMAGE_INPUT = """(?:images?|image[_ -](?:url|input|data|format|type|content)s?|vision|multimodal(?: input)?)"""
    private val imageRejectionMessage = Regex(
        """\b(?:does not support|doesn't support|do not support|not supported:|unsupported|invalid)\s+""" +
            """(?:the\s+)?(?:(?:content type|input type|parameter)\s*[:=]\s*)?['"`]?\b$IMAGE_INPUT\b|""" +
            """\b$IMAGE_INPUT\b['"`]?\s+(?:is |are )?(?:not supported|unsupported|invalid|only supported by)\b|""" +
            """(?:模型|接口)不支持(?:图片|图像|视觉|多模态)""",
        RegexOption.IGNORE_CASE,
    )
    private val toolRejectionCodes = setOf(
        "unsupported_tools", "tools_not_supported", "unsupported_tool_calling", "tool_calling_not_supported",
        "unsupported_function_calling", "function_calling_not_supported",
    )
    private val toolParameters = setOf("tools", "tool_choice", "tool_calls", "tool_call_id", "functions", "function_call")
    private const val TOOL_MESSAGE_PARAMETER = """messages\[\d+]\.(?:tool_calls|tool_call_id)"""
    private const val TOOL_INPUT = """(?:tools|tool[_ -](?:choice|calls?(?:[_ -]id)?|calling|use)|functions|function[_ -](?:calls?|calling))"""
    private val toolMessageParameter = Regex(TOOL_MESSAGE_PARAMETER)
    private val toolParameterNames = toolParameters.joinToString("|")
    private val toolRejectionMessage = Regex(
        """\b(?:does not support|doesn't support|do not support|unsupported)\s+(?:the\s+)?['"`]?$TOOL_INPUT\b|""" +
            """\b$TOOL_INPUT\b['"`]?\s+(?:is |are )?(?:not supported|unsupported)\b|""" +
            """\b(?:unsupported|unknown|unrecognized|unexpected) (?:request )?(?:parameter|argument|field)\s*[:=]?\s*['"`]?(?:$toolParameterNames|$TOOL_MESSAGE_PARAMETER)\b(?![./-]\w|\[)|""" +
            """(?:模型|接口)不支持(?:工具调用|函数调用)""",
        RegexOption.IGNORE_CASE,
    )

    private fun Response.requestFailure(sent: Set<String>, hasImageInput: Boolean, hasToolInput: Boolean): Failure {
        val summary = "HTTP $code，请检查地址、密钥、模型及服务配额"
        val invalidRequest = code == 400 || code == 422
        val canRejectParameter = sent.isNotEmpty() && invalidRequest
        val canRejectImage = hasImageInput && (code == 400 || code == 415 || code == 422)
        val canRejectTool = hasToolInput && invalidRequest
        if (!canRejectParameter && !canRejectImage && !canRejectTool) return Failure(summary)
        val raw = runCatching { peekBody(8 * 1024L).string() }.getOrNull() ?: return Failure(summary)
        val json = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
        val error = (json as? JsonObject)?.get("error") ?: json
        val fields = error as? JsonObject
        val parameter = (fields?.get("param") as? JsonPrimitive)?.contentOrNull
        val reason = (fields?.get("code") as? JsonPrimitive)?.contentOrNull
            ?: (fields?.get("type") as? JsonPrimitive)?.contentOrNull
        val message = (fields?.get("message") as? JsonPrimitive)?.contentOrNull
            ?: (error as? JsonPrimitive)?.contentOrNull
            ?: raw.takeIf { json == null }
        val rejectedImage = canRejectImage && when (reason) {
            in imageRejectionCodes -> true
            in parameterRejectionCodes -> parameter == "image_url" || message?.let(imageRejectionMessage::containsMatchIn) == true
            null, in genericRequestCodes -> message?.let(imageRejectionMessage::containsMatchIn) == true
            else -> false
        }
        val rejectedTool = canRejectTool && when (reason) {
            in toolRejectionCodes -> true
            in parameterRejectionCodes, null, in genericRequestCodes ->
                (reason in parameterRejectionCodes &&
                    (parameter in toolParameters || parameter?.matches(toolMessageParameter) == true)) ||
                    message?.let(toolRejectionMessage::containsMatchIn) == true
            else -> false
        }
        val rejectedParameters = if (canRejectParameter) rejectedOptionalParameters(sent, reason, parameter, message)
            else emptySet()
        return Failure(summary, rejectedParameters, rejectedImage, rejectedTool)
    }

    private fun rejectedOptionalParameters(
        sent: Set<String>, reason: String?, parameter: String?, message: String?,
    ): Set<String> {
        if (reason in parameterRejectionCodes && parameter != null) return sent.intersect(setOf(parameter))
        // Explicit errors such as authentication, quota and context limits are terminal.
        if (reason != null && reason !in genericRequestCodes && reason !in parameterRejectionCodes) return emptySet()
        if (message == null) return emptySet()
        return sent.filterTo(mutableSetOf()) { parameterName ->
            val name = Regex.escape(parameterName)
            val before = """\b(?:unsupported|unknown|unrecognized|unexpected) (?:request )?(?:parameter|argument|field)(?: supplied)?\s*[:=]?\s*['"\x60]?$name\b(?![./-]\w|\[)"""
            val after = """(?<![\w.])$name\b['"\x60]?\s+(?:is (?:not supported|not allowed|unsupported|unrecognized)|must be (?:less than|at most|between))\b"""
            Regex("$before|$after", RegexOption.IGNORE_CASE).containsMatchIn(message)
        }
    }

    fun parseReplies(body: String, count: Int): List<String> {
        val choices = Json.parseToJsonElement(body).jsonObject["choices"]?.jsonArray.orEmpty()
        return choices.flatMap { choice ->
            val content = choice.jsonObject["message"]?.jsonObject?.get("content")
                ?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            val unwrapped = content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val array = runCatching { Json.parseToJsonElement(unwrapped) as? JsonArray }.getOrNull()
            array?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: content.lines()
        }.map { it.trim().replace(Regex("^\\d+[.、)）]\\s*"), "").trim('"').trim() }
            .filter { it.isNotBlank() && !it.startsWith("```") }
            .distinct().take(count.coerceIn(1, 6))
    }

    fun parseModelIds(body: String): List<String> =
        Json.parseToJsonElement(body).jsonObject["data"]?.jsonArray.orEmpty().mapNotNull {
            it.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
        }.distinct().take(200)
}
