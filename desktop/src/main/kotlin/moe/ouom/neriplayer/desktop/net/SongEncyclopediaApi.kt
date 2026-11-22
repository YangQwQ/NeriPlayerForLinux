package moe.ouom.neriplayer.desktop.net

import moe.ouom.neriplayer.desktop.core.EncyclopediaArtistInfo
import moe.ouom.neriplayer.desktop.core.EncyclopediaAward
import moe.ouom.neriplayer.desktop.core.EncyclopediaBasicInfo
import moe.ouom.neriplayer.desktop.core.EncyclopediaCreditRole
import moe.ouom.neriplayer.desktop.core.EncyclopediaCredits
import moe.ouom.neriplayer.desktop.core.EncyclopediaMusicInfo
import moe.ouom.neriplayer.desktop.core.EncyclopediaRelatedPlaylist
import moe.ouom.neriplayer.desktop.core.EncyclopediaSimilarSong
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.core.SongEncyclopedia
import java.io.IOException
import kotlinx.serialization.json.JsonObject

private const val NETEASE_BASE = "https://music.163.com"
private const val BASIC_BLOCK_CODE = "SONG_PLAY_ABOUT_SONG_BASIC"
private const val SIMILAR_SONG_BLOCK_CODE = "SONG_PLAY_ABOUT_SIMILAR_SONG"
private const val RELATED_PLAYLIST_BLOCK_CODE = "SONG_PLAY_ABOUT_RELATED_PLAYLIST"
private const val CREDIT_ROLE_LYRICIST = "作词"
private const val CREDIT_ROLE_COMPOSER = "作曲"
private const val CREDIT_ROLE_ARRANGER = "编曲"
private const val CREDIT_ROLE_PRODUCER = "制作人"

private val STANDARD_CREDIT_ROLES = setOf(
    CREDIT_ROLE_LYRICIST,
    CREDIT_ROLE_COMPOSER,
    CREDIT_ROLE_ARRANGER,
    CREDIT_ROLE_PRODUCER,
)

private val NETEASE_HEADERS = mapOf(
    "Referer" to "https://music.163.com/",
    // 百科四个端点匿名可用；appver/os 让接口返回桌面版结构（与 OnlineApi 一致）。
    "Cookie" to "appver=2.0.2; os=pc",
)

/** 百科请求结果。与 Android 的 `SongEncyclopediaResult` 语义一致。 */
sealed interface EncyclopediaResult {
    data class Success(val data: SongEncyclopedia) : EncyclopediaResult
    /** 歌曲来源不支持百科（本地 / B 站 / YouTube）。 */
    data object NotSupported : EncyclopediaResult
    /** 接口可达，但这首歌确实没有百科内容（没有 SONG_PLAY_ABOUT_SONG_BASIC 板块）。 */
    data object NotAvailable : EncyclopediaResult
    /** 网络 / 解析失败，可重试。 */
    data class Failed(val cause: Throwable) : EncyclopediaResult
}

/**
 * 网易云歌曲百科数据源，移植自 Android 的 NeteaseSongEncyclopediaRepository + 解析器。
 *
 * 四个端点全部是匿名公开 GET（`/api/...`，明文参数，无 eapi 签名、无需登录 Cookie），
 * 实测见 desktop/docs/protocol-online.md。相似歌曲与相关歌单与基础板块同源，
 * 复用同一份 summary 响应不重复请求；歌曲详情 / 艺人简介 / 创作团队为加分项，
 * 单独失败只丢对应分区，不影响已取到的曲风、BPM 等主数据。
 */
object SongEncyclopediaApi {

    private data class Summary(
        val genres: List<String> = emptyList(),
        val tags: List<String> = emptyList(),
        val language: String? = null,
        val bpm: Int? = null,
        val awards: List<EncyclopediaAward> = emptyList(),
        val appearances: List<String> = emptyList(),
    )

    private data class SongDetail(
        val albumId: Long?,
        val albumName: String?,
        val albumPicUrl: String?,
        val albumCompany: String?,
        val publishTimeMs: Long?,
        val artistId: Long?,
    )

    /** 进程内 10 分钟 LRU 缓存，避免反复进出百科页重复请求。 */
    private val cache = object : LinkedHashMap<String, SongEncyclopedia>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SongEncyclopedia>?): Boolean =
            size > 32
    }
    private val cacheLock = Any()
    private val savedAt = HashMap<String, Long>()
    private const val TTL_MS = 10 * 60 * 1000L

    fun load(http: HttpService, song: Song): EncyclopediaResult {
        val songId = resolveNeteaseSongId(song) ?: return EncyclopediaResult.NotSupported
        val songKey = songKeyOf(song)
        cached(songKey)?.let { return EncyclopediaResult.Success(it) }
        return try {
            val result = fetch(http, song, songKey, songId)
            if (result is EncyclopediaResult.Success) put(songKey, result.data)
            result
        } catch (error: IOException) {
            EncyclopediaResult.Failed(error)
        } catch (error: Exception) {
            EncyclopediaResult.Failed(error)
        }
    }

    /** 供测试/手动清缓存使用。 */
    fun clearCache() {
        synchronized(cacheLock) {
            cache.clear()
            savedAt.clear()
        }
    }

    private fun cached(key: String): SongEncyclopedia? = synchronized(cacheLock) {
        val at = savedAt[key] ?: return@synchronized null
        if (System.currentTimeMillis() - at > TTL_MS) {
            cache.remove(key)
            savedAt.remove(key)
            null
        } else {
            cache[key]
        }
    }

    private fun put(key: String, data: SongEncyclopedia) = synchronized(cacheLock) {
        cache[key] = data
        savedAt[key] = System.currentTimeMillis()
    }

    private fun fetch(http: HttpService, song: Song, songKey: String, songId: Long): EncyclopediaResult {
        val summaryRaw = http.get("$NETEASE_BASE/api/song/play/about/block/page?songId=$songId", NETEASE_HEADERS)
            ?: throw IOException("百科板块接口无响应")
        val summary = parseSummary(summaryRaw)
            ?: return EncyclopediaResult.NotAvailable
        // 相似歌曲 / 相关歌单与 summary 同源，复用同一份原始响应。
        val similar = runCatching { parseSimilarSongs(summaryRaw) }.getOrDefault(emptyList())
        val playlists = runCatching { parseRelatedPlaylists(summaryRaw) }.getOrDefault(emptyList())
        // 以下三组都是加分项：单独失败只丢对应字段，不影响主数据。
        val detail = runCatching { loadSongDetail(http, songId) }.getOrNull()
        val artist = detail?.artistId?.let { id -> runCatching { loadArtist(http, id) }.getOrNull() }
        val credits = runCatching { loadCredits(http, songId) }.getOrNull()

        val data = SongEncyclopedia(
            songKey = songKey,
            source = MediaSource.NETEASE,
            basicInfo = EncyclopediaBasicInfo(
                name = song.title,
                artists = song.artist.split("/").map(String::trim).filter(String::isNotEmpty),
                album = detail?.albumName?.takeIf { it.isNotBlank() }
                    ?: song.album.takeIf { it.isNotBlank() },
            ),
            musicInfo = EncyclopediaMusicInfo(
                genres = summary.genres,
                bpm = summary.bpm,
                language = summary.language,
                tags = summary.tags,
            ),
            credits = credits,
            awards = summary.awards,
            appearances = summary.appearances,
            publishTimeMs = detail?.publishTimeMs,
            albumCompany = detail?.albumCompany,
            albumId = detail?.albumId,
            albumPicUrl = detail?.albumPicUrl,
            artistId = detail?.artistId,
            artist = artist,
            similarSongs = similar,
            relatedPlaylists = playlists,
            fetchedAtMs = System.currentTimeMillis(),
        )
        return EncyclopediaResult.Success(data)
    }

    private fun loadSongDetail(http: HttpService, songId: Long): SongDetail? {
        val raw = http.get("$NETEASE_BASE/api/song/detail?ids=%5B$songId%5D", NETEASE_HEADERS)
            ?: return null
        val root = NeriJsonParser.parse(raw).asObject() ?: return null
        if (root.int("code")?.takeIf { it != 200 } != null) return null
        val song = root.array("songs")?.objects()?.firstOrNull() ?: return null
        val album = song.obj("album") ?: JsonObject(emptyMap())
        val artists = song.array("artists")?.objects().orEmpty()
        return SongDetail(
            albumId = album.long("id"),
            albumName = album.str("name")?.takeIf { it.isNotBlank() },
            albumPicUrl = normalizeImageUrl(album.str("picUrl")),
            albumCompany = album.str("company")?.takeIf { it.isNotBlank() },
            publishTimeMs = song.long("publishTime"),
            artistId = artists.firstOrNull()?.long("id"),
        )
    }

    private fun loadArtist(http: HttpService, artistId: Long): EncyclopediaArtistInfo? {
        val raw = http.get("$NETEASE_BASE/api/v1/artist/$artistId", NETEASE_HEADERS) ?: return null
        val root = NeriJsonParser.parse(raw).asObject() ?: return null
        if (root.int("code")?.takeIf { it != 200 } != null) return null
        val artist = root.obj("artist") ?: return null
        val id = artist.long("id") ?: return null
        val name = artist.str("name")?.takeIf { it.isNotBlank() } ?: return null
        return EncyclopediaArtistInfo(
            artistId = id,
            name = name,
            picUrl = normalizeImageUrl(artist.str("picUrl")),
            briefDesc = artist.str("briefDesc")?.takeIf { it.isNotBlank() },
            // 该端点不返回粉丝数，专用接口实测不可用，禁止估算。
            fansCount = null,
            albumSize = artist.int("albumSize"),
            musicSize = artist.int("musicSize"),
        )
    }

    private fun loadCredits(http: HttpService, songId: Long): EncyclopediaCredits? {
        val raw = http.get("$NETEASE_BASE/api/song/creators?songId=$songId", NETEASE_HEADERS)
            ?: return null
        val root = NeriJsonParser.parse(raw).asObject() ?: return null
        if (root.int("code")?.takeIf { it != 200 } != null) return null
        val roles = root.obj("data")?.array("songCreatorsRoleVos")?.objects().orEmpty()
            .mapNotNull { role ->
                val roleName = role.str("roleName")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val names = role.array("creatorMetaVOS")?.objects().orEmpty()
                    .mapNotNull { it.str("artistName")?.takeIf { value -> value.isNotBlank() } }
                if (names.isEmpty()) null else EncyclopediaCreditRole(roleName, names)
            }
        if (roles.isEmpty()) return null
        return EncyclopediaCredits(
            lyricists = roles.namesOf(CREDIT_ROLE_LYRICIST),
            composers = roles.namesOf(CREDIT_ROLE_COMPOSER),
            arrangers = roles.namesOf(CREDIT_ROLE_ARRANGER),
            producers = roles.namesOf(CREDIT_ROLE_PRODUCER),
            otherRoles = roles.filterNot { it.role in STANDARD_CREDIT_ROLES },
        )
    }

    private fun List<EncyclopediaCreditRole>.namesOf(role: String): List<String> =
        firstOrNull { it.role == role }?.names.orEmpty()

    // ------------------------------------------------------------------ 解析器

    /** 歌曲不存在时接口同样 code=200，但没有 SONG_PLAY_ABOUT_SONG_BASIC 板块 → null。 */
    private fun parseSummary(raw: String): Summary? {
        val root = NeriJsonParser.parse(raw).asObject() ?: return null
        if (root.int("code") != 200) throw IOException("百科板块接口返回 code=${root.int("code")}")
        val blocks = root.obj("data")?.array("blocks")?.objects().orEmpty()
        val basic = blocks.firstOrNull { it.str("code") == BASIC_BLOCK_CODE } ?: return null
        val creatives = basic.array("creatives")?.objects().orEmpty()
        return Summary(
            genres = creatives.resourceTitlesOf("songTag"),
            tags = creatives.resourceTitlesOf("songBizTag"),
            language = creatives.firstCreativeOf("language")?.firstTextLinkText(),
            bpm = creatives.firstCreativeOf("bpm")?.firstTextLinkText()
                ?.toIntOrNull()?.takeIf { it > 0 },
            awards = creatives.awardsOf("songAward"),
            appearances = creatives.resourceTitlesOf("entertainment"),
        )
    }

    private fun parseSimilarSongs(raw: String): List<EncyclopediaSimilarSong> {
        val root = NeriJsonParser.parse(raw).asObject() ?: return emptyList()
        if (root.int("code") != 200) return emptyList()
        val blocks = root.obj("data")?.array("blocks")?.objects().orEmpty()
        val block = blocks.firstOrNull { it.str("code") == SIMILAR_SONG_BLOCK_CODE } ?: return emptyList()
        val result = ArrayList<EncyclopediaSimilarSong>()
        for (creative in block.array("creatives")?.objects().orEmpty()) {
            for (resource in creative.array("resources")?.objects().orEmpty()) {
                if (resource.str("resourceType") != "SONG") continue
                if (resource.bool("valid") != true) continue
                val songId = resource.long("resourceId") ?: continue
                val element = resource.obj("uiElement") ?: continue
                val name = element.obj("mainTitle")?.str("title")?.takeIf { it.isNotBlank() }
                    ?: continue
                val artist = element.subTitleTexts().firstOrNull() ?: continue
                val coverUrl = element.array("images")?.objects()?.firstOrNull()
                    ?.str("imageUrl")?.let { normalizeImageUrl(it) }
                result.add(EncyclopediaSimilarSong(songId, name, artist, coverUrl))
            }
        }
        return result
    }

    private fun parseRelatedPlaylists(raw: String): List<EncyclopediaRelatedPlaylist> {
        val root = NeriJsonParser.parse(raw).asObject() ?: return emptyList()
        if (root.int("code") != 200) return emptyList()
        val blocks = root.obj("data")?.array("blocks")?.objects().orEmpty()
        val block = blocks.firstOrNull { it.str("code") == RELATED_PLAYLIST_BLOCK_CODE } ?: return emptyList()
        val result = ArrayList<EncyclopediaRelatedPlaylist>()
        for (creative in block.array("creatives")?.objects().orEmpty()) {
            for (resource in creative.array("resources")?.objects().orEmpty()) {
                if (resource.str("resourceType") != "PLAYLIST") continue
                val playlistId = resource.long("resourceId") ?: continue
                val element = resource.obj("uiElement") ?: continue
                val name = element.obj("mainTitle")?.str("title")?.takeIf { it.isNotBlank() }
                    ?: continue
                val coverUrl = element.array("images")?.objects()?.firstOrNull()
                    ?.str("imageUrl")?.let { normalizeImageUrl(it) }
                result.add(
                    EncyclopediaRelatedPlaylist(
                        playlistId = playlistId,
                        name = name,
                        coverUrl = coverUrl,
                        playCount = resource.obj("resourceExt")?.long("playCount"),
                        // 接口不给歌曲数，保持 null，禁止伪造。
                        trackCount = null,
                    ),
                )
            }
        }
        return result
    }

    private fun List<JsonObject>.creativesOfType(type: String): List<JsonObject> =
        filter { it.str("creativeType") == type }

    private fun List<JsonObject>.firstCreativeOf(type: String): JsonObject? =
        creativesOfType(type).firstOrNull()

    private fun List<JsonObject>.resourceTitlesOf(type: String): List<String> =
        creativesOfType(type).flatMap { creative ->
            creative.array("resources")?.objects().orEmpty().mapNotNull { resource ->
                resource.obj("uiElement")?.obj("mainTitle")?.str("title")?.takeIf { it.isNotBlank() }
            }
        }

    private fun List<JsonObject>.awardsOf(type: String): List<EncyclopediaAward> =
        creativesOfType(type).flatMap { creative ->
            creative.array("resources")?.objects().orEmpty().mapNotNull { resource ->
                val element = resource.obj("uiElement") ?: return@mapNotNull null
                val title = element.obj("mainTitle")?.str("title")?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val detail = element.subTitleTexts().joinToString(" ").takeIf { it.isNotBlank() }
                EncyclopediaAward(title, detail)
            }
        }

    private fun JsonObject.firstTextLinkText(): String? =
        obj("uiElement")?.array("textLinks")?.objects()?.firstOrNull()?.str("text")
            ?.takeIf { it.isNotBlank() }

    private fun JsonObject.subTitleTexts(): List<String> =
        array("subTitles")?.objects()?.mapNotNull { it.str("title")?.takeIf { value -> value.isNotBlank() } }
            .orEmpty()

    private fun normalizeImageUrl(url: String?): String? {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return null
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://") -> "https:" + value.removePrefix("http")
            else -> value
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 与 Android 一致：只给网易云歌曲取 id，避免对本地/B 站/YouTube 发起网易云请求。 */
    private fun resolveNeteaseSongId(song: Song): Long? {
        if (song.source != MediaSource.NETEASE) return null
        return song.remoteId?.toLongOrNull()?.takeIf { it > 0L }
            ?: song.key.removePrefix("netease:").toLongOrNull()?.takeIf { it > 0L }
    }

    private fun songKeyOf(song: Song): String =
        "${song.key}|${song.album}|${song.filePath.orEmpty()}"
}
