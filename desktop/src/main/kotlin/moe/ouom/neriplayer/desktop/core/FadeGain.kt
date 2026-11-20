package moe.ouom.neriplayer.desktop.core

import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/**
 * 淡入淡出增益状态。写入线程按「已写入采样数」推进，命令线程只改目标，
 * 两组数据互不阻塞：AtomicReference 里的快照对读写双方都是可见且一致的。
 *
 * 交叉淡入时旧曲用 target=0 的 fadeOut、新曲用 target=1 的 fadeIn，
 * 两条 PCM 流各自乘增益后混合，互补叠加即可，不需要额外的混音器。
 */
class FadeGain {
    private data class State(
        val current: Float,
        val target: Float,
        val elapsedSamples: Long,
        val totalSamples: Long,
    )

    private val state = AtomicReference(State(1f, 1f, 0L, 0L))

    /** 目标 1f=淡入，0f=淡出；durationMs<=0 时立即跳转，不产生渐变。 */
    fun retarget(target: Float, sampleRate: Int, durationMs: Int) {
        val goal = target.coerceIn(0f, 1f)
        val total = if (durationMs > 0) durationMs.toLong() * sampleRate / 1000L else 0L
        while (true) {
            val old = state.get()
            // 与上一次目标相同时不重置进度，避免写盘/设置刷新打断正在进行的渐变。
            if (old.target == goal) return
            val next = if (total <= 0L) {
                State(goal, goal, 0L, 0L)
            } else {
                // 从当前增益出发、以 0 进度开始新渐变；起点就是当前值，不会有音量跳变。
                State(old.current, goal, 0L, total)
            }
            if (state.compareAndSet(old, next)) return
        }
    }

    /** 立即设置增益（seek / open 等需要瞬时生效的场景）。 */
    fun reset(value: Float) {
        val fixed = value.coerceIn(0f, 1f)
        state.set(State(fixed, fixed, 0L, 0L))
    }

    /** 读取当前增益；写入线程每处理 frames 个采样帧调用一次推进。 */
    fun gainAndAdvance(frames: Int): Float {
        while (true) {
            val old = state.get()
            if (old.totalSamples <= 0L || old.current == old.target) return old.current
            val elapsed = old.elapsedSamples + max(0, frames)
            val value = if (elapsed >= old.totalSamples) {
                old.target
            } else {
                val t = elapsed.toFloat() / old.totalSamples
                old.current + (old.target - old.current) * t
            }.coerceIn(0f, 1f)
            val next = if (value == old.target) {
                State(value, value, 0L, 0L)
            } else {
                State(value, old.target, elapsed, old.totalSamples)
            }
            if (state.compareAndSet(old, next)) return value
        }
    }
}
