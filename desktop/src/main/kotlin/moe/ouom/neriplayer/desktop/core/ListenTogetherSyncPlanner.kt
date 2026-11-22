package moe.ouom.neriplayer.desktop.core

import kotlin.math.abs

/**
 * 一起听播放同步规划器 —— Android `.../playback/sync/ListenTogetherPlayerSyncPlanner.kt` 的 1:1 移植。
 *
 * 这是同步的**唯一决策点**：一次性产出「要不要重建队列 / 定位到哪 / 要不要 seek / 要不要
 * play / 要不要 pause」。桌面端历史上把这些判断散落在 `applyRoomStateToPlayer`、
 * `correctListenerPlayback`、`reconcileListenerDrift` 三处，各打各的补丁
 * （`suppressLocalUntilMs` / `pendingSeekSample` / 前瞻 / 速率迟滞），越修越乱。这里把它们
 * 收敛成一个纯函数：无播放器、无网络、无线程依赖，阈值全部参数化，便于逐字段对照 Android。
 *
 * 字段命名与 Android 保持一致，中文注释说明每个字段对应的 Android 语义与桌面端取值来源。
 * 阈值常量见 [ListenTogetherSyncThresholds]（抄自 Android `ListenTogetherSessionManager` companion）。
 */

// ---------------------------------------------------------------- 阈值常量（Android 原值）

/**
 * Android `ListenTogetherSessionManager` companion 里的阈值原值，集中在这里便于对照。
 *
 * 对应关系（Android 常量 → 本对象）：
 *  - `PLAYING_DRIFT_FORCE_SYNC_MS`            → [playingDriftForceSyncMs]
 *  - `HEARTBEAT_DRIFT_FORCE_SYNC_MS`          → [heartbeatDriftForceSyncMs]
 *  - `PAUSED_DRIFT_FORCE_SYNC_MS`             → [pausedDriftForceSyncMs]
 *  - `TRACK_SWITCH_FORCE_SYNC_MS`             → [trackSwitchForceSyncMs]
 *  - `TRACK_SWITCH_GRACE_PERIOD_MS`           → [trackSwitchGracePeriodMs]
 *  - `SOFT_SYNC_MIN_DRIFT_MS`                 → [softSyncMinDriftMs]
 *  - `SOFT_SYNC_FAST_DRIFT_MS`                → [softSyncFastDriftMs]
 *  - `UNEXPECTED_ZERO_POSITION_ROLLBACK_GUARD_MS` → [zeroPositionRollbackGuardMs]
 */
internal object ListenTogetherSyncThresholds {
    /** 播放中偏差超过它 → 硬 seek。Android 值 2500ms。 */
    const val PLAYING_DRIFT_FORCE_SYNC_MS = 2_500L
    /** HEARTBEAT 触发的硬 seek 阈值（更宽松）。Android 值 5 * SECOND = 5000ms。 */
    const val HEARTBEAT_DRIFT_FORCE_SYNC_MS = 5_000L
    /** 暂停态偏差超过它 → 硬 seek。Android 值 800ms。 */
    const val PAUSED_DRIFT_FORCE_SYNC_MS = 800L
    /** 切歌/重建队列时的强制定位门槛。Android 值 500ms。 */
    const val TRACK_SWITCH_FORCE_SYNC_MS = 500L
    /** 切歌宽限期：期内 passive 事件不做位置纠正。Android 值 800ms。 */
    const val TRACK_SWITCH_GRACE_PERIOD_MS = 800L
    /** 软同步最小偏差。Android 值 600ms。 */
    const val SOFT_SYNC_MIN_DRIFT_MS = 600L
    /** 软同步「快档」分界。Android 值 1500ms。 */
    const val SOFT_SYNC_FAST_DRIFT_MS = 1_500L
    /** 归零保护门槛：本地进度已超过它才认为「期望 0」是异常回退。Android 值 2 * SECOND = 2000ms。 */
    const val ZERO_POSITION_ROLLBACK_GUARD_MS = 2_000L
}

/**
 * `passivePositionUpdateTypes`：只对这些事件启用归零保护与切歌宽限期。
 * 抄自 Android `.../control/ListenTogetherControlEventTypes.kt`。
 * 主动控制事件（SEEK / SET_TRACK / PLAY / PAUSE …）不在此列，必须立即生效。
 */
internal val passivePositionUpdateTypes = setOf(
    "HEARTBEAT",
    "WATCHDOG",
    "WATCHDOG_STALL",
    "LINK_READY",
    "LINK_UNAVAILABLE",
    "MEMBER_JOINED",
    "MEMBER_LEFT",
)

/** 需强制定位的特殊 cause（对齐 Android `LISTEN_TOGETHER_LISTENER_SAFETY_RESUME_CAUSE`）。 */
internal const val CAUSE_LISTENER_SAFETY_RESUME = "LISTENER_SAFETY_RESUME"

/** 规划器的输入。字段一一对应 Android `ListenTogetherPlayerSyncContext`。 */
internal data class ListenTogetherPlayerSyncContext(
    /** Android `playbackContextChanged`：播放上下文（队列/音源）是否变化 → 需要重建。 */
    val playbackContextChanged: Boolean,
    /** Android `targetIndexChanged`：目标曲目下标是否变化 → 需要重建。 */
    val targetIndexChanged: Boolean,
    /** Android `desiredPlaying`：房间期望的播放状态（state.playback.state == "playing"）。 */
    val desiredPlaying: Boolean,
    /** Android `localPlaying`：本地播放器当前是否播放中。 */
    val localPlaying: Boolean,
    /** Android `localPlaybackAlreadyStarting`：本地是否已在启动播放（playWhenReady），用于暂停态取消待启动。 */
    val localPlaybackAlreadyStarting: Boolean,
    /** Android `awaitingAuthoritativeStream`：是否正在等共享直链，等待时不发 play。 */
    val awaitingAuthoritativeStream: Boolean,
    /** Android `expectedPositionMs`：房间期望位置（已投影到此刻）。 */
    val expectedPositionMs: Long,
    /** Android `localPositionMs`：本地播放器当前位置（>=0）。 */
    val localPositionMs: Long,
    /** Android `ignoreUnexpectedZeroPositionRollback`：归零保护命中时用本地位置当期望位置。 */
    val ignoreUnexpectedZeroPositionRollback: Boolean,
    /** Android `trackSwitchGracePeriodActive`：切歌宽限期是否生效（期内 passive 更新不纠正位置）。 */
    val trackSwitchGracePeriodActive: Boolean = false,
    /** Android `forcePositionSync`：安全恢复等场景强制 seek。 */
    val forcePositionSync: Boolean = false,
    /** Android `causeType`：触发这次同步的事件类型（HEARTBEAT 用宽松阈值等）。 */
    val causeType: String?,
    /** Android `trackSwitchForceSyncMs`。 */
    val trackSwitchForceSyncMs: Long,
    /** Android `heartbeatDriftForceSyncMs`。 */
    val heartbeatDriftForceSyncMs: Long,
    /** Android `playingDriftForceSyncMs`。 */
    val playingDriftForceSyncMs: Long,
    /** Android `pausedDriftForceSyncMs`。 */
    val pausedDriftForceSyncMs: Long,
)

/** 规划器的输出。字段一一对应 Android `ListenTogetherPlayerSyncPlan`。 */
internal data class ListenTogetherPlayerSyncPlan(
    /** Android `shouldReloadPlaylist`：是否重建队列（只有它为真才 setQueue）。 */
    val shouldReloadPlaylist: Boolean,
    /** Android `effectiveExpectedPositionMs`：实际采用的期望位置（归零保护/宽限期可能改写成 local）。 */
    val effectiveExpectedPositionMs: Long,
    /** Android `signedDriftMs`：effectiveExpected - local；正数本地落后（要加速）。 */
    val signedDriftMs: Long,
    /** Android `driftMs`：abs(signedDriftMs)。 */
    val driftMs: Long,
    /** Android `shouldSeek`：是否硬定位。 */
    val shouldSeek: Boolean,
    /** Android `shouldIssuePlay`：是否下发 play。 */
    val shouldIssuePlay: Boolean,
    /** Android `shouldIssuePause`：是否下发 pause。 */
    val shouldIssuePause: Boolean,
    /** Android `shouldForcePauseAfterRemoteLoad`：远端重建后是否强制暂停。 */
    val shouldForcePauseAfterRemoteLoad: Boolean,
    /** Android `desiredPlaying`：透传房间期望播放态。 */
    val desiredPlaying: Boolean,
    /** Android `localPlaying`：透传本地播放态。 */
    val localPlaying: Boolean,
)

/**
 * Android `resolveListenTogetherPlayerSyncPlan()`，逻辑逐行照搬。
 */
internal fun resolveListenTogetherPlayerSyncPlan(
    context: ListenTogetherPlayerSyncContext,
): ListenTogetherPlayerSyncPlan {
    val shouldReloadPlaylist = context.playbackContextChanged || context.targetIndexChanged
    val shouldDeferPassiveTrackSwitchSync =
        context.trackSwitchGracePeriodActive &&
            context.causeType in passivePositionUpdateTypes
    val effectiveExpectedPositionMs = resolveExpectedPosition(context, shouldDeferPassiveTrackSwitchSync)
    val signedDriftMs = effectiveExpectedPositionMs - context.localPositionMs
    val driftMs = abs(signedDriftMs)
    val shouldSeek = shouldSeek(
        context, shouldReloadPlaylist, shouldDeferPassiveTrackSwitchSync, effectiveExpectedPositionMs, driftMs,
    )
    return ListenTogetherPlayerSyncPlan(
        shouldReloadPlaylist = shouldReloadPlaylist,
        effectiveExpectedPositionMs = effectiveExpectedPositionMs,
        signedDriftMs = signedDriftMs,
        driftMs = driftMs,
        shouldSeek = shouldSeek,
        shouldIssuePlay = shouldIssuePlay(context, shouldReloadPlaylist),
        shouldIssuePause = shouldIssuePause(context, shouldReloadPlaylist),
        shouldForcePauseAfterRemoteLoad = !context.desiredPlaying && shouldReloadPlaylist,
        desiredPlaying = context.desiredPlaying,
        localPlaying = context.localPlaying,
    )
}

/** Android `resolveExpectedPosition()`：归零保护或宽限期命中时改用本地位置当期望值。 */
private fun resolveExpectedPosition(
    context: ListenTogetherPlayerSyncContext,
    deferPassiveSync: Boolean,
): Long = if (context.ignoreUnexpectedZeroPositionRollback || deferPassiveSync) {
    context.localPositionMs
} else {
    context.expectedPositionMs
}

/** Android `shouldSeek()`。 */
private fun shouldSeek(
    context: ListenTogetherPlayerSyncContext,
    reloadPlaylist: Boolean,
    deferPassiveSync: Boolean,
    expectedPositionMs: Long,
    driftMs: Long,
): Boolean {
    if (context.forcePositionSync) return true
    if (deferPassiveSync) return false
    if (reloadPlaylist) return expectedPositionMs > 0L || driftMs > context.trackSwitchForceSyncMs
    return driftMs > driftThreshold(context)
}

/** Android `driftThreshold()`。 */
private fun driftThreshold(context: ListenTogetherPlayerSyncContext): Long {
    if (context.causeType == "HEARTBEAT" && context.desiredPlaying) return context.heartbeatDriftForceSyncMs
    return if (context.desiredPlaying) context.playingDriftForceSyncMs else context.pausedDriftForceSyncMs
}

/**
 * 桌面版 `shouldIssuePlay()`：与 Android 的唯一差异是不用 `awaitingAuthoritativeStream` 拦截 play。
 *
 * Android 的 ExoPlayer 把「提交音源」和「playWhenReady」合成一次原子操作，等权威流时可以
 * 完全不发 play。桌面播放器是 `playCurrent()` 异步解析 + `engine.play()` 两步：等流期间若
 * 恰好有一次重建/引擎错误把播放打断，这里再被 awaitStream 挡住，就再也没有任何路径会
 * 重新下发 play —— 现场是房间在播、本地 state 却一直是 PAUSED/IDLE、进度停在 0。
 * 共享流到达后的重载本身会 `playCurrent()` 把声音开出来，无需靠这个标志去重。
 */
private fun shouldIssuePlay(context: ListenTogetherPlayerSyncContext, reloadPlaylist: Boolean): Boolean =
    context.desiredPlaying && !context.localPlaying &&
        (!reloadPlaylist || shouldResumeAfterReload(context))

/** Android `shouldResumeAfterReload()`。 */
private fun shouldResumeAfterReload(context: ListenTogetherPlayerSyncContext): Boolean =
    context.causeType == "LINK_READY"

/** Android `shouldIssuePause()`。 */
private fun shouldIssuePause(context: ListenTogetherPlayerSyncContext, reloadPlaylist: Boolean): Boolean =
    !context.desiredPlaying && (reloadPlaylist || context.localPlaying || context.localPlaybackAlreadyStarting)

/**
 * Android `shouldIgnoreListenTogetherUnexpectedZeroPositionRollback()`，逻辑逐行照搬。
 *
 * 归零保护：passive 事件 + 房间期望播放中 + 期望位置≈0 + 本地进度已经跑过 guard + 非切歌，
 * 就判定这个「0」是异常回退（典型是 heartbeat 携带的陈旧 expectedPosition），必须忽略，
 * 否则听众会被硬 seek 回 0 —— 这正是用户报的「拖进度条没收到确认直接跳 0」。
 */
internal fun shouldIgnoreUnexpectedZeroPositionRollback(
    causeType: String?,
    desiredPlaying: Boolean,
    expectedPositionMs: Long,
    localPositionMs: Long,
    playbackContextChanged: Boolean,
    targetIndexChanged: Boolean,
    zeroPositionRollbackGuardMs: Long,
): Boolean {
    if (causeType !in passivePositionUpdateTypes) return false
    if (!desiredPlaying) return false
    if (expectedPositionMs > 0L) return false
    if (localPositionMs < zeroPositionRollbackGuardMs) return false
    if (playbackContextChanged || targetIndexChanged) return false
    return true
}
