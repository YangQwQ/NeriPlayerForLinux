package moe.ouom.neriplayer.desktop.core

/**
 * 「解码器读到 EOF」→「是否真的播完了」的判定策略。
 *
 * 解码流水线只有在 ffmpeg 进程 stdout 关闭时才知道「播放结束」，但远端直链会让它在中途就
 * 关闭，而 ffmpeg 往往以 0 正常退出、stderr 一个字都不写：
 *
 * 1. 输入侧 `-ss`（`-i` 之前）要靠一条**带偏移的 Range 请求**才能落到目标位置。直链对这种
 *    请求返回 403 / 空的 206 时，ffmpeg 只是放弃定位，stdout 立刻 EOF。表现就是用户说的
 *    「一拖动进度条就跳到下一首」；
 * 2. CDN / 网络把长音频的响应截断，ffmpeg 解出前面一段后报 `Stream ends prematurely` 再 EOF。
 *
 * 两者都不是播放结束。判定依据是「这条流水线实际解出了多少音频」与「已知时长还差多少」，
 * 而不是 EOF 本身，也不是进程退出码。
 *
 * 时间线约定：`startMs` / `durationMs` 是**媒体时间线**（音轨本身的时间戳），
 * `playedOutMs` 是**输出时间线**（ffmpeg 真正吐出的 PCM 时长，已被倍速压缩）。
 * 两者不能直接相加，换算统一在这一处做。
 */
object PlaybackEndPolicy {

    /** 末尾允许的绝对误差下限（媒体时间线）：跳转精度、编码帧边界都可能差零点几秒。 */
    const val MIN_TOLERANCE_MS: Long = 3_000L

    /** 末尾允许的绝对误差上限：再大就把「中途断掉」也当成播完了。 */
    const val MAX_TOLERANCE_MS: Long = 60_000L

    /** 相对误差：长音频按 2% 放宽，覆盖元数据时长与真实音频的常见偏差。 */
    const val RELATIVE_TOLERANCE: Double = 0.02

    enum class Verdict {
        /** 真的播到了结尾（含「用户把进度条拖到最右端」这种起点就在结尾的情况）。 */
        COMPLETED,

        /** 中途断了：末段音频缺失，不能当成播放结束。 */
        TRUNCATED,

        /**
         * 时长未知但确实解出了音频：既无法证明也无法否证，按播完处理。
         * 误判成中断会让未知时长音频的正常结束变成卡在原地，代价比偶发误切更大。
         */
        UNKNOWN_DURATION,
    }

    data class Outcome(
        val verdict: Verdict,
        /** 这条流水线实际输出的音频长度（输出时间线毫秒）。 */
        val playedOutMs: Long,
        /** 断点位置（媒体时间线毫秒）：续播从这里开始。 */
        val breakpointMs: Long,
        /** 距离结尾还差多少媒体毫秒；已到结尾时为 0。 */
        val remainingMs: Long,
        /** 是否连一个字节的音频都没解出来（多半是 seek 请求当场被拒）。 */
        val producedNothing: Boolean,
        /** stderr 里是否出现了「流被打断」的痕迹，仅用于诊断与提示。 */
        val streamBroken: Boolean,
    ) {
        /** 是否可以当成播放结束（含「时长未知」这种无法判定但按结束处理的情况）。 */
        val isCompleted: Boolean get() = verdict != Verdict.TRUNCATED
    }

    /** 末尾容差（媒体时间线毫秒）；[durationMs] 未知时无意义，返回 0。 */
    fun toleranceMs(durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        val relative = (durationMs * RELATIVE_TOLERANCE).toLong()
        return relative.coerceIn(MIN_TOLERANCE_MS, MAX_TOLERANCE_MS)
    }

    /** 倍速归一化：非法值按 1.0 处理，避免除零。 */
    private fun speedFactor(speed: Float): Double =
        if (speed.isFinite() && speed > 0.01f) speed.toDouble() else 1.0

    /**
     * 从媒体时间 [startMs] 播到结尾，按倍速 [speed] 应当输出多少毫秒 PCM。
     *
     * 变速走 atempo；变调链里 asetrate / aresample 的伸缩与 atempo 的补偿相互抵消，
     * 净效果就是「输出时长 = 媒体时长 / 倍速」。
     */
    fun expectedOutputMs(durationMs: Long, startMs: Long, speed: Float): Long {
        if (durationMs <= 0L) return 0L
        val remainingMedia = (durationMs - startMs.coerceAtLeast(0L)).coerceAtLeast(0L)
        return (remainingMedia / speedFactor(speed)).toLong()
    }

    /**
     * 解码器 EOF 时的最终判定。
     *
     * @param startMs 本条流水线的起点（媒体时间线；输入侧 / 解码侧 seek 都是这个位置）
     * @param playedOutMs 本条流水线实际输出的 PCM 毫秒数
     * @param durationMs 已知的音轨时长（媒体时间线）；<=0 表示未知
     * @param speed 构建流水线时的倍速
     * @param producedAudio 是否至少解出了一个字节的音频
     * @param stderr ffmpeg 的 stderr 文本（可为空）
     */
    fun check(
        startMs: Long,
        playedOutMs: Long,
        durationMs: Long,
        speed: Float,
        producedAudio: Boolean,
        stderr: String,
    ): Outcome {
        val start = startMs.coerceAtLeast(0L)
        val played = playedOutMs.coerceAtLeast(0L)
        val factor = speedFactor(speed)
        val broken = hasStreamBreak(stderr)
        val breakpoint = start + (played * factor).toLong()

        if (durationMs <= 0L) {
            return if (!producedAudio) {
                // 时长未知 + 一个字节都没解出来：只能认定这条流没跑起来，不能当播完
                Outcome(
                    verdict = Verdict.TRUNCATED,
                    playedOutMs = played,
                    breakpointMs = start,
                    remainingMs = -1L,
                    producedNothing = true,
                    streamBroken = broken,
                )
            } else {
                Outcome(
                    verdict = Verdict.UNKNOWN_DURATION,
                    playedOutMs = played,
                    breakpointMs = breakpoint,
                    remainingMs = 0L,
                    producedNothing = false,
                    streamBroken = broken,
                )
            }
        }

        val tolerance = toleranceMs(durationMs)
        // 起点已经在结尾容差内：拖到最右端后没有音频可解，属于正常结束而不是中断
        if (start >= durationMs - tolerance) {
            return Outcome(
                verdict = Verdict.COMPLETED,
                playedOutMs = played,
                breakpointMs = durationMs,
                remainingMs = 0L,
                producedNothing = !producedAudio,
                streamBroken = broken,
            )
        }
        val missingMedia = (durationMs - breakpoint).coerceAtLeast(0L)
        return if (missingMedia <= tolerance) {
            Outcome(
                verdict = Verdict.COMPLETED,
                playedOutMs = played,
                breakpointMs = durationMs,
                remainingMs = 0L,
                producedNothing = !producedAudio,
                streamBroken = broken,
            )
        } else {
            Outcome(
                verdict = Verdict.TRUNCATED,
                playedOutMs = played,
                breakpointMs = breakpoint,
                remainingMs = missingMedia,
                producedNothing = !producedAudio,
                streamBroken = broken,
            )
        }
    }

    /** 上层复核用：播放位置是否已经到了结尾（[durationMs] 未知时不校验）。 */
    fun isCompletionConfirmed(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return true
        return positionMs >= durationMs - toleranceMs(durationMs)
    }

    /**
     * 用户可见的中断提示：带上播放到哪、总时长多少、以及 ffmpeg 的原话，
     * 便于判断是直链问题还是真的没缓存好。
     */
    fun interruptedMessage(breakpointMs: Long, durationMs: Long, detail: String?): String {
        val progress = if (durationMs > 0L) {
            "已播放到 ${formatDuration(breakpointMs)} / 共 ${formatDuration(durationMs)}"
        } else {
            "已播放 ${formatDuration(breakpointMs)}（总时长未知）"
        }
        val suffix = detail?.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()
        return "音频流提前中断（$progress$suffix）。已停止播放，不会自动切歌；可重新拖动进度条再试"
    }

    /** 简短时长文本：不足 1 小时用 `m:ss`，否则 `h:mm:ss`。 */
    fun formatDuration(ms: Long): String {
        val total = ms.coerceAtLeast(0L) / 1000L
        val hours = total / 3600L
        val minutes = (total % 3600L) / 60L
        val seconds = total % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    /** stderr 里出现这些串说明输入流不是正常结束（CDN 截断 / 半截文件 / 解复用错误）。 */
    private val STREAM_BREAK_MARKERS = listOf(
        "ends prematurely",
        "partial file",
        "error during demuxing",
        "error reading header",
        "error opening input",
        "input/output error",
        "connection reset",
        "invalid data found",
        "403 forbidden",
        "401 unauthorized",
        "550 ",
    )

    /** stderr 是否含有「流被打断」的痕迹。 */
    fun hasStreamBreak(stderr: String): Boolean {
        if (stderr.isBlank()) return false
        val lower = stderr.lowercase()
        return STREAM_BREAK_MARKERS.any { marker -> lower.contains(marker) }
    }
}
