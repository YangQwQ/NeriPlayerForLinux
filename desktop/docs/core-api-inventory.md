# 核心 API 与数据模型 · 索引

> 本文档是**索引**，不再逐条罗列历史 API 盘点。需要细节时按下方路径直接读源码；
> 命令与事件的协议说明见同目录 `protocol-*.md`。

## 1. 核心对象图

`AppContainer`（`core/AppContainer.kt`）在构造时创建全部仓储与服务，单向依赖、无循环：

```
AppContainer
 ├─ SettingsRepository      core/Storage.kt（AppSettings + JsonFileStore）
 ├─ LibraryRepository       core/Library.kt（扫描/索引）
 ├─ PlaylistRepository      core/Playlists.kt
 ├─ HistoryRepository       core/History.kt
 ├─ StatsRepository         core/Stats.kt
 ├─ OnlineRepository        net/OnlineRepository.kt（含 HttpService、各平台 API）
 ├─ LyricsRepository        core/Lyrics.kt
 ├─ DownloadCatalog/Manager core/Download.kt
 ├─ PlayerManager           core/Player.kt（唯一播放状态源）
 │   └─ AudioEngine         core/AudioEngine.kt（Ffmpeg 主 / JavaSound 回退）
 ├─ SyncConfig + GitHubSync sync/（GitHubSyncManager、SyncMerger…）
 └─ StatePublisher          backend/StatePublisher.kt（StateFlow/消息 → 广播）
```

## 2. 后端命令注册位置

所有命令在 `backend/api/*.kt` 中以 `server.on("<domain>.<method>") { params -> ... }` 注册，
由 `backend/BackendMain.kt` 统一 `register(...)`。

| 域 | 文件 |
| --- | --- |
| `app.*` / `settings.*` | `backend/api/AppApi.kt` |
| `player.*` | `backend/api/PlayerApi.kt` |
| `library.*` | `backend/api/LibraryApi.kt` |
| `collections.*` / `history.*` / `stats.*` | `backend/api/CollectionsApi.kt` 等 |
| `online.*` / `login.*` / `artwork.*` | `backend/api/OnlineApi.kt`、`LoginApi.kt`、`ArtworkApi.kt` |
| `download.*` / `sync.*` / `comments.*` / `ytmusic.*` / `listenTogether.*` | 同名 `*Api.kt` |

查全部命令：

```bash
grep -rhoE 'server\.on\("[^"]+"' desktop/src/main/kotlin/moe/ouom/neriplayer/desktop/backend/api/ \
  | sed 's/server.on("//' | sort -u
```

## 3. 状态推送（QML 订阅面）

`StatePublisher.kt` 把核心 `StateFlow` 与 `SharedFlow` 转成协议事件广播：

- **状态类**：`player`（播放快照）、`library.changed`、`library.scan`、`download.tasks`、`download.items`、
  `sync.state`、`settings`、`theme`、`accounts`、`collections.changed`、`listenTogether.*` 等。
- **消息类**：`player.messages`（`SharedFlow`，无 replay）→ 广播为 `toast` 事件。

约定：QML 只通过 `Api.on(name, fn)` 订阅；事件名必须写进对应 `protocol-*.md`。

## 4. `AppSettings` 字段（唯一设置来源）

定义在 `core/Storage.kt` 的 `data class AppSettings`，约 100 个字段，按区块注释分组：

| 区块 | 关键字 |
| --- | --- |
| 主题/界面 | `themeSeedColor` `dynamicColor` `darkMode` `paletteStyle` `colorSpec` `uiScale` |
| 字体与背景 | `appFontFamily` `lyricFontFamily` `appBackgroundImage` `appBackgroundBlur` `appBackgroundDim` |
| 语言 | `language`（`zh-CN` / `en`） |
| 播放 | `volume` `playbackSpeed` `pitchSemitone` `loudnessEnhancer` `equalizer*` `fadeEnabled` `fadeInMs` `fadeOutMs` `crossfadeEnabled` `crossfadeMs` |
| 歌词 | `lyricsFontScale` `showLyricTranslation` `lyricsAlignment` `lyricsHighlight` `lyricsHighlightDuration` `lyricsScrollEasing` `lyricsScrollDuration` |
| 悬浮歌词 | `floatingLyrics*`（启用/锁定/颜色/字号/位置比例等） |
| 播放页 | `coverShowsLyrics` `nowPlayingCoverShape` `nowPlayingBackground` `nowPlayingAutoHide*` `nowPlayingCoverRotate`… |
| 媒体库/首页 | `musicFolders` `homeCards` `homeGridMode` `libraryViewMode` |
| 下载 | `downloadDirectory` `downloadQuality` `downloadConcurrency` `downloadNotifyOnComplete` |
| 系统集成 | `closeToTray` `minimizeToTray` `notifyOnSongChange` `mprisEnabled` `shortcuts` |
| 音源 | `neteaseEnabled` `bilibiliEnabled` `youtubeEnabled` `neteaseAutoSourceSwitch` `qualityPreference` |

**关键约束**：`settings.set` 用严格 `@Serializable` 反序列化，未知字段会被丢弃。
新增设置项必须同时加进 `AppSettings`，否则前端写入无效。

## 5. 数据模型

`@Serializable` 模型集中在 `core/Models.kt`（`Song` / `MediaSource` 等）与各仓储文件内。
非 `@Serializable` 的内部类型（扫描态、同步态等）通过 `StatePublisher` 手工组装 JSON。

## 6. 线程模型

- 命令处理是 `suspend`，磁盘/网络用 `withContext(Dispatchers.IO)`。
- `SettingsRepository.update()` 会整文件写盘；高频设置（音量等）用 `updateDeferred()` 做 400ms 去抖。
- 音频写入是独立线程（`neri-audio-reader`），与命令线程通过 `@Volatile`/`AtomicReference` 同步。
