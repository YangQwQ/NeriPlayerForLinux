package moe.ouom.neriplayer.desktop.core

import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * 存储占用统计的数据模型（`storage.usage` / `storage.clearCache`）。
 *
 * 分节列出真实目录占用，前端按 section / item 的 id 选择文案，协议见
 * desktop/docs/protocol-storage.md。**「可清理」只描述本命令能处理的部分**，
 * 其余条目（下载内容、本地曲库封面、配置）一律只读，避免把用户数据当缓存删掉。
 */
@Serializable
data class StorageUsageItem(
    /** 条目 id（协议常量，前端据此取文案）。 */
    val id: String,
    /** 该条目对应的绝对路径；目录可能不存在。 */
    val path: String = "",
    val sizeBytes: Long = 0L,
    val fileCount: Int = 0,
    /** 是否可通过 `storage.clearCache` 清理；false 表示只读展示。 */
    val cleanable: Boolean = false,
    /** cleanable 为 true 时对应的清理目标名（`storage.clearCache` 的 targets 取值）。 */
    val clearTarget: String? = null,
)

@Serializable
data class StorageUsageSection(
    /** 分组 id：`cleanable_cache` / `cache_other` / `downloads` / `app_data`。 */
    val id: String,
    val items: List<StorageUsageItem> = emptyList(),
)

/**
 * 被排除在磁盘占用之外的路径。
 *
 * 目前只有 `/dev/shm` 上的音频预取缓存：它是内存文件系统，重启即失，
 * 计入磁盘占用会虚高，同时也绝不能作为「可清理缓存」展示。
 */
@Serializable
data class StorageExcludedPath(
    val path: String,
    /** `memory` = 内存文件系统，不算磁盘占用。 */
    val reason: String,
    val sizeBytes: Long = 0L,
    val fileCount: Int = 0,
)

@Serializable
data class StorageUsageSummary(
    val sections: List<StorageUsageSection> = emptyList(),
    /** 各分组字节之和；只含磁盘上的、互不重叠的路径。 */
    val totalSizeBytes: Long = 0L,
    val totalFileCount: Int = 0,
    /** 可清理（仅 coverDir）的部分，用于对话框主按钮上的副标题。 */
    val cleanableSizeBytes: Long = 0L,
    val cleanableFileCount: Int = 0,
    /** 下载索引（downloads.json）里记录的字节之和；与磁盘扫描口径可能略有差异。 */
    val downloadIndexBytes: Long = 0L,
    val excluded: List<StorageExcludedPath> = emptyList(),
    val cacheDir: String = "",
    val downloadDir: String = "",
    val generatedAt: Long = 0L,
)

@Serializable
data class StorageClearResult(
    /** 实际执行的清理目标（目前只有 `coverCache`）。 */
    val targets: List<String> = emptyList(),
    /** 清理前后目录体积差，而不是删除前累加的文件大小（部分文件写失败时更准确）。 */
    val releasedBytes: Long = 0L,
    val deleted: Int = 0,
    val failed: Int = 0,
    /** 删除失败的条目名（最多保留前若干个，便于排障）。 */
    val failedItems: List<String> = emptyList(),
    val remainingBytes: Long = 0L,
    val remainingFiles: Int = 0,
)

/** 目录扫描结果：字节数 + 普通文件数。 */
internal data class FileStats(val sizeBytes: Long = 0L, val fileCount: Int = 0) {

    operator fun plus(other: FileStats): FileStats = FileStats(
        sizeBytes = saturatingAdd(sizeBytes, other.sizeBytes),
        fileCount = clampCount(fileCount.toLong() + other.fileCount.toLong()),
    )

    companion object {
        val ZERO = FileStats()
    }
}

private fun saturatingAdd(a: Long, b: Long): Long =
    if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b

private fun clampCount(value: Long): Int =
    if (value > Int.MAX_VALUE) Int.MAX_VALUE else value.toInt()

/**
 * 递归统计目录占用。
 *
 * 有意不跟随符号链接（避免软链环导致无限递归 / 重复计数），
 * 单个条目读不到属性时跳过并继续，不让一个坏文件毁掉整次统计。
 */
internal fun scanTree(root: File): FileStats {
    if (!root.exists()) return FileStats.ZERO
    if (root.isFile) {
        val size = runCatching { root.length() }.getOrDefault(0L)
        return FileStats(size.coerceAtLeast(0L), 1)
    }
    var sizeBytes = 0L
    var fileCount = 0L
    runCatching {
        Files.walkFileTree(
            root.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) {
                        sizeBytes = saturatingAdd(sizeBytes, attrs.size().coerceAtLeast(0L))
                        fileCount += 1L
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                    FileVisitResult.CONTINUE
            },
        )
    }
    return FileStats(sizeBytes, clampCount(fileCount))
}

/** 只统计指定文件（不存在/不可读的忽略）。 */
internal fun scanFiles(files: List<File>): FileStats {
    var total = FileStats.ZERO
    for (file in files) {
        if (!file.isFile) continue
        total += FileStats(runCatching { file.length() }.getOrDefault(0L).coerceAtLeast(0L), 1)
    }
    return total
}

/** canonical 路径（存在则解析软链，失败时退回绝对路径），用于目录去重。 */
internal fun canonicalPath(file: File): String =
    runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
