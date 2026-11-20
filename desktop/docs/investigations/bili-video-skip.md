# B站视频跳过规则功能调查（纯调查，不涉及代码改动）

> 目的：评估把参考项目 NeriPlayer-Desktop 的「B站视频自定义跳过区间」功能移植到本仓库桌面版（Kotlin 后端 + Qt/QML 前端）的可行性、工作量与风险。重点评估播放中自动 seek 与现有「内存缓存 seek」链路是否冲突。
>
> 参考项目：`/home/yang/Git/NeriPlayer-Desktop`（下称「参考实现 / Desktop 版」）。
> 本仓库：`/home/yang/Git/neriplayer`（下称「本项目 / 桌面版」）。

---

## 0. 先说清楚：这个功能到底是什么（面向用户）

如果你在 B站听一首歌（比如某首歌的 MV、演唱会切片、带口播的投稿视频），视频里经常有你不想听的片段：前奏广告、UP 主的开场闲聊、副歌前的「点赞投币」，或者某段纯乐器间奏。这个功能让**用户自己给某个具体视频（精确到分 P）标记若干段"跳过区间"**，比如 `00:00–00:25`（片头口播）和 `03:10–03:40（赞助）`。之后每次播放这个视频，播放器会在进度走到区间起点时**自动快进到区间终点**，用户完全不用手动拖进度条。

几个对用户而言的关键点：

- 规则是**用户手动标注的、按"视频 + 分 P"保存的**，不是算法自动识别的，也不依赖任何第三方字幕/赞助数据。一个视频一个分 P 一条规则，可以有多段区间。
- 编辑器里可以一边播放一边"取当前时间"作为区间起点/终点，还有 ±1s/±5s 微调按钮和播放/暂停，方便精确卡点（参考 `src/components/BiliVideoSkipDialog.vue`）。
- 规则会随用户数据一起同步（Desktop 版通过它的 user-data 同步机制），清除区间等于给该目标写一条"删除墓碑"，避免同步时旧规则复活。
- **注意：B站本身还有另一个独立功能叫 SponsorBlock**（见 §1.5）：那是社区众包的广告/片头片段数据库，按 `bvid` 的哈希去第三方服务器查询，用户不需要自己标。两者容易混淆：本文调查的「视频跳过规则」是**用户自定义、本地规则**；SponsorBlock 是**云端共享规则**。Android 版两者都有，Desktop 版目前只移植了用户自定义规则。

一句话总结功能价值：**把"每次听歌都要手动拖过片头/口播"变成一次性标注、永久自动跳过**，是纯播放体验增强，不影响音源解析本身。

---

## 1. 参考实现（NeriPlayer-Desktop）的完整工作机制

### 1.1 涉及文件

| 文件 | 职责 |
| --- | --- |
| `src/modules/playback/biliVideoSkip.ts` | 纯逻辑：类型定义、BVID/CID 解析、区间/规则归一化、匹配、自动跳过 tracker |
| `src/stores/biliVideoSkip.ts` | Pinia store：规则的内存态 + 持久化 + 同步合并 |
| `src/components/BiliVideoSkipDialog.vue` | 编辑弹窗：选分 P、输入/微调区间、保存 |
| `src-tauri/src/library/bili_video_skip.rs` | Rust 侧：规则的数据模型、归一化、读写 SQLite / user-data 扩展字段 |
| `src-tauri/src/commands/user_data_cmd.rs` | Tauri commands：`get_bili_video_skip_rules` / `get_bili_video_skip_targets` / `set_bili_video_skip_rule` |
| `src/stores/player.ts` | 播放链路集成：识别当前曲目的 BVID/CID、从 position 事件触发自动跳过 |

### 1.2 规则定义与数据结构

核心模型（`biliVideoSkip.ts:1-19`、`bili_video_skip.rs:14-41`）：

```ts
interface BiliVideoSkipTarget { bvid: string; cid: number }        // 目标 = 视频 + 分 P
interface BiliVideoSkipInterval { startMs: number; endMs: number } // 一段跳过区间（毫秒）
interface BiliVideoSkipRule extends BiliVideoSkipTarget {
  intervals: BiliVideoSkipInterval[]
  modifiedAt: number      // 毫秒时间戳，用于冲突合并与删除墓碑
  isDeleted: boolean      // 删除标记（清空区间时写墓碑，防同步旧数据复活）
}
```

限制：单目标最多 100 段区间、最多 2000 条规则（TS 与 Rust 两处常量一致），单段时间上限 86400 秒（`biliVideoSkip.ts:17-20`、`bili_video_skip.rs:10-12`）。

区间归一化（`normalizeBiliSkipIntervals` / Rust `normalize_intervals`）：裁剪到 `[0, duration]`、丢弃 `end <= start` 的无效段、按起点排序、**相邻/重叠区间合并**、截断到 100 段。规则归一化（`normalizeBiliVideoSkipRules` / Rust `normalize_rules`）：按 `(bvid, cid)` 去重，`modifiedAt` 大者胜；同时间时删除墓碑优先于活规则，活规则则合并区间；按 bvid/cid 排序并截断到 2000 条。TS 与 Rust 两端逻辑严格对齐（Rust 的测试里有 "follow android clamping" 字样，说明这一套规则源自 Android 端）。

### 1.3 BVID / CID 从哪来（关键）

目标是 `(bvid, cid)` 二元组，**cid 是分 P 的 cid，不是视频 aid**。来源分两条路：

1. **从当前曲目元数据直接解析**（`resolveBiliVideoSkipBvid/Cid`，`biliVideoSkip.ts:63-94`）：
   - BVID：`syncPayload.album` 或 `track.album` 中 `Bilibili|<cid>|<bvid>` 格式串的第 3 段 → `syncPayload.bvid` → `audioId/audio_id`（形如 `BVxxx`）→ 从 `track.id` 的 `bilibili:` 前缀中剥出。
   - CID：`syncPayload.subAudioId/sub_audio_id` → `Bilibili|<cid>|<bvid>` 的第 2 段。
2. **播放中兜底解析**（`prepareBiliVideoSkipTrack`，`player.ts:1275-1295`）：若曲目没带齐 cid（例如**缓存播放、下载播放绕过了取流解析**），用 `get_bili_video_skip_targets(bvid)` 调 Rust 侧 `BiliApi.get_video_info(bvid)`，拿到该视频全部分 P 列表（每个分 P 的 `cid`、`part` 标题、`duration`），按已有 cid 或第一个分 P 补全；补完后**把 `Bilibili|<cid>|<bvid>` 写回 track.album 和 syncPayload 并落盘**（`player.ts:1312-1328`），保证下次缓存/下载播放仍能命中同一条规则。这条兜底只在当前曲目确实被判定为 B站轨（`isBiliVideoSkipTrack`）且**不在一起听房间**时执行（房间曲目身份以权威状态为准）。

分 P 列表的数据源在 Rust 的 `BiliVideoInfo.pages`（`bili_video_skip.rs:43-61` 的 `target_options`），返回给弹窗做下拉选择。

### 1.4 播放中如何应用跳过（自动 seek 的时机与防抖）

编辑弹窗保存规则后，真正的自动跳过发生在**播放器前端的 position 事件处理里**（`player.ts:1331-1351`、`1375-1376`、`2172`）：

1. 每次收到后端的 `player:position` 事件（且在 seek 后的保护窗口内会先过滤旧位置事件），调用 `maybeAutoSkipBiliVideoInterval(currentPositionMs, durationMs)`。
2. 前置守卫很严格：必须是 B站轨、**正在播放、非加载中、非 pendingSeek、规则匹配 generation 未过期、不在一起听房间**，才继续。
3. 用 `intervalsForBiliVideoSkipPlayback` 按当前 `(bvid, cid)` 取对应规则（目标不全时用单个 fallback cid/bvid 匹配，且只在候选唯一时才用，避免误跳），再用 `BiliVideoSkipTracker.nextSkipPosition` 算出应该跳到的位置。
4. `nextSkipPosition` 的算法（`biliVideoSkip.ts:181-216`）：维护 `skippedIntervals` 集合，**同一段区间在一次播放中只跳一次**；检测到进度回退超过 1 秒（用户手动拖回）时清空已跳集合，允许重跳；用 do-while 处理连续/重叠区间，一次跳到覆盖范围的最终终点。
5. **跳转目标若落在曲尾**（`skipEndMs >= durationMs`），不调 `seekTo`，而是调 `handleTrackEnded()` 复用正常的播完切歌/循环/睡眠定时逻辑（`player.ts:1344-1348`）；否则 `seekTo(skipEndMs, 'local_safety')`。切歌/换目标时 `tracker.reset()`。

### 1.5 与 SponsorBlock 的区别

| 维度 | 视频跳过规则（本文） | SponsorBlock（Android 版，本仓库 `modules/`） |
| --- | --- | --- |
| 数据来源 | 用户自己在弹窗里标注，存本地/随用户数据同步 | 第三方社区数据库 `https://bsbsb.top/api/skipSegments/`，按 `bvid` 的 SHA-256 前缀哈希查询（`BiliSponsorBlockClient.kt:40`、`biliSponsorBlockHashPrefix`） |
| 标识粒度 | `(bvid, cid)`，精确到分 P | 只有 `bvid`（SponsorBlock 协议是按视频的） |
| 区间内容 | `startMs/endMs`，无分类 | `BiliSponsorBlockSegment(uuid, category, startMs, endMs)`，带 `category`（sponsor/intro/outro 等） |
| 保存方式 | 用户规则，可增删改、有删除墓碑 | 查询结果，按设置开关启用 |
| 跳过算法 | `BiliVideoSkipTracker` | `BiliSponsorBlockSkipTracker`（同构：1 秒回退容差、连续段合并、按 uuid 去重，`BiliSponsorBlockSkipPolicy.kt`） |

两者算法骨架几乎一样，但**数据获取与身份键不同**。本项目桌面版目前**两者都没有**（Android 版有 SponsorBlock），本次只评估用户自定义规则这一项。

---

## 2. 本项目桌面版现状盘点

### 2.1 B站音源是否已有 remoteId / cid —— **已有，且是现成的**

- `Song` 模型（`core/Models.kt:30-46`）有 `remoteId: String?` 与 `extraId: String?`。
- 所有 B站音源构造点都写好了：`remoteId = bvid`，`extraId = cid`（部分来源有）：
  - `net/OnlineApis.kt:667-683` `mapSearchResults`（搜索结果）：`remoteId = bvid`，**但没写 extraId**；
  - `net/OnlineApis.kt:817-839` `musicHotVideos`、`841-861` `musicRankSong`：`remoteId = bvid` + `extraId = cid`；
  - `favoriteFolderSongs`（收藏夹）同理（`836`、`859` 行附近的同构代码）。
- 播放取流 `net/OnlineRepository.kt:103-110`：`val bvid = song.remoteId; val cid = song.extraId ?: bilibili.videoPages(bvid).firstOrNull()?.first; bilibili.audioUrl(bvid, cid)` —— **即 cid 缺失时本项目已经有"现查分 P 列表"的兜底**，和参考实现的兜底同源。
- `net/OnlineApis.kt:712-720` `videoPages(bvid)` 返回 `List<Pair<cid, part>>`，可直接用于弹窗的分 P 下拉框（对应参考的 `get_bili_video_skip_targets`）。

**结论：BVID/CID 数据链路是完整的，不需要为"能不能拿到目标"发愁。** 唯一要注意：搜索结果（`mapSearchResults`）不带 `extraId`，此时 `resolvePlayback` 会走 `videoPages()` 取第一个分 P；自动跳过应复用这个「解析后确定 cid」的动作，而不是只看 `Song.extraId`（否则搜索来源的 B站歌无法命中按分 P 存的规则）。这与参考实现「缓存/下载播放要补全实际分 P 身份」的动机完全一致。

### 2.2 seek 链路现状（内存缓存 seek）

- 前端命令：`backend/api/PlayerApi.kt:40-42` `player.seekTo(positionMs)` → `PlayerManager.seekTo`。
- 后端：`core/Player.kt:396-404`：
  ```
  seekTo(positionMs):
    engine.fadeReset(...)
    song = currentSong; cached = audioCachedKey
    seekInput = (song != null && cached == song.key && 缓存文件存在) ? AudioInput(path=current.audio) : null
    engine.seekTo(positionMs, seekInput)
  ```
- 引擎层：`core/AudioEngine.kt:242-250`（Ffmpeg）与 `645-...`（JavaSound）：`seekTo(positionMs, input)` 会**停掉当前解码管线（stopPipeline）并重新 `startPipeline(startMs=target)`**，用传入的 `input` 替换引擎当前音源。缓存文件位于 `/dev/shm/NeriPlayer/audio/current.audio`（内存文件系统，单文件，`Player.kt:121-158`），首播/切歌走 URL 直连，**仅 seek 时切到已缓存的本地文件**，从而瞬时跳转。
- 缓存的建立在 `core/Player.kt:533-555` `playCurrent()` 启动稳定后 `cacheCurrentForSeek()` 后台整首下载 + 预解析下一首直链。
- 状态广播：`backend/StatePublisher.kt:35-47` 每 250ms 对 `PlayerWire.state()` 去重后 `broadcast("player", state)`，`PlayerWire.state()` 含 `positionMs / durationMs / song`（`backend/PlayerWire.kt:55-73`）。前端播放页据此驱动进度条（可对照 `desktop/qt/qml/components/player/PlayerCore.qml`、`PlayerProgressSlider.qml`）。

### 2.3 存储与命令注册现状

- `core/Storage.kt` 提供 `JsonFileStore<T>`（原子写 tmp+rename）与各数据文件常量；规则可新增一个 `biliVideoSkipRules` JSON 文件，放 `dataDir`（随用户数据走）或 `configDir`。
- 新命令注册模式：`backend/api/*Api.kt` 里 `server.on("domain.method") {...}`，再在 `backend/BackendMain.kt` 注册（按 AGENTS.md，该文件由集成负责人改）；新命令/事件需同步 `desktop/docs/protocol*.md`。
- 本项目桌面版没有 user-data 同步扩展字段机制（参考实现把规则塞在 `archive_metadata.extensions` 里随同步走），若要做同步需要单独设计；首期可先只做本地持久化。

---

## 3. 本项目要实现需要补什么

### 3.1 后端（Kotlin）

1. **规则模型与存储**（新文件，建议 `core/BiliVideoSkip.kt` + `Storage.kt` 加一个文件路径）：
   - `@Serializable data class BiliVideoSkipRule(bvid, cid, intervals, modifiedAt, isDeleted)` 与 interval 模型；移植 TS/Rust 的归一化、去重、合并、100/2000 上限逻辑（用 Kotlin 写一份，与参考逻辑等价）。
   - 一个 `JsonFileStore<List<BiliVideoSkipRule>>` 仓库，负责 load/save；建议放 `dataDir`（跟随用户数据备份/迁移）。
2. **命令**（建议新增 `backend/api/BiliVideoSkipApi.kt`，并在 `BackendMain.kt` 注册）：
   - `biliVideoSkip.getRules` → 返回全部规则；
   - `biliVideoSkip.getTargets { bvid }` → 调 `online.bilibili.videoPages(bvid)` 返回分 P 列表（`pair.first`=cid，`pair.second`=part，duration 本项目的 `videoPages` 没返回时长，需扩展 `BiliApi.videoPages` 或复用参考里 `pagelist` 接口的 `duration` 字段；这是唯一需要在网络层补的小改动）；
   - `biliVideoSkip.setRule { bvid, cid, intervals, durationMs }` → 归一化 + 写盘 + 广播事件（如 `biliVideoSkip.changed`）。
3. **播放链路 hook（自动 seek 的执行点，两种方案）**：
   - **方案 A（推荐，与参考实现一致）：放在前端 QML。** 后端只负责规则 CRUD；前端在收到 `player` 事件的 `positionMs` 变化时，用当前 `song.source === 'BILIBILI'`、`song.remoteId`(bvid) 与解析出的 cid 查规则，调用现成的 `Api.call('player.seekTo', ...)`。优点：不碰后端播放核心，完全复用现有 seek 命令；缺点：position 事件是**每 250ms 采样并去重后广播**，跳过动作会有最多约 250ms 的滞后，且需前端自己维护"已跳区间/回退重置"状态。
   - **方案 B：放在后端 `PlayerManager`。** 在 `playCurrent()` 解析到 cid 后确定当前目标，在引擎 snapshot 位置推进的采集处（`positionMs` 的 derived flow / ticker）触发 `PlayerManager.seekTo(skipEndMs)`。优点：跳过更及时、不受前端事件节流影响；缺点：要改播放核心、`seekTo` 是引擎级重启动作（见 §4），且 `PlayerManager` 里已有多个 scope/状态（crossfade、pending seek）需要小心避让。
   - 两者都要处理：跳到曲尾时改走「下一首/播完」逻辑（`PlayerManager` 的 `handleTrackCompleted`，`Player.kt:870` 附近），而不是 seek 到 EOF；换歌/seek 时重置 tracker；只在 B站轨、播放中生效。

### 3.2 前端（Qt/QML）

1. **设置页入口**：`desktop/qt/qml/pages/SettingsPage.qml` + `desktop/qt/qml/components/settings/` 加一个开关（是否启用自动跳过）与说明；文案按 AGENTS.md 全部走 `I18n.tr("中文原文")` 并同步 `i18n/translations.js` 英文表。
2. **编辑弹窗**：新增 `desktop/qt/qml/components/panels/BiliVideoSkipDialog.qml`（面板挂载由 `PlayerOverlays.qml` 集成负责人接线，或直接从播放页 `NowPlayingPage.qml` 弹出），实现参考 `BiliVideoSkipDialog.vue` 的能力：分 P 下拉（`biliVideoSkip.getTargets`）、起止时间输入（mm:ss / h:mm:ss）、"用当前播放位置填入"、±1s/±5s 微调、播放/暂停、区间列表增删清空、保存。
3. **入口位置**：参考实现有两处——播放页按钮（NowPlaying）与曲目右键菜单（TrackContextMenu）。本项目可在播放页 `NowPlayingControls.qml` / `NowPlayingInfoRow.qml` 加一个"视频跳过"按钮（仅当前曲目是 B站源时显示），并与 `SettingsPage` 的开关联动。
4. **协议文档**：按 AGENTS.md 更新 `desktop/docs/protocol-online.md` 或新增 `protocol-bili-video-skip.md`，记录命令与 `biliVideoSkip.changed` 事件。

### 3.3 工作量粗估（人日）

| 模块 | 估计 |
| --- | --- |
| 规则模型 + 归一化 + 本地持久化（Kotlin） | 0.5–1 天 |
| 3 个后端命令 + 注册 + 协议文档 | 0.5 天 |
| `videoPages` 补 duration（网络层小改） | 0.2 天 |
| QML 编辑弹窗（含取当前时间、微调、分 P 选择、i18n） | 1.5–2 天 |
| 播放页入口 + 设置页开关 + 事件接线（含集成负责人协调） | 0.5 天 |
| 自动跳过触发逻辑（前端方案 A）或后端 hook（方案 B） | 0.5–1 天 |
| 联调与边界测试（回退、曲尾、缓存/下载播放、非 B站源、一起听） | 1 天 |
| **合计** | **约 5–7 人日**（方案 A 偏下限，方案 B + 同步功能另加 1–2 天） |

---

## 4. 风险评估：播放中自动 seek vs 内存缓存 seek（重点）

### 4.1 结论先行

**不冲突，但有一个必须做对的点：自动跳过必须走统一的 `PlayerManager.seekTo()` 入口，让它去选择"缓存文件 seek"或"URL 直连 seek"，绝不能在后端直接调 `engine.seekTo()` 绕过这层判断；同时要避开缓存文件正在写入的窗口。** 只要满足这两点，自动 seek 反而能受益于内存缓存（瞬时跳转、无网络等待）。

### 4.2 为什么天然兼容

- 内存缓存 seek 的选择逻辑集中在 `PlayerManager.seekTo()`（`core/Player.kt:396-404`）：它根据 `audioCachedKey == song.key` 且缓存文件存在，动态构造 `AudioInput(path=current.audio)` 传给引擎。任何从这个入口发起的 seek（用户拖进度条、previous、自动跳过）都走同一条路径，自动跳过只是**多了一个 seek 调用方**，不改变 seek 的实现。
- 两种 seek 的目标都是"同一首歌的某个位置"：跳过区间终点一定在 `[0, duration]` 内（规则归一化已裁剪），而 `engine.seekTo` 自身也会 clamp（`AudioEngine.kt:243`），与缓存文件覆盖的是同一首歌，语义一致。
- 缓存文件是**整首歌的完整字节**（`prefetchAudio` 用 `httpService.download` 下载完整音频再 rename，`Player.kt:141-158`），不是分段缓存，因此跳到任意区间终点时本地文件都能正确解码，不会像流式 Range 缓存那样出现"跳到未下载区域"的问题。

### 4.3 需要注意的边界与竞态（逐条）

1. **缓存建立时机与跳过时机可能重叠（最需要防的点）。** `cacheCurrentForSeek()` 在 `playCurrent()` 起播后异步执行，`prefetchAudio` 写文件前会先把 `audioCachedKey = null`（`Player.kt:149`）。如果自动跳过恰好在这个窗口触发：`seekTo()` 读到的 `cached` 为 null（或 song.key 不匹配），就会退回 **URL 直连 seek**——这仍然是**正确可播放**的（只是这次跳转不是瞬时的），不会跳错或失败。所以最坏情况是"某一次跳过慢一点"，不是功能错误。反过来，若跳过发生在缓存完成后，就自动享受瞬时 seek。
   - 若要更稳：可在 `seekTo()` 里当发现"目标歌有 URL 直链但缓存未就绪"时，跳过动作仍正常执行，不等待缓存（不要为了等缓存阻塞自动跳过，否则滞后更明显）。
2. **每次 seek 都会重启解码管线，自动跳过不是"无成本"的。** `AudioEngine.seekTo` → `startPipeline` → `stopPipelineLocked()` + `generation++` + 新开 SourceDataLine + 新起 ffmpeg 进程（`AudioEngine.kt:357-410`）。这是一次实打实的管道重建（有几百毫秒级的缓冲重建成本），并非轻量操作。因此：
   - 区间归一化必须合并重叠段、tracker 必须保证同一段只跳一次，避免在同一位置反复 seek；
   - 连续多段区间要靠 `nextSkipPosition` 的 do-while **一次跳到最终终点**，而不是每段各 seek 一次；
   - 曲尾区间要改走"下一首"逻辑，不要 seek 到 EOF 让解码器抛错。
   - 参考实现里 `seekTo(skipEndMs, 'local_safety')` 与 seek 后的 position 事件保护窗口（`player.ts:1370-1372` 的 `seekGuardUntil` / `shouldAcceptPositionAfterSeek`）就是为了防止"seek 后旧位置事件又把 tracker 往回带、造成连环跳"。本项目 QML 侧同样需要在 seek 后短时间内忽略旧的 `player.position` 事件（或后端在 seek 后暂停采样一拍），否则 250ms 采样 + 事件乱序可能导致重复触发。
3. **进度回退检测要兼容缓存 seek 的重置语义。** 参考 tracker 用"回退 > 1s 就清空已跳集合"，让用户拖回时还能再跳一次。本项目用户手动拖进度条也走同一个 `seekTo()`，所以 tracker 应当同时监听"用户主动 seek"（前端可记录手动 seek 调用）与"位置倒退"两种信号；否则用户想重听被跳过片段时会被永久拦住。
4. **cid 必须在跳过前确定。** B站搜索来源的 `Song` 没有 `extraId`（§2.1），若只按 `Song.remoteId/cid` 直读会匹配不到规则。应在 `playCurrent()` 解析输入时把实际用的 cid 回填到当前播放上下文（本项目 `Song` 是数据类，不能像参考实现那样改 `track.album` 并落盘——需要另存一张 `songKey -> (bvid, cid)` 的映射，或直接给 `Song` 加字段；前者改动小）。否则"搜索播放"和"缓存/下载播放"都无法命中按分 P 存的规则。
5. **`source == BILIBILI` 与"网易云自动换源到 B站"的区别。** 本项目的 B站音源有两类：用户主动选 B站源（`MediaSource.BILIBILI`，`Song.remoteId=bvid`）与网易云失败后 `NeteaseAutoSource` 兜底搜到的 B站视频（`core/NeteaseAutoSource.kt`）。后者 Song 的 `source` 仍是 `NETEASE`（只是播放输入来自 B站），按 `source` 过滤会漏掉；但按 bvid/cid 匹配规则其实对两类都成立。建议按"当前实际解析出的 (bvid, cid)"匹配，而不是按 `source` 枚举过滤，让自动换源的歌也能跳过——但这需要把兜底解析出的 bvid/cid 暴露给跳过模块（同上第 4 点）。
6. **一起听（ListenTogether）场景。** 参考实现明确在房间中禁用本地自动跳过，因为曲目身份以房间权威状态为准，本地跳会导致各端不一致。本项目桌面版已有一起听（`core/ListenTogetherManager.kt`），若做此功能应加同样的守卫。
7. **同步与冲突合并。** 参考实现用 `modifiedAt` + 删除墓碑防止旧同步快照覆盖新编辑（`biliVideoSkip.ts:137-160`、`bili_video_skip.rs:82-111`）。本项目桌面版若首期只做本地 JSON 存储可先不做；但规则结构里建议**一开始就带上 `modifiedAt/isDeleted`**，避免以后接同步时再改数据格式。

### 4.4 推荐实现顺序（降低风险）

1. 先做后端规则 CRUD + 本地存储 + QML 编辑弹窗（**不接自动跳过**），让用户能标注区间并验证数据正确落盘/读回；
2. 再做方案 A 的前端自动跳过：订阅 `player` 事件的 position，**统一调用 `Api.call('player.seekTo')`**，加 seek 后的事件保护窗口与 tracker 去重/回退重置；此时自动 seek 自动获得内存缓存 seek 的瞬时能力；
3. 补齐 cid 回填（搜索/缓存/下载/自动换源四种来源），确保规则命中；
4. 最后视需求接 SponsorBlock（另立任务，与本文功能独立）和用户数据同步（带上 modifiedAt/墓碑）。

---

## 5. 关键文件索引（便于后续实施）

参考实现：
- `NeriPlayer-Desktop/src/modules/playback/biliVideoSkip.ts`
- `NeriPlayer-Desktop/src/stores/biliVideoSkip.ts`
- `NeriPlayer-Desktop/src/components/BiliVideoSkipDialog.vue`
- `NeriPlayer-Desktop/src-tauri/src/library/bili_video_skip.rs`
- `NeriPlayer-Desktop/src-tauri/src/commands/user_data_cmd.rs`（159–192 行）
- `NeriPlayer-Desktop/src/stores/player.ts`（1275–1351、1370–1376、2172 行）

本项目：
- `desktop/src/main/kotlin/moe/ouom/neriplayer/desktop/core/Player.kt`（121–158 内存缓存、396–404 seekTo、494–565 起播与缓存、629–682 音源解析）
- `desktop/src/main/kotlin/moe/ouom/neriplayer/desktop/core/AudioEngine.kt`（26–34 AudioInput、242–250 与 645–657 seekTo、357–410 startPipeline）
- `desktop/src/main/kotlin/moe/ouom/neriplayer/desktop/backend/api/PlayerApi.kt`（40–42 seekTo 命令）
- `desktop/src/main/kotlin/moe/ouom/neriplayer/desktop/backend/PlayerWire.kt`（55–73 状态含 positionMs/song）
- `desktop/src/main/kotlin/moe/ouom/neriplayer/desktop/backend/StatePublisher.kt`（35–47 250ms 广播）
- `desktop/src/main/kotlin/moe/ouom/neriplayer/desktop/net/OnlineRepository.kt`（88–110 resolvePlayback，B站 remoteId/extraId）
- `desktop/src/main/kotlin/moe/ouom/neriplayer/desktop/net/OnlineApis.kt`（543–755 BiliApi；667–683 搜索结果；712–720 videoPages；817–861 榜单/收藏）
- `desktop/src/main/kotlin/moe/ouom/neriplayer/desktop/core/Models.kt`（30–46 Song）
- `desktop/src/main/kotlin/moe/ouom/neriplayer/desktop/core/Storage.kt`（52–74 JsonFileStore）
- `desktop/src/main/kotlin/moe/ouom/neriplayer/desktop/core/NeteaseAutoSource.kt`（自动换源兜底）
- QML：`desktop/qt/qml/pages/SettingsPage.qml`、`desktop/qt/qml/pages/NowPlayingPage.qml`、`desktop/qt/qml/components/player/`、`desktop/qt/qml/components/settings/`、`desktop/qt/qml/components/panels/`

Android 参考（SponsorBlock 对照）：
- `modules/platform/src/main/java/moe/ouom/neriplayer/platform/bilibili/api/sponsorblock/BiliSponsorBlockClient.kt`
- `modules/playback/logic/src/main/java/moe/ouom/neriplayer/core/player/policy/skip/BiliSponsorBlockSkipPolicy.kt`
