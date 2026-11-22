package moe.ouom.neriplayer.desktop.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

object NeriJsonParser {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    fun parse(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()
}

fun JsonElement?.asObject(): JsonObject? = (this as? JsonObject)

fun JsonObject.str(key: String): String? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return (value as? JsonPrimitive)?.contentOrNullSafe()
}

private fun JsonPrimitive.contentOrNullSafe(): String? =
    if (this is JsonNull) null else runCatching { content }.getOrNull()

fun JsonObject.long(key: String): Long? = str(key)?.let { raw ->
    raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
}

fun JsonObject.int(key: String): Int? = str(key)?.let { raw ->
    raw.toIntOrNull() ?: raw.toDoubleOrNull()?.toInt()
}

/**
 * 读取布尔字段。接口里既有 JSON 布尔（true/false），也有 0/1 数字，这里统一兼容，
 * 避免把 true 当成解析失败。
 */
fun JsonObject.bool(key: String): Boolean? = str(key)?.trim()?.lowercase()?.let { raw ->
    when (raw) {
        "true" -> true
        "false" -> false
        else -> raw.toIntOrNull()?.let { it != 0 }
    }
}

fun JsonObject.double(key: String): Double? = str(key)?.toDoubleOrNull()

fun JsonObject.obj(key: String): JsonObject? = this[key].asObject()

fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

fun JsonArray.objects(): List<JsonObject> = this.mapNotNull { it as? JsonObject }

/** 轻量 HTTP 客户端，带 Cookie 记忆。 */
class HttpService {

    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(12))
        .build()

    /**
     * 长空闲后复用的 HTTP/2 连接池里可能留下服务端已单方面关闭、但客户端仍认为可用的连接：
     * 请求会在上面挂到超时，且后续请求继续命中同一条连接，表现为「所有请求都卡住」。
     * 超时后用一条独立的 HTTP/1.1 客户端重试，绕开被污染的连接池。
     */
    private val freshClient: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(12))
        .build()

    private val cookieJar = ConcurrentHashMap<String, MutableMap<String, String>>()

    /** Cookie 按平台分组，让登录态可以在同一平台的子域之间共享。 */
    fun jarKeyFor(host: String): String = when {
        host.endsWith("bilibili.com") -> "bilibili"
        host.endsWith("music.163.com") || host.endsWith("163.com") -> "netease"
        else -> host
    }

    fun cookiesFor(host: String): Map<String, String> = cookieJar[jarKeyFor(host)].orEmpty()

    fun cookieSnapshot(key: String): Map<String, String> = cookieJar[key].orEmpty().toMap()

    fun setCookie(host: String, name: String, value: String) {
        cookieJar.getOrPut(jarKeyFor(host)) { ConcurrentHashMap() }[name] = value
    }

    fun importCookies(key: String, cookies: Map<String, String>) {
        val jar = cookieJar.getOrPut(key) { ConcurrentHashMap() }
        cookies.forEach { (name, value) -> if (value.isNotBlank()) jar[name] = value }
    }

    fun clearCookies(key: String) {
        cookieJar.remove(key)
    }

    private fun storeCookies(host: String, response: HttpResponse<*>) {
        val values = response.headers().allValues("set-cookie")
        if (values.isEmpty()) return
        val jar = cookieJar.getOrPut(jarKeyFor(host)) { ConcurrentHashMap() }
        values.forEach { header ->
            val pair = header.substringBefore(';')
            val name = pair.substringBefore('=', "").trim()
            val value = pair.substringAfter('=', "").trim()
            if (name.isNotEmpty() && value.isNotEmpty()) jar[name] = value
        }
    }

    private fun cookieHeaderFor(host: String): String? =
        cookieJar[jarKeyFor(host)]?.takeIf { it.isNotEmpty() }
            ?.entries?.joinToString("; ") { "${it.key}=${it.value}" }

    /**
     * 与 [execute] 相同，但走独立的 HTTP/1.1 客户端，不复用共享连接池。
     * 用于一起听这类「长时间空闲后突然发起、且必须可靠」的控制请求：
     * 共享连接池里的 HTTP/2 连接可能在空闲期间被服务端单方面关闭，复用会挂到超时。
     */
    fun executeFresh(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 30,
    ): RawResponse? = runCatching {
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        val builder = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("User-Agent", DESKTOP_USER_AGENT)
            .header("Accept-Encoding", "gzip")
        headers.forEach { (key, value) -> builder.header(key, value) }
        cookieHeaderFor(host)?.let { builder.header("Cookie", it) }
        val publisher = if (body == null) {
            HttpRequest.BodyPublishers.noBody()
        } else {
            HttpRequest.BodyPublishers.ofString(body)
        }
        when (method.uppercase()) {
            "GET" -> builder.GET()
            "POST" -> builder.POST(publisher)
            "PUT" -> builder.PUT(publisher)
            "PATCH" -> builder.method("PATCH", publisher)
            "DELETE" -> builder.DELETE()
            else -> builder.method(method.uppercase(), publisher)
        }
        val response = freshClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        storeCookies(host, response)
        val raw = response.body() ?: ByteArray(0)
        val bytes = if (
            response.headers().firstValue("Content-Encoding").orElse("").contains("gzip") &&
            raw.size > 2 && raw[0] == 0x1F.toByte() && raw[1] == 0x8B.toByte()
        ) {
            gunzipOrRaw(raw)
        } else {
            raw
        }
        RawResponse(
            status = response.statusCode(),
            bytes = bytes,
            header = { name -> response.headers().firstValue(name).orElse(null) },
        )
    }.getOrNull()

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 15,
    ): String? = request("GET", url, null, headers, timeoutSeconds)

    fun postForm(
        url: String,
        form: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 15,
    ): String? {
        val body = form.entries.joinToString("&") { (key, value) ->
            "${urlEncode(key)}=${urlEncode(value)}"
        }
        return request("POST", url, body, headers + mapOf("Content-Type" to "application/x-www-form-urlencoded"), timeoutSeconds)
    }

    /** 解压 gzip 字节；失败时原样返回，避免单个坏响应中断整个请求。 */
    private fun gunzipOrRaw(bytes: ByteArray): ByteArray = runCatching {
        GZIPInputStream(bytes.inputStream()).use { stream ->
            val out = ByteArrayOutputStream()
            stream.copyTo(out)
            out.toByteArray()
        }
    }.getOrDefault(bytes)

    private fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
        timeoutSeconds: Long,
    ): String? {
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        fun buildRequest(): HttpRequest {
            val builder = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "gzip")
            headers.forEach { (key, value) -> builder.header(key, value) }
            cookieHeaderFor(host)?.let { builder.header("Cookie", it) }
            if (method == "POST") {
                builder.POST(HttpRequest.BodyPublishers.ofString(body.orEmpty()))
            } else {
                builder.GET()
            }
            return builder.build()
        }
        fun sendWith(connectionClient: HttpClient): Pair<ByteArray?, Boolean> {
            val response = connectionClient.send(buildRequest(), HttpResponse.BodyHandlers.ofByteArray())
            storeCookies(host, response)
            val gzipped = response.headers().firstValue("Content-Encoding").orElse("").contains("gzip")
            return response.body() to gzipped
        }
        return runCatching {
            val (bytes, gzipped) = try {
                sendWith(client)
            } catch (timeout: java.net.http.HttpTimeoutException) {
                // 复用连接卡死：换一条全新 HTTP/1.1 连接重试，避免请求永远超时
                sendWith(freshClient)
            }
            bytes?.let { raw ->
                if (gzipped) gunzipOrRaw(raw).decodeToString() else raw.decodeToString()
            }
        }.getOrNull()
    }

    fun download(url: String, headers: Map<String, String> = emptyMap()): ByteArray? = runCatching {
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        val builder = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(25))
            .setHeader("User-Agent", DESKTOP_USER_AGENT)
        headers.forEach { (key, value) -> builder.setHeader(key, value) }
        cookieHeaderFor(host)?.let { builder.setHeader("Cookie", it) }
        val response = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofByteArray())
        storeCookies(host, response)
        response.body()
    }.getOrNull()

    /** 需要读取状态码 / 响应头的调用（GitHub 同步接口要用）。 */
    data class RawResponse(
        val status: Int,
        val bytes: ByteArray,
        val header: (String) -> String?,
    ) {
        fun text(): String = bytes.decodeToString()
    }

    fun execute(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 30,
    ): RawResponse? = runCatching {
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        fun buildRequest(): HttpRequest {
            val builder = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("User-Agent", DESKTOP_USER_AGENT)
                .header("Accept-Encoding", "gzip")
            headers.forEach { (key, value) -> builder.header(key, value) }
            cookieHeaderFor(host)?.let { builder.header("Cookie", it) }
            val publisher = if (body == null) {
                HttpRequest.BodyPublishers.noBody()
            } else {
                HttpRequest.BodyPublishers.ofString(body)
            }
            when (method.uppercase()) {
                "GET" -> builder.GET()
                "POST" -> builder.POST(publisher)
                "PUT" -> builder.PUT(publisher)
                "PATCH" -> builder.method("PATCH", publisher)
                "DELETE" -> builder.DELETE()
                else -> builder.method(method.uppercase(), publisher)
            }
            return builder.build()
        }
        fun sendWith(connectionClient: HttpClient): HttpResponse<ByteArray> {
            val response = connectionClient.send(buildRequest(), HttpResponse.BodyHandlers.ofByteArray())
            storeCookies(host, response)
            return response
        }
        val response = try {
            sendWith(client)
        } catch (timeout: java.net.http.HttpTimeoutException) {
            // 复用连接卡死：换一条全新 HTTP/1.1 连接重试，避免请求永远超时
            sendWith(freshClient)
        }
        val raw = response.body() ?: ByteArray(0)
        // 声明了 Accept-Encoding: gzip，就必须自行解压，否则 JSON 会解析失败
        val bytes = if (
            response.headers().firstValue("Content-Encoding").orElse("").contains("gzip") &&
            raw.size > 2 && raw[0] == 0x1F.toByte() && raw[1] == 0x8B.toByte()
        ) {
            gunzipOrRaw(raw)
        } else {
            raw
        }
        RawResponse(
            status = response.statusCode(),
            bytes = bytes,
            header = { name -> response.headers().firstValue(name).orElse(null) },
        )
    }.getOrNull()

    /**
     * 流式下载到文件：边下边写盘并回报进度，避免整首歌驻留内存。
     * @return 成功返回 true；失败时删除半成品文件
     */
    fun downloadToFile(
        url: String,
        headers: Map<String, String>,
        target: File,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): Boolean {
        return runCatching {
            val host = runCatching { URI(url).host }.getOrNull().orEmpty()
            val builder = HttpRequest.newBuilder(URI(url))
                .timeout(Duration.ofMinutes(30))
                .header("User-Agent", DESKTOP_USER_AGENT)
            headers.forEach { (key, value) -> builder.header(key, value) }
            cookieHeaderFor(host)?.let { builder.header("Cookie", it) }
            val response = client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() !in 200..299) return@runCatching false
            val total = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
            target.parentFile?.mkdirs()
            var downloaded = 0L
            BufferedInputStream(response.body()).use { input ->
                BufferedOutputStream(target.outputStream()).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            downloaded > 0
        }.getOrElse { false }.also { success ->
            if (!success) runCatching { target.delete() }
        }
    }
}

fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
