package moe.ouom.neriplayer.desktop.core

import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque

/**
 * 后端统一日志出口：内存环形缓冲 + 入环前脱敏。
 *
 * 为什么要有它：本仓库后端的日志一直只流向启动进程的 stdout/stderr，
 * 用户真实运行时既看不到、也没法让用户回传。这里把「最近发生了什么」留在内存里，
 * 由 `debug.recentLogs` 交给前端的日志查看器展示（协议见 desktop/docs/protocol-config.md）。
 *
 * 三条设计约束：
 * 1. **先脱敏再入环**：环里的内容会被前端整段展示、也可能被用户复制出去，
 *    Cookie / Token / Authorization 这类值一旦进来就已经晚了。
 * 2. **只保留最近 [CAPACITY] 条、单条最多 [MAX_MESSAGE_LENGTH] 字符**：内存有界，长任务不会把它撑爆。
 * 3. **stdout/stderr 桥接是可选的旁路**：既有的 `println` / `System.err` 调用点很多，
 *    统一改道成本高；这里把标准流 tee 一份进缓冲，原输出（开发时重定向到 backend.log）保持不变。
 */
object AppLog {

    /** 环形缓冲容量（与调查文档一致：1000 条足够看「现在正在发生什么」）。 */
    const val CAPACITY = 1000

    /** 单条消息上限；超长内容（例如整段 HTML/JSON）截断后入环。 */
    const val MAX_MESSAGE_LENGTH = 2000

    /** 同一条消息在该窗口内重复出现时合并计数，避免高频日志把环冲掉。 */
    private const val COALESCE_WINDOW_MS = 2000L

    private const val DEFAULT_TARGET = "app"

    /**
     * 常见日志前缀里的严重级别标记，例如 `SLF4J(W): …` / `Foo(E): …`。
     * 这类框架日志不带 error/warn 字样，只靠关键词会全部落成 INFO。
     */
    private val LEVEL_MARKER = Regex("^[A-Za-z0-9_.\\-]+\\((W|E|I|D)\\):")

    /** 标准流里出现这些词时把级别抬高，避免常规 println 全被当成 INFO。 */
    private val ERROR_HINTS = listOf(
        "error", "exception", "failed", "failure", "fatal", "traceback", "panic", "错误", "失败", "异常",
    )
    private val WARN_HINTS = listOf("warn", "deprecat", "unsupported", "警告", "不支持")

    enum class Level {
        DEBUG,
        INFO,
        WARN,
        ERROR;

        companion object {
            /** 宽松解析：接受 `warn` / `WARNING` / `err` 之类的写法，非法值返回 null。 */
            fun parse(value: String?): Level? {
                val text = value?.trim()?.uppercase().orEmpty()
                if (text.isEmpty()) return null
                return when (text) {
                    "DEBUG", "TRACE", "VERBOSE" -> DEBUG
                    "INFO", "NOTICE" -> INFO
                    "WARN", "WARNING" -> WARN
                    "ERROR", "ERR", "SEVERE", "FATAL" -> ERROR
                    else -> null
                }
            }
        }
    }

    /** 环里的一条日志。字段名即协议字段名（见 protocol-config.md）。 */
    @Serializable
    data class Entry(
        /** 首次出现的时间戳（毫秒）；合并计数时不会刷新，保证顺序稳定。 */
        val timestampMs: Long,
        val level: String,
        val target: String,
        val message: String,
        /** 被合并的重复次数，1 表示没有重复。 */
        val repeat: Int = 1,
    )

    private val lock = Any()

    /** 头尾分离：队头最旧、队尾最新。 */
    private val ring = ArrayDeque<Entry>(CAPACITY + 1)

    private var recordedTotal = 0L

    /** 桥接时用的「原始」流：本对象初始化早于桥接安装，保存下来避免写回 wrapper 造成递归。 */
    private val originalOut: PrintStream = System.out
    private val originalErr: PrintStream = System.err

    @Volatile
    private var bridgeInstalled = false

    private val bridgeLock = Any()

    // ------------------------------------------------------------------ 记录

    fun debug(target: String, message: String) = log(Level.DEBUG, target, message)

    fun info(target: String, message: String) = log(Level.INFO, target, message)

    fun warn(target: String, message: String) = log(Level.WARN, target, message)

    fun error(target: String, message: String) = log(Level.ERROR, target, message)

    /**
     * 统一入口：脱敏 → 截断 → 入环，并继续写一份到原始 stderr（保持开发时 backend.log 的可读性）。
     *
     * 注意 stderr 的这份输出走的是**桥接安装前**的流对象，不会被 tee 再次收进环，所以不会重复。
     */
    fun log(level: Level, target: String, message: String) {
        val safe = sanitize(message)
        val name = target.trim().ifEmpty { DEFAULT_TARGET }
        runCatching { originalErr.println("[${level.name.lowercase()}] [$name] $safe") }
        record(level, name, safe)
    }

    /** 只入环不回显：仅供标准流桥接使用（内容已经被原始流打印过了）。 */
    private fun record(level: Level, target: String, message: String) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val newest = ring.peekLast()
            if (newest != null &&
                newest.level == level.name &&
                newest.target == target &&
                newest.message == message &&
                now - newest.timestampMs <= COALESCE_WINDOW_MS
            ) {
                ring.removeLast()
                ring.addLast(newest.copy(repeat = newest.repeat + 1))
                return
            }
            if (ring.size >= CAPACITY) ring.removeFirst()
            ring.addLast(Entry(now, level.name, target, message))
            recordedTotal += 1
        }
    }

    /**
     * 最近日志，**新 → 旧**。
     *
     * @param limit 最多返回条数，夹在 1..[CAPACITY]。
     * @param minLevel 只返回该级别及以上的条目（`ERROR` 只剩 ERROR）。
     */
    fun recent(limit: Int = CAPACITY, minLevel: Level? = null): List<Entry> {
        val max = limit.coerceIn(1, CAPACITY)
        val threshold = minLevel?.ordinal ?: -1
        synchronized(lock) {
            if (ring.isEmpty()) return emptyList()
            val result = ArrayList<Entry>(minOf(max, ring.size))
            val iterator = ring.descendingIterator()
            while (iterator.hasNext() && result.size < max) {
                val entry = iterator.next()
                if (levelOrdinal(entry.level) < threshold) continue
                result += entry
            }
            return result
        }
    }

    /** 累计入环条数（含被环淘汰的），用于前端显示「共记录了多少」。 */
    fun recordedCount(): Long = synchronized(lock) { recordedTotal }

    fun size(): Int = synchronized(lock) { ring.size }

    fun clear() {
        synchronized(lock) {
            ring.clear()
            recordedTotal = 0
        }
    }

    private fun levelOrdinal(name: String): Int = Level.parse(name)?.ordinal ?: Level.INFO.ordinal

    // ------------------------------------------------------------------ 脱敏

    /**
     * 敏感值替换为 `[REDACTED]`。
     *
     * 顺序有讲究：先处理 JSON 里带引号的键（`"access_token":"..."`），再处理整行头部
     * （`Cookie: a=b; c=d` 必须整行吃掉，否则分号后面的值会漏），最后才是零散的 `key=value`。
     *
     * 这里只看「值」不看上下文，所以误伤的上限是常量 `[REDACTED]`，不会影响日志的可读性主线。
     */
    fun sanitize(message: String): String {
        if (message.isEmpty()) return message
        var text = message
        if (SECRET_PATTERN.containsMatchIn(text)) {
            text = JSON_SECRET.replace(text) { match ->
                "\"${match.groupValues[1]}\": \"[REDACTED]\""
            }
            text = HEADER_SECRET.replace(text) { match ->
                "${match.groupValues[1]}: [REDACTED]"
            }
            text = BEARER_TOKEN.replace(text) { match ->
                "${match.groupValues[1]} [REDACTED]"
            }
            text = URL_USERINFO.replace(text) { match ->
                "${match.groupValues[1]}[REDACTED]@"
            }
            text = PARAM_SECRET.replace(text) { match ->
                // 已经被前面的规则替换成 [REDACTED] 的值不要再套一层，否则会出现 [REDACTED]]
                if (match.groupValues[3].startsWith("[REDACTED")) {
                    match.value
                } else {
                    "${match.groupValues[1]}${match.groupValues[2]}[REDACTED]"
                }
            }
        }
        return truncate(text)
    }

    private fun truncate(text: String): String =
        if (text.length <= MAX_MESSAGE_LENGTH) text else text.take(MAX_MESSAGE_LENGTH - 1) + "…"

    /** 只做一次快速预筛，绝大多数日志行不含这些词，避免每行都跑 5 个正则。 */
    private val SECRET_PATTERN = Regex(
        "(?i)cookie|authorization|bearer|token|password|passwd|secret|sessdata|music_u|bili_jct|" +
            "buvid3|dedeuserid|session|csrf|xsrf|api_key|apikey|://[^/\\s:@]+:",
    )

    private val JSON_SECRET = Regex(
        "\"(${SECRET_KEYS})\"\\s*:\\s*\"(?:[^\"\\\\]|\\\\.)*\"",
        RegexOption.IGNORE_CASE,
    )

    private val HEADER_SECRET = Regex(
        "\\b(cookie|set-cookie|authorization|proxy-authorization|x-auth-token)\\s*:\\s*[^\\r\\n]*",
        RegexOption.IGNORE_CASE,
    )

    private val BEARER_TOKEN = Regex("\\bBearer\\s+[A-Za-z0-9._~+/=\\-]{6,}", RegexOption.IGNORE_CASE)

    private val URL_USERINFO = Regex("(://[^/\\s:@]+:)[^/\\s@]+@")

    private val PARAM_SECRET = Regex(
        "\\b(${SECRET_KEYS})([\"']?\\s*[=:]\\s*)([^\\s,;&\\]}\"']+)",
        RegexOption.IGNORE_CASE,
    )

    private const val SECRET_KEYS =
        "cookie|set-cookie|authorization|proxy-authorization|x-auth-token|music_u|sessdata|bili_jct|" +
            "buvid3|dedeuserid|access_token|refresh_token|id_token|token|api_key|apikey|secret|" +
            "password|passwd|pwd|sessionid|session_key|csrf|xsrf|sign"

    // ------------------------------------------------------------------ 标准流桥接

    /**
     * 把 System.out / System.err 各 tee 一份进缓冲，让既有的 `println` 也能在日志查看器里看到。
     *
     * 设计取舍：逐个改道几十处 `println` 会碰很多不属于本功能的文件；tee 标准流是
     * 「一处生效、不动调用点」的等价方案，且原输出（dev.sh 重定向的 backend.log）完全不变。
     * 设置环境变量 `NERI_APP_LOG_BRIDGE=0` 可关闭。
     *
     * @return 本次是否真的安装了桥接（重复调用返回 false）。
     */
    fun installStdoutBridge(): Boolean {
        if (System.getenv("NERI_APP_LOG_BRIDGE") == "0") return false
        synchronized(bridgeLock) {
            if (bridgeInstalled) return false
            bridgeInstalled = true
        }
        runCatching {
            System.setOut(PrintStream(LineTee(originalOut, "stdout"), true, StandardCharsets.UTF_8))
            System.setErr(PrintStream(LineTee(originalErr, "stderr"), true, StandardCharsets.UTF_8))
        }.onFailure {
            bridgeInstalled = false
            originalErr.println("[app] 标准流日志桥接安装失败: ${it.message}")
            return false
        }
        return true
    }

    /**
     * 按行切分标准流的 tee。
     *
     * 单条输出可能被 `print` 拆成多次 `write`，所以先按 `\n` 累积再整行入环；
     * 累积超过 [MAX_MESSAGE_LENGTH] 时提前截断入环，避免一条巨型垃圾输出把内存吃掉。
     */
    private class LineTee(
        private val delegate: PrintStream,
        private val streamName: String,
    ) : OutputStream() {

        private val buffer = ByteArrayOutputStream(256)

        override fun write(value: Int) {
            runCatching { delegate.write(value) }
            val byte = value and 0xFF
            synchronized(this) {
                when {
                    byte == '\n'.code -> flushLine()
                    buffer.size() >= MAX_MESSAGE_LENGTH -> flushLine()
                    else -> buffer.write(byte)
                }
            }
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            runCatching { delegate.write(bytes, offset, length) }
            synchronized(this) {
                var index = offset
                val end = offset + length
                while (index < end) {
                    val byte = bytes[index].toInt() and 0xFF
                    if (byte == '\n'.code || buffer.size() >= MAX_MESSAGE_LENGTH) flushLine() else buffer.write(byte)
                    index += 1
                }
            }
        }

        override fun flush() {
            runCatching { delegate.flush() }
            synchronized(this) {
                if (buffer.size() > 0) flushLine()
            }
        }

        override fun close() {
            flush()
        }

        private fun flushLine() {
            if (buffer.size() == 0) return
            val raw = String(buffer.toByteArray(), StandardCharsets.UTF_8).trimEnd('\r', '\n', ' ')
            buffer.reset()
            if (raw.isBlank()) return
            val line = raw.take(MAX_MESSAGE_LENGTH * 2)
            val target = targetOf(line) ?: streamName
            val body = if (target == streamName) line else line.substring(target.length + 2).trim()
            val level = classify(line.take(200))
            AppLog.record(level, target, AppLog.sanitize(body))
        }

        /** 既有日志大量写成 `[core] ...` / `[download] ...`，前缀正好可以当 target。 */
        private fun targetOf(line: String): String? {
            if (!line.startsWith("[")) return null
            val end = line.indexOf(']')
            if (end <= 1 || end > 24) return null
            val tag = line.substring(1, end).trim()
            if (tag.any { it.isWhitespace() }) return null
            return tag
        }

        private fun classify(head: String): Level {
            val lower = head.lowercase()
            LEVEL_MARKER.find(head.trim())?.let { match ->
                return when (match.groupValues[1]) {
                    "E" -> Level.ERROR
                    "W" -> Level.WARN
                    "D" -> Level.DEBUG
                    else -> Level.INFO
                }
            }
            return when {
                ERROR_HINTS.any { lower.contains(it) } -> Level.ERROR
                WARN_HINTS.any { lower.contains(it) } -> Level.WARN
                streamName == "stderr" -> Level.INFO
                else -> Level.INFO
            }
        }
    }
}
