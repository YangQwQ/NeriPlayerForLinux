package moe.ouom.neriplayer.desktop.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import moe.ouom.neriplayer.desktop.net.OnlineRepository
import moe.ouom.neriplayer.desktop.sync.GitHubSyncManager
import moe.ouom.neriplayer.desktop.sync.SyncConfigStore

/** 全局依赖容器，替代 Android 端的 Application 级单例。 */
class AppContainer {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 播放控制（播放/暂停、上下一首、seek）的唯一入口。
     *
     * 命令层、全局快捷键、MPRIS 媒体键、托盘菜单都调它，由它判断「房间里是听众 → 转发房间 /
     * 否则本地执行」。这些入口原先各自直接调 [PlayerManager]，导致房间转发只对走 IPC 的入口生效。
     */
    val playbackCommands = PlaybackCommandRouter(this)

    val settings = SettingsRepository()
    val library = LibraryRepository(settings)
    val playlists = PlaylistRepository()
    val history = HistoryRepository()
    val stats = StatsRepository(history)
    val online = OnlineRepository()
    val accounts = AccountRepository(online.httpService)
    val syncConfig = SyncConfigStore()
    val downloadCatalog = DownloadCatalog()
    val downloads = DownloadManager(
        online = online,
        settings = settings,
        catalog = downloadCatalog,
        scope = scope,
        lyricsProvider = { song -> lyrics.load(song).raw.takeIf { it.isNotBlank() } },
    )
    val mpris = MprisService(
        snapshotProvider = {
            buildNowPlayingSnapshot(
                song = player.currentSong.value,
                durationMs = player.durationMs.value,
                positionMs = player.positionMs.value,
                playing = player.state.value == PlaybackState.PLAYING,
            )
        },
        // MPRIS 回调是 D-Bus 线程上的同步接口，播放控制统一交给 playbackCommands 判断
        // 「房间里是听众就转发房间」，不能直接调 player（否则房间里的媒体键会被同步覆盖）。
        onPlayPause = { scope.launch { playbackCommands.togglePlayPause() } },
        onPlay = { scope.launch { playbackCommands.play() } },
        onPause = { scope.launch { playbackCommands.pause() } },
        onNext = { scope.launch { playbackCommands.next() } },
        onPrevious = { scope.launch { playbackCommands.previous() } },
        onStop = { scope.launch { playbackCommands.pause() } },
        onSeekBy = { deltaMs -> scope.launch { playbackCommands.seekBy(deltaMs) } },
        onSeekTo = { positionMs -> scope.launch { playbackCommands.seekTo(positionMs) } },
        volumeProvider = { player.volume.value.toDouble() },
        onVolumeChange = { player.setVolume(it.toFloat()) },
        shuffleProvider = { player.shuffle.value },
        onShuffleChange = { player.setShuffle(it) },
        repeatProvider = { player.repeatMode.value },
        onRepeatChange = { player.setRepeatMode(it) },
    )
    val sync = GitHubSyncManager(
        configStore = syncConfig,
        playlists = playlists,
        history = history,
        stats = stats,
        library = library,
        http = online.httpService,
    )
    val neteaseLogin = moe.ouom.neriplayer.desktop.net.NeteaseLogin(online.httpService)
    val biliLogin = moe.ouom.neriplayer.desktop.net.BiliLogin(online.httpService)
    val lyrics = LyricsRepository { song -> online.lyricsStructured(song) }.also {
        // 来源策略每次 load 都读当前设置，让设置页切换「歌词来源」立即生效。
        it.sourcePolicyProvider = { LyricSourcePolicy.from(settings) }
    }
    val player = PlayerManager(
        settings = settings,
        history = history,
        stats = stats,
        lyricsRepository = lyrics,
        online = online,
        scope = scope,
        downloads = downloadCatalog,
    )

    /**
     * 一起听管理器在 ListenTogetherApi 里创建（需要 broadcast 回调），那里随后回填到这里。
     * PlayerApi 用它判断「当前是房间听众」并拦截本地队列直改，避免改动被同步静默覆盖。
     */
    @Volatile
    var listenTogether: ListenTogetherManager? = null

    fun bootstrap(scanLibrary: Boolean = true) {
        library.load()
        downloadCatalog.load()
        accounts.load()
        playlists.load()
        history.load()
        stats.load()
        player.attachSnapshotFlow()
        player.restoreLastQueue()
        scope.launch(Dispatchers.IO) { refreshAccountProfiles() }
        observeLocalChangesForAutoSync()
        startMprisBridge()
        if (scanLibrary) {
            scope.launch { library.scan() }
        }
    }

    private var localRevision = 0L

    /** 播放状态变化时同步给 MPRIS；并按设置启停服务。 */
    private fun startMprisBridge() {
        scope.launch {
            combine(
                player.currentSong,
                player.state,
                player.volume,
                player.shuffle,
                player.repeatMode,
            ) { song, state, volume, shuffle, repeat ->
                listOf(song?.key, state, volume, shuffle, repeat).joinToString("|")
            }.collect {
                if (mpris.running) mpris.refresh()
            }
        }
        scope.launch {
            settings.state
                .map { it.mprisEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) {
                        mpris.start()
                    } else {
                        mpris.stop()
                    }
                }
        }
    }

    /** 本地歌单 / 历史 / 统计变化后，若开启了自动同步则静默同步一次。 */
    private fun observeLocalChangesForAutoSync() {
        scope.launch {
            combine(playlists.playlists, history.entries, stats.stats) { p, h, s ->
                Triple(p.size, h.size, s.size)
            }.collect { localRevision += 1 }
        }
        scope.launch {
            var lastSyncedRevision = -1L
            while (true) {
                delay(15_000)
                val config = syncConfig.current
                if (!config.autoSync || !config.configured) continue
                if (lastSyncedRevision < 0L) {
                    // 启动后的第一次循环只记录基线，避免刚打开就上传
                    lastSyncedRevision = localRevision
                    continue
                }
                if (localRevision == lastSyncedRevision) continue
                sync.performSync()
                lastSyncedRevision = localRevision
            }
        }
    }

    /**
     * 登录时若账号信息接口临时失败，会以「只有 Cookie」的形式保存登录态；
     * 这里在启动时补拉一次昵称与 UID，让界面与「我的歌单」恢复正常。
     */
    private fun refreshAccountProfiles() {
        accounts.accountOf(MediaSource.NETEASE)
            ?.takeIf { it.nickname.isBlank() || it.userId.isBlank() }
            ?.let { runCatching { neteaseLogin.fetchProfile() }.getOrNull() }
            ?.let { accounts.save(MediaSource.NETEASE, it, "netease") }
        accounts.accountOf(MediaSource.BILIBILI)
            ?.takeIf { it.nickname.isBlank() || it.userId.isBlank() }
            ?.let { runCatching { biliLogin.fetchProfile() }.getOrNull() }
            ?.let { accounts.save(MediaSource.BILIBILI, it, "bilibili") }
    }
}
