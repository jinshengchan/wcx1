package com.Johnny.wcx.features.items.chat

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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

/** Shared transport for explicitly enabled reply features. Never retry, follow redirects or log credentials / chat content. */
internal object SmartReplyClient {
    class Failure(message: String) : IOException(message)
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
    enum class Owner { MANUAL, AUTO, MODELS }
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

    suspend fun request(request: Request, owner: Owner = Owner.MANUAL): String = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request.newBuilder().tag(Attempt::class.java, Attempt()).build())
        calls[call] = owner
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
                        if (!it.isSuccessful) throw Failure("HTTP ${it.code}，请检查地址、密钥、模型及服务配额")
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
