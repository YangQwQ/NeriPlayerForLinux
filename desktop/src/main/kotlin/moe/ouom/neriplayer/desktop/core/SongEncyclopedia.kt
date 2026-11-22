package moe.ouom.neriplayer.desktop.core

import kotlinx.serialization.Serializable

/**
 * 歌曲百科的聚合数据，字段与 Android 的 `SongEncyclopedia` 对齐。
 *
 * [songKey] 是请求时歌曲的稳定标识，前端据此校验返回是否仍对应当前歌曲，
 * 避免快速切歌时旧请求覆盖新歌。[fetchedAtMs] 为后端取数时间。
 */
@Serializable
data class SongEncyclopedia(
    val songKey: String,
    val source: MediaSource = MediaSource.NETEASE,
    val basicInfo: EncyclopediaBasicInfo,
    val musicInfo: EncyclopediaMusicInfo? = null,
    val credits: EncyclopediaCredits? = null,
    val description: String? = null,
    val awards: List<EncyclopediaAward> = emptyList(),
    val appearances: List<String> = emptyList(),
    val publishTimeMs: Long? = null,
    val albumCompany: String? = null,
    val albumId: Long? = null,
    val albumPicUrl: String? = null,
    val artistId: Long? = null,
    val artist: EncyclopediaArtistInfo? = null,
    val similarSongs: List<EncyclopediaSimilarSong> = emptyList(),
    val relatedPlaylists: List<EncyclopediaRelatedPlaylist> = emptyList(),
    val fetchedAtMs: Long = 0L,
)

/** 基础信息，曲名/歌手来自当前歌曲，专辑取歌曲详情（缺失时回退当前歌曲专辑名）。 */
@Serializable
data class EncyclopediaBasicInfo(
    val name: String,
    val artists: List<String> = emptyList(),
    val album: String? = null,
)

/** 音乐属性。BPM 与语种只在数据源明确给出时填充，禁止推断。 */
@Serializable
data class EncyclopediaMusicInfo(
    val genres: List<String> = emptyList(),
    val bpm: Int? = null,
    val language: String? = null,
    val tags: List<String> = emptyList(),
    val instruments: List<String> = emptyList(),
)

/** 创作团队。[otherRoles] 承载作词/作曲/编曲/制作人以外的角色。 */
@Serializable
data class EncyclopediaCredits(
    val lyricists: List<String> = emptyList(),
    val composers: List<String> = emptyList(),
    val arrangers: List<String> = emptyList(),
    val producers: List<String> = emptyList(),
    val otherRoles: List<EncyclopediaCreditRole> = emptyList(),
)

@Serializable
data class EncyclopediaCreditRole(
    val role: String,
    val names: List<String>,
)

@Serializable
data class EncyclopediaAward(
    val title: String,
    val detail: String? = null,
)

/** 相似歌曲，全部来自 `/api/song/play/about/block/page` 的相似歌曲板块。 */
@Serializable
data class EncyclopediaSimilarSong(
    val songId: Long,
    val name: String,
    val artist: String,
    val coverUrl: String? = null,
    val albumName: String? = null,
    val durationMs: Long? = null,
)

/** 相关歌单。接口只给播放量；歌曲数不提供，恒为 null。 */
@Serializable
data class EncyclopediaRelatedPlaylist(
    val playlistId: Long,
    val name: String,
    val coverUrl: String? = null,
    val playCount: Long? = null,
    val trackCount: Int? = null,
)

/** 艺人简要信息。粉丝数接口不可用，恒为 null。 */
@Serializable
data class EncyclopediaArtistInfo(
    val artistId: Long,
    val name: String,
    val picUrl: String? = null,
    val briefDesc: String? = null,
    val fansCount: Long? = null,
    val albumSize: Int? = null,
    val musicSize: Int? = null,
)
