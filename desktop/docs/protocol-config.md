# 设置快照导入导出 + 调试日志查看器 · IPC 协议（protocol-config）

领域：**设置快照的本地备份/恢复**（阶段 A）与**只读的后端日志查看**（阶段 B）。
实现：`core/AppLog.kt`（环形缓冲 + 脱敏）、`core/ConfigSnapshot.kt`（快照编解码与校验）、
`backend/api/ConfigTransferApi.kt`（命令）。
UI：`qt/qml/components/settings/ConfigTransferDialog.qml`、`qt/qml/components/settings/DebugLogViewer.qml`
（入口由集成负责人接线，见 §6）。

调查与取舍背景见 `docs/investigations/config-transfer-debug.md`（阶段 A + B，C/D 不做）。

---

## 0. 设计边界（改代码前先读）

### 快照里有什么、没有什么

| 内容 | 在快照里 | 理由 |
| --- | --- | --- |
| `AppSettings`（全部偏好：主题/播放/歌词/界面/悬浮歌词/下载/快捷键…） | ✅ | 这就是「设置」本身 |
| Cookie、登录态（`accounts.json`） | ❌ | 600 权限的本地机密，不做成可随手分享的普通文件 |
| GitHub Token / WebDAV / 同步配置（`sync.json`） | ❌ | 同上 |
| 歌单、收藏、播放历史、统计 | ❌ | 属于业务数据，走 GitHub 云同步（`protocol-sync.md`），不是配置 |
| 播放队列 / 当前进度（`lastQueue` 等字段） | ✅（作为普通字段） | 它们在 `AppSettings` 里，一起进快照；用不上时导入方可以忽略 |

**不做**（与调查文档 §5 一致）：密码加密信封、Android 备份包互操作、`includeLoginState` 开关。
导出就是一份人可读的 JSON，用户可以自己 diff / 手改 / 放进 dotfiles 仓库。

### 导入是「先整份校验、再一次性替换」

`config.import` 的任何一步失败都**不会**修改内存设置，也不会写盘：

1. 绝对路径 / 存在 / 可读 / 大小 ≤ 2 MiB；
2. 合法 JSON 对象；
3. `kind` 精确匹配、`formatVersion` ∈ `[1, 1]`、`platform` 为空或 `desktop`；
4. `settings` 是对象且非空；
5. 剔除名字像凭据的字段（`cookie` / `token` / `password` / `secret` …），记录到 `omittedFields`；
6. 剔除本版本不认识的字段，记录到 `ignoredFields`；
7. 枚举字段校验（大小写写错自动归一，取值非法直接拒绝），见 §4；
8. 整份解码成 `AppSettings`（缺字段以**当前设置**补齐，保留当前值）；
9. 数值夹取（音量、倍速、透明度、时长、均衡器频段等越界就夹到合法区间并记 `warnings`）；
10. 全部通过后 `SettingsRepository.replace()`：内存立即生效 + `JsonFileStore` 的
    「临时文件 + 原子重命名」写盘，并广播 `settings` / `theme` 事件。

前端应先用 `dryRun: true` 拿一次预览，把 `changedFields` / `warnings` 展示给用户，
确认后再发一次不带 `dryRun` 的请求——真正替换只发生在第二次调用。

---

## 1. 命令清单

| method | params | result | 说明 |
| --- | --- | --- | --- |
| `config.export` | `{path?: string}` | `ConfigExportResult` | 导出当前设置为 JSON 文件；`path` 省略时写到 `~/neriplayer-settings-<时间戳>.json` |
| `config.import` | `{path: string, dryRun?: bool}` | `ConfigImportResult` | 校验并（可选）应用快照；`dryRun=true` 只校验不替换 |
| `debug.recentLogs` | `{limit?: int, minLevel?: string}` | `DebugLogsResult` | 最近日志，**新 → 旧**，已脱敏 |

三个命令都在 `ConfigTransferApi.register()` 里注册，`BackendMain.kt` 已统一接线。

---

## 2. `config.export`

```jsonc
// 请求
{ "path": "/home/me/backup/neriplayer-settings-20261120-201500.json" }   // path 可省略
```

返回：

```jsonc
{
  "path": "/home/me/backup/neriplayer-settings-20261120-201500.json",
  "bytes": 4218,
  "fieldCount": 97,            // 写入磁盘的设置字段数
  "exportedAt": 1763642100000, // 毫秒时间戳，同时写进文件
  "kind": "moe.ouom.neriplayer.config.settings",
  "formatVersion": 1,
  "omittedFields": []          // 出于安全被剔除的字段（当前 AppSettings 里没有，恒为空）
}
```

**写盘语义**：先写 `<name>.tmp` 再重命名，与 `JsonFileStore` 一致；
目标已存在时覆盖；目标是目录、或指向程序自己的数据文件（`settings.json` /
`accounts.json` / `sync.json` / `library.json` / `playlists.json` / `history.json` / `stats.json`）
时拒绝，返回 `unsafe_path`。

### 文件格式

```jsonc
{
  "kind": "moe.ouom.neriplayer.config.settings",
  "formatVersion": 1,
  "platform": "desktop",
  "exportedAt": 1763642100000,
  "appVersion": "2.1.0",
  "fieldCount": 97,
  "omittedFields": [],
  "settings": {
    "themeSeedColor": "0061A4",
    "darkMode": "AUTO",
    "volume": 0.85,
    "playbackSpeed": 1.0,
    "musicFolders": ["/home/me/Music"],
    "floatingLyricsPositionX": 0.5,
    "shortcuts": { "…": "…" },
    "lastQueue": []
    // … 与 AppSettings 的 @Serializable 字段一一对应
  }
}
```

键名与 `AppSettings` 完全一致（camelCase），`settings` 的内容可以直接和
`config/settings.json` 互相 diff。

---

## 3. `config.import`

```jsonc
// 只校验（前端预览用）
{ "path": "/home/me/backup/neriplayer-settings-20261120-201500.json", "dryRun": true }

// 真正导入
{ "path": "/home/me/backup/neriplayer-settings-20261120-201500.json" }
```

返回：

```jsonc
{
  "applied": true,                 // dryRun 时为 false
  "path": "/home/me/backup/neriplayer-settings-20261120-201500.json",
  "formatVersion": 1,
  "platform": "desktop",
  "exportedAt": 1763642100000,
  "appVersion": "2.1.0",
  "changedFields": ["volume", "themeSeedColor"],  // 与当前设置相比真正变化的字段
  "changedCount": 2,
  "keptFields": ["nowPlayingCoverShape"],         // 文件里没有 → 保留当前值
  "ignoredFields": [],                            // 文件里有但本版本不认识 → 忽略
  "omittedFields": [],                            // 看起来是凭据 → 丢弃
  "warnings": ["volume 越界（1.5），已夹取为 1.0"],
  "settings": { /* applied=true 时带回应用后的完整设置，等价于 settings.get */ }
}
```

- `changedFields` 以**当前设置**为基准；把刚导出的文件再导入一次，`changedCount` 应为 0。
- `keptFields` 让「手改过的精简文件」成为合法输入：只写 `{"volume": 0.3}` 的快照只改音量。
- `warnings` 是给人看的说明，**不阻断导入**；真正不合法的内容走 `error`（见 §5）。

### 导入后的运行时生效

`AppSettings` 是纯数据，`PlayerManager` 只在构造时读一次音量/语速/音高/均衡器，
所以导入成功后命令会通过 `PlayerManager` 的公开 setter 重放一遍音频相关设置：
`setVolume` / `setSpeed` / `setPitch` / `setFadeOptions` / `setLoudness` / `setEqualizerPreset`
（均衡器关闭时改为 `setEqualizerEnabled(false)`）。
其余设置由前端监听 `settings` 事件自然刷新（主题、字体、背景、页面开关…）。

---

## 4. 字段校验规则

### 枚举字段

取值必须命中白名单，大小写写错会被自动归一（`auto` → `AUTO`，记进 `warnings`），
其它取值直接以 `invalid_field` 拒绝（并把可选值列进错误消息）：

| 字段 | 允许值 |
| --- | --- |
| `darkMode` | `AUTO` / `LIGHT` / `DARK` |
| `paletteStyle` | `TonalSpot` / `Neutral` / `Vibrant` / `Expressive` / `Rainbow` / `FruitSalad` / `Monochrome` / `Fidelity` / `Content` |
| `colorSpec` | `SPEC_2021` / `SPEC_2025` |
| `language` | `zh-CN` / `en` |
| `qualityPreference`、`downloadQuality` | `standard` / `higher` / `exhigh` / `lossless` / `hires` |
| `lyricsAlignment`、`nowPlayingInfoAlignment`、`floatingLyricsAlignment` | `LEFT` / `CENTER` / `RIGHT` |
| `lyricsHighlight` | `LINE` / `WORD` |
| `lyricsScrollEasing` | `LINEAR` / `SINE` / `QUAD` / `CUBIC` |
| `nowPlayingCoverShape` | `CIRCLE` / `ROUNDED` / `SQUARE` |
| `nowPlayingBackground` | `BLUR` / `GRADIENT` / `SOLID` |
| `floatingLyricsRenderStyle` | `SHADOW` / `OUTLINE` |
| `floatingLyricsWindowMode` | `LAYER` / `NORMAL` |
| `floatingLyrics{Text,Shadow,Outline,Background}Color` | `WHITE` `BLACK` `RED` `ORANGE` `YELLOW` `GREEN` `CYAN` `BLUE` `PURPLE` `PINK` |

### 数值字段（越界自动夹取，不报错）

`volume` 0..1、`playbackSpeed` 0.5..3、`pitchSemitone` ±12、`lyricsFontScale` 0.5..3、
`appBackgroundDim` 0..1、`appBackgroundBlur` 0..100、`fadeInMs`/`fadeOutMs` 0..10000、
`crossfadeMs` 0..20000、`lyricsHighlightDuration`/`lyricsScrollDuration` 0..5000、
`nowPlayingAutoHideDelayMs` 0..60000、`sleepTimerMinutes` 0..1440、`cacheLimitMb` 0..1048576、
`downloadConcurrency` 1..8、`uiScale`（0 或 0.5..3）、
`floatingLyrics*` 的透明度/字号/描边/模糊/宽度/位置（见 `core/ConfigSnapshot.kt`）。

`equalizerBands` 必须是 10 段；长度不对时补齐/截断，增益夹到 ±20 dB。

`themeSeedColor` / `customSeedColors` 必须是 6 位十六进制；非法的种子色回退默认色，
非法的自定义色被丢弃（都记进 `warnings`）。

---

## 5. 错误码

导入/导出的失败都以协议错误返回（`{"id":N,"error":{"code","message"}}`），
`message` 是中文可读文案，前端可直接展示：

| code | 场景 |
| --- | --- |
| `bad_request` | 缺 `path`、`path` 不是绝对路径、`path` 是目录、`minLevel` 非法 |
| `not_found` | 文件不存在 |
| `io_error` | 读不动 / 写不动（权限、磁盘满） |
| `too_large` | 文件或导出内容超过 2 MiB |
| `invalid_snapshot` | 不是合法 JSON 对象、缺 `kind`/`formatVersion`/`settings`、字段类型不符 |
| `unsupported_kind` | `kind` 不是 `moe.ouom.neriplayer.config.settings` |
| `unsupported_version` | `formatVersion < 1` 或高于本程序支持的版本 |
| `unsupported_platform` | `platform` 不是 `desktop`（例如 Android 备份包） |
| `invalid_field` | 枚举字段取值不在白名单内 |
| `unsafe_path` | 导出目标指向程序自己的数据文件或目录 |

---

## 6. `debug.recentLogs`

```jsonc
// 请求（两个参数都可省）
{ "limit": 200, "minLevel": "INFO" }   // limit 默认 200，夹在 1..1000；minLevel 省略=全部
```

返回（**新 → 旧**，前端反转成时间正序展示）：

```jsonc
{
  "logs": [
    {
      "timestampMs": 1763642100123,
      "level": "WARN",              // DEBUG / INFO / WARN / ERROR
      "target": "config",           // 来源标记，见下
      "message": "标准流日志桥接已安装…",
      "repeat": 1                   // 2 秒内相同内容的重复次数（合并计数）
    }
  ],
  "total": 128,                     // 当前环里的条数
  "recordedTotal": 4210,            // 进程启动以来累计入环条数（含被淘汰的）
  "capacity": 1000,
  "truncatedMessageLength": 2000    // 单条消息的硬上限
}
```

### 缓冲语义（`core/AppLog.kt`）

- 容量 **1000 条**，单条消息最多 **2000 字符**（超长截断并以 `…` 结尾）；
- 2 秒窗口内**完全相同**的 `(level, target, message)` 合并成一条并累加 `repeat`，
  避免高频日志（如播放器 4 Hz 状态输出）把环冲掉；
- 内存有界、线程安全（`ArrayDeque` + 锁），不会因为长任务无限增长。

### 脱敏规则（**入环前**执行，无法关闭）

日志会被整段展示、也可能被用户复制粘贴给别人，所以脱敏发生在**写进环形缓冲之前**，
而不是在 `debug.recentLogs` 返回时。替换值统一是 `[REDACTED]`：

| 形态 | 处理 |
| --- | --- |
| JSON 里键名命中敏感词：`"cookie":"…"` | 整个值替换为 `[REDACTED]` |
| 整行头部：`Cookie: a=b; c=d` / `Authorization: …` / `Set-Cookie:` | 冒号后整段替换 |
| `Bearer <token>` | token 部分替换 |
| URL 里的 `user:password@host` | 密码部分替换 |
| 零散键值：`MUSIC_U=…`、`access_token=…`、`password: …`、`csrf=…` | 值部分替换 |

敏感键名（大小写不敏感）：`cookie` `set-cookie` `authorization` `proxy-authorization`
`x-auth-token` `music_u` `sessdata` `bili_jct` `buvid3` `dedeuserid` `access_token`
`refresh_token` `id_token` `token` `api_key` `apikey` `secret` `password` `passwd` `pwd`
`sessionid` `session_key` `csrf` `xsrf` `sign`。

> 说明：本仓库现有的 `println("[login] … Cookie：${http.cookieSnapshot(...).keys}")` 打的是
> **Cookie 键名**而不是值，键名不会被脱敏——这是有意的，排障需要知道「有没有拿到某个键」。

### 标准流桥接

既有代码里有几十处 `println` / `System.err.println`。为了不改道那些调用点
（它们分属别的模块，本功能无权修改），`ConfigTransferApi.register()` 会把 `System.out` /
`System.err` 各 tee 一份进环形缓冲：

- 原输出完全不变（`tools/dev.sh backend` 重定向的 `backend.log` 照常可读）；
- 每行按 `\n` 切分后入环；`[core] …`、`[download] …` 这样的既有前缀会被提取成 `target`，
  没有前缀时 `target` 是 `stdout` / `stderr`；
- 级别按关键字粗分：包含 error/exception/failed/错误/失败 的行记为 `ERROR`，
  包含 warn/unsupported/警告 的记为 `WARN`，其余 `INFO`；
- 设置环境变量 `NERI_APP_LOG_BRIDGE=0` 可关闭桥接（关闭后只有 `AppLog.log()` 的调用进环）。

### 前端查看器的行为（`DebugLogViewer.qml`）

- 1.5 s 轮询一次（只在对话框可见时）；`limit` 固定 500；
- 级别 chip **多选**筛选，双击 solo 单个级别；搜索框对 `target + message` 做大小写不敏感全文匹配；
- 「暂停」冻结显示但继续轮询，「跟随」在用户上滑后自动关闭；
- 「复制可见日志」复制当前筛选后的全部行；右键单行复制该行；
- 「清空筛选」重置级别与搜索（**不会**清空后端缓冲——环里的内容只随进程生命周期滚动淘汰）。

---

## 7. 集成接线点（由集成负责人处理）

本功能只交付自包含组件，不修改共享接线文件。需要接的地方：

1. **设置页入口**（`pages/SettingsPage.qml`）：在「数据与缓存」卡片附近用 `Loaders`/按钮打开
   `ConfigTransferDialog.openDialog()`，并在 `DebugLogViewer` 同理加一个「查看运行日志」按钮；
2. **i18n**（`qt/qml/i18n/translations.js`）：两个组件里所有 `I18n.tr("中文原文")` 需要补 `en` 词条，
   缺失时英文界面回退中文；
3. **`docs/README.md`** 的协议索引加一行指向本文件；
4. `BackendMain.kt` 已经注册 `ConfigTransferApi.register(server, container)`，无需再改。

---

## 8. 已知限制

- `SettingsRepository.updateDeferred()` 有 400 ms 合并写。导入时若恰有音量滑块在动，
  延迟写会用「导入替换之后」的内存状态落盘（不会把旧值写回），所以**数据不会错**；
  但如果在 `replace()` 之后仍有 setter 触发 `updateDeferred`，落盘内容会是该 setter 的增量——
  这也是 `applyRuntimeSettings` 只用「基于当前内存设置做 copy」的 setter 的原因。
  若要彻底消除这个窗口，需要在 `SettingsRepository` 上新增 `flush()` / `cancelDeferred()`
  （`core/Storage.kt` 属于共享文件，本功能无权修改，见汇报）。
- 快照不含凭据，因此「换机迁移」仍需在新机器上重新登录（扫码/贴 Cookie 走 `login.*`）。
- 日志只在内存里，进程退出即丢失；落盘日志（`AppDirs.logDir`）与崩溃报告属于阶段 C/D，本期不做。
