package moe.ouom.neriplayer.desktop.core

import kotlin.math.abs

/**
 * 播放控制意图（[PlaybackCommandOutcome.Local] / [PlaybackCommandOutcome.RoomForwarded] /
 * [PlaybackCommandOutcome.Failed]）的执行结果。
 *
 * 同步入口（托盘、媒体键、全局快捷键）没有 IPC 响应可回给前端，只需要判断成败；
 * IPC 命令层则要把它翻译成 `{ok}` / `{ok, roomQueue, notice}` / 协议错误。
 */
sealed interface PlaybackCommandOutcome {

    /** 不在房间里，或自己是房间控制者：已由本地播放器执行。 */
    data object Local : PlaybackCommandOutcome

    /** 房间里是听众：已转发成房间控制请求，[notice] 是服务端回执里的提示文案。 */
    data class RoomForwarded(val notice: String? = null) : PlaybackCommandOutcome

    /** 房间里是听众但请求被拒绝；[message] 已是可直接展示的中文文案。 */
    data class Failed(val message: String) : PlaybackCommandOutcome
}

/**
 * 播放控制的**唯一入口**。
 *
 * 「播放 / 暂停 / 上一首 / 下一首 / 拖动进度」这类*控制意图*有多个触发源：QML 按钮与应用内
 * 快捷键（走 IPC 命令）、全局快捷键（xdg portal）、MPRIS 媒体键、托盘菜单。它们原先各自直接
 * 调 [PlayerManager]，于是「一起听房间里当听众要转发给房间」这条分支只写在了 IPC 命令层，
 * 其余入口绕过去直接改本地播放器——本地播放器随后被权威房态同步覆盖，用户看到的是
 * 「按了下一首又跳回来」「按了暂停又自己播」。
 *
 * 所以判定只在这里做一次：
 *  - 房间里且自己是听众 → 转发成房间控制请求（[ListenTogetherManager.requestNextInRoom] 等）；
 *  - 其余情况（不在房间 / 自己是控制者）→ 直接调本地播放器，行为与改造前完全一致。
 *
 * 新增入口（未来可能还有桌面快捷面板、通知操作按钮）直接调本类，不需要再抄一遍这段分支。
 *
 * [ListenTogetherManager] 是 [AppContainer] 里稍后回填的 `@Volatile var`（要等 ListenTogetherApi
 * 拿到 broadcast 回调才能创建），因此每次调用都重新读取，不在构造期缓存。
 */
class PlaybackCommandRouter(private val container: AppContainer) {

    private companion object {
        /** 与房间进度相差在这个范围内视为「对齐到房间」，不再向房间发 seek 请求。 */
        const val ROOM_ALIGN_TOLERANCE_MS = 1_200L
    }

    suspend fun togglePlayPause(): PlaybackCommandOutcome {
        val manager = listenerInRoom() ?: return local { container.player.togglePlayPause() }
        // 按**房间**播放态决定发 play 还是 pause：听众的本地播放器可能正处在被同步覆盖的
        // 中间态，用本地状态判断会把「暂停」发成「播放」。
        val roomPlaying = manager.roomIsPlaying()
        return forward(if (roomPlaying) "pause" else "play") {
            if (roomPlaying) manager.requestPauseInRoom() else manager.requestPlayInRoom()
        }
    }

    suspend fun play(): PlaybackCommandOutcome {
        val manager = listenerInRoom() ?: return local { container.player.play() }
        return forward("play") { manager.requestPlayInRoom() }
    }

    suspend fun pause(): PlaybackCommandOutcome {
        val manager = listenerInRoom() ?: return local { container.player.pause() }
        return forward("pause") { manager.requestPauseInRoom() }
    }

    suspend fun next(): PlaybackCommandOutcome {
        val manager = listenerInRoom() ?: return local { container.player.next() }
        return forward("next") { manager.requestNextInRoom() }
    }

    suspend fun previous(): PlaybackCommandOutcome {
        val manager = listenerInRoom() ?: return local { container.player.previous() }
        return forward("previous") { manager.requestPreviousInRoom() }
    }

    /**
     * 拖动进度条 / 快进快退。
     *
     * 听众在房间里的进度由控制者仲裁，本地 seek 会被下一次房态同步拉回去，所以默认转发
     * （`requestControl("seek", positionMs)`，服务端对应 REQUEST_SEEK）。
     *
     * 例外：目标位置就是房间当前所在位置时不转发。房间面板的「对齐到房间进度」按钮复用的正是
     * `player.seekTo(房间进度)`——那本来就只是把本地追上去，不需要房间仲裁；若转发成请求，
     * 在「控制者关闭了听众控制」的房间里会得到一条错误提示且对齐失败。
     */
    suspend fun seekTo(positionMs: Long): PlaybackCommandOutcome {
        val manager = listenerInRoom() ?: return local { container.player.seekTo(positionMs) }
        val roomPositionMs = manager.snapshot().playback.positionMs
        if (abs(positionMs - roomPositionMs) <= ROOM_ALIGN_TOLERANCE_MS) {
            return local { container.player.seekTo(positionMs) }
        }
        return forward("seek", ::seekErrorMessage) {
            manager.requestControl(
                action = "seek",
                positionMs = positionMs.coerceAtLeast(0L),
                repeatMode = null,
                shuffleEnabled = null,
            )
        }
    }

    /**
     * 相对进度调整（快进 / 快退 / MPRIS Seek）。
     *
     * 基准位置要按身份取：房间里**听众**的本地播放器正被软校正慢慢追房间进度，
     * 用它算出来的目标位置会带上漂移量；房间播放态的位置才是用户看到的那个进度。
     */
    suspend fun seekBy(deltaMs: Long): PlaybackCommandOutcome {
        val manager = listenerInRoom()
        val base = manager?.snapshot()?.playback?.positionMs ?: container.player.positionMs.value
        return seekTo((base + deltaMs).coerceAtLeast(0L))
    }

    private fun listenerInRoom(): ListenTogetherManager? =
        container.listenTogether?.takeIf { it.isListenerInRoom() }

    private inline fun local(execute: () -> Unit): PlaybackCommandOutcome {
        execute()
        return PlaybackCommandOutcome.Local
    }

    private suspend fun forward(
        action: String,
        errorMessage: (String?) -> String = { listenTogetherErrorMessage(it, action) },
        request: suspend () -> ListenTogetherSnapshot,
    ): PlaybackCommandOutcome {
        val snapshot = request()
        val error = snapshot.error ?: return PlaybackCommandOutcome.RoomForwarded(snapshot.notice)
        return PlaybackCommandOutcome.Failed(errorMessage(error))
    }

    /**
     * seek 的失败文案。
     *
     * [listenTogetherErrorMessage] 只认 next/previous/play/pause，其它 action 会落到队列类
     * 措辞上（「无法加入房间队列」），对「拖了一下进度条」是错的说法，所以这里单独映射。
     */
    private fun seekErrorMessage(raw: String?): String =
        when (val text = describeListenTogetherError(raw)) {
            "控制者关闭了听众控制" -> "控制者关闭了听众控制，无法调整播放进度"
            "控制者不在线" -> "控制者不在线，无法调整播放进度"
            "只有控制者可以执行该操作" -> "只有控制者可以调整播放进度"
            else -> text
        }
}
