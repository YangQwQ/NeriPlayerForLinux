package moe.ouom.neriplayer.desktop.net

import kotlinx.serialization.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.desktop.core.AudioInput
import moe.ouom.neriplayer.desktop.core.Lyrics
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.OnlineCollection
import moe.ouom.neriplayer.desktop.core.SearchKind
import moe.ouom.neriplayer.desktop.core.SearchPayload
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.core.buildLrcLyrics
import moe.ouom.neriplayer.desktop.core.buildYrcLyrics
import moe.ouom.neriplayer.desktop.core.toListenTogetherTrackOrNull

@Serializable
data class NeteaseHomeData(
    val recommendedSongs: List<Song> = emptyList(),
    val topSongs: List<Song> = emptyList(),
    val newSongs: List<Song> = emptyList(),
    val radarPlaylists: List<OnlineCollection> = emptyList(),
    val hotPlaylists: List<OnlineCollection> = emptyList(),
    val error: String? = null,
)

/** 在线音源聚合：搜索 / 播放地址 / 歌词 / 首页推荐。 */
class OnlineRepository(private val http: HttpService = HttpService()) {

    /** 暴露给账号仓库复用同一份 Cookie 与连接设置。 */
    val httpService: HttpService get() = http

    /** 直链解析时最多尝试的分 P 数量（超长合集逐个试会变成上百次请求）。 */
    private val MAX_BILI_PAGE_ATTEMPTS = 3

    // ---- 一起听权威流：听众优先使用控制者上传的直链 ----

    /** 当前共享流对应的 stableKey；null 表示没有任何共享流。访问都在此对象的 synchronized 内。 */
    private var sharedStreamStableKey: String? = null
    private var sharedStreamUrls: List<String> = emptyList()

    /**
     * 设置房间共享直链。由 ListenTogetherManager 收到含 streamUrl 的权威房态时调用；
     * [stableKey] 为空或 [urls] 全空表示清除共享流，回退本地解析。
     */
    fun setSharedStreamUrls(stableKey: String?, urls: List<String>) {
        synchronized(this) {
            val valid = stableKey?.takeIf { it.isNotBlank() }
            val normalized = urls.map { it.trim() }.filter { it.startsWith("http://", true) || it.startsWith("https://", true) }.distinct()
            if (valid == null || normalized.isEmpty()) {
                sharedStreamStableKey = null
                sharedStreamUrls = emptyList()
            } else {
                sharedStreamStableKey = valid
                sharedStreamUrls = normalized
            }
        }
    }

    /** 当前是否对用户给定的曲目存有可用共享直链。 */
    fun hasSharedStreamFor(song: Song): Boolean {
        val key = song.toListenTogetherTrackOrNull()?.stableKey ?: return false
        return synchronized(this) { sharedStreamStableKey == key && sharedStreamUrls.isNotEmpty() }
    }

    /**
     * 把直链解析结果转成可播放的 [AudioInput]，headers 按来源对齐本地解析：
     * 网易云走 Referer/User-Agent，B 站走 biliHeaders（Referer/User-Agent/Origin），
     * 否则给一个通用 User-Agent。这样共享流的鉴权与本地解析一致。
     */
    private fun sharedStreamInput(song: Song, url: String): AudioInput = when (song.source) {
        MediaSource.NETEASE -> AudioInput(
            url = url,
            headers = mapOf(
                "Referer" to "https://music.163.com/",
                "User-Agent" to DESKTOP_USER_AGENT,
            ),
        )
        MediaSource.BILIBILI -> AudioInput(url = url, headers = bilibili.biliHeaders())
        else -> AudioInput(url = url, headers = mapOf("User-Agent" to DESKTOP_USER_AGENT))
    }

    /** 收藏夹封面接口不返回封面，加载过内容后把首条封面缓存下来供列表复用。 */
    private val _collectionCovers = kotlinx.coroutines.flow.MutableStateFlow<Map<String, String>>(emptyMap())
    val collectionCovers: kotlinx.coroutines.flow.StateFlow<Map<String, String>> = _collectionCovers

    fun coverFor(collection: OnlineCollection): String? =
        collection.coverUrl ?: _collectionCovers.value[collection.id]

    val netease = NeteaseApi(http)
    val bilibili = BiliApi(http)

    /** YouTube Music（Cookie 存在共享的 [HttpService] `youtube` 罐里）。 */
    val youtube = YouTubeMusicClient(http)

    suspend fun search(
        source: MediaSource,
        kind: SearchKind,
        query: String,
        page: Int = 1,
    ): SearchPayload = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext SearchPayload()
        when (source) {
            MediaSource.NETEASE -> when (kind) {
                SearchKind.SONG -> SearchPayload(songs = netease.searchSongs(query, offset = (page - 1) * 30))
                SearchKind.PLAYLIST -> SearchPayload(collections = netease.searchPlaylists(query))
                SearchKind.ARTIST -> SearchPayload(artists = netease.searchArtists(query))
                SearchKind.ALBUM -> SearchPayload(collections = netease.searchAlbums(query))
            }

            MediaSource.BILIBILI -> SearchPayload(songs = bilibili.searchVideos(query, page))

            MediaSource.YOUTUBE -> when (kind) {
                SearchKind.SONG -> SearchPayload(
                    songs = youtube.search(query, YouTubeMusicClient.SEARCH_FILTER_SONGS)
                        ?.let { YouTubeMusicParser.parseSearchSongs(it) }.orEmpty(),
                )

                SearchKind.PLAYLIST -> SearchPayload(
                    collections = youtube.search(query, YouTubeMusicClient.SEARCH_FILTER_PLAYLISTS)
                        ?.let { YouTubeMusicParser.parseSearchPlaylists(it) }.orEmpty(),
                )

                SearchKind.ARTIST -> SearchPayload(
                    artists = youtube.search(query, YouTubeMusicClient.SEARCH_FILTER_ARTISTS)
                        ?.let { YouTubeMusicParser.parseSearchArtists(it) }.orEmpty(),
                )

                SearchKind.ALBUM -> SearchPayload(
                    collections = youtube.search(query, YouTubeMusicClient.SEARCH_FILTER_ALBUMS)
                        ?.let { YouTubeMusicParser.parseSearchAlbums(it) }.orEmpty(),
                )
            }

            MediaSource.LOCAL -> SearchPayload()
        }
    }

    suspend fun resolvePlayback(song: Song, quality: String): AudioInput? = withContext(Dispatchers.IO) {
        // 一起听权威流：控制者已上传当前曲目的直链时优先用它，不再自己解析在线音源。
        // 注意：这里在下载/磁盘缓存之后——已缓存音频始终是可用的本地副本，没必要退回到远端直链。
        val shared = synchronized(this) {
            val key = song.toListenTogetherTrackOrNull()?.stableKey
            if (key != null && key == sharedStreamStableKey) sharedStreamUrls.firstOrNull() else null
        }
        if (shared != null) return@withContext sharedStreamInput(song, shared)
        when (song.source) {
            MediaSource.LOCAL -> song.filePath?.let { AudioInput(path = it) }
            MediaSource.NETEASE -> {
                val id = song.remoteId ?: return@withContext null
                val resolved = netease.songUrl(id, quality) ?: return@withContext null
                AudioInput(
                    url = resolved.url,
                    headers = mapOf(
                        "Referer" to "https://music.163.com/",
                        "User-Agent" to DESKTOP_USER_AGENT,
                    ),
                )
            }

            MediaSource.BILIBILI -> {
                val rawId = song.remoteId?.takeIf { it.isNotBlank() }
                if (rawId == null) {
                    BiliPlaybackDiagnostics.record(song.key, "曲目缺少视频 ID，无法解析播放地址")
                    return@withContext null
                }
                // 一起听房间里 Android 端的曲目只带纯数字 aid（stableKey `bilibili:<aid>`），
                // 而 playurl 只认 bvid：先用 view 接口换一次，否则必然解析失败。
                val resolved = if (rawId.startsWith("BV", ignoreCase = true)) {
                    null
                } else {
                    bilibili.resolveByAid(rawId)
                }
                if (resolved == null && !rawId.startsWith("BV", ignoreCase = true)) {
                    val reason = if (rawId.startsWith("au", ignoreCase = true)) {
                        "这是 B 站音频区内容（$rawId），桌面版暂不支持播放"
                    } else {
                        "无法从 id「$rawId」找到对应视频（不是有效的 aid 或 bvid）"
                    }
                    BiliPlaybackDiagnostics.record(rawId, reason)
                    return@withContext null
                }
                val bvid = resolved?.first ?: rawId
                // 一起听房间里 aid 形式曲目的 extraId 可能只是把 audioId（aid）原样带过来，
                // 那不是 cid，拿去查 playurl 只会多一次 -404。
                val extraCid = song.extraId?.takeIf { it.isNotBlank() && it.trim() != rawId.trim() }
                val url = resolveBiliPlaybackUrl(bvid, extraCid, resolved?.second)
                    ?: return@withContext null
                AudioInput(url = url, headers = bilibili.biliHeaders())
            }

            MediaSource.YOUTUBE -> {
                val videoId = song.remoteId?.takeIf { it.isNotBlank() } ?: return@withContext null
                val audio = youtube.resolveAudio(videoId, quality) ?: return@withContext null
                AudioInput(
                    url = audio.url,
                    headers = mapOf(
                        "User-Agent" to audio.userAgent.ifBlank { DESKTOP_USER_AGENT },
                        "Referer" to YouTubeMusicClient.MUSIC_ORIGIN + "/",
                        "Origin" to YouTubeMusicClient.MUSIC_ORIGIN,
                    ),
                )
            }
        }
    }

    /**
     * 选出与 [bvid] 配套的 cid 并解析直链。
     *
     * `extraId` 来自上游（音乐榜、一起听房间、收藏夹），**不保证属于这个 bvid**：
     * - 音乐榜的 `creation_first_cid` 配的是 `creation_bvid`，而榜单优先取 `mv_bvid`，
     *   两者不配套时 playurl 会直接 -404「啥都木有」；
     * - 一起听房间里的数字 ID 既可能是 cid，也可能是 aid（当 cid 用必然 -404）。
     *
     * 所以 [extraCid] 与 [aidCid] 都可能是错的：依次各试一次，都不行再用该视频自己的
     * 分 P 列表兜底，而不是把一个不配套的 cid 一路带下去直接失败。
     */
    private fun resolveBiliPlaybackUrl(bvid: String, extraCid: String?, aidCid: String?): String? {
        val tried = LinkedHashSet<String>()
        // 先试调用方给的 cid：它指定的就是「要播的那一分 P」，换成别的 P 会播错内容。
        // 它不是本视频的 cid 时（音乐榜、房间里的 aid）playurl 会直接 -404，再往下兜底。
        val direct = extraCid?.takeIf { it.toLongOrNull() != null }
        if (direct != null) {
            tried += direct
            bilibili.audioUrl(bvid, direct)?.let { return it }
        }
        val fromAid = aidCid?.takeIf { it.toLongOrNull() != null && it != direct }
        if (fromAid != null) {
            tried += fromAid
            bilibili.audioUrl(bvid, fromAid)?.let { return it }
        }
        // 上面都失败：拉一次分 P 列表，用视频自己的 cid 再试（多 P 用第一 P）。
        // 只试前几个：超长合集（实测有 164 P）逐个试会变成上百次网络往返。
        val pages = bilibili.videoPages(bvid)
        if (pages.isEmpty()) {
            // 保留 audioUrl 给出的更具体原因（-404 / 试看 / 地区限制），只在没有时才落这条
            if (BiliPlaybackDiagnostics.failureFor(bvid) == null) {
                BiliPlaybackDiagnostics.record(bvid, "读不到视频分 P 信息（视频可能已被删除或需要登录）")
            }
            return null
        }
        pages.take(MAX_BILI_PAGE_ATTEMPTS).forEach { (cid, _) ->
            if (cid in tried) return@forEach
            bilibili.audioUrl(bvid, cid)?.let { return it }
        }
        return null
    }

    /**
     * 某个曲目最近一次哔哩哔哩解析失败的原因（成功解析后为 null）。
     *
     * 供命令层在 [resolvePlayback] 返回 null 时把原因透给前端：界面上统一显示
     * 「无法解析播放地址」没有任何诊断价值，而这里的文案能区分「视频不存在」「只有试看片段」
     * 「番剧需要会员」「音频区内容不支持」等情况。
     */
    fun biliFailureReason(song: Song): String? {
        if (song.source != MediaSource.BILIBILI) return null
        val id = song.remoteId?.takeIf { it.isNotBlank() }
        return BiliPlaybackDiagnostics.failureFor(id) ?: BiliPlaybackDiagnostics.failureFor(song.key)
    }

    suspend fun lyrics(song: Song): Pair<String, String?>? = withContext(Dispatchers.IO) {
        when (song.source) {
            MediaSource.NETEASE -> song.remoteId?.let { netease.lyric(it) }
            MediaSource.BILIBILI -> null
            MediaSource.LOCAL -> localLyricFallback(song)
            MediaSource.YOUTUBE -> youtubeLyrics(song.remoteId.orEmpty())
        }
    }

    /**
     * 结构化歌词：网易云优先用 eapi 的逐词 yrc，取不到再回退普通 LRC。
     * 与 [lyrics] 的区别是这里直接产出可序列化的 [Lyrics]（带 words / endMs）。
     */
    suspend fun lyricsStructured(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        when (song.source) {
            MediaSource.NETEASE -> neteaseStructuredLyrics(song.remoteId)
            MediaSource.BILIBILI -> null
            MediaSource.LOCAL -> localLyricFallbackStructured(song)
            MediaSource.YOUTUBE -> youtubeLyrics(song.remoteId.orEmpty())
                ?.let { (raw, translated) -> buildLrcLyrics(raw, translated, song.source.displayName) }
        }
    }

    /** 网易云结构化歌词：yrc → 普通 LRC 的完整回退，任何失败都不影响普通歌词。 */
    private fun neteaseStructuredLyrics(remoteId: String?): Lyrics? {
        val id = remoteId?.takeIf { it.isNotBlank() } ?: return null
        val source = MediaSource.NETEASE.displayName
        val payload = netease.lyricPayload(id)
        val yrc = payload?.yrc
        val romanized = payload?.romanized?.takeIf { it.isNotBlank() }
        if (!yrc.isNullOrBlank()) {
            buildYrcLyrics(
                yrcRaw = yrc,
                lrcRaw = payload.lrc,
                translatedYrc = payload.yrcTranslated,
                translatedRaw = payload.translated,
                source = source,
                romanizedRaw = romanized,
            )?.let { return it }
        }
        // 普通 LRC 优先用 eapi 的 lrc；没有时回退明文接口（同时带 romalrc 音译）
        val plainPayload = netease.lyricPlainPayload(id)
        val plain = payload?.lrc?.takeIf { it.isNotBlank() }?.let { it to payload.translated }
            ?: plainPayload?.lrc?.takeIf { it.isNotBlank() }?.let { it to plainPayload.translated }
            ?: netease.lyric(id)
            ?: return null
        // eapi 没带音译时用明文接口的 romalrc 补齐
        val romanizedFinal = romanized ?: plainPayload?.romanized?.takeIf { it.isNotBlank() }
        return buildLrcLyrics(plain.first, plain.second, source, romanizedRaw = romanizedFinal)
    }

    /** 本地歌曲没有歌词文件时在线补全；有 yrc 的同样走逐词解析。 */
    private fun localLyricFallbackStructured(song: Song): Lyrics? {
        val candidate = neteaseLyricCandidate(song) ?: return null
        if (!candidate.remoteId.isNullOrBlank()) {
            neteaseStructuredLyrics(candidate.remoteId)?.let { return it }
        }
        return localLyricFallback(song)
            ?.let { (raw, translated) -> buildLrcLyrics(raw, translated, song.source.displayName) }
    }

    /** 「标题 + 歌手」搜索一首最匹配的网易云歌曲（本地歌词在线补全用）。 */
    private fun neteaseLyricCandidate(song: Song): Song? {
        val title = song.title.ifBlank { return null }
        val query = if (song.artist.isBlank()) title else "${song.title} ${song.artist}"
        return netease.searchSongs(query, limit = 5).firstOrNull { match ->
            match.title.contains(title, ignoreCase = true) || title.contains(match.title, ignoreCase = true)
        }
    }

    /** YouTube 歌词：next 接口拿到歌词页 browseId 再 browse（返回纯文本，无 LRC 时间轴）。 */
    private fun youtubeLyrics(videoId: String): Pair<String, String?>? {
        if (videoId.isBlank()) return null
        val next = youtube.next(videoId) ?: return null
        val browseId = YouTubeMusicParser.parseLyricsBrowseId(next) ?: return null
        val text = youtube.browse(browseId)?.let { YouTubeMusicParser.parseLyrics(it) } ?: return null
        return text.takeIf { it.isNotBlank() }?.let { it to "YouTube Music" }
    }

    /** 本地歌曲没有歌词文件时，用「标题 + 歌手」在线补全。 */
    private fun localLyricFallback(song: Song): Pair<String, String?>? {
        val candidate = neteaseLyricCandidate(song) ?: return null
        val id = candidate.remoteId ?: return null
        return netease.lyric(id)
    }

    suspend fun neteaseHome(): NeteaseHomeData = withContext(Dispatchers.IO) {
        runCatching {
            val recommended = netease.searchSongs("热门歌曲", limit = 18)
            // 榜单详情只请求一次，热歌榜 id 与首页榜单列表复用同一结果，省去两次网络往返
            val toplists = netease.toplists()
            val topListId = toplists.firstOrNull { it.name.contains("热歌") }?.id
                ?: toplists.firstOrNull()?.id
            // 首页只展示前几首：限制抓取数量，避免为榜单歌单拉取全部曲目
            val topSongs = topListId?.let { netease.playlistDetail(it, limit = 30)?.second }?.take(18).orEmpty()
            val newSongs = netease.recommendedNewSongs(18)
            val radar = netease.recommendedPlaylists(12)
            val hot = toplists.take(12)
            NeteaseHomeData(
                recommendedSongs = recommended,
                topSongs = topSongs,
                newSongs = newSongs,
                radarPlaylists = radar,
                hotPlaylists = hot,
            )
        }.getOrElse { error ->
            NeteaseHomeData(error = error.message ?: error.javaClass.simpleName)
        }
    }

    suspend fun playlistSongs(collection: OnlineCollection): List<Song> = withContext(Dispatchers.IO) {
        when (collection.source) {
            MediaSource.NETEASE -> netease.playlistDetail(collection.id)?.second.orEmpty()
            MediaSource.BILIBILI -> bilibili.favoriteFolderSongs(collection.id)
            MediaSource.YOUTUBE -> youtubeCollectionSongs(collection.id)
            else -> emptyList()
        }
    }

    /**
     * 按来源加载歌单 / 收藏夹内容；onPage 用于边加载边刷新界面。
     * 网易云可能是歌单也可能是专辑，因此歌单为空时回退到专辑接口。
     */
    suspend fun songsForCollection(
        collection: OnlineCollection,
        onPage: (List<Song>) -> Unit = {},
    ): List<Song> = withContext(Dispatchers.IO) {
        when (collection.source) {
            MediaSource.NETEASE -> {
                val detail = netease.playlistDetail(collection.id)
                if (!detail?.second.isNullOrEmpty()) {
                    detail!!.second
                } else {
                    netease.albumDetail(collection.id)?.second.orEmpty()
                }
            }

            MediaSource.BILIBILI -> bilibili.favoriteFolderSongs(collection.id, onPage = onPage)
            MediaSource.YOUTUBE -> youtubeCollectionSongs(collection.id)
            else -> emptyList()
        }
    }.also { loaded ->
        if (collection.coverUrl.isNullOrBlank()) {
            loaded.firstOrNull()?.artworkUrl?.let { cover ->
                _collectionCovers.value = _collectionCovers.value + (collection.id to cover)
            }
        }
    }

    suspend fun albumSongs(collection: OnlineCollection): List<Song> = withContext(Dispatchers.IO) {
        when (collection.source) {
            MediaSource.NETEASE -> netease.albumDetail(collection.id)?.second.orEmpty()
            MediaSource.YOUTUBE -> youtubeAlbumSongs(collection.id)
            else -> emptyList()
        }
    }

    suspend fun artistSongs(artistId: String): List<Song> = withContext(Dispatchers.IO) {
        netease.artistTopSongs(artistId)
    }

    /** 指定音源的歌手热门歌曲（目前只有 YouTube 需要区分来源）。 */
    suspend fun artistSongs(source: MediaSource, artistId: String): List<Song> = withContext(Dispatchers.IO) {
        when (source) {
            MediaSource.YOUTUBE -> youtubeArtist(artistId)?.songs.orEmpty()
            else -> artistSongs(artistId)
        }
    }

    /** YouTube 歌手详情（供在线音源聚合命令复用）。 */
    suspend fun youtubeArtist(artistId: String) = withContext(Dispatchers.IO) {
        val root = youtube.browse(artistId) ?: return@withContext null
        YouTubeMusicParser.parseArtist(root, artistId, fallbackName = "")
    }

    /** YouTube 歌单 / 专辑内容：browseId 前缀区分（MPRE=专辑，VL=歌单）。 */
    private fun youtubeCollectionSongs(id: String): List<Song> {
        if (id.isBlank()) return emptyList()
        return when {
            id.startsWith("MPRE") -> youtubeAlbumSongs(id)
            else -> {
                val browseId = if (id.startsWith("VL")) id else "VL$id"
                val root = youtube.browse(browseId) ?: return emptyList()
                YouTubeMusicParser.parsePlaylist(root, browseId, fallbackTitle = "").songs
            }
        }
    }

    private fun youtubeAlbumSongs(id: String): List<Song> {
        val browseId = if (id.startsWith("MPRE")) id else "MPRE$id"
        val root = youtube.browse(browseId) ?: return emptyList()
        val detail = YouTubeMusicParser.parseAlbum(root, browseId, fallbackTitle = "")
        if (detail.songs.isNotEmpty()) return detail.songs
        // 有些专辑 id 实际是音频播放列表，回退按歌单取
        val playlistBrowseId = if (id.startsWith("VL")) id else "VL$id"
        val fallbackRoot = youtube.browse(playlistBrowseId) ?: return emptyList()
        return YouTubeMusicParser.parsePlaylist(fallbackRoot, playlistBrowseId, fallbackTitle = "").songs
    }
}
