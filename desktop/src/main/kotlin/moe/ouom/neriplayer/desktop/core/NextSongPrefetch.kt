package moe.ouom.neriplayer.desktop.core

import java.util.concurrent.ConcurrentHashMap

/** 默认有效期：对齐 Android GenericUrlPrefetchCache 的 90 秒。 */
private const val NEXT_PREFETCH_DEFAULT_TTL_MS = 90_000L

/** 有效期上限：网易云直链短时效，最长也只缓存 10 分钟。 */
private const val NEXT_PREFETCH_MAX_TTL_MS = 10L * 60L * 1000L

private const val NEXT_PREFETCH_MAX_ENTRIES = 8

/**
 * 按当前曲目时长决定下一首预解析结果的有效期。
 *
 * 对齐 Android `resolveGenericUrlPrefetchTtlMs`：覆盖当前曲目剩余时长再加 30 秒余量，
 * 没有时长时用默认值，整体夹在 [1ms, 10min]。太早解析的直链会在切歌前过期，
 * 所以不能用一个固定的长 TTL。
 */
internal fun resolveNextPrefetchTtlMs(currentDurationMs: Long): Long {
    val durationBased = if (currentDurationMs > 0L) currentDurationMs + 30_000L else NEXT_PREFETCH_DEFAULT_TTL_MS
    return durationBased.coerceIn(1L, NEXT_PREFETCH_MAX_TTL_MS)
}

/**
 * 下一首播放地址的预解析缓存，按 [Song.key] 存储、带 TTL。
 *
 * 与 PlayerManager 的长期音源缓存分开：
 * 预解析的地址可能很早之前就算好，网易云直链短时效，必须能在 TTL 后失效重新解析。
 * 消费是一次性的——命中后交给正常播放链路，再次切回这首歌时重新解析。
 */
internal class NextSongPrefetchCache(
    private val maxEntries: Int = NEXT_PREFETCH_MAX_ENTRIES,
) {
    private data class Entry(
        val songKey: String,
        val input: AudioInput,
        val expiresAtMs: Long,
        val createdAtMs: Long,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    fun put(songKey: String, input: AudioInput, ttlMs: Long, nowMs: Long = System.currentTimeMillis()) {
        require(songKey.isNotBlank()) { "prefetch cache key must not be blank" }
        entries.entries.removeIf { (_, entry) -> nowMs >= entry.expiresAtMs }
        if (!entries.containsKey(songKey) && entries.size >= maxEntries) {
            entries.entries.minByOrNull { it.value.createdAtMs }?.let { oldest ->
                entries.remove(oldest.key, oldest.value)
            }
        }
        entries[songKey] = Entry(
            songKey = songKey,
            input = input,
            expiresAtMs = nowMs + ttlMs.coerceIn(1L, NEXT_PREFETCH_MAX_TTL_MS),
            createdAtMs = nowMs,
        )
    }

    /** 是否已有未过期的预解析结果；用于避免对同一首歌重复发起解析。 */
    fun containsFresh(songKey: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val entry = entries[songKey] ?: return false
        if (nowMs < entry.expiresAtMs && entry.songKey == songKey) return true
        entries.remove(songKey, entry)
        return false
    }

    /** 取出并移除未过期的预解析结果；song.key 与条目不符或已过期都视为未命中。 */
    fun consume(songKey: String, nowMs: Long = System.currentTimeMillis()): AudioInput? {
        val entry = entries.remove(songKey) ?: return null
        if (entry.songKey != songKey || nowMs >= entry.expiresAtMs) return null
        return entry.input
    }

    fun invalidate(songKey: String) {
        entries.remove(songKey)
    }

    fun clear() {
        entries.clear()
    }
}
