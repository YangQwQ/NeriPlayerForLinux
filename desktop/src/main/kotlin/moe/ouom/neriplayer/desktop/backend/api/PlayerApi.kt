package moe.ouom.neriplayer.desktop.backend.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import moe.ouom.neriplayer.desktop.backend.CoreServer
import moe.ouom.neriplayer.desktop.backend.PlayerWire
import moe.ouom.neriplayer.desktop.backend.WireJson
import moe.ouom.neriplayer.desktop.backend.badRequest
import moe.ouom.neriplayer.desktop.backend.boolOrNull
import moe.ouom.neriplayer.desktop.backend.doubleOrNull
import moe.ouom.neriplayer.desktop.backend.encodeAny
import moe.ouom.neriplayer.desktop.backend.encodeJson
import moe.ouom.neriplayer.desktop.backend.intOrNull
import moe.ouom.neriplayer.desktop.backend.longOrNull
import moe.ouom.neriplayer.desktop.backend.objectOrNull
import moe.ouom.neriplayer.desktop.backend.arrayOrNull
import moe.ouom.neriplayer.desktop.backend.stringOrNull
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.ListenTogetherManager
import moe.ouom.neriplayer.desktop.core.ListenTogetherSnapshot
import moe.ouom.neriplayer.desktop.core.PlaybackCommandOutcome
import moe.ouom.neriplayer.desktop.core.RepeatMode
import moe.ouom.neriplayer.desktop.core.Song
import moe.ouom.neriplayer.desktop.core.describeListenTogetherError
import moe.ouom.neriplayer.desktop.core.roomQueueErrorMessage
import moe.ouom.neriplayer.desktop.core.toListenTogetherTrackOrNull

/** 播放器命令。状态由 StatePublisher 通过 "player" / "player.queue" / "player.lyrics" 事件推送。 */
object PlayerApi {

    fun register(server: CoreServer, container: AppContainer) {
        val player = container.player

        fun ok() = buildJsonObject { put("ok", true) }

        server.on("player.seekTo") {
            val positionMs = it.longOrNull("positionMs") ?: badRequest("缺少 positionMs")
            // 播放控制统一走 playbackCommands：房间里是听众时由它转发给房间，
            // 否则直接本地执行（行为与改造前一致）。
            playbackReply(container.playbackCommands.seekTo(positionMs))
        }
        server.on("player.setVolume") {
            player.setVolume((it.doubleOrNull("value") ?: badRequest("缺少 value")).toFloat()); ok()
        }
        server.on("player.setShuffle") {
            player.setShuffle(it.boolOrNull("enabled") ?: badRequest("缺少 enabled")); ok()
        }
        server.on("player.toggleShuffle") { player.toggleShuffle(); ok() }
        server.on("player.setRepeatMode") {
            val mode = it.stringOrNull("mode") ?: badRequest("缺少 mode")
            player.setRepeatMode(runCatching { RepeatMode.valueOf(mode) }.getOrElse {
                badRequest("非法 repeatMode: $mode")
            }); ok()
        }
        server.on("player.cycleRepeatMode") { player.cycleRepeatMode(); ok() }
        server.on("player.setSpeed") {
            player.setSpeed((it.doubleOrNull("value") ?: badRequest("缺少 value")).toFloat()); ok()
        }
        server.on("player.setPitch") {
            player.setPitch((it.doubleOrNull("value") ?: badRequest("缺少 value")).toFloat()); ok()
        }
        server.on("player.setLoudness") {
            player.setLoudness(it.boolOrNull("enabled") ?: badRequest("缺少 enabled")); ok()
        }
        server.on("player.setEqualizerEnabled") {
            player.setEqualizerEnabled(it.boolOrNull("enabled") ?: badRequest("缺少 enabled")); ok()
        }
        server.on("player.setEqualizerPreset") {
            val name = it.stringOrNull("name") ?: badRequest("缺少 name")
            val bands = it.arrayOrNull("bands")?.map { element ->
                (element as? JsonPrimitive)?.content?.toFloatOrNull() ?: 0f
            } ?: badRequest("缺少 bands")
            player.setEqualizerPreset(name, bands); ok()
        }
        server.on("player.setEqualizerBand") {
            val index = it.intOrNull("index") ?: badRequest("缺少 index")
            val gain = (it.doubleOrNull("gain") ?: badRequest("缺少 gain")).toFloat()
            player.setEqualizerBand(index, gain); ok()
        }
        server.on("player.resetEffects") { player.resetEffects(); ok() }

        /**
         * 播放页「切换音质」：临时对当前（或指定）曲目切换在线音质档位。
         *
         * 与设置页的 `qualityPreference` 不同，这里只记在播放器的按 key 覆盖表里，不写全局
         * 偏好也不落盘。档位沿用设置页命名（standard/higher/exhigh/lossless/hires）；
         * 重新解析播放地址并从当前进度续播，解析失败返回明确错误并回滚该曲目的临时档位。
         */
        server.on("player.switchQuality") { params ->
            val quality = params.stringOrNull("quality")?.trim()?.lowercase()
                ?: badRequest("缺少 quality")
            if (quality !in setOf("standard", "higher", "exhigh", "lossless", "hires")) {
                badRequest("非法音质档位: $quality")
            }
            val target = params.objectOrNull("song")?.let { songOf(params) }
            val changed = player.switchQuality(quality, target)
            if (!changed) {
                badRequest("无法切换到该音质：当前音源不支持，或该档位需要会员/登录且解析失败")
            }
            buildJsonObject {
                put("ok", true)
                put("quality", quality)
                put("temporary", true)
            }
        }

        // 淡入淡出 / 交叉淡入：一次性传全部字段，与 AppSettings 字段名保持一致。
        server.on("player.setFadeOptions") {
            val fadeEnabled = it.boolOrNull("fadeEnabled") ?: badRequest("缺少 fadeEnabled")
            val fadeInMs = it.intOrNull("fadeInMs") ?: badRequest("缺少 fadeInMs")
            val fadeOutMs = it.intOrNull("fadeOutMs") ?: badRequest("缺少 fadeOutMs")
            val crossfadeEnabled = it.boolOrNull("crossfadeEnabled") ?: badRequest("缺少 crossfadeEnabled")
            val crossfadeMs = it.intOrNull("crossfadeMs") ?: badRequest("缺少 crossfadeMs")
            player.setFadeOptions(fadeEnabled, fadeInMs, fadeOutMs, crossfadeEnabled, crossfadeMs)
            ok()
        }

        server.on("player.playSongNow") { params ->
            val song = songOf(params)
            val queue = params.arrayOrNull("queue")?.takeIf { it.isNotEmpty() }
                ?.let { array -> WireJson.decodeFromJsonElement(
                    kotlinx.serialization.builtins.ListSerializer(Song.serializer()), array) }
            player.playSongNow(song, queue)
            ok()
        }
        server.on("player.playAt") {
            player.playAt(it.intOrNull("index") ?: badRequest("缺少 index")); ok()
        }

        /**
         * 队列命令在「一起听房间里且自己是听众」时**不直接改本地队列**：本地改动会被房间
         * 权威队列在下次同步时覆盖（表现为「加了又消失」）。这类命令统一转发成房间队列请求
         * （[ListenTogetherManager.addSongsToRoomQueue] 等），用户不需要知道自己是听众。
         *
         * 房间不允许成员控制（`member control disabled`）时仍返回错误，让前端提示
         * 「控制者关闭了听众控制，无法加入房间队列」——操作意图要说清楚为什么没生效。
         */
        fun listenerInRoom(): ListenTogetherManager? =
            container.listenTogether?.takeIf { it.isListenerInRoom() }

        /**
         * 房间转发的命令统一返回 `{ok, roomQueue}`：`roomQueue=true` 让前端知道这次操作
         * 进的是房间队列 / 由房间仲裁（成功提示要说「已添加到房间队列」而不是「已添加到下一首」）。
         * 失败时抛 ProtocolException，错误文案已是中文可读提示，前端直接 Toast。
         */
        fun listenTogetherReply(snapshot: ListenTogetherSnapshot): JsonObject {
            snapshot.error?.let { badRequest(roomQueueErrorMessage(it)) }
            return buildJsonObject {
                put("ok", true)
                put("roomQueue", true)
                snapshot.notice?.let { put("notice", it) }
            }
        }

        /** 本地下标换算不出房间下标：这首歌本身不可分享，不在房间队列里。 */
        fun notInRoomQueue(): Nothing = badRequest("这首歌不在房间队列里（本地歌曲不能加入一起听）")

        /**
         * 播放控制命令（播放/暂停、上下一首）本身不含任何房间判定：判定统一在
         * [PlaybackCommandRouter] 里做，和全局快捷键 / MPRIS / 托盘共用同一份逻辑，
         * 避免「只有一个入口支持一起听」的历史问题。
         */
        server.on("player.togglePlayPause") { playbackReply(container.playbackCommands.togglePlayPause()) }
        server.on("player.play") { playbackReply(container.playbackCommands.play()) }
        server.on("player.pause") { playbackReply(container.playbackCommands.pause()) }
        server.on("player.next") { playbackReply(container.playbackCommands.next()) }
        server.on("player.previous") { playbackReply(container.playbackCommands.previous()) }

        /**
         * 整队替换（「播放全部」/ 换一张歌单）在房间里的处理。
         *
         * 听众不能直接改本地队列：worker 的 `REQUEST_SET_QUEUE` 只接受「保留当前曲目的重排 /
         * 单曲增删」，一次替换多首会被拒绝；就算绕过去，本地队列也会在下一次房态同步时被覆盖
         * （用户看到的是「刚换的歌单又变回去」）。所以听众的整队替换降级为**逐首追加到房间
         * 队列末尾**（[ListenTogetherManager.addSongsToRoomQueue]，已带整体校验与失败回滚）。
         *
         * 降级不是用户点「播放全部」就想得到的语义，所以分两段：
         *  - 不带 `confirmAppend` 时只做预检，返回 `needsConfirm` 与实际可追加/需跳过的数量，
         *    前端据此弹确认框；
         *  - 用户确认后再带 `confirmAppend=true` 调回来，才真正改房间队列。
         * 非房间场景与控制者身份完全不进这条分支，行为与提示都不变。
         *
         * `mode="replace"` 是 Android 听众「播放全部」的语义：带完整新队列发
         * `REQUEST_SET_TRACK`，服务端仲裁后所有成员一起切歌（worker 允许 SET_TRACK 携带
         * 队列写入房态）。`mode="append"`（默认）保留上述两段式降级追加逻辑。
         *
         * `autoPlay` 在降级路径下不生效：房间里谁放哪首由控制者决定，追加不打断当前播放
         * （确认框也是这么向用户承诺的）。等房间队列走到这些歌时会自然播到。
         */
        server.on("player.setQueue") { params ->
            val songs = songsOf(params)
            val startIndex = params.intOrNull("startIndex") ?: 0
            val autoPlay = params.boolOrNull("autoPlay") ?: true
            val confirmAppend = params.boolOrNull("confirmAppend") ?: false
            val mode = params.stringOrNull("mode")?.trim()?.lowercase() ?: "append"
            if (mode !in setOf("append", "replace")) badRequest("非法 setQueue mode: $mode")
            val listenTogether = listenerInRoom()
            if (listenTogether == null) {
                player.setQueue(songs, startIndex, autoPlay)
                return@on ok()
            }
            // 空列表在房间里是 no-op：听众没有清空房间队列的权限，别让用户以为清掉了。
            if (songs.isEmpty()) return@on buildJsonObject { put("ok", true); put("roomQueue", true) }
            // 本地歌曲 / 缺远端 ID 的歌进不了房间：先过滤掉，避免整批请求因为一首本地歌全被拒绝。
            val shareable = songs.filter { it.toListenTogetherTrackOrNull() != null }
            val skipped = songs.size - shareable.size
            if (shareable.isEmpty()) {
                badRequest("这份歌单里没有可以加入一起听房间的歌曲（本地歌曲无法加入一起听）")
            }
            if (mode == "replace") {
                // 对齐 Android 听众的「播放全部」：带完整新队列的 REQUEST_SET_TRACK，
                // 由服务端仲裁并广播给所有成员（worker 允许 SET_TRACK 携带 queue 写入房态）。
                // 请求发出后房态会被权威版本覆盖，本地队列无需先改。
                val tracks = shareable.mapNotNull { it.toListenTogetherTrackOrNull() }
                val targetIndex = startIndex.coerceIn(0, tracks.lastIndex)
                val snapshot = listenTogether.replaceRoomQueue(tracks, targetIndex, autoPlay)
                snapshot.error?.let { raw ->
                    val reason = describeListenTogetherError(raw)
                    // 替换失败要说清是「替换」没生效，不能沿用追加路径的「无法加入房间队列」
                    badRequest(when (reason) {
                        "控制者关闭了听众控制" -> "控制者关闭了听众控制，无法替换房间队列"
                        "控制者不在线" -> "控制者不在线，无法替换房间队列"
                        else -> reason
                    })
                }
                return@on buildJsonObject {
                    put("ok", true)
                    put("roomQueue", true)
                    put("replaced", tracks.size)
                    put("skipped", skipped)
                    snapshot.notice?.let { put("notice", it) }
                }
            }
            if (!confirmAppend) {
                return@on buildJsonObject {
                    put("ok", true)
                    put("roomQueue", true)
                    put("needsConfirm", true)
                    put("appendable", shareable.size)
                    put("skipped", skipped)
                }
            }
            val snapshot = listenTogether.addSongsToRoomQueue(shareable, playNext = false)
            snapshot.error?.let { badRequest(roomQueueErrorMessage(it)) }
            buildJsonObject {
                put("ok", true)
                put("roomQueue", true)
                put("degraded", true)
                put("appended", shareable.size)
                put("skipped", skipped)
                snapshot.notice?.let { put("notice", it) }
            }
        }

        server.on("player.enqueue") { params ->
            val songs = songsOf(params)
            val listenTogether = listenerInRoom()
            if (listenTogether == null) {
                player.enqueue(songs)
                ok()
            } else {
                // 听众：加到房间队尾，房间广播回来后由 applyRoomStateToPlayer 同步本地队列
                listenTogetherReply(listenTogether.addSongsToRoomQueue(songs, playNext = false))
            }
        }
        server.on("player.enqueueNext") { params ->
            val songs = songsOf(params)
            val listenTogether = listenerInRoom()
            if (listenTogether == null) {
                player.enqueueNext(songs)
                ok()
            } else {
                // 听众：插到房间当前曲目之后（与本地 enqueueNext 语义一致），不是队尾
                listenTogetherReply(listenTogether.addSongsToRoomQueue(songs, playNext = true))
            }
        }
        server.on("player.removeFromQueue") {
            val index = it.intOrNull("index") ?: badRequest("缺少 index")
            val listenTogether = listenerInRoom()
            if (listenTogether == null) {
                player.removeFromQueue(index)
                ok()
            } else {
                // 传入的是本地队列下标，房间队列是可分享曲目的子集，先换算
                val roomIndex = listenTogether.localQueueIndexToRoomIndex(index)
                if (roomIndex < 0) notInRoomQueue() else
                    listenTogetherReply(listenTogether.removeLocalIndexFromRoomQueue(roomIndex))
            }
        }
        server.on("player.moveInQueue") {
            val from = it.intOrNull("from") ?: badRequest("缺少 from")
            val to = it.intOrNull("to") ?: badRequest("缺少 to")
            val listenTogether = listenerInRoom()
            if (listenTogether == null) {
                player.moveInQueue(from, to)
                ok()
            } else {
                val fromRoom = listenTogether.localQueueIndexToRoomIndex(from)
                val toRoom = listenTogether.localQueueIndexToRoomIndex(to)
                if (fromRoom < 0 || toRoom < 0) notInRoomQueue() else
                    listenTogetherReply(listenTogether.moveInRoomQueue(fromRoom, toRoom))
            }
        }
        server.on("player.clearQueue") { player.clearQueue(); ok() }

        server.on("player.startSleepTimer") {
            player.startSleepTimer(it.intOrNull("minutes") ?: badRequest("缺少 minutes")); ok()
        }
        server.on("player.stopAfterCurrentSong") { player.stopAfterCurrentSong(); ok() }
        server.on("player.cancelSleepTimer") { player.cancelSleepTimer(); ok() }
        server.on("player.reloadLyrics") { player.reloadLyrics(); ok() }

        server.on("player.state") { PlayerWire.state(player) }
        server.on("player.queue") { PlayerWire.queue(player) }
    }

    /**
     * 把 [moe.ouom.neriplayer.desktop.core.PlaybackCommandRouter] 的执行结果翻译成命令响应。
     *
     * 房间里是听众并转发成功时返回 `{ok, roomQueue:true, notice?}`：`roomQueue=true` 让前端知道
     * 这次操作由房间仲裁；转发被拒绝时抛 ProtocolException，`message` 已带动作名（如「控制者
     * 关闭了听众控制，无法切歌」），前端直接 Toast。
     */
    private fun playbackReply(outcome: PlaybackCommandOutcome): JsonObject = when (outcome) {
        PlaybackCommandOutcome.Local -> buildJsonObject { put("ok", true) }
        is PlaybackCommandOutcome.RoomForwarded -> buildJsonObject {
            put("ok", true)
            put("roomQueue", true)
            outcome.notice?.let { put("notice", it) }
        }
        is PlaybackCommandOutcome.Failed -> badRequest(outcome.message)
    }

    private fun songsOf(params: JsonObject): List<Song> {
        val songs = params.arrayOrNull("songs") ?: badRequest("缺少 songs")
        return runCatching {
            WireJson.decodeFromJsonElement(
                kotlinx.serialization.builtins.ListSerializer(Song.serializer()),
                songs,
            )
        }.getOrElse { badRequest("songs 格式非法: ${it.message}") }
    }

    private fun songOf(params: JsonObject): Song {
        val song = params.objectOrNull("song") ?: badRequest("缺少 song")
        return runCatching { WireJson.decodeFromJsonElement(Song.serializer(), song) }
            .getOrElse { badRequest("song 格式非法: ${it.message}") }
    }
}
