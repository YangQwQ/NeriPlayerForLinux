package moe.ouom.neriplayer.desktop.core

import java.security.MessageDigest
import java.text.Normalizer

/** 下载文件名模板，规则与 Android 端一致，便于两端互相识别已下载文件。 */
object DownloadNaming {
    const val DEFAULT_TEMPLATE = "%title% - %artist% - %album% - %source%"

    // 历史模板仅用于预览/兼容旧数据，保留常量避免各端写法漂移
    const val PREVIOUS_DEFAULT_TEMPLATE = "%title% - %artist% [%hash%]"
    const val PREVIOUS_SOURCE_TEMPLATE = "%source% - %artist% - %title%"
    const val LEGACY_TEMPLATE = "%artist% - %title%"

    val placeholders: List<String> = listOf(
        "%title%", "%artist%", "%album%", "%source%",
        "%id%", "%audioId%", "%subAudioId%", "%hash%",
    )

    private const val MAX_BASE_BYTES = 200
    private const val MAX_FILE_BYTES = 128
    private const val MAX_EXT_BYTES = 16
    private const val MIN_BASE_CODE_POINTS = 2
    private const val HASH_HEX_LENGTH = 12

    // 控制字符也要替换，否则文件名在部分文件系统上不可写
    private val illegalChars = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    fun normalizeTemplate(template: String?): String? =
        template?.trim()?.takeIf { it.isNotEmpty() }

    /** 渲染 basename（不含扩展名），已清洗并按 UTF-8 字节限长。 */
    fun renderBaseName(
        title: String,
        artist: String,
        album: String,
        source: String,
        songId: String = "",
        audioId: String = "",
        subAudioId: String = "",
        identity: String = "",
        template: String? = null,
    ): String {
        val effectiveTemplate = normalizeTemplate(template) ?: DEFAULT_TEMPLATE
        val hash = if (identity.isNotBlank()) {
            sha256Prefix(identity)
        } else {
            sha256Prefix(
                listOf(title, artist, album, source, songId, audioId, subAudioId)
                    .joinToString("\u0000")
            )
        }

        val rendered = truncateBase(
            renderExact(title, artist, album, source, songId, audioId, subAudioId, hash, effectiveTemplate)
        )
        if (
            rendered.codePointCount(0, rendered.length) >= MIN_BASE_CODE_POINTS ||
            effectiveTemplate == DEFAULT_TEMPLATE
        ) {
            return rendered
        }
        // 过短文件名在部分 ROM / 文件系统上会被拒绝，回退到稳定的默认模板
        return truncateBase(
            renderExact(title, artist, album, source, songId, audioId, subAudioId, hash, DEFAULT_TEMPLATE)
        )
    }

    /** Song 重载：来源按 MediaSource 映射，身份用 song.key。 */
    fun renderBaseName(song: Song, template: String? = null): String = renderBaseName(
        title = song.title,
        artist = song.artist,
        album = song.album,
        source = sourceName(song.source),
        songId = song.remoteId ?: song.key,
        audioId = song.remoteId.orEmpty(),
        subAudioId = song.remoteId.orEmpty(),
        identity = song.key,
        template = template,
    )

    /** 完整文件名（basename + "." + ext），总长不超过 128 UTF-8 字节。 */
    fun buildFileName(song: Song, extension: String, template: String? = null): String {
        val cleanExt = sanitize(extension.trim().removePrefix("."))
        val dotExt = truncateUtf8(".$cleanExt", MAX_EXT_BYTES)
        val baseBudget = (MAX_FILE_BYTES - dotExt.toByteArray(Charsets.UTF_8).size).coerceAtLeast(1)
        val base = truncateUtf8(renderBaseName(song, template), baseBudget)
            .trimEnd()
            .ifBlank { "audio" }
        return base + dotExt
    }

    /** 设置页预览：固定示例字段，直接展示最终文件名。 */
    fun preview(template: String?): String {
        val sample = Song(
            key = "netease:demo",
            source = MediaSource.NETEASE,
            title = "示例歌曲",
            artist = "示例歌手",
            album = "示例专辑",
            remoteId = "demo",
        )
        return buildFileName(sample, "mp3", template)
    }

    private fun renderExact(
        title: String,
        artist: String,
        album: String,
        source: String,
        songId: String,
        audioId: String,
        subAudioId: String,
        hash: String,
        template: String,
    ): String {
        val raw = if (template == DEFAULT_TEMPLATE) {
            // 空专辑省略整段；空歌手保留空位，否则会被解析成专辑或来源
            listOfNotNull(title, artist, album.takeIf { it.isNotBlank() }, source)
                .joinToString(" - ")
        } else {
            template
                .replace("%title%", title)
                .replace("%artist%", artist)
                .replace("%album%", album)
                .replace("%source%", source)
                .replace("%id%", songId)
                .replace("%audioId%", audioId)
                .replace("%subAudioId%", subAudioId)
                .replace("%hash%", hash)
        }
        return sanitize(raw)
    }

    private fun sanitize(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFKD)
            .replace(illegalChars, "_")
            .trim()
            .ifBlank { "audio" }

    private fun truncateBase(name: String): String =
        truncateUtf8(name, MAX_BASE_BYTES).trimEnd().ifBlank { "audio" }

    /** 按码点截断，避免把 UTF-8 多字节字符切断。 */
    private fun truncateUtf8(name: String, maximumBytes: Int): String {
        if (maximumBytes <= 0 || name.isEmpty()) return ""
        if (name.toByteArray(Charsets.UTF_8).size <= maximumBytes) return name

        val result = StringBuilder()
        var offset = 0
        var bytes = 0
        while (offset < name.length) {
            val codePoint = name.codePointAt(offset)
            val length = Character.charCount(codePoint)
            val codePointBytes = name.substring(offset, offset + length)
                .toByteArray(Charsets.UTF_8).size
            if (bytes + codePointBytes > maximumBytes) break
            result.appendCodePoint(codePoint)
            bytes += codePointBytes
            offset += length
        }
        return result.toString()
    }

    private fun sha256Prefix(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            .take(HASH_HEX_LENGTH)

    private fun sourceName(source: MediaSource): String = when (source) {
        MediaSource.NETEASE -> "netease"
        MediaSource.BILIBILI -> "bilibili"
        MediaSource.YOUTUBE -> "youtubeMusic"
        MediaSource.LOCAL -> "local"
    }
}
