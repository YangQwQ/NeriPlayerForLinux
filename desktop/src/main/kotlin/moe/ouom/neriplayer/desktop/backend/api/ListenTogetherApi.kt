package moe.ouom.neriplayer.desktop.backend.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import moe.ouom.neriplayer.desktop.backend.CoreServer
import moe.ouom.neriplayer.desktop.backend.arrayOrNull
import moe.ouom.neriplayer.desktop.backend.badRequest
import moe.ouom.neriplayer.desktop.backend.boolOrNull
import moe.ouom.neriplayer.desktop.backend.encodeJson
import moe.ouom.neriplayer.desktop.backend.intOrNull
import moe.ouom.neriplayer.desktop.backend.longOrNull
import moe.ouom.neriplayer.desktop.backend.objectOrNull
import moe.ouom.neriplayer.desktop.backend.stringOrNull
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.AppLog
import moe.ouom.neriplayer.desktop.core.ListenTogetherControlEvent
import moe.ouom.neriplayer.desktop.core.ListenTogetherJson
import moe.ouom.neriplayer.desktop.core.ListenTogetherManager
import moe.ouom.neriplayer.desktop.core.ListenTogetherRoomSettings
import moe.ouom.neriplayer.desktop.core.ListenTogetherSnapshot
import moe.ouom.neriplayer.desktop.core.ListenTogetherTrack
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.core.parseListenTogetherInvite
import moe.ouom.neriplayer.desktop.core.toListenTogetherTrackOrNull
import moe.ouom.neriplayer.desktop.net.redactListenTogetherUrl
import kotlinx.serialization.builtins.ListSerializer

private const val API_TAG = "lt"

/**
 * 一起听（Listen Together）命令。
 *
 * 注册方式（BackendMain.kt，由集成方添加）：
 *   ListenTogetherApi.register(server, container)
 *
 * 状态通过 listenTogether.state 事件推送，命令返回值是同一份合并快照。
 * 协议文档：desktop/docs/protocol-listen-together.md
 */
object ListenTogetherApi {

    fun register(server: CoreServer, container: AppContainer) {
        val manager = ListenTogetherManager(container) { event, data -> server.broadcast(event, data) }
        manager.start()
        // 回填给容器：PlayerApi 据此判断「房间听众」，拦截会被同步覆盖的本地队列直改。
        container.listenTogether = manager

        fun reply(snapshot: ListenTogetherSnapshot): JsonElement = encodeJson(snapshot)

        server.on("listenTogether.status") { reply(manager.snapshot()) }

        server.on("listenTogether.configure") { params ->
            val serverUrl = params.stringOrNull("serverUrl")
            AppLog.info(API_TAG, "API listenTogether.configure：serverUrl=${serverUrl?.let { redactListenTogetherUrl(it) } ?: "沿用"} nickname=${params.stringOrNull("nickname") ?: "沿用"}")
            reply(
                manager.configure(
                    serverUrl = serverUrl,
                    nickname = params.stringOrNull("nickname"),
                ),
            )
        }

        server.on("listenTogether.createRoom") { params ->
            AppLog.info(API_TAG, "API listenTogether.createRoom：nickname=${params.stringOrNull("nickname") ?: "默认"}")
            reply(manager.createRoom(params.stringOrNull("nickname")))
        }

        server.on("listenTogether.joinRoom") { params ->
            val target = params.stringOrNull("inviteOrRoomId")?.trim()
                ?: params.stringOrNull("roomId")?.trim()
                ?: badRequest("缺少 inviteOrRoomId")
            // raw 可能是带 joinSecret 的邀请链接：只记解析出的 roomId；
            // 解析不出时就记长度和"非邀请链接"，绝不把原文拼进日志（即便 AppLog 会脱敏）。
            val invite = parseListenTogetherInvite(target)
            val roomIdForLog = invite?.roomId
                ?: if (target.matches(Regex("^[A-Za-z0-9]{1,12}$"))) target.uppercase() else "<非邀请链接,长度${target.length}>"
            AppLog.info(API_TAG, "API listenTogether.joinRoom：roomId=$roomIdForLog input=${if (invite != null) "邀请链接" else "房间号"} nickname=${params.stringOrNull("nickname") ?: "默认"}")
            reply(manager.joinRoom(target, params.stringOrNull("nickname")))
        }

        server.on("listenTogether.leaveRoom") {
            AppLog.info(API_TAG, "API listenTogether.leaveRoom")
            reply(manager.leaveRoom())
        }

        server.on("listenTogether.requestControl") { params ->
            val action = params.stringOrNull("action")
                ?: params.stringOrNull("type")
                ?: badRequest("缺少 action")
            AppLog.info(API_TAG, "API listenTogether.requestControl：action=$action pos=${params.longOrNull("positionMs") ?: "-"} index=${params.intOrNull("index") ?: "-"}")
            val repeatMode = params.intOrNull("repeatMode")
                ?: params.stringOrNull("repeatMode")?.let(::repeatModeValue)
            val shuffleEnabled = params.boolOrNull("shuffleEnabled")
                ?: params.stringOrNull("shuffleEnabled")?.let { it == "true" }
            val (requestedTrack, trackError) = parseRequestedTrack(params)
            if (trackError != null) {
                // 曲目解析失败时不出队列请求，直接把原因写进快照 error 返回
                AppLog.warn(API_TAG, "API listenTogether.requestControl 拒绝：曲目解析失败 —— $trackError")
                reply(manager.failWith(trackError))
            } else {
                reply(
                    manager.requestControl(
                        action = action,
                        positionMs = params.longOrNull("positionMs"),
                        repeatMode = repeatMode,
                        shuffleEnabled = shuffleEnabled,
                        requestedTrack = requestedTrack,
                        requestedIndex = params.intOrNull("index"),
                        fromIndex = params.intOrNull("fromIndex"),
                        toIndex = params.intOrNull("toIndex"),
                    ),
                )
            }
        }

        server.on("listenTogether.sendControl") { params ->
            AppLog.info(API_TAG, "API listenTogether.sendControl：type=${params.stringOrNull("type") ?: "-"}")
            reply(manager.sendControlEvent(parseControlEvent(params)))
        }

        server.on("listenTogether.updateSettings") { params ->
            AppLog.info(API_TAG, "API listenTogether.updateSettings：allowMemberControl=${params.boolOrNull("allowMemberControl") ?: "-"} shareAudioLinks=${params.boolOrNull("shareAudioLinks") ?: "-"}")
            reply(
                manager.updateSettings(
                    allowMemberControl = params.boolOrNull("allowMemberControl"),
                    autoPauseOnMemberChange = params.boolOrNull("autoPauseOnMemberChange"),
                    shareAudioLinks = params.boolOrNull("shareAudioLinks"),
                ),
            )
        }
    }

    /** repeatMode 同时接受 0/1/2 与 OFF/ONE/ALL 字符串。 */
    private fun repeatModeValue(raw: String): Int? = when (raw.trim().uppercase()) {
        "OFF", "0", "NONE" -> 0
        "ONE", "SINGLE", "1" -> 1
        "ALL", "LIST", "2" -> 2
        else -> null
    }

    /**
     * `listenTogether.requestControl` 的 `track` 参数同时接受两种形状：
     *  - 房间曲目（`{"stableKey":"netease:123",...}`，即 `listenTogether.state.queue[]` 的元素）；
     *  - 桌面歌曲（`{"key":"netease:123","source":"NETEASE",...}`，即 `online.search` / `player.queue` 的元素）。
     *
     * 两种都归一成 [ListenTogetherTrack]，前端不必自己转换。
     * 返回的第二个值是失败原因（本地歌曲无法进入房间），用于给前端明确提示。
     */
    private fun parseRequestedTrack(params: JsonObject): Pair<ListenTogetherTrack?, String?> {
        val element = params.objectOrNull("track") ?: return null to null
        if (element["stableKey"] != null) {
            val track = runCatching {
                ListenTogetherJson.decodeFromJsonElement(ListenTogetherTrack.serializer(), element)
            }.getOrNull()
            return track to null
        }
        val song = runCatching {
            ListenTogetherJson.decodeFromJsonElement(Song.serializer(), element)
        }.getOrNull() ?: return null to "track 格式非法"
        val track = song.toListenTogetherTrackOrNull()
        return if (track == null) {
            null to "该歌曲不能加入一起听（本地歌曲或缺少远端 ID）"
        } else {
            track to null
        }
    }

    private fun parseControlEvent(params: JsonObject): ListenTogetherControlEvent {
        val type = params.stringOrNull("type") ?: badRequest("缺少 type")
        val track = params.objectOrNull("track")?.let { element ->
            runCatching {
                ListenTogetherJson.decodeFromJsonElement(ListenTogetherTrack.serializer(), element)
            }.getOrElse { badRequest("track 格式非法: ${it.message}") }
        }
        val queue = params.arrayOrNull("queue")?.let { array ->
            runCatching {
                ListenTogetherJson.decodeFromJsonElement(ListSerializer(ListenTogetherTrack.serializer()), array)
            }.getOrElse { badRequest("queue 格式非法: ${it.message}") }
        }
        val roomSettings = params.objectOrNull("roomSettings")?.let { element ->
            runCatching {
                ListenTogetherJson.decodeFromJsonElement(ListenTogetherRoomSettings.serializer(), element)
            }.getOrElse { badRequest("roomSettings 格式非法: ${it.message}") }
        }
        return ListenTogetherControlEvent(
            type = type,
            eventId = params.stringOrNull("eventId"),
            positionMs = params.longOrNull("positionMs"),
            currentIndex = params.intOrNull("currentIndex"),
            nextIndex = params.intOrNull("nextIndex"),
            track = track,
            queue = queue,
            roomSettings = roomSettings,
            shouldPlay = params.boolOrNull("shouldPlay"),
            state = params.stringOrNull("state"),
            repeatMode = params.intOrNull("repeatMode"),
            shuffleEnabled = params.boolOrNull("shuffleEnabled"),
            requestTrackStableKey = params.stringOrNull("requestTrackStableKey"),
            forceRefresh = params.boolOrNull("forceRefresh"),
            finishedTrackStableKey = params.stringOrNull("finishedTrackStableKey"),
        )
    }
}
