package moe.ouom.neriplayer.desktop.net

import kotlinx.serialization.json.JsonObject
import java.util.LinkedHashSet

private const val NETEASE_WEAPI_BASE = "https://music.163.com/weapi"
private const val ADD_BATCH_SIZE = 50

/**
 * 网易云「添加歌曲到歌单」结果。
 *
 * [needsWebVerification] 表示网易云返回了 524（网页业务校验令牌）而没有接受写入：
 * 桌面端没有 WebView 抓不到该令牌，不能自动重试，必须如实告诉用户去网页版操作。
 */
data class NeteaseAddToPlaylistResult(
    val added: Int = 0,
    val alreadyExists: Int = 0,
    val failed: Int = 0,
    val code: Int? = null,
    val message: String? = null,
    val needsWebVerification: Boolean = false,
    val needLogin: Boolean = false,
) {
    val ok: Boolean get() = !needLogin && !needsWebVerification && failed == 0 && code == 200
}

/** 登录态失效或被风控拦截，交给命令层转成明确的错误提示。 */
class NeteasePlaylistApiException(val reason: Reason, override val message: String) : RuntimeException(message) {
    enum class Reason { LOGIN_REQUIRED, NETWORK }
}

/**
 * 对齐 Android `NeteaseClient.addSongsToPlaylist` + `NeteasePlaylistSync` 的写歌单能力。
 *
 * - 接口 `POST /weapi/playlist/manipulate/tracks`（weapi 加密，见 [NeteaseWeapi]）；
 * - 参数 `op=add` / `pid` / `id` / `tracks(csv)` / `trackIds(json)` / `imme=true`，
 *   csrf 从登录 Cookie 读取后拼到 query（Android `buildRequest` 同款）；
 * - 提交前用明文歌单详情拿已有 trackIds 去重，避免重复写入；
 * - 网易云 524 需要网页版 `WM.getToken` 校验令牌，桌面端拿不到，明确降级而不是假装成功。
 */
class NeteasePlaylistApi(private val http: HttpService) {

    fun isLoggedIn(): Boolean = http.cookieSnapshot("netease")["MUSIC_U"].orEmpty().isNotBlank()

    /**
     * 把网易云歌曲 id 加入指定歌单。 songs 为已确认属于网易云的曲目 id（正数）。
     * [userId] 用于确认目标歌单归当前账号所有（Android 只列 creator=自己）。
     */
    fun addToPlaylist(
        playlistId: Long,
        songIds: List<Long>,
        userId: String = "",
    ): NeteaseAddToPlaylistResult {
        val ids = songIds.asSequence().filter { it > 0L }.distinct().toList()
        if (ids.isEmpty()) {
            return NeteaseAddToPlaylistResult(code = 200, message = "没有可添加的网易云歌曲")
        }
        if (!isLoggedIn()) {
            throw NeteasePlaylistApiException(
                NeteasePlaylistApiException.Reason.LOGIN_REQUIRED,
                "请先登录网易云音乐账号",
            )
        }
        if (userId.isNotBlank() && !isOwnPlaylist(playlistId, userId)) {
            return NeteaseAddToPlaylistResult(
                code = -1,
                message = "只能添加到你自己创建的网易云歌单",
            )
        }

        val existing = existingTrackIds(playlistId)
        val pending = ids.filterNot { it in existing }
        val already = ids.size - pending.size
        if (pending.isEmpty()) {
            return NeteaseAddToPlaylistResult(
                alreadyExists = already,
                code = 200,
                message = "歌曲已在歌单中",
            )
        }

        var added = 0
        var failed = 0
        var lastCode: Int? = null
        var lastMessage: String? = null
        for (batch in pending.chunked(ADD_BATCH_SIZE)) {
            val outcome = submitBatch(playlistId, batch)
            lastCode = outcome.code
            lastMessage = outcome.message
            when {
                outcome.needLogin || outcome.needsWebVerification -> {
                    return NeteaseAddToPlaylistResult(
                        added = added,
                        alreadyExists = already,
                        failed = failed + batch.size,
                        code = outcome.code,
                        message = outcome.message,
                        needsWebVerification = outcome.needsWebVerification,
                        needLogin = outcome.needLogin,
                    )
                }
                outcome.code == 200 -> added += batch.size
                else -> failed += batch.size
            }
        }
        return NeteaseAddToPlaylistResult(
            added = added,
            alreadyExists = already,
            failed = failed,
            code = lastCode,
            message = lastMessage,
        )
    }

    /** 单批提交，返回解析后的平台状态。 */
    private fun submitBatch(playlistId: Long, ids: List<Long>): NeteaseAddToPlaylistResult {
        val params = buildAddParams(playlistId, ids)
        val form = NeteaseWeapi.encrypt(params)
        val csrf = http.cookieSnapshot("netease")["__csrf"].orEmpty()
        val url = if (csrf.isEmpty()) {
            NETEASE_WEAPI_BASE + "/playlist/manipulate/tracks"
        } else {
            "$NETEASE_WEAPI_BASE/playlist/manipulate/tracks?csrf_token=$csrf"
        }
        val text = http.postForm(
            url = url,
            form = form,
            headers = mapOf("Referer" to "https://music.163.com/"),
            timeoutSeconds = 20,
        ) ?: throw NeteasePlaylistApiException(
            NeteasePlaylistApiException.Reason.NETWORK,
            "网络请求失败，请检查网络后重试",
        )
        val root = NeriJsonParser.parse(text).asObject()
            ?: throw NeteasePlaylistApiException(
                NeteasePlaylistApiException.Reason.NETWORK,
                "网易云返回结果无法解析",
            )
        val code = root.int("code") ?: -1
        val message = root.str("message")?.takeIf { it.isNotBlank() }
        return when (code) {
            200 -> NeteaseAddToPlaylistResult(code = 200, message = message)
            301, 302, 315, 401, 403, -460 -> NeteaseAddToPlaylistResult(
                code = code,
                message = message ?: "登录态已失效，请重新登录网易云音乐账号",
                needLogin = true,
            )
            524 -> NeteaseAddToPlaylistResult(
                code = code,
                message = message ?: "网易云要求网页安全校验，请在网页版完成此操作",
                needsWebVerification = true,
            )
            else -> NeteaseAddToPlaylistResult(
                code = code,
                message = message ?: "网易云返回码 $code",
            )
        }
    }

    /** 与 Android 一致的参数形态；checkToken 桌面端拿不到，只能省略。 */
    private fun buildAddParams(playlistId: Long, ids: List<Long>): Map<String, Any> = linkedMapOf(
        "op" to "add",
        "pid" to playlistId.toString(),
        "id" to playlistId.toString(),
        "tracks" to ids.joinToString(","),
        "trackIds" to ids.joinToString(",", "[", "]"),
        "imme" to "true",
    )

    /** 读取歌单详情；失败返回 null。 */
    private fun playlistDetail(playlistId: Long): JsonObject? {
        val text = http.get(
            url = "https://music.163.com/api/v6/playlist/detail?id=$playlistId&n=0",
            headers = mapOf("Referer" to "https://music.163.com/"),
            timeoutSeconds = 15,
        ) ?: return null
        val root = NeriJsonParser.parse(text).asObject() ?: return null
        if ((root.int("code") ?: -1) != 200) return null
        return root.obj("playlist")
    }

    /** 只有自建歌单可写：creator.userId 必须等于当前账号 userId（对齐 Android）。 */
    private fun isOwnPlaylist(playlistId: Long, userId: String): Boolean {
        val creatorId = playlistDetail(playlistId)?.obj("creator")?.long("userId")?.toString().orEmpty()
        return creatorId == userId.trim()
    }

    /**
     * 读取歌单已有曲目 id 用于去重。详情接口失败时返回空集合（不阻断写入），
     * 只影响去重精度，不会误报成功。
     */
    private fun existingTrackIds(playlistId: Long): Set<Long> {
        val playlist = playlistDetail(playlistId) ?: return emptySet()
        val result = LinkedHashSet<Long>()
        playlist.array("trackIds")?.objects().orEmpty().forEach { item ->
            item.long("id")?.takeIf { it > 0L }?.let { result.add(it) }
        }
        return result
    }
}
