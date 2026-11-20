package moe.ouom.neriplayer.desktop.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.math.abs
import kotlin.math.pow

data class EngineSnapshot(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val error: String? = null,
)

data class AudioInput(
    val path: String? = null,
    val url: String? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    val target: String get() = path ?: url.orEmpty()

    fun displayTarget(): String = path?.substringAfterLast('/') ?: url.orEmpty().substringAfterLast('/')
}

interface AudioEngine {
    val snapshot: StateFlow<EngineSnapshot>
    val supportsEffects: Boolean
    var onCompleted: (() -> Unit)?
    var onError: ((String) -> Unit)?

    fun open(input: AudioInput, startMs: Long, durationMs: Long)

    /** 切歌时先重置进度与时长，避免界面短暂显示上一首的进度信息。 */
    fun prepare(durationMs: Long)
    fun play()
    fun pause()
    fun release()
    fun seekTo(positionMs: Long, input: AudioInput? = null)
    fun setVolume(volume: Float)
    fun setSpeed(speed: Float)
    fun setPitch(semitones: Float)
    fun setLoudness(enabled: Boolean)

    /**
     * 旧版十段均衡器入口：`null` 表示关闭。
     * 现在只是 [setAudioEffects] 的语法糖（把十段增益映射成图形均衡），保留是为了老调用方。
     */
    fun setEqualizer(bands: List<Float>?)

    /**
     * DSP 音效参数：`null` 表示完全旁路。
     *
     * 引擎只认这一个入口，DSP 模型 → 具体滤镜的映射集中在 `FfmpegAudioEngine.applyAudioEffectsToFilters()`，
     * 换成原生 DSP 时替换那一处即可，Player / 协议 / 界面都不用动。
     */
    fun setAudioEffects(effects: AudioEffectsSound?) = Unit

    /** 渐变目标：target=1f 淡入、0f 淡出；durationMs<=0 时立即跳变。 */
    fun fadeTo(target: Float, sampleRate: Int, durationMs: Int) = Unit

    /** 立即把渐变增益设为 value（seek / 起播时清除残留渐变）。 */
    fun fadeReset(value: Float) = Unit

    /** 交叉淡入：新引擎写入前与旧引擎 PCM 叠加；传 null 取消。 */
    fun setMixFrom(source: (() -> Pair<ByteArray, Int>)?) = Unit

    /**
     * 交叉淡入：把本引擎的输出 PCM 推给新引擎（在应用自身增益之后）。
     * 传 null 关闭 tap。回调参数 (PCM, 有效字节数)。
     */
    fun setPcmTap(tap: ((ByteArray, Int) -> Unit)?) = Unit
}

private const val SAMPLE_RATE = 48_000
private const val CHANNELS = 2
private const val BYTES_PER_FRAME = CHANNELS * 2

/** 每次从 ffmpeg 管道读的块大小（32 KiB ≈ 170 ms 音频）。 */
private const val READ_BLOCK_BYTES = 1 shl 15
object FfmpegSupport {
    val available: Boolean by lazy {
        runCatching {
            val process = ProcessBuilder("ffmpeg", "-version")
                .redirectErrorStream(true)
                .start()
            process.inputStream.readBytes()
            process.waitFor() == 0
        }.getOrDefault(false)
    }

    val version: String by lazy {
        runCatching {
            val process = ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start()
            val text = process.inputStream.readBytes().decodeToString().lineSequence().firstOrNull().orEmpty()
            process.waitFor()
            text
        }.getOrDefault("")
    }

    fun probeDurationMs(input: AudioInput): Long = runCatching {
        val command = mutableListOf("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1")
        if (input.headers.isNotEmpty()) {
            command += listOf("-headers", input.headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n")
        }
        command += input.target
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val text = process.inputStream.readBytes().decodeToString().trim()
        process.waitFor()
        val seconds = text.toDoubleOrNull() ?: return@runCatching 0L
        (seconds * 1000.0).toLong()
    }.getOrDefault(0L)
}

/** 主播放引擎：ffmpeg 解码为 PCM，Java Sound 输出。支持变速、变调、响度与均衡器。 */
class FfmpegAudioEngine : AudioEngine {

    override val supportsEffects: Boolean = true

    private val _snapshot = MutableStateFlow(EngineSnapshot())
    override val snapshot: StateFlow<EngineSnapshot> = _snapshot.asStateFlow()

    override var onCompleted: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    private val lock = Any()
    private var process: Process? = null
    private var line: SourceDataLine? = null
    private var readerThread: Thread? = null
    private var tickerThread: Thread? = null

    private var input: AudioInput? = null
    private var offsetMs: Long = 0L
    private var durationMs: Long = 0L
    private var volume: Float = 0.85f
    /** 音量由命令线程写、PCM 写入线程读，必须 volatile 保证即时可见。 */
    private var speed: Float = 1.0f
    private var pitch: Float = 0.0f
    private var loudness: Boolean = false

    /** 当前 DSP 音效参数；`null` 表示旁路。由命令线程写、滤镜构建时读。 */
    @Volatile private var audioEffects: AudioEffectsSound? = null

    /**
     * 手递给音频线程的 native DSP 参数快照（SPSC：命令线程写、音频线程读）。
     *
     * `null` 表示「完全不碰 native 引擎」（DSP 从未启用）；`enabled=false` 的音效参数
     * 会是 MASTER_ENABLED=0 的数组，native 借此把总混合比淡到 0（等价直通），
     * 不需要重启解码器。
     */
    private val dspParams = AtomicReference<FloatArray?>(null)

    /**
     * 当前这条解码流水线是否把音效交给 native DSP。
     *
     * 这个布尔量决定两件事，必须同时成立：
     * 1. `buildFilterChain()` 不再拼 equalizer/bass/treble/volume（否则同一套参数处理两遍）；
     * 2. `readLoop` 在写 `line` 之前调用 native 处理。
     * 因此它只在 `startPipeline()` 里按 [NativeDsp.isAvailable] 求值一次，整条流水线一致。
     */
    @Volatile private var nativeDsp = false
    private var playRequested = false
    private var restarting = false
    private var generation = 0
    private val pauseGate = Object()
    private val fadeGain = FadeGain()
    private val mixSource = MixSource()
    @Volatile private var pcmTap: ((ByteArray, Int) -> Unit)? = null

    @Volatile
    private var paused = false

    override fun open(input: AudioInput, startMs: Long, durationMs: Long) {
        synchronized(lock) {
            stopPipelineLocked()
            this.input = input
            this.offsetMs = startMs.coerceAtLeast(0L)
            this.durationMs = durationMs
            playRequested = false
            paused = false
            fadeGain.reset(1f)
            mixSource.set(null)
            _snapshot.value = EngineSnapshot(
                positionMs = this.offsetMs,
                durationMs = durationMs,
                playing = false,
                buffering = true,
            )
        }
        startPipeline(startMs = offsetMs, autoPlay = false)
    }

    override fun prepare(durationMs: Long) {
        synchronized(lock) {
            offsetMs = 0L
            this.durationMs = durationMs
        }
        _snapshot.value = EngineSnapshot(
            positionMs = 0L,
            durationMs = durationMs,
            playing = false,
            buffering = true,
        )
    }

    override fun play() {
        val current = synchronized(lock) {
            playRequested = true
            paused = false
            line
        }
        if (FadeSettings.fadeEnabled) {
            fadeGain.reset(0f)
            fadeGain.retarget(1f, SAMPLE_RATE, FadeSettings.fadeInMs)
        } else {
            fadeGain.reset(1f)
        }
        synchronized(pauseGate) { pauseGate.notifyAll() }
        if (current == null) {
            startPipeline(startMs = synchronized(lock) { offsetMs }, autoPlay = true)
        } else {
            runCatching { current.start() }
            _snapshot.value = _snapshot.value.copy(playing = true, buffering = false)
        }
    }

    override fun pause() {
        synchronized(lock) {
            playRequested = false
            paused = true
        }
        if (FadeSettings.fadeEnabled) {
            // 淡出期间保持 line 运行，由读取线程写完渐变采样后再停。
            fadeGain.retarget(0f, SAMPLE_RATE, FadeSettings.fadeOutMs)
        } else {
            fadeGain.reset(0f)
            synchronized(lock) { line }?.let { runCatching { it.stop() } }
        }
        synchronized(pauseGate) { pauseGate.notifyAll() }
        _snapshot.value = _snapshot.value.copy(playing = false)
    }

    override fun fadeTo(target: Float, sampleRate: Int, durationMs: Int) =
        fadeGain.retarget(target, sampleRate, durationMs)

    override fun fadeReset(value: Float) = fadeGain.reset(value)

    override fun setMixFrom(source: (() -> Pair<ByteArray, Int>)?) = mixSource.set(source)

    override fun setPcmTap(tap: ((ByteArray, Int) -> Unit)?) {
        pcmTap = tap
    }

    override fun release() {
        synchronized(lock) {
            paused = false
            generation += 1
            stopPipelineLocked()
            input = null
        }
        synchronized(pauseGate) { pauseGate.notifyAll() }
        _snapshot.value = EngineSnapshot()
    }

    override fun seekTo(positionMs: Long, input: AudioInput?) {
        val target = positionMs.coerceAtLeast(0L).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
        val shouldPlay = synchronized(lock) {
            offsetMs = target
            if (input != null) this@FfmpegAudioEngine.input = input
            playRequested
        }
        startPipeline(startMs = target, autoPlay = shouldPlay, input = input)
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 2f)
    }

    override fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        if (abs(clamped - this.speed) < 0.0001f) return
        this.speed = clamped
        restartPreservingPosition()
    }

    override fun setPitch(semitones: Float) {
        val clamped = semitones.coerceIn(-12f, 12f)
        if (abs(clamped - this.pitch) < 0.0001f) return
        this.pitch = clamped
        restartPreservingPosition()
    }

    override fun setLoudness(enabled: Boolean) {
        if (this.loudness == enabled) return
        this.loudness = enabled
        restartPreservingPosition()
    }

    override fun setEqualizer(bands: List<Float>?) {
        // 旧接口语义：null = 关闭；有值 = 打开并写入十段增益。
        // 转成 DSP 模型后走同一条映射，避免两套 EQ 逻辑并存导致听感不一致。
        val current = audioEffects ?: AudioEffectsSound()
        this.audioEffects = if (bands == null) {
            null
        } else {
            current.copy(
                enabled = true,
                equalizerEnabled = true,
                graphicBandsDb = List(GRAPHIC_EQ_FREQUENCIES.size) { index ->
                    (bands.getOrNull(index) ?: 0f).toDouble()
                },
            ).normalized()
        }
        refreshEffectsPath()
    }

    override fun setAudioEffects(effects: AudioEffectsSound?) {
        val normalized = effects?.normalized()
        // 参数完全一致时不要重启解码器：SetEqualizer 类命令可能被重复下发，
        // 每来一次就重启 ffmpeg 会造成拖动滑块时的明显卡顿与爆音。
        if (normalized == audioEffects) return
        this.audioEffects = normalized
        refreshEffectsPath()
    }

    /**
     * 音效参数变更后决定「换参数」还是「重启解码器」。
     *
     * native DSP 走的是常驻处理环：参数通过 [dspParams] 手递给音频线程，下一块就生效，
     * 完全不用重启 ffmpeg（滑块拖动不再有重启造成的卡顿与爆音）。只有回退到
     * ffmpeg 滤镜方案时才必须重启——ffmpeg 的滤镜图在进程启动时固定。
     */
    private fun refreshEffectsPath() {
        val native = resolveNativeDsp()
        // 这条流水线的路径（滤镜 / native）由 startPipeline 决定并保持不变；
        // 只有可用性发生翻转（例如 native 中途失效、或首次尝试后加载成功）才重建。
        if (native != nativeDsp) {
            restartPreservingPosition()
            return
        }
        if (native) publishDspParams(audioEffects) else restartPreservingPosition()
    }

    /**
     * native 在播放中途失效（引擎创建失败 / 连续处理失败 / 参数被拒）。
     * 已经 [NativeDsp.disable]，这里只需要重建流水线让回退路径生效。
     */
    private fun onNativeDspFailed() {
        if (synchronized(lock) { input } == null) return
        runCatching { restartPreservingPosition() }
    }

    /**
     * 把参数快照交给 native DSP；返回 true 表示这台机器上 native 可用（不必重启解码器）。
     *
     * `null`（整体旁路）也把快照清空，音频线程看到 null 就完全跳过 native 引擎。
     */
    private fun publishDspParams(effects: AudioEffectsSound?): Boolean {
        if (!NativeDsp.isAvailable) {
            dspParams.set(null)
            return false
        }
        dspParams.set(effects?.toDspParams())
        return true
    }

    private fun restartPreservingPosition() {
        val current = input ?: return
        val position = currentPositionMs()
        val shouldPlay = synchronized(lock) { playRequested }
        synchronized(lock) { offsetMs = position }
        startPipeline(startMs = position, autoPlay = shouldPlay, input = current)
    }

    private fun currentPositionMs(): Long = synchronized(lock) {
        val frames = runCatching { line?.longFramePosition?.toLong() ?: 0L }.getOrDefault(0L)
        offsetMs + frames * 1000L / SAMPLE_RATE
    }

    /**
     * 决定这条解码流水线是否把音效交给 native DSP（调用方已持有 [lock]）。
     *
     * 必须同时满足「库能加载」与「引擎接受这条流的格式」：ffmpeg 的输出格式是常量
     * （48kHz/双声道/16bit），被拒说明 native 这条路走不通，整条流水线改用滤镜回退。
     */
    private fun resolveNativeDspLocked(): Boolean = resolveNativeDsp()

    /** [resolveNativeDspLocked] 的无锁版本；结论只取决于进程级状态，不需要持锁。 */
    private fun resolveNativeDsp(): Boolean =
        NativeDsp.ensureLoaded() &&
            NativeDsp.probeFormat(SAMPLE_RATE, CHANNELS, NeriDspEncoding.PCM_16BIT)

    private fun buildFilterChain(): String {
        val filters = mutableListOf<String>()
        val pitchRatio = 2.0.pow(pitch / 12.0)
        if (abs(pitchRatio - 1.0) > 1e-3) {
            filters += "asetrate=${(SAMPLE_RATE * pitchRatio).toInt()}"
            filters += "aresample=$SAMPLE_RATE"
        }
        var tempo = speed.toDouble()
        if (abs(pitchRatio - 1.0) > 1e-3) tempo /= pitchRatio
        filters += tempoChain(tempo)
        if (loudness) {
            filters += "dynaudnorm=f=250:g=15:p=0.9"
        }
        // native DSP 可用时，音效参数全部交给 native（readLoop 里逐块处理），
        // ffmpeg 这条链只留变速/变调/响度。决不能两边都挂：那就是双重 EQ + 双重增益。
        if (!nativeDsp) applyAudioEffectsToFilters(filters)
        return filters.joinToString(",")
    }

    /**
     * DSP 音效参数 → ffmpeg 滤镜（**native DSP 不可用时的回退路径**）。
     *
     * 正常情况下音效由 [NeriDspStage] 在 PCM 上逐块处理（见 `readLoop`），这里完全不参与，
     * 否则同一套参数会被处理两遍（双重 EQ + 双重增益）。
     * 只有 `lib_neri.so` 加载失败 / 本机架构不符 / 引擎配置失败时，[buildFilterChain] 才会
     * 调到这里，用 ffmpeg 滤镜近似；方法名保持不变，作为回退路径的稳定入口。
     *
     * ffmpeg 能力之外的参数（混响、压缩、限幅、虚拟低音、温暖度、激励器、人声）
     * 由 [AudioEffectsCapabilities] 在 native 不可用时标为未生效，这里**不生成任何滤镜**，
     * 以免出现「界面能调但听感对不上」的假象。
     *
     * 关于实时性：ffmpeg 子进程的滤镜图在启动时固定，运行期改参数必须重启进程；
     * 官方支持的运行期改参路子是 `azmq`（需要 libzmq + JVM 侧 ZMQ 客户端），
     * 本项目没有引入这两个依赖，所以保持「重启 + 界面防抖」，见 DspSettingsPanel 的提交时机。
     */
    private fun applyAudioEffectsToFilters(filters: MutableList<String>) {
        val sound = audioEffects?.normalized() ?: return
        if (!sound.enabled) return
        // 分区开关关闭时按默认值处理，与 AudioEffectsSound.effective() 语义一致
        val value = sound.effective()

        // ---------------- SPACE：声道矩阵 ----------------
        filters += spaceFilters(value)

        // ---------------- EQUALIZER ----------------
        if (value.equalizerEnabled) {
            if (value.preampDb != 0.0) {
                filters += "volume=${formatDb(value.preampDb)}dB"
            }
            value.graphicBandsDb.forEachIndexed { index, gain ->
                if (abs(gain) > 0.05) {
                    filters += "equalizer=f=${formatFreq(GRAPHIC_EQ_FREQUENCIES[index])}" +
                        ":t=q:w=1.2:g=${formatDb(gain)}"
                }
            }
            if (value.parametricEnabled) {
                for (band in value.parametricBands) {
                    if (!band.enabled) continue
                    filters += ffmpegBand(band)
                }
            }
        }

        // ---------------- TONE ----------------
        if (value.toneEnabled) {
            if (abs(value.bassDb) > 0.05) {
                filters += "bass=f=${formatFreq(value.bassFrequencyHz)}:t=q:w=0.707:g=${formatDb(value.bassDb)}"
            }
            if (abs(value.trebleDb) > 0.05) {
                filters += "treble=f=${formatFreq(value.trebleFrequencyHz)}:t=q:w=0.707:g=${formatDb(value.trebleDb)}"
            }
            // clarity 是 0~1 的力度旋钮，用峰值 EQ 近似：0 表示不挂滤镜，1 约等于 +6 dB
            if (value.clarity > 0.0) {
                filters += "equalizer=f=${formatFreq(value.clarityFrequencyHz)}" +
                    ":t=q:w=1.0:g=${formatDb(value.clarity * 6.0)}"
            }
        }

        // ---------------- DYNAMICS ----------------
        if (value.dynamicsEnabled && abs(value.outputGainDb) > 0.05) {
            filters += "volume=${formatDb(value.outputGainDb)}dB"
        }
    }

    /**
     * 空间分区里 ffmpeg 能表达的部分：单声道、左右互换、立体声宽度、低频单声道化。
     *
     * 声道矩阵只挂**一个** pan：串多个 pan 会反复重混声道，既损失精度又难预测。
     * 低频单声道化必须拆成两条支路（低通后取中置再与高通支路相加），
     * 所以它是一段带标签的子图，和前面的逗号链用 `;` 隔开。
     *
     * @return 需要追加的滤镜片段，没有任何处理时为空
     */
    private fun spaceFilters(value: AudioEffectsSound): List<String> {
        if (!value.spaceEnabled) return emptyList()
        // stereoWidth 1.0 是原样：mid/side 里侧向分量权重 w，0 = 单声道，2 = 最宽
        val width = if (value.mono) 0.0 else value.stereoWidth.coerceIn(0.0, 2.0)
        val monoBassHz = value.monoBassHz.takeIf { it >= 40.0 && !value.mono }
        val matrix = if (value.mono || abs(width - 1.0) > 0.02 || value.swapChannels) {
            // L' = a·L + b·R，R' = b·L + a·R，其中 a = (1+w)/2、b = (1-w)/2
            // w=1 退化成直通，w=0 两个声道都是中置，w=2 时 b 为负即左右相减（加宽）
            val a = (1.0 + width) / 2.0
            val b = (1.0 - width) / 2.0
            val left = if (value.swapChannels) "c1" else "c0"
            val right = if (value.swapChannels) "c0" else "c1"
            "pan=stereo|c0=%.3f*$left+%.3f*$right|c1=%.3f*$left+%.3f*$right".format(a, b, b, a)
        } else {
            null
        }
        if (matrix == null && monoBassHz == null) return emptyList()
        val out = mutableListOf("aformat=channel_layouts=stereo")
        if (matrix != null) out += matrix
        if (monoBassHz != null) {
            val hz = formatFreq(monoBassHz)
            out += "asplit=2[dsp_mb_low][dsp_mb_high]" +
                ";[dsp_mb_low]lowpass=f=$hz:w=0.707," +
                "pan=stereo|c0=0.5*c0+0.5*c1|c1=0.5*c0+0.5*c1[dsp_mb_mono]" +
                ";[dsp_mb_high]anull[dsp_mb_rest]" +
                ";[dsp_mb_mono][dsp_mb_rest]amix=inputs=2:normalize=0"
        }
        return out
    }

    /** 参数均衡段 → ffmpeg 滤镜；7 种类型逐一映射（LP/HP/带通/陷波忽略增益）。 */
    private fun ffmpegBand(band: ParametricEqBand): String {
        val frequency = formatFreq(band.frequencyHz)
        val width = ":t=q:w=" + "%.3f".format(band.q.coerceIn(0.1, 20.0))
        return when (band.type) {
            ParametricEqBandType.PEAK ->
                "equalizer=f=$frequency$width:g=${formatDb(band.gainDb)}"
            ParametricEqBandType.LOW_SHELF ->
                "bass=f=$frequency$width:g=${formatDb(band.gainDb)}"
            ParametricEqBandType.HIGH_SHELF ->
                "treble=f=$frequency$width:g=${formatDb(band.gainDb)}"
            ParametricEqBandType.LOW_PASS -> "lowpass=f=$frequency$width"
            ParametricEqBandType.HIGH_PASS -> "highpass=f=$frequency$width"
            ParametricEqBandType.NOTCH -> "bandreject=f=$frequency$width"
            ParametricEqBandType.BAND_PASS -> "bandpass=f=$frequency$width"
        }
    }

    /** ffmpeg 的频率参数要求有限正数；带小数便于精确对齐 31.25 Hz 这类中心频率。 */
    private fun formatFreq(hz: Double): String {
        val value = if (hz.isFinite()) hz.coerceIn(10.0, 20_000.0) else 1000.0
        return "%.2f".format(value)
    }

    /** 增益一律保留 1 位小数：低于 0.05 dB 的差别听不出来，却会让命令行无谓地变化。 */
    private fun formatDb(db: Double): String = "%.1f".format(if (db.isFinite()) db else 0.0)

    private fun tempoChain(tempo: Double): List<String> {
        var value = tempo.coerceIn(0.25, 4.0)
        val out = mutableListOf<String>()
        while (value > 2.0) {
            out += "atempo=2.0"
            value /= 2.0
        }
        while (value < 0.5) {
            out += "atempo=0.5"
            value /= 0.5
        }
        out += "atempo=${"%.4f".format(value)}"
        return out
    }

    private fun buildCommand(input: AudioInput, startMs: Long): List<String> {
        val command = mutableListOf(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin",
        )
        if (startMs > 0) {
            command += listOf("-ss", "%.3f".format(startMs / 1000.0))
        }
        if (input.headers.isNotEmpty()) {
            command += listOf(
                "-headers",
                input.headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" } + "\r\n",
            )
        }
        command += "-i"
        command += input.target
        command += listOf("-vn", "-sn", "-dn")
        val filters = buildFilterChain()
        if (filters.isNotEmpty()) {
            command += listOf("-af", filters)
        }
        command += listOf("-f", "s16le", "-ac", CHANNELS.toString(), "-ar", SAMPLE_RATE.toString(), "-")
        return command
    }

    private fun startPipeline(startMs: Long, autoPlay: Boolean, input: AudioInput? = null) {
        val target = input ?: synchronized(lock) { this.input } ?: return
        val myGeneration: Int
        synchronized(lock) {
            stopPipelineLocked()
            generation += 1
            myGeneration = generation
            offsetMs = startMs.coerceAtLeast(0L)
            playRequested = autoPlay || playRequested
            // 音效走哪条路必须在建 ffmpeg 命令行**之前**定好：滤镜链与 native 处理环
            // 是二选一，同一条流水线上不允许两边都生效。
            nativeDsp = resolveNativeDspLocked()
            // 新流水线要从当前参数起步：native 首次加载可能发生在 setAudioEffects 之后，
            // 那时快照还是空的，这里补一次，避免"接了 DSP 但参数没送到"。
            if (nativeDsp) publishDspParams(audioEffects)
        }
        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, CHANNELS, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val newLine = runCatching {
            (AudioSystem.getLine(info) as SourceDataLine).apply {
                open(format, SAMPLE_RATE * BYTES_PER_FRAME / 25)  // ~40ms 缓冲：音量/淡入淡出即时生效
            }
        }.getOrElse { error ->
            val message = "无法打开音频输出设备：${error.message ?: error.javaClass.simpleName}"
            _snapshot.value = _snapshot.value.copy(playing = false, buffering = false, error = message)
            onError?.invoke(message)
            return
        }
        val newProcess = runCatching {
            ProcessBuilder(buildCommand(target, startMs)).start()
        }.getOrElse { error ->
            runCatching { newLine.close() }
            val message = "无法启动解码器：${error.message ?: error.javaClass.simpleName}"
            _snapshot.value = _snapshot.value.copy(playing = false, buffering = false, error = message)
            onError?.invoke(message)
            return
        }

        synchronized(lock) {
            line = newLine
            process = newProcess
            _snapshot.value = _snapshot.value.copy(
                positionMs = startMs,
                durationMs = durationMs,
                buffering = true,
                playing = false,
                error = null,
            )
        }

        val shouldPlayNow = synchronized(lock) { playRequested }
        if (shouldPlayNow) {
            runCatching { newLine.start() }
            _snapshot.value = _snapshot.value.copy(playing = true, buffering = false)
        }

        // native 处理环绑定这条解码流水线：换一条流水线就建新的引擎（JNI 句柄不能跨线程复用）
        val dspStage = if (nativeDsp) {
            NeriDspStage(
                sampleRate = SAMPLE_RATE,
                channelCount = CHANNELS,
                encoding = NeriDspEncoding.PCM_16BIT,
                params = dspParams,
                maxBlockBytes = READ_BLOCK_BYTES,
            )
        } else {
            null
        }

        val reader = Thread({ readLoop(myGeneration, newProcess, newLine, startMs, dspStage) }, "neri-audio-reader")
        reader.isDaemon = true
        readerThread = reader
        reader.start()

        if (tickerThread == null) {
            val ticker = Thread({ tickLoop() }, "neri-audio-ticker")
            ticker.isDaemon = true
            tickerThread = ticker
            ticker.start()
        }
    }

    private fun readLoop(
        myGeneration: Int,
        process: Process,
        line: SourceDataLine,
        startMs: Long,
        dspStage: NeriDspStage?,
    ) {
        val inputStream: InputStream = BufferedInputStream(process.inputStream, 1 shl 16)
        val buffer = ByteArray(READ_BLOCK_BYTES)
        var finishedNormally = false
        try {
            while (true) {
                if (synchronized(lock) { generation != myGeneration || restarting }) break
                // 暂停时等淡出写完再阻塞读取线程（避免读到文件尾误判为播放结束）。
                if (!awaitPauseGate(myGeneration, line)) break
                if (synchronized(lock) { generation != myGeneration || restarting }) break
                val read = inputStream.read(buffer)
                if (read < 0) {
                    // 处于暂停状态读到结尾时不立即切歌，等恢复播放后再结算
                    if (!awaitPauseGate(myGeneration, line)) return
                    if (synchronized(lock) { generation != myGeneration }) return
                    finishedNormally = true
                    break
                }
                if (read == 0) continue
                // DSP 必须在 applyVolume 之前：压缩器/限幅器的阈值按 dBFS 判定，对绝对电平敏感。
                // 放在音量之后的话，用户把音量拉到 20% 就等于让压缩器彻底失效。
                if (dspStage != null && !dspStage.process(buffer, read)) {
                    // native 在这条流上失效了，而这条流水线没有挂滤镜 —— 重建一次，
                    // 让新流水线走 applyAudioEffectsToFilters 的回退路径，别让声音变成未处理。
                    onNativeDspFailed()
                    return
                }
                applyVolume(buffer, read)
                var written = 0
                while (written < read) {
                    if (synchronized(lock) { generation != myGeneration }) return
                    val count = line.write(buffer, written, read - written)
                    if (count <= 0) break
                    written += count
                }
            }
        } catch (_: Throwable) {
            // 管道被主动关闭时忽略
        } finally {
            // JNI 句柄必须在本线程销毁（引擎不保证跨线程安全），跟随读线程一起收尾
            dspStage?.close()
            runCatching { inputStream.close() }
            val stillCurrent = synchronized(lock) { generation == myGeneration }
            if (finishedNormally && stillCurrent) {
                runCatching { line.drain() }
                runCatching { line.stop() }
                _snapshot.value = _snapshot.value.copy(
                    playing = false,
                    buffering = false,
                    positionMs = durationMs.takeIf { it > 0 } ?: currentPositionMs(),
                )
                val errorText = runCatching { process.errorStream.readBytes().decodeToString().trim() }.getOrDefault("")
                if (errorText.isNotBlank() && _snapshot.value.durationMs == 0L && offsetMs == 0L) {
                    _snapshot.value = _snapshot.value.copy(error = errorText.take(300))
                    onError?.invoke(errorText.take(300))
                } else {
                    onCompleted?.invoke()
                }
            }
        }
    }

    private fun applyVolume(buffer: ByteArray, length: Int) {
        val frames = length / BYTES_PER_FRAME
        val gain = volume * fadeGain.gainAndAdvance(frames)
        mixSource.drain().let { (other, otherLength) -> mixPcm(buffer, length, other, otherLength, BYTES_PER_FRAME) }
        if (abs(gain - 1f) >= 0.001f) scalePcm(buffer, length, gain, BYTES_PER_FRAME)
        // pcmTap 必须在缩放之外调用：原来 gain≈1 时提前 return 会跳过 tap，
        // 交叉淡入在音量接近 1 时就没有旧曲 PCM 可混，表现为淡入失效。
        pcmTap?.invoke(buffer, length)
    }

    private fun tickLoop() {
        while (true) {
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                return
            }
            val active = synchronized(lock) { line != null }
            if (!active) continue
            val position = currentPositionMs()
            val playingNow = synchronized(lock) { playRequested }
            _snapshot.value = _snapshot.value.copy(
                positionMs = if (playingNow) position else _snapshot.value.positionMs,
                playing = playingNow,
            )
        }
    }

    /**
     * 暂停时先让主循环继续消费 PCM 直到淡出增益归零（增益在 applyVolume 中推进），
     * 之后停 line 并阻塞等待恢复；返回 false 表示 generation 已失效，调用方应退出循环。
     */
    private fun awaitPauseGate(myGeneration: Int, line: SourceDataLine): Boolean {
        if (paused && fadeGain.gainAndAdvance(0) <= 0f) {
            runCatching { line.stop() }
            synchronized(pauseGate) {
                while (paused && synchronized(lock) { generation == myGeneration }) {
                    runCatching { pauseGate.wait(200L) }
                }
            }
        }
        return synchronized(lock) { generation == myGeneration }
    }

    private fun stopPipelineLocked() {
        restarting = true
        runCatching { process?.destroy() }
        process = null
        runCatching { line?.stop() }
        runCatching { line?.close() }
        line = null
        restarting = false
        synchronized(pauseGate) { pauseGate.notifyAll() }
    }
}

/** 回退引擎：纯 Java Sound（mp3 / flac / ogg / wav / aiff），不支持变速与均衡器。 */
class JavaSoundAudioEngine : AudioEngine {

    override val supportsEffects: Boolean = false

    private val _snapshot = MutableStateFlow(EngineSnapshot())
    override val snapshot: StateFlow<EngineSnapshot> = _snapshot.asStateFlow()

    override var onCompleted: (() -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    private val lock = Any()
    private var line: SourceDataLine? = null
    private var thread: Thread? = null
    private var input: AudioInput? = null
    private var offsetMs = 0L
    private var durationMs = 0L
    @Volatile private var volume = 0.85f
    @Volatile private var frameBytes = 4
    @Volatile private var sampleRate = SAMPLE_RATE
    private var playRequested = false
    private var generation = 0
    private val closing = AtomicBoolean(false)
    private val pauseGate = Object()
    private val fadeGain = FadeGain()
    private val mixSource = MixSource()
    @Volatile private var pcmTap: ((ByteArray, Int) -> Unit)? = null

    @Volatile
    private var paused = false

    override fun open(input: AudioInput, startMs: Long, durationMs: Long) {
        synchronized(lock) {
            closeLocked()
            this.input = input
            offsetMs = startMs
            this.durationMs = durationMs
            generation += 1
            playRequested = false
            paused = false
            fadeGain.reset(1f)
            mixSource.set(null)
        }
        synchronized(pauseGate) { pauseGate.notifyAll() }
        _snapshot.value = EngineSnapshot(positionMs = startMs, durationMs = durationMs, buffering = true)
    }

    override fun prepare(durationMs: Long) {
        synchronized(lock) {
            offsetMs = 0L
            this.durationMs = durationMs
        }
        _snapshot.value = EngineSnapshot(
            positionMs = 0L,
            durationMs = durationMs,
            playing = false,
            buffering = true,
        )
    }

    override fun play() {
        val shouldStart = synchronized(lock) {
            playRequested = true
            paused = false
            thread == null
        }
        if (FadeSettings.fadeEnabled) {
            fadeGain.reset(0f)
            fadeGain.retarget(1f, sampleRate, FadeSettings.fadeInMs)
        } else {
            fadeGain.reset(1f)
        }
        synchronized(pauseGate) { pauseGate.notifyAll() }
        if (shouldStart) {
            startStream()
        } else {
            synchronized(lock) { line }?.let { runCatching { it.start() } }
            _snapshot.value = _snapshot.value.copy(playing = true, buffering = false)
        }
    }

    override fun pause() {
        synchronized(lock) {
            playRequested = false
            paused = true
        }
        if (FadeSettings.fadeEnabled) {
            fadeGain.retarget(0f, sampleRate, FadeSettings.fadeOutMs)
        } else {
            fadeGain.reset(0f)
            synchronized(lock) { line }?.let { runCatching { it.stop() } }
        }
        synchronized(pauseGate) { pauseGate.notifyAll() }
        _snapshot.value = _snapshot.value.copy(playing = false)
    }

    override fun fadeTo(target: Float, sampleRate: Int, durationMs: Int) =
        fadeGain.retarget(target, sampleRate, durationMs)

    override fun fadeReset(value: Float) = fadeGain.reset(value)

    override fun setMixFrom(source: (() -> Pair<ByteArray, Int>)?) = mixSource.set(source)

    override fun setPcmTap(tap: ((ByteArray, Int) -> Unit)?) {
        pcmTap = tap
    }

    override fun release() {
        synchronized(lock) {
            paused = false
            closeLocked()
            input = null
            generation += 1
        }
        synchronized(pauseGate) { pauseGate.notifyAll() }
        _snapshot.value = EngineSnapshot()
    }

    override fun seekTo(positionMs: Long, input: AudioInput?) {
        synchronized(lock) {
            offsetMs = positionMs.coerceAtLeast(0L)
            if (input != null) this@JavaSoundAudioEngine.input = input
            generation += 1
            closeLocked()
        }
        if (synchronized(lock) { playRequested }) startStream()
        _snapshot.value = _snapshot.value.copy(positionMs = positionMs)
    }

    override fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(0f, 2f)
    }

    override fun setSpeed(speed: Float) = Unit

    override fun setPitch(semitones: Float) = Unit

    override fun setLoudness(enabled: Boolean) = Unit

    override fun setEqualizer(bands: List<Float>?) = Unit

    private fun openStream(target: AudioInput): javax.sound.sampled.AudioInputStream? = runCatching {
        if (target.path != null) {
            AudioSystem.getAudioInputStream(File(target.path))
        } else {
            AudioSystem.getAudioInputStream(java.net.URL(target.url))
        }
    }.getOrNull()

    private fun startStream() {
        val target = synchronized(lock) { input } ?: return
        val myGeneration = synchronized(lock) { generation }
        val skipMs = synchronized(lock) { offsetMs }
        val raw = openStream(target)
        if (raw == null) {
            val message = "无法解码该音频文件（Java Sound 回退引擎不支持该格式）"
            _snapshot.value = _snapshot.value.copy(error = message, buffering = false)
            onError?.invoke(message)
            return
        }
        val decoded = runCatching { AudioSystem.getAudioInputStream(AudioFormat.Encoding.PCM_SIGNED, raw) }
            .getOrElse { raw }
        val format = decoded.format
        frameBytes = (format.sampleSizeInBits / 8).coerceAtLeast(1) * format.channels.coerceAtLeast(1)
        sampleRate = format.frameRate.toInt().coerceAtLeast(1)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val newLine = runCatching { (AudioSystem.getLine(info) as SourceDataLine).apply {
            open(format, (sampleRate * frameBytes.toLong() / 25).toInt().coerceAtLeast(1024))  // ~40ms
        } }
            .getOrElse { error ->
                val message = "无法打开音频输出设备：${error.message}"
                _snapshot.value = _snapshot.value.copy(error = message, buffering = false)
                onError?.invoke(message)
                return
            }
        if (skipMs > 0) {
            val bytesToSkip = skipMs * format.frameSize * format.frameRate.toLong() / 1000L
            var remaining = bytesToSkip
            while (remaining > 0) {
                val skipped = runCatching { decoded.skip(remaining) }.getOrDefault(0L)
                if (skipped <= 0) break
                remaining -= skipped
            }
        }
        synchronized(lock) {
            line = newLine
            _snapshot.value = _snapshot.value.copy(buffering = true, playing = false, error = null)
        }
        if (synchronized(lock) { playRequested }) {
            runCatching { newLine.start() }
            _snapshot.value = _snapshot.value.copy(playing = true, buffering = false)
        }
        val worker = Thread({
            val buffer = ByteArray(1 shl 15)
            var finished = false
            try {
                while (true) {
                    if (synchronized(lock) { generation != myGeneration }) return@Thread
                    if (!awaitPauseGate(myGeneration, newLine)) return@Thread
                    if (synchronized(lock) { generation != myGeneration }) return@Thread
                    val read = decoded.read(buffer)
                    if (read < 0) {
                        if (!awaitPauseGate(myGeneration, newLine)) return@Thread
                        if (synchronized(lock) { generation != myGeneration }) return@Thread
                        finished = true
                        break
                    }
                    applyVolume(buffer, read)
                    var written = 0
                    while (written < read) {
                        val count = newLine.write(buffer, written, read - written)
                        if (count <= 0) break
                        written += count
                    }
                }
            } catch (_: Throwable) {
                // 关闭时忽略
            } finally {
                runCatching { decoded.close() }
                if (finished && synchronized(lock) { generation == myGeneration }) {
                    runCatching { newLine.drain() }
                    runCatching { newLine.stop() }
                    _snapshot.value = _snapshot.value.copy(playing = false, buffering = false)
                    onCompleted?.invoke()
                }
            }
        }, "neri-javasound-reader")
        worker.isDaemon = true
        synchronized(lock) { thread = worker }
        worker.start()
    }

    private fun applyVolume(buffer: ByteArray, length: Int) {
        val frames = length / frameBytes
        val gain = volume * fadeGain.gainAndAdvance(frames)
        mixSource.drain().let { (other, otherLength) -> mixPcm(buffer, length, other, otherLength, frameBytes) }
        if (abs(gain - 1f) >= 0.001f) scalePcm(buffer, length, gain, frameBytes)
        // 同 FfmpegAudioEngine：tap 不能跟着提前 return，否则音量≈1 时交叉淡入拿不到旧曲 PCM
        pcmTap?.invoke(buffer, length)
    }

    /** 暂停时等待淡出完成并停 line，随后阻塞直到恢复播放或释放引擎。 */
    /**
     * 暂停时先让主循环继续消费 PCM 直到淡出增益归零（增益在 applyVolume 中推进），
     * 之后停 line 并阻塞等待恢复；返回 false 表示 generation 已失效，调用方应退出循环。
     */
    private fun awaitPauseGate(myGeneration: Int, line: SourceDataLine): Boolean {
        if (paused && fadeGain.gainAndAdvance(0) <= 0f) {
            runCatching { line.stop() }
            synchronized(pauseGate) {
                while (paused && synchronized(lock) { generation == myGeneration }) {
                    runCatching { pauseGate.wait(200L) }
                }
            }
        }
        return synchronized(lock) { generation == myGeneration }
    }

    private fun closeLocked() {
        runCatching { line?.stop() }
        runCatching { line?.close() }
        line = null
        thread = null
        synchronized(pauseGate) { pauseGate.notifyAll() }
    }
}

/**
 * 淡入淡出参数的进程内镜像。PlayerManager 更新 AppSettings 时同步到这里，
 * 引擎无需持有 SettingsRepository；@Volatile 保证命令线程与 PCM 写入线程的可见性。
 */
object FadeSettings {
    @Volatile var fadeEnabled: Boolean = true
    @Volatile var fadeInMs: Int = 300
    @Volatile var fadeOutMs: Int = 300
    @Volatile var crossfadeEnabled: Boolean = false
    @Volatile var crossfadeMs: Int = 2000

    fun apply(enabled: Boolean, inMs: Int, outMs: Int, crossEnabled: Boolean, crossMs: Int) {
        fadeEnabled = enabled
        fadeInMs = inMs.coerceAtLeast(0)
        fadeOutMs = outMs.coerceAtLeast(0)
        crossfadeEnabled = crossEnabled
        crossfadeMs = crossMs.coerceAtLeast(0)
    }
}

/** 交叉淡入时旧曲 PCM 的提供者；新曲每写入一批前取走并叠加。 */
private class MixSource {
    private val provider = AtomicReference<(() -> Pair<ByteArray, Int>)?>(null)

    fun set(source: (() -> Pair<ByteArray, Int>)?) = provider.set(source)

    fun drain(): Pair<ByteArray, Int> = provider.get()?.invoke() ?: (EMPTY to 0)

    companion object {
        private val EMPTY = ByteArray(0)
    }
}

/** 交叉淡入时保存旧曲最近一批输出 PCM 的槽位，供新曲读取并叠加。 */
class PcmTapSlot {
    private val ref = AtomicReference<Pair<ByteArray, Int>>(ByteArray(0) to 0)

    fun publish(buffer: ByteArray, length: Int) {
        ref.set(buffer.copyOf(length) to length)
    }

    fun drain(): Pair<ByteArray, Int> = ref.getAndSet(ByteArray(0) to 0)

    fun clear() {
        ref.set(ByteArray(0) to 0)
    }
}

/** 16bit PCM 原地缩放；frameBytes 为每帧字节数（采样宽度 × 声道数）。 */
private fun scalePcm(buffer: ByteArray, length: Int, gain: Float, frameBytes: Int) {
    if (gain == 1f) return
    if (gain <= 0f) { java.util.Arrays.fill(buffer, 0, length, 0.toByte()); return }
    // 逐 16bit 采样处理：一帧可能是立体声（4 字节 = L2+R2），此前只处理帧内前 2 字节，
    // 导致右声道不受音量/淡入淡出影响（滑到最低仍有声音）。
    var index = 0
    while (index + 1 < length) {
        val sample = ((buffer[index].toInt() and 0xFF) or (buffer[index + 1].toInt() shl 8)).toShort()
        val scaled = (sample * gain).toInt().coerceIn(-32768, 32767)
        buffer[index] = (scaled and 0xFF).toByte()
        buffer[index + 1] = ((scaled shr 8) and 0xFF).toByte()
        index += 2
    }
}

/** 16bit PCM 叠加：按较短的一侧混合，防止越界。同样逐声道处理，避免右声道漏混。 */
private fun mixPcm(target: ByteArray, targetLength: Int, source: ByteArray, sourceLength: Int, frameBytes: Int) {
    val limit = minOf(targetLength, sourceLength)
    var index = 0
    while (index + 1 < limit) {
        val a = ((target[index].toInt() and 0xFF) or (target[index + 1].toInt() shl 8)).toShort()
        val b = ((source[index].toInt() and 0xFF) or (source[index + 1].toInt() shl 8)).toShort()
        val mixed = (a.toInt() + b.toInt()).coerceIn(-32768, 32767)
        target[index] = (mixed and 0xFF).toByte()
        target[index + 1] = ((mixed shr 8) and 0xFF).toByte()
        index += 2
    }
}

fun createAudioEngine(): AudioEngine =
    if (FfmpegSupport.available) FfmpegAudioEngine() else JavaSoundAudioEngine()
