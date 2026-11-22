package moe.ouom.neriplayer.desktop.core

import kotlinx.serialization.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.Tag
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.logging.Level
import java.util.logging.Logger

private val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wave", "aiff", "aif", "aifc",
    "wma", "ape", "mka", "mp4", "alac", "dsf", "dff"
)

/** 常见音频扩展名，扫描时按大小写不敏感匹配。 */
fun isSupportedAudioFile(file: File): Boolean =
    file.isFile && file.extension.lowercase(Locale.ROOT) in AUDIO_EXTENSIONS

@Serializable
data class ScanState(
    val running: Boolean = false,
    val total: Int = 0,
    val processed: Int = 0,
    val found: Int = 0,
    val finishedAt: Long = 0L,
    val error: String? = null,
) {
    val progress: Float
        get() = if (total <= 0) 0f else processed.toFloat() / total.toFloat()
}

private object JaudiologQuiet {
    init {
        runCatching {
            listOf("org.jaudiotagger", "org.jaudiotagger.audio", "org.jaudiotagger.tag", "org.jaudiotagger.tag.id3")
                .forEach { name ->
                    Logger.getLogger(name).apply {
                        level = Level.OFF
                        useParentHandlers = false
                    }
                }
            Logger.getLogger("").handlers.forEach { handler ->
                handler.filter = java.util.logging.Filter { record ->
                    !record.loggerName.startsWith("org.jaudiotagger")
                }
            }
        }
    }

    fun touch() = Unit
}

private fun md5Hex(input: String): String {
    val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}

/** 本地媒体库：扫描文件夹、读取标签、缓存封面。 */
class LibraryRepository(private val settings: SettingsRepository) {

    private val store = JsonFileStore(AppDirs.libraryFile, ListSerializer(Song.serializer())) { emptyList() }
    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs.asStateFlow()

    private val _scanState = MutableStateFlow(ScanState())
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    private val _indexLoaded = MutableStateFlow(false)
    val indexLoaded: StateFlow<Boolean> = _indexLoaded.asStateFlow()

    fun load() {
        JaudiologQuiet.touch()
        _songs.value = store.load().sortedWith(SONG_ORDER)
        _indexLoaded.value = true
    }

    fun persist() {
        store.save(_songs.value)
    }

    fun byKey(key: String): Song? = _songs.value.firstOrNull { it.key == key }

    fun byPath(path: String): Song? = _songs.value.firstOrNull { it.filePath == path }

    fun artists(): List<ArtistGroup> =
        _songs.value.groupBy { it.artistText() }
            .map { (name, songs) -> ArtistGroup(name, songs.sortedWith(SONG_ORDER)) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

    fun albums(): List<AlbumGroup> =
        _songs.value.groupBy { it.albumText() to it.artistText() }
            .map { (pair, songs) -> AlbumGroup(pair.first, pair.second, songs.sortedWith(SONG_ORDER)) }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

    suspend fun scan(): Int = withContext(Dispatchers.IO) {
        _scanState.value = ScanState(running = true)
        val folders = settings.current.musicFolders.map(::File).filter { it.isDirectory }
        if (folders.isEmpty()) {
            _scanState.value = ScanState(running = false, finishedAt = System.currentTimeMillis())
            return@withContext 0
        }
        val files = LinkedHashSet<File>()
        folders.forEach { folder -> collectAudioFiles(folder, files, 0) }
        val fileList = files.toList()
        _scanState.value = ScanState(running = true, total = fileList.size)

        val previous = _songs.value.associateBy { it.key }
        val scanned = ArrayList<Song>(fileList.size)
        var processed = 0
        fileList.chunked(64).forEach { chunk ->
            val chunkSongs = withContext(Dispatchers.Default) {
                chunk.mapNotNull { file -> readSong(file, previous[localSongKey(file.absolutePath)]) }
            }
            scanned += chunkSongs
            processed += chunk.size
            _scanState.value = ScanState(
                running = true,
                total = fileList.size,
                processed = processed,
                found = scanned.size,
            )
        }

        val ordered = scanned.sortedWith(SONG_ORDER)
        _songs.value = ordered
        persist()
        _scanState.value = ScanState(
            running = false,
            total = fileList.size,
            processed = fileList.size,
            found = ordered.size,
            finishedAt = System.currentTimeMillis(),
        )
        ordered.size
    }

    private fun collectAudioFiles(dir: File, out: MutableSet<File>, depth: Int) {
        if (depth > 12) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                if (child.name.startsWith(".")) continue
                collectAudioFiles(child, out, depth + 1)
            } else if (isSupportedAudioFile(child)) {
                out += child
            }
        }
    }

    private fun readSong(file: File, known: Song?): Song? {
        val path = file.absolutePath
        return runCatching {
            val audioFile = AudioFileIO.read(file)
            val header = audioFile.audioHeader
            val tag = audioFile.tag
            val title = tag?.getFirst(FieldKey.TITLE)?.trim().orEmpty().ifBlank {
                file.nameWithoutExtension
            }
            val artist = tag?.getFirst(FieldKey.ARTIST)?.trim().orEmpty()
            val album = tag?.getFirst(FieldKey.ALBUM)?.trim().orEmpty()
            val track = tag?.getFirst(FieldKey.TRACK)?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            val durationSec = header?.trackLength?.toLong() ?: 0L
            val embeddedLyrics = runCatching { tag?.getFirst(FieldKey.LYRICS)?.trim() }.getOrNull()
                ?.takeIf { it.isNotBlank() }
            val artworkPath = known?.artworkPath?.takeIf { it.isNotBlank() && File(it).exists() }
                ?: runCatching {
                    val artwork = tag?.firstArtwork ?: return@runCatching null
                    val bytes = artwork.binaryData ?: return@runCatching null
                    if (bytes.isEmpty()) return@runCatching null
                    val target = File(AppDirs.artworkDir, md5Hex(path) + ".img")
                    if (!target.exists() || target.length() != bytes.size.toLong()) {
                        target.writeBytes(bytes)
                    }
                    target.absolutePath
                }.getOrNull()
            Song(
                key = localSongKey(path),
                source = MediaSource.LOCAL,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationSec * 1000L,
                filePath = path,
                artworkPath = artworkPath,
                lyrics = embeddedLyrics ?: known?.lyrics,
                dateAdded = known?.dateAdded?.takeIf { it > 0 } ?: file.lastModified(),
                trackNumber = track,
            )
        }.getOrNull()
    }

    /**
     * 把可编辑字段写回本地文件的音频标签，并同步内存中的 Song。
     *
     * 对齐安卓端实际写入的嵌入式字段：标题（TITLE）与艺术家（ARTIST）；专辑（ALBUM）
     * 仅在显式提供时写入。远端歌曲或没有 filePath 的歌曲不可写，调用方需先校验。
     * 只读文件、格式不支持等异常统一抛给 API 层转成明确错误。
     *
     * @return 写入后同步过的最新 Song
     */
    suspend fun updateSongMetadata(
        key: String,
        title: String?,
        artist: String?,
        album: String?,
    ): Song = withContext(Dispatchers.IO) {
        val existing = byKey(key) ?: throw IllegalArgumentException("找不到歌曲: $key")
        val path = existing.filePath
            ?: throw IllegalArgumentException("该歌曲没有本地文件，无法写入元数据")
        val file = File(path)
        if (!existing.isLocalFile() || !file.isFile) {
            throw IllegalArgumentException("该歌曲不是可写的本地文件: $path")
        }
        if (!file.canWrite()) {
            throw IllegalStateException("文件不可写（只读或权限不足）: $path")
        }
        if (!isSupportedAudioFile(file)) {
            throw IllegalArgumentException("不支持写入的音频格式: ${file.extension}")
        }

        val audioFile = runCatching { AudioFileIO.read(file) }
            .getOrElse { throw IllegalStateException("读取音频标签失败: ${it.message}") }
        val tag: Tag = audioFile.tag
            ?: throw IllegalStateException("文件没有可写入的标签: $path")

        // 用 null 表示该字段不修改；空串表示显式清空该字段（与安卓端「恢复原信息」语义一致）。
        title?.let { tag.setField(FieldKey.TITLE, it) }
        artist?.let { tag.setField(FieldKey.ARTIST, it) }
        album?.let { tag.setField(FieldKey.ALBUM, it) }

        runCatching { audioFile.commit() }
            .onFailure { throw IllegalStateException("写入音频标签失败: ${it.message}") }

        val updated = existing.copy(
            title = title?.let { it.trim().ifBlank { file.nameWithoutExtension } } ?: existing.title,
            artist = artist?.let { it.trim() } ?: existing.artist,
            album = album?.let { it.trim() } ?: existing.album,
        )
        // 替换列表元素会更新 _songs，从而由 StatePublisher 广播 library.changed。
        _songs.value = _songs.value.map { if (it.key == key) updated else it }
        persist()
        updated
    }
}

internal val SONG_ORDER: Comparator<Song> = compareBy<Song>(
    { it.artistText().lowercase(Locale.ROOT) },
    { it.albumText().lowercase(Locale.ROOT) },
    { it.trackNumber },
    { it.displayName().lowercase(Locale.ROOT) },
)
