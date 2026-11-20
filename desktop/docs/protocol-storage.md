# 存储占用统计与安全清理 · IPC 协议（protocol-storage）

领域：只读统计本机各目录的实际磁盘占用 + 白名单式清理可再生缓存。
实现：`backend/api/StorageApi.kt`、模型 `core/StorageUsage.kt`。
UI：`qt/qml/components/settings/StorageManagementDialog.qml`（入口在设置页「数据与缓存」卡片，由集成负责人接线）。

设计要点（改代码前先读）：

- **「可清理」只描述本命令能处理的部分**，其余条目一律只读。`storage.clearCache` 的参数是白名单枚举，
  不接受任何路径——下载目录、配置目录、本地曲库封面根本不在取值范围内，传错值直接 `bad_request`。
- **`coverDir` 与 `artworkDir` 是两回事**，不能混：
  | 目录 | 内容 | 能否清理 |
  | --- | --- | --- |
  | `AppDirs.coverDir`（`$XDG_CACHE_HOME/NeriPlayer/covers`） | 在线封面缓存（`ArtworkApi` 按 URL 下载） | ✅ 可再生，唯一清理目标 |
  | `AppDirs.artworkDir`（`$XDG_CACHE_HOME/NeriPlayer/artwork`） | 本地曲库扫描时从音频标签抽取的内嵌封面 | ❌ 删了本地歌曲就没封面，必须重新扫描媒体库 |
- **`/dev/shm` 上的音频预取缓存是内存文件系统**，不占磁盘；统计时不并入总计，只在 `excluded` 里单独回报，
  也不提供清理入口。
- 统计全部在 `Dispatchers.IO` 上跑；递归扫描不跟随符号链接（防软链环与重复计数），
  单条属性读失败跳过继续。已计入的路径用 canonical path 记账，避免 `cacheDir` 总量把子目录重复累加。
- 清理的护栏：只清空目录内容、不删目录本身；逐条删除，单条失败累计 `failed` 后继续；
  `releasedBytes` 用**删除前后体积差**计算（而不是删除前累加文件长度），部分失败时不会高估。

## 1. 命令清单

| method | params | result | 说明 |
| --- | --- | --- | --- |
| `storage.usage` | — | `StorageUsageSummary` | 只读统计，不修改任何文件 |
| `storage.clearCache` | `{targets?: string[]}` | `StorageClearResult` | 清理白名单内的缓存；`targets` 省略时默认 `["coverCache"]` |

## 2. `storage.usage`

```jsonc
// 请求
{}
```

返回结构（字段名 camelCase，全部 `@Serializable`，定义见 `core/StorageUsage.kt`）：

```jsonc
{
  "sections": [
    {
      "id": "cleanable_cache",
      "items": [
        {
          "id": "cover_cache",
          "path": "/home/me/.cache/NeriPlayer/covers",
          "sizeBytes": 18342144,
          "fileCount": 212,
          "cleanable": true,
          "clearTarget": "coverCache"      // 可直接作为 storage.clearCache 的 targets 元素
        }
      ]
    },
    {
      "id": "cache_other",
      "items": [ { "id": "other_cache", "path": "/home/me/.cache/NeriPlayer",
                   "sizeBytes": 0, "fileCount": 0, "cleanable": false } ]
    },
    {
      "id": "downloads",
      "items": [
        { "id": "downloaded_music", "path": "/home/me/Music/NeriPlayer",
          "sizeBytes": 52428800, "fileCount": 12, "cleanable": false },   // 索引口径（downloads.json）
        { "id": "downloaded_lyrics", "path": "/home/me/Music/NeriPlayer",
          "sizeBytes": 4096, "fileCount": 3, "cleanable": false }         // 下载目录里的 .lrc/.tlrc
      ]
    },
    {
      "id": "app_data",
      "items": [
        { "id": "local_covers", "path": "/home/me/.cache/NeriPlayer/artwork",
          "sizeBytes": 2097152, "fileCount": 40, "cleanable": false },    // 本地曲库封面，不可清理
        { "id": "library_data", "path": "/home/me/.local/share/NeriPlayer",
          "sizeBytes": 81920, "fileCount": 6, "cleanable": false },       // library/playlists/history/stats/…
        { "id": "logs", "path": "/home/me/.local/share/NeriPlayer/logs",
          "sizeBytes": 16384, "fileCount": 2, "cleanable": false },
        { "id": "config", "path": "/home/me/.config/NeriPlayer",
          "sizeBytes": 4096, "fileCount": 2, "cleanable": false }         // 设置与登录态
      ]
    }
  ],
  "totalSizeBytes": 73318436,        // 以上各项字节之和（互不重叠，不含 excluded）
  "totalFileCount": 277,
  "cleanableSizeBytes": 18342144,    // 仅 coverDir，用于对话框主按钮副标题
  "cleanableFileCount": 212,
  "downloadIndexBytes": 52428800,    // 下载索引记录值之和（DownloadCatalog.totalSizeBytes）
  "excluded": [
    {
      "path": "/dev/shm/NeriPlayer/audio",
      "reason": "memory",            // memory = 内存文件系统，不计入磁盘占用
      "sizeBytes": 10485760,         // 仅供展示，不参与 totalSizeBytes
      "fileCount": 1
    }
  ],
  "cacheDir": "/home/me/.cache/NeriPlayer",
  "downloadDir": "/home/me/Music/NeriPlayer",
  "generatedAt": 1763630000000
}
```

### 2.1 分组与条目

| section.id | item.id | 口径 | cleanable |
| --- | --- | --- | --- |
| `cleanable_cache` | `cover_cache` | `coverDir` 递归 | ✅ |
| `cache_other` | `other_cache` | `cacheDir` 下除 `covers` / `artwork`（及内存盘分支）之外的剩余 | ❌ |
| `downloads` | `downloaded_music` | 下载**索引**里的 `sizeBytes` 之和（用户手工删文件后可能偏大） | ❌ |
| | `downloaded_lyrics` | 下载目录顶层 `.lrc` / `.tlrc` 实际字节 | ❌ |
| `app_data` | `local_covers` | `artworkDir` 递归（本地曲库封面） | ❌ |
| | `library_data` | `library.json` / `playlists.json` / `history.json` / `stats.json` / `search_history.json` / `downloads.json` | ❌ |
| | `logs` | `logDir` 递归 | ❌ |
| | `config` | `configDir` 递归（`settings.json` / `accounts.json` / `sync.json`） | ❌ |

说明：

- 前端应**按 id 取文案**，不要按数组顺序假设条目存在（科目可能因目录缺失而字节为 0，但条目始终返回）。
- `cleanable: true` 的条目才允许发清理命令；`clearTarget` 是唯一合法的 `targets` 取值来源。
- 目录不存在时 `sizeBytes`/`fileCount` 均为 0，命令本身仍然成功（`storage.usage` 没有业务错误）。
- `downloads.downloaded_music` 是索引口径，与磁盘实际占用可能不同；需要精确值时应递归扫描下载目录，
  当前刻意不做（下载目录可能是用户自选的大目录，扫描代价高且易与其他分类重复计数）。

## 3. `storage.clearCache`

```jsonc
// 请求（省略 targets 等价于 ["coverCache"]）
{}
{"targets": ["coverCache"]}
```

成功返回：

```jsonc
{
  "targets": ["coverCache"],
  "releasedBytes": 18342144,   // 删除前后目录体积差，>= 0
  "deleted": 212,              // 实际删除成功的条目数
  "failed": 0,                 // 删除失败数（被占用/无权限），不中断其余条目
  "failedItems": [],           // 失败条目名，最多 20 条
  "remainingBytes": 0,         // 清理后目录剩余体积
  "remainingFiles": 0
}
```

错误：

| code | 触发条件 |
| --- | --- |
| `bad_request` | `targets` 是空数组；或含白名单之外的值（例如 `"downloads"` / `"artwork"` / 任意路径） |
| `unsafe_path` | 实际解析出的 `coverDir` 不在 `cacheDir` 下、或指到了 `artworkDir` / `dataDir` / `configDir` / 内存盘（`XDG_CACHE_HOME` 被改成异常值时的兜底拒绝） |

安全边界（协议层保证，不由前端约束）：

1. **唯一清理目标**是 `AppDirs.coverDir`。下载目录、`artworkDir`、`dataDir`、`configDir`、日志目录
   没有任何清理入口，命令参数也无法指定路径。
2. 只清空目录**内容**，目录本身保留；逐条删除，单条失败不影响其余条目
   （`artwork.clearCache` 与本命令都只删 `coverDir` 里的普通文件，结果等价，只是本命令额外回报字节数）。
   本命令**不清理** `ArtworkApi` 的内存并发锁表（那是内存态、按文件名分槽，残留几条不影响正确性）。
3. 内存盘（`/dev/shm`）上的音频缓存不参与清理。
4. 单条删除失败不会中断，返回 `failed` / `failedItems` 供 UI 提示「部分未能删除」。
5. `releasedBytes` 是实测差值，不是预估；清理无变化时返回 0（前端应提示「缓存为空」而不是「已释放 0 MB」）。

## 4. 前端约定

- 对话框（`StorageManagementDialog.qml`）打开时调一次 `storage.usage`；清理成功后重新调一次刷新数字。
- 只对 `cleanable: true` 的行显示可点击/清理入口，其余行置灰只读；点击清理目标必须先走二次确认
  （`components/library/ConfirmDialog.qml` 的交互模式），确认按钮使用危险色。
- 字节格式化在前端完成（`formatSize`），协议里只传字节数。
- 本功能**不推送事件**（无 `Api.on`），不需要 `Api.off`。
- 之前设置页的「封面缓存 → artwork.clearCache」按钮与本功能等价，只是拿不到释放字节数；
  两者都只清 `coverDir`，可以共存，但新 UI 优先用 `storage.clearCache`。
