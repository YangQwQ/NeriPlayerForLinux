package moe.ouom.neriplayer.desktop.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.desktop.net.OnlineRepository
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

/** 网易云不可播、自动切到哔哩哔哩成功后的提示。 */
private const val NETEASE_AUTO_SOURCE_FALLBACK_MESSAGE =
    "网易云音源不可用，已自动切换到哔哩哔哩音源"

data class SleepTimerState(
    val active: Boolean = false,
    val stopAfterCurrent: Boolean = false,
    val remainingSeconds: Long = 0L,
    val totalSeconds: Long = 0L,
)

/** 播放器核心：队列、播放控制、歌词、统计与音效。 */
class PlayerManager(
    private val settings: SettingsRepository,
    private val history: HistoryRepository,
    private val stats: StatsRepository,
    private val lyricsRepository: LyricsRepository,
    private val online: OnlineRepository,
    private val scope: CoroutineScope,
    private val downloads: DownloadCatalog,
) {

    private val _activeEngine = MutableStateFlow(createAudioEngine())
    /** 当前输出引擎；交叉淡入时会临时切换到新引擎。 */
    val engine: AudioEngine get() = _activeEngine.value

    /** 交叉淡入期间仍在淡出的旧引擎；结束后释放。 */
    @Volatile private var outgoingEngine: AudioEngine? = null
    private var crossfadeJob: Job? = null
    private var crossfadeGeneration = 0

    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _lyrics = MutableStateFlow(Lyrics())
    val lyrics: StateFlow<Lyrics> = _lyrics.asStateFlow()

    private val _lyricsLoading = MutableStateFlow(false)
    val lyricsLoading: StateFlow<Boolean> = _lyricsLoading.asStateFlow()

    private val _coverSeedColor = MutableStateFlow<String?>(null)
    val coverSeedColor: StateFlow<String?> = _coverSeedColor.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _volume = MutableStateFlow(settings.current.volume)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _speed = MutableStateFlow(settings.current.playbackSpeed)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    private val _pitch = MutableStateFlow(settings.current.pitchSemitone)
    val pitch: StateFlow<Float> = _pitch.asStateFlow()

    private val _loudness = MutableStateFlow(settings.current.loudnessEnhancer)
    val loudness: StateFlow<Boolean> = _loudness.asStateFlow()

    private val _equalizerEnabled = MutableStateFlow(settings.current.equalizerEnabled)
    val equalizerEnabled: StateFlow<Boolean> = _equalizerEnabled.asStateFlow()

    private val _equalizerBands = MutableStateFlow(settings.current.equalizerBands)
    val equalizerBands: StateFlow<List<Float>> = _equalizerBands.asStateFlow()

    /**
     * DSP 音效参数（唯一权威来源）。旧 `equalizerEnabled/equalizerBands` 是它的视图：
     * 十段图形均衡的增益，供旧的均衡器界面继续读取。
     *
     * 初始化时若音效文件从没保存过，就用 AppSettings 里的旧均衡器设置迁移一份，
     * 老用户升级后听感不变；保存过一次之后旧值不再覆盖。
     */
    private val _audioEffects = MutableStateFlow(
        AudioEffectsStore.currentOrMigrate(
            LegacyEqualizer(
                enabled = settings.current.equalizerEnabled,
                preset = settings.current.equalizerPreset,
                bands = settings.current.equalizerBands,
            ),
        ),
    )
    val audioEffects: StateFlow<AudioEffectsSound> = _audioEffects.asStateFlow()

    /**
     * 音效参数上次落盘的时间戳；0 表示从没保存过（此时启动会走旧均衡器迁移）。
     * 放在内存里而不是每次回包都读一遍配置文件：滑块每次提交都会取快照，
     * 每次都做文件 IO 既慢又没必要。
     */
    @Volatile
    var audioEffectsUpdatedAt: Long = AudioEffectsStore.load().updatedAt
        private set

    private val _sleepTimer = MutableStateFlow(SleepTimerState())
    val sleepTimer: StateFlow<SleepTimerState> = _sleepTimer.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages

    private val _buffering = MutableStateFlow(false)
    val buffering: StateFlow<Boolean> = _buffering.asStateFlow()

    private var shuffleOrder: List<Int> = emptyList()
    private var currentPlayingKey: String? = null
    private var playbackStartedAt = 0L
    private var sleepTimerJob: Job? = null
    private val resolveMutex = Mutex()
    private val resolvedCache = ConcurrentHashMap<String, AudioInput>()

    /**
     * 下一首的内存音频缓存：播放稳定后后台把下一首整首下载到 /dev/shm（内存文件系统，
     * 不落磁盘）的单个固定文件，切到下一首时直接按本地文件读，无联网等待。
     * 只保留一首：每次预取前覆盖旧文件。/dev/shm 不可用则回退普通缓存目录。
     */
    private val audioCacheDir: File by lazy {
        // /dev/shm 本身存在且可写时用它（内存文件系统），否则回退普通缓存目录
        val shmRoot = File("/dev/shm")
        val dir = if (shmRoot.exists() && shmRoot.canWrite()) File(shmRoot, "NeriPlayer/audio")
                  else File(AppDirs.cacheDir, "audio")
        dir.apply { mkdirs() }
        // 清理旧版本遗留的按 key 命名的缓存文件（新版只用 current.audio 单文件）
        runCatching { dir.listFiles()?.filter { it.name != "current.audio" && it.name != "current.audio.tmp" }?.forEach { it.delete() } }
        dir
    }
    /** 当前预取缓存对应的歌曲 key；null 表示没有缓存。 */
    @Volatile private var audioCachedKey: String? = null
    private val audioCacheFile: File get() = File(audioCacheDir, "current.audio")

    /** 后台流式下载指定歌曲到唯一的内存缓存文件；成功记录 audioCachedKey。 */
    private suspend fun prefetchAudio(song: Song, input: AudioInput) {
        val url = input.url ?: return
        val ok = withContext(Dispatchers.IO) {
            val tmp = File(audioCacheDir, "current.audio.tmp")
            audioCachedKey = null   // 先作废旧缓存，避免切歌时读到半写文件
            // 流式下载，避免整首歌以 ByteArray 驻留堆内存；失败时工具方法会删除半成品
            if (!online.httpService.downloadToFile(url, input.headers, tmp) { _, _ -> }) {
                false
            } else {
                runCatching {
                    if (audioCacheFile.exists()) audioCacheFile.delete()
                    tmp.renameTo(audioCacheFile)
                    audioCacheFile.length() > 0L
                }.getOrDefault(false)
            }
        }
        audioCachedKey = if (ok) song.key else null
    }
    private val seedCache = ConcurrentHashMap<String, String>()

    /** 网易云不可播时的哔哩哔哩兜底：缓存、提示去重与引擎侧失败重试都在内部处理。 */
    private val neteaseAutoSource = NeteaseAutoSource(online)

    val supportsEffects: Boolean get() = engine.supportsEffects

    init {
        val saved = settings.current
        FadeSettings.apply(
            saved.fadeEnabled,
            saved.fadeInMs,
            saved.fadeOutMs,
            saved.crossfadeEnabled,
            saved.crossfadeMs,
        )
        bindEngineCallbacks(engine)
        engine.setVolume(_volume.value)
        if (engine.supportsEffects) {
            engine.setSpeed(_speed.value)
            engine.setPitch(_pitch.value)
            engine.setLoudness(_loudness.value)
        }
        // 边播放边累计统计，保证统计页面实时更新
        scope.launch {
            while (true) {
                delay(10_000)
                if (_state.value == PlaybackState.PLAYING) {
                    flushListeningTime()
                }
            }
        }
    }

    fun attachSnapshotFlow() {
        scope.launch {
            _activeEngine.collectLatest { target ->
                target.snapshot.collect { snapshot ->
                    _buffering.value = snapshot.buffering
                    if (snapshot.playing != (_state.value == PlaybackState.PLAYING)) {
                        _state.value = when {
                            snapshot.error != null -> PlaybackState.ERROR
                            snapshot.playing -> PlaybackState.PLAYING
                            _state.value == PlaybackState.PLAYING -> PlaybackState.PAUSED
                            else -> _state.value
                        }
                    }
                }
            }
        }
    }

    /** 引擎快照流：切换引擎后自动跟随新的 snapshot。 */
    private val engineSnapshots: Flow<EngineSnapshot> = _activeEngine.flatMapLatest { it.snapshot }

    val positionMs: StateFlow<Long> = derivedState(engineSnapshots) { it.positionMs }
    val durationMs: StateFlow<Long> = derivedState(engineSnapshots) { it.durationMs }

    private fun bindEngineCallbacks(target: AudioEngine) {
        target.onCompleted = { scope.launch { handleTrackCompleted() } }
        target.onError = { message ->
            _state.value = PlaybackState.ERROR
            _buffering.value = false
            scope.launch { _messages.emit(message) }
            maybeRetryWithAutoSource()
        }
        target.setVolume(_volume.value)
        if (target.supportsEffects) {
            target.setSpeed(_speed.value)
            target.setPitch(_pitch.value)
            target.setLoudness(_loudness.value)
            target.setAudioEffects(_audioEffects.value)
        }
    }

    /** 切到新引擎并同步应用当前设置；旧引擎由调用方决定是否继续淡出。 */
    private fun switchEngine(next: AudioEngine) {
        _activeEngine.value = next
        bindEngineCallbacks(next)
    }

    private fun <T> derivedState(
        source: Flow<EngineSnapshot>,
        selector: (EngineSnapshot) -> T,
    ): StateFlow<T> {
        val holder = MutableStateFlow(selector(engine.snapshot.value))
        scope.launch {
            source.collectLatest { holder.value = selector(it) }
        }
        return holder.asStateFlow()
    }

    // ---------------- 队列与播放控制 ----------------

    fun setQueue(songs: List<Song>, startIndex: Int, autoPlay: Boolean = true) {
        if (songs.isEmpty()) {
            clearQueue()
            return
        }
        _queue.value = songs
        rebuildShuffleOrder(startIndex.coerceIn(0, songs.lastIndex))
        _currentIndex.value = startIndex.coerceIn(0, songs.lastIndex)
        if (autoPlay) {
            playCurrent()
        } else {
            _currentSong.value = songs[_currentIndex.value]
        }
    }

    fun playSongNow(song: Song, queue: List<Song>? = null) {
        val targetQueue = (queue ?: _queue.value).ifEmpty { listOf(song) }
        val index = targetQueue.indexOfFirst { it.key == song.key }.takeIf { it >= 0 } ?: 0
        setQueue(targetQueue, index, autoPlay = true)
    }

    fun enqueueNext(songs: List<Song>) {
        if (songs.isEmpty()) return
        val current = _currentSong.value
        val list = _queue.value.toMutableList()
        if (current == null) {
            setQueue(songs, 0, autoPlay = true)
            return
        }
        val insertAt = (_currentIndex.value + 1).coerceIn(0, list.size)
        list.addAll(insertAt, songs)
        _queue.value = list
        rebuildShuffleOrder(_currentIndex.value)
        scope.launch { _messages.emit("已添加到下一首播放") }
    }

    fun enqueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        if (_currentSong.value == null) {
            setQueue(songs, 0, autoPlay = true)
            return
        }
        _queue.value = _queue.value + songs
        rebuildShuffleOrder(_currentIndex.value)
        scope.launch { _messages.emit("已添加到播放队列") }
    }

    fun removeFromQueue(index: Int) {
        val list = _queue.value.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        _queue.value = list
        when {
            list.isEmpty() -> clearQueue()
            index < _currentIndex.value -> _currentIndex.value -= 1
            index == _currentIndex.value -> playCurrent()
        }
        rebuildShuffleOrder(_currentIndex.value)
    }

    fun moveInQueue(from: Int, to: Int) {
        val list = _queue.value.toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val playing = _currentSong.value
        val item = list.removeAt(from)
        list.add(to, item)
        _queue.value = list
        _currentIndex.value = playing?.let { song -> list.indexOfFirst { it.key == song.key } } ?: -1
        rebuildShuffleOrder(_currentIndex.value)
    }

    fun clearQueue() {
        stopPlaybackInternal()
        _queue.value = emptyList()
        _currentIndex.value = -1
        _currentSong.value = null
        _state.value = PlaybackState.IDLE
        _lyrics.value = Lyrics()
    }

    fun play() {
        if (_currentSong.value == null) {
            val queue = _queue.value
            if (queue.isNotEmpty()) {
                _currentIndex.value = _currentIndex.value.coerceIn(0, queue.lastIndex)
                playCurrent()
            }
            return
        }
        // 恢复上次队列时只还原了歌曲信息、没有真正 open 音频源（currentPlayingKey 仍为 null），
        // 此时直接 engine.play() 不会出声。必须重新走 playCurrent 解析音源并 open。
        if (currentPlayingKey != _currentSong.value?.key ||
            _state.value == PlaybackState.ERROR ||
            engine.snapshot.value.error != null
        ) {
            playCurrent()
            return
        }
        engine.play()
        _state.value = PlaybackState.PLAYING
        playbackStartedAt = System.currentTimeMillis()
    }

    fun pause() {
        flushListeningTime()
        engine.pause()
        _state.value = PlaybackState.PAUSED
    }

    fun togglePlayPause() {
        if (_state.value == PlaybackState.PLAYING) pause() else play()
    }

    fun next(userInitiated: Boolean = true) {
        val queue = _queue.value
        if (queue.isEmpty()) return
        val nextIndex = resolveNextIndex(userInitiated) ?: run {
            if (userInitiated) {
                _currentIndex.value = 0
                playCurrent()
            } else {
                pause()
                engine.seekTo(0)
            }
            return
        }
        _currentIndex.value = nextIndex
        playCurrent()
    }

    fun previous() {
        val queue = _queue.value
        if (queue.isEmpty()) return
        if (engine.snapshot.value.positionMs > 4000L) {
            seekTo(0)
            return
        }
        val prevIndex = resolvePreviousIndex()
        _currentIndex.value = prevIndex
        playCurrent()
    }

    fun seekTo(positionMs: Long) {
        engine.fadeReset(if (_state.value == PlaybackState.PLAYING) 1f else 0f)
        // 当前歌的内存缓存就绪时用本地文件 seek（瞬时）；否则维持 URL 直连 seek。
        val song = _currentSong.value
        val cached = audioCachedKey
        val seekInput = if (song != null && cached == song.key && audioCacheFile.isFile)
            AudioInput(path = audioCacheFile.absolutePath) else null
        engine.seekTo(positionMs, seekInput)
    }

    /** 直接播放队列中指定下标（Qt 前端的队列点击用）。 */
    fun playAt(index: Int) {
        if (index !in _queue.value.indices) return
        _currentIndex.value = index
        playCurrent()
    }

    fun restartCurrent() {
        playCurrent()
    }

    private fun resolveNextIndex(userInitiated: Boolean): Int? {
        val queue = _queue.value
        if (queue.isEmpty()) return null
        val current = _currentIndex.value
        if (_shuffle.value && shuffleOrder.isNotEmpty()) {
            val position = shuffleOrder.indexOf(current)
            if (position >= 0 && position < shuffleOrder.lastIndex) return shuffleOrder[position + 1]
            return if (_repeatMode.value != RepeatMode.OFF || userInitiated) shuffleOrder.firstOrNull() else null
        }
        return when {
            current < queue.lastIndex -> current + 1
            _repeatMode.value == RepeatMode.ALL -> 0
            userInitiated -> 0
            else -> null
        }
    }

    private fun resolvePreviousIndex(): Int {
        val queue = _queue.value
        if (queue.isEmpty()) return -1
        val current = _currentIndex.value
        if (_shuffle.value && shuffleOrder.isNotEmpty()) {
            val position = shuffleOrder.indexOf(current)
            if (position > 0) return shuffleOrder[position - 1]
            return shuffleOrder.lastOrNull() ?: 0
        }
        return if (current > 0) current - 1 else queue.lastIndex
    }

    suspend fun handleTrackCompleted() {
        flushListeningTime()
        if (_sleepTimer.value.stopAfterCurrent) {
            stopAfterCurrentFinished()
            return
        }
        if (_repeatMode.value == RepeatMode.ONE) {
            seekTo(0)
            play()
            return
        }
        next(userInitiated = false)
    }

    private fun rebuildShuffleOrder(anchorIndex: Int) {
        val size = _queue.value.size
        if (size == 0) {
            shuffleOrder = emptyList()
            return
        }
        val indices = (0 until size).filter { it != anchorIndex }.shuffled(Random(System.nanoTime()))
        shuffleOrder = if (anchorIndex in 0 until size) listOf(anchorIndex) + indices else indices
    }

    fun toggleShuffle() {
        _shuffle.value = !_shuffle.value
        rebuildShuffleOrder(_currentIndex.value)
    }

    fun setShuffle(enabled: Boolean) {
        if (_shuffle.value == enabled) return
        toggleShuffle()
    }

    fun setRepeatMode(mode: RepeatMode) {
        _repeatMode.value = mode
    }

    fun cycleRepeatMode() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    // ---------------- 播放核心 ----------------

    private fun playCurrent() {
        val queue = _queue.value
        val index = _currentIndex.value
        if (index !in queue.indices) return
        val song = queue[index]
        val switchingTrack = currentPlayingKey != null && currentPlayingKey != song.key
        _currentSong.value = song
        _state.value = PlaybackState.PREPARING
        _buffering.value = true
        beginCrossfadeIfNeeded(switchingTrack)
        engine.prepare(song.durationMs)
        _lyrics.value = lyricsRepository.cached(song) ?: Lyrics()
        scope.launch { loadLyricsFor(song) }
        scope.launch { updateCoverSeed(song) }
        scope.launch {
            val input = resolveInput(song)
            if (input == null) {
                _state.value = PlaybackState.ERROR
                _buffering.value = false
                _messages.emit("无法获取播放地址，请稍后重试")
                return@launch
            }
            val duration = song.durationMs.takeIf { it > 0 }
                ?: FfmpegSupport.probeDurationMs(input)
            engine.open(input, 0L, duration)
            engine.setVolume(_volume.value)
            if (engine.supportsEffects) {
                engine.setSpeed(_speed.value)
                engine.setPitch(_pitch.value)
                engine.setLoudness(_loudness.value)
                engine.setAudioEffects(_audioEffects.value)
            }
            engine.play()
            currentPlayingKey = song.key
            playbackStartedAt = System.currentTimeMillis()
            _state.value = PlaybackState.PLAYING
            history.record(song)
            stats.recordPlay(song)
            persistQueueState()
            // 播放稳定后：后台下载当前歌到内存缓存（供 seek 瞬时切换），
            // 并预解析下一首直链（切歌时省去网络解析）。都不阻塞当前播放。
            scope.launch { cacheCurrentForSeek() }
        }
    }

    /**
     * 后台缓存当前歌音频到唯一的内存文件（供 seek 使用），并预解析下一首直链。
     * 缓存文件同时只存在一首：切歌后会被新的当前歌覆盖，不会累积占用。
     */
    private suspend fun cacheCurrentForSeek() {
        val song = _currentSong.value ?: return
        val input = resolvedCache[song.key] ?: runCatching { resolveInput(song) }.getOrNull() ?: return
        if (input.url != null && (audioCachedKey != song.key || !audioCacheFile.isFile)) {
            prefetchAudio(song, input)
        }
        // 预解析下一首直链，切歌更快（不下载音频，仅缓存地址）
        val queue = _queue.value
        val current = _currentIndex.value
        val nextIndex = resolveNextIndex(userInitiated = false)?.takeIf { it != current } ?: return
        val next = queue.getOrNull(nextIndex) ?: return
        if (!resolvedCache.containsKey(next.key)) runCatching { resolveInput(next) }
        trimResolvedCache(current, setOf(nextIndex))
    }

    /** 只保留当前与下一首的音源地址缓存（音频缓存始终只有一个文件）。 */
    private fun trimResolvedCache(current: Int, neighbors: Set<Int>) {
        val queue = _queue.value
        val keep = HashSet<String>()
        keep += queue.getOrNull(current)?.key ?: ""
        neighbors.forEach { index -> queue.getOrNull(index)?.key?.let(keep::add) }
        resolvedCache.keys.removeIf { it !in keep }
    }

    /**
     * 交叉淡入：切歌时把当前引擎降级为「旧曲」，仅做淡出；为新曲创建独立引擎，
     * 两路 PCM 由系统混音器（PipeWire/PulseAudio）按互补增益叠加，实现真正的重叠渐变。
     * 无交叉淡入或首曲播放时不做任何切换，保持原有手感。
     */
    private fun beginCrossfadeIfNeeded(switchingTrack: Boolean) {
        if (!switchingTrack || !FadeSettings.crossfadeEnabled) return
        if (_state.value != PlaybackState.PLAYING) return
        val previous = engine
        val duration = FadeSettings.crossfadeMs
        // 旧曲的完成回调不应触发再次切歌；淡出结束后它会被 release。
        previous.onCompleted = null
        previous.fadeTo(0f, 48_000, duration)
        outgoingEngine = previous
        switchEngine(createAudioEngine())
        crossfadeJob?.cancel()
        val generation = ++crossfadeGeneration
        crossfadeJob = scope.launch {
            delay(duration.toLong() + 200L)
            finishCrossfade(generation)
        }
    }

    /** 交叉淡入窗口结束后释放旧引擎；若期间又发生切歌，旧的 cleanup 会被 generation 拦下。 */
    private fun finishCrossfade(generation: Int? = null) {
        if (generation != null && generation != crossfadeGeneration) return
        outgoingEngine?.let { old ->
            old.setPcmTap(null)
            old.release()
        }
        outgoingEngine = null
        crossfadeJob = null
    }

    private fun stopPlaybackInternal() {
        flushListeningTime()
        crossfadeJob?.cancel()
        finishCrossfade()
        engine.release()
        currentPlayingKey = null
        playbackStartedAt = 0L
    }

    /** 把当前播放片段的时长写入统计，并重新开始计时。 */
    fun flushListeningTime() {
        val song = _currentSong.value ?: return
        val started = playbackStartedAt
        if (started <= 0L) return
        val now = System.currentTimeMillis()
        val delta = (now - started).coerceAtLeast(0L)
        playbackStartedAt = if (_state.value == PlaybackState.PLAYING) now else 0L
        if (delta > 0L) {
            stats.addListenTime(song, delta)
        }
    }

    /**
     * 解析播放输入。
     *
     * 网易云歌曲多一层兜底：直链解析失败（VIP/无权限）或直链在引擎侧播放失败时，
     * 按设置 neteaseAutoSourceSwitch 到哔哩哔哩搜索同名音频；其它音源保持原有行为。
     */
    private suspend fun resolveInput(song: Song): AudioInput? = resolveMutex.withLock {
        // 已下载的歌曲优先走本地文件，未联网也能播放
        downloads.fileFor(song.key)?.let { file ->
            return@withLock AudioInput(path = file.absolutePath)
        }
        // 引擎侧直链播放失败后会申请一次 B 站回退：本次跳过直链缓存，直接从 B 站音源开始
        val engineFailureRetry = song.source == MediaSource.NETEASE &&
            neteaseAutoSource.consumeEngineFailureRetry(song.key)
        if (!engineFailureRetry) {
            resolvedCache[song.key]?.let { cached ->
                val path = cached.path
                if (path != null) {
                    // 内存缓存文件被裁剪掉后要重新解析，不能返回失效路径
                    if (File(path).isFile) return@withLock cached
                } else if (cached.url != null) {
                    return@withLock cached
                }
            }
            // 30 分钟内解析过的 B 站音源直接复用，避免每次播放都重新搜索；关闭开关后不再使用
            if (song.source == MediaSource.NETEASE && settings.current.neteaseAutoSourceSwitch) {
                neteaseAutoSource.cached(song.key)?.let { return@withLock it }
            }
        }
        if (!engineFailureRetry) {
            val resolved = online.resolvePlayback(song, settings.current.qualityPreference)
            if (resolved != null) {
                resolvedCache[song.key] = resolved
                // 首播/切歌照常走 URL 直连，不等待缓存；本地缓存只在 seek 时使用
                return@withLock resolved
            }
            if (song.source == MediaSource.NETEASE) {
                println("[fallback] 网易云直链解析失败：${song.displayName()} - ${song.artist}")
            }
        }
        if (song.source != MediaSource.NETEASE || !settings.current.neteaseAutoSourceSwitch) {
            return@withLock null
        }
        val fallback = neteaseAutoSource.resolve(song)
        if (fallback != null) {
            if (neteaseAutoSource.takeNotice(song.key)) {
                _messages.tryEmit(NETEASE_AUTO_SOURCE_FALLBACK_MESSAGE)
            }
            return@withLock fallback
        }
        if (engineFailureRetry) {
            // B 站也没找到时把直链再交给引擎试一次，避免一次偶发失败就彻底不播
            val resolved = online.resolvePlayback(song, settings.current.qualityPreference)
            if (resolved != null) {
                resolvedCache[song.key] = resolved
                return@withLock resolved
            }
        }
        null
    }

    /** 网易云直链在引擎侧失败（如 403）时，同一首歌最多用 B 站音源重试一次，防止来回切换。 */
    private fun maybeRetryWithAutoSource() {
        val song = _currentSong.value ?: return
        if (song.source != MediaSource.NETEASE) return
        if (!settings.current.neteaseAutoSourceSwitch) return
        if (!neteaseAutoSource.requestEngineFailureRetry(song.key)) return
        println("[fallback] 网易云直链播放失败，尝试哔哩哔哩音源：${song.displayName()}")
        scope.launch { playCurrent() }
    }

    private suspend fun loadLyricsFor(song: Song) {
        _lyricsLoading.value = true
        val loaded = lyricsRepository.load(song)
        if (_currentSong.value?.key == song.key) {
            _lyrics.value = loaded
        }
        _lyricsLoading.value = false
    }

    fun reloadLyrics() {
        val song = _currentSong.value ?: return
        lyricsRepository.invalidate(song)
        scope.launch { loadLyricsFor(song) }
    }

    private suspend fun updateCoverSeed(song: Song) {
        val cached = seedCache[song.key]
        if (cached != null) {
            _coverSeedColor.value = cached
            return
        }
        val color = withContext(Dispatchers.IO) { extractSeedColor(song) }
        if (color != null) {
            seedCache[song.key] = color
            if (_currentSong.value?.key == song.key) _coverSeedColor.value = color
        } else if (_currentSong.value?.key == song.key) {
            _coverSeedColor.value = null
        }
    }

    private fun artworkBytes(song: Song): ByteArray? {
        val path = song.artworkPath
        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.isFile) return runCatching { file.readBytes() }.getOrNull()
        }
        val url = song.artworkUrl ?: return null
        val cached = File(AppDirs.coverDir, song.key.replace(Regex("[^A-Za-z0-9]"), "_") + ".img")
        if (cached.isFile) return runCatching { cached.readBytes() }.getOrNull()
        val bytes = runCatching { java.net.URI(url).toURL().openStream().use { it.readBytes() } }.getOrNull() ?: return null
        runCatching { cached.writeBytes(bytes) }
        return bytes
    }

    private fun extractSeedColor(song: Song): String? {
        val bytes = artworkBytes(song) ?: return null
        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        if (image.width <= 0 || image.height <= 0) return null
        val stepX = (image.width / 32).coerceAtLeast(1)
        val stepY = (image.height / 32).coerceAtLeast(1)
        var bestScore = -1.0
        var bestColor: Int? = null
        var x = 0
        while (x < image.width) {
            var y = 0
            while (y < image.height) {
                val rgb = image.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                val chroma = (max - min).toDouble() / 255.0
                val brightness = max / 255.0
                val score = chroma * 1.6 + brightness * 0.4
                if (score > bestScore) {
                    bestScore = score
                    bestColor = rgb
                }
                y += stepY
            }
            x += stepX
        }
        val color = bestColor ?: return null
        return "%06X".format(color and 0xFFFFFF)
    }

    // ---------------- 音效 ----------------

    fun setVolume(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        _volume.value = clamped
        engine.setVolume(clamped)
        outgoingEngine?.setVolume(clamped)
        settings.updateDeferred { it.copy(volume = clamped) }
    }

    fun setSpeed(value: Float) {
        val clamped = value.coerceIn(0.5f, 3f)
        _speed.value = clamped
        engine.setSpeed(clamped)
        settings.updateDeferred { it.copy(playbackSpeed = clamped) }
    }

    fun setPitch(semitones: Float) {
        val clamped = semitones.coerceIn(-12f, 12f)
        _pitch.value = clamped
        engine.setPitch(clamped)
        settings.updateDeferred { it.copy(pitchSemitone = clamped) }
    }

    fun setLoudness(enabled: Boolean) {
        _loudness.value = enabled
        engine.setLoudness(enabled)
        settings.update { it.copy(loudnessEnhancer = enabled) }
    }

    /**
     * 应用一整份 DSP 参数：写内存 → 写引擎 → 落盘，并把旧的十段均衡器视图同步过去。
     *
     * 这是音效参数的**唯一写入口**（`player.setAudioEffects` 与 `audioEffects.updateSound` 都走它），
     * 引擎侧只在参数真的变了时才重启解码器，所以重复下发同样的值没有代价。
     */
    fun setAudioEffects(effects: AudioEffectsSound): AudioEffectsSound {
        val normalized = effects.normalized()
        if (normalized == _audioEffects.value) return normalized
        _audioEffects.value = normalized
        // 旧界面的视图：图形均衡段的增益；分区/总开关关掉时对旧界面表现为「均衡器已关闭」
        val legacyBands = normalized.graphicBandsDb.map { it.toFloat() }
        _equalizerBands.value = legacyBands
        _equalizerEnabled.value = normalized.enabled && normalized.equalizerEnabled
        if (engine.supportsEffects) engine.setAudioEffects(normalized)
        settings.updateDeferred {
            it.copy(
                equalizerEnabled = _equalizerEnabled.value,
                equalizerBands = legacyBands,
            )
        }
        audioEffectsUpdatedAt = AudioEffectsStore.save(normalized).updatedAt
        return normalized
    }

    /** 只改一部分字段（滑块一次只改一个值），其余保留当前值。 */
    fun updateAudioEffects(transform: (AudioEffectsSound) -> AudioEffectsSound): AudioEffectsSound =
        setAudioEffects(transform(_audioEffects.value))

    fun setEqualizerEnabled(enabled: Boolean) {
        setAudioEffects(_audioEffects.value.copy(enabled = true, equalizerEnabled = enabled))
    }

    /** 切换预设：只是把一组十段增益写进图形均衡，后端不内置预设曲线。 */
    fun setEqualizerPreset(name: String, bands: List<Float>) {
        setAudioEffects(
            _audioEffects.value.copy(
                enabled = true,
                equalizerEnabled = true,
                graphicBandsDb = List(GRAPHIC_EQ_FREQUENCIES.size) { index ->
                    (bands.getOrNull(index) ?: 0f).toDouble()
                },
            ),
        )
        settings.updateDeferred { it.copy(equalizerPreset = name) }
    }

    fun setEqualizerBand(index: Int, gain: Float) {
        if (index !in 0 until GRAPHIC_EQ_FREQUENCIES.size) return
        setAudioEffects(
            _audioEffects.value.copy(
                enabled = true,
                equalizerEnabled = true,
                graphicBandsDb = List(GRAPHIC_EQ_FREQUENCIES.size) { position ->
                    if (position == index) {
                        gain.toDouble()
                    } else {
                        _audioEffects.value.graphicBandsDb.getOrElse(position) { 0.0 }
                    }
                },
            ),
        )
        settings.updateDeferred { it.copy(equalizerPreset = "自定义") }
    }

    /** 更新淡入淡出 / 交叉淡入设置；内存与引擎立即生效，写盘延迟合并。 */
    fun setFadeOptions(
        fadeEnabled: Boolean,
        fadeInMs: Int,
        fadeOutMs: Int,
        crossfadeEnabled: Boolean,
        crossfadeMs: Int,
    ) {
        val inMs = fadeInMs.coerceAtLeast(0)
        val outMs = fadeOutMs.coerceAtLeast(0)
        val crossMs = crossfadeMs.coerceAtLeast(0)
        FadeSettings.apply(fadeEnabled, inMs, outMs, crossfadeEnabled, crossMs)
        settings.updateDeferred {
            it.copy(
                fadeEnabled = fadeEnabled,
                fadeInMs = inMs,
                fadeOutMs = outMs,
                crossfadeEnabled = crossfadeEnabled,
                crossfadeMs = crossMs,
            )
        }
    }

    fun resetEffects() {
        setSpeed(1f)
        setPitch(0f)
        setLoudness(false)
        // 全部恢复默认：DSP 参数回到出厂值，旧均衡器视图随之清零
        setAudioEffects(AudioEffectsSound())
        settings.update { it.copy(equalizerPreset = "平直") }
    }

    // ---------------- 睡眠定时器 ----------------

    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        val total = minutes.coerceAtLeast(1) * 60L
        _sleepTimer.value = SleepTimerState(active = true, remainingSeconds = total, totalSeconds = total)
        sleepTimerJob = scope.launch {
            var remaining = total
            while (remaining > 0) {
                delay(1_000)
                remaining -= 1
                _sleepTimer.value = _sleepTimer.value.copy(remainingSeconds = remaining)
            }
            pause()
            _sleepTimer.value = SleepTimerState()
            _messages.emit("睡眠定时器已结束，播放已暂停")
        }
    }

    fun stopAfterCurrentSong() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimer.value = SleepTimerState(active = true, stopAfterCurrent = true)
        _messages.tryEmit("播完当前歌曲后停止")
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimer.value = SleepTimerState()
    }

    private suspend fun stopAfterCurrentFinished() {
        _sleepTimer.value = SleepTimerState()
        pause()
        _messages.emit("已播放完当前歌曲，播放暂停")
    }

    // ---------------- 状态持久化 ----------------

    fun persistQueueState() {
        settings.update {
            it.copy(
                lastQueue = _queue.value.take(200),
                lastQueueIndex = _currentIndex.value,
                lastPositionMs = engine.snapshot.value.positionMs,
            )
        }
    }

    fun restoreLastQueue() {
        val saved = settings.current
        if (!saved.resumeLastQueue || saved.lastQueue.isEmpty()) return
        val index = saved.lastQueueIndex.coerceIn(0, saved.lastQueue.lastIndex)
        _queue.value = saved.lastQueue
        _currentIndex.value = index
        _currentSong.value = saved.lastQueue[index]
        _state.value = PlaybackState.PAUSED
    }
}
