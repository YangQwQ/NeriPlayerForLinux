package moe.ouom.neriplayer.desktop.net

import kotlinx.serialization.Serializable
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.NeteaseCloudPage
import moe.ouom.neriplayer.desktop.core.OnlineArtist
import moe.ouom.neriplayer.desktop.core.OnlineCollection
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.core.biliSongKey
import moe.ouom.neriplayer.desktop.core.neteaseSongKey
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private const val NETEASE_BASE = "https://music.163.com"

private fun md5Hex(text: String): String =
    MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

private fun cleanBiliText(raw: String?): String =
    raw.orEmpty().replace(Regex("<[^>]+>"), "").replace("&quot;", "\"").replace("&amp;", "&").trim()

private fun biliDurationMs(duration: String?): Long {
    if (duration.isNullOrBlank()) return 0L
    val parts = duration.trim().split(':')
    if (parts.size == 1) {
        val seconds = parts[0].toLongOrNull() ?: return 0L
        return seconds * 1000L
    }
    var totalSeconds = 0L
    parts.forEach { part -> totalSeconds = totalSeconds * 60 + (part.toLongOrNull() ?: 0L) }
    return totalSeconds * 1000L
}

private fun normalizeImageUrl(url: String?): String? {
    val value = url?.trim().orEmpty()
    if (value.isEmpty()) return null
    return when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://") -> "https://" + value.removePrefix("http://")
        else -> value
    }
}

/** eapi 歌词原始包：普通 LRC、翻译与逐词 yrc，任一字段都可能缺失。 */
data class NeteaseLyricPayload(
    val lrc: String? = null,
    val translated: String? = null,
    /** 翻译的逐词时间版本（`ytlrc`），比 `tlyric` 更贴合 yrc 行时间。 */
    val yrcTranslated: String? = null,
    val yrc: String? = null,
    /** 音译歌词（`romalrc` / `romanized`），与 Android 的 romanizedLyric 对应。 */
    val romanized: String? = null,
)

/** 网易云音乐公开接口客户端（无需登录即可搜索、取歌词与试听地址）。 */
class NeteaseApi(private val http: HttpService) {

    private val baseHeaders = mapOf(
        "Referer" to "https://music.163.com/",
        "Cookie" to "appver=2.0.2; os=pc",
    )

    private fun getJson(path: String): JsonObjectSelf? {
        val text = http.get("$NETEASE_BASE$path", baseHeaders) ?: return null
        return NeriJsonParser.parse(text).asObject()?.let { JsonObjectSelf(it) }
    }

    private fun songFromJson(obj: JsonObjectSelf): Song? {
        val id = obj.long("id") ?: return null
        val artists = obj.array("artists")?.objects()?.mapNotNull { it.str("name") }.orEmpty()
            .ifEmpty { obj.array("ar")?.objects()?.mapNotNull { it.str("name") }.orEmpty() }
        val albumObj = obj.obj("album") ?: obj.obj("al")
        val albumName = albumObj?.str("name").orEmpty()
        val cover = normalizeImageUrl(albumObj?.str("picUrl") ?: obj.str("picUrl"))
        val duration = obj.long("duration") ?: obj.long("dt") ?: 0L
        val albumId = (albumObj?.long("id"))?.toString()
        return Song(
            key = neteaseSongKey(id),
            source = MediaSource.NETEASE,
            title = obj.str("name").orEmpty().ifBlank { "未知歌曲" },
            artist = artists.joinToString(" / "),
            album = albumName,
            durationMs = duration,
            remoteId = id.toString(),
            artworkUrl = cover,
            albumId = albumId,
            artistId = obj.array("artists")?.objects()?.firstOrNull()?.long("id")?.toString(),
        )
    }

    fun searchSongs(query: String, limit: Int = 30, offset: Int = 0): List<Song> {
        val path = "/api/search/get?s=${urlEncode(query)}&type=1&limit=$limit&offset=$offset"
        val root = getJson(path) ?: return emptyList()
        val songs = root.obj("result")?.array("songs") ?: return emptyList()
        val parsed = songs.objects().mapNotNull { songFromJson(JsonObjectSelf(it)) }
        // 搜索接口不返回封面，缺少封面的歌曲用详情接口补齐
        val missingCoverIds = parsed.filter { it.artworkUrl.isNullOrBlank() }
            .mapNotNull { it.remoteId?.toLongOrNull() }
        if (missingCoverIds.isEmpty()) return parsed
        val covers = songCovers(missingCoverIds.take(50))
        return parsed.map { song ->
            if (song.artworkUrl.isNullOrBlank()) {
                val cover = covers[song.remoteId?.toLongOrNull()]
                if (cover != null) song.copy(artworkUrl = cover) else song
            } else {
                song
            }
        }
    }

    /** 批量补齐歌曲封面。 */
    private fun songCovers(ids: List<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val root = getJson("/api/song/detail?ids=%5B${ids.joinToString(",")}%5D") ?: return emptyMap()
        val list = root.array("songs") ?: return emptyMap()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            val cover = normalizeImageUrl(item.obj("album")?.str("picUrl")) ?: return@mapNotNull null
            id to cover
        }.toMap()
    }

    fun searchPlaylists(query: String, limit: Int = 30, offset: Int = 0): List<OnlineCollection> {
        val path = "/api/search/get?s=${urlEncode(query)}&type=1000&limit=$limit&offset=$offset"
        val root = getJson(path) ?: return emptyList()
        val list = root.obj("result")?.array("playlists") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineCollection(
                id = id.toString(),
                name = cleanBiliText(item.str("name")),
                creator = item.obj("creator")?.str("nickname").orEmpty(),
                coverUrl = normalizeImageUrl(item.str("coverImgUrl")),
                trackCount = item.int("trackCount") ?: 0,
                playCount = item.long("playCount") ?: 0L,
                source = MediaSource.NETEASE,
                description = item.str("description").orEmpty(),
            )
        }
    }

    fun searchArtists(query: String, limit: Int = 30, offset: Int = 0): List<OnlineArtist> {
        val path = "/api/search/get?s=${urlEncode(query)}&type=100&limit=$limit&offset=$offset"
        val root = getJson(path) ?: return emptyList()
        val list = root.obj("result")?.array("artists") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineArtist(
                id = id.toString(),
                name = cleanBiliText(item.str("name")),
                avatarUrl = normalizeImageUrl(item.str("picUrl")),
                songCount = item.int("musicSize") ?: item.int("albumSize") ?: 0,
                source = MediaSource.NETEASE,
            )
        }
    }

    fun searchAlbums(query: String, limit: Int = 30, offset: Int = 0): List<OnlineCollection> {
        val path = "/api/search/get?s=${urlEncode(query)}&type=10&limit=$limit&offset=$offset"
        val root = getJson(path) ?: return emptyList()
        val list = root.obj("result")?.array("albums") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineCollection(
                id = id.toString(),
                name = cleanBiliText(item.str("name")),
                creator = item.obj("artist")?.str("name").orEmpty(),
                coverUrl = normalizeImageUrl(item.str("picUrl")),
                trackCount = item.int("size") ?: 0,
                source = MediaSource.NETEASE,
            )
        }
    }

    fun hotSearchKeywords(): List<String> {
        val root = getJson("/api/search/hot") ?: return emptyList()
        val hots = root.obj("result")?.array("hots") ?: return emptyList()
        return hots.objects().mapNotNull { it.obj("first")?.str("keyword") ?: it.str("keyword") }.distinct()
    }

    /** 返回 (播放地址, 音质标签, 文件大小)。 */
    fun songUrl(id: String, level: String = "exhigh"): SongUrl? {
        val bitrate = when (level) {
            "standard" -> 128000
            "higher" -> 192000
            "exhigh", "lossless", "hires" -> 320000
            else -> 320000
        }
        val root = getJson("/api/song/enhance/player/url?ids=%5B$id%5D&br=$bitrate") ?: return null
        val data = root.array("data")?.objects()?.firstOrNull() ?: return null
        val url = data.str("url") ?: return null
        return SongUrl(
            url = url,
            level = data.str("level") ?: level,
            type = data.str("type").orEmpty(),
            size = data.long("size") ?: 0L,
        )
    }

    fun lyric(id: String): Pair<String, String?>? {
        val root = getJson("/api/song/lyric?id=$id&lv=1&kv=1&tv=-1") ?: return null
        val lyric = root.obj("lrc")?.str("lyric") ?: return null
        val translated = root.obj("tlyric")?.str("lyric")
        return lyric to translated?.takeIf { it.isNotBlank() }
    }

    /** 明文歌词接口的完整包（含音译 romalrc），供 eapi 失败时回退。 */
    fun lyricPlainPayload(id: String): NeteaseLyricPayload? {
        val root = getJson("/api/song/lyric?id=$id&lv=1&kv=1&tv=-1") ?: return null
        val lyric = root.obj("lrc")?.str("lyric")?.takeIf { it.isNotBlank() } ?: return null
        return NeteaseLyricPayload(
            lrc = lyric,
            translated = root.obj("tlyric")?.str("lyric")?.takeIf { it.isNotBlank() },
            romanized = (root.obj("romalrc")?.str("lyric")
                ?: root.obj("romanized")?.str("lyric"))
                ?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * eapi 版本歌词：一次拿回普通 LRC、翻译与逐词 yrc。
     *
     * 普通接口没有 yrc，逐词时间只能从这里取；请求失败返回 null，由调用方回退 [lyric]。
     */
    fun lyricPayload(id: String): NeteaseLyricPayload? = runCatching {
        val text = NeteaseEapi.postForm(
            http,
            "/eapi/song/lyric/v1",
            linkedMapOf(
                "id" to id,
                "cp" to "false",
                "lv" to 0,
                "tv" to 1,
                "rv" to 0,
                "yv" to 1,
                "ytv" to 1,
                "yrv" to 0,
            ),
        ) ?: return@runCatching null
        val root = NeriJsonParser.parse(text).asObject() ?: return@runCatching null
        if ((root.int("code") ?: 200) != 200) return@runCatching null
        val neteaseYrc = root.obj("yrc")?.str("lyric")?.takeIf { it.isNotBlank() }
        // 与安卓对齐：网易云没有 yrc 时回退 AMLL TTML 逐字时间，
        // 转成 yrc 文本后交给既有 buildYrcLyrics，前端无需改动。
        val wordTimed = neteaseYrc ?: AmllTtmlClient.fetchWordTimed(http, id)?.let { TtmlParser.toYrcText(it) }
        NeteaseLyricPayload(
            lrc = root.obj("lrc")?.str("lyric"),
            translated = root.obj("tlyric")?.str("lyric"),
            yrcTranslated = root.obj("ytlrc")?.str("lyric"),
            yrc = wordTimed,
            // 不同接口字段名不一：优先 romalrc，回退 romanized / yromalrc
            romanized = (root.obj("romalrc")?.str("lyric")
                ?: root.obj("romanized")?.str("lyric")
                ?: root.obj("yromalrc")?.str("lyric"))
                ?.takeIf { it.isNotBlank() },
        )
    }.getOrNull()

    /** 只要逐词歌词原文；没有 yrc 或取不到时返回 null。 */
    fun lyricYrc(id: String): String? =
        lyricPayload(id)?.yrc?.takeIf { it.isNotBlank() }

    /**
     * 歌单详情。
     * @param limit 最多返回多少首（首页只展示前几首时传小值以省流量），默认返回完整歌单。
     */
    fun playlistDetail(id: String, limit: Int = Int.MAX_VALUE): Pair<OnlineCollection, List<Song>>? {
        val root = getJson("/api/v6/playlist/detail?id=$id") ?: return null
        val playlist = root.obj("playlist") ?: return null
        val collection = collectionFromPlaylist(id, playlist)
        val tracks = playlist.array("tracks")?.objects().orEmpty().mapNotNull { songFromJson(JsonObjectSelf(it)) }
        // 详情接口只返回前若干首（热歌榜 200 首只回 10 首），完整列表在 trackIds 里，
        // 缺的部分按 trackIds 顺序用歌曲详情接口补齐，否则歌单会「只显示 10 首」。
        val trackIds = playlist.array("trackIds")?.objects().orEmpty().mapNotNull { item ->
            item.long("id")?.takeIf { it != 0L }
        }
        val wantedIds = trackIds.take(limit)
        val songs = when {
            wantedIds.isEmpty() -> tracks.take(limit)
            wantedIds.size > tracks.size -> fetchSongsByIds(wantedIds)
            else -> tracks.take(limit)
        }
        return collection to songs
    }

    /**
     * 首页榜单 / 雷达：v6 接口支持 `n` 参数一次取回前 N 首，
     * 避免「先拿 10 首再按 trackIds 补拉」的第二趟请求。
     */
    fun playlistPreview(id: String, limit: Int): Pair<OnlineCollection, List<Song>>? {
        val root = getJson("/api/v6/playlist/detail?id=$id&n=$limit") ?: return null
        val playlist = root.obj("playlist") ?: return null
        val collection = collectionFromPlaylist(id, playlist)
        val tracks = playlist.array("tracks")?.objects().orEmpty().mapNotNull { songFromJson(JsonObjectSelf(it)) }
        val trackIds = playlist.array("trackIds")?.objects().orEmpty().mapNotNull { item ->
            item.long("id")?.takeIf { it != 0L }
        }
        val wantedIds = trackIds.take(limit)
        val songs = when {
            wantedIds.isEmpty() -> tracks.take(limit)
            wantedIds.size > tracks.size -> fetchSongsByIds(wantedIds)
            else -> tracks.take(limit)
        }
        return collection to songs
    }

    /** 只取歌单元数据（首页雷达歌单只要名称/封面），n=0 不返回曲目。 */
    fun playlistSummary(id: String): OnlineCollection? {
        val root = getJson("/api/v6/playlist/detail?id=$id&n=0") ?: return null
        val playlist = root.obj("playlist") ?: return null
        return collectionFromPlaylist(id, playlist)
    }

    private fun collectionFromPlaylist(id: String, playlist: JsonObjectSelf): OnlineCollection =
        OnlineCollection(
            id = id,
            name = cleanBiliText(playlist.str("name")),
            creator = playlist.obj("creator")?.str("nickname").orEmpty(),
            coverUrl = normalizeImageUrl(playlist.str("coverImgUrl")),
            trackCount = playlist.int("trackCount") ?: 0,
            playCount = playlist.long("playCount") ?: 0L,
            source = MediaSource.NETEASE,
            description = playlist.str("description").orEmpty(),
        )

    /** 按 id 批量拉取歌曲详情，保持传入顺序；单次请求最多 200 首以控制 URL 长度。 */
    private fun fetchSongsByIds(ids: List<Long>): List<Song> {
        if (ids.isEmpty()) return emptyList()
        val byId = LinkedHashMap<Long, Song>()
        for (chunk in ids.chunked(200)) {
            val path = "/api/song/detail?ids=%5B${chunk.joinToString(",")}%5D"
            val root = getJson(path) ?: continue
            for (item in root.array("songs")?.objects().orEmpty()) {
                val songId = item.long("id") ?: continue
                songFromJson(JsonObjectSelf(item))?.let { song -> byId[songId] = song }
            }
        }
        return ids.mapNotNull { byId[it] }
    }

    fun albumDetail(id: String): Pair<OnlineCollection, List<Song>>? {
        val root = getJson("/api/v1/album/$id") ?: return null
        val album = root.obj("album") ?: return null
        val collection = OnlineCollection(
            id = id,
            name = cleanBiliText(album.str("name")),
            creator = album.obj("artist")?.str("name").orEmpty(),
            coverUrl = normalizeImageUrl(album.str("picUrl")),
            trackCount = album.int("size") ?: 0,
            source = MediaSource.NETEASE,
            description = album.str("description").orEmpty(),
        )
        val songs = root.array("songs")?.objects().orEmpty().mapNotNull { songFromJson(JsonObjectSelf(it)) }
        return collection to songs
    }

    fun artistTopSongs(id: String): List<Song> {
        val root = getJson("/api/v1/artist/$id") ?: return emptyList()
        val hot = root.array("hotSongs") ?: return emptyList()
        return hot.objects().mapNotNull { songFromJson(JsonObjectSelf(it)) }
    }

    fun recommendedNewSongs(limit: Int = 12): List<Song> {
        // 旧核心少了 type/areaId，接口会退回非个性化结果；补上后才是「推荐新歌」。
        val root = getJson("/api/personalized/newsong?type=recommend&limit=$limit&areaId=0") ?: return emptyList()
        val list = root.array("result") ?: return emptyList()
        val parsed = list.objects().mapNotNull { item ->
            val songObj = item.obj("song") ?: item
            songFromJson(JsonObjectSelf(songObj))?.let { song ->
                if (song.artworkUrl.isNullOrBlank()) song.copy(artworkUrl = normalizeImageUrl(item.str("picUrl"))) else song
            }
        }
        // 部分条目既没有 album.picUrl 也没有外层 picUrl，用歌曲详情接口补齐封面
        val missingIds = parsed.filter { it.artworkUrl.isNullOrBlank() }
            .mapNotNull { it.remoteId?.toLongOrNull() }
        if (missingIds.isEmpty()) return parsed
        val covers = songCovers(missingIds.take(50))
        return parsed.map { song ->
            if (song.artworkUrl.isNullOrBlank()) {
                val cover = covers[song.remoteId?.toLongOrNull()]
                if (cover != null) song.copy(artworkUrl = cover) else song
            } else {
                song
            }
        }
    }

    fun recommendedPlaylists(limit: Int = 12): List<OnlineCollection> {
        val root = getJson("/api/personalized/playlist?limit=$limit") ?: return emptyList()
        val list = root.array("result") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineCollection(
                id = id.toString(),
                name = cleanBiliText(item.str("name")),
                creator = item.obj("creator")?.str("nickname").orEmpty(),
                coverUrl = normalizeImageUrl(item.str("picUrl")),
                trackCount = item.int("trackCount") ?: 0,
                playCount = item.long("playCount") ?: 0L,
                source = MediaSource.NETEASE,
            )
        }
    }

    fun toplists(): List<OnlineCollection> {
        val root = getJson("/api/toplist/detail") ?: return emptyList()
        val list = root.array("list") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineCollection(
                id = id.toString(),
                name = cleanBiliText(item.str("name")),
                coverUrl = normalizeImageUrl(item.str("coverImgUrl")),
                trackCount = item.int("trackCount") ?: 0,
                source = MediaSource.NETEASE,
                description = item.str("updateFrequency").orEmpty(),
            )
        }
    }

    /** 登录后读取用户创建与收藏的歌单。 */
    fun userPlaylists(uid: String): List<OnlineCollection> {
        if (uid.isBlank()) return emptyList()
        val root = getJson("/api/user/playlist?uid=$uid&limit=100&offset=0") ?: return emptyList()
        val list = root.array("playlist") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineCollection(
                id = id.toString(),
                name = cleanBiliText(item.str("name")),
                creator = item.obj("creator")?.str("nickname").orEmpty(),
                coverUrl = normalizeImageUrl(item.str("coverImgUrl")),
                trackCount = item.int("trackCount") ?: 0,
                playCount = item.long("playCount") ?: 0L,
                source = MediaSource.NETEASE,
                description = item.str("description").orEmpty(),
            )
        }
    }

    /**
     * 分页读取用户云盘（明文 /api，带登录 Cookie）。code=301 表示登录态失效，返回 null 交由命令层转 needLogin。
     * 条目优先用 simpleSong 映射，缺失时用外层 songId/songName/fileName 等字段回退。
     */
    fun cloudSongs(offset: Int, limit: Int): NeteaseCloudPage? {
        val root = getJson("/api/v1/cloud/get?offset=$offset&limit=$limit") ?: return null
        val code = root.int("code")
        if (code == 301) return null
        if (code != null && code != 200) return null
        val total = root.int("count") ?: 0
        val data = root.array("data")?.objects().orEmpty().map(::JsonObjectSelf)
        val songs = data.mapNotNull { item ->
            val simple = item.obj("simpleSong")
            if (simple != null && simple.long("id") != null) {
                songFromJson(simple)
            } else {
                val id = item.long("songId") ?: return@mapNotNull null
                val name = item.str("songName").takeIf { !it.isNullOrBlank() }
                    ?: item.str("fileName")?.substringBeforeLast('.').takeIf { !it.isNullOrBlank() }
                    ?: "未知歌曲"
                Song(
                    key = neteaseSongKey(id),
                    source = MediaSource.NETEASE,
                    title = name,
                    artist = item.str("artist").orEmpty(),
                    album = item.str("album").orEmpty(),
                    durationMs = item.long("duration") ?: 0L,
                    remoteId = id.toString(),
                    artworkUrl = normalizeImageUrl(item.str("cover")),
                )
            }
        }
        val consumed = data.size
        val hasMore = root.str("hasMore")?.toBooleanStrictOrNull()
            ?: (consumed > 0 && offset + consumed < total)
        return NeteaseCloudPage(
            songs = songs,
            totalCount = total,
            usedBytes = root.long("size") ?: 0L,
            maxBytes = root.long("maxSize") ?: 0L,
            hasMore = hasMore,
        )
    }

    // ---------------------------------------------------------------- 首页推荐（登录态 / 公开接口）

    /**
     * 每日推荐歌曲。未登录时接口返回 code=301，这里按空结果处理（栏目由首页隐藏）。
     * 走 /api 前缀的明文接口，无需 WEAPI 加密。
     */
    fun dailyRecommendedSongs(): List<Song> {
        val root = postApi("/api/v3/discovery/recommend/songs", "{}") ?: return emptyList()
        if (root.int("code") != 200) return emptyList()
        val list = root.obj("data")?.array("dailySongs") ?: return emptyList()
        return list.objects().mapNotNull { songFromJson(JsonObjectSelf(it)) }
    }

    /**
     * 私人 FM：接口每次只回一小批（有时仅 1 首），调用方按需多次取并去重。
     * 未登录返回 301 → 空数组。
     */
    fun personalFmSongs(limit: Int = 18): List<Song> {
        val collected = LinkedHashMap<String, Song>()
        repeat(6) {
            if (collected.size >= limit) return@repeat
            val root = postApi("/api/v1/radio/get", "{}") ?: return collected.values.toList()
            if (root.int("code") != 200) return collected.values.toList()
            val list = root.array("data") ?: root.obj("data")?.array("list") ?: return collected.values.toList()
            list.objects().mapNotNull { songFromJson(JsonObjectSelf(it)) }.forEach { song ->
                if (collected.size < limit) collected.putIfAbsent(song.key, song)
            }
        }
        return collected.values.toList()
    }

    /** 每日推荐歌单（需登录；未登录 301 → 空数组）。 */
    fun dailyRecommendedPlaylists(): List<OnlineCollection> {
        val root = postApi("/api/v1/discovery/recommend/resource", "{\"limit\":12}") ?: return emptyList()
        if (root.int("code") != 200) return emptyList()
        val list = root.array("recommend") ?: root.array("result") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineCollection(
                id = id.toString(),
                name = cleanBiliText(item.str("name")),
                creator = item.obj("creator")?.str("nickname").orEmpty(),
                coverUrl = normalizeImageUrl(item.str("picUrl") ?: item.str("coverImgUrl")),
                trackCount = item.int("trackCount") ?: 0,
                playCount = item.long("playCount") ?: 0L,
                source = MediaSource.NETEASE,
            )
        }
    }

    /** 精品歌单：/api 明文 POST 即可，返回体顶层是 playlists。 */
    fun highQualityPlaylists(limit: Int = 12): List<OnlineCollection> {
        val text = http.postForm(
            "$NETEASE_BASE/api/playlist/highquality/list",
            mapOf("cat" to "全部", "limit" to limit.toString(), "lasttime" to "0", "total" to "true"),
            baseHeaders,
        ) ?: return emptyList()
        val root = NeriJsonParser.parse(text).asObject() ?: return emptyList()
        val list = root.array("playlists") ?: root.obj("data")?.array("playlists") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineCollection(
                id = id.toString(),
                name = cleanBiliText(item.str("name")),
                creator = item.obj("creator")?.str("nickname").orEmpty(),
                coverUrl = normalizeImageUrl(item.str("coverImgUrl")),
                trackCount = item.int("trackCount") ?: 0,
                playCount = item.long("playCount") ?: item.long("subscribedCount") ?: 0L,
                source = MediaSource.NETEASE,
            )
        }
    }

    /** 歌单广场（热门 / ACG 等分类），与标签歌单同一公开接口。 */
    fun playlistSquare(cat: String, limit: Int = 12): List<OnlineCollection> {
        val root = getJson(
            "/api/playlist/list?cat=${urlEncode(cat)}&order=hot&offset=0&limit=$limit&total=true",
        ) ?: return emptyList()
        val list = root.array("playlists") ?: return emptyList()
        return list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineCollection(
                id = id.toString(),
                name = cleanBiliText(item.str("name")),
                creator = item.obj("creator")?.str("nickname").orEmpty(),
                coverUrl = normalizeImageUrl(item.str("coverImgUrl")),
                trackCount = item.int("trackCount") ?: 0,
                playCount = item.long("playCount") ?: 0L,
                source = MediaSource.NETEASE,
            )
        }
    }

    /** 网易云明文 /api 接口（部分首页推荐接口仍接受非加密 POST）。 */
    private fun postApi(
        path: String,
        jsonBody: String,
    ): JsonObjectSelf? {
        val text = http.execute(
            method = "POST",
            url = "$NETEASE_BASE$path",
            body = jsonBody,
            headers = baseHeaders + mapOf("Content-Type" to "application/json"),
        )?.text() ?: return null
        return NeriJsonParser.parse(text).asObject()?.let(::JsonObjectSelf)
    }
}

/** 简单的 JSON 对象包装，方便统一调用解析扩展。 */
@JvmInline
value class JsonObjectSelf(private val raw: kotlinx.serialization.json.JsonObject) {
    fun str(key: String) = raw.str(key)
    fun long(key: String) = raw.long(key)
    fun int(key: String) = raw.int(key)
    fun obj(key: String) = raw.obj(key)?.let(::JsonObjectSelf)
    fun array(key: String) = raw.array(key)
    fun keys(): Set<String> = raw.keys
}

@Serializable
data class SongUrl(
    val url: String,
    val level: String,
    val type: String,
    val size: Long,
)

/** 哔哩哔哩客户端：WBI 签名搜索 + 音频流解析。 */
class BiliApi(private val http: HttpService) {

    private val headers = mapOf("Referer" to "https://www.bilibili.com/")

    /**
     * DASH 请求档位，与 Android `PlayOptions.fnval` 一致：
     * 272 = DASH(1<<4) + 杜比(1<<8)，4048 再加 8K/HDR/AV1/AAC 高档位。
     * 高档位放后面：低档位拿到的音轨音质相同，先花一次往返更划算。
     */
    private val DASH_ATTEMPTS = listOf(
        PlayUrlAttempt(fnval = "272", fourk = "1", platform = null),
        PlayUrlAttempt(fnval = "4048", fourk = "1", platform = null),
    )

    /** 渐进式 MP4（durl）：`fnval=1` 只给 MP4，不含 DASH。 */
    private val PROGRESSIVE_ATTEMPT = PlayUrlAttempt(fnval = "1", fourk = "0", platform = null)

    /** html5 移动端 MP4 兜底（`high_quality=1` 才会给较高画质）。 */
    private val HTML5_ATTEMPT = PlayUrlAttempt(fnval = "0", fourk = "0", platform = "html5")

    /** WBI key 缓存时长，对齐 Android 的 WBI_CACHE_MS。 */
    private val WBI_CACHE_MS = 10 * 60 * 1000L

    /** 空音轨结果的重试次数与基础等待，对齐 Android 的 EMPTY_AUDIO_RETRY_*。 */
    private val EMPTY_AUDIO_RETRY_COUNT = 3
    private val EMPTY_AUDIO_RETRY_DELAY_MS = 250L

    private var wbiKeys: Pair<String, String>? = null
    // mixin key 会随 B 站轮换失效；对齐 Android 的 WBI_CACHE_MS，到期主动重取，
    // 否则一次进程活很久之后所有签名请求都会一直失败。
    private var wbiKeysLoadedAt = 0L
    private var cookieReady = false

    private fun ensureCookies() {
        if (cookieReady) return
        cookieReady = true
        runCatching {
            // 先访问主页拿到 buvid3 / b_nut，再补一份指纹 Cookie，降低接口风控概率
            http.get("https://www.bilibili.com/", mapOf("Referer" to "https://www.bilibili.com/"), timeoutSeconds = 12)
            val spi = http.get("https://api.bilibili.com/x/frontend/finger/spi", headers)
            NeriJsonParser.parse(spi.orEmpty()).asObject()?.obj("data")?.let { data ->
                data.str("b_3")?.let { http.setCookie("api.bilibili.com", "buvid3", it) }
                data.str("b_4")?.let { http.setCookie("api.bilibili.com", "buvid4", it) }
            }
        }
    }

    /** 风控/验证响应：data 里只有 v_voucher，或返回码非 0。 */
    private fun isBlockedResponse(root: kotlinx.serialization.json.JsonObject?): Boolean {
        if (root == null) return true
        if (root.int("code") != 0) return true
        val data = root.obj("data") ?: return true
        if (data["v_voucher"] != null) return true
        return data.array("result") == null
    }

    private val mixinKeyEncTab = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52
    )

    private fun loadWbiKeys(): Pair<String, String>? {
        val cached = wbiKeys
        if (cached != null && System.currentTimeMillis() - wbiKeysLoadedAt < WBI_CACHE_MS) return cached
        ensureCookies()
        val text = http.get("https://api.bilibili.com/x/web-interface/nav", headers) ?: return cached
        val wbi = NeriJsonParser.parse(text).asObject()?.obj("data")?.obj("wbi_img")
        if (wbi == null) return cached
        val imgKey = wbi.str("img_url").orEmpty().substringAfterLast('/').substringBefore('.')
        val subKey = wbi.str("sub_url").orEmpty().substringAfterLast('/').substringBefore('.')
        if (imgKey.isEmpty() || subKey.isEmpty()) return cached
        val keys = imgKey to subKey
        wbiKeys = keys
        wbiKeysLoadedAt = System.currentTimeMillis()
        return keys
    }

    private fun mixinKey(): String? {
        val (imgKey, subKey) = loadWbiKeys() ?: return null
        val raw = imgKey + subKey
        val builder = StringBuilder()
        mixinKeyEncTab.forEach { index -> if (index < raw.length) builder.append(raw[index]) }
        return builder.toString().take(32)
    }

    private fun refreshWbiKeys() {
        wbiKeys = null
    }

    private fun signedQuery(params: Map<String, String>): String? {
        val mixin = mixinKey() ?: return null
        val withTime = params.toMutableMap()
        withTime["wts"] = (System.currentTimeMillis() / 1000).toString()
        val query = withTime.entries
            .sortedBy { it.key }
            .joinToString("&") { (key, value) ->
                val cleaned = value.filterNot { it in "!'()*" }
                "${urlEncode(key)}=${urlEncode(cleaned)}"
            }
        val wRid = md5Hex(query + mixin)
        return "$query&w_rid=$wRid"
    }

    /**
     * 视频搜索。
     *
     * 参数与 Android `BiliClient.searchVideos` 对齐：
     * - [order] 排序（`totalrank`=综合，另有 `click`/`pubdate`/`dm`/`stow`）；
     * - [duration] 时长筛选（0=全部 / 1=<10 分钟 / 2=10~30 分钟 / 3=30~60 分钟 / 4=>60 分钟）；
     * - [tids] 分区（0=全部，传入 B 站分区 tid）。
     *
     * 自动换源依赖 [duration] 把候选限制在与歌曲时长相近的档位，避免搜到整场 live/合集。
     */
    fun searchVideos(
        keyword: String,
        page: Int = 1,
        order: String = "totalrank",
        duration: Int = 0,
        tids: Int = 0,
    ): List<Song> {
        ensureCookies()
        val params = mapOf(
            "search_type" to "video",
            "keyword" to keyword,
            "order" to order,
            "duration" to duration.toString(),
            "tids" to tids.toString(),
            "page" to page.toString(),
            "page_size" to "30",
        )
        var lastRoot: kotlinx.serialization.json.JsonObject? = null
        repeat(3) { attempt ->
            if (attempt > 0) {
                Thread.sleep(600L * attempt)
                refreshWbiKeys()
            }
            lastRoot = wbiRequest("https://api.bilibili.com/x/web-interface/wbi/search/type", params)
            if (isBlockedResponse(lastRoot)) {
                val fallback = "https://api.bilibili.com/x/web-interface/search/type?search_type=video" +
                    "&keyword=${urlEncode(keyword)}&order=${urlEncode(order)}&duration=$duration&tids=$tids" +
                    "&page=$page&page_size=30"
                lastRoot = NeriJsonParser.parse(http.get(fallback, headers).orEmpty()).asObject()
            }
            val results = lastRoot?.obj("data")?.array("result")
            if (results != null && results.isNotEmpty()) {
                return mapSearchResults(results)
            }
        }
        return emptyList()
    }

    private fun mapSearchResults(results: kotlinx.serialization.json.JsonArray): List<Song> =
        results.objects().mapNotNull { item ->
            // 与 Android 一致：只保留视频条目，搜索结果里偶尔混入课程/直播等其它 type
            val type = item.str("type")
            if (!type.isNullOrBlank() && type != "video") return@mapNotNull null
            val bvid = item.str("bvid") ?: item.str("aid") ?: return@mapNotNull null
            Song(
                key = biliSongKey(bvid),
                source = MediaSource.BILIBILI,
                title = cleanBiliText(item.str("title")).ifBlank { "未知视频" },
                artist = cleanBiliText(item.str("author")),
                album = "哔哩哔哩",
                durationMs = biliDurationMs(item.str("duration")),
                remoteId = bvid,
                artworkUrl = normalizeImageUrl(item.str("pic")),
            )
        }

    private fun wbiRequest(baseUrl: String, params: Map<String, String>): kotlinx.serialization.json.JsonObject? {
        val query = signedQuery(params) ?: return null
        val text = http.get("$baseUrl?$query", headers) ?: return null
        return NeriJsonParser.parse(text).asObject()
    }

    /** 调试用：返回搜索接口的原始 code 与 message。 */
    fun debugSearch(keyword: String): String {
        ensureCookies()
        val params = mapOf(
            "search_type" to "video",
            "keyword" to keyword,
            "page" to "1",
            "page_size" to "30",
        )
        val query = signedQuery(params) ?: return "mixin key unavailable"
        val text = http.get("https://api.bilibili.com/x/web-interface/wbi/search/type?$query", headers)
            ?: return "empty response"
        val root = NeriJsonParser.parse(text).asObject()
        val data = root?.obj("data")
        val results = data?.array("result")
        val first = results?.objects()?.firstOrNull()
        return "code=${root?.int("code")} message=${root?.str("message")} length=${text.length} " +
            "dataKeys=${data?.keys} resultSize=${results?.size} firstKeys=${first?.keys} " +
            "firstTitle=${first?.str("title")} firstBvid=${first?.str("bvid")}"
    }

    fun videoPages(bvid: String): List<Pair<String, String>> {
        val text = http.get("https://api.bilibili.com/x/player/pagelist?bvid=$bvid", headers) ?: return emptyList()
        val root = NeriJsonParser.parse(text).asObject() ?: return emptyList()
        val data = root.array("data") ?: return emptyList()
        return data.objects().mapNotNull { item ->
            val cid = item.long("cid")?.toString() ?: return@mapNotNull null
            cid to cleanBiliText(item.str("part"))
        }
    }

    /**
     * 用 aid 换 bvid 与首个 cid。
     *
     * 一起听房间里来自 Android 端的 B 站曲目只带纯数字 aid（stableKey 形如 `bilibili:<aid>`），
     * 而 playurl 接口只接受 bvid；缺少这一步就只能报「无法解析播放地址」。
     * 返回 null 表示 aid 无效或接口不可用。
     */
    fun resolveByAid(aid: String): Pair<String, String?>? {
        val numeric = aid.trim().takeIf { it.isNotEmpty() && it.all { ch -> ch.isDigit() } } ?: return null
        ensureCookies()
        val text = http.get("https://api.bilibili.com/x/web-interface/view?aid=$numeric", headers)
            ?: return null
        val root = NeriJsonParser.parse(text).asObject() ?: return null
        if (root.int("code") != 0) return null
        val data = root.obj("data") ?: return null
        val bvid = data.str("bvid")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cid = data.long("cid")?.takeIf { it > 0L }?.toString()
        return bvid to cid
    }

    /**
     * 解析可播放的音频直链。
     *
     * 链路与 Android 端 `BiliClient.getAllAudioStreams` 保持一致（那边的解析已经稳定，
     * 桌面版没有理由另造一套）：
     * 1. DASH：默认 `fnval=272`（DASH 16 + 杜比 256），这是 Android 的默认值；
     *    音轨为空时重试 3 次并换 WBI key（空列表多为签名/风控抖动，不是视频真的没音轨）；
     * 2. DASH 高档位 `fnval=4048`（8K + HDR + 杜比 + AV1）：覆盖只在高档位下发音轨的稿件；
     * 3. `fnval=1` 的 `durl`：老视频/版权受限稿件只有渐进式 MP4（含视频轨，仍可播）；
     * 4. `platform=html5` + `fnval=0`：移动端 MP4 兜底，部分稿件 PC 端拒绝、html5 端可播。
     *
     * 注意 `fnval` 是位掩码：`16|1=17` 会退回 durl（`1` 是「只要 mp4」的旧语义），
     * 所以 DASH 与 durl 必须分两次请求，不能拼成一个值。
     *
     * 试看流（`is_preview=1`）虽然也有直链，但只是前几分钟片段，播到一半会突然结束，
     * 不能当完整音频交出去，只记录「需要登录/会员」。
     *
     * 失败原因写进 [BiliPlaybackDiagnostics]，供上层给出比「无法解析播放地址」更具体的话术。
     */
    fun audioUrl(bvid: String, cid: String): String? {
        val cleanBvid = bvid.trim()
        val cleanCid = cid.trim()
        // 先按「调用方给的原始 ID」清掉旧原因：同一个视频换成正确的 cid 后可能就成功了，
        // 不清会让上层读到上一次的失败原因。
        BiliPlaybackDiagnostics.clear(cleanBvid)
        if (cleanBvid.isEmpty() || cleanCid.isEmpty()) {
            BiliPlaybackDiagnostics.record(cleanBvid, "缺少视频 ID 或分 P 信息")
            return null
        }
        // bvid 大小写敏感：整串小写或全大写都会被 playurl 判成 -404「啥都木有」，
        // 一起听房间与收藏同步里的写法不一定规范，这里只纠正 `BV` 前缀（后半段是大小写敏感的编码，
        // 不能整体转换），纠正得了就纠正，纠正不了就明确报错。
        if (!cleanBvid.startsWith("bv", ignoreCase = true)) {
            // `au…` 是 B 站「音频」区的内容，走的是完全独立的接口（music-service-c），
            // 不属于视频播放链路；这里必须点名，否则用户会以为是自己视频的问题。
            val reason = if (cleanBvid.startsWith("au", ignoreCase = true)) {
                "这是 B 站音频区内容（$cleanBvid），桌面版暂不支持播放"
            } else {
                "视频 ID 不是 bvid 形式（$cleanBvid）"
            }
            BiliPlaybackDiagnostics.record(cleanBvid, reason)
            return null
        }
        val normalizedBvid = "BV" + cleanBvid.substring(2)
        if (cleanCid.toLongOrNull() == null) {
            BiliPlaybackDiagnostics.record(normalizedBvid, "分 P 的 cid 不是数字（$cleanCid）")
            return null
        }
        ensureCookies()

        var lastCode: Int? = null
        var lastMessage: String? = null
        var sawPreviewOnly = false
        var sawEmptyDashAudio = false

        // DASH：主档位按 Android 的做法重试（空音轨多半是签名/风控抖动），高档位只试一次
        for ((index, attempt) in DASH_ATTEMPTS.withIndex()) {
            val tries = if (index == 0) EMPTY_AUDIO_RETRY_COUNT else 1
            for (round in 0 until tries) {
                if (round > 0) {
                    Thread.sleep(EMPTY_AUDIO_RETRY_DELAY_MS * round)
                    refreshWbiKeys()
                }
                val root = requestPlayUrl(normalizedBvid, cleanCid, attempt) ?: continue
                if (root.int("code").let { it != null && it != 0 }) {
                    lastCode = root.int("code")
                    lastMessage = root.str("message")
                    break
                }
                val data = root.obj("data") ?: continue
                if (data.bool("is_preview") == true) {
                    sawPreviewOnly = true
                    break
                }
                pickDashAudioUrl(data)?.let { return it }
                if (data.obj("dash") == null) {
                    // 整个 dash 结构都没有（老视频/版权受限稿件）：这类只会给 durl，
                    // 重试也不会有变化，直接用渐进式 MP4，省掉两次空转请求。
                    pickDurlUrl(data)?.let { return it }
                    break
                }
                sawEmptyDashAudio = true
            }
        }

        // 渐进式 MP4（durl）：老视频/版权受限稿件没有 DASH，只有含视频轨的 MP4。
        // 顺序放在 DASH 之后、html5 之前：PC 端 durl 的画质（qn 32）通常高于 html5（qn 16）。
        var multiSegmentCount = 0
        val progressive = playUrlData(normalizedBvid, cleanCid, PROGRESSIVE_ATTEMPT)
        if (progressive != null && progressive.bool("is_preview") != true) {
            val segments = progressive.array("durl")?.objects().orEmpty().size
            // 多分片 MP4 要按顺序拼接才完整，而播放器只接单条 URI；用第一段会播到一半断掉。
            if (segments > 1) multiSegmentCount = segments else pickDurlUrl(progressive)?.let { return it }
        }

        // html5 移动端兜底：PC 端偶发拒绝/只给试看时，html5 端常能拿到完整 MP4
        val html5 = playUrlData(normalizedBvid, cleanCid, HTML5_ATTEMPT)
        if (html5 != null && html5.bool("is_preview") != true) {
            val segments = html5.array("durl")?.objects().orEmpty().size
            if (segments > 1) {
                if (multiSegmentCount == 0) multiSegmentCount = segments
            } else {
                pickDurlUrl(html5)?.let { return it }
            }
        }

        val reason = when {
            sawPreviewOnly -> "该视频只提供试看片段，需要登录或会员才能播放完整内容"
            multiSegmentCount > 1 -> "该视频只提供分段（$multiSegmentCount 段）MP4，桌面版暂不支持拼接播放"
            lastCode == -404 -> "视频或分 P 不存在（cid 与视频不匹配，或视频已被删除/设为私密）"
            lastCode == -400 -> "播放接口拒绝了请求（视频 ID 或分 P 参数无效）"
            lastCode == -403 -> "无权播放该视频（可能需要登录、会员或受地区限制）"
            lastCode == -10403 -> "该视频受地区限制，当前网络无法播放"
            lastCode != null -> "播放接口返回失败（code=$lastCode${lastMessage?.let { " $it" } ?: ""}）"
            sawEmptyDashAudio -> "视频音轨为空（接口返回了 dash 但没有 audio，可能是付费内容或风控拦截）"
            else -> "播放接口未返回可用的音频流"
        }
        // 番剧/影视在普通链路上必然失败，单独兜底一次；它会给更准确的原因
        // （试看片段 / 大会员专属），所以先写下普通链路的原因，让兜底结果覆盖它。
        BiliPlaybackDiagnostics.record(normalizedBvid, reason)
        resolvePgcDurl(normalizedBvid)?.let { return it }
        return null
    }

    /**
     * 番剧 / 影视（PGC）的最后兜底。
     *
     * 这类内容本质上是 PGC 稿件：`x/player/playurl` 只认真实投稿，对需要付费的番剧会直接
     * 返回 -404，正确入口是 `pgc/player/web/playurl`，而且必须带 `ep_id` 而不是 bvid+cid。
     * 免费/限免的番剧通常能走普通链路，所以这里只在一切都失败时补一次，
     * 顺带把「付费/大会员」和「视频真的没了」区分开。
     *
     * 付费剧集也会给一条 `is_preview=1` 的 durl，但它只是前几分钟的试看片段；
     * 播放器会把它当完整曲目播，播到片段末尾就「莫名其妙」结束了，所以不采用试看流，
     * 只给出明确原因。
     */
    private fun resolvePgcDurl(bvid: String): String? {
        val viewText = http.get("https://api.bilibili.com/x/web-interface/view?bvid=$bvid", headers)
            ?: return null
        val redirect = NeriJsonParser.parse(viewText).asObject()
            ?.obj("data")?.str("redirect_url").orEmpty()
        // 只有番剧/影视的稿件才会带 bangumi/play/epXXXX 跳转
        val epId = Regex("/bangumi/play/ep(\\d+)").find(redirect)?.groupValues?.getOrNull(1)
            ?: return null
        val text = http.get(
            "https://api.bilibili.com/pgc/player/web/playurl?ep_id=$epId&fnval=16&fnver=0&fourk=1",
            headers,
        ) ?: return null
        val root = NeriJsonParser.parse(text).asObject() ?: return null
        // pgc 接口把播放数据放在 result 而不是 data
        val payload = root.obj("result") ?: root.obj("data")
        val previewOnly = payload?.let { it.bool("is_preview") == true || it.int("is_preview") == 1 } == true
        if (previewOnly) {
            BiliPlaybackDiagnostics.record(
                bvid,
                "该视频是番剧/影视内容（ep$epId），只有试看片段，需要登录或大会员才能播放完整剧集",
            )
            return null
        }
        val url = payload?.let { pickDashAudioUrl(it) ?: pickDurlUrl(it) }
        if (!url.isNullOrBlank()) return url
        BiliPlaybackDiagnostics.record(
            bvid,
            "该视频是番剧/影视内容（ep$epId），当前网络/账号拿不到播放流（多为付费或大会员专属）",
        )
        return null
    }

    /** 一次 playurl 请求的档位参数。 */
    private data class PlayUrlAttempt(
        val fnval: String,
        val fourk: String,
        val platform: String?,
    )

    /** 请求一次 playurl 并直接取出 `data`；接口失败或没有 data 时返回 null。 */
    private fun playUrlData(
        bvid: String,
        cid: String,
        attempt: PlayUrlAttempt,
    ): kotlinx.serialization.json.JsonObject? {
        val root = requestPlayUrl(bvid, cid, attempt) ?: return null
        if (root.int("code").let { it != null && it != 0 }) return null
        return root.obj("data")
    }

    /**
     * 请求 playurl：优先 WBI 签名接口，签名不可用/风控时退到明文接口。
     * 两个接口对同一组参数返回体一致，所以解析逻辑共用。
     */
    private fun requestPlayUrl(
        bvid: String,
        cid: String,
        attempt: PlayUrlAttempt,
    ): kotlinx.serialization.json.JsonObject? {
        val params = buildMap {
            put("bvid", bvid)
            put("cid", cid)
            put("fnval", attempt.fnval)
            put("fnver", "0")
            put("fourk", attempt.fourk)
            attempt.platform?.let { put("platform", it) }
            // html5 端要配合 high_quality 才给较高画质，否则只有 360P
            if (attempt.platform == "html5") put("high_quality", "1")
        }
        wbiRequest("https://api.bilibili.com/x/player/wbi/playurl", params)?.let { signed ->
            val code = signed.int("code")
            // -403 / -352 是签名失效或风控：换 key 后由上层重试，这里不再叠加明文请求
            if (code == 0 || signed.obj("data") != null) return signed
        }
        val query = params.entries.joinToString("&") { (key, value) -> "$key=${urlEncode(value)}" }
        val text = http.get("https://api.bilibili.com/x/player/playurl?$query", headers) ?: return null
        return NeriJsonParser.parse(text).asObject()
    }

    /**
     * DASH 音轨：优先普通音轨（取码率最高的一条）。
     * 少数稿件普通音轨为空，音频只出现在 `dash.flac.audio`（Hi-Res）或
     * `dash.dolby.audio`（杜比），不补这一步会误判成「没有音频流」。
     *
     * 每条流都会给出 baseUrl + 若干 backupUrl（不同 CDN），播放器只会用拿到的这一条，
     * 所以要在这里按 CDN 稳定性排好序取第一条，避免拿到偶发握手中断的 mcdn 调度域名。
     */
    private fun pickDashAudioUrl(data: kotlinx.serialization.json.JsonObject): String? {
        val dash = data.obj("dash") ?: return null
        val ordinary = dash.array("audio")?.objects().orEmpty()
            .maxByOrNull { it.long("bandwidth") ?: 0L }
            ?.let(::firstReachableUrl)
        if (!ordinary.isNullOrBlank()) return ordinary
        val flac = dash.obj("flac")?.obj("audio")?.let(::firstReachableUrl)
        if (!flac.isNullOrBlank()) return flac
        return dash.obj("dolby")?.array("audio")?.objects().orEmpty()
            .maxByOrNull { it.long("bandwidth") ?: 0L }
            ?.let(::firstReachableUrl)
    }

    /** 取一条流的主备 URL 里最优的一条；DASH 用 baseUrl/backupUrl，durl 用 url/backup_url。 */
    private fun firstReachableUrl(item: kotlinx.serialization.json.JsonObject): String? {
        val primary = item.str("baseUrl") ?: item.str("base_url") ?: item.str("url")
        val backups = (item.array("backupUrl") ?: item.array("backup_url")).orEmpty().mapNotNull { element ->
            if (element is kotlinx.serialization.json.JsonPrimitive &&
                element !is kotlinx.serialization.json.JsonNull
            ) {
                element.content
            } else {
                null
            }
        }
        return BiliStreamUrls.prioritize(primary, backups).firstOrNull()?.let(::normalizeImageUrl)
    }

    /**
     * 渐进式 MP4 回退：`durl` 数组是完整（含视频轨）的 mp4，顺序分片。
     * 只要第一片的直链：播放器按单条流播放，分片视频实际只出现在超长/特殊稿件上。
     */
    private fun pickDurlUrl(data: kotlinx.serialization.json.JsonObject): String? {
        val durl = data.array("durl")?.objects().orEmpty()
        val first = durl.firstOrNull() ?: return null
        return firstReachableUrl(first)
    }

    fun biliHeaders(): Map<String, String> = mapOf(
        "Referer" to "https://www.bilibili.com/",
        "User-Agent" to DESKTOP_USER_AGENT,
        "Origin" to "https://www.bilibili.com",
    )

    /** 登录后读取用户创建的收藏夹。 */
    fun favoriteFolders(mid: String): List<OnlineCollection> {
        if (mid.isBlank()) return emptyList()
        val text = http.get(
            "https://api.bilibili.com/x/v3/fav/folder/created/list-all?up_mid=$mid",
            headers,
        ) ?: return emptyList()
        val root = NeriJsonParser.parse(text).asObject() ?: return emptyList()
        if (root.int("code") != 0) return emptyList()
        val list = root.obj("data")?.array("list") ?: return emptyList()
        val folders = list.objects().mapNotNull { item ->
            val id = item.long("id") ?: return@mapNotNull null
            OnlineCollection(
                id = id.toString(),
                name = cleanBiliText(item.str("title")),
                creator = "哔哩哔哩收藏夹",
                // created/list-all 的 list 项不带 cover；能直接拿到时用之，缺的下面用详情接口补。
                coverUrl = normalizeImageUrl(item.str("cover")),
                trackCount = item.int("media_count") ?: 0,
                source = MediaSource.BILIBILI,
            )
        }
        // created/list-all 通常不返回封面，逐个用 folder/info 补齐；封面属于低频元数据，
        // 按 id 缓存成功结果，避免刷新页面时重复请求。请求失败只保持 null，下次刷新可重试。
        return folders.map { folder ->
            if (!folder.coverUrl.isNullOrBlank()) return@map folder
            val cached = folderInfoCovers[folder.id]
            if (!cached.isNullOrEmpty()) return@map folder.copy(coverUrl = cached)
            val info = http.get(
                "https://api.bilibili.com/x/v3/fav/folder/info?media_id=${folder.id}",
                headers,
            )
            val infoRoot = NeriJsonParser.parse(info.orEmpty()).asObject()
            val cover = if (infoRoot?.int("code") == 0) {
                normalizeImageUrl(infoRoot.obj("data")?.str("cover"))
            } else {
                null
            }
            if (cover.isNullOrBlank()) folder else {
                folderInfoCovers[folder.id] = cover
                folder.copy(coverUrl = cover)
            }
        }
    }

    /** 收藏夹封面详情缓存：id → 封面 URL（只缓存成功解析的结果）。 */
    private val folderInfoCovers = ConcurrentHashMap<String, String>()

    // ---------------------------------------------------------------- 首页音乐榜 / 热门音乐

    /**
     * B 站版权音乐榜（listType：1 热门榜 / 2 原创榜）。
     * 先取期数列表挑最新一期，再取该期曲目；两步都走公开 GET 接口，不需要 WBI 签名。
     */
    fun musicRankSongs(listType: Int, limit: Int = 20): List<Song> {
        val periodsRoot = getApiJson(
            "https://api.bilibili.com/x/copyright-music-publicity/toplist/all_period?list_type=$listType",
        ) ?: return emptyList()
        if (periodsRoot.int("code") != 0) return emptyList()
        val periods = periodsRoot.obj("data")?.obj("list") ?: return emptyList()
        var latestId = 0L
        var latestPublish = 0L
        periods.keys().forEach { year ->
            periods.array(year)?.objects().orEmpty().forEach { item ->
                val id = item.long("ID") ?: return@forEach
                val publish = item.long("publish_time") ?: 0L
                if (publish > latestPublish || (publish == latestPublish && id > latestId)) {
                    latestPublish = publish
                    latestId = id
                }
            }
        }
        if (latestId <= 0L) return emptyList()
        val songsRoot = getApiJson(
            "https://api.bilibili.com/x/copyright-music-publicity/toplist/music_list?list_id=$latestId",
        ) ?: return emptyList()
        if (songsRoot.int("code") != 0) return emptyList()
        val list = songsRoot.obj("data")?.array("list") ?: return emptyList()
        return list.objects().take(limit).mapNotNull { musicRankSong(JsonObjectSelf(it)) }
    }

    /**
     * 音乐分区（rid=3）热门视频榜。
     * Web 端分区榜已停更，只能走移动端接口；它只返回 avid，需要本地换算 bvid。
     */
    fun musicHotVideos(limit: Int = 30): List<Song> {
        val root = getApiJson(
            "https://app.bilibili.com/x/v2/rank/region?rid=3&type=all&pn=1&ps=$limit",
        ) ?: return emptyList()
        if (root.int("code") != 0) return emptyList()
        val data = root.array("data") ?: return emptyList()
        return data.objects().take(limit).mapNotNull { item ->
            val aid = item.str("param")?.trim()?.toLongOrNull() ?: return@mapNotNull null
            val bvid = biliAidToBvid(aid)
            if (bvid.isBlank()) return@mapNotNull null
            Song(
                key = biliSongKey(bvid),
                source = MediaSource.BILIBILI,
                title = cleanBiliText(item.str("title")).ifBlank { "未知视频" },
                artist = cleanBiliText(item.str("name")),
                album = "哔哩哔哩",
                durationMs = (item.long("duration") ?: 0L) * 1000L,
                remoteId = bvid,
                artworkUrl = normalizeImageUrl(item.str("cover")),
                extraId = item.long("cid")?.takeIf { it > 0L }?.toString(),
            )
        }
    }

    private fun musicRankSong(item: JsonObjectSelf): Song? {
        // 有官方 MV 时优先用 MV（音质/画面更稳定），否则回退上榜的授权投稿。
        val mvBvid = item.str("mv_bvid").orEmpty().trim()
        val creationBvid = item.str("creation_bvid").orEmpty().trim()
        val bvid = mvBvid.ifBlank { creationBvid }
        if (bvid.isBlank()) return null
        val cover = normalizeImageUrl(item.str("mv_cover"))
            ?: normalizeImageUrl(item.str("creation_cover"))
        // creation_first_cid 永远属于 creation_bvid；选中 MV 时这个 cid 与 bvid 不配套，
        // 带上它会让 playurl 直接 -404「啥都木有」。所以只有两个 bvid 相同时才复用 cid。
        val cid = item.long("creation_first_cid")
            ?.takeIf { it > 0L && mvBvid == creationBvid }
            ?.toString()
        return Song(
            key = biliSongKey(bvid),
            source = MediaSource.BILIBILI,
            title = cleanBiliText(item.str("music_title"))
                .ifBlank { cleanBiliText(item.str("creation_title")) }
                .ifBlank { "未知歌曲" },
            artist = cleanBiliText(item.str("singer")),
            album = cleanBiliText(item.str("album")),
            durationMs = (item.long("creation_duration") ?: 0L) * 1000L,
            remoteId = bvid,
            artworkUrl = cover,
            extraId = cid,
        )
    }

    private fun getApiJson(url: String): JsonObjectSelf? {
        val text = http.get(url, headers) ?: return null
        return NeriJsonParser.parse(text).asObject()?.let(::JsonObjectSelf)
    }

    /**
     * 读取收藏夹内的视频（作为可播放音频）。
     * 该接口每页最多 20 条，需要按 pn 翻页；onPage 会在每页返回时回调已累计的列表，便于界面边加载边展示。
     */
    fun favoriteFolderSongs(
        mediaId: String,
        maxItems: Int = 2000,
        onPage: (List<Song>) -> Unit = {},
    ): List<Song> {
        if (mediaId.isBlank()) return emptyList()
        ensureCookies()
        val collected = ArrayList<Song>()
        var page = 1
        while (collected.size < maxItems && page <= 100) {
            val url = "https://api.bilibili.com/x/v3/fav/resource/list" +
                "?media_id=$mediaId&pn=$page&ps=20&platform=web"
            val text = http.get(url, headers) ?: break
            val root = NeriJsonParser.parse(text).asObject() ?: break
            if (root.int("code") != 0) {
                println("[bili] 收藏夹内容读取失败：code=${root.int("code")} ${root.str("message")}")
                break
            }
            val data = root.obj("data") ?: break
            val medias = data.array("medias")?.objects().orEmpty()
            if (medias.isEmpty()) break
            medias.forEach { item ->
                favoriteMediaToSong(JsonObjectSelf(item))?.let { collected += it }
            }
            onPage(collected.toList())
            val hasMore = data.bool("has_more") ?: false
            if (!hasMore) break
            page += 1
        }
        return if (collected.size > maxItems) collected.take(maxItems) else collected
    }
}

/**
 * B 站 avid → bvid 换算（算法与官方前端一致）。
 * 移动端分区榜只给 avid，而播放链路要求 bvid；本地换算可以省去逐个视频的详情请求。
 */
private fun biliAidToBvid(aid: Long): String {
    if (aid <= 0L) return ""
    val alphabet = "FcwAPNKTMug3GV5Lj7EJnHpWsx4tb8haYeviqBz6rkCy12mUSDQX9RdoZf"
    val xorCode = 23442827791579L
    val maxAid = 1L shl 51
    val base = 58L
    val chars = "BV1000000000".toCharArray()
    var remainder = (maxAid or aid) xor xorCode
    var index = chars.size - 1
    while (remainder > 0L && index >= 3) {
        chars[index] = alphabet[(remainder % base).toInt()]
        remainder /= base
        index -= 1
    }
    chars[3] = chars[9].also { chars[9] = chars[3] }
    chars[4] = chars[7].also { chars[7] = chars[4] }
    return String(chars)
}

/** 收藏夹条目 → 可播放歌曲（duration 单位为秒，封面为 http 需转 https）。可离线单测。 */
fun favoriteMediaToSong(item: JsonObjectSelf): Song? {
    val bvid = item.str("bvid")?.takeIf { it.isNotBlank() } ?: return null
    return Song(
        key = biliSongKey(bvid),
        source = MediaSource.BILIBILI,
        title = cleanBiliText(item.str("title")).ifBlank { "未知视频" },
        artist = cleanBiliText(item.obj("upper")?.str("name")),
        album = "哔哩哔哩收藏夹",
        durationMs = (item.long("duration") ?: 0L) * 1000L,
        remoteId = bvid,
        artworkUrl = normalizeImageUrl(item.str("cover")),
    )
}
