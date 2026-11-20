package moe.ouom.neriplayer.desktop.backend.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import moe.ouom.neriplayer.desktop.backend.CoreServer
import moe.ouom.neriplayer.desktop.backend.ProtocolException
import moe.ouom.neriplayer.desktop.backend.arrayOrNull
import moe.ouom.neriplayer.desktop.backend.badRequest
import moe.ouom.neriplayer.desktop.backend.encodeJson
import moe.ouom.neriplayer.desktop.backend.log
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.AppDirs
import moe.ouom.neriplayer.desktop.core.FileStats
import moe.ouom.neriplayer.desktop.core.StorageClearResult
import moe.ouom.neriplayer.desktop.core.StorageExcludedPath
import moe.ouom.neriplayer.desktop.core.StorageUsageItem
import moe.ouom.neriplayer.desktop.core.StorageUsageSection
import moe.ouom.neriplayer.desktop.core.StorageUsageSummary
import moe.ouom.neriplayer.desktop.core.canonicalPath
import moe.ouom.neriplayer.desktop.core.scanFiles
import moe.ouom.neriplayer.desktop.core.scanTree
import java.io.File

/**
 * 存储占用统计与安全清理。
 *
 * 详细方案见 desktop/docs/investigations/storage-management.md；
 * 协议见 desktop/docs/protocol-storage.md。
 *
 * 安全边界（改动前务必读一遍）：
 * - 可清理的只有 [AppDirs.coverDir]（在线封面缓存，可重新下载）；
 * - [AppDirs.artworkDir]（本地曲库抽取的内嵌封面）删掉要重新扫描媒体库才能恢复，
 *   因此**只统计、不提供清理入口**；
 * - 下载目录、`dataDir`（曲库/歌单/历史/统计索引）、`configDir`（设置与登录态）只读；
 * - `/dev/shm` 上的音频预取缓存是内存文件系统，不算磁盘占用，也不清理。
 */
object StorageApi {

    /** `storage.clearCache` 唯一支持的清理目标。 */
    private const val TARGET_COVER_CACHE = "coverCache"

    /** 白名单：目前只有封面缓存可清理。新目标必须先确认 100% 可再生。 */
    private val CLEAR_WHITELIST = setOf(TARGET_COVER_CACHE)

    /** 内存文件系统前缀：其中的占用不计入磁盘统计。 */
    private const val SHM_ROOT = "/dev/shm"

    private const val FAILED_ITEM_LIMIT = 20

    private val LYRIC_EXTENSIONS = setOf("lrc", "tlrc")

    fun register(server: CoreServer, container: AppContainer) {
        // storage.usage：只读统计各缓存目录占用（字节/文件数）。
        server.on("storage.usage") {
            withContext(Dispatchers.IO) { encodeJson(collectUsage(container)) }
        }

        // storage.clearCache：白名单清理可再生的缓存，返回释放字节数与失败数。
        server.on("storage.clearCache") { params ->
            val targets = parseTargets(params)
            withContext(Dispatchers.IO) { encodeJson(clearCache(targets)) }
        }
    }

    // ------------------------------------------------------------------ 统计

    private fun collectUsage(container: AppContainer): StorageUsageSummary {
        // 已计入的 canonical 路径：保证 coverDir / artworkDir 不会在 cacheDir 总量里被重复累加
        val accounted = ArrayList<String>(8)

        fun isAccounted(path: String): Boolean = accounted.any { path == it || path.startsWith("$it/") }

        /** 统计并登记一个路径；内存盘与已计入路径返回 0（跳过重复计数）。 */
        fun account(file: File): FileStats {
            val path = canonicalPath(file)
            if (isMemoryPath(path)) return FileStats.ZERO
            if (isAccounted(path)) return FileStats.ZERO
            accounted += path
            return scanTree(file)
        }

        val coverStats = account(AppDirs.coverDir)
        val artworkStats = account(AppDirs.artworkDir)

        // other_cache：cacheDir 下扣除 coverDir / artworkDir 后的剩余，逐个顶层子项累加，
        // 避免「先算整目录再减去子目录」在目录不存在时算出负数。
        var otherStats = FileStats.ZERO
        AppDirs.cacheDir.listFiles()?.sortedBy { it.name }?.forEach { child ->
            val path = canonicalPath(child)
            if (isMemoryPath(path) || isAccounted(path)) return@forEach
            accounted += path
            otherStats += scanTree(child)
        }

        val downloadDir = runCatching { container.downloads.downloadDirectory() }
            .getOrElse { File(System.getProperty("user.home"), "Music/NeriPlayer") }
        val downloadIndexBytes = runCatching { container.downloadCatalog.totalSizeBytes() }.getOrDefault(0L)
        val downloadCount = runCatching { container.downloadCatalog.items.value.size }.getOrDefault(0)
        // 下载目录里的歌词是用户数据，只统计不清理；只扫顶层（下载直写最终文件，无子目录）
        val lyricStats = if (isMemoryPath(canonicalPath(downloadDir))) {
            FileStats.ZERO
        } else {
            scanFiles(downloadDir.listFiles()?.filter { it.isFile && it.extension.lowercase() in LYRIC_EXTENSIONS } ?: emptyList())
        }
        val libraryStats = accountFiles(
            listOf(
                AppDirs.libraryFile,
                AppDirs.playlistFile,
                AppDirs.historyFile,
                AppDirs.statsFile,
                AppDirs.searchHistoryFile,
                AppDirs.downloadCatalogFile,
            ),
            accounted,
        )
        val logStats = account(AppDirs.logDir)
        val configStats = account(AppDirs.configDir)

        val cleanable = StorageUsageSection(
            id = "cleanable_cache",
            items = listOf(
                StorageUsageItem(
                    id = "cover_cache",
                    path = AppDirs.coverDir.absolutePath,
                    sizeBytes = coverStats.sizeBytes,
                    fileCount = coverStats.fileCount,
                    cleanable = true,
                    clearTarget = TARGET_COVER_CACHE,
                ),
            ),
        )

        val otherCache = StorageUsageSection(
            id = "cache_other",
            items = listOf(
                StorageUsageItem(
                    id = "other_cache",
                    path = AppDirs.cacheDir.absolutePath,
                    sizeBytes = otherStats.sizeBytes,
                    fileCount = otherStats.fileCount,
                    cleanable = false,
                ),
            ),
        )

        val downloads = StorageUsageSection(
            id = "downloads",
            items = listOf(
                StorageUsageItem(
                    id = "downloaded_music",
                    path = downloadDir.absolutePath,
                    // 索引口径：下载完成时记录的文件长度；用户手动删除文件后索引可能偏大
                    sizeBytes = downloadIndexBytes,
                    fileCount = downloadCount,
                    cleanable = false,
                ),
                StorageUsageItem(
                    id = "downloaded_lyrics",
                    path = downloadDir.absolutePath,
                    sizeBytes = lyricStats.sizeBytes,
                    fileCount = lyricStats.fileCount,
                    cleanable = false,
                ),
            ),
        )

        val appData = StorageUsageSection(
            id = "app_data",
            items = listOf(
                StorageUsageItem(
                    id = "local_covers",
                    path = AppDirs.artworkDir.absolutePath,
                    sizeBytes = artworkStats.sizeBytes,
                    fileCount = artworkStats.fileCount,
                    cleanable = false,
                ),
                StorageUsageItem(
                    id = "library_data",
                    path = AppDirs.dataDir.absolutePath,
                    sizeBytes = libraryStats.sizeBytes,
                    fileCount = libraryStats.fileCount,
                    cleanable = false,
                ),
                StorageUsageItem(
                    id = "logs",
                    path = AppDirs.logDir.absolutePath,
                    sizeBytes = logStats.sizeBytes,
                    fileCount = logStats.fileCount,
                    cleanable = false,
                ),
                StorageUsageItem(
                    id = "config",
                    path = AppDirs.configDir.absolutePath,
                    sizeBytes = configStats.sizeBytes,
                    fileCount = configStats.fileCount,
                    cleanable = false,
                ),
            ),
        )

        val sections = listOf(cleanable, otherCache, downloads, appData)
        val totalSize = sections.sumOf { section -> section.items.sumOf { it.sizeBytes } }
        val totalFiles = sections.sumOf { section -> section.items.sumOf { it.fileCount } }

        return StorageUsageSummary(
            sections = sections,
            totalSizeBytes = totalSize,
            totalFileCount = totalFiles,
            cleanableSizeBytes = coverStats.sizeBytes,
            cleanableFileCount = coverStats.fileCount,
            downloadIndexBytes = downloadIndexBytes,
            excluded = memoryExclusions(),
            cacheDir = AppDirs.cacheDir.absolutePath,
            downloadDir = downloadDir.absolutePath,
            generatedAt = System.currentTimeMillis(),
        )
    }

    /** 统计单个文件集合（不参与 canonical 去重，它们都是具名文件而不是目录）。 */
    private fun accountFiles(files: List<File>, accounted: MutableList<String>): FileStats {
        val targets = files.filter { file ->
            val path = canonicalPath(file)
            if (isMemoryPath(path) || accounted.contains(path)) return@filter false
            accounted += path
            true
        }
        return scanFiles(targets)
    }

    /** `/dev/shm` 上的音频预取缓存：内存文件系统，不计入磁盘占用。 */
    private fun memoryExclusions(): List<StorageExcludedPath> {
        val shmAudio = File(SHM_ROOT, "NeriPlayer/audio")
        if (!shmAudio.exists()) return emptyList()
        val stats = scanTree(shmAudio)
        return listOf(
            StorageExcludedPath(
                path = shmAudio.absolutePath,
                reason = "memory",
                sizeBytes = stats.sizeBytes,
                fileCount = stats.fileCount,
            ),
        )
    }

    private fun isMemoryPath(path: String): Boolean =
        path == SHM_ROOT || path.startsWith("$SHM_ROOT/")

    // ------------------------------------------------------------------ 清理

    private fun parseTargets(params: JsonObject): List<String> {
        val raw = params.arrayOrNull("targets")
        if (raw == null) {
            // 不传时默认清理唯一的白名单目标，保持「一个按钮即可用」的简单语义
            return listOf(TARGET_COVER_CACHE)
        }
        val requested = raw.mapNotNull { (it as? JsonPrimitive)?.content }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (requested.isEmpty()) badRequest("targets 不能为空")
        requested.firstOrNull { it !in CLEAR_WHITELIST }?.let { unknown ->
            // 明确拒绝而不是静默忽略：避免调用方以为下载目录/本地封面也能清
            badRequest("不支持清理目标: $unknown（仅支持 ${CLEAR_WHITELIST.joinToString()}）")
        }
        return requested.distinct()
    }

    private fun clearCache(targets: List<String>): StorageClearResult {
        if (targets.isEmpty()) badRequest("targets 不能为空")
        if (TARGET_COVER_CACHE !in targets) return StorageClearResult(targets = targets)

        val coverDir = AppDirs.coverDir
        ensureClearable(coverDir)

        val before = scanTree(coverDir)
        var deleted = 0
        var failed = 0
        val failedItems = ArrayList<String>(4)

        // 只删目录内容，绝不删目录本身；逐条删除，单条失败不影响其余条目
        coverDir.listFiles()?.forEach { entry ->
            val ok = runCatching { deleteEntry(entry) }.getOrDefault(false)
            if (ok) {
                deleted += 1
            } else {
                failed += 1
                if (failedItems.size < FAILED_ITEM_LIMIT) failedItems += entry.name
            }
        }

        val after = scanTree(coverDir)
        // 按前后体积差记账：被其它进程占用/部分写入时，累加文件长度会高估释放量
        val released = (before.sizeBytes - after.sizeBytes).coerceAtLeast(0L)
        log("storage cache cleared: target=$TARGET_COVER_CACHE released=$released deleted=$deleted failed=$failed")

        return StorageClearResult(
            targets = targets,
            releasedBytes = released,
            deleted = deleted,
            failed = failed,
            failedItems = failedItems,
            remainingBytes = after.sizeBytes,
            remainingFiles = after.fileCount,
        )
    }

    /**
     * 删除单个条目：目录递归删除、文件直接删除。
     *
     * 符号链接**只删链接本身**，不跟随进目标目录——缓存目录里出现一个指向用户目录的软链时，
     * 跟随删除会把缓存以外的数据一起删掉。
     */
    private fun deleteEntry(entry: File): Boolean {
        val isLink = runCatching { java.nio.file.Files.isSymbolicLink(entry.toPath()) }.getOrDefault(false)
        if (isLink || !entry.isDirectory) return entry.delete()
        return runCatching { entry.deleteRecursively() }.getOrDefault(false)
    }

    /**
     * 清理前的路径护栏。
     *
     * 白名单是编译期常量，理论上不会指错；这里再校验一次实际解析出的路径，
     * 以防 `XDG_CACHE_HOME` 被设置成奇怪的值（例如直接指到 configDir 或数据目录），
     * 让命令拒绝执行而不是删掉用户数据。
     */
    private fun ensureClearable(dir: File) {
        val target = canonicalPath(dir)
        val cache = canonicalPath(AppDirs.cacheDir)
        val artwork = canonicalPath(AppDirs.artworkDir)
        val data = canonicalPath(AppDirs.dataDir)
        val config = canonicalPath(AppDirs.configDir)
        fun within(path: String, root: String) = path.startsWith("$root/")

        val blocked = target == cache || target == artwork || target == data || target == config ||
            !within(target, cache) || within(target, artwork) || within(target, data) || within(target, config)
        if (blocked || isMemoryPath(target)) {
            throw ProtocolException(
                "unsafe_path",
                "封面缓存目录不在预期的缓存根下，已拒绝清理: $target",
            )
        }
    }
}
