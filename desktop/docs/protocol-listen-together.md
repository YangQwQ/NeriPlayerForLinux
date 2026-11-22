# 一起听（Listen Together）协议

桌面版「一起听」与 Android 客户端共用同一个 **LTW 服务端**协议
（`np-submodule/NeriPlayer-LTW`，Cloudflare Worker + Durable Object）。
本文档说明：

1. 桌面后端 ⇄ Qt 前端的 IPC 命令与事件（`listenTogether.*`）；
2. 桌面后端 ⇄ LTW 服务端的 HTTP / WebSocket 协议（与 Android 完全一致的字段子集）；
3. 持久化文件、错误码、兼容性核对与不支持项。

参考实现：

- 服务端：`np-submodule/NeriPlayer-LTW/src/worker.js`、`README.md`
- Android 客户端：`modules/model/.../data/model/ltw/`、`modules/listentogether/`
  （`api/ltw/http`、`api/ltw/ws`、`data/ltw/{control,mapping,playback,session}`）
- 桌面实现：`desktop/src/main/kotlin/.../core/ListenTogether{Models,Mapping,Manager}.kt`、
  `.../net/ListenTogetherClient.kt`、`.../backend/api/ListenTogetherApi.kt`
- 前端：`desktop/qt/qml/components/panels/ListenTogetherPanel.qml`、
  `desktop/qt/qml/components/listentogether/`

---

## 1. Qt 前端 ⇄ 桌面后端（IPC）

命令注册在 `backend/api/ListenTogetherApi.kt`，由集成方在 `BackendMain.kt` 调用
`ListenTogetherApi.register(server, container)`。所有命令都返回同一份 **合并快照**
（`ListenTogetherSnapshot`），失败时通过快照里的 `error` 字段返回可读错误（不抛异常）。
状态变化通过 `listenTogether.state` 事件推送（同一结构）。

### 1.1 合并快照 `ListenTogetherSnapshot`

```jsonc
{
  "configured": true,                 // 是否已配置服务端地址
  "serverUrl": "https://example.workers.dev",
  "defaultServerUrl": "https://neriplayer.hancat.work",
  "nickname": "桌面用户",
  "userUuid": "安装级随机 UUID",
  "connection": "connected",          // disconnected/connecting/connected/reconnecting/error
  "connected": true,
  "wsConnected": true,
  "roomId": "ABC234",                 // 不在房间时字段省略（null）
  "role": "controller",               // controller/listener
  "isController": true,
  "selfUserUuid": "…",
  "inviteLink": "neriplayer://listen-together/join?roomId=…&secret=…",
  "members": [ { "userUuid": "…", "nickname": "…", "userId": "…", "role": "listener", "joinedAt": 1700000000000 } ],
  "controllerUserUuid": "…",
  "queue": [ { "stableKey": "netease:123", "channelId": "netease", "audioId": "123",
               "name": "…", "artist": "…", "album": "…", "durationMs": 0, "coverUrl": null,
               "streamUrl": null, "streamUrls": [] } ],
  "currentIndex": 0,
  "track": { /* 同 queue 元素 */ },
  "playback": {
    "state": "playing",               // playing/paused
    "positionMs": 12345,              // 按服务端时钟推算的当前进度
    "durationMs": 240000,
    "serverNowMs": 1700000000000,
    "basePositionMs": 0,
    "repeatMode": 0,                  // 0 关闭 / 1 单曲 / 2 列表
    "shuffleEnabled": false
  },
  "settings": { "allowMemberControl": true, "autoPauseOnMemberChange": true, "shareAudioLinks": true },
  "roomStatus": "active",             // active/controller_offline/closed
  "version": 12,
  "autoPauseOnJoin": false,
  "sharedStreamAvailable": true,      // 房间当前曲目有可用共享直链（shareAudioLinks 开启且 streamUrl 非空）
  "usingSharedStream": true,          // 听众当前正在用房间共享直链播放
  "error": null,                      // 最近一次错误（中文可读文案）
  "notice": "…"                       // 非错误提示（重连、控制者离线等）
}
```

> `explicitNulls = false`：值为 null 的可选字段在 JSON 里直接省略，QML 用 `undefined` 判空。

### 1.2 命令

| method | params | 说明 |
| --- | --- | --- |
| `listenTogether.status` | — | 返回当前合并快照；面板打开 / 轮询进度用 |
| `listenTogether.configure` | `{serverUrl?, nickname?}` | 保存服务端地址（http/https，自动去尾部 `/`）与默认昵称；不在房间时顺带做 `/healthz` 可用性提示。`serverUrl` 传空串表示清空配置 |
| `listenTogether.createRoom` | `{nickname?}` | 用当前播放器队列创建房间，自己成为控制者（初始快照来自 `container.player`）。当前歌曲必须是可分享的在线歌曲（本地歌曲拒绝） |
| `listenTogether.joinRoom` | `{inviteOrRoomId, nickname?}` | 加入房间：接受邀请链接（`neriplayer://listen-together/join?roomId=…&secret=…`，链接里可含 `baseUrl`/`inviter`）或 6 位房间号。首次加入必须用邀请链接（需要 `joinSecret`）；房间号适用于重连已有身份（`memberSecret`） |
| `listenTogether.leaveRoom` | — | 显式离开（HTTP `POST /leave`）并清空本地会话 |
| `listenTogether.requestControl` | `{action, positionMs?, repeatMode?, shuffleEnabled?, track?, index?, fromIndex?, toIndex?}` | 听众发起控制请求。播放类 `action`：`play`/`pause`/`seek`/`playbackMode`/`next`/`previous`；队列类 `action`：`addToQueue`/`removeFromQueue`/`moveInQueue`（见 1.5）。服务端在 `allowMemberControl` 且控制者在线时自动仲裁 |
| `listenTogether.sendControl` | `{type, …事件字段}` | 原样提交控制事件（`PLAY`/`PAUSE`/`SEEK`/`PLAYBACK_MODE`/`SET_TRACK`/`SET_QUEUE`/`HEARTBEAT`/`UPDATE_SETTINGS`/`REQUEST_*`）；缺省字段由后端用本地播放器补齐 |
| `listenTogether.updateSettings` | `{allowMemberControl?, autoPauseOnMemberChange?, shareAudioLinks?}` | 控制者修改房间设置（走 `UPDATE_SETTINGS`，仅控制者可用） |

### 1.3 事件

| event | data | 触发时机 |
| --- | --- | --- |
| `listenTogether.state` | `ListenTogetherSnapshot` | 连接状态、房态版本、成员、设置、错误/提示变化时 |

### 1.4 QML 使用示例

```qml
Component.onCompleted: {
    Api.on("listenTogether.state", onState)
    Api.get("listenTogether.status", {}, function(result, error) {
        if (!error) panel.status = result
    })
}
Component.onDestruction: Api.off("listenTogether.state", onState)

function onState(data) { panel.status = data }

// 创建房间；结果同样是快照
Api.get("listenTogether.createRoom", {}, function(result, error) {
    if (error) Toast.show(error.message, "error")
    else panel.status = result
})
```

面板打开方式：`Nav.openPanel("listenTogether")`（集成方在 `PlayerOverlays.qml` 里挂载
`components/panels/ListenTogetherPanel.qml`）。

### 1.5 听众队列变更请求（`listenTogether.requestControl` 的队列 action）

worker 的 `REQUEST_SET_QUEUE` **一次只接受一种受校验的队列变更**（保留当前曲目的重排 /
单曲插入 / 单曲移除 / 移除当前曲目后按移除位置选下一首 / 单曲队列清空），
不允许一次替换多首。桌面端据此把队列操作拆成三个单步 action：

| action | 参数 | 说明 |
| --- | --- | --- |
| `addToQueue` | `track`（必填）、`index?` | 插入一首歌。`index` 是插入位置（缺省追加到队尾，越界自动收敛到 `[0, size]`） |
| `removeFromQueue` | `index`（必填） | 移除房间队列第 `index` 首（`queue` 下标）。队列只剩一首时拒绝 |
| `moveInQueue` | `fromIndex`、`toIndex`（必填） | 把 `fromIndex` 处的曲目移动到 `toIndex`，两者都不能越界且不能相同 |

`track` 参数**同时接受两种形状**（后端归一，前端不必自己转换）：

```jsonc
// 1) 房间曲目：listenTogether.state.queue[i] 原样传回
{ "stableKey": "netease:123", "channelId": "netease", "audioId": "123", "name": "…", "artist": "…" }

// 2) 桌面歌曲：online.search / online.y* / player.queue 的元素原样传回
{ "key": "netease:123", "source": "NETEASE", "title": "…", "artist": "…", "remoteId": "123", "durationMs": 0 }
```

后端用 `Song.toListenTogetherTrackOrNull()` 把桌面歌曲转成房间曲目；本地歌曲
（`source:"LOCAL"`）、缺 `remoteId` 的歌曲会被直接拒绝并在快照 `error` 里给出原因。

行为约定：

- **仅听众可用**。控制者调用返回 `error: "你已经是控制者"`；控制者改队列应直接操作
  `player.*`（`enqueue` / `enqueueNext` / `removeFromQueue` / `moveInQueue` /
  `setQueue` 等），由控制者轮询自动上报，无需调用本命令。
- 每次只表达**一首歌**的变化，与 worker 语义一致。
- **乐观应用**：请求发出后先把变更应用到本地队列与播放器（面板立刻可见），
  同时记住「基线房态版本 + 目标队列」。服务端广播推进版本且权威队列已包含这次变更时，
  覆盖层退休、完全以房态为准；被服务端拒绝时立即回退到权威队列并把中文错误写进 `error`；
  超过 10s 仍未确认按超时回退并提示「队列请求未得到确认，已恢复为房间队列」。
- 房间队列为空时三个 action 都返回 `error: "房间队列为空"`（worker 拒绝空队列的 `REQUEST_SET_QUEUE`）。
- 服务端在 `allowMemberControl=false` 或控制者离线时拒绝，错误文案见 §5。

```qml
// 听众：把选中歌曲加入房间队列
Api.get("listenTogether.requestControl", { action: "addToQueue", track: song }, cb)
// 听众：移除房间队列第 3 首 / 把第 5 首移到第 1 位
Api.get("listenTogether.requestControl", { action: "removeFromQueue", index: 2 }, cb)
Api.get("listenTogether.requestControl", { action: "moveInQueue", fromIndex: 4, toIndex: 0 }, cb)
```

### 1.6 播放器队列命令在房间里的行为（`player.enqueue*` 转发）

用户在歌曲菜单点「下一首播放」/「添加到播放队列」时，**不需要知道自己是听众**：
后端 `PlayerApi` 在「在房间里且自己是听众」时把这些命令自动转发成 1.5 的房间队列请求，
不再直接改本地队列（直接改会被房间权威队列在下次同步时覆盖，表现为「加了又消失」）。

| 本地命令 | 房间里作为听众时 |
| --- | --- |
| `player.enqueue` `{songs}` | `addToQueue`，`index` 缺省（追加到队尾） |
| `player.enqueueNext` `{songs}` | `addToQueue` 且 `index = currentIndex + 1`（插到房间当前曲目之后，与本地 `enqueueNext` 语义一致） |
| `player.removeFromQueue` `{index}` | `removeFromQueue`，`index` 先按曲目身份（`stableKey`）换算成房间队列下标 |
| `player.moveInQueue` `{from,to}` | `moveInQueue`，两个下标同样按 `stableKey` 换算 |

- 不在房间、或自己是控制者时行为与之前完全一致（直接改本地队列，成功返回 `{ok:true}`）。
- 转发成功时返回 `{ok:true, roomQueue:true, notice?}`；前端据此把成功提示说成
  「已添加到房间队列」而不是「已添加到下一首」（后者对听众是假话）。
- 转发失败时返回 `bad_request`，`message` 是可直接展示的中文原因：
  - `控制者关闭了听众控制，无法加入房间队列`（服务端 `member control disabled`）
  - `控制者不在线，无法加入房间队列`
  - `本地歌曲不能加入一起听` / `该歌曲无法加入一起听（缺少远端 ID）`
  - `这首歌不在房间队列里（本地歌曲不能加入一起听）`（删/移时本地曲目换算不出房间下标）
- 多选歌曲时逐条发送（worker 一次只接受一首歌的变化）；先整批校验可分享性再发送，
  避免加到一半才发现某首是本地歌曲。
- 乐观更新沿用 1.5：命令返回前变更已经应用到本地播放器，所以「点了立刻看到」不需要额外处理。

### 1.7 播放控制在房间里的行为（`player.next` / `previous` / `play` / `pause` / `togglePlayPause` 转发）

上下首与播放/暂停和加歌同理：**听众直接操作本地播放器会被房间权威房态在下次同步时改回去**
（表现为「按了下一首又跳回来」「按了暂停又自己播放」），所以 `PlayerApi` 在「房间里且自己是听众」时
把这些命令自动转发成房间请求。主页播放控制栏（`MiniPlayerBar.qml`）、播放页控制条
（`NowPlayingControls.qml`）、全局快捷键（`AppShortcuts.qml`）都走同一批命令，因此只改后端即可全部生效。

| 本地命令 | 房间里作为听众时 | 说明 |
| --- | --- | --- |
| `player.next` | `requestControl(action="next")` → `REQUEST_SET_TRACK` | 目标下标由后端按**房间队列**与房间循环模式计算（列表末尾回到开头 / 单曲循环等） |
| `player.previous` | `requestControl(action="previous")` → `REQUEST_SET_TRACK` | 同上，目标为房间队列的上一首 |
| `player.play` | `requestControl(action="play")` → `REQUEST_PLAY` | 进度取服务端时钟推算的房间当前进度 |
| `player.pause` | `requestControl(action="pause")` → `REQUEST_PAUSE` | 同上 |
| `player.togglePlayPause` | 按**房间**当前播放态二选一 | `room.playback.state == "playing"` → 发 `pause` 请求，否则发 `play` 请求 |

- **`togglePlayPause` 必须读房间播放态**（`ListenTogetherManager.roomIsPlaying()`），不能用本地
  `player.state`：听众的本地播放器可能正处于被房间同步覆盖的中间态，判错就会「想暂停却发了一次暂停
  再发播放」。
- 不在房间、或自己是控制者时行为与之前完全一致（直接操作本地播放器，返回 `{ok:true}`）。
- 转发成功返回 `{ok:true, roomQueue:true, notice?}`；切歌/播放本身是可见反馈，前端不额外弹成功 Toast
  （`PlayerGlobal._send` 只把错误 Toast 出来）。
- 转发失败时返回 `bad_request`，`message` 是可直接展示的中文原因，且**带上动作名**：
  - `控制者关闭了听众控制，无法切歌` / `…无法播放` / `…无法暂停`（服务端 `member control disabled`）
  - `控制者不在线，无法切歌` / `…无法播放` / `…无法暂停`
  - `房间队列为空，无法切歌`（切歌类才会拼动作名；播放/暂停遇到空队列时原样返回原因）
- 文案映射在 `core/ListenTogetherMapping.kt` 的 `roomPlaybackErrorMessage` /
  `listenTogetherErrorMessage`（队列类命令仍走 `roomQueueErrorMessage`，文案里是「无法加入房间队列」）。
- 控制者路径不受影响：控制者按 `next` 改变本地当前曲目后，由 `publishLocalState` 的
  `trackChanged` 检测上报 `SET_TRACK`（见 2.5），不需要额外转发逻辑。
- 已知语义差异：本地 `Player.previous()` 在进度 > 4s 时是「重播当前曲」，听众身份下转发的是
  `REQUEST_SET_TRACK` → 房间队列的上一首。房间的当前曲目由控制者权威决定，听众侧不能自行
  重播当前曲而对房间无影响，因此这里以「切上一首」为准（与 Android 听众一致）。

- 不可分享曲目：听众发 `REQUEST_PLAY/PAUSE/SEEK/SET_TRACK` 前会校验房间当前曲目可分享
  （有 `stableKey` 且 `channelId != local`，对齐 Android `transport()/seek()/trackSelection()`
  的 `isShareableForListenTogether` 校验），不可分享时不发无效请求并给出明确提示。
- `control_result` 应用规则（对齐 Android `shouldApplyListenTogetherCommittedControlState`）：
  - 听众只应用**自己发起**的 `REQUEST_*` 回执（按 `causedBy.userUuid` 归属），他人控制回执不干扰本地；
  - `applied.state` 仍无条件 apply（避免「控制按钮无响应」回归），但听众 `REQUEST_PLAY/PAUSE`
    的 ok 回执若播放态与刚发意图相反、且 `applied.state.version` 不比本地已见房态更新
    （worker 在 persist 后才构造 applied，窗口内可能被控制者新事件覆盖），则只把播放态字段
    按意图覆盖后再 apply；后续权威广播仍会校正真实房态。

---

## 2. 桌面后端 ⇄ LTW 服务端

### 2.1 HTTP

所有 URL 基于归一化后的 `baseUrl`（http/https，去尾部斜杠）。

| 方法 | 路径 | 鉴权 | 请求体 / 说明 |
| --- | --- | --- | --- |
| `POST` | `/api/rooms` | — | `{userUuid, nickname, initialSnapshot:{queue, currentIndex, track, settings, isPlaying, positionMs, repeatMode, shuffleEnabled, shuffleRestoreQueue?}}` |
| `POST` | `/api/rooms/:roomId/join` | 可选 `Authorization: Bearer <token>`（重连） | `{userUuid, nickname, memberSecret?, joinSecret?}` |
| `GET` | `/api/rooms/:roomId/state` | `Bearer <token>` | 返回 `{ok, state, expectedPositionMs, serverNowMs, autoPauseOnJoin, error}` |
| `POST` | `/api/rooms/:roomId/leave` | `Bearer <token>` | 请求体 `{}` |
| `POST` | `/api/rooms/:roomId/control` | `Bearer <token>` | 控制事件（见 2.3） |
| `GET` | `/api/rooms/:roomId/ws?token=…` | token 查询参数 | WebSocket |
| `GET` | `/healthz` | — | 配置后的可用性探测（非官方实现可忽略结果） |

创建/加入响应（字段与 Android `ListenTogetherRoomResponse` 一致）：

```jsonc
{
  "ok": true, "roomId": "ABC234", "userUuid": "…", "userId": "…", "nickname": "…",
  "role": "controller", "memberSecret": "…", "joinSecret": "…", "autoPauseOnJoin": true,
  "token": "…", "state": { /* ListenTogetherRoomState */ }, "wsUrl": "wss://…", "error": null
}
```

房间状态 `state`（与 Android `ListenTogetherRoomState` 一致）：
`roomId/version/schemaVersion/controllerUserUuid/controllerUserId/controllerHeartbeatAt/
settings/members/queue/currentIndex/track/playback/controllerOfflineSince/roomStatus/closedReason/updatedAt`。

### 2.2 WebSocket 消息

服务端 → 客户端：

| type | 字段 |
| --- | --- |
| `welcome` | `sessionId, userUuid, userId, nickname, role, autoPauseOnJoin, state, expectedPositionMs, nowMs` |
| `room_state_updated` | `roomId, version, state, expectedPositionMs, nowMs, causedBy{userUuid,userId,nickname,eventId,type}, message` |
| `room_resumed` / `room_suspended` | 同上（控制者上线/离线） |
| `room_closed` | `roomId, version, state, expectedPositionMs, nowMs, message` |
| `control_result` | `ok, result:{ok, applied?:{type,roomId,version,state,expectedPositionMs,nowMs,causedBy}, error}, nowMs, message, causedBy` |
| `link_requested` | 听众请求音频直链：`{roomId, requestTrackStableKey, track, currentIndex, causedBy}`；控制者回 `LINK_READY` 或 `LINK_UNAVAILABLE`（见 §2.8） |
| `np_pong` | `{t, nowMs}`；旧版 `pong` 也接收 |
| `error` | `{message}` |

客户端 → 服务端：

- 控制事件 JSON（字段见 2.3）；
- `{"type":"np_ping","t":<毫秒>}`（每 12s，兼作控制者心跳刷新与时钟校准）；
- `{"type":"ping"}`（旧版心跳）。

### 2.3 控制事件字段（Android `ListenTogetherEvent` 子集）

```
type, eventId, clientTimeMs, clientInstanceId, clientSequence,
positionMs, currentIndex, nextIndex, track, queue, roomSettings,
shouldPlay, state, repeatMode, shuffleEnabled, requestTrackStableKey,
forceRefresh, finishedTrackStableKey, queueMutation
```

桌面端实际发送：

| 场景 | 事件 |
| --- | --- |
| 本地切歌 | `SET_TRACK`（带队列快照/索引/track/进度/shouldPlay/循环/随机） |
| 本地队列变化（增删/排序） | `SET_QUEUE`（带 `queueMutation` 增量；fallback 为整队列快照） |
| 播放/暂停 | `PLAY` / `PAUSE`（带 positionMs） |
| 本地 seek（进度跳变检测） | `SEEK` |
| 循环/随机变化 | `PLAYBACK_MODE` |
| 定期心跳（播放中 8s / 暂停 15s） | `HEARTBEAT`（广播 `state`/`positionMs`/`track`/`currentIndex`） |
| 听众控制 | `REQUEST_PLAY/PAUSE/SEEK/PLAYBACK_MODE/SET_TRACK` |
| 听众队列变更 | `REQUEST_SET_QUEUE`（单曲增/删/移，带 `queueMutation` 或整快照，见 1.5） |
| 房间设置 | `UPDATE_SETTINGS{roomSettings}` |
| 控制者上传直链 | `LINK_READY{requestTrackStableKey, track{streamUrl,streamUrls}, positionMs}`（当前曲目直链变化或收到 `link_requested` 时） |
| 控制者宣告直链失效 | `LINK_UNAVAILABLE{requestTrackStableKey, track}`（本地解析失败且没有可分享直链时） |
| 听众请求直链 | `REQUEST_LINK{requestTrackStableKey, currentIndex, track}`（本地解析失败时） |

`schemaVersion` 固定为 2（与 worker 一致）。房态 `schemaVersion >= 2` 时，桌面端的队列类事件
（`SET_QUEUE`/`SET_TRACK`/`REQUEST_SET_QUEUE`）优先携带 **`queueMutation` 增量操作**
（见 2.7），并保留整队列快照作为 fallback；旧服务端（`schemaVersion < 2`）自动回落到整快照。
收到 `room_state_updated` 后以服务端 `version` 为准整体应用；若服务端只下发增量
（没有 `state`），桌面端在本地队列上重放该操作。

### 2.4 曲目字段与映射

`track` / `queue[]` 元素字段（与 Android `ListenTogetherTrack` 一致）：

```
stableKey, channelId, audioId, subAudioId, playlistContextId, mediaUri,
streamUrl, streamUrls[], name, artist, album, durationMs, coverUrl
```

`stableKey` 规则（与 Android `ListenTogetherTrackIdentity.buildStableTrackKey` 一致）：

- `netease` → `netease:<audioId>`（audioId = 网易云歌曲 id，桌面 `Song.remoteId`）
- `bilibili` → `bilibili:<audioId>[:<subAudioId>]`（桌面 `Song.remoteId` 是 bvid）
- `youtubeMusic` → `youtubeMusic:<audioId>[:<playlistContextId>]`

桌面 `Song` ⇄ 房间曲目：

| 房间字段 | 桌面 Song |
| --- | --- |
| `channelId=netease` | `source=NETEASE`，`key=netease:<audioId>`，`remoteId=audioId`，`artworkUrl=coverUrl` |
| `channelId=bilibili` | `source=BILIBILI`，`key=bili:<audioId>`，`remoteId=audioId`，`extraId=subAudioId`（cid） |
| `channelId=youtubeMusic` | `source=YOUTUBE`（仅展示，桌面无 YouTube 播放实现） |
| `channelId=local` | 拒绝：本地歌曲不能进入房间（创建/上报前过滤） |

`playback.repeatMode`：`0` 关闭、`1` 单曲循环、`2` 列表循环（桌面 `RepeatMode.OFF/ONE/ALL`）；
`playback.shuffleEnabled` 布尔。`expectedPositionMs` 由服务端按 `basePositionMs + elapsed*playbackRate`
推算，单曲循环时按曲目时长取模；客户端用它校正本地进度（漂移 > 2.5s 时 seek）。

#### 时钟同步与延迟补偿

服务端广播里的 `nowMs` / `serverNowMs` 是**消息发出时**的服务端时间，到达客户端有一个单程延迟。
客户端用 `np_ping`（回显 `t` 并带 `nowMs`）与 HTTP `/state` 的 `serverNowMs` 测量往返时间（RTT）：

- `offset = serverNowMs - (sentWall + rtt/2)`：用「发送时刻 + 半个 RTT」作为服务端时间的参照点，避免把单程延迟算进 offset；
- 首个往返样本直接采信，之后按 `新 offset = 旧*0.7 + 样本*0.3` 平滑；
- RTT > 30s 或 < 0 的样本丢弃；首次往返样本之前允许用单向样本做初步估算，之后忽略单向样本；
- 连接建立 / 断开时重置状态；RTT 用 `nanoTime` 测、offset 用墙钟算（避免墙钟跳变影响 RTT）。

应用权威房态时，不用广播里的 `expectedPositionMs` 直接作为「本地此刻的进度」，而是按
`basePositionMs + (本机now + 已补偿offset - baseTimestampMs) * playbackRate` 重新投影；
`expectedPositionMs` 仅作兜底。这样可将端到端进度误差压到 600ms 死区内，避免刚同步完就再次硬 seek。

### 2.5 控制者 / 听众行为

- **控制者**：轮询本地播放器（400ms）与"上次已上报状态"做差异比较，按上表上报控制事件；
  播放中每 8s、暂停每 15s 发送 `HEARTBEAT`；每 12s `np_ping`。
  收到他人触发的权威房态（例如听众 `REQUEST_*` 被服务端仲裁）时应用到本地播放器；
  自己事件触发的回声按 `causedBy.userUuid` 忽略（防回环）。
- **听众**：收到 `room_state_updated`/`welcome` 后把权威房态应用到 `container.player`
  （队列/当前曲/播放暂停/seek/循环/随机）；另有 400ms 看门狗，检测曲目/播放态/进度漂移
  （> 2.5s）时重新对齐。听众本地操作会转成 `REQUEST_*` 请求。
- 应用远端状态期间设置 `applyingRemote`/抑制窗口，并以上报基线=权威房态，避免本地回环。

### 2.6 重连

- WebSocket 断开且会话仍存在时进入 `reconnecting`，指数退避（1s→30s）重新 `POST /join`
  （带 `memberSecret`）拿新 token/wsUrl 后重连；`room closed`/`not in room`/`unauthorized`
  之类错误直接清理会话。
- 连接静默 35s 时用 `GET /state` 兜底刷新房态。
- 启动时若持久化会话存在，自动尝试一次重连。

### 2.7 增量队列同步（`queueMutation`）

与 Android `ListenTogetherQueueMutation*`、worker `src/queue-mutation.js` 对齐的
schemaVersion=2 增量操作。**为什么需要**：整队列快照是「最后写入者覆盖全部」，
并发时会把另一端已经到达的增删吃掉；增量只描述「我改了什么」，服务端把它重放到
收到事件时的最新权威队列，其余远端变化得以保留。

事件里的形状（`SET_QUEUE` / `SET_TRACK` / `REQUEST_SET_QUEUE` 共用）：

```jsonc
{
  "type": "SET_QUEUE",
  "queueMutation": {
    "baseRoomVersion": 12,          // 构造方看到的房态版本
    "operations": [                 // 最多 64 个（worker 与 Android 同一上限）
      { "type": "remove", "target": { "stableKey": "netease:9", "occurrence": 0 } },
      { "type": "move", "target": { "stableKey": "netease:3", "occurrence": 0 },
        "anchor": { "stableKey": "netease:1", "occurrence": 0 }, "placement": "before" },
      { "type": "insert", "anchor": { "stableKey": "netease:1", "occurrence": 0 },
        "placement": "before",
        "track": { "stableKey": "netease:7", "channelId": "netease", "audioId": "7", "name": "…", "artist": "…" } }
    ],
    "targetCurrent": { "stableKey": "netease:1", "occurrence": 0 }
  }
}
```

- 引用 = `stableKey` + 同键 `occurrence`（第几次出现），重复曲目也能唯一定位。
- 操作类型：`remove` / `remove_many`（`order[]`）/ `move` / `insert` / `reorder`（`order[]`）。
  `placement`：`before`（需 `anchor`）/ `append` / `prepend`。
- `targetCurrent` 指明操作后应成为当前曲目的那一项；**新插入的曲目没有基线引用，
  因此此项通常为 null**，服务端/客户端用 `track.stableKey` 兜底。

桌面端实现（`core/ListenTogetherQueueMutation.kt`）：

| 函数 | 作用 |
| --- | --- |
| `buildListenTogetherQueueMutationPlan(baseQueue, baseVersion, targetQueue, targetCurrentIndex)` | 由基线队列与目标队列构造操作；稳定键为空、操作数超限时 `requiresSnapshotFallback = true` |
| `applyListenTogetherQueueMutation(roomQueue, roomCurrentIndex, mutation, targetCurrentStableKey)` | 在给定队列上重放（保留远端增删，按稳定键重算当前索引） |
| `buildListenTogetherQueueEventPayload(room, queue, currentIndex)` | 决定这次上报带增量还是整快照 |

**fallback 策略**（`requiresSnapshotFallback` 为真时改发整队列快照）：

1. 任一队列项 `stableKey` 为空（无法构造引用）；
2. 操作数超过 `LISTEN_TOGETHER_MAX_QUEUE_MUTATION_OPERATIONS`（64）；
3. 不知道房态、或房态 `schemaVersion < 2`（旧服务端不认 `queueMutation`）。

**接收侧**：worker 目前始终下发完整 `state`，桌面端按原有路径整体应用。若服务端只发
`queueMutation` 而没有 `state`，桌面端在本地队列上重放它：`baseRoomVersion` 高于本地版本
说明自己落后，不猜（交给 35s 静默兜底刷新）；低于本地版本则按「最新队列上重放」处理
（rebase），不会用旧快照覆盖。

**控制者**的队列变化（增删/排序）从整快照切换为「增量 + 快照 fallback」；
监听者**不**上报队列（避免回声把房间队列改回去）。

### 2.8 共享音频直链（`REQUEST_LINK` / `LINK_READY` / `LINK_UNAVAILABLE`）

对齐 Android 的权威流机制，房间开启 `shareAudioLinks`（创建房间默认 `true`）时：

- **控制者上传**：`PlayerManager.currentAudioInput` 暴露本次实际打开的音源（只读）。控制者
  轮询到当前曲目的 `streamUrl` 变化（切歌或重新解析）时发 `LINK_READY`：
  `requestTrackStableKey` = 当前曲目稳定键，`track` = 带 `streamUrl` / `streamUrls` 的曲目，
  `currentIndex`、`positionMs`、`state` 一并带上。没有可用直链（本地文件/解析失败）时
  由本地解析失败回调发 `LINK_UNAVAILABLE`，服务端清掉该曲目缓存并广播房态。
- **收到 `link_requested`**：服务端在听众 `REQUEST_LINK` 命中不了缓存、且控制者在线时，
  向控制者 WS 推 `link_requested{requestTrackStableKey, track, currentIndex, causedBy}`。
  控制者用当前直链回 `LINK_READY`；当前没有可分享直链则回 `LINK_UNAVAILABLE`，听众立即
  回退本地解析而不是等到请求超时。
- **听众使用权威流**：收到 `welcome` / `room_state_updated` 且当前曲目带 `streamUrl` 时，
  `OnlineRepository.setSharedStreamUrls(stableKey, urls)` 设置共享流；随后
  `resolvePlayback` 在下载/磁盘缓存之后、在线解析之前优先返回共享直链，headers 按来源
  补齐（网易云 `Referer: https://music.163.com/` + `User-Agent`；B 站
  `BiliApi.biliHeaders()` 的 `Referer`/`User-Agent`/`Origin`；其它来源通用 `User-Agent`）。
  这样 B 站直链不会因缺少 Referer 而 403。
- **请求与回退**：听众本地解析失败时经 `PlayerManager.onPlaybackResolveFailed` 回调发
  `REQUEST_LINK`（带本地节流 4s，避免与服务端节流冲突）；收到 `LINK_UNAVAILABLE`
  广播时清掉共享流回到本地解析，同一稳定键不再自动重复请求（服务端可 `forceRefresh=true`
  主动刷新）。流地址变化（控制者重新上传）自动解除不可用标记。
- **快照字段**：`sharedStreamAvailable`（当前曲目有可用共享直链）、`usingSharedStream`
  （听众正在用共享流播放）供前端展示；QML 的开关走 `listenTogether.updateSettings` 的
  `shareAudioLinks`，无需新增 IPC 命令。

worker 侧只公开**当前曲目**的缓存候选，队列里其它曲目的 `streamUrl` / `streamUrls`
会被 strip；关闭 `shareAudioLinks` 时服务端立即清空缓存，桌面端同时清掉本地共享流。
由于直链是短时效的临时地址，不把它当成歌曲缓存持久化。

---

## 3. 持久化

`AppDirs.dataDir/listen_together.json`（不写 `settings.json`，不改 `Storage.kt`）：

```jsonc
{
  "schemaVersion": 1,
  "serverUrl": "https://…",
  "nickname": "桌面用户",
  "userUuid": "安装级 UUID（创建/加入时的身份）",
  "clientInstanceId": "desktop-…（控制事件顺序号用）",
  "session": {
    "baseUrl": "https://…", "roomId": "ABC234", "userUuid": "…", "nickname": "…",
    "role": "controller", "token": "…", "memberSecret": "…", "joinSecret": "…", "wsUrl": "…"
  }
}
```

- 创建/加入成功后写入 `session`；离开房间或房间关闭后清空 `session`（保留 serverUrl/nickname）。
- `memberSecret` / `joinSecret` / `token` 只保存在本地文件，不上报给前端（`status` 只暴露
  `inviteLink`）。
- 邀请链接由后端生成：`neriplayer://listen-together/join?roomId=…&inviter=…&baseUrl=…&secret=…`，
  默认服务端地址时不写 `baseUrl`；解析时只接受 `https` 的 `baseUrl`（防中间人，和 Android 一致），
  否则回退到本机已配置地址。

---

## 4. 兼容性核对与不支持项

已逐项对照 `worker.js` / Android 序列化字段实现：

- ✅ HTTP create/join/state/leave/control、`/healthz`、`ws?token=`；字段名、鉴权方式、错误文案键一致
  （`join_secret_required`、`member_secret_required`、`controller offline`、`member control disabled`…）。
- ✅ `welcome` / `room_state_updated` / `room_suspended` / `room_resumed` / `room_closed` /
  `control_result` / `np_ping`↔`np_pong` / 旧版 `ping`↔`pong`。
- ✅ 事件类型 `PLAY/PAUSE/SEEK/PLAYBACK_MODE/SET_TRACK/SET_QUEUE/HEARTBEAT/UPDATE_SETTINGS`、
  直链事件 `REQUEST_LINK/LINK_READY/LINK_UNAVAILABLE`（见 §2.8）、
  听众 `REQUEST_*` 仲裁、`clientInstanceId/clientSequence/clientTimeMs` 顺序字段。
- ✅ `schemaVersion=2`、`repeatMode 0/1/2`、`shuffleEnabled`、`expectedPositionMs` 推算与单曲循环取模、
  `autoPauseOnMemberChange`（新成员加入/离开暂停）、`allowMemberControl`。
- ✅ 本地歌曲拒绝（`channelId=local` 不上报；服务端也会拒绝）。
- ✅ 邀请链接格式（`neriplayer://listen-together/join`）与 Android `ListenTogetherInviteParser` 一致；
  额外容忍 `neriplayer-debug://`。
- ✅ 会话持久化与用 `memberSecret` 重连（不触发自动暂停，符合 worker 语义）。

不支持的子集 / 已知限制：

1. **音频直链共享已实现**（见 §2.8）：创建房间 `shareAudioLinks` 默认 `true`；控制者解析出
   直链后上报 `LINK_READY`，听众优先使用权威直链；`REQUEST_LINK` / `LINK_UNAVAILABLE`
   与回退本地解析均已对齐 Android。开关由控制者通过 `listenTogether.updateSettings`
   的 `shareAudioLinks` 控制（关闭后服务端立即清空缓存，桌面端同步清掉本地共享流）。
2. **`queueMutation` 的部分子场景**：桌面端已支持发送/重放 `queueMutation`（见 2.7），
   但 `TRACK_FINISHED` 与随机播放重排不使用增量路径——桌面端不上报 `TRACK_FINISHED`
   （见下一条），随机开关仍走 `PLAYBACK_MODE` 的整队列快照语义。
3. **`TRACK_FINISHED` 多端结束栅栏**：桌面端不上报 `TRACK_FINISHED`；本地播完切歌在控制者身份下
   由轮询检测为 `SET_TRACK`。服务端的 `trackFinishBarrier` 逻辑不会被桌面端触发。
4. **`REQUEST_SET_TRACK` / `REQUEST_SET_QUEUE` 的校验由服务端裁决**：桌面听众的
   "上一首/下一首"仍发 `REQUEST_SET_TRACK`；单曲增删与移动发 `REQUEST_SET_QUEUE`
   （见 1.5），并且**每次只改一首**——这是 worker 的硬性语义，桌面端不做批量替换。
   听众侧另有乐观应用：请求在途期间本地队列先落地，服务端确认后以权威房态为准，
   被拒或超时则回退。
   **例外：「播放全部」的替换模式**（`player.setQueue {mode:"replace"}`）刻意带完整队列发
   `REQUEST_SET_TRACK`：worker 的 `sanitizeForwardedControlPayload` 对 SET_TRACK 会把
   `event.queue` 整体写入房态（对齐 Android `buildSetTrackEvent`），且
   `shouldAcceptTrackBoundRequest` 只要求 `requestTrackStableKey` 落在新队列里，
   因此听众可以整体替换房间队列。本地歌曲在发送前先过滤（`sanitizeTrack` 会丢弃 local 曲目，
   worker 也会以 `local tracks cannot be shared` 拒绝），空歌单/无权限由后端返回明确错误。
5. **bilibili 跨端身份**：桌面 `Song.remoteId` 是 bvid，发送 `bilibili:<bvid>`；
   Android 对多 P 视频使用 `bilibili:<aid>:<cid>`。同一视频两端 stableKey 可能不同
   （网易云曲目不受影响）。桌面端不会去在线查询 aid↔bvid。
6. **YouTube Music**：可接收/展示 Android 房间里的 `youtubeMusic` 曲目，但桌面版没有 YouTube
   播放实现，播放会由在线音源解析失败报错。
7. **控制者本地播放不可分享歌曲（本地文件等）**：不上报事件（房间保持上一次权威状态），面板给提示；
   恢复播放可分享歌曲后自动重新同步。
8. **无 Android 的高级恢复策略**：没有网络切换监听、后台保活、播放卡顿探测、
   `FOREGROUND` 恢复等策略，只用简单 WS 重连 + 35s 静默 HTTP 兜底 + 2.5s 漂移校正。
9. **面板不提供改昵称/重置 UUID 的入口**：昵称在面板的服务端配置里统一设置，
   `userUuid` 与安装绑定（持久化文件），不提供重置按钮。

---

## 5. 错误码 / 提示

后端返回的命令结果不带异常，错误放在快照 `error` 字段（已翻译为中文提示）；常见服务端错误：

| worker 错误 | 面板提示 |
| --- | --- |
| `join_secret_required` | 首次加入需要邀请链接（其中带有加入密钥），只填房间号仅适用于重连 |
| `member_secret_required` | 该身份已在房间中，需要用原身份重连 |
| `room not initialized` / `room closed` | 房间不存在或已过期 / 房间已关闭 |
| `controller offline` | 控制者不在线 |
| `member control disabled` | 控制者关闭了听众控制 |
| `only controller …` | 只有控制者可以执行该操作 |
| `member control target mismatch` | 请求控制的目标歌曲已变化，请重试 |
| `member control target unavailable` | 请求的歌曲不在当前房间队列中 |
| `queue update changes too many tracks` | 队列请求一次只能增/删/移一首歌 |
| `queue reorder current track mismatch` | 重排后当前歌曲必须保持不变 |
| `queue mutation is invalid` | 队列增量格式不合法（桌面端会自动回退整队列快照） |
| `queue mutation base version is ahead` | 本地队列依据的房态版本过期，正在重新同步 |
| `audio link sharing disabled` | 房间未开启音频直链共享 |
| `nickname …` | 昵称不合法（1-24 位中文/字母/数字） |

桌面端本地校验（不经过服务端）也会写进同一字段：

| 本地错误 | 触发条件 |
| --- | --- |
| `你已经是控制者` | 控制者调用队列 action（应直接操作 `player.*`） |
| `房间队列为空` | 房间队列为空时执行队列 action |
| `队列下标越界（当前队列共 N 首）` | `removeFromQueue.index` 越界 |
| `不能移除队列中的最后一首歌` | `removeFromQueue` 时队列只剩一首 |
| `移动位置不合法（…）` | `moveInQueue` 的 `fromIndex`/`toIndex` 越界或相同 |
| `缺少要添加的歌曲` / `缺少 fromIndex` / `缺少 toIndex` | 必需参数缺失 |
| `该歌曲不能加入一起听（本地歌曲或缺少远端 ID）` | `addToQueue.track` 无法转成可分享曲目 |
| `队列请求未得到确认，已恢复为房间队列` | 乐观请求 10s 未获服务端确认（`notice` 字段） |

---

## 6. 集成方需要做的事

1. `BackendMain.kt` 注册（仅一行，文件由集成负责人维护）：

   ```kotlin
   import moe.ouom.neriplayer.desktop.backend.api.ListenTogetherApi
   // ...其它 register 之后
   ListenTogetherApi.register(server, container)
   ```

2. `PlayerOverlays.qml` 挂载面板：

   ```qml
   case "listenTogether":
       return "panels/ListenTogetherPanel.qml"
   ```

3. 入口：设置页 `SettingsPage.qml`「一起听」区按钮调用 `Nav.openPanel("listenTogether")`
   （可选在 `NavGlobal.qml` 加 `openListenTogetherPanel()` 便捷函数）。
4. 本功能不依赖登录态/账号；不需要新增账号相关事件或设置项。服务端地址、昵称与房间
   交互全部在面板内完成，配置与会话持久化在 `AppDirs.dataDir/listen_together.json`。
