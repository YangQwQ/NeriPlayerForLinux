# 存储管理对话框：参考项目调查与移植方案

> 纯调查文档，不含任何代码改动。
> 参考项目：`/home/yang/Git/NeriPlayer-Desktop`（Tauri + Vue）。
> 本项目：`/home/yang/Git/neriplayer`（Kotlin 无头后端 + Qt6/QML）。
> 调查时间：2026-11-20。

---

## 0. 结论摘要（TL;DR）

- **他们展示的是一份"只读占用清单 + 一个二次确认的清理对话框"**，不是饼图/条形图：按 4 个分组（可清理缓存 / 下载内容 / 诊断文件 / 应用数据）逐行列出「名称 + 说明 + 字节数 + 文件数或记录数」，底部一个总计；点"清除缓存"后弹出带勾选框和危险色确认的第二个对话框。
- **他们能清理 6 类**：音频缓存、图片缓存、下载暂存、分享暂存、平台歌单缓存、歌词缓存。默认只勾选音频+图片，其余默认不勾；有下载任务时"下载暂存"被禁用。数据库里的歌词/歌单缓存按 bucket 清空并回报释放字节。
- **他们的占用统计是后端递归 `fs::metadata().len()` + 数据库 `SUM(payload_bytes + length(cache_key))`**，并做了大量"防重复计数"处理（canonicalize 去重、excluded 路径、数据库归属从总文件大小里扣除）；前端再补一段 localStorage 估算。
- **我们后端目前有 5 个 clear 类命令，但没有任何"占用字节数统计"命令**：`DownloadCatalog.totalSizeBytes()` 是唯一现成的字节口径，且只是索引里的记录值。真正在磁盘上的可清理缓存只有 `~/.cache/NeriPlayer/covers/`；歌词缓存是**纯内存**（不落盘），下载目录里的 `.lrc` 属于用户数据不是缓存；音频预取缓存放在 `/dev/shm`（内存盘，不占磁盘）。
- **缺口核心**：没有 `storage.usage` 这类只读统计命令；`cacheLimitMb` 设置项是**死配置**（无人读取、不生效）。
- **最小可行移植**：先加只读 `storage.usage`，再把现有的 `artwork.clearCache` 包装成带确认的"安全清理"入口；UI 复用现有 `SettingsPage.qml` 的"数据与缓存"卡片 + `ConfirmDialog.qml`。**关键是不要把"清记录"和"清磁盘空间"混为一谈**，尤其不要误删下载目录和用户封面。

---

## 1. 参考项目（NeriPlayer-Desktop）怎么做

### 1.1 UI：两个 M3 对话框，分组清单而非图表

关键文件：`src/components/StorageManagementDialog.vue`（266 行）、`src/views/SettingsView.vue`、`src/utils/storage.ts`、`src/i18n/{zh-CN,en,ja,zh-TW}.json`。

触发方式：`SettingsView.vue:3109` 有一张可点击卡片（`settings.storage_usage_title` / `storage_usage_desc`），点击 `openStorageManagement()`（`SettingsView.vue:1109`）先置 `showStorageManagement = true`，再异步 `loadStorageUsage()` 调 `invoke('get_storage_usage', { downloadDir })`（`SettingsView.vue:1096`）。

**主对话框**（"存储占用详情"）：
- 加载中显示旋转图标 + "正在统计存储占用..."；
- 加载完成后按 `summary.sections` 顺序渲染，每个 section 一个标题，下面若干行；
- 每行：左侧「标题（13px 粗）+ 说明（11px 次色）」，右侧「`formatStorageSize(item.sizeBytes)` 加粗 + `{count} 个文件` 或 `{count} 条记录`」；
- 底部一行「总计 + `formatStorageSize(summary.totalSizeBytes)`」；
- 最后一个红色容器色按钮"清除缓存"（`delete_sweep` 图标），`clearing` 时禁用。

**清理对话框**（点"清除缓存"后弹出）：
- 一条警告文案："只会删除可重新生成的缓存，不会删除已下载音乐、歌词或应用配置"；
- 6 个勾选行（checkbox），默认仅 `audioCache`、`imageCache` 为 true（`StorageManagementDialog.vue:33-40, 55-63`）；
- 下载暂存有 `activeDownloadCount > 0` 时禁用，提示"有下载任务进行中，暂不可清理"（`:45, 65-68, 176-188`）；
- 确认按钮为 danger 样式，无任何勾选时禁用；点确认 emit `clear(options)`，父组件调 `invoke('clear_storage_cache', { options, downloadDir })`（`SettingsView.vue:1114`），完成后 toast 显示释放的 MB / 部分失败 / 缓存为空，并重新 `loadStorageUsage()`（`SettingsView.vue:1114-1130`）。

> 注意：**没有饼图/进度条/占比可视化**，只有分组列表 + 数字 + 总计。所谓"可视化"更接近 Android 设置里的存储分类列表。

### 1.2 展示的占用分类（`storage_cmd.rs:180-259`）

| 分组 id | 条目 id | 口径 |
| --- | --- | --- |
| `cleanable_cache` 可清理缓存 | `audio_cache` 音频缓存 | `app_cache_dir/playback-audio` 目录递归 |
| | `image_cache` 图片缓存 | `app_data/covers`、`app_data/thumbnails`、`app_cache/image_cache` |
| | `download_staging` 下载暂存 | `app_data/temp`、`app_cache/download_staging` + 下载根目录下 `.part`/`.reserve` 文件 |
| | `shared_media` 分享暂存 | `app_data/shared_media`、`app_cache/shared_media_exports` |
| | `platform_list_cache` 平台歌单缓存 | 3 个平台缓存目录 + 数据库 `platform_detail` bucket（按**记录数**展示） |
| | `lyrics_cache` 歌词缓存 | 数据库 `lyrics` bucket（按**记录数**展示，不落盘） |
| | `other_cache` 其他缓存 | 缓存根目录下扣除以上已归类路径后的剩余 |
| `downloads` 下载内容 | `downloaded_music` / `downloaded_lyrics` / `download_index` | 下载根目录按扩展名分类 / 数据库下载索引记录数 |
| `diagnostics` 诊断文件 | `logs` / `crash_logs` | `logs`、`crashes`、`error_logs`、`crash-reports` |
| `app_data` 应用数据 | `local_covers` / `custom_background` / `webview_data` / `playlist_data` / `app_data` | 本地封面、自定义背景、WebView 用户数据、用户数据库（扣除归属缓存后的剩余）、其他应用数据 |

显示时 `item.recordCount != null` 显示"N 条记录"，否则显示"N 个文件"（`StorageManagementDialog.vue:119-123`）。

### 1.3 占用是怎么算出来的（`src-tauri/src/commands/storage_cmd.rs`）

数据结构（`storage_cmd.rs:21-46`）：`StorageUsageItem { id, sizeBytes, fileCount, path, cacheKind, recordCount }` → `StorageUsageSection { id, items }` → `StorageUsageSummary { sections, totalSizeBytes, totalFileCount }`，全部 `#[serde(rename_all = "camelCase")]`。

算法要点：

1. **目录体积**：`stats_for_path()`（`:501-524`）递归 `fs::read_dir` + `fs::metadata(path).len()` 累加；`FileStats::AddAssign` 用 `saturating_add` 防溢出。
2. **防重复计数**：`is_excluded()` + `canonical_key()`（`:526-539`）用规范路径前缀匹配，把已归入某类的子目录从"其他缓存/其他应用数据"里排除；`clear_storage_cache` 也用 `HashSet<canonical_key>` 去重删除目标（`:334-338`）。
3. **数据库缓存归属**：`database_usage()`（`:452-462`）对 bucket 执行 `usage()`（`src-tauri/src/db/cache.rs:96-103`）：`SELECT SUM(payload_bytes + length(cache_key)), COUNT(*) FROM cache_entry WHERE bucket = ?`。用户数据库文件本身的体积先全部计入 `unattributed`，再用 `attribute()` 闭包把各 bucket 的估算字节逐项"扣除"，剩余才算 `playlist_data`——**保证合计不双算**（`:156-169, 248-256`）。
4. **下载目录分类**：`scan_download_path()`（`:597-622`）按扩展名判定：`.part`/`.reserve` → staging，`.lrc`/`.tlrc` → lyrics，`manifest.json` 排除，其余 → music。
5. **WebView 数据不是缓存**：`webview_data_paths()`（`:72-89`）把 `EBWebView/` 和 `debug-*` 目录显式列为"应用数据"，不进可清理清单，避免误删登录态（对应单测 `webview_profile_and_debug_stores_are_not_cache`，`:688-704`）。
6. **前端补充**：`src/utils/storage.ts:31-49` 的 `mergeBrowserCacheUsage()` 把 localStorage 里 `neri:recommend:cache` / `neri:home-feed:v1` 按 `value.length * 2` 估算追加到 `platform_list_cache`，再用 `withTotals()` 重算总计；`formatStorageSize()` 按 B/KB/MB/GB 格式化（`:56-62`）。

### 1.4 清理的安全设计（`clear_storage_cache`, `storage_cmd.rs:279-382`）

- 选项结构体 `StorageCacheClearOptions` 6 个布尔，后端 `Default` 同样只开 audio+image（`:48-70`）。
- `clear_directory_contents()`（`:541-566`）**只清空目录内容、不删目录本身**；逐条目删除，删不掉（被占用/无权限）的累计 `failed_count` 后继续，不中断。
- `cleared_bytes` 按删除前后 `stats_for_path` 差值计算（`:340-349`），不是预估；数据库 bucket 用 `clear()` 返回的 `(bytes, records)`（`:359-379`）。
- 下载暂存只删 `.part`/`.reserve`（`:384-402`），不碰已完成的音乐文件。
- 返回 `StorageClearResult { clearedBytes, deletedFiles, failedCount }` 供 UI 提示。
- 前端清理后 `clearBrowserCache()` 删 localStorage 键、`invalidateBiliSubtitleCache()` 失效内存歌词，再刷新用量（`SettingsView.vue:1120-1129`）。

---

## 2. 我们项目现状

### 2.1 前端：`SettingsPage.qml` 的"数据与缓存"卡片

关键位置：`desktop/qt/qml/pages/SettingsPage.qml`
- 分类索引：`downloadStorage` 分类包含 `library` / `downloads` / `dataCache`（`:121`）；搜索关键词里有"数据与缓存 → 清理 缓存 历史 统计 下载记录"（`:161`）。
- 卡片本体：`secDataCache`（`:2390-2448`），标题"数据与缓存"，说明"清理封面缓存、下载记录与本地统计"，图标 `storage`。现有 4 行：
  1. **封面缓存** → `page.clearArtworkCache()` → `Api.call("artwork.clearCache")`，toast"已清除 N 个封面缓存文件"（`:532-542, 2400-2409`）；
  2. **已完成的下载记录** → `download.clearFinished`，说明明确写"只清空下载列表中的已完成任务，不删除已下载的文件"（`:2411-2420`）；
  3. **播放历史** → `history.clear`（`:2422-2430`）；
  4. **播放统计** → `stats.clear`（`:2432-2440`）。
- 底部说明："以上清理操作不可撤销；下载文件本体请在「下载管理」中删除。"（`:2444-2447`）。
- `runCleanup(method, msg)`（`:548-557`）统一包装：成功 toast + `page.loadSnapshot()`，失败只 toast。

**缺口**：卡片里**没有任何占用数字**，也没有一个统一的"存储占用详情"入口；4 个按钮语义混杂——只有第 1 个真正释放磁盘空间，后 3 个是清记录/内存状态。

### 2.2 后端：`AppDirs` 与实际路径

关键文件：`desktop/src/main/kotlin/moe/ouom/neriplayer/desktop/core/Storage.kt:18-42`。

| AppDirs 字段 | 实际路径（`$XDG_*` 未设置时） | 用途 / 是否缓存 |
| --- | --- | --- |
| `dataDir` | `~/.local/share/NeriPlayer` | 持久数据根 |
| `configDir` | `~/.config/NeriPlayer` | `settings.json` / `accounts.json` / `sync.json`（**用户配置，禁止清理**） |
| `cacheDir` | `~/.cache/NeriPlayer` | 缓存根 |
| `artworkDir` | `~/.cache/NeriPlayer/artwork` | `Library.kt:178` 扫描本地音乐时抽取的内嵌封面 `md5Hex(path)+.img`——**本地曲库封面，删了会影响本地歌曲显示** |
| `coverDir` | `~/.cache/NeriPlayer/covers` | `ArtworkApi.kt` 在线封面缓存（`resolveToCache`，`md5Hex(url)+.img`，`:141-152`）和 `Player.kt:731` 播放页封面缓存（`song.key` 清洗后 `+.img`）——**真正可再生的封面缓存** |
| `logDir` | `~/.local/share/NeriPlayer/logs` | 日志 |
| 数据文件 | `library.json` / `playlists.json` / `history.json` / `stats.json` / `search_history.json` / `downloads.json` | 用户数据/索引，**不可清理** |

其他实际落盘位置：

- **音频预取缓存**：`Player.kt:120-138`，优先 `/dev/shm/NeriPlayer/audio/current.audio`（**内存文件系统，不占磁盘**），不可用时回退 `~/.cache/NeriPlayer/audio/`；单文件固定名 `current.audio` + `.tmp`。
- **歌词缓存**：`core/Lyrics.kt:7, 235-256` 是 `LinkedHashMap` 内存 LRU（上限 120 首），**完全不落盘**，没有独立歌词缓存目录。下载的 `.lrc` 在 `Download.kt:326-328` 写到音频同目录，属于下载目录的一部分（用户数据）。
- **下载目录**：`core/Download.kt:128-131`，`settings.downloadDirectory` 或默认 `~/Music/NeriPlayer`；下载直接写最终文件（`Download.kt:255-300`），取消时 `target.delete()`（`:266`）——**没有 `.part`/`.reserve` 暂存文件**，所以参考项目的"下载暂存"分类在我们这里没有对应物。目录内可能含音频 + `.lrc` + 内嵌封面抽出的图片。
- **下载体积口径**：`DownloadCatalog.totalSizeBytes()`（`Download.kt:102`）是索引 `DownloadedSong.sizeBytes` 之和，下载完成时写入 `target.length()`（`:285, 298`）——现成的"已下载占用"数字，但未暴露给 UI。

### 2.3 现有清理命令（backend/api）

| 命令 | 文件 | 实际行为 | 是否释放磁盘 |
| --- | --- | --- | --- |
| `artwork.clearCache` | `ArtworkApi.kt:73-85` | 删除 `coverDir` 下所有**普通文件** + 清 `cacheLocks`，返回 `{ removed }`；**不动 `artworkDir`** | ✅ 是（封面缓存） |
| `history.clear` | `CollectionsApi.kt:162-164` | `history.clear()` 清内存并持久化空列表 | ❌ 仅记录 |
| `stats.clear` | `CollectionsApi.kt:180-182` | `stats.clear()` 清统计 | ❌ 仅记录 |
| `download.clearFinished` | `DownloadApi.kt:98-101` | `removeFinished()` 只从内存任务列表移除 DONE/失败任务（`Download.kt:176-180`），**不删文件、不删索引** | ❌ 仅任务列表 |
| `download.clearAll` | `DownloadApi.kt:104-107` | 取消进行中任务并清空任务列表，同样不删文件 | ❌ |
| `download.remove` | `DownloadApi.kt:73-96` | 按 songKey/task id 删单个下载文件 + 索引 | ✅ 单文件 |

后端命令注册在 `BackendMain.kt:51-62`（集成负责人维护）；新增命令须在此登记并同步 `desktop/docs/protocol*.md`。

### 2.4 缺口清单

1. **无任何占用统计命令**：全后端没有 `storage.usage` / 目录体积聚合；`grep length()/sizeBytes` 只找到单文件读取和 `totalSizeBytes()`。
2. **`cacheLimitMb`（`Storage.kt:171`，默认 1024）是死配置**：全仓库无人读取，缓存无上限约束——若做占用展示，是否顺带启用限流需单独决策。
3. **"清记录"与"清空间"未区分**：UI 上 4 个按钮只有封面缓存释放空间，容易让用户误以为"清理记录"能腾出磁盘。
4. **`artworkDir` 与 `coverDir` 语义混淆风险**：现有 `artwork.clearCache` 只清 `coverDir`；若移植时图省事直接递归清 `cacheDir`，会误删本地曲库封面（`artworkDir`）。
5. **无防误删护栏**：没有"删除前统计、删除后校验、失败计数"这套参考项目的机制；也没有 activeDownload 之类的禁用条件。
6. **i18n / 协议文档**：新文案需进 `desktop/qt/qml/i18n/translations.js` 的 `en` 表（`I18n.tr("中文原文")` 机制，缺失回退中文）；新命令需进 `protocol*.md`。

---

## 3. 移植方案

### 3.1 后端要加什么（安全优先，只读先行）

建议新增 `desktop/src/main/kotlin/moe/ouom/neriplayer/desktop/backend/api/StorageApi.kt`，模型用 `@Serializable` 放 `core/`（如 `StorageUsage`），在 `BackendMain.kt` 注册（**该文件由集成负责人改**），并更新 `desktop/docs/protocol-storage.md`（新建）或追加到 `protocol.md`。

**(a) `storage.usage`（只读，第一步必做）**

返回分级结构，对齐参考项目的 `sections/items/totalSizeBytes/totalFileCount`，但按我们的实际布局裁剪：

- `cleanable_cache`
  - `cover_cache`：`AppDirs.coverDir` 递归字节/文件数 → 对应 `artwork.clearCache`
  - `other_cache`：`AppDirs.cacheDir` 下扣除 `coverDir`、`artworkDir`、`audio` 后的剩余（可再生的零散缓存）
- `downloads`
  - `downloaded_music`：优先用 `DownloadCatalog.totalSizeBytes()`（索引口径），也可递归下载目录校验；二者有差异时以索引为准并在文档说明
  - `downloaded_lyrics`：下载目录内 `.lrc` 文件（按参考项目的扩展名分类法）
- `app_data`（只读、不可清理）
  - `local_covers`：`artworkDir`（本地曲库抽取封面）
  - `user_data`：`library.json` / `playlists.json` / `history.json` / `stats.json` 等文件大小
  - `logs`：`logDir`
  - `config`：`configDir`（只展示，不清理）

实现要点（照搬参考项目的正确性经验）：
- `Files.walk` / `walkTopDown()` 递归 `length()`，`Long` 累加并 `coerceAtLeast(0)` 防异常；
- **canonical path 去重 + excluded 前缀**，避免 `cacheDir` 总量把 `coverDir`/`artworkDir` 重复计入；
- 音频缓存目录 `/dev/shm` 分支**不计入磁盘占用**（内存盘）；
- 全扫描放 `withContext(Dispatchers.IO)`；目录很大时考虑加超时或限制深度。

**(b) `storage.clearCache`（带白名单的安全清理，第二步）**

不要做一个"全清"的万能命令，用**显式选项白名单**，后端默认全 false，前端显式勾选：

```
{ coverCache: Boolean, otherCache: Boolean }
```

- `coverCache`：复用/内部调用与 `artwork.clearCache` **同一段逻辑**（删 `coverDir` 下普通文件 + 清 `cacheLocks`），返回 `{ clearedBytes, deletedFiles, failedCount }`；
- `otherCache`：只清 `cacheDir` 下经白名单确认可再生的子目录（初期建议留空/禁用，见 3.4 风险）；
- **明确不提供**对 `artworkDir`、`dataDir`、`configDir`、下载目录、日志的删除选项；
- 照搬参考项目的护栏：删除前后体积差计算 `clearedBytes`、单条失败累计 `failedCount` 后继续、只清目录内容不删目录本身、`canonical` 去重；
- 复用现有 `artwork.clearCache` 也可（最小改动）：前端直接调它，但那样拿不到字节数——要显示"释放了多少 MB"就得改造返回体或先调 `storage.usage` 前后各一次。

**(c) 可选第三步：`cacheLimitMb` 启用 / 自动清理**

若产品要"缓存上限"，在 `SettingsRepository` 读取 `cacheLimitMb`，超过阈值时按 LRU 清 `coverDir`——属于独立功能，建议不在本次移植范围。

### 3.2 前端 UI 放哪

- **入口**：就在 `SettingsPage.qml:2390` 的 `secDataCache` 卡片顶部加一行"存储占用详情"（带 `database`/`storage` 图标、右箭头），点击打开一个 Dialog；或把卡片本身改成可点击（对齐参考项目 `SettingsView.vue:3109` 的可点击卡片）。
- **对话框**：新建 `desktop/qt/qml/components/settings/StorageManagementDialog.qml`（`components/settings/` 是设置页组件目录），复用现有 `components/library/ConfirmDialog.qml` 的 `openFor(title, message, confirmText)` + `confirmed()` 模式；若需要勾选框，则在对话框内用 `CheckBox` 自绘 2 个选项（封面缓存 / 其他缓存），参照参考项目 6 个勾选行的交互与默认勾选策略。
- **数据展示**：分组 + 行 + 总计，用 `Theme.spacing*` / `Theme.radius*` / `Theme.textStyle(...)` / `Theme.withAlpha()`，颜色走 `Theme` 角色，危险按钮用 `Theme.error`/error-container；**不要硬编码像素字号与颜色**。
- **格式化**：新增小工具（如 `components/settings/StorageFormat.js`，`.pragma library`）实现 B/KB/MB/GB 格式化，逻辑同 `src/utils/storage.ts:56-62`。
- **生命周期**：`Component.onCompleted` 或点击时 `Api.call("storage.usage").then(...).catch(...)`；`Api.on` 若用到事件须在 `Component.onDestruction` 里 `Api.off`（本功能用不到事件）。
- **文案**：所有用户可见文本用 `I18n.tr("中文原文")` 包裹，并同步往 `desktop/qt/qml/i18n/translations.js` 的 `en` 表加英文；可直接参考参考项目 `src/i18n/zh-CN.json:1299-1356` 的文案措辞（"存储占用详情 / 可清理缓存 / 总计 / 只会删除可重新生成的缓存…"）。
- **图标**：`database` / `delete_sweep` / `storage` 需在 `components/IconMap.js` 中确认存在，缺失先补（Material Symbols 名称）。
- **不改** `SettingsPage.qml` 之外的共享接线文件；`SettingsPage.qml` 属共享文件，需集成负责人授权后改（本调查只给方案，不落地）。

### 3.3 工作量估计

| 部分 | 估计 | 说明 |
| --- | --- | --- |
| 后端 `storage.usage`（只读 + 去重统计 + 模型） | 0.5–1 天 | 无现成工具，需写递归统计与 excluded 逻辑；模型/注册/协议文档另计 |
| 后端安全清理命令（含字节差、失败计数） | 0.5 天 | 可复用 `artwork.clearCache` 逻辑 |
| 前端对话框 + 入口 + 格式化 + 勾选交互 | 1 天 | 复用现有组件可压缩到 0.5 天 |
| i18n（中英）+ 协议文档 + 自测 | 0.5 天 | QML 文案较多 |
| **合计** | **约 2.5–3 人天** | 若只做"只读展示 + 复用现有 clearCache"，可压到 ~1.5 人天 |

> 注意：`BackendMain.kt`（注册）、`SettingsPage.qml`（入口）是共享接线文件，按仓库协作规则须由集成负责人统一改或明确授权，排期时要把沟通成本算进去。

### 3.4 误删风险与规避（最重要）

| 风险点 | 后果 | 规避措施 |
| --- | --- | --- |
| 把 `artworkDir`（本地曲库封面）当缓存清 | 本地歌曲封面丢失，需重新扫描才能恢复 | 白名单只含 `coverDir`；`artworkDir` 明确归入"应用数据/只读"，**不提供清理选项** |
| 把下载目录当缓存清 | 用户下载的音频/歌词被删，**不可恢复** | 下载目录只读展示；清理命令参数里根本不接受 download 路径；相册外置目录尤须小心 |
| 清理 `dataDir` / `configDir` | 曲库、歌单、历史、统计、登录态丢失 | 这些目录只统计不清理；参考项目把 WebView/凭据列为"非缓存"的做法值得照搬 |
| 音频缓存误计/误清 | `/dev/shm` 是内存盘，重启即失，统计进去会虚高 | 统计时排除 `/dev/shm` 分支；不提供音频缓存清理项 |
| 清理时文件被占用/无权限 | 半删状态、报错中断 | 逐条删除、`failedCount` 累计后继续、按前后体积差记实际释放量 |
| 重复计数 | 总计虚高 | canonical 路径去重 + excluded 前缀，数据库/索引归属只算一次 |
| 用户误解"清理记录=腾空间" | 误以为已释放磁盘 | UI 文案区分"清除记录"与"释放空间"；详情对话框明确"不会删除已下载音乐、歌词或配置" |
| 清理对话框无二次确认 | 误点即删 | 危险色确认按钮 + 默认不勾危险项 + 警告文案（对齐参考项目） |
| 缓存删除后播放/显示异常 | 正在使用的封面被删 | 清 `coverDir` 时同步清 `cacheLocks` 与内存缓存（现有 `artwork.clearCache` 已做 `cacheLocks.clear()`，但前端 `ArtworkCache.js` 的 `resolved` 字典是否要失效需评估） |

### 3.5 分步建议

1. **第 1 步（只读、零风险）**：实现 `storage.usage` 并在"数据与缓存"卡片加一个入口展示分组清单 + 总计。只统计、不新增任何删除能力。可先用隔离目录 `NERI_TEST_ROOT=/tmp/neri-dev-<名字>` + `tools/dev.sh screenshot --page settings` 验证数字合理性。
2. **第 2 步（最小清理）**：把现有 `artwork.clearCache` 包装进带确认的对话框，返回/展示释放字节数（改造返回体或前后各取一次 usage）。此时仅清 `coverDir`。
3. **第 3 步（可选扩展）**：评估 `~/.cache/NeriPlayer` 下是否还有其他可再生子目录；确认后再把 `otherCache` 加入白名单。任何无法 100% 确认可再生的目录一律不动。
4. **第 4 步（语义整理）**：把"已完成的下载记录 / 播放历史 / 播放统计"三行明确标注为"仅清理记录，不释放磁盘空间"，与上面的"缓存清理"分组分开，降低误删预期。
5. **第 5 步（文档与国际化）**：新建/更新 `protocol-*.md`，补齐 `translations.js` 英文文案；按 AGENTS.md 约定，多个并行改动整理成文件互不重叠的提交。
6. **不纳入本次范围**：启用 `cacheLimitMb` 自动限流、数据库缓存 bucket（我们当前无 `cache_entry` 表）、WebView/凭据目录（我们是 Qt 无 WebView）、下载暂存 `.part`（我们下载直写最终文件，无此概念）。

---

## 附：关键文件路径速查

- 参考 UI：`/home/yang/Git/NeriPlayer-Desktop/src/components/StorageManagementDialog.vue`
- 参考后端：`/home/yang/Git/NeriPlayer-Desktop/src-tauri/src/commands/storage_cmd.rs`
- 参考缓存库：`/home/yang/Git/NeriPlayer-Desktop/src-tauri/src/db/cache.rs`（`usage` / `clear`）
- 参考前端工具/文案：`/home/yang/Git/NeriPlayer-Desktop/src/utils/storage.ts`、`/home/yang/Git/NeriPlayer-Desktop/src/i18n/zh-CN.json`
- 本项目设置页：`/home/yang/Git/neriplayer/desktop/qt/qml/pages/SettingsPage.qml`（`secDataCache` 约 2390 行；清理函数约 532–566 行）
- 本项目目录定义：`/home/yang/Git/neriplayer/desktop/src/main/kotlin/moe/ouom/neriplayer/desktop/core/Storage.kt`（`AppDirs` 18–42 行；`cacheLimitMb` 171 行）
- 本项目封面缓存：`.../backend/api/ArtworkApi.kt`（`artwork.clearCache` 73–85 行）、`.../core/Player.kt`（封面缓存 731 行、音频缓存 120–138 行）、`.../core/Library.kt`（本地封面 178 行）
- 本项目下载：`.../core/Download.kt`（目录 128–131 行、体积 102 行、clearFinished 176–180 行）、`.../backend/api/DownloadApi.kt`（98–107 行）
- 本项目记录清理：`.../backend/api/CollectionsApi.kt`（history.clear 162 行、stats.clear 180 行）
- 本项目命令注册：`.../backend/BackendMain.kt`（51–62 行）
- 本项目 i18n：`/home/yang/Git/neriplayer/desktop/qt/qml/i18n/translations.js`
