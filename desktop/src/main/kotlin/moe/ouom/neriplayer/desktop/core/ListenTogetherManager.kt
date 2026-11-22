package moe.ouom.neriplayer.desktop.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import moe.ouom.neriplayer.desktop.net.ListenTogetherHttpClient
import moe.ouom.neriplayer.desktop.net.ListenTogetherWebSocketClient
import moe.ouom.neriplayer.desktop.net.redactControlEventJson
import moe.ouom.neriplayer.desktop.net.redactListenTogetherUrl
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.math.abs

/**
 * 一起听会话管理器。
 *
 * 职责：
 *  - 维护配置与会话（AppDirs.dataDir/listen_together.json），按需创建/加入/离开房间；
 *  - 用 HTTP 完成 create/join/state/leave/control，用 java.net.http.WebSocket 接收
 *    welcome / room_state_updated / control_result 等消息；
 *  - 作为控制者时把本地播放变化上报为控制事件（SET_TRACK/SET_QUEUE/PLAY/PAUSE/SEEK/
 *    PLAYBACK_MODE/HEARTBEAT），并定期发 HEARTBEAT 与 np_ping；
 *  - 收到权威房态时把播放/暂停/seek/切歌/队列/循环/随机应用到 container.player，
 *    应用期间加抑制标记，避免本地变化再次上报造成回环。
 *
 * 协议对照与不支持项见 desktop/docs/protocol-listen-together.md。
 */
class ListenTogetherManager(
    private val container: AppContainer,
    configFile: File = File(AppDirs.dataDir, "listen_together.json"),
    private val broadcast: (String, JsonElement) -> Unit,
) {
    private companion object {
        const val TAG = "listenTogether"
        const val EVENT_STATE = "listenTogether.state"
        /** 听众队列请求的 action 名（经 listenTogether.requestControl 暴露给前端）。 */
        const val ACTION_ADD_TO_QUEUE = "addToQueue"
        const val ACTION_REMOVE_FROM_QUEUE = "removeFromQueue"
        const val ACTION_MOVE_IN_QUEUE = "moveInQueue"
        const val TICK_INTERVAL_MS = 400L
        const val HEARTBEAT_PLAYING_MS = 8_000L
        const val HEARTBEAT_PAUSED_MS = 15_000L
        const val PING_INTERVAL_MS = 12_000L
        const val STATE_REFRESH_SILENCE_MS = 35_000L
        const val LOCAL_SEEK_TOLERANCE_PLAYING_MS = 2_500L
        const val LOCAL_SEEK_TOLERANCE_PAUSED_MS = 900L
        const val CONTROL_RESULT_TIMEOUT_MS = 4_000L
        const val REMOTE_APPLY_SUPPRESS_MS = 2_500L
        /** 听众队列请求的乐观窗口：超时仍未见服务端确认就交还给权威房态。 */
        const val QUEUE_REQUEST_PENDING_MS = 10_000L
        /** 听众 REQUEST_LINK 的本地节流：与 worker 的服务端节流叠加，避免反复请求同一失效直链。 */
        const val LINK_REQUEST_THROTTLE_MS = 4_000L
        /** 控制者 LINK_READY 的重发间隔：直链未变化时不重复上传。 */
        const val LINK_PUBLISH_MIN_INTERVAL_MS = 1_000L
        /**
         * 听众播放意图兜底的有效期。
         *
         * worker 提交控制事件后再构造 applied 回执，窗口内房态可能被控制者新事件覆盖；
         * 回执的 applied.state 因此可能带着「当前房态」而不是「我这次请求的播放态」。
         * 这条兜底只在请求发出后短时间内有效，且只在回执不比本地已见版本更新时生效。
         */
        const val PENDING_PLAYBACK_INTENT_TTL_MS = 4_000L
        const val MAX_RECONNECT_DELAY_MS = 30_000L
        const val MAX_QUEUE_SIZE = LISTEN_TOGETHER_MAX_QUEUE_SIZE
        /** 服务端 eventClientInstanceId 的校验规则（worker.js L1110）。 */
        val CLIENT_INSTANCE_ID_PATTERN = Regex("^[A-Za-z0-9:_-]{1,80}$")

        // ---- 同步阈值：全部抄自 Android ListenTogetherSessionManager companion，
        //      集中在 ListenTogetherSyncThresholds，这里保留别名兼容原有引用。----

        /** 偏差小于它就完全不动（人的听觉/显示都看不出这档偏差）。Android SOFT_SYNC_MIN_DRIFT_MS。 */
        const val SOFT_SYNC_MIN_DRIFT_MS = ListenTogetherSyncThresholds.SOFT_SYNC_MIN_DRIFT_MS

        /** 软校正的「快档」分界：超过它用 ±5%，否则用 ±3%。Android SOFT_SYNC_FAST_DRIFT_MS。 */
        const val SOFT_SYNC_FAST_DRIFT_MS = ListenTogetherSyncThresholds.SOFT_SYNC_FAST_DRIFT_MS

        /** 播放中的硬同步阈值。Android PLAYING_DRIFT_FORCE_SYNC_MS。 */
        const val HARD_SYNC_DRIFT_MS = ListenTogetherSyncThresholds.PLAYING_DRIFT_FORCE_SYNC_MS

        /** 心跳触发的硬同步阈值。Android HEARTBEAT_DRIFT_FORCE_SYNC_MS。 */
        const val HEARTBEAT_HARD_SYNC_DRIFT_MS = ListenTogetherSyncThresholds.HEARTBEAT_DRIFT_FORCE_SYNC_MS

        /** 暂停态的硬同步阈值。Android PAUSED_DRIFT_FORCE_SYNC_MS。 */
        const val PAUSED_HARD_SYNC_DRIFT_MS = ListenTogetherSyncThresholds.PAUSED_DRIFT_FORCE_SYNC_MS

        /** 切歌重建时的强制定位门槛。Android TRACK_SWITCH_FORCE_SYNC_MS。 */
        const val TRACK_SWITCH_FORCE_SYNC_MS = ListenTogetherSyncThresholds.TRACK_SWITCH_FORCE_SYNC_MS

        /** 切歌宽限期。Android TRACK_SWITCH_GRACE_PERIOD_MS。 */
        const val TRACK_SWITCH_GRACE_PERIOD_MS = ListenTogetherSyncThresholds.TRACK_SWITCH_GRACE_PERIOD_MS

        /** 归零保护门槛。Android UNEXPECTED_ZERO_POSITION_ROLLBACK_GUARD_MS。 */
        const val ZERO_POSITION_ROLLBACK_GUARD_MS = ListenTogetherSyncThresholds.ZERO_POSITION_ROLLBACK_GUARD_MS

        /**
         * 硬 seek 的落点前瞻量上限。
         *
         * 桌面引擎的 seek 会重建整条 ffmpeg 流水线，重建完成前播放器的位置是冻结的
         * （[FfmpegAudioEngine] 在 `startPipeline` 里把 `positionMs` 设成目标值，但帧计数
         * 要等新进程吐出音频才开始涨）。这段时间房间还在往前走，于是 seek 完当场就又
         * 落后「重建耗时」——本地缓存命中时是 100~200ms 量级，远端直链重新建连 + 首包
         * 缓冲能到两秒以上。上限必须覆盖实测到的慢网络重建耗时，否则前瞻被截断，
         * seek 完当场就还差「重建耗时 - 上限」这么大一截，紧接着又走软/硬校正
         * （两者都会重建流水线），形成回退循环；快网络下由 [SEEK_LEAD_DEFAULT_MS]
         * 和实测样本决定实际前瞻，不会被这个上限主动顶大。
         */
        const val SEEK_LEAD_MAX_MS = 2_800L

        /** 落点前瞻的下限：低于它不值得为「顶一点」再冒校正不足的风险。 */
        const val SEEK_LEAD_MIN_MS = 100L

        /** 还没有实测样本时的落点前瞻默认值（本机缓存 seek 重建的典型量级）。 */
        const val SEEK_LEAD_DEFAULT_MS = 180L

        /** 观测「seek 后重新出声耗时」的超时：超过它只说明远端直链重建特别慢。 */
        const val SEEK_LEAD_SAMPLE_TIMEOUT_MS = 5_000L

        /**
         * 允许一次重建观测采用的最大墙钟时长。超过它仍没越过落点，说明样本已经被
         * 暂停/切歌等外部动作污染（位置不可能增长的时段被算了进去），不该拿来当前瞻。
         */
        const val SEEK_LEAD_SAMPLE_VALID_MS = SEEK_LEAD_MAX_MS + 1_000L

        /**
         * 连续这么多次重建后仍收敛不了，就暂时停止下发速率/硬 seek，
         * 让流水线稳定这么久，避免在慢网络下反复重建永远出不来声。
         */
        const val REBUILD_RECOVERY_TICKS = 2
        const val REBUILD_STABILIZE_MS = 5_000L

        /**
         * 软校正试算间隔。
         *
         * 只从 1s 放松到 2s：这个闸门在 planner 之前，硬 seek 判定也受它限制，
         * 拉太长（如 10s）会让本该立刻硬同步的大偏差最多晚 10s 才发现。真正控制
         * 「重建频率」的是 [SOFT_SYNC_RATE_MIN_INTERVAL_MS]，这里保持秒级即可。
         */
        const val SOFT_SYNC_RECHECK_INTERVAL_MS = 2_000L

        /**
         * 听众两次真正「应用房态」的最小间隔。一次 apply 可能正在重建流水线，
         * 这段时间内 positionMs 冻结、state 短暂翻成 PAUSED；若每 tick 都把它当成
         * 结构性差异重进 apply，就会反复 HARD seek，永远等不到出声。
         */
        const val LISTENER_APPLY_MIN_INTERVAL_MS = 2_000L

        /**
         * 两次真正下发速率的最小间隔，避免频繁重建流水线造成卡顿/爆音。
         *
         * 3s → 12s：配合 [SOFT_SYNC_RECHECK_INTERVAL_MS] 的拉长，一次软校正期间最多下发
         * 一次速率（首个 tick），剩下的靠保持档位收敛。代价是收敛变慢，但速率重建本身
         * 造成的断声远比这几百毫秒的残余偏差恼人。
         */
        const val SOFT_SYNC_RATE_MIN_INTERVAL_MS = 12_000L

        /** 速率差小于它就不重设（引擎侧 0.0001 的阈值太细，会持续抖）。 */
        const val SOFT_SYNC_RATE_EPSILON = 0.005f

        /** 连续多少次复核确认已收敛才恢复用户倍速。 */
        const val SOFT_SYNC_CONVERGED_TICKS = 2

        /**
         * 收敛判定门槛：漂移小于它就可以恢复用户倍速（走 deferred，无重建）。
         *
         * 与 Android 的 [SOFT_SYNC_MIN_DRIFT_MS]（600ms，同时也是 planner 的硬同步参考）
         * 分开定义：它只用于「要不要收工」，不再复用那个会牵动 planner 的别名，避免
         * 调低它误把硬同步阈值一起改掉。阈值从 600ms 收紧到 250ms：600ms 就收工的话，
         * 残余偏差在接下来十几秒里又累积过 600ms，形成「收敛→恢复→再调速」的横跳；
         * 250ms 下恢复后通常要很久才会再次触发软同步。
         */
        const val SOFT_SYNC_CONVERGED_DRIFT_MS = 250L
    }

    // ---- 结构化日志辅助：只写身份/决策类字段，任何 Token/Secret/直链都不进日志 ----

    /** 控制指令摘要：类型 + 序号 + 关键参数，不含 streamUrl（可能带签名 query）。 */
    private fun controlEventLog(event: ListenTogetherControlEvent): String = buildString {
        append("type=${event.type}")
        append(" seq=${event.clientSequence ?: "-"}")
        append(" eventId=${event.eventId?.take(20) ?: "-"}")
        event.positionMs?.let { append(" pos=$it") }
        event.currentIndex?.let { append(" idx=$it") }
        event.requestTrackStableKey?.let { append(" track=$it") }
        event.queue?.let { append(" queueSize=${it.size}") }
        event.queueMutation?.let { append(" mutOps=${it.operations.size}@v${it.baseRoomVersion}") }
        event.shouldPlay?.let { append(" shouldPlay=$it") }
    }

    /** 控制结果摘要。 */
    private fun outcomeLog(outcome: ControlOutcome?): String = when {
        outcome == null -> "result=无回执"
        outcome.ok -> "result=ok applied=${outcome.applied?.type ?: "-"}"
        else -> "result=error error=${outcome.error ?: "-"}"
    }

    /** planner 输入摘要：用户要看的「为什么这样决策」全在这里。 */
    private fun syncContextLog(ctx: ListenTogetherPlayerSyncContext): String = buildString {
        append("cause=${ctx.causeType ?: "-"}")
        append(" expected=${ctx.expectedPositionMs} local=${ctx.localPositionMs}")
        append(" drift=${ctx.expectedPositionMs - ctx.localPositionMs}")
        append(" sameTrack=${!ctx.targetIndexChanged && !ctx.playbackContextChanged}")
        append(" queueChanged=${ctx.playbackContextChanged} indexChanged=${ctx.targetIndexChanged}")
        append(" desiredPlaying=${ctx.desiredPlaying} localPlaying=${ctx.localPlaying}")
        append(" zeroRollbackGuard=${ctx.ignoreUnexpectedZeroPositionRollback}")
        append(" grace=${ctx.trackSwitchGracePeriodActive} awaitStream=${ctx.awaitingAuthoritativeStream}")
        append(" forceSync=${ctx.forcePositionSync}")
    }

    /** planner 输出摘要。 */
    private fun syncPlanLog(plan: ListenTogetherPlayerSyncPlan): String = buildString {
        append("seek=${plan.shouldSeek}")
        append(" reload=${plan.shouldReloadPlaylist}")
        append(" play=${plan.shouldIssuePlay} pause=${plan.shouldIssuePause}")
        append(" effectiveExpected=${plan.effectiveExpectedPositionMs}")
        append(" signedDrift=${plan.signedDriftMs} drift=${plan.driftMs}")
    }

    /**
     * planner 决策留痕：每次调用都记 debug（AppLog 的 2s 合并降噪），
     * 决策结构变化或触发 HARD seek 时升为 info，方便用户直接看到「为什么突然硬同步」。
     */
    private fun logSyncPlanDecision(
        source: String,
        context: ListenTogetherPlayerSyncContext,
        plan: ListenTogetherPlayerSyncPlan,
    ) {
        val signature = buildString {
            append(plan.shouldReloadPlaylist).append('|').append(plan.shouldSeek).append('|')
            append(plan.shouldIssuePlay).append('|').append(plan.shouldIssuePause).append('|')
            append(plan.desiredPlaying)
        }
        val changed = signature != lastSyncPlanSignature
        val message = "$source 同步决策 ctx{${syncContextLog(context)}} plan{${syncPlanLog(plan)}}" +
            if (changed) " [决策变化]" else ""
        if (changed || plan.shouldSeek) {
            AppLog.info(TAG, message)
        } else {
            AppLog.debug(TAG, message)
        }
        lastSyncPlanSignature = signature
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = JsonFileStore(configFile, ListenTogetherConfig.serializer()) { ListenTogetherConfig() }
    private val http = ListenTogetherHttpClient(container.online.httpService)

    private val lock = Any()
    private val clientSequence = AtomicLong(0)
    private val pendingResults = ConcurrentHashMap<String, CompletableDeferred<ControlOutcome>>()
    private val applyMutex = Mutex()

    private var config: ListenTogetherConfig = ListenTogetherConfig()
    private var room: ListenTogetherRoomState? = null
    private var connection: String = "disconnected"
    private var lastError: String? = null
    private var notice: String? = null
    private var token: String? = null
    private var joinSecret: String? = null
    private var sessionBaseUrl: String? = null
    private var sessionUserUuid: String? = null
    private var sessionNickname: String? = null
    private var sessionRole: String? = null
    private var wsUrl: String? = null
    private var wsOpen = false
    private var expectedPositionMs = 0L
    private var expectedAtLocalMs = 0L

    /**
     * 听众刚发出的 REQUEST_PLAY / REQUEST_PAUSE 意图（按 eventId 索引）。
     *
     * 用于修正 worker 回执竞态：回执 ok 但 applied.state.playback.state 与意图相反时，
     * 只要房态不是「比本地已见版本更新的权威态」，就以意图为准覆盖播放态再 apply。
     * 控制者不需要这条（按钮本地立即生效）；后续权威广播仍会校正真实房态。
     */
    private val pendingPlaybackIntents = ConcurrentHashMap<String, PendingPlaybackIntent>()

    private data class PendingPlaybackIntent(val desiredPlaying: Boolean, val atMs: Long)

    /**
     * 服务端时钟同步状态。
     *
     * 早先这里是「服务端时间戳 - 收到时的本机时间」直算，每条 WS 消息覆盖一次：既少算了
     * 一个单程网络延迟（服务端的时间戳是它发消息那一刻的），又被无过滤地反复覆盖。
     * 现在统一由 [ListenTogetherClockSync] 管理：ping/pong 往返样本、RTT/2 补偿、7:3 平滑。
     */
    private val clockSync = ListenTogetherClockSync()

    /** 服务端时钟 - 本机墙钟；把本机时间换算到服务端时间线时用。 */
    private val serverClockOffsetMs: Long get() = clockSync.offsetMs

    /** 服务端是否支持带回显时间的 np_ping；false 时退回不带 t 的旧版 ping。 */
    private var clockSyncPingSupported: Boolean? = null
    private var autoPauseOnJoin = false
    private var lastOutboundAtMs = 0L
    private var lastInboundAtMs = 0L
    private var lastPingAtMs = 0L
    private var lastStateRefreshAtMs = 0L
    private var reconnectAttempts = 0
    private var nextReconnectAtMs = 0L
    private var reconnectInFlight = false
    private var started = false
    private var leaving = false
    private var applyingRemote = false
    private var suppressLocalUntilMs = 0L
    private var lastListenerApplyAtMs = 0L

    /**
     * 最近一次切歌/重建队列的时刻（墙钟）。对齐 Android `lastTrackSwitchAtElapsedMs`：
     * 切歌后 [TRACK_SWITCH_GRACE_PERIOD_MS] 内的 passive 位置更新不纠正，避免新曲目的
     * 首条心跳把刚打开的进度拽回旧值。
     */
    private var lastTrackSwitchAtMs = 0L

    private var unsupportedLocalSongNotified = false
    private var wsClient: ListenTogetherWebSocketClient? = null
    private var reported: LocalReport? = null
    private var tickJob: Job? = null
    private var reconnectJob: Job? = null

    // ---- 听众侧软同步状态 ----

    /** 最后一次软校正试算的时间；用来限制试算频率。 */
    private var lastSoftSyncEvalAtMs = 0L

    /** 最后一次真正把速率下发到引擎的时间；引擎重建流水线的节流依据。 */
    private var lastSoftSyncRateAtMs = 0L

    /** 连续收敛的复核次数；达到 [SOFT_SYNC_CONVERGED_TICKS] 才恢复用户倍速。 */
    private var softSyncConvergedTicks = 0

    /** 上一次 planner 决策摘要，用于「决策变化时升高一级日志」；高频路径只记 debug。 */
    private var lastSyncPlanSignature: String? = null

    /**
     * 正在进行的一次硬 seek 观测样本。
     *
     * 非空同时表示「流水线可能正在重建、播放器位置不可信」——漂移校正要据此跳过本轮，
     * 否则会把重建期间冻结的位置当成真实漂移，再排一次 seek（一次同步卡好几下就是这么来的）。
     */
    @Volatile
    private var pendingSeekSample: SeekSample? = null

    /** 上一次实测到的重建耗时（毫秒）；null 表示还没采到样本。 */
    @Volatile
    private var observedSeekRebuildMs: Long? = null

    /**
     * 漂移校正暂停截止时间（墙钟）。
     *
     * 硬 seek **和软调速**都会重建 ffmpeg 流水线、冻结播放器位置；重建期间 local 是假值，
     * 据此继续下发速率/硬 seek 只会一遍遍重建，永远出不来声。任何触发重建的动作都会把
     * 这个截止时间往后推，重建真正出声（见 [sampleSeekRebuildLatency]）后再收尾。
     */
    @Volatile
    private var rebuildPausedUntilMs = 0L

    /** 连续重建但始终没越过落点的次数；达到阈值后进入短暂稳定期。 */
    private var rebuildNoProgressTicks = 0

    /** 稳定期截止时刻：期间只软等待，不再下发速率，也不再排硬 seek。 */
    private var rebuildStabilizeUntilMs = 0L

    private data class SeekSample(val atMs: Long, val targetMs: Long)

    /**
     * 听众乐观队列变更：请求已发出、服务端还没确认期间，房态覆盖时用它保住本地那条改动，
     * 避免「刚加的歌立刻被房间旧队列冲掉」。确认/超时/被拒后清空。
     */
    private var pendingQueueRequest: PendingQueueRequest? = null

    // ---- 共享音频直链（对齐 Android LINK_READY / LINK_UNAVAILABLE / REQUEST_LINK）----

    /** 听众当前是否在使用房间共享直链（供快照展示）。 */
    @Volatile
    private var usingSharedStream = false

    /**
     * 听众上次为共享直链 reload 过的 (stableKey, url)。
     *
     * 只有「直链真的换了」才值得 reload：房间每 400ms 心跳都会带同一条 streamUrl，
     * 若每次都按「当前音源 url != 房间 url」重载，一旦本地回退到备用音源（直链过期
     * 触发 B 站兜底），当前音源 url 永远不等于房间里的旧直链，就会每次心跳都
     * `playCurrent()` 把进度打回 0 —— 现场是房间在播、听众进度反复归零。
     */
    private var lastAppliedSharedKey: String? = null
    private var lastAppliedSharedUrl: String? = null

    /** 控制者上次成功上报的直链：stableKey 与 URL 都变化才重新 LINK_READY。 */
    private var publishedLinkKey: String? = null
    private var publishedLinkUrl: String? = null
    private var publishedLinkAtMs = 0L

    /** 听众最近一次 REQUEST_LINK 的曲目与时间（本地节流）。 */
    private var linkRequestKey: String? = null
    private var linkRequestAtMs = 0L

    /** 已被控制者确认为失效的直链曲目：同一首歌不再自动重复请求。 */
    private var unavailableLinkKey: String? = null

    // ---------------------------------------------------------------- 生命周期

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            config = normalizedConfig(store.load())
            store.save(config)
        }
        // 听众本地直链解析失败时由 Player 回调到这里，发 REQUEST_LINK 请控制者补发共享流。
        container.player.onPlaybackResolveFailed = { song -> scope.launch { onLocalResolveFailed(song) } }
        tickJob = scope.launch { tickLoop() }
        scope.launch { restoreSavedSession() }
        notifyState()
    }

    private fun normalizedConfig(loaded: ListenTogetherConfig): ListenTogetherConfig = loaded.copy(
        userUuid = loaded.userUuid.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
        clientInstanceId = loaded.clientInstanceId
            .takeIf { it.isNotBlank() && it.matches(CLIENT_INSTANCE_ID_PATTERN) }
            ?: newClientInstanceId(),
        serverUrl = normalizeListenTogetherBaseUrlOrNull(loaded.serverUrl).orEmpty(),
    )

    private fun newClientInstanceId(): String =
        "desktop-" + UUID.randomUUID().toString().replace("-", "").take(12)

    /**
     * 新建一次会话时把控制序列身份一起换掉。
     *
     * 服务端按 (userUuid:clientInstanceId) 记录 clientSequence，≤ 已记录值的事件会被静默丢弃并回 ok；
     * clientSequence 是进程内计数，重启/重连后会归零。若沿用旧的持久化 instanceId，新会话发出的
     * seq=1,2,3… 会被当成过期事件丢掉，房主收不到任何控制。对齐 Android：instanceId 与 seq 同生命周期。
     * 服务端仅把 instanceId 用作该序列去重键，按 userUuid/memberSecret 识别成员，换号不影响成员身份。
     * 调用方须持有 [lock]。
     */
    private fun resetClientControlIdentityLocked() {
        clientSequence.set(0)
        config = config.copy(clientInstanceId = newClientInstanceId())
    }

    private fun persistConfig() {
        store.save(config)
    }

    // ---------------------------------------------------------------- 对外命令

    fun snapshot(): ListenTogetherSnapshot {
        val values = snapshotValues()
        return values
    }

    private fun snapshotValues(): ListenTogetherSnapshot {
        val bundle = synchronized(lock) {
            SnapshotBundle(
                // 面板要看到乐观队列（刚加的歌立刻出现），所以这里取叠加覆盖层后的状态
                room = effectiveRoomStateLocked(),
                role = sessionRole,
                connection = connection,
                wsOpen = wsOpen,
                joinSecret = joinSecret,
                autoPauseOnJoin = autoPauseOnJoin,
                lastError = lastError,
                notice = notice,
                config = config,
            )
        }
        val now = System.currentTimeMillis()
        val roomState = bundle.room
        val isController = isControllerRole(bundle.role)
        val playback = playbackSnapshot(roomState, now)
        val sharedKey = roomState?.track ?: roomState?.queue?.getOrNull(roomState.currentIndex)
        val sharedAvailable = roomState?.settings?.shareAudioLinks == true &&
            sharedKey?.let { !it.streamUrl.isNullOrBlank() || it.streamUrls.any { url -> url.isNotBlank() } } == true
        return ListenTogetherSnapshot(
            configured = bundle.config.serverUrl.isNotBlank(),
            serverUrl = bundle.config.serverUrl,
            nickname = bundle.config.nickname,
            userUuid = bundle.config.userUuid,
            connection = bundle.connection,
            connected = bundle.connection == "connected",
            wsConnected = bundle.wsOpen,
            roomId = roomState?.roomId,
            role = bundle.role,
            isController = isController,
            selfUserUuid = bundle.config.userUuid,
            inviteLink = if (isController && roomState != null && !bundle.joinSecret.isNullOrBlank()) {
                buildListenTogetherInvite(
                    roomId = roomState.roomId,
                    nickname = bundle.config.nickname.ifBlank { null },
                    baseUrl = bundle.config.serverUrl,
                    joinSecret = bundle.joinSecret!!,
                )
            } else {
                null
            },
            members = roomState?.members.orEmpty(),
            controllerUserUuid = roomState?.controllerUserUuid,
            queue = roomState?.queue.orEmpty(),
            currentIndex = roomState?.currentIndex ?: 0,
            track = roomState?.track ?: roomState?.queue?.getOrNull(roomState.currentIndex),
            playback = playback,
            settings = roomState?.settings,
            roomStatus = roomState?.roomStatus,
            version = roomState?.version ?: 0L,
            autoPauseOnJoin = bundle.autoPauseOnJoin,
            sharedStreamAvailable = sharedAvailable,
            usingSharedStream = usingSharedStream,
            error = bundle.lastError,
            notice = bundle.notice,
        )
    }

    private data class SnapshotBundle(
        val config: ListenTogetherConfig,
        val room: ListenTogetherRoomState?,
        val role: String?,
        val connection: String,
        val wsOpen: Boolean,
        val joinSecret: String?,
        val autoPauseOnJoin: Boolean,
        val lastError: String?,
        val notice: String?,
    )

    suspend fun configure(serverUrl: String?, nickname: String?): ListenTogetherSnapshot {
        AppLog.info(TAG, "configure 入口：serverUrl=${serverUrl?.let { redactListenTogetherUrl(it) } ?: "沿用"} nickname=${nickname ?: "沿用"}")
        val normalizedNickname = nickname?.trim()
        if (normalizedNickname != null && normalizedNickname.isNotEmpty()) {
            val nicknameError = validateListenTogetherNickname(normalizedNickname)
            if (nicknameError != null) {
                AppLog.warn(TAG, "configure 拒绝：昵称非法 —— $nicknameError")
                return fail(nicknameError)
            }
        }
        val normalizedUrl = if (serverUrl == null) {
            config.serverUrl
        } else if (serverUrl.isBlank()) {
            ""
        } else {
            normalizeListenTogetherBaseUrlOrNull(serverUrl)
                ?: run {
                    AppLog.warn(TAG, "configure 拒绝：serverUrl 不是合法 http(s) URL")
                    return fail("服务端地址需为 http(s) URL，例如 $LISTEN_TOGETHER_DEFAULT_SERVER_URL")
                }
        }
        val currentBase = synchronized(lock) { sessionBaseUrl }
        if (room != null && !normalizedUrl.equals(currentBase, ignoreCase = true)) {
            AppLog.warn(TAG, "configure 拒绝：在房间中不能改服务端地址")
            return fail("请先离开房间再修改服务端地址")
        }
        synchronized(lock) {
            config = config.copy(
                serverUrl = normalizedUrl,
                nickname = normalizedNickname ?: config.nickname,
            )
            lastError = null
            notice = if (normalizedUrl.isBlank()) "已清空服务端地址" else null
            persistConfig()
        }
        if (normalizedUrl.isNotBlank() && room == null) {
            val reachable = withContext(Dispatchers.IO) { runCatching { http.healthz(normalizedUrl) }.getOrDefault(false) }
            AppLog.info(TAG, "configure 服务端可用性检测：${redactListenTogetherUrl(normalizedUrl)} → ${if (reachable) "可达" else "不可达/未识别"}")
            if (!reachable) {
                synchronized(lock) { notice = "服务端地址已保存，但可用性测试未通过（可能是自建实例未实现 /healthz）" }
            }
        }
        AppLog.debug(TAG, "configure 完成：最终 serverUrl=${redactListenTogetherUrl(normalizedUrl)}")
        notifyState()
        return snapshot()
    }

    suspend fun createRoom(nickname: String?): ListenTogetherSnapshot {
        val createStarted = System.currentTimeMillis()
        AppLog.info(TAG, "createRoom 入口：baseUrl=${redactListenTogetherUrl(config.serverUrl)} nickname=${nickname ?: "默认"}")
        if (room != null) {
            AppLog.warn(TAG, "createRoom 拒绝：已在房间中")
            return fail("已在房间中，请先离开")
        }
        val baseUrl = config.serverUrl
        if (baseUrl.isBlank()) {
            AppLog.warn(TAG, "createRoom 拒绝：未配置服务端地址")
            return fail("请先配置一起听服务端地址")
        }
        val resolvedNickname = resolveNickname(nickname)
        val snapshotPair = shareableQueueSnapshot()
        if (snapshotPair == null) {
            AppLog.warn(TAG, "createRoom 拒绝：本地无可分享在线歌曲")
            return fail("请先播放一首在线歌曲：本地歌曲不能进入一起听房间")
        }
        val (tracks, index) = snapshotPair
        val track = tracks.getOrNull(index) ?: run {
            AppLog.warn(TAG, "createRoom 拒绝：当前歌曲无法分享")
            return fail("当前歌曲无法分享")
        }
        val player = container.player
        val bounded = boundQueueAround(tracks, index)
        val request = ListenTogetherCreateRoomRequest(
            userUuid = config.userUuid,
            nickname = resolvedNickname,
            initialSnapshot = ListenTogetherInitialSnapshot(
                queue = bounded.first,
                currentIndex = bounded.second,
                track = bounded.first.getOrNull(bounded.second) ?: track,
                settings = ListenTogetherRoomSettings(
                    allowMemberControl = true,
                    autoPauseOnMemberChange = true,
                    // 对齐 Android：默认共享音频直链，控制者解析出直链后上报 LINK_READY
                    shareAudioLinks = true,
                ),
                isPlaying = player.state.value == PlaybackState.PLAYING,
                positionMs = player.positionMs.value.coerceAtLeast(0L),
                repeatMode = player.repeatMode.value.toListenTogetherRepeatMode(),
                shuffleEnabled = player.shuffle.value,
                shuffleRestoreQueue = null,
            ),
        )
        val result = withContext(Dispatchers.IO) { http.createRoom(baseUrl, request) }
        val response = result.value
        if (result.error != null || response == null || !response.ok) {
            val error = describeListenTogetherError(result.error ?: response?.error)
            AppLog.warn(TAG, "createRoom 失败：error=$error（${System.currentTimeMillis() - createStarted}ms）")
            return fail(error)
        }
        AppLog.info(TAG, "createRoom 成功：roomId=${response.roomId} role=${response.role} nickname=${response.nickname ?: resolvedNickname}（${System.currentTimeMillis() - createStarted}ms）")
        synchronized(lock) {
            config = config.copy(nickname = response.nickname ?: resolvedNickname)
            resetClientControlIdentityLocked()
            persistConfig()
        }
        adoptSession(baseUrl, response)
        connectWebSocket()
        notifyState()
        return snapshot()
    }

    suspend fun joinRoom(inviteOrRoomId: String, nickname: String?): ListenTogetherSnapshot {
        val joinStarted = System.currentTimeMillis()
        // 输入可能是整条邀请链接（带 joinSecret）；只记「是否邀请链接 + 房间号」，绝不记原文。
        AppLog.info(TAG, "joinRoom 入口：input=${if (parseListenTogetherInvite(inviteOrRoomId) != null) "邀请链接" else "房间号"} nickname=${nickname ?: "默认"} configuredBase=${redactListenTogetherUrl(config.serverUrl)}")
        if (room != null) {
            AppLog.warn(TAG, "joinRoom 拒绝：已在房间中")
            return fail("已在房间中，请先离开")
        }
        val configuredBase = config.serverUrl
        val invite = parseListenTogetherInvite(inviteOrRoomId)
        val roomId: String
        val baseUrl: String
        var joinSecretForJoin: String?
        var memberSecretForJoin: String? = null
        if (invite != null) {
            roomId = invite.roomId
            baseUrl = invite.baseUrl ?: configuredBase
            joinSecretForJoin = invite.joinSecret
            if (baseUrl.isBlank()) {
                AppLog.warn(TAG, "joinRoom 拒绝：邀请链接中的服务端地址无效，且本机未配置")
                return fail("邀请链接中的服务端地址无效，且本机未配置服务端地址")
            }
            val saved = config.session
            if (saved != null && saved.roomId.equals(roomId, ignoreCase = true) &&
                saved.userUuid == config.userUuid
            ) {
                memberSecretForJoin = saved.memberSecret
            }
        } else {
            roomId = normalizeListenTogetherRoomId(inviteOrRoomId)
            validateListenTogetherRoomId(roomId)?.let {
                AppLog.warn(TAG, "joinRoom 拒绝：房间号非法（原始输入长度 ${inviteOrRoomId.length}）")
                return fail(it)
            }
            baseUrl = configuredBase
            if (baseUrl.isBlank()) {
                AppLog.warn(TAG, "joinRoom 拒绝：未配置服务端地址")
                return fail("请先配置一起听服务端地址")
            }
            val saved = config.session
            if (saved != null && saved.roomId.equals(roomId, ignoreCase = true)) {
                joinSecretForJoin = saved.joinSecret
                if (saved.userUuid == config.userUuid) memberSecretForJoin = saved.memberSecret
            } else {
                joinSecretForJoin = null
            }
            if (joinSecretForJoin.isNullOrBlank() && memberSecretForJoin.isNullOrBlank()) {
                AppLog.warn(TAG, "joinRoom 拒绝：首次加入缺少邀请密钥（roomId=$roomId）")
                return fail("首次加入需要邀请链接（链接里带有加入密钥）；只填房间号适用于重新连接已在的房间")
            }
        }
        AppLog.debug(TAG, "joinRoom 发起 HTTP：roomId=$roomId baseUrl=${redactListenTogetherUrl(baseUrl)} hasJoinSecret=${!joinSecretForJoin.isNullOrBlank()} hasMemberSecret=${!memberSecretForJoin.isNullOrBlank()}")
        val resolvedNickname = resolveNickname(nickname)
        val request = ListenTogetherJoinRoomRequest(
            userUuid = config.userUuid,
            nickname = resolvedNickname,
            memberSecret = memberSecretForJoin,
            joinSecret = joinSecretForJoin,
        )
        val result = withContext(Dispatchers.IO) { http.joinRoom(baseUrl, roomId, request) }
        val response = result.value
        if (result.error != null || response == null || !response.ok) {
            val error = describeListenTogetherError(result.error ?: response?.error)
            AppLog.warn(TAG, "joinRoom 失败：roomId=$roomId error=$error（${System.currentTimeMillis() - joinStarted}ms）")
            return fail(error)
        }
        AppLog.info(TAG, "joinRoom 成功：roomId=${response.roomId ?: roomId} role=${response.role} nickname=${response.nickname ?: resolvedNickname}（${System.currentTimeMillis() - joinStarted}ms）")
        synchronized(lock) {
            config = config.copy(
                nickname = response.nickname ?: resolvedNickname,
                serverUrl = config.serverUrl.ifBlank { baseUrl },
            )
            resetClientControlIdentityLocked()
            persistConfig()
        }
        adoptSession(baseUrl, response)
        val state = response.state
        if (state != null) {
            applyAuthoritativeState(
                state = state,
                expectedPosition = responseExpectedPosition(state),
                causedBy = null,
                applyToPlayer = !isControllerRole(response.role),
            )
        }
        connectWebSocket()
        notifyState()
        return snapshot()
    }

    suspend fun leaveRoom(): ListenTogetherSnapshot {
        val leaveStarted = System.currentTimeMillis()
        val (base, roomId, currentToken) = synchronized(lock) {
            Triple(sessionBaseUrl, room?.roomId, token)
        }
        AppLog.info(TAG, "leaveRoom 入口：roomId=$roomId baseUrl=${base?.let { redactListenTogetherUrl(it) } ?: "-"}")
        leaving = true
        try {
            if (base != null && roomId != null && !currentToken.isNullOrBlank()) {
                val result = withContext(Dispatchers.IO) {
                    runCatching { http.leaveRoom(base, roomId, currentToken) }
                }
                AppLog.debug(TAG, "leaveRoom HTTP 回执：${result.getOrNull()?.let { if (it.ok) "ok" else "error=${it.error}" } ?: "请求异常"}（${System.currentTimeMillis() - leaveStarted}ms）")
            }
            reconnectJob?.cancel()
            reconnectJob = null
            closeSocket()
            resetSoftSyncRate()
            clearSession(clearPersistedSession = true)
            synchronized(lock) {
                connection = "disconnected"
                lastError = null
                notice = "已离开房间"
            }
        } finally {
            leaving = false
        }
        AppLog.info(TAG, "leaveRoom 完成：roomId=$roomId（${System.currentTimeMillis() - leaveStarted}ms）")
        notifyState()
        return snapshot()
    }

    /**
     * 听众发起控制请求（服务端在 `allowMemberControl` 且控制者在线时直接仲裁）。
     *
     * 队列类请求（[ACTION_ADD_TO_QUEUE] / [ACTION_REMOVE_FROM_QUEUE] / [ACTION_MOVE_IN_QUEUE]）
     * 每次只表达一首歌的增减或一次移动：worker 的 `REQUEST_SET_QUEUE` 只接受
     * 「保留当前曲目的重排 / 单曲插入 / 单曲移除 / 移除后选下一首」，一次替换多首会被拒绝。
     */
    suspend fun requestControl(
        action: String,
        positionMs: Long?,
        repeatMode: Int?,
        shuffleEnabled: Boolean?,
        requestedTrack: ListenTogetherTrack? = null,
        requestedIndex: Int? = null,
        fromIndex: Int? = null,
        toIndex: Int? = null,
    ): ListenTogetherSnapshot {
        AppLog.debug(TAG, "requestControl 入口：action=$action pos=${positionMs ?: "-"} repeat=${repeatMode ?: "-"} shuffle=${shuffleEnabled ?: "-"} index=${requestedIndex ?: "-"} from=${fromIndex ?: "-"} to=${toIndex ?: "-"}")
        val state = room ?: return fail("不在房间中")
        if (isControllerRole(sessionRole)) return fail("你已经是控制者")
        if (action == ACTION_ADD_TO_QUEUE || action == ACTION_REMOVE_FROM_QUEUE ||
            action == ACTION_MOVE_IN_QUEUE
        ) {
            return requestQueueControl(
                action = action,
                state = state,
                requestedTrack = requestedTrack,
                requestedIndex = requestedIndex,
                fromIndex = fromIndex,
                toIndex = toIndex,
            )
        }
        // 上行事件与 Android 的 toShareableQueueSnapshot(includeResolvedStreamUrl=false) 对齐：
        // 房间队列里当前曲目可能带 LINK_READY 写入的共享直链，控制事件里不能把它塞回去。
        val queue = state.queue.map { it.withoutStreamUrls() }
        val index = state.currentIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        val track = (state.track ?: state.queue.getOrNull(index))?.withoutStreamUrls()
        val stableKey = track?.stableKey
        val roomPlaying = state.playback.state == "playing"
        // Android 的 transport()/seek()/trackSelection() 都先校验当前曲目可分享：不可分享时不发事件。
        // 房间里的曲目若在本地映射成 LOCAL / 缺远端 ID，发出去也会被 worker 判为无效请求，
        // 不如在这里按同口径拦掉，避免「按了没反应还弹一条错误」。
        if (action in setOf("play", "pause", "seek") && !roomTrackShareable(track)) {
            AppLog.warn(TAG, "requestControl 跳过：当前房间曲目不可分享 action=$action track=${stableKey ?: "-"}")
            return fail("当前曲目无法参与一起听控制")
        }
        val targetPosition = positionMs?.coerceAtLeast(0L) ?: computeExpectedPosition(System.currentTimeMillis())
        val event = when (action) {
            // Android 的 playbackSnapshotEvent 对 PLAY/PAUSE/SEEK 都带 repeatMode/shuffleEnabled，
            // worker 的 sanitizeForwardedControlPayload 会用它们回填房态；缺省会让听众请求
            // 意外把房间的播放模式冲成默认值。
            "play" -> ListenTogetherControlEvent(
                type = "REQUEST_PLAY",
                positionMs = targetPosition,
                currentIndex = index,
                track = track,
                shouldPlay = true,
                state = "playing",
                repeatMode = state.playback.repeatMode,
                shuffleEnabled = state.playback.shuffleEnabled == true,
                requestTrackStableKey = stableKey,
            )

            "pause" -> ListenTogetherControlEvent(
                type = "REQUEST_PAUSE",
                positionMs = targetPosition,
                currentIndex = index,
                track = track,
                shouldPlay = false,
                state = "paused",
                repeatMode = state.playback.repeatMode,
                shuffleEnabled = state.playback.shuffleEnabled == true,
                requestTrackStableKey = stableKey,
            )

            "seek" -> ListenTogetherControlEvent(
                type = "REQUEST_SEEK",
                positionMs = targetPosition,
                currentIndex = index,
                track = track,
                state = if (roomPlaying) "playing" else "paused",
                repeatMode = state.playback.repeatMode,
                shuffleEnabled = state.playback.shuffleEnabled == true,
                requestTrackStableKey = stableKey,
            )

            "playbackMode" -> ListenTogetherControlEvent(
                type = "REQUEST_PLAYBACK_MODE",
                positionMs = targetPosition,
                currentIndex = index,
                track = track,
                repeatMode = repeatMode,
                shuffleEnabled = shuffleEnabled,
                state = if (roomPlaying) "playing" else "paused",
                requestTrackStableKey = stableKey,
            )

            "next", "previous" -> {
                if (queue.isEmpty()) return fail("房间队列为空")
                val nextIndex = if (action == "next") {
                    if (index < queue.lastIndex) index + 1 else if (repeatAll(state)) 0 else queue.lastIndex
                } else {
                    if (index > 0) index - 1 else queue.lastIndex
                }
                val nextTrack = queue[nextIndex]
                // 对齐 Android trackSelection()：校验的是目标曲目是否可分享。
                if (!roomTrackShareable(nextTrack)) {
                    AppLog.warn(TAG, "requestControl 跳过：目标曲目不可分享 action=$action track=${nextTrack.stableKey}")
                    return fail("目标歌曲无法参与一起听控制")
                }
                // 对齐 Android buildListenTogetherQueueEventPayload：schemaVersion>=2 时
                // 用增量 mutation 描述队列（queue=null），避免把整队列（含共享直链）原样
                // 写回房间；旧服务端或无法用增量表达时退回干净队列快照。
                val mutationPlan = buildListenTogetherQueueMutationPlan(
                    baseQueue = state.queue,
                    baseVersion = state.version,
                    targetQueue = queue,
                    targetCurrentIndex = nextIndex,
                )
                val useMutation = state.schemaVersion >= LISTEN_TOGETHER_SCHEMA_VERSION &&
                    !mutationPlan.requiresSnapshotFallback
                ListenTogetherControlEvent(
                    type = "REQUEST_SET_TRACK",
                    positionMs = 0L,
                    currentIndex = nextIndex,
                    track = nextTrack,
                    queue = if (useMutation) null else queue,
                    queueMutation = if (useMutation) mutationPlan.mutation else null,
                    shouldPlay = roomPlaying,
                    state = if (roomPlaying) "playing" else "paused",
                    repeatMode = state.playback.repeatMode,
                    shuffleEnabled = state.playback.shuffleEnabled == true,
                    requestTrackStableKey = nextTrack.stableKey,
                )
            }

            else -> return fail("不支持的控制请求：$action")
        }
        // 播放/暂停发的是 REQUEST_*，控制者仲裁后可能因为 worker 的 applied 竞态回带相反播放态。
        // 这里提前固定 eventId 并登记意图，让 control_result 能按 eventId 找到它做兜底
        // （sendControlEvent 会复用已有 eventId）。
        val desiredPlaying = when (action) {
            "play" -> true
            "pause" -> false
            else -> null
        }
        val requestEvent = if (desiredPlaying != null) {
            val eventId = event.eventId ?: newEventId()
            pendingPlaybackIntents[eventId] = PendingPlaybackIntent(desiredPlaying, System.currentTimeMillis())
            event.copy(eventId = eventId)
        } else event
        val outcome = sendControlEvent(requestEvent, awaitResult = true)
        requestEvent.eventId?.let { pendingPlaybackIntents.remove(it) }
        AppLog.info(TAG, "requestControl 结果：action=$action ${outcomeLog(outcome)}")
        synchronized(lock) {
            if (outcome?.ok == true) {
                notice = "控制请求已提交"
                lastError = null
            } else {
                lastError = describeListenTogetherError(outcome?.error)
            }
        }
        notifyState()
        return snapshot()
    }

    /**
     * 听众替换整个房间队列（播放全部 / 换一张歌单）。
     *
     * 与逐首追加不同：这里构造一条带**完整新队列**的 `REQUEST_SET_TRACK`，
     * currentIndex 指向要开始播放的曲目、positionMs=0、shouldPlay 按调用方 autoPlay。
     * worker 会按 `requestTrackStableKey` 在新队列里定位当前曲目并整体写入房态
     * （与 Android `buildSetTrackEvent` 一致），从而让所有成员跟着切歌。
     *
     * 限制：
     * - 本地歌曲 / 缺远端 ID 的歌进不了房间，调用方必须先过滤；
     * - 房间关闭听众控制（`allowMemberControl=false`）或控制者离线时 worker 拒绝，
     *   错误由 [sendControlEvent] 翻译成中文并返回在 `error` 字段；
     * - `shareAudioLinks` 关闭不影响队列替换本身，但其他成员无法共享直链，
     *   他们仍可各自解析在线音源；这里不额外拦截。
     */
    suspend fun replaceRoomQueue(
        tracks: List<ListenTogetherTrack>,
        startIndex: Int,
        autoPlay: Boolean,
    ): ListenTogetherSnapshot {
        AppLog.info(TAG, "replaceRoomQueue 入口：size=${tracks.size} startIndex=$startIndex autoPlay=$autoPlay")
        if (tracks.isEmpty()) return fail("替换队列失败：歌单为空")
        if (!isListenerInRoom()) return fail("不在一起听房间里，或你已经是控制者")
        val state = room ?: return fail("不在房间中")
        val index = startIndex.coerceIn(0, tracks.lastIndex)
        val target = tracks[index]
        if (target.channelId == LISTEN_TOGETHER_CHANNEL_LOCAL) {
            return fail("本地歌曲不能加入一起听")
        }
        val event = ListenTogetherControlEvent(
            type = "REQUEST_SET_TRACK",
            positionMs = 0L,
            currentIndex = index,
            track = target,
            queue = tracks,
            shouldPlay = autoPlay,
            state = if (autoPlay) "playing" else "paused",
            repeatMode = state.playback.repeatMode,
            shuffleEnabled = state.playback.shuffleEnabled == true,
            // worker 的 TRACK_QUEUE_BOUND_REQUEST_TYPES 校验：目标必须存在于新队列里
            requestTrackStableKey = target.stableKey,
        )
        return sendControlEvent(event)
    }

    /**
     * 听众把歌曲加入房间队列（`playNext=false`）或插到当前播放曲目之后（`playNext=true`）。
     *
     * 上层（`player.enqueue` / `player.enqueueNext`）在房间里且自己是听众时直接走这里，
     * 用户不需要知道自己是听众——点了加歌就是加到房间队列。
     *
     * `playNext` 的插入位置与本地 `Player.enqueueNext` 保持同一语义：紧跟在当前曲目之后
     * （`requestedIndex = currentIndex + 1`），而不是队尾；`requestControl` 内部会把
     * 插入位置收敛到 `[0, size]` 并让房间的 `currentIndex` 跟随当前曲目移动。
     *
     * 返回合并快照；失败原因在 `error` 字段（已翻译为中文）。
     */
    suspend fun addSongsToRoomQueue(
        songs: List<Song>,
        playNext: Boolean = false,
    ): ListenTogetherSnapshot {
        AppLog.info(TAG, "addSongsToRoomQueue 入口：count=${songs.size} playNext=$playNext")
        if (songs.isEmpty()) return snapshot()
        if (!isListenerInRoom()) return fail("不在一起听房间里，或你已经是控制者")
        // 先把整批歌曲校验并转换完，避免加到一半才发现后面有本地歌曲导致队列只加了一部分。
        val tracks = songs.map { song ->
            song.toListenTogetherTrackOrNull() ?: return fail(
                if (song.source == MediaSource.LOCAL) {
                    "本地歌曲不能加入一起听"
                } else {
                    "该歌曲无法加入一起听（缺少远端 ID）"
                },
            )
        }
        // worker 的 REQUEST_SET_QUEUE 一次只接受一首歌的变化，多选时逐条发送。
        var latest = snapshot()
        for ((index, track) in tracks.withIndex()) {
            latest = requestQueueAdd(track, playNext, index)
            if (latest.error != null) return latest
        }
        return latest
    }

    /** 加歌请求的单步发送（多首歌曲逐条发送，与服务端「一次只表达一首歌」的限制一致）。 */
    private suspend fun requestQueueAdd(
        track: ListenTogetherTrack,
        playNext: Boolean,
        alreadyInserted: Int,
    ): ListenTogetherSnapshot {
        val insertAt = if (playNext) {
            val current = synchronized(lock) { room?.currentIndex } ?: 0
            // 已插入的曲目就排在当前曲目之后，后续曲目依次顺延
            (current + 1 + alreadyInserted).coerceAtLeast(0)
        } else {
            null
        }
        return requestControl(
            action = ACTION_ADD_TO_QUEUE,
            positionMs = null,
            repeatMode = null,
            shuffleEnabled = null,
            requestedTrack = track,
            requestedIndex = insertAt,
        )
    }

    /**
     * 听众移除房间队列里的一首（房间队列下标）。
     *
     * 上层 `player.removeFromQueue` 传进来的是**本地**播放队列下标，两者不一定同构，
     * 调用方需要先用 [localQueueIndexToRoomIndex] 按曲目身份换算。
     */
    suspend fun removeLocalIndexFromRoomQueue(index: Int): ListenTogetherSnapshot =
        requestControl(
            action = ACTION_REMOVE_FROM_QUEUE,
            positionMs = null,
            repeatMode = null,
            shuffleEnabled = null,
            requestedIndex = index,
        )

    /** 听众把房间队列里第 [from] 首移动到第 [to] 首（均为房间队列下标）。 */
    suspend fun moveInRoomQueue(from: Int, to: Int): ListenTogetherSnapshot =
        requestControl(
            action = ACTION_MOVE_IN_QUEUE,
            positionMs = null,
            repeatMode = null,
            shuffleEnabled = null,
            fromIndex = from,
            toIndex = to,
        )

    /**
     * 听众点「下一首」：转发成房间的 `REQUEST_SET_TRACK`（目标 = 房间队列的下一首）。
     *
     * 上层 `player.next` 在「房间里且自己是听众」时走这里，用户不需要知道自己是听众——
     * 主页播放控制栏、播放页控制条、全局快捷键都只发 `player.next`，因此一处转发全部生效。
     * 下一首的选取（列表末尾回到开头 / 停在末尾）由 [requestControl] 按房间循环模式决定，
     * 不能用本地队列推断：听众本地队列可能与房间队列不同步（本地歌曲不上报）。
     */
    suspend fun requestNextInRoom(): ListenTogetherSnapshot = requestTrackStepInRoom("next")

    /** 听众点「上一首」：转发成房间的 `REQUEST_SET_TRACK`（目标 = 房间队列的上一首）。 */
    suspend fun requestPreviousInRoom(): ListenTogetherSnapshot = requestTrackStepInRoom("previous")

    private suspend fun requestTrackStepInRoom(action: String): ListenTogetherSnapshot {
        AppLog.info(TAG, "高层控制请求：$action")
        // 先确认身份，让「不在房间」「自己就是控制者」这两种情况有明确文案，
        // 而不是让 requestControl 里的校验与调用方语义混在一起。
        if (!isListenerInRoom()) return fail("不在一起听房间里，或你已经是控制者（$action 已忽略）")
        return requestControl(
            action = action,
            positionMs = null,
            repeatMode = null,
            shuffleEnabled = null,
        )
    }

    /** 听众点「播放」：转发成房间的 `REQUEST_PLAY`（进度取房间当前进度）。 */
    suspend fun requestPlayInRoom(): ListenTogetherSnapshot = requestPlaybackInRoom("play")

    /** 听众点「暂停」：转发成房间的 `REQUEST_PAUSE`（进度取房间当前进度）。 */
    suspend fun requestPauseInRoom(): ListenTogetherSnapshot = requestPlaybackInRoom("pause")

    private suspend fun requestPlaybackInRoom(action: String): ListenTogetherSnapshot {
        AppLog.info(TAG, "高层控制请求：$action")
        if (!isListenerInRoom()) return fail("不在一起听房间里，或你已经是控制者（$action 已忽略）")
        return requestControl(
            action = action,
            positionMs = null,
            repeatMode = null,
            shuffleEnabled = null,
        )
    }

    /**
     * 本地播放队列下标 → 房间队列下标。
     *
     * 两个队列不保证等长：房间队列里可能有桌面版解析不了的来源，本地队列也可能残留
     * 房间同步前的曲目，因此不能按偏移换算，必须按曲目身份（稳定键）匹配。
     * 返回 -1 表示这首歌不在房间队列里（本地歌曲、或队列还没同步过来）。
     */
    fun localQueueIndexToRoomIndex(localIndex: Int): Int {
        val roomQueue = synchronized(lock) { room?.queue }.orEmpty()
        if (roomQueue.isEmpty()) return -1
        val song = container.player.queue.value.getOrNull(localIndex) ?: return -1
        val track = song.toListenTogetherTrackOrNull() ?: return -1
        return roomQueue.indexOfFirst { it.stableKey == track.stableKey }
    }

    /**
     * 听众的队列变更请求（单曲增/删/移动）。
     *
     * 语义取舍：worker 的 `REQUEST_SET_QUEUE` 一次只接受一种受校验的变更，
     * 所以这里每次只构造一首歌的变化，并带上整队列快照 + 增量 mutation：
     *  - 旧服务端（schemaVersion 1）或需要回退时用快照；
     *  - 新服务端用 mutation（服务端重放到最新队列，不会吃掉别人的改动）。
     *
     * 乐观应用：请求发出后先把这条变更应用到本地播放器（与 Android 一致），
     * 同时记入 [pendingQueueRequest]；服务端广播到达时以权威房态为准并清除乐观标记，
     * 请求被拒则回滚到请求前的队列快照。
     */
    private suspend fun requestQueueControl(
        action: String,
        state: ListenTogetherRoomState,
        requestedTrack: ListenTogetherTrack?,
        requestedIndex: Int?,
        fromIndex: Int?,
        toIndex: Int?,
    ): ListenTogetherSnapshot {
        val roomQueue = state.queue
        if (roomQueue.isEmpty()) return fail("房间队列为空")
        val roomIndex = state.currentIndex.coerceIn(0, roomQueue.lastIndex)
        val targetQueue = when (action) {
            ACTION_ADD_TO_QUEUE -> {
                val track = requestedTrack?.takeIf { it.stableKey.isNotBlank() }
                    ?: return fail("缺少要添加的歌曲")
                if (track.channelId == LISTEN_TOGETHER_CHANNEL_LOCAL) {
                    return fail("本地歌曲不能加入一起听")
                }
                val insertAt = (requestedIndex ?: roomQueue.size).coerceIn(0, roomQueue.size)
                targetQueueForInsert(roomQueue, roomIndex, track, insertAt)
            }

            ACTION_REMOVE_FROM_QUEUE -> {
                val removeAt = requestedIndex ?: return fail("缺少要移除的队列下标")
                if (removeAt !in roomQueue.indices) return fail("队列下标越界（当前队列共 ${roomQueue.size} 首）")
                if (roomQueue.size <= 1) return fail("不能移除队列中的最后一首歌")
                val next = roomQueue.toMutableList().apply { removeAt(removeAt) }
                // 移除当前曲目时服务端会选中「移除位置处的下一首（末尾则前一首）」，本地按同一规则算
                val nextIndex = when {
                    removeAt < roomIndex -> roomIndex - 1
                    removeAt == roomIndex -> removeAt.coerceAtMost(next.lastIndex)
                    else -> roomIndex
                }
                next to nextIndex
            }

            ACTION_MOVE_IN_QUEUE -> {
                val from = fromIndex ?: return fail("缺少 fromIndex")
                val to = toIndex ?: return fail("缺少 toIndex")
                if (from !in roomQueue.indices || to !in roomQueue.indices) {
                    return fail("移动位置不合法（队列共 ${roomQueue.size} 首）")
                }
                if (from == to) return fail("移动位置不合法（起点与终点相同）")
                val next = roomQueue.toMutableList()
                val moved = next.removeAt(from)
                next.add(to, moved)
                // 移动不改变当前曲目身份，按稳定键重算下标
                val currentKey = roomQueue.getOrNull(roomIndex)?.stableKey
                next to next.indexOfFirst { it.stableKey == currentKey }.coerceAtLeast(0)
            }

            else -> return fail("不支持的控制请求：$action")
        }
        val (nextQueue, nextIndex) = targetQueue
        if (nextQueue.isEmpty()) return fail("房间队列为空")
        val mutationPlan = buildListenTogetherQueueMutationPlan(
            baseQueue = roomQueue,
            baseVersion = state.version,
            targetQueue = nextQueue,
            targetCurrentIndex = nextIndex,
        )
        val useMutation = state.schemaVersion >= LISTEN_TOGETHER_SCHEMA_VERSION &&
            !mutationPlan.requiresSnapshotFallback
        val currentTrack = nextQueue.getOrNull(nextIndex)
        val roomPlaying = state.playback.state == "playing"
        val event = ListenTogetherControlEvent(
            type = "REQUEST_SET_QUEUE",
            positionMs = computeExpectedPosition(System.currentTimeMillis()),
            currentIndex = nextIndex,
            track = currentTrack,
            queue = if (useMutation) null else nextQueue,
            queueMutation = if (useMutation) mutationPlan.mutation else null,
            shouldPlay = roomPlaying,
            state = if (roomPlaying) "playing" else "paused",
            repeatMode = state.playback.repeatMode,
            shuffleEnabled = state.playback.shuffleEnabled == true,
            requestTrackStableKey = currentTrack?.stableKey,
        )
        return submitQueueRequest(
            event = event,
            nextQueue = nextQueue,
            nextIndex = nextIndex,
            mutation = mutationPlan.mutation.takeIf { useMutation },
        )
    }

    /**
     * 计算插入后的目标队列：worker 要求「一次插入一首且保留原有顺序」，
     * 并且 currentIndex 必须跟随当前曲目移动。
     */
    private fun targetQueueForInsert(
        roomQueue: List<ListenTogetherTrack>,
        roomIndex: Int,
        track: ListenTogetherTrack,
        insertAt: Int,
    ): Pair<List<ListenTogetherTrack>, Int> {
        val next = roomQueue.toMutableList().apply { add(insertAt, track) }
        val nextIndex = if (insertAt <= roomIndex) roomIndex + 1 else roomIndex
        return next to nextIndex
    }

    /**
     * 发送队列请求并以服务端结果为准：
     *  - 发出前先乐观应用，避免「请求在途时房态把刚加的歌冲掉」；
     *  - 成功：覆盖层保留到服务端广播确认（版本前进）或超时；
     *  - 失败：立刻撤掉覆盖层回到权威房态，并把错误写进快照 error（不静默丢弃）。
     */
    private suspend fun submitQueueRequest(
        event: ListenTogetherControlEvent,
        nextQueue: List<ListenTogetherTrack>,
        nextIndex: Int,
        mutation: ListenTogetherQueueMutation?,
    ): ListenTogetherSnapshot {
        val requestId = newEventId()
        val baseVersion = synchronized(lock) { room?.version } ?: 0L
        val optimistic = PendingQueueRequest(
            requestId = requestId,
            queue = nextQueue,
            currentIndex = nextIndex,
            mutation = mutation,
            baseVersion = baseVersion,
            createdAtMs = System.currentTimeMillis(),
        )
        AppLog.info(TAG, "队列请求发出：requestId=${requestId.take(20)} type=${event.type} baseVersion=$baseVersion mutation=${mutation != null}")
        applyOptimisticQueue(optimistic)
        val outcome = sendControlEvent(event.copy(eventId = requestId), awaitResult = true)
        if (outcome?.ok == true) {
            AppLog.info(TAG, "队列请求已提交：requestId=${requestId.take(20)} ${outcomeLog(outcome)}（等待广播确认）")
            synchronized(lock) {
                lastError = null
                notice = "队列请求已提交"
            }
            notifyState()
            return snapshot()
        }
        val error = describeListenTogetherError(outcome?.error)
        AppLog.warn(TAG, "队列请求失败并回滚：requestId=${requestId.take(20)} ${outcomeLog(outcome)} error=$error")
        synchronized(lock) {
            if (pendingQueueRequest?.requestId == requestId) {
                pendingQueueRequest = null
                lastError = error
            }
        }
        rollbackOptimisticQueue(optimistic)
        notifyState()
        return snapshot()
    }

    /**
     * 乐观应用：把请求的目标队列作为「覆盖层」叠加在权威房态之上并推到播放器。
     *
     * 为什么不直接改 `room`：权威房态仍要以服务端版本为准，覆盖层只在请求在途期间生效
     * （面板与播放器立刻能看到这次改动，Android 同样是乐观应用），服务端确认/超时后自动消失。
     */
    private suspend fun applyOptimisticQueue(request: PendingQueueRequest) {
        val effective = synchronized(lock) {
            pendingQueueRequest = request
            effectiveRoomStateLocked()
        } ?: return
        if (!leaving) {
            applyRoomStateToPlayer(effective, computeExpectedPosition(System.currentTimeMillis()))
        }
        // 必须在 applyRoomStateToPlayer 之后设置：它会把自己的抑制窗口改成 400ms。
        // 听众看门狗（correctListenerPlayback）读的是权威房态，请求在途期间放开它
        // 会把还没确认的本地改动当成漂移，直接覆盖回去（表现为「刚加的歌消失了」）。
        synchronized(lock) {
            if (pendingQueueRequest?.requestId == request.requestId) {
                suppressLocalUntilMs = System.currentTimeMillis() + QUEUE_REQUEST_PENDING_MS
            }
        }
    }

    private suspend fun rollbackOptimisticQueue(request: PendingQueueRequest) {
        val effective = synchronized(lock) {
            if (pendingQueueRequest?.requestId == request.requestId) pendingQueueRequest = null
            suppressLocalUntilMs = System.currentTimeMillis() + 400L
            effectiveRoomStateLocked()
        } ?: return
        if (!leaving) {
            applyRoomStateToPlayer(effective, computeExpectedPosition(System.currentTimeMillis()))
        }
    }

    /**
     * 权威房态 + 在途乐观队列 = 需要展示/应用给播放器的状态。
     *
     * 覆盖层不是「用某个快照盖掉房态」，而是把我们的变更**重放到最新权威队列**上
     * （与服务端相同的语义）：这样别人在请求在途期间到达的增删不会被我方覆盖丢失，
     * 我方那条改动也立刻可见。服务端确认（版本前进且队列已包含这次变更）后覆盖层退休。
     */
    private fun effectiveRoomStateLocked(): ListenTogetherRoomState? {
        val current = room ?: return null
        val pending = pendingQueueRequest ?: return current
        if (current.version > pending.baseVersion && containsQueueMultiset(current.queue, pending.queue)) {
            pendingQueueRequest = null
            // 覆盖层退休：交还给权威房态，恢复正常看门狗节奏
            suppressLocalUntilMs = System.currentTimeMillis() + 400L
            return current
        }
        val mutation = pending.mutation
        if (mutation == null) {
            // 只能用整快照表达（稳定键为空/操作超限）：退化为直接展示目标队列
            val index = pending.currentIndex.coerceIn(0, pending.queue.lastIndex.coerceAtLeast(0))
            return current.copy(
                queue = pending.queue,
                currentIndex = index,
                track = pending.queue.getOrNull(index) ?: current.track,
            )
        }
        val replayed = applyListenTogetherQueueMutation(
            roomQueue = current.queue,
            roomCurrentIndex = current.currentIndex,
            mutation = mutation,
            targetCurrentStableKey = pending.queue.getOrNull(pending.currentIndex)?.stableKey,
        )
        if (replayed.queue.isEmpty()) return current
        val index = if (replayed.targetCurrentIndex != null) {
            replayed.targetCurrentIndex
        } else {
            replayed.currentIndex.coerceIn(0, replayed.queue.lastIndex)
        }
        return current.copy(
            queue = replayed.queue,
            currentIndex = index,
            track = replayed.queue.getOrNull(index) ?: current.track,
        )
    }

    /** [container] 按 stableKey 多重集合是否包含 [subset]，用于判断远端是否已合并本地变更。 */
    private fun containsQueueMultiset(
        container: List<ListenTogetherTrack>,
        subset: List<ListenTogetherTrack>,
    ): Boolean {
        if (subset.isEmpty()) return true
        val counts = mutableMapOf<String, Int>()
        container.forEach { counts[it.stableKey] = (counts[it.stableKey] ?: 0) + 1 }
        subset.forEach { track ->
            val remaining = (counts[track.stableKey] ?: 0) - 1
            if (remaining < 0) return false
            counts[track.stableKey] = remaining
        }
        return true
    }

    /** 听众乐观队列请求；服务端确认（版本前进）或超时后清除。 */
    private data class PendingQueueRequest(
        val requestId: String,
        /** 本次请求想达到的队列（用于判断服务端是否已经合并这次变更）。 */
        val queue: List<ListenTogetherTrack>,
        val currentIndex: Int,
        /** 增量操作；为 null 表示这次变更只能用整快照表达。 */
        val mutation: ListenTogetherQueueMutation?,
        /** 发起请求时的权威房态版本；服务端在该版本之上的广播视为已处理。 */
        val baseVersion: Long,
        val createdAtMs: Long,
    )

    suspend fun updateSettings(
        allowMemberControl: Boolean?,
        autoPauseOnMemberChange: Boolean?,
        shareAudioLinks: Boolean?,
    ): ListenTogetherSnapshot {        val state = room ?: return fail("不在房间中")
        if (!isControllerRole(sessionRole)) return fail("只有控制者可以修改房间设置")
        val current = state.settings
        val next = current.copy(
            allowMemberControl = allowMemberControl ?: current.allowMemberControl,
            autoPauseOnMemberChange = autoPauseOnMemberChange ?: current.autoPauseOnMemberChange,
            shareAudioLinks = shareAudioLinks ?: current.shareAudioLinks,
        )
        AppLog.info(TAG, "updateSettings：allowMemberControl=$allowMemberControl autoPause=$autoPauseOnMemberChange shareAudioLinks=$shareAudioLinks")
        val outcome = sendControlEvent(
            ListenTogetherControlEvent(type = "UPDATE_SETTINGS", roomSettings = next),
            awaitResult = true,
        )
        AppLog.info(TAG, "updateSettings 结果：${outcomeLog(outcome)}")
        synchronized(lock) {
            if (outcome?.ok != true) lastError = describeListenTogetherError(outcome?.error)
        }
        if (outcome?.ok == true && !next.shareAudioLinks) {
            // 关闭直链共享：服务端会清空缓存，本地也不再保留/使用共享流
            clearSharedStream()
            publishedLinkKey = null
            publishedLinkUrl = null
        }
        notifyState()
        return snapshot()
    }

    suspend fun sendControlEvent(event: ListenTogetherControlEvent): ListenTogetherSnapshot {
        AppLog.info(TAG, "sendControlEvent(API) 入口：${controlEventLog(event)}")
        if (room == null) return fail("不在房间中")
        val enriched = enrichControlEvent(event)
        val outcome = sendControlEvent(enriched, awaitResult = true)
        AppLog.info(TAG, "sendControlEvent(API) 结果：${controlEventLog(enriched)} ${outcomeLog(outcome)}")
        synchronized(lock) {
            if (outcome?.ok == true) {
                lastError = null
                notice = "控制指令已提交"
            } else {
                lastError = describeListenTogetherError(outcome?.error)
            }
        }
        notifyState()
        return snapshot()
    }

    // ---------------------------------------------------------------- 会话与连接

    private fun adoptSession(baseUrl: String, response: ListenTogetherRoomResponse) {
        val state = response.state
        val now = System.currentTimeMillis()
        AppLog.debug(TAG, "adoptSession：roomId=${response.roomId} role=${response.role} wsUrl=${response.wsUrl?.let { redactListenTogetherUrl(it) } ?: "下发明文构建"} serverUrl=${redactListenTogetherUrl(baseUrl)}")
        synchronized(lock) {
            sessionBaseUrl = baseUrl
            sessionUserUuid = response.userUuid ?: config.userUuid
            sessionNickname = response.nickname ?: config.nickname
            sessionRole = response.role ?: resolveRoleFromState(state, config.userUuid)
            token = response.token ?: token
            joinSecret = response.joinSecret ?: joinSecret
            wsUrl = response.wsUrl ?: buildWsUrl(baseUrl, response.roomId, response.token)
            autoPauseOnJoin = response.autoPauseOnJoin
            if (state != null) {
                room = mergeRoomState(room, state)
                expectedPositionMs = responseExpectedPosition(state)
                expectedAtLocalMs = now
            }
            connection = "connecting"
            lastError = null
            notice = null
            reconnectAttempts = 0
            nextReconnectAtMs = 0L
            config = config.copy(
                serverUrl = config.serverUrl.ifBlank { baseUrl },
                nickname = response.nickname ?: config.nickname,
                session = ListenTogetherSavedSession(
                    baseUrl = baseUrl,
                    roomId = response.roomId ?: state?.roomId.orEmpty(),
                    userUuid = sessionUserUuid.orEmpty(),
                    nickname = sessionNickname.orEmpty(),
                    role = sessionRole.orEmpty(),
                    token = response.token,
                    memberSecret = response.memberSecret,
                    joinSecret = response.joinSecret,
                    wsUrl = wsUrl,
                ),
            )
            persistConfig()
        }
        if (isControllerRole(response.role)) {
            reported = buildLocalReport(System.currentTimeMillis())
        } else {
            reported = null
        }
    }

    private fun connectWebSocket() {
        val url = synchronized(lock) { wsUrl ?: buildWsUrl(sessionBaseUrl, room?.roomId, token) }
        if (url.isNullOrBlank()) {
            AppLog.error(TAG, "连接失败：缺少 WebSocket 地址（baseUrl=${sessionBaseUrl?.let { redactListenTogetherUrl(it) } ?: "-"} roomId=${room?.roomId ?: "-"}）")
            synchronized(lock) {
                connection = "error"
                lastError = "缺少 WebSocket 地址"
            }
            notifyState()
            return
        }
        AppLog.info(TAG, "connectWebSocket：${redactListenTogetherUrl(url)} roomId=${room?.roomId ?: "-"}")
        val client = ListenTogetherWebSocketClient(
            onOpen = {
                scope.launch {
                    synchronized(lock) {
                        wsOpen = true
                        // 新连接：清掉上一轮未完成的 ping 记录并允许立刻发一次，
                        // 让时钟偏移尽快拿到往返样本（否则要等满一个 ping 周期）。
                        lastPingAtMs = 0L
                    }
                    clockSync.resetPingTracking()
                    notifyState()
                }
            },
            onText = { text -> scope.launch { handleSocketText(text) } },
            onClosed = { scope.launch { handleSocketLost("连接已断开") } },
            onFailure = { message -> scope.launch { handleSocketLost(message) } },
        )
        val previous = synchronized(lock) {
            val old = wsClient
            wsClient = client
            old
        }
        runCatching { previous?.close() }
        synchronized(lock) { connection = "connecting" }
        notifyState()
        client.connect(url)
    }

    private fun closeSocket() {
        val client = synchronized(lock) {
            wsOpen = false
            val current = wsClient
            wsClient = null
            current
        }
        AppLog.info(TAG, "closeSocket：主动关闭连接（leaving=$leaving pendingResults=${pendingResults.size}）")
        clockSync.resetPingTracking()
        runCatching { client?.close() }
        pendingResults.values.forEach { it.complete(ControlOutcome(false, "连接已关闭", null)) }
        pendingResults.clear()
    }

    private suspend fun handleSocketLost(message: String) {
        val now = System.currentTimeMillis()
        val lastInbound = synchronized(lock) { lastInboundAtMs }
        val idleMs = if (lastInbound > 0L) (now - lastInbound).coerceAtLeast(0L) else -1L
        AppLog.warn(TAG, "WS 连接断开：reason=$message 上次收到消息距今=${if (idleMs < 0) "从未" else "${idleMs}ms"}（连接超时阈值 12s / 状态静默兜底 ${STATE_REFRESH_SILENCE_MS}ms）")
        val retry = synchronized(lock) {
            wsOpen = false
            pendingResults.values.forEach { it.complete(ControlOutcome(false, message, null)) }
            pendingResults.clear()
            if (leaving || config.session == null) {
                connection = "disconnected"
                false
            } else {
                connection = "reconnecting"
                if (lastError.isNullOrBlank()) lastError = message
                reconnectAttempts += 1
                val delayMs = (1_000L shl (reconnectAttempts - 1).coerceIn(0, 5)).coerceAtMost(MAX_RECONNECT_DELAY_MS)
                nextReconnectAtMs = System.currentTimeMillis() + delayMs
                true
            }
        }
        if (retry) {
            val delay = synchronized(lock) { nextReconnectAtMs - System.currentTimeMillis() }
            AppLog.info(TAG, "计划重连：第 ${synchronized(lock) { reconnectAttempts }} 次，${delay}ms 后（上限 ${MAX_RECONNECT_DELAY_MS}ms）")
        } else {
            AppLog.info(TAG, "不重连：leaving=$leaving 已保存会话=${synchronized(lock) { config.session != null }}")
        }
        notifyState()
        if (retry) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (reconnectInFlight) {
            AppLog.debug(TAG, "scheduleReconnect 跳过：已有重连在途")
            return
        }
        reconnectInFlight = true
        reconnectJob = scope.launch {
            try {
                val saved = synchronized(lock) { config.session } ?: run {
                    AppLog.warn(TAG, "scheduleReconnect 中止：无已保存会话")
                    return@launch
                }
                AppLog.info(TAG, "scheduleReconnect 启动：roomId=${saved.roomId} baseUrl=${redactListenTogetherUrl(saved.baseUrl)}")
                connection = "connecting"
                notifyState()
                rejoinSession(saved)
            } finally {
                reconnectInFlight = false
            }
        }
    }

    private suspend fun rejoinSession(saved: ListenTogetherSavedSession) {
        val rejoinStarted = System.currentTimeMillis()
        if (leaving) {
            AppLog.debug(TAG, "rejoinSession 跳过：正在离开房间")
            return
        }
        val baseUrl = normalizeListenTogetherBaseUrlOrNull(saved.baseUrl) ?: run {
            AppLog.warn(TAG, "rejoinSession 中止：已保存的 baseUrl 非法，清除会话")
            clearSession(clearPersistedSession = true)
            return
        }
        AppLog.info(TAG, "rejoinSession 发起：roomId=${saved.roomId} baseUrl=${redactListenTogetherUrl(baseUrl)} 第 ${synchronized(lock) { reconnectAttempts }} 次")
        val request = ListenTogetherJoinRoomRequest(
            userUuid = saved.userUuid.ifBlank { config.userUuid },
            nickname = saved.nickname.ifBlank { resolveNickname(null) },
            memberSecret = saved.memberSecret,
            joinSecret = saved.joinSecret,
        )
        val result = withContext(Dispatchers.IO) { http.joinRoom(baseUrl, saved.roomId, request) }
        val response = result.value
        if (leaving || synchronized(lock) { config.session == null }) {
            AppLog.debug(TAG, "rejoinSession 结果作废：期间已离开/会话被清除")
            return
        }
        if (result.error != null || response == null || !response.ok) {
            val rawError = result.error ?: response?.error
            val sessionGone = isSessionGone(rawError)
            val attempt = synchronized(lock) {
                reconnectAttempts += 1
                nextReconnectAtMs = System.currentTimeMillis() +
                    (1_000L shl (reconnectAttempts - 1).coerceIn(0, 5)).coerceAtMost(MAX_RECONNECT_DELAY_MS)
                lastError = describeListenTogetherError(rawError)
                connection = if (sessionGone) "disconnected" else "reconnecting"
                reconnectAttempts
            }
            AppLog.warn(TAG, "rejoinSession 失败：roomId=${saved.roomId} error=$rawError 第 $attempt 次 ${if (sessionGone) "会话已失效，清除" else "${synchronized(lock) { nextReconnectAtMs - System.currentTimeMillis() }}ms 后重试"}（${System.currentTimeMillis() - rejoinStarted}ms）")
            if (sessionGone) clearSession(clearPersistedSession = true)
            notifyState()
            return
        }
        AppLog.info(TAG, "rejoinSession 成功：roomId=${response.roomId ?: saved.roomId} role=${response.role}（${System.currentTimeMillis() - rejoinStarted}ms）")
        synchronized(lock) {
            sessionBaseUrl = baseUrl
            sessionUserUuid = response.userUuid ?: saved.userUuid
            sessionNickname = response.nickname ?: saved.nickname
            sessionRole = response.role ?: saved.role.ifBlank { "listener" }
            token = response.token ?: token
            joinSecret = response.joinSecret ?: joinSecret
            wsUrl = response.wsUrl ?: buildWsUrl(baseUrl, response.roomId, response.token)
            autoPauseOnJoin = response.autoPauseOnJoin
            reconnectAttempts = 0
            nextReconnectAtMs = 0L
            lastError = null
            notice = "已重新连接房间"
            config = config.copy(
                session = saved.copy(
                    baseUrl = baseUrl,
                    roomId = response.roomId ?: saved.roomId,
                    userUuid = sessionUserUuid.orEmpty(),
                    nickname = sessionNickname.orEmpty(),
                    role = sessionRole.orEmpty(),
                    token = response.token,
                    memberSecret = response.memberSecret ?: saved.memberSecret,
                    joinSecret = response.joinSecret ?: saved.joinSecret,
                    wsUrl = wsUrl,
                ),
            )
            resetClientControlIdentityLocked()
            persistConfig()
        }
        response.state?.let { state ->
            applyAuthoritativeState(
                state = state,
                expectedPosition = responseExpectedPosition(state),
                causedBy = null,
                applyToPlayer = !isControllerRole(sessionRole),
            )
        }
        connectWebSocket()
        notifyState()
    }

    private fun isSessionGone(rawError: String?): Boolean {
        val lower = rawError?.lowercase().orEmpty()
        return "room closed" in lower || "room not initialized" in lower ||
            "not in room" in lower || "member not in room" in lower || "unauthorized" in lower
    }

    private suspend fun restoreSavedSession() {
        val saved = synchronized(lock) { config.session }
        if (saved == null) {
            AppLog.debug(TAG, "restoreSavedSession：无已保存会话，跳过")
            return
        }
        if (saved.roomId.isBlank() || saved.baseUrl.isBlank()) {
            AppLog.warn(TAG, "restoreSavedSession：已保存会话字段缺失（roomId/baseUrl 为空），跳过")
            return
        }
        AppLog.info(TAG, "restoreSavedSession：恢复会话 roomId=${saved.roomId} baseUrl=${redactListenTogetherUrl(saved.baseUrl)} role=${saved.role}")
        synchronized(lock) {
            sessionBaseUrl = normalizeListenTogetherBaseUrlOrNull(saved.baseUrl)
            sessionUserUuid = saved.userUuid
            sessionNickname = saved.nickname
            sessionRole = saved.role.ifBlank { "listener" }
            joinSecret = saved.joinSecret
            token = saved.token
            connection = "connecting"
        }
        notifyState()
        scope.launch { rejoinSession(saved) }
    }

    private fun clearSession(clearPersistedSession: Boolean) {
        AppLog.info(TAG, "clearSession：清除会话（同时清持久化=$clearPersistedSession）")
        // 会话结束：软校正状态一并清空，且必须立刻把用户倍速恢复回去
        resetSoftSyncRate()
        // 时钟偏移只对当前连接有效：下次进房间按新连接的 RTT 重新校准，
        // 否则会拿着旧网络环境的偏移去推算进度。
        clockSync.reset()
        clockSyncPingSupported = null
        pendingSeekSample = null
        observedSeekRebuildMs = null
        rebuildPausedUntilMs = 0L
        rebuildNoProgressTicks = 0
        rebuildStabilizeUntilMs = 0L
        clearSharedStream()
        publishedLinkKey = null
        publishedLinkUrl = null
        linkRequestKey = null
        unavailableLinkKey = null
        synchronized(lock) {
            room = null
            token = null
            joinSecret = null
            sessionBaseUrl = null
            sessionUserUuid = null
            sessionNickname = null
            sessionRole = null
            wsUrl = null
            wsOpen = false
            connection = "disconnected"
            expectedPositionMs = 0L
            expectedAtLocalMs = 0L
            reported = null
            lastOutboundAtMs = 0L
            lastInboundAtMs = 0L
            reconnectAttempts = 0
            nextReconnectAtMs = 0L
            if (clearPersistedSession) {
                config = config.copy(session = null)
                persistConfig()
            }
        }
    }

    // ---------------------------------------------------------------- WS 消息处理

    private suspend fun handleSocketText(text: String) {
        val envelope = runCatching {
            ListenTogetherJson.decodeFromString<ListenTogetherSocketEnvelope>(text)
        }.getOrNull() ?: return
        synchronized(lock) { lastInboundAtMs = System.currentTimeMillis() }
        AppLog.debug(TAG, "WS 收到消息：type=${envelope.type} version=${envelope.version ?: "-"} cause=${envelope.causedBy?.type ?: "-"}")
        // pong 自带往返信息，走 onPong 精确算 RTT；其余消息的 nowMs 只是单向样本，
        // 仅用于「还没拿到往返样本前」把偏移垫住。
        val isPong = envelope.type == "pong" || envelope.type == "np_pong"
        if (!isPong) clockSync.onServerMessage(envelope.nowMs, envelope.type)
        when (envelope.type) {
            "welcome" -> handleWelcome(envelope)
            "room_state_updated", "room_resumed" -> handleRoomStateMessage(envelope, message = null)
            "room_suspended" -> {
                handleRoomStateMessage(envelope, message = "控制者已离线，房间已挂起")
                synchronized(lock) { notice = "控制者已离线，等待其重连" }
                notifyState()
            }

            "room_closed" -> handleRoomClosed(envelope.message)
            "control_result" -> handleControlResult(envelope)
            "pong" -> {
                // 旧版 ping 不带 t：服务端只回 nowMs，没有回显就没有可靠的 RTT，
                // 只能按「记录的发送时刻」估一个（有记录时仍是往返样本）。
                clockSync.onPong(envelope.nowMs, echoedElapsedMs = null, reason = "pong")
            }

            "np_pong" -> {
                clockSyncPingSupported = true
                clockSync.onPong(envelope.nowMs, echoedElapsedMs = envelope.t, reason = "np_pong")
            }

            "link_requested" -> handleLinkRequested(envelope)

            "error" -> {
                // 老服务端不认 np_ping：退回旧版 ping，否则时钟同步永远拿不到往返样本
                if (envelope.message?.contains("np_ping", ignoreCase = true) == true) {
                    clockSyncPingSupported = false
                }
                synchronized(lock) { lastError = describeListenTogetherError(envelope.message) }
                notifyState()
            }

            else -> Unit
        }
    }

    private suspend fun handleWelcome(envelope: ListenTogetherSocketEnvelope) {
        AppLog.info(TAG, "handleWelcome：收到服务端 welcome role=${envelope.role ?: "-"} nickname=${envelope.nickname ?: "-"} userUuid=${envelope.userUuid ?: "-"} hasState=${envelope.state != null}")
        synchronized(lock) {
            wsOpen = true
            connection = "connected"
            reconnectAttempts = 0
            nextReconnectAtMs = 0L
            lastError = null
            envelope.userUuid?.let { sessionUserUuid = it }
            envelope.role?.let { sessionRole = it }
            envelope.nickname?.let { sessionNickname = it }
        }
        val state = envelope.state
        if (state != null) {
            applyAuthoritativeState(
                state = state,
                // 服务端附带的 expectedPositionMs 是「它发消息那一刻」的值，只做兜底；
                // 正常路径按播放基线 + 已补偿的时钟偏移重新投影到本机此刻。
                expectedPosition = responseExpectedPosition(state, fallbackMs = envelope.expectedPositionMs),
                causedBy = null,
                applyToPlayer = !isControllerRole(envelope.role),
            )
        }
        if (isControllerRole(envelope.role) || isControllerRole(sessionRole)) {
            // 重连回来时以本地播放器为准把房间状态拉回来（控制者离线期间听众无法控制）
            resetSoftSyncRate()
            pushLocalSnapshotToRoom()
        }
        notifyState()
    }

    private suspend fun handleRoomStateMessage(envelope: ListenTogetherSocketEnvelope, message: String?) {
        val state = envelope.state
        val mutation = envelope.queueMutation
        val resolved = when {
            state == null && mutation != null -> replayIncomingQueueMutation(envelope, mutation)
            else -> state
        } ?: run {
            AppLog.warn(TAG, "房态消息无法解析：type=${envelope.type} version=${envelope.version ?: "-"} state=${state != null} mutation=${mutation != null}")
            return
        }
        AppLog.debug(TAG, "房态更新：type=${envelope.type} version=${resolved.version} cause=${envelope.causedBy?.type ?: "-"} roomPlaying=${resolved.playback.state}")
        val expected = responseExpectedPosition(resolved, fallbackMs = envelope.expectedPositionMs)
        val applyToPlayer = !(isControllerRole(sessionRole) && isSelfCause(envelope.causedBy))
        applyAuthoritativeState(
            state = resolved,
            expectedPosition = expected,
            causedBy = envelope.causedBy,
            applyToPlayer = applyToPlayer,
        )
        if (message != null) synchronized(lock) { notice = message }
        notifyState()
    }

    /**
     * 服务端只下发增量（没有整队列快照）时，在本地房态上重放它。
     *
     * worker 目前始终下发全量 `state`，这个分支是给「只发操作」的实现在用的：
     *  - `baseRoomVersion` 高于本地版本 → 我们落后了，不能猜，交给 35s 静默兜底刷新；
     *  - 低于本地版本 → 基线过期，但重放语义本来就是「在最新队列上应用」，
     *    所以仍按本地队列做 rebase，而不是用旧快照整队覆盖。
     */
    private fun replayIncomingQueueMutation(
        envelope: ListenTogetherSocketEnvelope,
        mutation: ListenTogetherQueueMutation,
    ): ListenTogetherRoomState? {
        val base = synchronized(lock) { room } ?: return null
        if (mutation.baseRoomVersion > base.version) return null
        val result = applyListenTogetherQueueMutation(
            roomQueue = base.queue,
            roomCurrentIndex = base.currentIndex,
            mutation = mutation,
            targetCurrentStableKey = envelope.track?.stableKey,
        )
        val nextIndex = if (result.queue.isEmpty()) -1
        else result.currentIndex.coerceIn(0, result.queue.lastIndex)
        return base.copy(
            queue = result.queue,
            currentIndex = nextIndex,
            track = result.queue.getOrNull(nextIndex),
            version = (envelope.version ?: (base.version + 1)).coerceAtLeast(base.version + 1),
        )
    }

    private suspend fun handleControlResult(envelope: ListenTogetherSocketEnvelope) {
        val response = envelope.result
        val ok = envelope.ok == true && response?.ok != false
        val error = envelope.message ?: response?.error
        val eventId = envelope.causedBy?.eventId
        val applied = response?.applied
        // 先快照播放意图再 complete 等待者：complete 会立刻恢复 requestControl 协程，
        // 后者会清掉 pendingPlaybackIntents；兜底必须基于这里提前取到的值。
        val intentSnapshot = eventId?.let { pendingPlaybackIntents[it] }
        AppLog.info(
            TAG,
            "control_result：ok=$ok eventId=${eventId?.take(20) ?: "-"} applied=${applied?.type ?: "-"}" +
                " room.version=${applied?.state?.version ?: applied?.version ?: "-"}" +
                " error=${error ?: "-"}" +
                " matchedPending=${if (eventId != null && pendingResults.containsKey(eventId)) "yes" else "no"}",
        )
        if (eventId != null) {
            pendingResults.remove(eventId)?.complete(
                ControlOutcome(ok, error, response?.applied),
            )
        }
        if (applied?.state != null) {
            // 不再对 control_result 的房态做「已应用」去重。
            //
            // 血泪史：这里先后用过「version 比较」和「结构指纹 + 播放器态兜底」两种去重，
            // 两次都让 pause/play 按钮偶发失效——服务端广播先到时版本号/指纹一致，回执就被
            // 整段跳过；而广播那次 apply 可能正赶上流水线重建被抑制，播放态根本没生效。
            //
            // 现在：applied.state 无条件 apply（只排控制者自己的回声、他人的 REQUEST 回执）。
            // 幂等性由 applyRoomStateToPlayer 自身保证——它按「播放器当前实际状态」判定结构差异：
            // room_state_updated 已经切歌的话，回执再来时 sameTrack=true，planner 不会
            // shouldReloadPlaylist，因此不会重复 setQueue、也不会把进度 prepare 回 0。
            // 「切歌闪回 0」由 setQueue(startMs) 与 pendingSeekSample 两处专门防护，不靠这里。
            val cause = applied.causedBy ?: envelope.causedBy
            val selfControllerEcho = isControllerRole(sessionRole) && isSelfCause(cause)
            // 归属 gate（对齐 Android shouldApplyListenTogetherCommittedControlState）：
            // 听众只应用自己发起的 REQUEST_* 回执，避免房间里别人控制时回执串到本机覆盖本地。
            val isRequestResult = cause?.type?.startsWith("REQUEST_") == true
            val foreignRequestResult = !isControllerRole(sessionRole) &&
                isRequestResult && !isSelfCause(cause)
            if (foreignRequestResult) {
                AppLog.debug(
                    TAG,
                    "control_result 忽略他人请求回执：cause=${cause?.type} causeUser=${cause?.userUuid ?: cause?.userId ?: "-"}",
                )
            } else {
                val resolvedState = resolveAppliedPlaybackStateWithPendingIntent(
                    appliedState = applied.state,
                    cause = cause,
                    eventId = eventId,
                    intentSnapshot = intentSnapshot,
                    ok = ok,
                )
                AppLog.debug(
                    TAG,
                    "control_result 应用房态：room.version=${room?.version} applied.version=${applied.state.version}" +
                        " appliedPlaying=${resolvedState.playback.state} rawPlaying=${applied.state.playback.state}" +
                        " selfControllerEcho=$selfControllerEcho",
                )
                applyAuthoritativeState(
                    state = resolvedState,
                    expectedPosition = responseExpectedPosition(resolvedState, fallbackMs = applied.expectedPositionMs),
                    causedBy = cause,
                    applyToPlayer = !selfControllerEcho,
                )
            }
        }
        if (!ok) {
            // 控制者的队列事件被拒绝（基线过期 / 操作不被接受）时清掉上报基线，
            // 下一轮 tick 会用最新房态版本重新构造整快照，而不是当作「已同步」静默丢掉改动。
            if (isControllerRole(sessionRole)) reported = null
            AppLog.warn(TAG, "control_result 控制被拒：error=$error（已翻译：${describeListenTogetherError(error)}）")
            synchronized(lock) { lastError = describeListenTogetherError(error) }
            notifyState()
        }
    }

    private fun handleRoomClosed(reason: String?) {
        AppLog.info(TAG, "房间已关闭：reason=${reason ?: "-"}")
        closeSocket()
        resetSoftSyncRate()
        clearSession(clearPersistedSession = true)
        synchronized(lock) {
            lastError = "房间已关闭" + (reason?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: "")
            notice = null
        }
        notifyState()
    }

    private suspend fun applyAuthoritativeState(
        state: ListenTogetherRoomState,
        expectedPosition: Long,
        causedBy: ListenTogetherCause?,
        applyToPlayer: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val wasSharing = synchronized(lock) { room?.settings?.shareAudioLinks == true }
        val effective = synchronized(lock) {
            // 权威房态照常前进；在途乐观请求的覆盖层由 effectiveRoomStateLocked 叠加，
            // 这样服务端确认前到达的他人改动不会因为覆盖而丢失。
            // 覆盖层是否退休由「版本前进 + 队列已包含这次变更」共同判断，这里不提前清。
            val merged = mergeRoomState(room, state)
            room = merged
            if (merged != null) {
                expectedPositionMs = expectedPosition
                expectedAtLocalMs = now
            }
            effectiveRoomStateLocked()
        }
        if (isControllerRole(sessionRole) && !wasSharing && state.settings.shareAudioLinks) {
            // 服务端确认重新开启直链共享：清掉上报基线，让 tick 重新上传当前曲目直链
            publishedLinkKey = null
            publishedLinkUrl = null
        }
        if (effective != null && applyToPlayer && !leaving) {
            // 听众先装填共享直链，再驱动播放器：这样切歌时 resolveInput 直接用权威直链，
            // 不会先播本地音源再 reload。LINK_UNAVAILABLE 时也在这里清掉共享流回退本地。
            syncSharedStreamFromRoom(effective, causedBy?.type)
            // 用带乐观覆盖层的 effective 房态，同时把触发源带进去：
            // 心跳只保活，判定时应当用更宽松的硬阈值。
            applyRoomStateToPlayer(effective, expectedPosition, causeType = causedBy?.type)
        }
        notifyState()
    }

    private fun mergeRoomState(current: ListenTogetherRoomState?, candidate: ListenTogetherRoomState): ListenTogetherRoomState {
        if (current == null) return candidate
        if (candidate.roomId.isNotBlank() && current.roomId.isNotBlank() &&
            !candidate.roomId.equals(current.roomId, ignoreCase = true)
        ) {
            return candidate
        }
        return if (candidate.version >= current.version) candidate else current
    }

    private fun isSelfCause(cause: ListenTogetherCause?): Boolean {
        val self = config.userUuid
        if (self.isBlank()) return false
        val causeUser = cause?.userUuid ?: cause?.userId ?: return false
        return causeUser.equals(self, ignoreCase = true)
    }

    /**
     * 听众 REQUEST_PLAY/PAUSE 回执的播放态兜底。
     *
     * worker 在 `await persist()` 之后才构造 applied payload，窗口内控制者新事件可能覆盖房态，
     * 于是「我发的 REQUEST_PAUSE」回执 ok、cause 也是自己，但 applied.state.playback.state 却是
     * playing。此时若照抄 applied 的播放态，desiredPlaying=true，pause 按钮就表现为「没反应」。
     *
     * 只在下面几条同时成立时改播放态字段（其它字段一律保留权威回执）：
     *  - 我是听众、回执 ok、cause 是自己；
     *  - eventId 命中刚发出的 REQUEST_PLAY/PAUSE 意图，且未超时；
     *  - applied 的播放态与意图相反；
     *  - applied.state.version 不比本地已见房态更新（说明它不是一条更新的权威态，
     *    只是竞态下回填的旧房态；若它确实更新，就尊重服务端，让后续广播校正）。
     */
    private fun resolveAppliedPlaybackStateWithPendingIntent(
        appliedState: ListenTogetherRoomState,
        cause: ListenTogetherCause?,
        eventId: String?,
        intentSnapshot: PendingPlaybackIntent?,
        ok: Boolean,
    ): ListenTogetherRoomState {
        if (!ok || isControllerRole(sessionRole) || !isSelfCause(cause)) return appliedState
        val intent = intentSnapshot ?: return appliedState
        if (System.currentTimeMillis() - intent.atMs > PENDING_PLAYBACK_INTENT_TTL_MS) return appliedState
        val appliedPlaying = appliedState.playback.state == "playing"
        if (appliedPlaying == intent.desiredPlaying) return appliedState
        val localVersion = synchronized(lock) { room?.version }
        if (localVersion != null && appliedState.version > localVersion) return appliedState
        AppLog.info(
            TAG,
            "control_result 播放态兜底：eventId=${eventId?.take(20) ?: "-"} applied.version=${appliedState.version}" +
                " localVersion=$localVersion appliedPlaying=$appliedPlaying desiredPlaying=${intent.desiredPlaying}" +
                " cause=${cause?.type}",
        )
        return appliedState.copy(
            playback = appliedState.playback.copy(state = if (intent.desiredPlaying) "playing" else "paused"),
        )
    }

    // ---------------------------------------------------------------- 共享音频直链

    /**
     * 控制者收到听众的 `link_requested` 后：用当前播放器的实际直链回一条 LINK_READY，
     * 没有可用直链则回 LINK_UNAVAILABLE，让听众回退到本地解析。
     */
    private suspend fun handleLinkRequested(envelope: ListenTogetherSocketEnvelope) {
        if (!isControllerRole(sessionRole)) return
        if (room?.settings?.shareAudioLinks != true) return
        val targetKey = envelope.requestTrackStableKey?.takeIf { it.isNotBlank() }
            ?: envelope.track?.stableKey?.takeIf { it.isNotBlank() }
            ?: return
        val roomKey = synchronized(lock) { room?.track?.stableKey }
        if (roomKey != null && roomKey != targetKey) return
        val hasUrl = container.player.currentAudioInput?.url
            ?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) } != null
        if (hasUrl) {
            publishAudioLink(force = true)
        } else {
            // 当前没有可分享的直链（本地文件/解析失败/尚未解析完成）：明确回 unavailable，
            // 让听众立刻回退本地解析，而不是等到请求超时。
            publishAudioLinkUnavailable(targetKey)
        }
    }

    /**
     * 控制者上报当前曲目的直链。
     *
     * 只在 shareAudioLinks 开启、自己是控制者、且当前曲目是可分享的在线歌曲时发送。
     * 直链取自 [PlayerManager.currentAudioInput]——它是播放器实际打开的音源，
     * 与听众解析到的地址同源；B 站/网易云的 headers 由听众按来源自行补齐。
     */
    private suspend fun publishAudioLink(force: Boolean = false) {
        if (!isController()) return
        if (synchronized(lock) { room?.settings?.shareAudioLinks } != true) return
        if (suppressLocalUntilMs > System.currentTimeMillis()) return
        val song = container.player.currentSong.value ?: return
        val track = song.toListenTogetherTrackOrNull() ?: return
        val input = container.player.currentAudioInput ?: return
        val url = input.url?.takeIf { it.startsWith("http://", true) || it.startsWith("https://", true) } ?: return
        val stableKey = track.stableKey
        val previousKey = publishedLinkKey
        val previousUrl = publishedLinkUrl
        val now = System.currentTimeMillis()
        if (!force && previousKey == stableKey && previousUrl == url &&
            now - publishedLinkAtMs < LINK_PUBLISH_MIN_INTERVAL_MS
        ) {
            return
        }
        // LINK_READY 的 track 要带 streamUrls，worker 用它做当前曲目的候选缓存；
        // 队列字段保持 strip（worker 会清掉队列里的候选地址），这里只发 track。
        val linkTrack = song.toListenTogetherTrackOrNull(listOf(url))
            ?: track.copy(streamUrl = url, streamUrls = listOf(url))
        val event = ListenTogetherControlEvent(
            type = "LINK_READY",
            positionMs = container.player.positionMs.value.coerceAtLeast(0L),
            currentIndex = container.player.currentIndex.value.coerceAtLeast(0),
            track = linkTrack,
            state = if (container.player.state.value == PlaybackState.PLAYING) "playing" else "paused",
            requestTrackStableKey = stableKey,
        )
        val outcome = sendControlEvent(event, awaitResult = false)
        if (outcome?.ok == true) {
            publishedLinkKey = stableKey
            publishedLinkUrl = url
            publishedLinkAtMs = now
            unavailableLinkKey = null
        }
    }

    /** tick 里的控制者直链检查：没有新解析结果时不做任何事。 */
    private suspend fun maybePublishAudioLink() {
        val song = container.player.currentSong.value ?: return
        val url = container.player.currentAudioInput?.url?.takeIf {
            it.startsWith("http://", true) || it.startsWith("https://", true)
        } ?: return
        val key = song.toListenTogetherTrackOrNull()?.stableKey ?: return
        if (key == publishedLinkKey && url == publishedLinkUrl) return
        publishAudioLink()
    }

    /** 控制者明确没有可用直链（解析失败）时通知房间清除缓存，听众回退本地解析。 */
    private suspend fun publishAudioLinkUnavailable(stableKey: String) {
        if (!isController()) return
        if (synchronized(lock) { room?.settings?.shareAudioLinks } != true) return
        val event = ListenTogetherControlEvent(
            type = "LINK_UNAVAILABLE",
            requestTrackStableKey = stableKey,
            track = room?.track?.copy(streamUrl = null, streamUrls = emptyList()),
        )
        sendControlEvent(event, awaitResult = false)
        publishedLinkKey = null
        publishedLinkUrl = null
    }

    /** 听众本地解析失败时请求控制者补发直链（对齐 Android ControllerLinkOwner.maybeRequest）。 */
    private suspend fun onLocalResolveFailed(song: Song) {
        val state = synchronized(lock) { room } ?: return
        val track = song.toListenTogetherTrackOrNull() ?: return
        if (isControllerRole(sessionRole)) {
            // 控制者解析失败：清掉自己上次上传的直链并通知房间，听众回退本地解析
            if (state.settings.shareAudioLinks == true) publishAudioLinkUnavailable(track.stableKey)
            return
        }
        if (state.settings.shareAudioLinks != true || state.roomStatus != "active") return
        if (track.stableKey != (state.track ?: state.queue.getOrNull(state.currentIndex))?.stableKey) return
        if (unavailableLinkKey == track.stableKey) return
        val now = System.currentTimeMillis()
        if (linkRequestKey == track.stableKey && now - linkRequestAtMs < LINK_REQUEST_THROTTLE_MS) return
        linkRequestKey = track.stableKey
        linkRequestAtMs = now
        val event = ListenTogetherControlEvent(
            type = "REQUEST_LINK",
            currentIndex = state.currentIndex,
            track = track,
            requestTrackStableKey = track.stableKey,
        )
        sendControlEvent(event, awaitResult = false)
        synchronized(lock) { notice = "已向控制者请求共享播放地址" }
        notifyState()
    }

    /**
     * 听众侧消费权威房态里的 streamUrl：设置共享流并在必要时重载当前曲目。
     *
     * 调用时机在 [applyRoomStateToPlayer] 之前，这样切歌时 playCurrent 会直接解析到共享直链，
     * 不用先播本地音源再 reload 一次。收到 LINK_UNAVAILABLE 则清掉共享流回退本地解析。
     */
    private suspend fun syncSharedStreamFromRoom(state: ListenTogetherRoomState, causeType: String?) {
        if (isControllerRole(sessionRole)) return
        val track = state.track ?: state.queue.getOrNull(state.currentIndex)
        val key = track?.stableKey?.takeIf { it.isNotBlank() }
        if (causeType == "LINK_UNAVAILABLE") {
            unavailableLinkKey = key
            clearSharedStream()
            return
        }
        if (state.settings.shareAudioLinks != true || state.roomStatus == "closed" || key == null) {
            clearSharedStream()
            return
        }
        val urls = buildList {
            track?.streamUrl?.let { add(it) }
            track?.streamUrls?.forEach { add(it) }
        }.map { it.trim() }.filter { it.startsWith("http://", true) || it.startsWith("https://", true) }.distinct()
        if (urls.isEmpty()) {
            clearSharedStream()
            return
        }
        // 控制者重新发布了可用直链：解除之前的「不可用」标记
        if (unavailableLinkKey == key) unavailableLinkKey = null
        container.online.setSharedStreamUrls(key, urls)
        val song = track.toSongOrNull()
        val alreadyUsing = song != null && container.online.hasSharedStreamFor(song) &&
            container.player.currentAudioInput?.url?.let { url -> url in urls } == true
        if (alreadyUsing) {
            if (!usingSharedStream) {
                usingSharedStream = true
                notifyState()
            }
            return
        }
        // 共享流刚到（或与上次应用的直链不同）：让当前曲目重新解析、打开共享直链。
        // 只有当前曲目身份匹配时才 reload，避免把还没切过来的旧曲目替换掉。
        // 关键：用「直链是否变化」而不是「当前音源 url 是否等于房间 url」判定——后者在本地
        // 因直链失效回退到备用音源后永远为假，会让每次心跳都 reload、把进度打回 0。
        val primaryUrl = urls.firstOrNull()
        val unchanged = key == lastAppliedSharedKey && primaryUrl == lastAppliedSharedUrl
        if (!unchanged && song != null && container.player.currentSong.value?.key == song.key) {
            lastAppliedSharedKey = key
            lastAppliedSharedUrl = primaryUrl
            usingSharedStream = false
            container.player.reloadCurrentForSharedStream()
            usingSharedStream = true
            notifyState()
        } else if (!unchanged) {
            lastAppliedSharedKey = key
            lastAppliedSharedUrl = primaryUrl
        }
    }

    private fun clearSharedStream() {
        container.online.setSharedStreamUrls(null, emptyList())
        usingSharedStream = false
        lastAppliedSharedKey = null
        lastAppliedSharedUrl = null
    }

    // ---------------------------------------------------------------- 权威状态 → 播放器

    /**
     * 把权威房态应用到播放器 —— Android `ListenTogetherPlayerStateApplier.apply()` 的桌面版。
     *
     * 结构性判断（切歌/队列/播放态/seek）统一交给 [resolveListenTogetherPlayerSyncPlan]：
     * 先组装 [ListenTogetherPlayerSyncContext]（其中归零保护/切歌宽限期/等待共享流全部在这里
     * 算出），再由返回的 plan 决定要不要 setQueue / seek / play / pause。
     * 小偏差（[SOFT_SYNC_MIN_DRIFT_MS] ~ 硬阈值）的软校正由 [correctListenerPlayback] 依据同一
     * 个 planner 的 [ListenTogetherPlayerSyncPlan.signedDriftMs] 驱动，避免同一份漂移两条路径重复处理。
     *
     * @param causeType 触发这次应用的事件类型（`HEARTBEAT` 时用宽松硬阈值）。
     */
    private suspend fun applyRoomStateToPlayer(
        state: ListenTogetherRoomState,
        expectedPosition: Long,
        causeType: String? = null,
    ) {
        applyMutex.withLock {
            val player = container.player
            val tracks = state.queue
            val songs = tracks.mapNotNull { it.toSongOrNull() }
            if (songs.isEmpty()) return
            val index = state.currentIndex.coerceIn(0, songs.lastIndex)
            val targetSong = songs.getOrNull(index) ?: return
            val roomPlaying = state.playback.state == "playing"
            val currentKey = player.currentSong.value?.key
            val sameTrack = currentKey == targetSong.key
            val sameQueue = player.queue.value.map { it.key } == songs.map { it.key }
            val trackChanging = !sameTrack || player.currentIndex.value != index
            // Android QueueSync 的 indexChanged / queueChanged 对应物。
            val targetIndexChanged = trackChanging
            val playbackContextChanged = !sameQueue

            // 用最新房态基线重新投影到「此刻」——不要用消息里那条已经过了一个单程延迟的
            // expectedPosition。切歌时带上落点前瞻，作为新曲目的起始位置一次性交给 Player。
            val now = System.currentTimeMillis()
            val expectedNow = clampPosition(
                expectedRoomPositionAt(state, now, fallbackMs = expectedPosition),
                targetSong.durationMs,
            )
            val localPosition = player.positionMs.value.coerceAtLeast(0L)
            val awaitingStream = isAwaitingAuthoritativeStream(state, targetSong)
            val graceActive = isTrackSwitchGracePeriodActive(now)
            val ignoreZeroRollback = shouldIgnoreUnexpectedZeroPositionRollback(
                causeType = causeType,
                desiredPlaying = roomPlaying,
                expectedPositionMs = expectedNow,
                localPositionMs = localPosition,
                playbackContextChanged = playbackContextChanged,
                targetIndexChanged = targetIndexChanged,
                zeroPositionRollbackGuardMs = ZERO_POSITION_ROLLBACK_GUARD_MS,
            )
            val syncContext = ListenTogetherPlayerSyncContext(
                playbackContextChanged = playbackContextChanged,
                targetIndexChanged = targetIndexChanged,
                desiredPlaying = roomPlaying,
                localPlaying = player.state.value == PlaybackState.PLAYING,
                localPlaybackAlreadyStarting = player.state.value == PlaybackState.PREPARING,
                awaitingAuthoritativeStream = awaitingStream,
                expectedPositionMs = expectedNow,
                localPositionMs = localPosition,
                ignoreUnexpectedZeroPositionRollback = ignoreZeroRollback,
                trackSwitchGracePeriodActive = graceActive,
                forcePositionSync = causeType == CAUSE_LISTENER_SAFETY_RESUME,
                causeType = causeType,
                trackSwitchForceSyncMs = TRACK_SWITCH_FORCE_SYNC_MS,
                heartbeatDriftForceSyncMs = HEARTBEAT_HARD_SYNC_DRIFT_MS,
                playingDriftForceSyncMs = HARD_SYNC_DRIFT_MS,
                pausedDriftForceSyncMs = PAUSED_HARD_SYNC_DRIFT_MS,
            )
            val plan = resolveListenTogetherPlayerSyncPlan(syncContext)
            logSyncPlanDecision("applyRoomState", syncContext, plan)
            if (ignoreZeroRollback) {
                AppLog.info(TAG, "归零保护命中：cause=$causeType expected=$expectedNow local=$localPosition 采用本地进度（避免被硬 seek 回 0）")
            }
            if (graceActive) {
                AppLog.debug(TAG, "切歌宽限期生效：cause=$causeType 距上次切歌 ${System.currentTimeMillis() - lastTrackSwitchAtMs}ms / ${TRACK_SWITCH_GRACE_PERIOD_MS}ms")
            }

            // 实际是否重建了队列：Android 由 `songs.isEmpty()` 等运行时条件决定，
            // 而非只看 plan；后续 play/pause 的「重建后再处理」都以此为开关。
            var reloadedPlaylist = false
            applyingRemote = true
            suppressLocalUntilMs = now + REMOTE_APPLY_SUPPRESS_MS
            try {
                // 只有 shouldReloadPlaylist 才重建队列（Android：避免无谓重建流水线）。
                if (plan.shouldReloadPlaylist) {
                    reloadedPlaylist = true
                    lastTrackSwitchAtMs = now
                    AppLog.info(TAG, "重建播放队列：sameTrack=$sameTrack cause=$causeType startMs=${plan.effectiveExpectedPositionMs} queued=${songs.size}")
                    resetSoftSyncRate()
                    val durationForStart = targetSong.durationMs.takeIf { it > 0 }
                        ?: player.durationMs.value.takeIf { it > 0 }
                        ?: 0L
                    val roomStartMs = clampPosition(
                        plan.effectiveExpectedPositionMs +
                            if (!isControllerRole(sessionRole) && roomPlaying) seekLeadEstimateMs() else 0L,
                        durationForStart,
                    )
                    if (sameTrack) {
                        // 同一首歌只是队列变了（排序/增删）：不要 autoPlay，否则会从头重播当前曲目
                        player.setQueue(songs, index, autoPlay = false)
                    } else {
                        // 重建时先不 autoPlay：play/pause 统一交给下面的 plan 处理
                        // （对齐 Android：先 playPlaylist，再由 shouldIssuePlay 决定 play）。
                        player.setQueue(songs, index, autoPlay = false, startMs = roomStartMs)
                    }
                }
                val repeat = listenTogetherRepeatModeToRepeatMode(state.playback.repeatMode)
                if (player.repeatMode.value != repeat) player.setRepeatMode(repeat)
                val shuffle = state.playback.shuffleEnabled == true
                if (player.shuffle.value != shuffle) player.setShuffle(shuffle)

                // 位置按 plan 执行；切歌后新曲目的位置已经由 setQueue(startMs) 打开，
                // 此时流水线可能还在重建、位置冻结，不能再用 drift 判一次 seek（会被 seek 回 0）。
                if (plan.shouldSeek && !plan.shouldReloadPlaylist) {
                    // 上一次 seek 的流水线还在重建时 positionMs 冻结（引擎把快照重置到落点，
                    // 直到新进程吐出第一帧才继续前进）。此时再按「local=0/落点」算出的大偏差
                    // 排一次 seek，只会把刚要出声的流水线拆掉重建，永远出不来声。
                    if (!canCorrectDriftNow()) {
                        AppLog.debug(TAG, "apply 的 HARD seek 被抑制：流水线重建中/稳定期")
                    } else {
                        val duration = player.durationMs.value.takeIf { it > 0 }
                            ?: targetSong.durationMs
                            ?: 0L
                        // 落点前瞻只对「听众 + 播放中」有意义：只有这时房间进度还会继续前进。
                        val leadMs = if (!isControllerRole(sessionRole) && plan.desiredPlaying) seekLeadEstimateMs() else 0L
                        seekListenerTo(plan.effectiveExpectedPositionMs, duration, leadMs = leadMs)
                    }
                }

                // 播放态：对齐 Android `applySyncPlan()`——play/pause 都是「状态有差异才下发」，
                // 重建队列后再按 plan 补一次（shouldResumeAfterReload / shouldForcePauseAfterRemoteLoad）。
                //
                // 「正在播」必须同时满足「state=PLAYING」与「音频引擎真的打开的是目标曲目」。
                // setQueue(autoPlay=false) 只换界面曲目，不会 open 新音源，state 里残留的还是
                // 上一首的 PLAYING。快速切歌时若只看 state，就会误判为「已在播目标歌」而跳过
                // 补发 play —— play() 正是检测 currentPlayingKey != 当前 key 并触发 playCurrent
                // 的唯一入口，少了它音频就永远停在旧歌（界面/歌词却是新歌）。
                val statePlaying = player.state.value == PlaybackState.PLAYING
                val audioOnTarget = player.isAudioOnCurrentTrack
                val nowLocalPlaying = statePlaying && audioOnTarget
                if (plan.desiredPlaying) {
                    // 重建队列时 setQueue 用的是 autoPlay=false，play 必须在这里补发。
                    // 规划器的 shouldIssuePlay 对「重建后恢复」只认 LINK_READY，首曲 play
                    // （cause=REQUEST_PLAY）会落到 false——于是队列装填完却没人开声，
                    // 正是「第一首不播放，按下一首才行」。房间要播就一定补发一次，
                    // 此时本地队列刚重建、状态不会是 PLAYING，不会与稳态重复下发。
                    val shouldPlay = plan.shouldIssuePlay || (reloadedPlaylist && nowLocalPlaying.not())
                    if (shouldPlay && (!reloadedPlaylist || nowLocalPlaying.not())) {
                        AppLog.info(
                            TAG,
                            "补发 play：statePlaying=$statePlaying audioOnTarget=$audioOnTarget" +
                                " targetKey=${targetSong.key} cause=$causeType",
                        )
                        player.play()
                        awaitPlayerPrepared(targetSong.key)
                    }
                } else {
                    val needPause = (plan.shouldIssuePause && nowLocalPlaying) ||
                        (reloadedPlaylist && plan.shouldForcePauseAfterRemoteLoad)
                    if (needPause) player.pause()
                    // 暂停态下不该带着软校正速率：Player.pause() 已会复位，这里再兜一次。
                    player.resetListenTogetherRate()
                }
            } finally {
                applyingRemote = false
                suppressLocalUntilMs = System.currentTimeMillis() + 400
                // 上报基线只在「控制者」身份下维护：听众根本不跑控制者上报路径，
                // 在这里给它铺基线只会和心跳/本地采样两套口径互相打架。
                //
                // 控制者这边，基线位置取**本地播放器实际位置**而不是房间推算位置：
                // publishLocalState 的 SEEK 判定是「本地采样 vs 基线 + 经过时间」，
                // 两端同源才不会因为服务端时钟推算与引擎输出之间的正常偏差被判成
                // 一次 SEEK —— 那会广播新的权威位置，把所有听众又拽一下。
                reported = if (isControllerRole(sessionRole)) {
                    buildReportFromRoom(
                        state = state,
                        positionMs = container.player.positionMs.value.coerceAtLeast(0L),
                        now = System.currentTimeMillis(),
                    )
                } else {
                    null
                }
            }
        }
    }

    /**
     * Android `ListenTogetherPlayerStateApplier.isTrackSwitchGracePeriodActive()`：
     * 切歌后 [TRACK_SWITCH_GRACE_PERIOD_MS] 内视为宽限期，passive 位置更新不纠正进度。
     */
    private fun isTrackSwitchGracePeriodActive(now: Long): Boolean {
        val switchedAt = lastTrackSwitchAtMs
        if (switchedAt <= 0L) return false
        return now - switchedAt < TRACK_SWITCH_GRACE_PERIOD_MS
    }

    /**
     * Android `ListenTogetherPlaybackPositionSync.isAwaitingStream()` 的桌面版：
     * 房间开启了直链共享、当前曲目有可用共享地址，但播放器实际打开的音源还不是它时，
     * 认为正在等共享流，planner 据此不发 play（避免先播本地音源再 reload 一次）。
     */
    private fun isAwaitingAuthoritativeStream(
        state: ListenTogetherRoomState,
        targetSong: Song,
    ): Boolean {
        if (isControllerRole(sessionRole)) return false
        if (state.settings.shareAudioLinks != true || state.roomStatus == "closed") return false
        if (container.player.currentSong.value?.key != targetSong.key) return false
        val urls = buildList {
            (state.track ?: state.queue.getOrNull(state.currentIndex))?.streamUrl?.let { add(it) }
            (state.track ?: state.queue.getOrNull(state.currentIndex))?.streamUrls?.forEach { add(it) }
        }.filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
        if (urls.isEmpty()) return false
        val currentUrl = container.player.currentAudioInput?.url
        return currentUrl == null || currentUrl !in urls
    }

    /**
     * 用权威房态构造上报基线：队列/播放/模式按 room 认为的来（避免把尚未同步的本地
     * 变化误判成已上报），位置由调用方给（通常是本地播放器的实际采样）。
     */
    private fun buildReportFromRoom(
        state: ListenTogetherRoomState,
        positionMs: Long,
        now: Long,
    ): LocalReport {
        val index = if (state.queue.isEmpty()) -1 else state.currentIndex.coerceIn(0, state.queue.lastIndex)
        val track = state.track ?: state.queue.getOrNull(index)
        return LocalReport(
            queueKeys = state.queue.map { it.stableKey },
            index = index,
            trackKey = track?.stableKey,
            playing = state.playback.state == "playing",
            repeatMode = state.playback.repeatMode ?: 0,
            shuffle = state.playback.shuffleEnabled == true,
            positionMs = positionMs.coerceAtLeast(0L),
            atMs = now,
        )
    }

    private suspend fun awaitPlayerPrepared(expectedKey: String) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val player = container.player
            if (player.currentSong.value?.key != expectedKey) return
            when (player.state.value) {
                PlaybackState.PLAYING, PlaybackState.PAUSED -> return
                PlaybackState.ERROR -> return
                else -> delay(80)
            }
        }
    }

    private fun clampPosition(positionMs: Long, durationMs: Long): Long {
        val value = positionMs.coerceAtLeast(0L)
        return if (durationMs > 0) value.coerceAtMost(durationMs) else value
    }

    /**
     * 听众侧硬定位：把落点往前顶一点，抵消「重建流水线期间位置冻结」造成的二次滞后。
     *
     * 为什么需要它：桌面引擎的 seek 是重建整条 ffmpeg 流水线（`FfmpegAudioEngine.seekTo`
     * → `startPipeline`），而播放器的位置在流水线出新音频之前不会前进（实测本机缓存命中时
     * 这段是 100~200ms，远端直链重新建连时更长）。房间不会等我们，于是「seek 到 T」
     * 实际出声时已经落后这么久 —— 用户感知到的正是「每次同步都要卡一下、然后还是慢一点」。
     *
     * @param leadMs 落点前瞻；只有「房间还在前进」时才该给，暂停时传 0。
     */
    private fun seekListenerTo(targetPositionMs: Long, durationMs: Long, leadMs: Long) {
        val target = clampPosition(targetPositionMs + leadMs.coerceAtLeast(0L), durationMs)
        AppLog.info(TAG, "HARD seek 执行：目标=$targetPositionMs 前瞻=$leadMs 落点=$target duration=$durationMs（重建期间位置冻结，房间继续前进）")
        val sample = SeekSample(atMs = System.currentTimeMillis(), targetMs = target)
        pendingSeekSample = sample
        // 重建期间暂停一切校正：自己的落点前瞻已经把「预计重建耗时」算进去了，
        // 只有等它真正出声再判断，否则冻结的 local 会被反复当成大漂移。
        pauseDriftCorrectionForRebuild(sample.atMs)
        container.player.seekTo(target)
        // 采样放到独立协程：重建期间不能让主循环停在原地等，否则同步判定会被拖慢
        scope.launch { sampleSeekRebuildLatency(sample) }
    }

    /**
     * 估计一次 seek 重建要多久才出声。
     *
     * 没有实测数据时用 [SEEK_LEAD_DEFAULT_MS]；有上一轮的样本就用它（限幅），
     * 这样在慢机器/远端直链上自动多顶一点，本机缓存命中时又不会顶过头。
     */
    private fun seekLeadEstimateMs(): Long {
        val observed = observedSeekRebuildMs ?: return SEEK_LEAD_DEFAULT_MS
        return observed.coerceIn(SEEK_LEAD_MIN_MS, SEEK_LEAD_MAX_MS)
    }

    /**
     * 重建开始：先复位软同步临时速率（避免带着旧速率重建），并把漂移校正暂停到
     * 「重建预计耗时 + 一个软同步下发间隔」之后。重建真正出声由采样协程提前解除暂停。
     *
     * 暂停期间 local 是冻结的假值，据此继续下发速率/硬 seek 只会一遍遍重建，
     * 永远出不来声 —— 这正是慢网络下「越同步越回退」的循环。
     */
    private fun pauseDriftCorrectionForRebuild(atMs: Long) {
        resetSoftSyncRate()
        val budget = (observedSeekRebuildMs ?: SEEK_LEAD_DEFAULT_MS)
            .coerceIn(SEEK_LEAD_MIN_MS, SEEK_LEAD_MAX_MS)
        rebuildPausedUntilMs = maxOf(rebuildPausedUntilMs, atMs + budget + SOFT_SYNC_RATE_MIN_INTERVAL_MS)
        rebuildStabilizeUntilMs = 0L
    }

    /** 本轮是否允许做漂移校正（软调速 / 硬 seek）。 */
    private fun canCorrectDriftNow(): Boolean {
        if (pendingSeekSample != null) return false
        if (System.currentTimeMillis() < rebuildPausedUntilMs) return false
        if (System.currentTimeMillis() < rebuildStabilizeUntilMs) return false
        return true
    }

    /**
     * 重建真正出声后重置软同步判定基线：解除阻塞式暂停，清掉收敛计数并把节流时钟
     * 推到出声这一刻。下一轮 tick 会用当前 local 与权威基线重新算一次干净的漂移，
     * 而不是拿重建期间冻结期攒下的旧 drift 继续追。
     *
     * 这里刻意**不调用** [resetSoftSyncRate]：速率在重建开始时已复位，现在再改一次
     * 又要重建流水线，等于把刚出声的播放拆掉。
     */
    private fun finishRebuildBaselineReset(atMs: Long) {
        rebuildPausedUntilMs = 0L
        softSyncConvergedTicks = 0
        lastSoftSyncRateAtMs = atMs
        lastSoftSyncEvalAtMs = 0L
    }

    /**
     * 采样 seek 后「播放器位置重新开始前进」花了多久。
     *
     * 判据是位置越过 seek 落点：引擎重建流水线期间帧计数是 0（位置冻结在 offsetMs），
     * 只有新进程真的开始吐音频，位置才会涨过落点。只观察不改行为，
     * 拿不到（一直在暂停、或流水线卡住）就保持上一次的估计。
     */
    private suspend fun sampleSeekRebuildLatency(sample: SeekSample) {
        try {
            val deadline = sample.atMs + SEEK_LEAD_SAMPLE_TIMEOUT_MS
            val player = container.player
            while (System.currentTimeMillis() < deadline) {
                delay(60)
                if (player.state.value != PlaybackState.PLAYING) continue
                if (player.positionMs.value > sample.targetMs) {
                    val now = System.currentTimeMillis()
                    val took = (now - sample.atMs).coerceAtLeast(0L)
                    // 超过上限的样本说明期间被暂停/切歌污染，不当作重建耗时采用。
                    if (took <= SEEK_LEAD_SAMPLE_VALID_MS) observedSeekRebuildMs = took
                    AppLog.debug(TAG, "seek 后重新出声耗时：${took}ms（下次落点前瞻 ${seekLeadEstimateMs()}ms）")
                    rebuildNoProgressTicks = 0
                    rebuildStabilizeUntilMs = 0L
                    finishRebuildBaselineReset(now)
                    return
                }
            }
            // 超时没越过落点：连续几次说明慢网络上重建反复被打断，进入稳定期让流水线安静出声。
            rebuildNoProgressTicks++
            AppLog.debug(TAG, "seek 后 ${SEEK_LEAD_SAMPLE_TIMEOUT_MS}ms 内未越过落点（连续 $rebuildNoProgressTicks 次），本轮不采信重建耗时")
            if (rebuildNoProgressTicks >= REBUILD_RECOVERY_TICKS) {
                rebuildStabilizeUntilMs = System.currentTimeMillis() + REBUILD_STABILIZE_MS
                rebuildPausedUntilMs = 0L
                rebuildNoProgressTicks = 0
                AppLog.info(TAG, "连续重建未出声，进入 ${REBUILD_STABILIZE_MS}ms 稳定期：期间暂停漂移校正")
            }
        } finally {
            if (pendingSeekSample === sample) pendingSeekSample = null
        }
    }

    /**
     * 用权威房态里的播放基线推算「本机此刻对应的房间进度」。
     *
     * 为什么不直接采信消息里附带的 `expectedPositionMs`：那是服务端**发出消息那一刻**算的值，
     * 消息还要再飞一个单程延迟才到本机。把它当成「本机现在的进度」就等于每次同步都主动
     * 落后一个单程延迟，这是「同步完又差一大截」的直接来源之一。改用
     * `basePositionMs + (服务端现在 - baseTimestampMs) * rate` 重新投影，配合已经做过
     * RTT/2 补偿的时钟偏移，得到的才是真正的此刻。
     *
     * @param fallbackMs 服务端没给出可用的基线时间戳（老版本）时退回使用的进度。
     * @param fallbackAnchorMs 该兜底进度对应的本机时刻；用来把它往前推到 now。
     */
    private fun expectedRoomPositionAt(
        state: ListenTogetherRoomState,
        now: Long,
        fallbackMs: Long? = null,
        fallbackAnchorMs: Long = 0L,
    ): Long {
        val playback = state.playback
        if (playback.state != "playing") return playback.basePositionMs.coerceAtLeast(0L)
        val duration = (state.track ?: state.queue.getOrNull(state.currentIndex))?.durationMs ?: 0L
        val rate = playback.playbackRate.takeIf { it > 0.0 } ?: 1.0
        val projected = if (playback.baseTimestampMs <= 0L) {
            // 基线时间戳非法时不能拿它做减法（会把进度推到天文数字）：
            // 退化成「上一条已知进度 + 本地经过时间」，与修复前的行为一致。
            val base = fallbackMs ?: return playback.basePositionMs.coerceAtLeast(0L)
            val elapsed = if (fallbackAnchorMs > 0L) (now - fallbackAnchorMs).coerceAtLeast(0L) else 0L
            base + (elapsed * rate).toLong()
        } else {
            val serverNow = now + serverClockOffsetMs
            playback.basePositionMs + ((serverNow - playback.baseTimestampMs) * rate).toLong()
        }
        return if (playback.repeatMode == 1 && duration > 0) {
            projected.coerceAtLeast(0L) % duration
        } else {
            projected.coerceAtLeast(0L)
        }
    }

    private fun responseExpectedPosition(state: ListenTogetherRoomState, fallbackMs: Long? = null): Long =
        expectedRoomPositionAt(state, System.currentTimeMillis(), fallbackMs)

    /** tick 里取「房间此刻应该在哪」：始终按播放基线重新投影，避免用陈旧快照推算。 */
    private fun computeExpectedPosition(now: Long): Long {
        val state = synchronized(lock) { room } ?: return 0L
        return expectedRoomPositionAt(
            state = state,
            now = now,
            fallbackMs = expectedPositionMs,
            fallbackAnchorMs = expectedAtLocalMs,
        )
    }

    // ---------------------------------------------------------------- 本地播放 → 控制事件

    private data class LocalReport(
        val queueKeys: List<String>,
        val index: Int,
        val trackKey: String?,
        val playing: Boolean,
        val repeatMode: Int,
        val shuffle: Boolean,
        val positionMs: Long,
        val atMs: Long,
    )

    private fun buildLocalReport(now: Long): LocalReport? {
        val player = container.player
        val pair = shareableQueueSnapshot() ?: return null
        val (tracks, index) = pair
        val track = tracks.getOrNull(index) ?: return null
        return LocalReport(
            queueKeys = tracks.map { it.stableKey },
            index = index,
            trackKey = track.stableKey,
            playing = player.state.value == PlaybackState.PLAYING,
            repeatMode = player.repeatMode.value.toListenTogetherRepeatMode(),
            shuffle = player.shuffle.value,
            positionMs = player.positionMs.value.coerceAtLeast(0L),
            atMs = now,
        )
    }

    /** 返回可分享的队列快照与当前歌曲在其中的下标；当前歌曲不可分享时返回 null。 */
    private fun shareableQueueSnapshot(): Pair<List<ListenTogetherTrack>, Int>? {
        val player = container.player
        val currentSong = player.currentSong.value ?: return null
        val currentTrack = currentSong.toListenTogetherTrackOrNull() ?: return null
        val currentIndex = player.currentIndex.value
        val entries = player.queue.value.mapIndexedNotNull { index, song ->
            song.toListenTogetherTrackOrNull()?.let { index to it }
        }
        if (entries.isEmpty()) return null
        val mappedIndex = entries.indexOfFirst { (originalIndex, track) ->
            originalIndex == currentIndex || track.stableKey == currentTrack.stableKey
        }.coerceAtLeast(0)
        return entries.map { it.second } to mappedIndex
    }

    private fun boundQueueAround(
        tracks: List<ListenTogetherTrack>,
        index: Int,
    ): Pair<List<ListenTogetherTrack>, Int> {
        if (tracks.size <= MAX_QUEUE_SIZE) return tracks to index
        val half = MAX_QUEUE_SIZE / 2
        val start = (index - half).coerceIn(0, (tracks.size - MAX_QUEUE_SIZE).coerceAtLeast(0))
        val window = tracks.subList(start, (start + MAX_QUEUE_SIZE).coerceAtMost(tracks.size)).toList()
        return window to (index - start).coerceIn(0, window.lastIndex)
    }

    private suspend fun publishLocalState(now: Long) {
        // 只有控制者维护上报基线；听众侧调用直接返回，避免两条状态口径互相污染
        if (!isController()) return
        if (applyingRemote || now < suppressLocalUntilMs) return
        val player = container.player
        val state = player.state.value
        if (state == PlaybackState.PREPARING || state == PlaybackState.IDLE || state == PlaybackState.ERROR) return
        val pair = shareableQueueSnapshot()
        if (pair == null) {
            reported = null
            if (!unsupportedLocalSongNotified) {
                unsupportedLocalSongNotified = true
                synchronized(lock) { notice = "当前播放的是本地/不可分享歌曲，播放不会同步到房间" }
                notifyState()
            }
            return
        }
        unsupportedLocalSongNotified = false
        val (allTracks, index) = pair
        val (tracks, boundedIndex) = boundQueueAround(allTracks, index)
        val track = tracks.getOrNull(boundedIndex) ?: return
        val playing = state == PlaybackState.PLAYING
        val position = player.positionMs.value.coerceAtLeast(0L)
        val repeat = player.repeatMode.value.toListenTogetherRepeatMode()
        val shuffle = player.shuffle.value
        val current = LocalReport(
            queueKeys = tracks.map { it.stableKey },
            index = boundedIndex,
            trackKey = track.stableKey,
            playing = playing,
            repeatMode = repeat,
            shuffle = shuffle,
            positionMs = position,
            atMs = now,
        )
        val previous = reported
        reported = current
        if (previous == null || previous.trackKey == null) {
            sendFullSnapshot(tracks, boundedIndex, track, position, playing, repeat, shuffle)
            return
        }
        val trackChanged = previous.trackKey != track.stableKey
        val queueChanged = previous.queueKeys != current.queueKeys || previous.index != boundedIndex
        val playbackStateChanged = previous.playing != playing
        val modeChanged = previous.repeatMode != repeat || previous.shuffle != shuffle
        val expectedPosition = if (previous.playing) {
            previous.positionMs + (now - previous.atMs)
        } else {
            previous.positionMs
        }
        val seek = !trackChanged && !queueChanged && !playbackStateChanged &&
            if (previous.playing) {
                abs(position - expectedPosition) > LOCAL_SEEK_TOLERANCE_PLAYING_MS
            } else {
                abs(position - previous.positionMs) > LOCAL_SEEK_TOLERANCE_PAUSED_MS
            }

        if (trackChanged) {
            sendControlEvent(
                buildQueueControlEvent(
                    type = "SET_TRACK",
                    tracks = tracks,
                    index = boundedIndex,
                    track = track,
                    positionMs = position,
                    playing = playing,
                    repeatMode = repeat,
                    shuffle = shuffle,
                    requestTrackStableKey = track.stableKey,
                ),
                awaitResult = false,
            )
            return
        }
        if (queueChanged) {
            sendQueueEvent(
                tracks = tracks,
                index = boundedIndex,
                track = track,
                positionMs = position,
                playing = playing,
                repeatMode = repeat,
                shuffle = shuffle,
            )
        }
        if (playbackStateChanged) {
            sendControlEvent(
                ListenTogetherControlEvent(
                    type = if (playing) "PLAY" else "PAUSE",
                    positionMs = position,
                    currentIndex = boundedIndex,
                    track = track,
                    shouldPlay = playing,
                    state = if (playing) "playing" else "paused",
                    repeatMode = repeat,
                    shuffleEnabled = shuffle,
                ),
                awaitResult = false,
            )
        }
        if (modeChanged) {
            sendControlEvent(
                ListenTogetherControlEvent(
                    type = "PLAYBACK_MODE",
                    positionMs = position,
                    currentIndex = boundedIndex,
                    track = track,
                    repeatMode = repeat,
                    shuffleEnabled = shuffle,
                    state = if (playing) "playing" else "paused",
                ),
                awaitResult = false,
            )
        }
        if (seek) {
            sendControlEvent(
                ListenTogetherControlEvent(
                    type = "SEEK",
                    positionMs = position,
                    currentIndex = boundedIndex,
                    track = track,
                    state = if (playing) "playing" else "paused",
                    shouldPlay = playing,
                ),
                awaitResult = false,
            )
        }
    }

    /** 控制者队列变化上报：优先增量，必要时回退整队列快照。 */
    private suspend fun sendQueueEvent(
        tracks: List<ListenTogetherTrack>,
        index: Int,
        track: ListenTogetherTrack,
        positionMs: Long,
        playing: Boolean,
        repeatMode: Int,
        shuffle: Boolean,
    ) {
        AppLog.debug(TAG, "sendQueueEvent：size=${tracks.size} index=$index pos=$positionMs playing=$playing repeat=$repeatMode shuffle=$shuffle track=${track.stableKey.take(40)}")
        sendControlEvent(
            buildQueueControlEvent(
                type = "SET_QUEUE",
                tracks = tracks,
                index = index,
                track = track,
                positionMs = positionMs,
                playing = playing,
                repeatMode = repeatMode,
                shuffle = shuffle,
            ),
            awaitResult = false,
        )
    }

    /**
     * 构造队列控制事件：按 [buildListenTogetherQueueEventPayload] 的语义在
     * 「整快照」与「增量 mutation」之间二选一，并把 `baseRoomVersion` 锚定到本地房态版本。
     */
    private fun buildQueueControlEvent(
        type: String,
        tracks: List<ListenTogetherTrack>,
        index: Int,
        track: ListenTogetherTrack?,
        positionMs: Long,
        playing: Boolean,
        repeatMode: Int,
        shuffle: Boolean,
        requestTrackStableKey: String? = null,
    ): ListenTogetherControlEvent {
        val room = synchronized(lock) { room }
        val payload = buildListenTogetherQueueEventPayload(
            room = room,
            queue = tracks,
            currentIndex = index,
            alwaysIncludeSnapshot = true,
        )
        return ListenTogetherControlEvent(
            type = type,
            positionMs = positionMs,
            currentIndex = index,
            track = track,
            queue = payload.queue,
            queueMutation = payload.mutation,
            shouldPlay = playing,
            state = if (playing) "playing" else "paused",
            repeatMode = repeatMode,
            shuffleEnabled = shuffle,
            requestTrackStableKey = requestTrackStableKey,
        )
    }

    /** 首次同步/基线缺失时的整队列上报（同样走 sendQueueEvent，按需带增量）。 */
    private suspend fun sendFullSnapshot(
        tracks: List<ListenTogetherTrack>,
        index: Int,
        track: ListenTogetherTrack,
        positionMs: Long,
        playing: Boolean,
        repeatMode: Int,
        shuffle: Boolean,
    ) {
        sendQueueEvent(
            tracks = tracks,
            index = index,
            track = track,
            positionMs = positionMs,
            playing = playing,
            repeatMode = repeatMode,
            shuffle = shuffle,
        )
    }

    private suspend fun pushLocalSnapshotToRoom() {
        val pair = shareableQueueSnapshot() ?: return
        val (tracks, index) = boundQueueAround(pair.first, pair.second)
        val player = container.player
        val position = player.positionMs.value.coerceAtLeast(0L)
        val playing = player.state.value == PlaybackState.PLAYING
        sendQueueEvent(
            tracks = tracks,
            index = index,
            track = tracks.getOrNull(index) ?: return,
            positionMs = position,
            playing = playing,
            repeatMode = player.repeatMode.value.toListenTogetherRepeatMode(),
            shuffle = player.shuffle.value,
        )
        reported = buildLocalReport(System.currentTimeMillis())
    }

    /**
     * 控制者心跳：只保活，不参与状态比对。
     *
     * 位置取本地播放器实际采样 —— 与控制者上报基线（[reported] 里的本地采样）同源。
     * 如果这里上报推算位置、而基线用另一套口径，服务端按心跳重算出的权威位置就会和
     * 本地播放器有稳定偏差，听众一收到心跳广播就被拽一下进度。
     */
    private suspend fun maybeSendHeartbeat(now: Long) {
        // 心跳位置必须与控制者的上报基线同源，所以心跳也只由控制者发
        if (!isController()) return
        if (applyingRemote || now < suppressLocalUntilMs) return
        val pair = shareableQueueSnapshot() ?: return
        val (tracks, index) = pair
        val track = tracks.getOrNull(index) ?: return
        val player = container.player
        val playing = player.state.value == PlaybackState.PLAYING
        val interval = if (playing) HEARTBEAT_PLAYING_MS else HEARTBEAT_PAUSED_MS
        if (now - lastOutboundAtMs < interval) return
        sendControlEvent(
            ListenTogetherControlEvent(
                type = "HEARTBEAT",
                positionMs = player.positionMs.value.coerceAtLeast(0L),
                currentIndex = index,
                track = track,
                state = if (playing) "playing" else "paused",
                shouldPlay = playing,
                repeatMode = player.repeatMode.value.toListenTogetherRepeatMode(),
                shuffleEnabled = player.shuffle.value,
            ),
            awaitResult = false,
        )
    }

    private suspend fun maybePing(now: Long) {
        if (!wsOpen) return
        if (now - lastPingAtMs < PING_INTERVAL_MS) return
        lastPingAtMs = now
        val client = synchronized(lock) { wsClient } ?: return
        // 记下发送时刻：pong 里服务端会把 t 原样回显，有了它才能算出真实往返时间，
        // 进而把「服务端时间戳 - 本机时间」里差的那个单程延迟补回来。
        val sentAtElapsedMs = clockSync.notePingSent()
        val payload = if (clockSyncPingSupported == false) {
            // 服务端不认 np_ping 时退回旧版心跳：它不回显 t，只能勉强当往返样本用
            """{"type":"ping"}"""
        } else {
            """{"type":"np_ping","t":$sentAtElapsedMs}"""
        }
        withContext(Dispatchers.IO) { runCatching { client.send(payload) } }
    }

    private suspend fun maybeRefreshState(now: Long) {
        val connected = synchronized(lock) { connection == "connected" }
        if (!connected || room == null) return
        if (now - lastInboundAtMs < STATE_REFRESH_SILENCE_MS) return
        if (now - lastStateRefreshAtMs < STATE_REFRESH_SILENCE_MS) return
        val (base, roomId, currentToken) = synchronized(lock) {
            Triple(sessionBaseUrl, room?.roomId, token)
        }
        if (base == null || roomId == null || currentToken == null) return
        lastStateRefreshAtMs = now
        // HTTP 请求/响应同样能提供一个往返样本（服务端在 `serverNowMs` 里回了自己的时间），
        // 静默期用它把偏移再校准一次，比等下一轮 ping 更快收敛。
        val sentAtElapsedMs = System.nanoTime() / 1_000_000L
        val sentAtWallMs = System.currentTimeMillis()
        val result = withContext(Dispatchers.IO) { http.getState(base, roomId, currentToken) }
        val response = result.value
        if (result.error != null || response == null || !response.ok) {
            AppLog.warn(TAG, "静默兜底取房态失败：roomId=$roomId error=${result.error ?: response?.error ?: "未知"} 距上次收消息 ${now - lastInboundAtMs}ms")
            return
        }
        AppLog.debug(TAG, "静默兜底取房态成功：roomId=$roomId version=${response.state?.version} 距上次收消息 ${now - lastInboundAtMs}ms")
        clockSync.onRoundTrip(
            serverNowMs = response.serverNowMs,
            sentAtWallMs = sentAtWallMs,
            sentAtElapsedMs = sentAtElapsedMs,
            reason = "http_state",
        )
        val state = response.state ?: return
        applyAuthoritativeState(
            state = state,
            expectedPosition = responseExpectedPosition(state, fallbackMs = response.expectedPositionMs),
            causedBy = null,
            applyToPlayer = !isControllerRole(sessionRole),
        )
    }

    /**
     * 听众侧周期复核：与 Android `ListenTogetherSoftSyncRateRecheckOwner` 对齐。
     *
     * 结构性差异（切歌/播放态不一致）直接走 [applyRoomStateToPlayer]；播放中同一首歌的
     * 进度偏差则组装同一套 planner context，由 plan 的 `signedDriftMs` 单一驱动软/硬校正。
     * 这样「apply」与「tick」两个入口共用同一个决策点，不会再出现两处各判一次、互相打架。
     */
    private suspend fun correctListenerPlayback(now: Long) {
        val state = synchronized(lock) { room } ?: return
        if (state.roomStatus == "closed") return
        if (isControllerRole(sessionRole)) return
        if (now < suppressLocalUntilMs || applyingRemote) return
        val player = container.player
        if (player.state.value == PlaybackState.ERROR) return
        val track = state.track ?: state.queue.getOrNull(state.currentIndex) ?: return
        val song = track.toSongOrNull() ?: return
        val target = computeExpectedPosition(now)
        val sameTrack = player.currentSong.value?.key == song.key
        val roomPlaying = state.playback.state == "playing"
        val localPlaying = player.state.value == PlaybackState.PLAYING
        // 结构性差异（切歌 / 播放暂停不一致）必须尽快到位，不受软校正节流限制。
        // 这一判定必须放在「漂移校正暂停」之前：pending=true 期间被拦的只能是位置校正，
        // play/pause/切歌是结构变化，若也被一起拦掉，首曲 play 请求就会因为之前一次 seek
        // 的重建一直没出声（pending 一直为真）而永远下发不出去——正是「第一首不播，
        // 按下一首（切歌清掉 pending）才行」的现场。
        val structuralChange = !sameTrack || (roomPlaying != localPlaying)
        if (structuralChange) {
            AppLog.info(TAG, "听众看门狗发现结构性差异：sameTrack=$sameTrack roomPlaying=$roomPlaying localPlaying=$localPlaying（触发 applyRoomState）")
            // 播放态不一致时先给上一次 apply（可能刚重建了流水线）时间出声。
            // 这里和 HARD seek 校正共用 lastListenerApplyAtMs 这个节流点：否则每 tick 都
            // 重进 apply + HARD seek，而 HARD seek 的重建又把 positionMs 卡回 0，形成死循环。
            if (now - lastListenerApplyAtMs < LISTENER_APPLY_MIN_INTERVAL_MS) return
            lastListenerApplyAtMs = now
            applyRoomStateToPlayer(state, target, causeType = "WATCHDOG")
            return
        }
        // 到这里是同一首歌 + 播放态一致，只剩位置漂移校正；重建期间 local 是冻结的假值，
        // 据此继续下发速率/硬 seek 只会一遍遍重建，永远出不来声，整段跳过。
        if (!canCorrectDriftNow()) {
            AppLog.debug(TAG, "漂移校正暂停：流水线重建中/稳定期，pending=${pendingSeekSample != null}")
            return
        }
        if (now - lastSoftSyncEvalAtMs < SOFT_SYNC_RECHECK_INTERVAL_MS) return
        lastSoftSyncEvalAtMs = now

        // 同一首歌 + 播放态一致：组装 planner context。tick 的 cause 是 WATCHDOG（passive），
        // 于是归零保护与切歌宽限期在这里同样生效，心跳/看门狗带来的陈旧 expectedPosition
        // 不会被当成一次真实的大偏差硬 seek。
        val graceActive = isTrackSwitchGracePeriodActive(now)
        val ignoreZero = shouldIgnoreUnexpectedZeroPositionRollback(
            causeType = "WATCHDOG",
            desiredPlaying = roomPlaying,
            expectedPositionMs = target,
            localPositionMs = player.positionMs.value.coerceAtLeast(0L),
            playbackContextChanged = false,
            targetIndexChanged = false,
            zeroPositionRollbackGuardMs = ZERO_POSITION_ROLLBACK_GUARD_MS,
        )
        val syncContext = ListenTogetherPlayerSyncContext(
            playbackContextChanged = false,
            targetIndexChanged = false,
            desiredPlaying = roomPlaying,
            localPlaying = localPlaying,
            localPlaybackAlreadyStarting = player.state.value == PlaybackState.PREPARING,
            awaitingAuthoritativeStream = isAwaitingAuthoritativeStream(state, song),
            expectedPositionMs = target,
            localPositionMs = player.positionMs.value.coerceAtLeast(0L),
            ignoreUnexpectedZeroPositionRollback = ignoreZero,
            trackSwitchGracePeriodActive = graceActive,
            causeType = "WATCHDOG",
            trackSwitchForceSyncMs = TRACK_SWITCH_FORCE_SYNC_MS,
            heartbeatDriftForceSyncMs = HEARTBEAT_HARD_SYNC_DRIFT_MS,
            playingDriftForceSyncMs = HARD_SYNC_DRIFT_MS,
            pausedDriftForceSyncMs = PAUSED_HARD_SYNC_DRIFT_MS,
        )
        val plan = resolveListenTogetherPlayerSyncPlan(syncContext)
        logSyncPlanDecision("correctListenerPlayback", syncContext, plan)
        if (ignoreZero) {
            AppLog.info(TAG, "听众看门狗归零保护命中：expected=$target local=${player.positionMs.value} 本轮不纠正")
        }
        if (graceActive) {
            AppLog.debug(TAG, "听众看门狗切歌宽限期生效：距上次切歌 ${now - lastTrackSwitchAtMs}ms")
        }
        reconcileListenerDrift(player, plan, now)
    }

    /**
     * 播放中漂移校正：完全由 [ListenTogetherPlayerSyncPlan] 驱动。
     *
     * 与 Android `ListenTogetherPlayerStateApplier.applyPlayingSyncPlan()` +
     * `ListenTogetherSoftSyncRateRecheckOwner` 对齐：
     *  - plan.shouldSeek（signedDrift 超过场景硬阈值）→ 复位速率并硬 seek；
     *  - 否则偏差在软区间 → 用 0.95~1.05 的临时速率追（带桌面特有的迟滞，避免流水线反复重建）；
     *  - 偏差 < 软阈值 → 连续两次复核都收敛才恢复 1.0x。
     *
     * 引擎不支持变速（JavaSound 回退）时软校正没有意义，退化为硬 seek。
     */
    private fun reconcileListenerDrift(
        player: PlayerManager,
        plan: ListenTogetherPlayerSyncPlan,
        now: Long,
    ) {
        if (!plan.desiredPlaying) {
            // 房间已暂停：复位临时倍率，位置由 applyRoomStateToPlayer 的 plan 负责。
            player.resetListenTogetherRate()
            return
        }
        if (plan.shouldSeek) {
            // 上一次重建（硬 seek 或软调速）还没出声时位置不可信，先别再排一次（会卡顿）。
            if (canCorrectDriftNow() && now - lastListenerApplyAtMs >= LISTENER_APPLY_MIN_INTERVAL_MS) {
                lastListenerApplyAtMs = now
                resetSoftSyncRate()
                val duration = player.durationMs.value.takeIf { it > 0 } ?: 0L
                seekListenerTo(plan.effectiveExpectedPositionMs, duration, leadMs = seekLeadEstimateMs())
            } else {
                AppLog.debug(TAG, "硬 seek 被抑制：drift=${plan.driftMs} correcting=${
                    canCorrectDriftNow()
                } pending=${pendingSeekSample != null} sinceLastApply=${now - lastListenerApplyAtMs}ms")
            }
            return
        }
        val driftMs = plan.driftMs
        if (driftMs < SOFT_SYNC_CONVERGED_DRIFT_MS) {
            // 收敛：连续两次确认已回到容差内才把用户倍速还回去，避免边界反复切速率。
            if (player.listenTogetherSyncRate == 1f) {
                softSyncConvergedTicks = 0
            } else if (++softSyncConvergedTicks >= SOFT_SYNC_CONVERGED_TICKS) {
                softSyncConvergedTicks = 0
                lastSoftSyncRateAtMs = now
                AppLog.info(TAG, "软同步收敛：drift=$driftMs signedDrift=${plan.signedDriftMs} 恢复用户倍速（原临时速率 ${player.listenTogetherSyncRate}）")
                // 收敛后立即热更新引擎速率。Sonic 下 setSpeed 不重建流水线，与 Android 的
                // resetListenTogetherSyncPlaybackRate 对齐；不能只写参数等重建（deferred），
                // 否则 Sonic 仍按临时倍速跑，进度会再次跑过头并被硬 seek 回拉。
                player.finishSoftSyncDeferred()
            }
            return
        }
        softSyncConvergedTicks = 0
        // 引擎不能变速时软校正无效，按硬阈值直接定位。
        if (!player.supportsSpeedChange) {
            AppLog.debug(TAG, "引擎不支持变速，软校正退化为硬 seek：drift=$driftMs signedDrift=${plan.signedDriftMs}")
            if (canCorrectDriftNow() && now - lastListenerApplyAtMs >= LISTENER_APPLY_MIN_INTERVAL_MS) {
                lastListenerApplyAtMs = now
                resetSoftSyncRate()
                seekListenerTo(
                    plan.effectiveExpectedPositionMs,
                    player.durationMs.value.takeIf { it > 0 } ?: 0L,
                    leadMs = seekLeadEstimateMs(),
                )
            }
            return
        }
        // 带迟滞：追进度途中只升不降。引擎每改一次速率就要重建一次 ffmpeg 流水线，
        // 随偏差在 1.03/1.05/0.97 之间来回换档会让「软同步」比硬 seek 还卡。
        // resolveSoftSyncRateWithHysteresis 返回 null 表示偏差落在软区间下沿（250~600ms），
        // 此时保持当前档位：折算成 1.0 会为一次无意义的复位再重建流水线。
        val rate = ListenTogetherSyncPolicy.resolveSoftSyncRateWithHysteresis(
            currentRate = player.listenTogetherSyncRate,
            driftMs = plan.signedDriftMs,
            softSyncMinDriftMs = SOFT_SYNC_MIN_DRIFT_MS,
            softSyncFastDriftMs = SOFT_SYNC_FAST_DRIFT_MS,
            hardSyncDriftMs = HARD_SYNC_DRIFT_MS,
        ) ?: player.listenTogetherSyncRate
        if (
            ListenTogetherSyncPolicy.shouldRetuneRate(
                currentRate = player.listenTogetherSyncRate,
                nextRate = rate,
                sinceLastAdjustMs = now - lastSoftSyncRateAtMs,
                minIntervalMs = SOFT_SYNC_RATE_MIN_INTERVAL_MS,
                epsilon = SOFT_SYNC_RATE_EPSILON,
            )
        ) {
            val sinceLast = now - lastSoftSyncRateAtMs
            lastSoftSyncRateAtMs = now
            AppLog.info(TAG, "软同步下发速率：$rate（原 ${player.listenTogetherSyncRate}）drift=$driftMs signedDrift=${plan.signedDriftMs} 距上次调整 ${sinceLast}ms")
            player.applyListenTogetherRate(rate)
            // 改速率同样会重建流水线、冻结位置。给一段静默期让流水线出声，
            // 期间 canCorrectDriftNow() 一律拦掉校正，避免「重建 2s → drift 又涨 → 再调速」的循环。
            // 注意 pauseDriftCorrectionForRebuild 会复位速率，所以这里只推进暂停截止时间，不复位。
            val budget = (observedSeekRebuildMs ?: SEEK_LEAD_DEFAULT_MS)
                .coerceIn(SEEK_LEAD_MIN_MS, SEEK_LEAD_MAX_MS)
            rebuildPausedUntilMs = maxOf(rebuildPausedUntilMs, now + budget + SOFT_SYNC_RATE_MIN_INTERVAL_MS)
        }
    }

    // ---------------------------------------------------------------- 控制事件发送

    private data class ControlOutcome(
        val ok: Boolean,
        val error: String?,
        val applied: ListenTogetherAppliedEvent?,
    )

    private fun enrichControlEvent(event: ListenTogetherControlEvent): ListenTogetherControlEvent {
        val player = container.player
        val pair = shareableQueueSnapshot()
        val index = pair?.second
        val track = pair?.first?.getOrNull(index ?: -1)
        return when (event.type) {
            "PLAY", "PAUSE", "SEEK", "HEARTBEAT" -> event.copy(
                positionMs = event.positionMs ?: player.positionMs.value.coerceAtLeast(0L),
                currentIndex = event.currentIndex ?: index,
                track = event.track ?: track,
                state = event.state ?: when (event.type) {
                    "PLAY" -> "playing"
                    "PAUSE" -> "paused"
                    else -> player.state.value.toListenTogetherPlaybackStateName()
                },
                shouldPlay = event.shouldPlay ?: (event.type == "PLAY" ||
                    (event.type == "HEARTBEAT" && player.state.value == PlaybackState.PLAYING)),
                repeatMode = event.repeatMode ?: player.repeatMode.value.toListenTogetherRepeatMode(),
                shuffleEnabled = event.shuffleEnabled ?: player.shuffle.value,
                requestTrackStableKey = event.requestTrackStableKey ?: track?.stableKey,
            )

            "PLAYBACK_MODE" -> event.copy(
                positionMs = event.positionMs ?: player.positionMs.value.coerceAtLeast(0L),
                currentIndex = event.currentIndex ?: index,
                track = event.track ?: track,
                repeatMode = event.repeatMode ?: player.repeatMode.value.toListenTogetherRepeatMode(),
                shuffleEnabled = event.shuffleEnabled ?: player.shuffle.value,
            )

            "SET_TRACK", "SET_QUEUE" -> {
                val tracks = event.queue ?: pair?.first.orEmpty()
                val targetIndex = event.currentIndex ?: index ?: 0
                event.copy(
                    queue = tracks,
                    currentIndex = targetIndex,
                    track = event.track ?: tracks.getOrNull(targetIndex),
                    positionMs = event.positionMs ?: player.positionMs.value.coerceAtLeast(0L),
                    shouldPlay = event.shouldPlay ?: (player.state.value == PlaybackState.PLAYING),
                    state = event.state ?: player.state.value.toListenTogetherPlaybackStateName(),
                    repeatMode = event.repeatMode ?: player.repeatMode.value.toListenTogetherRepeatMode(),
                    shuffleEnabled = event.shuffleEnabled ?: player.shuffle.value,
                )
            }

            else -> event
        }
    }

    private suspend fun sendControlEvent(
        event: ListenTogetherControlEvent,
        awaitResult: Boolean,
    ): ControlOutcome? {
        val sendStarted = System.currentTimeMillis()
        val (base, roomId, currentToken) = synchronized(lock) {
            Triple(sessionBaseUrl, room?.roomId, token)
        }
        if (base == null || roomId == null) {
            AppLog.warn(TAG, "sendControlEvent 丢弃：不在房间中 ${controlEventLog(event)}")
            if (awaitResult) synchronized(lock) { lastError = "不在房间中" }
            return ControlOutcome(false, "不在房间中", null)
        }
        val prepared = event.copy(
            eventId = event.eventId ?: newEventId(),
            clientTimeMs = System.currentTimeMillis(),
            clientInstanceId = config.clientInstanceId,
            clientSequence = clientSequence.incrementAndGet(),
        )
        val json = ListenTogetherJson.encodeToString(ListenTogetherControlEvent.serializer(), prepared)
        // 完整事件 JSON 留痕（脱敏直链），用于对照 Android 端同类型事件的字段构造；
        // 心跳也走这里，用 debug 避免每 8~15s 刷一条长日志
        AppLog.debug(TAG, "sendControlEvent JSON ${prepared.type}: ${redactControlEventJson(prepared)}")
        val eventId = prepared.eventId ?: newEventId()
        synchronized(lock) { lastOutboundAtMs = System.currentTimeMillis() }
        // 心跳每 8~15s 一次，用 debug；用户主动控制（awaitResult 或关键类型）用 info。
        val verbose = awaitResult || prepared.type in setOf("PLAY", "PAUSE", "SEEK", "SET_TRACK", "SET_QUEUE", "REQUEST_LINK")
        fun logSent(channel: String, ok: Boolean, note: String = "") {
            val message = "sendControlEvent $channel ${controlEventLog(prepared)} awaitResult=$awaitResult ok=$ok ${System.currentTimeMillis() - sendStarted}ms $note".trim()
            if (verbose && ok) AppLog.info(TAG, message) else AppLog.debug(TAG, message)
        }

        if (synchronized(lock) { wsOpen }) {
            val client = synchronized(lock) { wsClient }
            if (awaitResult) {
                val deferred = CompletableDeferred<ControlOutcome>()
                pendingResults[eventId] = deferred
                val sent = withContext(Dispatchers.IO) { client?.send(json) == true }
                if (sent) {
                    logSent("WS", true, "等待 control_result（超时 ${CONTROL_RESULT_TIMEOUT_MS}ms）")
                    val outcome = withTimeoutOrNull(CONTROL_RESULT_TIMEOUT_MS) { deferred.await() }
                    pendingResults.remove(eventId)
                    if (outcome != null) {
                        AppLog.info(TAG, "control_result 已匹配：${controlEventLog(prepared)} ${outcomeLog(outcome)}")
                        return outcome
                    }
                    // 控制结果超时：指令已经发出，按服务端广播的房态为准
                    AppLog.warn(TAG, "control_result 等待超时（${CONTROL_RESULT_TIMEOUT_MS}ms）：${controlEventLog(prepared)} 按房态广播继续")
                    return ControlOutcome(true, null, null)
                }
                pendingResults.remove(eventId)
                logSent("WS", false, "发送失败，转 HTTP 兜底")
            } else {
                val sent = withContext(Dispatchers.IO) { client?.send(json) == true }
                logSent("WS", sent)
                if (sent) return ControlOutcome(true, null, null)
            }
        } else {
            AppLog.debug(TAG, "sendControlEvent WS 未打开，走 HTTP 兜底：${controlEventLog(prepared)}")
        }

        // WebSocket 不可用 → HTTP /control 兜底（Android 也有同样的回退路径）
        if (currentToken.isNullOrBlank()) {
            AppLog.warn(TAG, "sendControlEvent 丢弃：连接已断开且无 token ${controlEventLog(prepared)}")
            return ControlOutcome(false, "连接已断开", null)
        }
        val result = withContext(Dispatchers.IO) { http.sendControlEvent(base, roomId, currentToken, prepared) }
        val response = result.value
        if (result.error != null || response == null) {
            val error = result.error ?: "控制指令发送失败"
            logSent("HTTP", false, "error=$error")
            return ControlOutcome(false, error, null)
        }
        val applied = response.applied
        if (applied?.state != null) {
            val cause = applied.causedBy
            val foreignRequestResult = !isControllerRole(sessionRole) &&
                cause?.type?.startsWith("REQUEST_") == true && !isSelfCause(cause)
            if (!foreignRequestResult) {
                val resolvedState = resolveAppliedPlaybackStateWithPendingIntent(
                    appliedState = applied.state,
                    cause = cause,
                    eventId = prepared.eventId,
                    intentSnapshot = prepared.eventId?.let { pendingPlaybackIntents[it] },
                    ok = response.ok && result.status in 200..299,
                )
                applyAuthoritativeState(
                    state = resolvedState,
                    expectedPosition = responseExpectedPosition(resolvedState, fallbackMs = applied.expectedPositionMs),
                    causedBy = cause,
                    applyToPlayer = !(isControllerRole(sessionRole) && isSelfCause(cause)),
                )
            }
        }
        val outcome = ControlOutcome(
            ok = response.ok && result.status in 200..299,
            error = response.error ?: result.error,
            applied = applied,
        )
        logSent("HTTP", outcome.ok, outcomeLog(outcome))
        return outcome
    }

    private fun newEventId(): String =
        "evt-${System.currentTimeMillis()}-${clientSequence.get()}-${UUID.randomUUID().toString().take(8)}"

    private fun buildWsUrl(baseUrl: String?, roomId: String?, tokenValue: String?): String? {
        if (baseUrl == null || roomId == null || tokenValue.isNullOrBlank()) return null
        val wsBase = when {
            baseUrl.startsWith("https://") -> "wss://" + baseUrl.removePrefix("https://")
            baseUrl.startsWith("http://") -> "ws://" + baseUrl.removePrefix("http://")
            else -> return null
        }
        val encodedToken = java.net.URLEncoder.encode(tokenValue, Charsets.UTF_8)
        return "$wsBase/api/rooms/$roomId/ws?token=$encodedToken"
    }

    private fun resolveRoleFromState(state: ListenTogetherRoomState?, userUuid: String): String {
        if (state == null || state.controllerUserUuid == null) return "listener"
        return if (state.controllerUserUuid.equals(userUuid, ignoreCase = true)) "controller" else "listener"
    }

    private fun isControllerRole(role: String?): Boolean =
        role?.equals("controller", ignoreCase = true) == true

    private fun isController(): Boolean = isControllerRole(sessionRole) && room != null

    /**
     * 当前是否「在房间里且不是控制者」。
     *
     * 听众直接改本地播放队列（加歌/删歌/移动）会被房间权威队列在下次同步时覆盖掉，
     * 用户看到的是「加进去了又莫名消失」。上层据此拦截并给出明确提示，
     * 而不是让改动静默失效。
     */
    fun isListenerInRoom(): Boolean = room != null && !isControllerRole(sessionRole)

    /**
     * 房间当前是否正在播放（读权威房态 `playback.state`）。
     *
     * 听众的本地播放器可能正处在被房间同步覆盖的中间态，**不能**用
     * `container.player.state` 判断——`player.togglePlayPause` 要据此决定发 play 还是 pause，
     * 判错就会「想暂停却发了一次暂停再发播放」。
     */
    fun roomIsPlaying(): Boolean = synchronized(lock) { room?.playback?.state == "playing" }

    /**
     * 房间曲目是否可作为一起听控制目标（对齐 Android `isShareableForListenTogether`）。
     *
     * 本地歌曲没有远端身份，无法进入 worker 的曲目表；缺 stableKey 的曲目也无法被服务端定位。
     * 这类曲目不该发出 REQUEST_PLAY/PAUSE/SEEK/SET_TRACK。
     */
    private fun roomTrackShareable(track: ListenTogetherTrack?): Boolean =
        track != null && track.stableKey.isNotBlank() && track.channelId != LISTEN_TOGETHER_CHANNEL_LOCAL

    /**
     * 结束软校正：把播放速率恢复成用户设置，并清空节流/收敛状态。
     *
     * 所有「不该再追进度」的时机都要调它——离开房间、房间关闭、切歌、暂停、硬 seek。
     * 重复调用无副作用（Player 侧 1.0x 时直接返回）。
     */
    private fun resetSoftSyncRate() {
        synchronized(lock) {
            softSyncConvergedTicks = 0
            lastSoftSyncRateAtMs = 0L
        }
        container.player.resetListenTogetherRate()
    }

    private fun repeatAll(state: ListenTogetherRoomState): Boolean = state.playback.repeatMode == 2

    private fun resolveNickname(requested: String?): String {
        val trimmed = requested?.trim()?.takeIf { it.isNotEmpty() }
            ?: config.nickname.trim().takeIf { it.isNotEmpty() }
        if (trimmed != null && validateListenTogetherNickname(trimmed) == null) return trimmed
        return buildDefaultNickname()
    }

    private fun buildDefaultNickname(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val suffix = (1..4).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        return "Neri$suffix"
    }

    // ---------------------------------------------------------------- 主循环

    private suspend fun tickLoop() {
        while (true) {
            delay(TICK_INTERVAL_MS)
            runCatching { tick() }
        }
    }

    private suspend fun tick() {
        val now = System.currentTimeMillis()
        // 听众与控制者两条路径互斥：控制者上报本地状态，听众只消费房间状态，
        // 因此听众永远不会走到 publishLocalState / maybeSendHeartbeat 里污染上报基线。
        if (isController() && synchronized(lock) { wsOpen }) {
            // 控制者不做软校正：被提升为控制者时把听众期可能残留的临时速率还回去
            if (container.player.listenTogetherSyncRate != 1f) container.player.resetListenTogetherRate()
            publishLocalState(now)
            maybeSendHeartbeat(now)
            maybePublishAudioLink()
        }
        maintainPendingQueueRequest(now)
        correctListenerPlayback(now)
        maybePing(now)
        maybeRefreshState(now)
        scheduleReconnectIfDue(now)
    }

    /**
     * 维护乐观队列请求的生命周期：
     *  - 仍在窗口内：持续屏蔽听众看门狗（它在 applyRoomStateToPlayer 里会被重置成 400ms，
     *    这里每轮补回来，否则看门狗读到权威房态会把未确认的本地改动覆盖掉）；
     *  - 超过 [QUEUE_REQUEST_PENDING_MS]：服务端迟迟没有推进版本（消息丢失/被静默丢弃），
     *    交还给权威房态，避免本地队列长期与房间分叉。
     */
    private suspend fun maintainPendingQueueRequest(now: Long) {
        val expired = synchronized(lock) {
            val pending = pendingQueueRequest ?: return
            if (now - pending.createdAtMs < QUEUE_REQUEST_PENDING_MS) {
                suppressLocalUntilMs = now + QUEUE_REQUEST_PENDING_MS
                return
            }
            pendingQueueRequest = null
            notice = "队列请求未得到确认，已恢复为房间队列"
            AppLog.warn(TAG, "队列请求超时未确认（>${QUEUE_REQUEST_PENDING_MS}ms）：requestId=${pending.requestId.take(20)}，回滚为权威房态")
            pending
        }
        rollbackOptimisticQueue(expired)
        notifyState()
    }

    private suspend fun scheduleReconnectIfDue(now: Long) {
        // 只有「连接已断开、等待重连」（handleSocketLost/rejoin 失败）时才重连；
        // 创建/加入后正在等 welcome 的 connecting 阶段不能抢先重连，否则会重复 join + 重复 WS。
        val shouldReconnect = synchronized(lock) {
            config.session != null && !wsOpen && !leaving &&
                connection == "reconnecting" && now >= nextReconnectAtMs && !reconnectInFlight
        }
        if (!shouldReconnect) return
        val saved = synchronized(lock) { config.session } ?: return
        AppLog.debug(TAG, "scheduleReconnectIfDue：到点触发，roomId=${saved.roomId}")
        scheduleReconnect()
        if (saved.roomId.isBlank()) return
    }

    private fun notifyState() {
        runCatching {
            val payload = ListenTogetherJson.encodeToJsonElement(
                ListenTogetherSnapshot.serializer(),
                snapshot(),
            )
            broadcast(EVENT_STATE, payload)
        }
    }

    private fun fail(message: String): ListenTogetherSnapshot {
        // 所有 fail 分支统一留痕：以往往往只改了快照 error 字段，日志面板里完全看不到原因。
        AppLog.warn(TAG, "操作失败：$message")
        synchronized(lock) { lastError = message }
        notifyState()
        return snapshot()
    }

    /** 供 API 层在校验失败（例如传入的歌曲无法转换）时把错误写进快照并返回。 */
    fun failWith(message: String): ListenTogetherSnapshot = fail(message)

    private fun playbackSnapshot(state: ListenTogetherRoomState?, now: Long): ListenTogetherPlaybackSnapshot {
        if (state == null) {
            return ListenTogetherPlaybackSnapshot(
                state = container.player.state.value.toListenTogetherPlaybackStateName(),
                positionMs = container.player.positionMs.value,
                durationMs = container.player.durationMs.value,
                serverNowMs = now + serverClockOffsetMs,
            )
        }
        val playing = state.playback.state == "playing"
        val duration = (state.track ?: state.queue.getOrNull(state.currentIndex))?.durationMs ?: 0L
        // 与驱动播放器的那套推算保持同源：都按播放基线 + 已补偿的时钟偏移投影，
        // 否则面板显示的进度会和实际同步目标差一个网络单程延迟。
        val position = expectedRoomPositionAt(state, now, fallbackMs = expectedPositionMs)
        return ListenTogetherPlaybackSnapshot(
            state = if (playing) "playing" else "paused",
            positionMs = position.coerceAtLeast(0L),
            durationMs = duration,
            serverNowMs = now + serverClockOffsetMs,
            basePositionMs = state.playback.basePositionMs,
            repeatMode = state.playback.repeatMode ?: 0,
            shuffleEnabled = state.playback.shuffleEnabled == true,
        )
    }
}

