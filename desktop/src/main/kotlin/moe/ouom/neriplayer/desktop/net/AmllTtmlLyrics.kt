package moe.ouom.neriplayer.desktop.net

import moe.ouom.neriplayer.desktop.core.LyricLine
import moe.ouom.neriplayer.desktop.core.LyricWord

/**
 * AMLL TTML 逐字歌词回退。
 *
 * 与 Android 对齐：网易云 eapi 没有 yrc 时（例如《晴天》186016），Android 会从 AMLL DB
 * 取同曲的 TTML 逐字时间；桌面此前没有这条回退链，只会退化成逐行 LRC。
 * 这里把 TTML 解析成与 yrc 相同的 [LyricLine]/[LyricWord] 结构，交给既有构建函数使用。
 */

/** AMLL 搜索结果里与歌词相关的字段；其余字段忽略。 */
private data class AmllCandidate(val file: String)

/** 一条 TTML 行的时间轴与文本片段。 */
private data class TtmlSpan(val text: String, val startMs: Long, val endMs: Long)

object AmllTtmlClient {

    private const val BASE = "https://amlldb.bikonoo.com"

    // TTML 逐字稿不会频繁变化，缓存避免每次切歌/刷新都重新搜索+下载整首 TTML
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String?>()

    /**
     * 按网易云歌曲 id 取逐字歌词；找不到、网络失败或没有逐字时间时返回 null。
     *
     * 用 `type=id` 直接在 AMLL 库里按 ncmId 检索，比按标题/歌手搜索更不易串歌；
     * 候选的 ncmIds 必须包含请求的 id，避免库里同名不同曲的歌词被误用。
     */
    fun fetchWordTimed(http: HttpService, ncmId: String): String? {
        if (ncmId.isBlank()) return null
        cache[ncmId]?.let { return it }
        val result = runCatching { fetchWordTimedUncached(http, ncmId) }.getOrNull()
        cache[ncmId] = result
        return result
    }

    private fun fetchWordTimedUncached(http: HttpService, ncmId: String): String? {
        val searchBody = """{"query":"$ncmId","type":"id"}"""
        val searchText = http.execute(
            method = "POST",
            url = "$BASE/api/search-lyrics",
            body = searchBody,
            headers = mapOf("Content-Type" to "application/json"),
            timeoutSeconds = 12,
        )?.text() ?: return null
        val candidates = parseCandidates(NeriJsonParser.parse(searchText), ncmId)
        for (candidate in candidates.take(5)) {
            val file = candidate.file
            if (!file.endsWith(".ttml") || file.contains('/') || file.contains('\\')) continue
            val raw = http.get("$BASE/raw-lyrics/$file", timeoutSeconds = 15) ?: continue
            if (TtmlParser.parse(raw).any { it.words.isNotEmpty() }) return raw
        }
        return null
    }

    private fun parseCandidates(element: kotlinx.serialization.json.JsonElement?, ncmId: String): List<AmllCandidate> {
        val array = element as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val file = obj.str("file")?.takeIf { it.endsWith(".ttml") } ?: return@mapNotNull null
            val ids = (obj["ncmIds"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                .orEmpty()
            if (ids.isNotEmpty() && ncmId !in ids) return@mapNotNull null
            AmllCandidate(file)
        }
    }
}

/** TTML → yrc 形状的逐字行。只处理主唱（无 agent 或 v1），跳过翻译/音译 agent。 */
object TtmlParser {

    private val pRegex = Regex("""<p\b([^>]*)>([\s\S]*?)</p>""", RegexOption.IGNORE_CASE)
    private val spanRegex = Regex(
        """<span\b([^>]*)>([\s\S]*?)</span>""",
        RegexOption.IGNORE_CASE,
    )
    private val attrRegex = Regex("""([\w:-]+)\s*=\s*"([^"]*)""")
    private val tagRegex = Regex("""<[^>]+>""")

    /** 解析 TTML 时间：支持 `HH:MM:SS.mmm`、`MM:SS.mmm` 与 `123.456s`。 */
    fun parseTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val text = value.trim()
        return runCatching {
            when {
                text.contains(':') -> {
                    val parts = text.split(':')
                    var total = 0L
                    parts.forEach { part ->
                        total = total * 60_000L + parseClockPart(part)
                    }
                    total
                }
                text.endsWith("s", ignoreCase = true) ->
                    (text.removeSuffix("s").removeSuffix("S").toDouble() * 1000L).toLong()
                else -> text.toDoubleOrNull()?.let { (it * 1000L).toLong() }
            }
        }.getOrNull()
    }

    private fun parseClockPart(part: String): Long {
        val (whole, fraction) = if ('.' in part) {
            val head = part.substringBefore('.')
            val tail = part.substringAfter('.')
            head to (tail + "000").take(3).toLongOrNull()
        } else {
            part to 0L
        }
        return whole.toLongOrNull()?.let { it * 1000L + (fraction ?: 0L) } ?: 0L
    }

    /**
     * 把 TTML 转成 yrc 文本 `[行start,行dur](词start,词dur,0)词…`，
     * 让既有的 [moe.ouom.neriplayer.desktop.core.buildYrcLyrics] 直接消费。
     */
    fun toYrcText(raw: String): String? {
        val lines = parse(raw)
        if (lines.isEmpty() || lines.none { it.words.isNotEmpty() }) return null
        return buildString {
            lines.forEach { line ->
                val duration = (line.endMs - line.timeMs).coerceAtLeast(1L)
                append('[').append(line.timeMs).append(',').append(duration).append(']')
                line.words.forEach { word ->
                    append('(').append(word.startMs).append(',')
                        .append(word.durationMs).append(",0)")
                        .append(word.text)
                }
                append('\n')
            }
        }
    }

    /** 解析成 yrc 形状的行：每行带 words，时间单位毫秒。 */
    fun parse(raw: String): List<LyricLine> {
        if (raw.isBlank()) return emptyList()
        val lines = ArrayList<LyricLine>()
        pRegex.findAll(raw).forEach { pMatch ->
            val attrs = attrs(pMatch.groupValues[1])
            val agent = attrs["ttm:agent"]
            if (!agent.isNullOrBlank() && agent != "v1") return@forEach
            val start = parseTime(attrs["begin"]) ?: return@forEach
            val end = parseTime(attrs["end"]) ?: (start + 3_000L)
            val body = pMatch.groupValues[2]
            val matches = spanRegex.findAll(body).toList()
            val spans = matches.mapIndexedNotNull { index, spanMatch ->
                val spanAttrs = attrs(spanMatch.groupValues[1])
                val spanStart = parseTime(spanAttrs["begin"]) ?: return@mapIndexedNotNull null
                val spanEnd = parseTime(spanAttrs["end"]) ?: (spanStart + 1L)
                // span 之间的空格不在任何标签内；并到后一个词，避免英文歌词丢空格
                val gap = if (index == 0) "" else {
                    val raw = decodeEntities(body.substring(matches[index - 1].range.last + 1, spanMatch.range.first))
                    if (raw.isEmpty()) "" else " "
                }
                val text = gap + plainText(spanMatch.groupValues[2])
                if (text.isBlank()) null else TtmlSpan(text, spanStart, spanEnd)
            }
            if (spans.isEmpty()) return@forEach
            val words = spans.map { span ->
                LyricWord(
                    startMs = span.startMs,
                    durationMs = (span.endMs - span.startMs).coerceAtLeast(1L),
                    text = span.text,
                )
            }
            lines += LyricLine(
                timeMs = start,
                text = spans.joinToString("") { it.text },
                endMs = end.coerceAtLeast(start),
                words = words,
            )
        }
        return lines.sortedBy { it.timeMs }
    }

    private fun attrs(raw: String): Map<String, String> =
        attrRegex.findAll(raw).associate { it.groupValues[1] to it.groupValues[2] }

    private fun plainText(raw: String): String =
        decodeEntities(tagRegex.replace(raw, "")).trim()

    private fun decodeEntities(raw: String): String =
        raw.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
}
