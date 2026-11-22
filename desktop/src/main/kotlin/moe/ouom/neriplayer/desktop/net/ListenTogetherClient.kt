package moe.ouom.neriplayer.desktop.net

import moe.ouom.neriplayer.desktop.core.AppLog
import moe.ouom.neriplayer.desktop.core.ListenTogetherControlEvent
import moe.ouom.neriplayer.desktop.core.ListenTogetherControlResponse
import moe.ouom.neriplayer.desktop.core.ListenTogetherCreateRoomRequest
import moe.ouom.neriplayer.desktop.core.ListenTogetherJoinRoomRequest
import moe.ouom.neriplayer.desktop.core.ListenTogetherJson
import moe.ouom.neriplayer.desktop.core.ListenTogetherLeaveRoomResponse
import moe.ouom.neriplayer.desktop.core.ListenTogetherRoomResponse
import moe.ouom.neriplayer.desktop.core.ListenTogetherStateResponse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

private const val LT_CLIENT_TAG = "lt"

/**
 * 控制事件完整 JSON 的脱敏摘要（用于对照 Android 发出的事件字段）。
 *
 * 事件本身不含 token/secret；streamUrl/streamUrls 是歌曲直链（可能带签名 query），
 * 只保留协议相关字段结构，把 URL 值截断，避免把长签名写进日志。
 */
fun redactControlEventJson(event: ListenTogetherControlEvent): String {
    val json = ListenTogetherJson.encodeToString(ListenTogetherControlEvent.serializer(), event)
        .replace(Regex("https?://[^\"\\\\]+"), "URL…")
    return json.take(6000)
}

/**
 * 日志里只写「方法 + 路径」，丢掉 query（WS 地址把 token 放在 `?token=` 里，
 * HTTP 虽然把 token 放 Authorization 头，但统一剥 query 更保险）。
 */
internal fun redactListenTogetherUrl(url: String): String {
    val cut = url.indexOf('?')
    val withoutQuery = if (cut >= 0) url.substring(0, cut) else url
    return withoutQuery.removePrefix("http://").removePrefix("https://")
        .removePrefix("ws://").removePrefix("wss://")
}

/**
 * 一起听服务端 HTTP 客户端。
 *
 * 复用 [HttpService] 的 java.net.http.HttpClient（连接复用、超时、UA）；错误响应里
 * worker.js 会返回 `{ok:false,error:"..."}`，所以即使非 2xx 也按协议模型解析。
 */
class ListenTogetherHttpClient(private val http: HttpService) {

    data class Result<T>(val value: T?, val status: Int, val error: String?) {
        val ok: Boolean get() = error == null && value != null && status in 200..299
    }

    suspend fun createRoom(
        baseUrl: String,
        request: ListenTogetherCreateRoomRequest,
    ): Result<ListenTogetherRoomResponse> = post(
        url = "$baseUrl/api/rooms",
        body = ListenTogetherJson.encodeToString(request),
    )

    suspend fun joinRoom(
        baseUrl: String,
        roomId: String,
        request: ListenTogetherJoinRoomRequest,
        bearerToken: String? = null,
    ): Result<ListenTogetherRoomResponse> = post(
        url = "$baseUrl/api/rooms/$roomId/join",
        body = ListenTogetherJson.encodeToString(request),
        bearerToken = bearerToken,
    )

    suspend fun getState(
        baseUrl: String,
        roomId: String,
        token: String,
    ): Result<ListenTogetherStateResponse> = get(
        url = "$baseUrl/api/rooms/$roomId/state",
        bearerToken = token,
    )

    suspend fun leaveRoom(
        baseUrl: String,
        roomId: String,
        token: String,
    ): Result<ListenTogetherLeaveRoomResponse> = post(
        url = "$baseUrl/api/rooms/$roomId/leave",
        body = "{}",
        bearerToken = token,
    )

    suspend fun sendControlEvent(
        baseUrl: String,
        roomId: String,
        token: String,
        event: ListenTogetherControlEvent,
    ): Result<ListenTogetherControlResponse> = post(
        url = "$baseUrl/api/rooms/$roomId/control",
        body = ListenTogetherJson.encodeToString(event),
        bearerToken = token,
    )

    /** GET /healthz：用于「服务端可用性」提示；不要求一定是官方 worker 实现。 */
    suspend fun healthz(baseUrl: String): Boolean {
        val label = redactListenTogetherUrl("$baseUrl/healthz")
        val started = System.currentTimeMillis()
        val response = runCatching { http.execute("GET", "$baseUrl/healthz", timeoutSeconds = 6) }
            .getOrNull()
        if (response == null) {
            AppLog.warn(LT_CLIENT_TAG, "HTTP GET $label 健康检查无响应（${System.currentTimeMillis() - started}ms）")
            return false
        }
        if (response.status !in 200..299) {
            AppLog.warn(LT_CLIENT_TAG, "HTTP GET $label 健康检查返回 ${response.status}（${System.currentTimeMillis() - started}ms）")
            return false
        }
        val text = response.text()
        val healthy = text.contains("neriplayer-listen-together-worker", ignoreCase = true) ||
            (text.contains("\"ok\"") && text.contains("true"))
        AppLog.debug(LT_CLIENT_TAG, "HTTP GET $label 健康检查 ${if (healthy) "通过" else "未识别"}（${System.currentTimeMillis() - started}ms）")
        return healthy
    }

    private inline fun <reified T> get(url: String, bearerToken: String? = null): Result<T> {
        val label = redactListenTogetherUrl(url)
        val started = System.currentTimeMillis()
        val response = runCatching {
            http.execute("GET", url, headers = authHeaders(bearerToken), timeoutSeconds = 15)
        }.getOrElse {
            AppLog.warn(LT_CLIENT_TAG, "HTTP GET $label 失败：${it.message ?: it::class.simpleName}（${System.currentTimeMillis() - started}ms）")
            return networkFailure(it)
        } ?: run {
            AppLog.warn(LT_CLIENT_TAG, "HTTP GET $label 无响应（${System.currentTimeMillis() - started}ms）")
            return Result(null, 0, "无法连接一起听服务端")
        }
        val result: Result<T> = decode(url, response.status, response.text())
        AppLog.debug(LT_CLIENT_TAG, "HTTP GET $label → ${response.status} ${resultSummary(result)}（${System.currentTimeMillis() - started}ms）")
        return result
    }

    private inline fun <reified T> post(
        url: String,
        body: String,
        bearerToken: String? = null,
    ): Result<T> {
        val label = redactListenTogetherUrl(url)
        val started = System.currentTimeMillis()
        val response = runCatching {
            http.execute(
                method = "POST",
                url = url,
                body = body,
                headers = authHeaders(bearerToken) + mapOf("Content-Type" to "application/json; charset=utf-8"),
                timeoutSeconds = 20,
            )
        }.getOrElse {
            AppLog.warn(LT_CLIENT_TAG, "HTTP POST $label 失败：${it.message ?: it::class.simpleName}（${System.currentTimeMillis() - started}ms）")
            return networkFailure(it)
        } ?: run {
            AppLog.warn(LT_CLIENT_TAG, "HTTP POST $label 无响应（${System.currentTimeMillis() - started}ms）")
            return Result(null, 0, "无法连接一起听服务端")
        }
        val result: Result<T> = decode(url, response.status, response.text())
        AppLog.debug(LT_CLIENT_TAG, "HTTP POST $label → ${response.status} ${resultSummary(result)}（${System.currentTimeMillis() - started}ms）")
        return result
    }

    /** 只写 ok / error 文案：body 里可能带 Token/Secret，绝不原样入日志。 */
    private fun resultSummary(result: Result<*>): String =
        if (result.ok) "ok" else "error=${result.error ?: "未知错误"}"

    private inline fun <reified T> decode(url: String, status: Int, body: String): Result<T> {
        val text = body.trim()
        val parsed = runCatching { ListenTogetherJson.decodeFromString<T>(text) }.getOrNull()
        if (parsed != null) return Result(parsed, status, null)
        val hint = when {
            text.isEmpty() -> "空响应"
            else -> text.take(180)
        }
        return Result(null, status, "服务端返回异常（HTTP $status）：$hint")
    }

    private fun authHeaders(token: String?): Map<String, String> =
        if (token.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token")

    private fun <T> networkFailure(error: Throwable): Result<T> {
        if (error is CancellationException) throw error
        val hint = error.message ?: error::class.simpleName.orEmpty()
        return Result(null, 0, "无法连接一起听服务端：$hint")
    }
}

/**
 * 一起听 WebSocket 客户端，基于 java.net.http.WebSocket。
 *
 * 回调都在 HttpClient 的线程上触发，调用方必须自行切回协程/线程再处理。
 */
class ListenTogetherWebSocketClient(
    private val onOpen: () -> Unit,
    private val onText: (String) -> Unit,
    private val onClosed: () -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val lock = Any()
    private val finished = AtomicBoolean(false)

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var manuallyClosed = false

    @Volatile
    private var opened = false

    val isOpen: Boolean get() = opened && !manuallyClosed && socket != null

    fun connect(wsUrl: String) {
        val label = redactListenTogetherUrl(wsUrl)
        val started = System.currentTimeMillis()
        AppLog.info(LT_CLIENT_TAG, "WS 连接开始：$label（连接超时 12s）")
        closeInternal(notify = false)
        manuallyClosed = false
        opened = false
        finished.set(false)

        val listener = object : WebSocket.Listener {
            private val buffer = StringBuilder()

            override fun onOpen(webSocket: WebSocket) {
                opened = true
                webSocket.request(1)
                AppLog.info(LT_CLIENT_TAG, "WS 已打开：$label（握手 ${System.currentTimeMillis() - started}ms）")
                onOpen()
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                if (buffer.length + data.length > MAX_WS_TEXT_CHARS) {
                    buffer.setLength(0)
                    fail("WebSocket 消息过大")
                    webSocket.abort()
                    return null
                }
                buffer.append(data)
                if (last) {
                    val text = buffer.toString()
                    buffer.setLength(0)
                    onText(text)
                }
                webSocket.request(1)
                return null
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                opened = false
                socket = null
                // reason 由服务端给，可能带诊断文案；截断避免超长
                AppLog.info(LT_CLIENT_TAG, "WS 关闭：$label（code=$statusCode, reason=${reason.take(80)}）")
                if (!manuallyClosed) onClosed()
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                opened = false
                socket = null
                AppLog.warn(LT_CLIENT_TAG, "WS 错误：$label（${error.message ?: error::class.simpleName}）")
                if (!manuallyClosed) fail(error.message ?: error::class.simpleName.orEmpty())
            }
        }

        val future = runCatching {
            client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .buildAsync(URI.create(wsUrl), listener)
        }.getOrElse {
            AppLog.warn(LT_CLIENT_TAG, "WS 连接建立异常：$label（${it.message}，${System.currentTimeMillis() - started}ms）")
            fail("无法建立 WebSocket 连接：${it.message}")
            return
        }
        future.whenComplete { webSocket, error ->
            if (error != null) {
                val reason = error.cause?.message ?: error.message ?: "WebSocket 连接失败"
                AppLog.warn(LT_CLIENT_TAG, "WS 连接失败：$label（$reason，${System.currentTimeMillis() - started}ms）")
                if (!manuallyClosed) fail(reason)
            } else if (webSocket != null) {
                synchronized(lock) { socket = webSocket }
            }
        }
    }

    /** 同步发送一帧文本；调用线程会短暂阻塞（仅在连接异常时）。 */
    fun send(text: String): Boolean {
        if (!isOpen) return false
        val webSocket = synchronized(lock) { socket } ?: return false
        return runCatching {
            synchronized(lock) {
                webSocket.sendText(text, true).get(5, TimeUnit.SECONDS)
            }
            true
        }.getOrElse { false }
    }

    fun close() = closeInternal(notify = false)

    private fun closeInternal(notify: Boolean) {
        manuallyClosed = true
        opened = false
        val webSocket = synchronized(lock) {
            val current = socket
            socket = null
            current
        }
        runCatching { webSocket?.sendClose(WebSocket.NORMAL_CLOSURE, "client_closed") }
        if (notify) onClosed()
    }

    private fun fail(message: String) {
        if (finished.compareAndSet(false, true)) onFailure(message)
    }

    private companion object {
        const val MAX_WS_TEXT_CHARS = 2 * 1024 * 1024
    }
}
