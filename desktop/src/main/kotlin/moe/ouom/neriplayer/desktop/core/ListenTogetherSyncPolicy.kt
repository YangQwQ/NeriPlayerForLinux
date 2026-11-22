package moe.ouom.neriplayer.desktop.core

import kotlin.math.abs

/**
 * 一起听听众侧的软同步速率工具。
 *
 * 决策（要不要 seek / play / pause / reload）已全部收敛到
 * [ListenTogetherSyncPlanner]；这里只保留「怎么把偏差折算成临时播放速率」这一件事，
 * 与 Android `ListenTogetherPlaybackSyncPolicies.resolveListenTogetherSoftSyncPlaybackRate`
 * 对齐。桌面特有的迟滞/下发节流是对 ffmpeg 重建流水线成本的补偿，Android 不需要。
 */
internal object ListenTogetherSyncPolicy {

    /** 软同步速率的允许范围：相当于 Android `PlaybackSoundPolicy` 的 0.95~1.05 合并区间。 */
    const val SOFT_SYNC_RATE_MIN = 0.95f
    const val SOFT_SYNC_RATE_MAX = 1.05f

    /**
     * 软校正的目标速率：与 Android `resolveListenTogetherSoftSyncPlaybackRate` 一致。
     *
     * 返回 `null` 表示这档偏差不该用软同步（太小、太大、或不在播放中）。
     */
    fun resolveSoftSyncRate(
        driftMs: Long,
        softSyncMinDriftMs: Long,
        softSyncFastDriftMs: Long,
        hardSyncDriftMs: Long,
    ): Float? {
        val drift = abs(driftMs)
        if (drift < softSyncMinDriftMs || drift >= hardSyncDriftMs) return null
        return when {
            driftMs >= softSyncFastDriftMs -> 1.05f
            driftMs > 0L -> 1.03f
            driftMs <= -softSyncFastDriftMs -> 0.95f
            else -> 0.97f
        }
    }

    /**
     * 带迟滞的软校正速率：追进度的过程中**只升不降**，避免为了 3%→5% 这种「更快的收敛」
     * 反而多付一次流水线重建。
     *
     * 为什么桌面版必须和 Android 不同：Android 的 ExoPlayer 换倍速是实时生效的，
     * 可以随偏差变化每秒钟微调一次；桌面引擎的 [FfmpegAudioEngine.setSpeed] 会重建整条
     * ffmpeg 流水线（可听见的断音），一轮校正里换 2~3 次速率就是 2~3 次卡顿。
     * 偏差从 1.5s 降到 1.4s 时把 1.05 降成 1.03 完全不值得——1.05 只会让它收敛得更快。
     *
     * 因此规则是：
     *  - 偏差跑出软区间（太小或超过硬阈值）→ 返回 null，由调用方收回速率；
     *  - 当前没有软校正 → 用标准档位；
     *  - 偏差换了方向（本地从落后变超前，或相反）→ 必须换档，否则会越追越偏；
     *  - 目标档位幅度不小于当前档位 → 升档（收敛更快，且这次重建有明确收益）；
     *  - 否则保持当前速率，不做无谓重建。
     */
    fun resolveSoftSyncRateWithHysteresis(
        currentRate: Float,
        driftMs: Long,
        softSyncMinDriftMs: Long,
        softSyncFastDriftMs: Long,
        hardSyncDriftMs: Long,
    ): Float? {
        val target = resolveSoftSyncRate(driftMs, softSyncMinDriftMs, softSyncFastDriftMs, hardSyncDriftMs)
            ?: return null
        if (abs(currentRate - 1f) < 0.001f) return target
        val currentSign = if (currentRate > 1f) 1 else -1
        val targetSign = if (target > 1f) 1 else -1
        if (currentSign != targetSign) return target
        return if (abs(target - 1f) >= abs(currentRate - 1f)) target else currentRate
    }

    /**
     * 是否需要把新速率下发给引擎。
     *
     * 桌面引擎改速率会重建整条 ffmpeg 流水线（[FfmpegAudioEngine.setSpeed] → `restartPreservingPosition`），
     * 频繁调用会卡顿甚至爆音，所以这里做了两道闸：速率没实质变化就不下发，
     * 且两次下发至少间隔 [minIntervalMs]。
     */
    fun shouldRetuneRate(
        currentRate: Float,
        nextRate: Float,
        sinceLastAdjustMs: Long,
        minIntervalMs: Long,
        epsilon: Float,
    ): Boolean {
        if (abs(nextRate - currentRate) < epsilon) return false
        return sinceLastAdjustMs >= minIntervalMs
    }
}
