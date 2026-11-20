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
    fun seekTo(positionMs: Long)
    fun setVolume(volume: Float)
    fun setSpeed(speed: Float)
    fun setPitch(semitones: Float)
    fun setLoudness(enabled: Boolean)
    fun setEqualizer(bands: List<Float>?)

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
private const val EQ_FREQUENCIES = "31,62,125,250,500,1000,2000,4000,8000,16000"

private val EQ_FREQ_LIST = EQ_FREQUENCIES.split(',').map { it.trim() }

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
    private var speed: Float = 1.0f
    private var pitch: Float = 0.0f
    private var loudness: Boolean = false
    private var equalizer: List<Float>? = null
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

    override fun seekTo(positionMs: Long) {
        val target = positionMs.coerceAtLeast(0L).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
        val shouldPlay = synchronized(lock) {
            offsetMs = target
            playRequested
        }
        startPipeline(startMs = target, autoPlay = shouldPlay)
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
        this.equalizer = bands
        restartPreservingPosition()
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
        val bands = equalizer
        if (bands != null) {
            bands.take(EQ_FREQ_LIST.size).forEachIndexed { index, gain ->
                if (abs(gain) > 0.05f) {
                    filters += "equalizer=f=${EQ_FREQ_LIST[index]}:t=q:w=1.2:g=${"%.1f".format(gain)}"
                }
            }
        }
        return filters.joinToString(",")
    }

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
        }
        val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, CHANNELS, true, false)
        val info = DataLine.Info(SourceDataLine::class.java, format)
        val newLine = runCatching {
            (AudioSystem.getLine(info) as SourceDataLine).apply {
                open(format, SAMPLE_RATE * BYTES_PER_FRAME)
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

        val reader = Thread({ readLoop(myGeneration, newProcess, newLine, startMs) }, "neri-audio-reader")
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

    private fun readLoop(myGeneration: Int, process: Process, line: SourceDataLine, startMs: Long) {
        val inputStream: InputStream = BufferedInputStream(process.inputStream, 1 shl 16)
        val buffer = ByteArray(1 shl 15)
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
        if (abs(gain - 1f) < 0.001f) return
        scalePcm(buffer, length, gain, BYTES_PER_FRAME)
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
    private var volume = 0.85f
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

    override fun seekTo(positionMs: Long) {
        synchronized(lock) {
            offsetMs = positionMs.coerceAtLeast(0L)
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
        val newLine = runCatching { (AudioSystem.getLine(info) as SourceDataLine).apply { open(format) } }
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
        if (abs(gain - 1f) < 0.001f) return
        scalePcm(buffer, length, gain, frameBytes)
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
    var index = 0
    val step = frameBytes.coerceAtLeast(2)
    while (index + 1 < length) {
        val sample = ((buffer[index].toInt() and 0xFF) or (buffer[index + 1].toInt() shl 8)).toShort()
        val scaled = (sample * gain).toInt().coerceIn(-32768, 32767)
        buffer[index] = (scaled and 0xFF).toByte()
        buffer[index + 1] = ((scaled shr 8) and 0xFF).toByte()
        index += step
    }
}

/** 16bit PCM 叠加：按较短的一侧混合，防止越界。 */
private fun mixPcm(target: ByteArray, targetLength: Int, source: ByteArray, sourceLength: Int, frameBytes: Int) {
    val step = frameBytes.coerceAtLeast(2)
    val limit = (minOf(targetLength, sourceLength) / step) * step
    var index = 0
    while (index + 1 < limit) {
        val a = ((target[index].toInt() and 0xFF) or (target[index + 1].toInt() shl 8)).toShort()
        val b = ((source[index].toInt() and 0xFF) or (source[index + 1].toInt() shl 8)).toShort()
        val mixed = (a.toInt() + b.toInt()).coerceIn(-32768, 32767)
        target[index] = (mixed and 0xFF).toByte()
        target[index + 1] = ((mixed shr 8) and 0xFF).toByte()
        index += step
    }
}

fun createAudioEngine(): AudioEngine =
    if (FfmpegSupport.available) FfmpegAudioEngine() else JavaSoundAudioEngine()
