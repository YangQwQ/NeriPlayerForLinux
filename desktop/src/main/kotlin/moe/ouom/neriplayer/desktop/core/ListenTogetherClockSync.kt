package moe.ouom.neriplayer.desktop.core

/**
 * 一起听的时钟同步：估计「服务端时钟 - 本机时钟」的偏移。
 *
 * 为什么需要它：房态里的权威进度是服务端用自己的时钟算出来的（`basePositionMs` +
 * `baseTimestampMs`），听众必须把本机时钟换算到服务端时钟上才能算出「现在该播到哪」。
 * 直接用「服务端时间戳 - 收到时的本机时间」会**系统性少算一个单程网络延迟**：
 * 服务端的时间戳是它发消息那一刻的，消息还要再飞一段才到本机。少算的这部分会直接变成
 * 听众的永久滞后（进度一直比房间慢「单程延迟」那么多，网络越差越明显）。
 *
 * 正确做法与 Android 参考实现一致（`ListenTogetherSocketHealthOwner`）：
 *  1. 发 ping 时记下本机的单调时钟（`np_ping.t`），服务端在 `np_pong` 里原样回显；
 *  2. 收到 pong 时 `rtt = now - t`，用「发送时刻 + rtt / 2」作为服务端时间戳对应的本机时刻
 *     （假设上下行延迟对称，这也是 NTP 的假设）；
 *  3. 样本按 7:3 平滑，避免单次抖动把偏移拽飞；
 *  4. `rtt` 超过 [ListenTogetherClockSyncPolicy.MAX_RTT_MS] 的样本丢弃（连接卡住后
 *     迟到的 pong 只会带来噪声）；
 *  5. 拿到往返样本之前，单向样本（每条消息的 `nowMs`）只能先把偏移垫住，之后不再采信——
 *     它天然少算单程延迟，继续用它会把已经校准好的偏移又拽回去。
 *
 * 双时间基准的理由：`System.currentTimeMillis()` 会被 NTP 校时/用户改时间跳动，而偏移
 * 是「服务端 - 本机墙钟」的差值，跳动会污染样本。所以 RTT 用单调时钟
 * （`System.nanoTime()`）算，只有最终换算服务端时间戳时才用到墙钟；反过来把回显的
 * 单调时刻映射回墙钟也用两者的差值，避免在两种基准之间混用。
 *
 * 这个类只做计算，不碰网络与播放器，便于单独推理。
 */
internal object ListenTogetherClockSyncPolicy {

    /**
     * 超过它的往返样本直接丢弃。
     *
     * 30s 与 Android 一致：真实网络往返不可能到这个量级，出现这种样本只说明 pong 迟到了
     * （连接挂起、DO 冷启动），按它算出的偏移毫无意义。
     */
    const val MAX_RTT_MS = 30_000L

    /** 平滑权重：历史 7 成、新样本 3 成（与 Android `blendedOffset` 一致）。 */
    private const val PREVIOUS_WEIGHT = 7L
    private const val SAMPLE_WEIGHT = 3L

    /** 往返时间；`sentAtElapsedMs <= 0` 表示没有可用的发送记录。 */
    fun rttMs(nowElapsedMs: Long, sentAtElapsedMs: Long): Long? {
        if (sentAtElapsedMs <= 0L) return null
        val rtt = nowElapsedMs - sentAtElapsedMs
        if (rtt < 0L || rtt > MAX_RTT_MS) return null
        return rtt
    }

    /**
     * 服务端时间戳对应的本机墙钟时刻。
     *
     * 有往返样本时取「发送时刻 + rtt/2」—— 即服务端打时间戳的那一刻在本机时钟上的位置；
     * 单向样本没有往返信息，只能退化成「收到时的墙钟」，这也是它必须被平滑、且只在
     * 首个往返样本之前使用的原因。
     */
    fun referenceWallMs(hasRoundTrip: Boolean, sentAtWallMs: Long, rttMs: Long, nowWallMs: Long): Long =
        if (hasRoundTrip) sentAtWallMs + rttMs / 2 else nowWallMs

    /** 单个样本的偏移；服务端时间戳非法时返回 null。 */
    fun sampleOffsetMs(serverNowMs: Long?, referenceWallMs: Long): Long? {
        if (serverNowMs == null || serverNowMs <= 0L) return null
        if (referenceWallMs <= 0L) return null
        return serverNowMs - referenceWallMs
    }

    /**
     * 把新样本并进已有偏移。
     *
     * 首个样本（[hasPrevious] 为 false）与「刚拿到第一个往返样本」两种情况直接采信新样本：
     * 前者没有历史，后者要把之前被单向样本垫低的偏移一次性扳正，平滑反而拖慢收敛。
     */
    fun blendOffsetMs(
        previousOffsetMs: Long,
        hasPrevious: Boolean,
        sampleMs: Long,
        hasRoundTrip: Boolean,
        previousFromRoundTrip: Boolean,
    ): Long {
        if (!hasPrevious) return sampleMs
        if (hasRoundTrip && !previousFromRoundTrip) return sampleMs
        return (previousOffsetMs * PREVIOUS_WEIGHT + sampleMs * SAMPLE_WEIGHT) / 10L
    }
}

/**
 * 时钟偏移的状态机（收发时机、样本平滑、丢弃策略）。
 *
 * 与 [ListenTogetherClockSyncPolicy] 的分工：策略对象是纯函数，这个类负责持有时序状态。
 * 时间来源可注入，便于在别处以确定性的时钟复用同一套逻辑。
 */
internal class ListenTogetherClockSync(
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val wallMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()

    /** 服务端时钟 - 本机墙钟（毫秒）；0 表示还没拿到任何样本。 */
    @Volatile
    var offsetMs: Long = 0L
        private set

    /** 是否已有往返样本；有它之后单向样本一律不采信。 */
    @Volatile
    var hasRoundTripOffset: Boolean = false
        private set

    /** 本轮 ping 的发送时刻（单调时钟毫秒），服务端会在 pong 里回显。 */
    private var pingSentAtElapsedMs = 0L

    /** 同一轮 ping 的发送墙钟，用于「服务端没回显 t」的旧版 pong。 */
    private var pingSentAtWallMs = 0L

    private var hasSample = false

    /**
     * 记录一次 ping 发出，返回要放进 `np_ping.t` 的值。
     *
     * 用单调时钟而不是墙钟：服务端只负责原样回显，客户端靠它算 RTT，
     * 墙钟在这中间被校时会算出负数或巨大的往返时间。
     */
    fun notePingSent(): Long = synchronized(lock) {
        val elapsed = elapsedMs()
        pingSentAtElapsedMs = elapsed
        pingSentAtWallMs = wallMs()
        elapsed
    }

    /** 收到 `np_pong` / `pong`：能用回显时刻算往返就用往返，否则退回记录的发送时刻。 */
    fun onPong(serverNowMs: Long?, echoedElapsedMs: Long?, reason: String = "pong") {
        val echoed = echoedElapsedMs?.takeIf { it > 0L }
        val (sentAtElapsed, sentAtWall) = synchronized(lock) {
            if (echoed != null) {
                // 回显值比记录更可靠（可能跨了多轮 ping），但要把它映射回墙钟：
                // 本机墙钟与单调时钟的差值在几秒内是稳定的。
                val nowElapsed = elapsedMs()
                val nowWall = wallMs()
                val wall = nowWall - (nowElapsed - echoed)
                echoed to wall
            } else {
                pingSentAtElapsedMs to pingSentAtWallMs
            }
        }
        applySample(
            serverNowMs = serverNowMs,
            sentAtWallMs = sentAtWall,
            sentAtElapsedMs = sentAtElapsed,
            reason = reason,
        )
    }

    /**
     * 单向样本：来自任意一条服务端消息的 `nowMs`。
     *
     * 只在还没有往返样本时用于垫底。之后必须忽略——它少算了一个单程延迟，
     * 每次都用它「修正」一次，等于把校准好的偏移持续往回拽。
     */
    fun onServerMessage(serverNowMs: Long?, reason: String = "message") {
        if (serverNowMs == null || serverNowMs <= 0L) return
        if (synchronized(lock) { hasRoundTripOffset }) return
        applySample(serverNowMs = serverNowMs, sentAtWallMs = 0L, sentAtElapsedMs = 0L, reason = reason)
    }

    /** 带往返的样本（HTTP `/state` 这类请求/响应也能提供一个）。 */
    fun onRoundTrip(
        serverNowMs: Long?,
        sentAtWallMs: Long,
        sentAtElapsedMs: Long,
        reason: String = "round_trip",
    ) {
        applySample(
            serverNowMs = serverNowMs,
            sentAtWallMs = sentAtWallMs,
            sentAtElapsedMs = sentAtElapsedMs,
            reason = reason,
        )
    }

    /** 断线重连后清掉未完成的 ping，避免用一个很久以前的发送时刻算出巨大的 RTT。 */
    fun resetPingTracking() {
        synchronized(lock) {
            pingSentAtElapsedMs = 0L
            pingSentAtWallMs = 0L
        }
    }

    /** 会话结束：偏移一起清空，下次进房间重新校准。 */
    fun reset() {
        synchronized(lock) {
            offsetMs = 0L
            hasSample = false
            hasRoundTripOffset = false
            pingSentAtElapsedMs = 0L
            pingSentAtWallMs = 0L
        }
    }

    private fun applySample(serverNowMs: Long?, sentAtWallMs: Long, sentAtElapsedMs: Long, reason: String) {
        if (serverNowMs == null || serverNowMs <= 0L) return
        val hasRoundTrip = sentAtWallMs > 0L && sentAtElapsedMs > 0L
        val nowElapsed = elapsedMs()
        val nowWall = wallMs()
        val rtt = if (hasRoundTrip) {
            ListenTogetherClockSyncPolicy.rttMs(nowElapsed, sentAtElapsedMs) ?: run {
                AppLog.debug(TAG, "丢弃过期的时钟样本：reason=$reason, rtt=${nowElapsed - sentAtElapsedMs}ms")
                return
            }
        } else {
            0L
        }
        val reference = ListenTogetherClockSyncPolicy.referenceWallMs(hasRoundTrip, sentAtWallMs, rtt, nowWall)
        val sample = ListenTogetherClockSyncPolicy.sampleOffsetMs(serverNowMs, reference) ?: return
        synchronized(lock) {
            val blended = ListenTogetherClockSyncPolicy.blendOffsetMs(
                previousOffsetMs = offsetMs,
                hasPrevious = hasSample,
                sampleMs = sample,
                hasRoundTrip = hasRoundTrip,
                previousFromRoundTrip = hasRoundTripOffset,
            )
            offsetMs = blended
            hasSample = true
            if (hasRoundTrip) {
                hasRoundTripOffset = true
                pingSentAtElapsedMs = 0L
                pingSentAtWallMs = 0L
            }
        }
        AppLog.debug(
            TAG,
            "时钟偏移更新：reason=$reason, rtt=${rtt}ms, sample=$sample, offset=$offsetMs",
        )
    }

    private companion object {
        const val TAG = "listenTogether.clock"
    }
}
