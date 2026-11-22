package moe.ouom.neriplayer.desktop.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

/** 网易云不可播、自动切到哔哩哔哩成功后的提示。 */
private const val NETEASE_AUTO_SOURCE_FALLBACK_MESSAGE =
    "网易云音源不可用，已自动切换到哔哩哔哩音源"

/** 下一首预解析的超时预算：超时放弃，不阻塞也不拖累切歌。 */
private const val PREFETCH_TIMEOUT_MS = 15_000L

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
    /**
     * 每次 playCurrent 递增的代次。音源解析是异步的，快速连续切歌会有多个解析协程在飞；
     * 回调里只有代次仍是最新才允许 open/写 currentPlayingKey，过期结果直接丢弃，
     * 否则先完成的旧歌解析会把音频覆盖成已经不是当前的歌曲（界面新歌、声音旧歌）。
     */
    private var playbackGeneration = 0
    /**
     * setQueue(autoPlay=false, startMs=...) 收下的起始进度。一起听切歌时房态已经给出落点，
     * 但重建队列刻意不 autoPlay、改由后续 play() 触发 playCurrent；startMs 必须经这里
     * 传给那次 playCurrent，否则新歌会从 0 开始播，与房间进度错位。
     */
    private var pendingPlayStartMs = 0L
    private var playbackStartedAt = 0L
    private var sleepTimerJob: Job? = null
    private val resolveMutex = Mutex()
    private val resolvedCache = ConcurrentHashMap<String, AudioInput>()

    /**
     * 下一首播放地址的预解析缓存（按 song.key + TTL）。Android 在当前曲目进入 READY
     * 时就解析下一首的最终播放地址，桌面端对齐这一时机，切歌时命中缓存直接 open，
     * 省掉网易云失败再串行去 B 站搜索的等待。与 [resolvedCache] 分开：这里带 TTL，
     * 网易云直链短时效，过期后必须重新解析。
     */
    private val nextPrefetchCache = NextSongPrefetchCache()
    /** 当前在飞的下一次解析任务；目标变化时取消，防止旧的解析覆盖新目标。 */
    private var nextPrefetchJob: Job? = null
    @Volatile private var nextPrefetchKey: String? = null

    /** 按歌曲 key 记录的临时音质档位（播放页「切换音质」，不写全局偏好，不落盘）。 */
    private val qualityOverrides = ConcurrentHashMap<String, String>()
    private val _currentQuality = MutableStateFlow<String?>(null)
    /** 当前曲目实际使用的音质档位（临时覆盖优先，否则为全局默认）；null 表示尚无当前曲目。 */
    val currentQuality: StateFlow<String?> = _currentQuality.asStateFlow()

    /**
     * 本次播放实际使用的音源：控制者据此把直链上传到一起听房间。
     * 只在 engine.open 成功后写入，切歌/清空队列时清掉；仅暴露只读快照。
     */
    private val _currentAudioInput = AtomicReference<AudioInput?>(null)
    val currentAudioInput: AudioInput? get() = _currentAudioInput.get()

    /**
     * 实际出声的曲目是否就是当前界面曲目。
     * setQueue(autoPlay=false) 只换 _currentSong（界面/歌词依据），不会真正 open 音频，
     * 于是会出现「界面已是新歌、引擎还在放旧歌」的中间态。一起听补播判定必须读这个，
     * 不能只看 state 里残留的 PLAYING，否则缺一次 play() 就永远停在旧歌。
     */
    val isAudioOnCurrentTrack: Boolean
        get() = currentPlayingKey != null && currentPlayingKey == _currentSong.value?.key

    /**
     * 一起听：本地直链解析失败回调。由 ListenTogetherManager 注入，听众侧据此发 REQUEST_LINK。
     * 参数用 Song 而非 URL，避免把可能为空的解析结果误当成失败通知。
     */
    @Volatile
    var onPlaybackResolveFailed: ((Song) -> Unit)? = null

    /** 在线音频磁盘 LRU 缓存；与 /dev/shm 的播放加速缓存相互独立。 */
    private val musicCache = MusicCache(settings)

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

    /**
     * seek 用的缓存快照（[audioCacheFile] 的硬链接）。
     *
     * current.audio 会被切歌预取/换源随时删掉重建，直接把它的路径交给 ffmpeg 是典型 TOCTOU：
     * 判定时 isFile=true，等新进程真正 open 时文件已经不在，于是 ffmpeg 报
     * `No such file or directory` → 播放流中断 → 回退换源 → 进度被打回 0。
     * 硬链接在创建的瞬间就固定了 inode，之后原路径被删/改名也不影响它，且不用整首复制。
     *
     * 每次 seek 只保留一份快照：下一次 seek 时删除上一次的链接（那一刻旧 ffmpeg 早已打开 fd，
     * 删除链接不影响它继续读），把磁盘占用限定在一首歌。
     */
    @Volatile private var seekSnapshotFile: File? = null
    private var seekSnapshotSeq = 0L

    /**
     * 预取时记录的「远端音频应有多大」，键是歌曲 key。
     *
     * seek 路径不联网，只能靠这份下载时记下的大小判断缓存是否完整；
     * 值为 -1 表示远端没给出长度，此时不做完整性限制。
     */
    private val expectedAudioBytes = ConcurrentHashMap<String, Long>()

    /** 后台流式下载指定歌曲到唯一的内存缓存文件；成功记录 audioCachedKey。 */
    private suspend fun prefetchAudio(song: Song, input: AudioInput) {
        val url = input.url ?: return
        val ok = withContext(Dispatchers.IO) {
            val tmp = File(audioCacheDir, "current.audio.tmp")
            audioCachedKey = null   // 先作废旧缓存，避免切歌时读到半写文件
            // 下载服务端也会在传输中途正常关闭连接（CDN 截断、连接被中间设备回收），这时
            // read 只返回 -1 而不是抛异常。必须拿已下载字节数与 Content-Length 对账，
            // 否则半截文件会被当成完整缓存：之后用它 seek 长音频就会一拖进度条就 EOF。
            var expectedBytes = -1L
            var downloadedBytes = 0L
            // 流式下载，避免整首歌以 ByteArray 驻留堆内存；失败时工具方法会删除半成品
            val transferred = online.httpService.downloadToFile(url, input.headers, tmp) { done, total ->
                downloadedBytes = done
                if (total > 0L) expectedBytes = total
            }
            val complete = transferred && (expectedBytes <= 0L || downloadedBytes >= expectedBytes)
            if (!complete) {
                println(
                    "[cache] 音频缓存不完整，丢弃：downloaded=$downloadedBytes expected=$expectedBytes " +
                        "（${song.displayName()}）",
                )
                false
            } else {
                runCatching {
                    if (audioCacheFile.exists()) audioCacheFile.delete()
                    tmp.renameTo(audioCacheFile)
                    audioCacheFile.length() > 0L
                }.getOrDefault(false).also { renamed ->
                    // 记下远端大小：seek 路径不联网，靠这份记录判断缓存是否完整
                    if (renamed) expectedAudioBytes[song.key] = expectedBytes
                }
            }
        }
        audioCachedKey = if (ok) song.key else null
    }
    private val seedCache = ConcurrentHashMap<String, String>()

    /** 网易云不可播时的哔哩哔哩兜底：缓存、提示去重与引擎侧失败重试都在内部处理。 */
    private val neteaseAutoSource = NeteaseAutoSource(online)

    val supportsEffects: Boolean get() = engine.supportsEffects

    /** 当前引擎是否真的能变速；不能变速时一起听软同步会退回硬 seek。 */
    val supportsSpeedChange: Boolean get() = engine.supportsSpeedChange

    /**
     * 一起听软同步用的临时播放速率（0.95~1.05），**只作用于引擎，不写用户设置**。
     *
     * 与 [setSpeed] 的区别：那个是用户偏好（会落盘），这个是同步期间的临时倍率。
     * 引擎拿到的是「用户倍速 × 同步倍率」，所以软校正结束必须调
     * [resetListenTogetherRate]，否则会一直停在非 1.0 速率上。
     */
    @Volatile
    private var listenTogetherRate: Float = 1f

    /** 当前生效的同步倍率（1f 表示没有软校正）；给一起听管理器做节流判断。 */
    val listenTogetherSyncRate: Float get() = listenTogetherRate

    /** 真正下发给引擎的倍速 = 用户偏好倍速 × 一起听临时同步倍率。 */
    private val effectiveSpeed: Float get() = _speed.value * listenTogetherRate

    private fun applyEffectiveSpeedToEngine() {
        if (engine.supportsSpeedChange) engine.setSpeed(effectiveSpeed)
    }

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
            engine.setSpeed(effectiveSpeed)
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
            target.setSpeed(effectiveSpeed)
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

    fun setQueue(
        songs: List<Song>,
        startIndex: Int,
        autoPlay: Boolean = true,
        startMs: Long = 0L,
    ) {
        if (songs.isEmpty()) {
            clearQueue()
            return
        }
        _queue.value = songs
        rebuildShuffleOrder(startIndex.coerceIn(0, songs.lastIndex))
        _currentIndex.value = startIndex.coerceIn(0, songs.lastIndex)
        if (autoPlay) {
            // 一起听切歌时房间已经给出目标进度：直接带着它打开，先播 0 再 seek
            // 会让界面看到一次进度归零又跳回来的跳动。
            pendingPlayStartMs = 0L
            playCurrent(startMs)
        } else {
            val target = songs[_currentIndex.value]
            _currentSong.value = target
            // 只装填队列不自动播放时（一起听恢复暂停中的房间、纯队列重排），不会走
            // playCurrent，歌词就永远停在初始空值。这里按缓存先渲染，再异步补一次加载；
            // 代次取自当前值，随后的 playCurrent 会递增代次让这次结果自然失效。
            _lyrics.value = lyricsRepository.cached(target) ?: Lyrics()
            val lyricsGeneration = playbackGeneration
            scope.launch { loadLyricsFor(target, lyricsGeneration) }
            // 暂存房态落点，等随后的 play() 真正打开音源时使用；纯队列重排（startMs=0）
            // 写 0 也无害。真正 open 音源时 playCurrent 会递增代次并清掉这个值。
            pendingPlayStartMs = startMs
        }
    }

    fun playSongNow(song: Song, queue: List<Song>? = null) {
        val targetQueue = (queue ?: _queue.value).ifEmpty { listOf(song) }
        val index = targetQueue.indexOfFirst { it.key == song.key }.takeIf { it >= 0 } ?: 0
        setQueue(targetQueue, index, autoPlay = true)
    }

    fun enqueueNext(songs: List<Song>) {
        if (songs.isEmpty()) return
        if (_currentSong.value == null) {
            setQueue(PlayerQueueEdit.dedupe(songs), 0, autoPlay = true)
            return
        }
        applyQueueEdit(songs, QueueInsertPlacement.NEXT, "已添加到下一首播放")
    }

    fun enqueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        if (_currentSong.value == null) {
            setQueue(PlayerQueueEdit.dedupe(songs), 0, autoPlay = true)
            return
        }
        applyQueueEdit(songs, QueueInsertPlacement.END, "已添加到播放队列")
    }

    /** 按曲目身份去重后加歌（规则见 [PlayerQueueEdit]）；正在播放的曲目保持不变。 */
    private fun applyQueueEdit(
        songs: List<Song>,
        placement: QueueInsertPlacement,
        message: String,
    ) {
        val playing = _currentSong.value ?: return
        val edit = PlayerQueueEdit.insert(
            queue = _queue.value,
            songs = songs,
            currentIndex = _currentIndex.value,
            playingKey = playing.key,
            placement = placement,
        )
        _queue.value = edit.queue
        _currentIndex.value = edit.currentIndex
        rebuildShuffleOrder(edit.currentIndex)
        // 队列改动可能换掉了"下一首"，旧的预解析目标不再适用
        cancelNextPrefetch("queue_edited")
        nextPrefetchCache.clear()
        scope.launch { _messages.emit(message) }
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
        cancelNextPrefetch("queue_cleared")
        nextPrefetchCache.clear()
        _queue.value = emptyList()
        _currentIndex.value = -1
        _currentSong.value = null
        _state.value = PlaybackState.IDLE
        _lyrics.value = Lyrics()
        _currentAudioInput.set(null)
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
            val startMs = pendingPlayStartMs
            pendingPlayStartMs = 0L
            playCurrent(startMs)
            return
        }
        pendingPlayStartMs = 0L
        engine.play()
        _state.value = PlaybackState.PLAYING
        playbackStartedAt = System.currentTimeMillis()
    }

    fun pause() {
        flushListeningTime()
        engine.pause()
        // 暂停时只复位同步倍率参数、不立即改引擎速率：engine.pause() 会先做一段淡出，
        // 此时 setSpeed 可能重建流水线（音效回退路径）或改写 Sonic 的速率换算，
        // 让还没淡完的那一小段声音又响出来。用 deferred 语义等下次 play/seek 重建再生效。
        // 离开房间/被提升控制者等"不紧跟重建"的场景仍用 resetListenTogetherRate 立即生效。
        prepareSpeedForPipelineRestart()
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
        // 硬定位后原来的软校正已经没有意义：只写速率参数、不额外重启流水线，
        // seek 本身就会重建解码器，届时读到的正是用户倍速。
        prepareSpeedForPipelineRestart()
        engine.fadeReset(if (_state.value == PlaybackState.PLAYING) 1f else 0f)
        // 当前歌的内存缓存就绪时用本地文件 seek（瞬时）；否则维持 URL 直连 seek。
        // 缓存文件必须证明自己是完整的：半截文件（下载中断、磁盘满、旧版本留下的）能解析出
        // 正确时长，却读不到后半段，用它 seek 长音频就会立刻 EOF —— 正是「一拖进度条就切歌」
        // 的现场之一。判定只用本地已掌握的信息，不在 seek 路径上发网络请求：
        // 拖动进度条是高频操作，卡一次网络就会让界面明显迟滞。
        val song = _currentSong.value
        val cached = audioCachedKey
        val cacheUsable = song != null && cached == song.key && audioCacheFile.isFile
        // 用硬链接快照而不是 current.audio 本身：快照一旦建立，即便预取/切歌随后删掉原路径，
        // 已经打开的 ffmpeg 依然能完整读到这份 inode，杜绝 TOCTOU 引起的「文件不存在」中断。
        // 建快照失败（跨文件系统/权限）时退回 null —— URL 直连 seek 慢但可靠。
        val seekInput = if (cacheUsable && isAudioCacheComplete()) {
            createSeekCacheSnapshot()?.let { AudioInput(path = it.absolutePath) }
        } else {
            if (cacheUsable) invalidateAudioCache(song, "seek 时发现缓存不完整")
            null
        }
        engine.seekTo(positionMs, seekInput)
    }

    /**
     * 为一次 seek 建立缓存文件的硬链接快照；失败返回 null（调用方退回 URL 直连）。
     *
     * 硬链接与 current.audio 同一 inode，不占额外空间；原路径被删后快照仍有效，
     * 直到下一次 seek 主动删除它。长度再次对账，避免两次判定之间文件被换成半截文件。
     */
    private fun createSeekCacheSnapshot(): File? {
        val key = audioCachedKey ?: return null
        val snapshot = File(audioCacheDir, "seek-${key.hashCode()}-${++seekSnapshotSeq}.audio")
        val ok = runCatching {
            if (snapshot.exists()) snapshot.delete()
            Files.createLink(snapshot.toPath(), audioCacheFile.toPath())
            // 建立链接后再核对长度：预取若在两次检查之间替换了文件，宁可这次不用缓存
            val expected = expectedAudioBytes[key] ?: -1L
            snapshot.length() > 0L && (expected <= 0L || snapshot.length() >= expected)
        }.getOrDefault(false)
        if (!ok) {
            runCatching { snapshot.delete() }
            return null
        }
        // 上一次的快照此时已无 ffmpeg 在读（新一次 seek 会先停掉旧流水线），可以安全删除
        seekSnapshotFile?.let { old -> if (old != snapshot) runCatching { old.delete() } }
        seekSnapshotFile = snapshot
        return snapshot
    }

    /** 本地缓存的已下载字节数。 */
    private val audioCacheBytes: Long get() = runCatching { audioCacheFile.length() }.getOrDefault(0L)

    /**
     * 内存缓存是否完整：本地字节数与预取时记录的远端大小对账。
     *
     * 大小未知（预取时远端没给 Content-Length）时只能按「文件非空」判断，
     * 这种情况交给解码器侧的结尾判定兜底，不会静默切歌。
     */
    private fun isAudioCacheComplete(): Boolean {
        val localBytes = audioCacheBytes
        if (localBytes <= 0L) return false
        val expected = expectedAudioBytes[_currentSong.value?.key] ?: return true
        if (expected <= 0L) return true
        if (localBytes >= expected) return true
        println("[cache] 缓存不完整：local=$localBytes expected=$expected")
        return false
    }

    /** 缓存被判为不可用时清掉标记与文件，下次 seek 直接走远端直链。 */
    private fun invalidateAudioCache(song: Song, reason: String) {
        println("[cache] $reason，改用远端直链 seek：${song.displayName()}")
        audioCachedKey = null
        expectedAudioBytes.remove(song.key)
        runCatching { audioCacheFile.delete() }
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

    /**
     * 临时切换当前（或指定）曲目的播放音质。
     *
     * 与设置页的全局 qualityPreference 不同：这里的档位只记在 [qualityOverrides] 里，
     * 按 Song.key 生效，既不写设置也不落盘。切歌后这首再次被播放时仍用新档位，
     * 切到其他歌不受影响。音源不支持/无权限时解析失败会由调用方拿到明确文案。
     */
    suspend fun switchQuality(quality: String, song: Song? = null): Boolean {
        val target = song ?: _currentSong.value ?: return false
        val key = target.key
        val previous = qualityOverrides.put(key, quality)
        resolvedCache.remove(key)
        nextPrefetchCache.invalidate(key)
        if (audioCachedKey == key) audioCachedKey = null
        runCatching { audioCacheFile.delete() }
        expectedAudioBytes.remove(key)
        // 解析新档位（网络 IO），失败时回滚覆盖，避免整首歌后续都卡在不可用的档位上
        val input = resolveInput(target)
        if (input == null) {
            if (previous == null) qualityOverrides.remove(key) else qualityOverrides[key] = previous
            return false
        }
        resolvedCache[key] = input
        if (target.key == _currentSong.value?.key) {
            _currentQuality.value = quality
            // 续播：以当前引擎进度重新 open 新音源；open 会清掉播放请求，正在播时要恢复。
            val wasPlaying = _state.value == PlaybackState.PLAYING
            val position = engine.snapshot.value.positionMs
            engine.open(input, position, durationMs.value)
            _currentAudioInput.set(input)
            if (wasPlaying) {
                engine.play()
                _state.value = PlaybackState.PLAYING
                playbackStartedAt = System.currentTimeMillis()
            }
        }
        return true
    }

    /** 当前曲目生效的音质档位：临时覆盖优先，否则用全局默认。 */
    fun qualityFor(song: Song): String =
        qualityOverrides[song.key] ?: settings.current.qualityPreference

    /**
     * 一起听：权威流到达后让当前曲目重新解析并打开音频。
     *
     * 只在当前曲目身份匹配、且当前确实已经 open 过音源时重载；清掉已解析缓存，
     * 让 [resolveInput] 重新走 [OnlineRepository.resolvePlayback]（那边已优先使用共享流）。
     * 进度由 ListenTogetherManager 在重载后按房态重新定位，这里只负责换音源。
     */
    fun reloadCurrentForSharedStream() {
        val song = _currentSong.value ?: return
        if (currentPlayingKey == null || _currentAudioInput.get() == null) return
        resolvedCache.remove(song.key)
        nextPrefetchCache.invalidate(song.key)
        _currentAudioInput.set(null)
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

    /**
     * 引擎报告「播放结束」。
     *
     * 引擎侧已经用「实际解出多少音频 vs 已知时长」筛掉了远端直链提前 EOF 的情况，
     * 这里再按播放位置复核一次：位置明显没到结尾就不是真的播完，绝不能静默切下一首。
     * 复核只对「时长已知」的曲目生效 —— 时长未知时没有任何依据，只能相信引擎。
     */
    suspend fun handleTrackCompleted() {
        // 先结账：中断那条路径也不该丢掉这一段已经听过的时间
        flushListeningTime()
        val snapshot = engine.snapshot.value
        val durationMs = snapshot.durationMs.takeIf { it > 0 } ?: _currentSong.value?.durationMs ?: 0L
        val positionMs = snapshot.positionMs
        if (!PlaybackEndPolicy.isCompletionConfirmed(positionMs, durationMs)) {
            val message = PlaybackEndPolicy.interruptedMessage(positionMs, durationMs, null)
            println("[player] 收到播放结束但没有播到结尾（position=${positionMs}ms duration=${durationMs}ms），按中断处理")
            _state.value = PlaybackState.ERROR
            _buffering.value = false
            _messages.emit(message)
            return
        }
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

    private fun playCurrent(startMs: Long = 0L) {
        val queue = _queue.value
        val index = _currentIndex.value
        if (index !in queue.indices) return
        // 落点是一次性的：setQueue(autoPlay=false, startMs) 暂存后由紧随的 play() 取出，
        // 其它路径（next/previous/换源）不带落点，不能让上一次遗留的值污染新曲目的起点。
        pendingPlayStartMs = 0L
        val song = queue[index]
        val switchingTrack = currentPlayingKey != null && currentPlayingKey != song.key
        // 用户手动切到别的歌时，旧目标已无效：取消在飞任务，防止它写入过期结果。
        // 注意不能在这里清掉 song.key 的缓存——自然轮播到预解析目标时，
        // song.key 正是被预解析的下一首，清掉就失去了预解析的意义。
        // resolveInput 里 consume 是一次性的，命中即移除，不会重复误用。
        if (nextPrefetchKey != null && nextPrefetchKey != song.key) {
            cancelNextPrefetch("target_changed")
        }
        // 切歌同样会重建流水线：只写参数，让 open 之后的流水线直接用用户倍速
        prepareSpeedForPipelineRestart()
        // 递增代次：此后任何旧解析协程回来时只要代次已变，就丢弃它的结果。
        // 快速连续切歌时会有多个 resolveInput 在飞，代次是「最后一次请求胜出」的唯一裁决。
        val generation = ++playbackGeneration
        _currentSong.value = song
        _state.value = PlaybackState.PREPARING
        _buffering.value = true
        beginCrossfadeIfNeeded(switchingTrack)
        // prepare 用目标进度初始化快照：界面从第一帧就显示房态位置，不再闪一下 0。
        engine.prepare(song.durationMs, startMs)
        _lyrics.value = lyricsRepository.cached(song) ?: Lyrics()
        scope.launch { loadLyricsFor(song, generation) }
        scope.launch { updateCoverSeed(song) }
        scope.launch {
            val input = resolveInput(song)
            // 解析是挂起调用，期间可能已经又切了歌；不是最新代次就丢弃结果，
            // 绝不能 open 旧音源、也不能把 currentPlayingKey 写成过期 key。
            if (generation != playbackGeneration) {
                AppLog.debug(
                    "player",
                    "丢弃过期音源解析结果：key=${song.key} 当前代次=$playbackGeneration 本代次=$generation",
                )
                return@launch
            }
            if (input == null) {
                val reason = online.biliFailureReason(song) ?: "无法获取播放地址"
                AppLog.error("player", "音源解析失败：key=${song.key} 歌名=${song.displayName()} 原因=$reason")
                _state.value = PlaybackState.ERROR
                _buffering.value = false
                // 解析失败时不再保留上一次成功的音源：控制者据此避免把失效直链再上报给房间
                _currentAudioInput.set(null)
                // 一起听：本地解析失败时通知管理器（听众据此请求控制者直链），
                // 不直接报错就结束——控制者若在线会补发共享流，稍后 reload 无缝接上。
                onPlaybackResolveFailed?.invoke(song)
                // B 站解析链路会记录具体失败原因（durl 缺失 / cid 不配套 / 试看片段等），
                // 直接透出比笼统的「请稍后重试」更有用。
                _messages.emit("$reason，请稍后重试")
                return@launch
            }
            // probeDurationMs 同样是挂起式外部进程调用，期间也可能切歌；open 前再校验一次。
            val duration = song.durationMs.takeIf { it > 0 }
                ?: FfmpegSupport.probeDurationMs(input)
            if (generation != playbackGeneration) {
                AppLog.debug(
                    "player",
                    "丢弃过期音源解析结果（时长探测后）：key=${song.key} 当前代次=$playbackGeneration 本代次=$generation",
                )
                return@launch
            }
            engine.open(input, startMs, duration)
            _currentAudioInput.set(input)
            engine.setVolume(_volume.value)
            if (engine.supportsEffects) {
                engine.setSpeed(effectiveSpeed)
                engine.setPitch(_pitch.value)
                engine.setLoudness(_loudness.value)
                engine.setAudioEffects(_audioEffects.value)
            }
            engine.play()
            currentPlayingKey = song.key
            playbackStartedAt = System.currentTimeMillis()
            _state.value = PlaybackState.PLAYING
            AppLog.info(
                "player",
                "音频已切到目标曲目：key=${song.key} 代次=$generation target=${input.displayTarget()}",
            )
            history.record(song)
            stats.recordPlay(song)
            persistQueueState()
            // 播放稳定后：后台下载当前歌到内存缓存（供 seek 瞬时切换），不阻塞当前播放。
            scope.launch { cacheCurrentForSeek() }
            // 对齐 Android：当前曲目开始播放（READY）即预解析下一首的最终播放地址，
            // 不等整首歌下载完，也不阻塞当前播放。切歌命中缓存即可直接 open。
            prefetchNextSongUrl()
            // 把在线音频流式缓存到磁盘 LRU（后台进行，切歌即取消），不阻塞播放。
            musicCacheApplyLimit()
            cacheOnlineSong(song, input)
        }
    }

    /** 播放开始时低频应用一次缓存上限（如用户把上限调小后立即生效）。 */
    private fun musicCacheApplyLimit() {
        if (settings.current.cacheLimitMb == 0) {
            scope.launch(Dispatchers.IO) { musicCache.clear() }
        } else {
            scope.launch(Dispatchers.IO) { musicCache.applyLimit(settings.current.cacheLimitMb) }
        }
    }

    /**
     * 在线音频后台写入磁盘 LRU 缓存：本地/已下载歌曲、无直链的音频、缓存被禁用时都跳过。
     * 缓存文件按 key 命名但写入时用唯一临时文件，避免两个并发任务互相覆盖半成品。
     * writeMetadata 等下载路径不受影响。
     */
    private fun cacheOnlineSong(song: Song, input: AudioInput) {
        if (settings.current.cacheLimitMb == 0) return
        if (song.isLocalFile() || song.source == MediaSource.LOCAL || downloads.contains(song.key)) return
        val url = input.url ?: return
        scope.launch(Dispatchers.IO) {
            val target = musicCache.targetFor(song.key, input.urlExt())
            val part = File(target.parentFile, "${target.name}.part-${System.nanoTime()}")
            val ok = runCatching {
                if (!online.httpService.downloadToFile(url, input.headers, part) { _, _ -> }) {
                    false
                } else {
                    runCatching {
                        if (target.exists()) target.delete()
                        Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        true
                    }.getOrDefault(false)
                }
            }.getOrDefault(false)
            if (!ok) runCatching { part.delete() }
            musicCache.applyLimit(settings.current.cacheLimitMb)
        }
    }

    /** 从直链路径推断音频扩展名，失败时按 mp3 处理。 */
    private fun AudioInput.urlExt(): String {
        val raw = url?.substringBefore('?')?.substringAfterLast('.')?.lowercase().orEmpty()
        return raw.takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) } ?: "mp3"
    }

    /**
     * 后台缓存当前歌音频到唯一的内存文件（供 seek 使用）。
     * 缓存文件同时只存在一首：切歌后会被新的当前歌覆盖，不会累积占用。
     *
     * 下一首地址解析已经拆到 [prefetchNextSongUrl]，在播放开始时就并发启动，
     * 不再排在整首歌下载之后——否则大文件下载会把地址预解析拖到切歌之后。
     */
    private suspend fun cacheCurrentForSeek() {
        val song = _currentSong.value ?: return
        val input = resolvedCache[song.key] ?: runCatching { resolveInput(song) }.getOrNull() ?: return
        if (input.url != null && (audioCachedKey != song.key || !audioCacheFile.isFile)) {
            prefetchAudio(song, input)
        }
    }

    /**
     * 在当前曲目开始播放时预解析下一首的最终播放地址（对齐 Android 的 READY 时机）。
     *
     * 与旧的 `cacheCurrentForSeek` 里顺序解析不同：
     * - 独立协程，不与当前歌的整曲下载互相等待；
     * - 走 [resolveInputForPrefetch]，会把网易云失败后的 B 站兜底也算进去，
     *   这样切歌时不会现场串行「网易云失败 → B 站搜索」；
     * - 有 TTL（[NextSongPrefetchCache]）、song.key 校验和超时；
     * - 目标变化时取消在飞任务，用户手点另一首不会误用旧的预解析结果。
     */
    private fun prefetchNextSongUrl() {
        val queue = _queue.value
        val current = _currentIndex.value
        val nextIndex = resolveNextIndex(userInitiated = false)?.takeIf { it != current } ?: return
        val next = queue.getOrNull(nextIndex) ?: return
        if (next.isLocalFile() || next.source == MediaSource.LOCAL) return
        // 一起听：有人在一起听时用房间共享流，不能预解析本地来源覆盖它。
        // 共享流是"当前曲目"的权威流，下一首共享流尚未到达时不猜它的地址。
        if (online.hasSharedStreamFor(next)) return

        val key = next.key
        if (nextPrefetchCache.containsFresh(key)) return
        if (nextPrefetchJob?.isActive == true && nextPrefetchKey == key) return
        cancelNextPrefetch("replace_target")

        nextPrefetchKey = key
        val durationMs = _currentSong.value?.durationMs ?: 0L
        val launched = scope.launch(Dispatchers.IO) {
            try {
                // 与 Android 的解析预算一致：预解析不阻塞播放，超时就放弃，
                // 让切歌时走正常的串行解析（届时由用户看到明确的等待/报错）。
                val input = withTimeout(PREFETCH_TIMEOUT_MS) {
                    resolveInputForPrefetch(next)
                }
                if (input != null && currentCoroutineContext().isActive) {
                    // 解析途中共享流到达时丢弃本地结果，避免把过时地址留给切歌使用
                    if (online.hasSharedStreamFor(next)) return@launch
                    nextPrefetchCache.put(
                        songKey = key,
                        input = input,
                        ttlMs = resolveNextPrefetchTtlMs(durationMs),
                    )
                    println("[prefetch] 下一首地址预解析完成：${next.displayName()} key=$key")
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // 预解析失败不影响播放：切歌时走原来的同步解析兜底
                println("[prefetch] 下一首地址预解析失败：${next.displayName()} error=${error.message}")
            }
        }
        nextPrefetchJob = launched
        launched.invokeOnCompletion {
            if (nextPrefetchJob === launched) {
                nextPrefetchJob = null
                nextPrefetchKey = null
            }
        }
    }

    /** 切换目标、清空队列或切歌时取消在飞的下一次解析。 */
    private fun cancelNextPrefetch(reason: String) {
        val job = nextPrefetchJob ?: return
        nextPrefetchJob = null
        nextPrefetchKey = null
        job.cancel(CancellationException("next prefetch cancelled: $reason"))
    }

    /**
     * 预解析专用解析：与 [resolveInput] 的区别是"网易云直链不可用时当场走 B 站兜底"，
     * 而不是把兜底留给真正切歌时做。只写预解析缓存，不写 [resolvedCache]/[currentQuality]
     * 等播放态，避免影响正在播放的曲目。
     */
    private suspend fun resolveInputForPrefetch(song: Song): AudioInput? {
        // 已下载的本地副本优先（等价于 resolveInput 的前两级缓存）
        downloads.fileFor(song.key)?.let { return AudioInput(path = it.absolutePath) }
        if (settings.current.cacheLimitMb != 0) {
            musicCache.fileFor(song.key)?.let { return AudioInput(path = it.absolutePath) }
        }
        online.resolvePlayback(song, qualityFor(song))?.let { return it }
        // 网易云直链解析失败（VIP/无权限）时提前跑 B 站搜索，这才是桌面端比手机端慢的根源。
        if (song.source == MediaSource.NETEASE && settings.current.neteaseAutoSourceSwitch) {
            return neteaseAutoSource.resolve(song)
        }
        return null
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
        // 引擎即将释放：只清同步倍率标记，不再向已释放的引擎下发速率
        discardListenTogetherRate()
        crossfadeJob?.cancel()
        finishCrossfade()
        engine.release()
        // 清空/停止时让在飞的解析与歌词加载（共用播放代次）全部作废，避免它们的回调
        // 在队列清空后又 open 音频、写 currentPlayingKey 或歌词，出现「空队列却在播旧歌」。
        playbackGeneration++
        currentPlayingKey = null
        playbackStartedAt = 0L
        _currentAudioInput.set(null)
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
        // 磁盘 LRU 缓存：cacheLimitMb=0 表示禁用（不读也不写）
        if (settings.current.cacheLimitMb != 0) {
            musicCache.fileFor(song.key)?.let { file ->
                musicCache.touch(song.key)
                return@withLock AudioInput(path = file.absolutePath)
            }
        }
        // 引擎侧直链播放失败后会申请一次 B 站回退：本次跳过直链缓存，直接从 B 站音源开始
        val engineFailureRetry = song.source == MediaSource.NETEASE &&
            neteaseAutoSource.consumeEngineFailureRetry(song.key)
        if (!engineFailureRetry) {
            // 命中预解析结果：切歌省去同步解析/换源等待。缓存带 TTL 与 song.key 校验，
            // 过期或目标变化时 consume 返回 null，自然回退到下面的完整解析链路。
            // 一起听：有房间共享流时绝不用本地预解析地址覆盖——共享流是权威来源，
            // 且它的直链可能与本地解析结果不同（控制者版本/源不同）。
            val prefetched = if (online.hasSharedStreamFor(song)) null else nextPrefetchCache.consume(song.key)
            prefetched?.let { hit ->
                println("[prefetch] 命中下一首预解析地址：${song.displayName()} key=${song.key}")
                resolvedCache[song.key] = hit
                return@withLock hit
            }
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
            // 播放页「切换音质」的临时档位只作用于这首歌（按 key），不写全局偏好
            val effectiveQuality = qualityFor(song)
            val resolved = online.resolvePlayback(song, effectiveQuality)
            if (resolved != null) {
                resolvedCache[song.key] = resolved
                if (song.key == _currentSong.value?.key) _currentQuality.value = effectiveQuality
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
            val resolved = online.resolvePlayback(song, qualityFor(song))
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
        // 换源重建必须带着中断前的进度：seek 失败触发的 fallback 若从 0 重新 open，
        // 听众会被房间房态重新对齐、显示「跳回 0 再跳回来」。engine 快照的断点已被
        // 中断处理写在 positionMs 上，直接用它续播，用户几乎无感。
        val resumeMs = engine.snapshot.value.positionMs.coerceAtLeast(0L)
        scope.launch { playCurrent(startMs = resumeMs) }
    }

    private suspend fun loadLyricsFor(song: Song, generation: Int) {
        AppLog.debug(
            "player",
            "歌词加载开始：key=${song.key} 代次=$generation 当前代次=$playbackGeneration 音源=${song.source}",
        )
        _lyricsLoading.value = true
        val loaded = lyricsRepository.load(song)
        // 歌词加载同样可能跨越一次切歌：用播放代次裁决，过期的加载既不写歌词，
        // 也不能把 loading 标志清成 false 覆盖新一轮的加载中状态。
        if (generation != playbackGeneration) {
            AppLog.debug(
                "player",
                "丢弃过期歌词结果：key=${song.key} 当前代次=$playbackGeneration 本代次=$generation 行数=${loaded.lines.size}",
            )
            return
        }
        if (_currentSong.value?.key == song.key) {
            _lyrics.value = loaded
            if (loaded.lines.isEmpty()) {
                AppLog.info(
                    "player",
                    "歌词为空：key=${song.key} 本地文件=${song.filePath ?: "无"} 来源策略=${lyricsRepository.sourcePolicyProvider()}",
                )
            } else {
                AppLog.info(
                    "player",
                    "歌词加载完成：key=${song.key} 行数=${loaded.lines.size} 来源=${loaded.source ?: "未知"}",
                )
            }
        } else {
            AppLog.debug(
                "player",
                "歌词结果丢弃：当前曲目已变 key=${_currentSong.value?.key ?: "无"} 结果 key=${song.key}",
            )
        }
        _lyricsLoading.value = false
    }

    fun reloadLyrics() {
        val song = _currentSong.value ?: return
        lyricsRepository.invalidate(song)
        scope.launch { loadLyricsFor(song, playbackGeneration) }
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
        // 一起听软校正期间引擎跑的是「用户倍速 × 同步倍率」，用户改倍速时不能把同步抹掉。
        engine.setSpeed(effectiveSpeed)
        settings.updateDeferred { it.copy(playbackSpeed = clamped) }
    }

    /**
     * 一起听软同步：把临时同步倍率叠到用户倍速上。
     *
     * **不写 settings**——这是同步态，不是用户偏好；离开房间/暂停/切歌时必须调
     * [resetListenTogetherRate] 恢复。引擎不支持变速时直接忽略（调用方应改走硬 seek）。
     */
    fun applyListenTogetherRate(rate: Float) {
        val clamped = rate.coerceIn(
            ListenTogetherSyncPolicy.SOFT_SYNC_RATE_MIN,
            ListenTogetherSyncPolicy.SOFT_SYNC_RATE_MAX,
        )
        listenTogetherRate = clamped
        applyEffectiveSpeedToEngine()
    }

    /** 只清软校正标记，不碰引擎；用于「引擎即将重建/释放」的场合。 */
    private fun discardListenTogetherRate() {
        listenTogetherRate = 1f
    }

    /**
     * 结束软校正，同时把用户倍速写进引擎参数但**不重启**流水线。
     *
     * 调用方紧接着一定会 seek / 切歌触发重建，那才是速率真正生效的时机；
     * 这里要是走 [resetListenTogetherRate] 就会多一次重启（卡顿 + 爆音）。
     */
    private fun prepareSpeedForPipelineRestart() {
        listenTogetherRate = 1f
        if (engine.supportsSpeedChange) engine.setSpeedDeferred(effectiveSpeed)
    }

    /** 结束软校正，恢复到用户的倍速设置；重复调用无副作用。 */
    fun resetListenTogetherRate() {
        if (listenTogetherRate == 1f) return
        listenTogetherRate = 1f
        applyEffectiveSpeedToEngine()
    }

    /**
     * 软同步收敛后的倍速恢复：立刻把用户倍速热更新到引擎。
     *
     * 方法名沿用历史（当时以为恢复必须等重建），现在语义是「立即生效」，不是 deferred。
     *
     * Sonic 下 [AudioEngine.setSpeed] 只更新 sonicParam、不重建流水线，收敛时走这条路径
     * 和 Android 的 `resetListenTogetherSyncPlaybackRate()` 一样即时生效、无卡顿。
     *
     * 不能走 [prepareSpeedForPipelineRestart] 的 deferred：那条路径只改引擎的 speed 参数、
     * 不动 sonicParam，Sonic 实际仍按旧的临时倍速跑。Player 以为已经恢复用户倍速，
     * Sonic 却还在加速 → 本地进度继续跑过头 → drift 反向增大 → 触发硬 seek 回拉 1~2 秒。
     *
     * 暂停场景的 deferred 由 [pause] 单独调用 [prepareSpeedForPipelineRestart] 保留，
     * 避免淡出中的尾音被 setSpeed 重建/改写速率放大；两者调用点互不重叠。
     */
    fun finishSoftSyncDeferred() {
        resetListenTogetherRate()
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
