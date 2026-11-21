package moe.ouom.neriplayer.desktop.core

import java.io.File
import java.security.MessageDigest

/**
 * 在线音频的磁盘 LRU 缓存。
 *
 * 与 Player 里 /dev/shm 的单文件「播放加速缓存」相互独立：这里按歌曲 key 保留整首音频，
 * 用于离线复听与减少重复联网；播放加速缓存只服务当前播放的 seek 瞬时切换。
 * 缓存上限来自 [SettingsRepository] 的 cacheLimitMb（-1 不限制，0 禁用并清空）。
 */
class MusicCache(private val settings: SettingsRepository) {
    private val lock = Any()
    private val dir: File get() = AppDirs.musicCacheDir

    /**
     * 把歌曲 key 转成安全文件名：清洗路径分隔符等危险字符，过长或过短时改用 SHA-256 摘要，
     * 避免路径穿越与文件系统文件名长度限制。同一 key 的不同扩展名共享同一 basename。
     */
    private fun safeKey(songKey: String): String {
        val cleaned = songKey.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.')
        return if (cleaned.length in 3..80) cleaned else sha256(songKey)
    }

    private fun safeExtension(extension: String): String =
        extension.trim().removePrefix(".")
            .replace(Regex("[^A-Za-z0-9]"), "")
            .take(8)
            .ifBlank { "mp3" }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun filesFor(songKey: String): List<File> {
        val prefix = safeKey(songKey) + "."
        return dir.listFiles()?.filter { isManaged(it) && it.name.startsWith(prefix) } ?: emptyList()
    }

    /** 临时下载文件（下载暂存 <uuid>.part 与缓存写入 .part-<uuid>）不参与缓存命中与 LRU。 */
    private fun isManaged(file: File): Boolean =
        file.isFile && !file.name.contains(".part")

    /** 命中返回非空缓存文件（同 key 多扩展名时取最近写入的），否则返回 null。 */
    fun fileFor(songKey: String): File? = synchronized(lock) {
        filesFor(songKey).filter { it.length() > 0L }.maxByOrNull { it.lastModified() }
    }

    /** 写入目标路径；调用方可直接把音频流式下载到这里。 */
    fun targetFor(songKey: String, extension: String): File = File(dir, "${safeKey(songKey)}.${safeExtension(extension)}")

    /** 命中后刷新 lastModified，重置 LRU 顺序。 */
    fun touch(songKey: String) = synchronized(lock) {
        val now = System.currentTimeMillis()
        filesFor(songKey).forEach { runCatching { it.setLastModified(now) } }
        Unit
    }

    /**
     * 应用缓存上限：-1 不限制；0 清空整个目录；其余按 lastModified 升序淘汰最旧文件，
     * 直到目录总大小不超过 limitMb MiB。单条删除失败跳过，不影响其余文件。
     */
    fun applyLimit(limitMb: Int = settings.current.cacheLimitMb) = synchronized(lock) {
        val files = dir.listFiles()?.filter(::isManaged) ?: return@synchronized
        if (limitMb == 0) {
            files.forEach { runCatching { it.delete() } }
            return@synchronized
        }
        if (limitMb < 0) return@synchronized
        val maxBytes = limitMb.toLong() * 1024L * 1024L
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return@synchronized
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxBytes) return@forEach
            if (runCatching { file.delete() }.getOrDefault(false)) total -= file.length()
        }
        Unit
    }

    /** 清空所有缓存文件（保留目录本身）。 */
    fun clear() = synchronized(lock) {
        dir.listFiles()?.filter(::isManaged)?.forEach { runCatching { it.delete() } }
        Unit
    }
}
