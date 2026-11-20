# 调查：配置导入导出 + Debug 日志查看器

> 性质：纯调查，不含代码改动。参考项目 `NeriPlayer-Desktop`（Tauri + Vue，下称「桌面版」）与本仓库 `neriplayer`（Kotlin 无头后端 + Qt6/QML，下称「本仓库」）对比，结论供后续排期参考。

## 0. 结论速览

| 议题 | 桌面版现状 | 本仓库现状 | 建议 |
| --- | --- | --- | --- |
| 配置导入导出 | 完整的「设置 + 登录态 + 同步配置 + 一起听 + 语言」打包，可选密码加密（PBKDF2 + AES-GCM），兼容 Android 备份并弹确认 | 只有 GitHub 云同步（同步的是**业务数据**，不含设置/凭据）；没有本地配置备份文件 | 做「设置快照导出/导入」的最小闭环（无加密、不含 Cookie/Token），不做完整克隆 |
| 日志查看器 | 后端 1500 条环形缓冲 + 前端 1.5s 轮询，级别/来源筛选、搜索、暂停、跟随、复制、导出诊断报告、打开日志目录 | 只有 `println`/`System.err` 打stdout；`AppDirs.logDir` 建了目录但从未写入；前端只有 `console.warn` | 做「后端日志缓冲 + 一个 `debug.logs` 命令 + 只读日志面板」，不做崩溃报告/一键导出包 |
| 崩溃报告 | panic hook 落盘 + 列表/读取/清空/测试触发命令 + 后端开发者模式校验 | 无 | 暂不做（理由见 §5） |
| 诊断包导出 | 文本报告（版本 + 系统 + 日志 + 崩溃清单），固定落日志目录 | 无 | 可作为日志面板的附属小命令延后做 |

---

## 1. 参考项目：配置导入导出包含什么

### 1.1 入口与交互

- 前端对话框：`/home/yang/Git/NeriPlayer-Desktop/src/components/settings/ConfigTransferDialogs.vue`。
  - 导出两个开关：**是否加密**、**是否包含登录状态**（`ConfigTransferDialogs.vue:113-136`）。勾选包含登录态时展示隐私警示。
  - 导出加密时需要输入并确认密码（1–1024 UTF-16 字符，`ConfigTransferDialogs.vue:23-26,140-151`）。
  - 导入是三步状态机：选文件 → 若加密则提示输密码 → 若识别为 Android 备份则弹警告确认；任一步失败回到对应阶段（`ConfigTransferDialogs.vue:37-80`）。
- 调用入口：`/home/yang/Git/NeriPlayer-Desktop/src/stores/sync.ts` 的 `exportConfig()` / `importConfig()`（`:818-870`）。
  - 导出把 `settings.snapshot()`、一起听 UUID 一起交给后端，带 `password` 与 `includeLoginState`。
  - 导入成功后：应用设置快照、恢复一起听 UUID、切换语言、刷新三平台登录态、通知各 store 配置失效、防止在途同步覆盖旧配置（generation 机制）。

### 1.2 数据格式与内容

后端实现：`/home/yang/Git/NeriPlayer-Desktop/src-tauri/src/commands/sync_cmd.rs:1915-2163`。

导出文件是一个 JSON 对象（`DesktopConfigFile`，`sync_cmd.rs:812-839`），`kind = "moe.ouom.neriplayer.config"`，`formatVersion = 1`，`platform = "pc"`，包含：

- `exportedAt`：毫秒时间戳；
- `settings`：整个 `AppSettings`（主题、音量、播放、歌词、下载、托盘、快捷键等全部偏好）；
- `listenTogether`：UUID、服务器地址、昵称、三个权限开关；
- `language`：语言码；
- `auth`：**仅当 `includeLoginState` 为真时**包含三平台 `AuthState`（网易/B站/YouTube 的 Cookie + 用户资料字段）；
- `githubSync` / `webDavSync`：**仅当包含登录态时**带上，包括 GitHub Token、WebDAV 用户名密码、自动同步/省流等开关与同步游标；
- `syncPreferences`：历史更新模式。

文件名 `neriplayer-desktop-config-<时间戳>.json`，用系统文件保存对话框选择路径（`sync_cmd.rs:1961-1978`）。

**是否加密 / 含凭据：**
- 不勾加密 → 明文 pretty JSON，不设文件大小限制之外的保护；
- 勾选加密 → 信封 `kind = "moe.ouom.neriplayer.config.encrypted"`，PBKDF2-HMAC-SHA256（60 万轮，16 字节随机 salt）+ AES-256-GCM（12 字节随机 IV），固定 AAD，Base64 编码，字段 `kdf/iterations/cipher/salt/iv/ciphertext`。实现见 `/home/yang/Git/NeriPlayer-Desktop/src-tauri/src/config_backup.rs:1-171`。
- 明文上限 2 MiB、文件上限 3 MiB；密码长度 1–1024 UTF-16 码元；解密前先固定算法/轮数/长度，避免恶意文件放大密码计算成本；tests 里有 Android 官方 fixture 互操作与篡改/弱密码校验（`config_backup.rs:173-303`）。
- **默认 `includeLoginState = false`**：默认备份不含任何 Cookie/Token；勾选才把明文凭据写进（可选加密的）包里。

### 1.3 迁移体验（Android ↔ PC）

- 识别：`is_android_config()` 看 `platform != "pc"` 且 `settings` 有 `booleans/floats/ints/longs/strings/stringSets` 类型分组（`sync_cmd.rs:565-574`）。
- 导入 Android 包先反序列化 `AndroidConfigFile`，未确认时返回 `requiresConfirmation`，前端弹「Android 配置会覆盖桌面设置」警告（`sync_cmd.rs:2019-2033`）。
- 字段映射（`sync_cmd.rs:576-725`）：
  - Cookie 只保留白名单名称，忽略带分号/控制字符的值，转换成桌面 Cookie jar 条目；YouTube 还要补 `google.com` 会话 Cookie；忽略 Android 的用户资料字段，导入后靠 `auth.checkStatus` 重新拉。
  - 语言码 `zh` → `zh-CN`。
  - GitHub Token/仓库、WebDAV 地址/用户名/密码、一起听、历史更新模式逐项映射；空分组不覆盖桌面已有配置。
- PC 包导入校验 `kind/formatVersion/platform`，只接受 `format_version <= 1`（`sync_cmd.rs:763-782`）。
- 导入落库顺序：先保存设置并重建 HTTP 客户端 → 替换 Cookie 并广播三个平台登录态变化 → 在 generation 校验后写同步偏好/仓库配置 → 返回应用后的设置和变更平台列表让前端刷新（`sync_cmd.rs:2069-2162`）。

### 1.4 与导入导出无关但相邻的一块：持久化模块

`/home/yang/Git/NeriPlayer-Desktop/src/modules/persistence/userData.ts` 不是备份功能，而是**本地用户状态持久化**：播放队列、播放历史（含删除墓碑）、逐曲歌词偏移、B 站跳片段规则。启动时一次性 `load_user_data_snapshot`，旧 localStorage 数据按域导入 `import_legacy_user_data`，写入经串行 Promise 链保证顺序（`:134-184`）。它解释了「哪些状态值得持久化」，但**不进入配置备份包**。

---

## 2. 参考项目：Debug 日志查看器 / 崩溃报告 / 诊断包

### 2.1 DebugView 的五个标签页

`/home/yang/Git/NeriPlayer-Desktop/src/views/DebugView.vue`（约 1400 行）：

1. **概览**：构建版本/UUID/构建时间（可点击复制）、平台与 WebView 版本、数据目录、运行时诊断（FPS、JS heap、运行时长、视口/DPI、语言/时区、网络在线、解码器、音频设备/音量/倍速/EQ）、播放器状态、GitHub/WebDAV 同步状态与上次同步时间。
2. **网络**：Debug Cookie 存储状态查看/清空（需确认对话框）、平台连通性探针（收到任意 HTTP 响应算连通，传输层失败才算不通，`DebugView.vue:72-81`）、三平台 API 详情探针（账号信息/歌单/歌曲详情/播放地址/搜索，显示耗时与 JSON，可复制，`DebugView.vue:276-351`）。
3. **日志**：常开日志开关 `logToFile` + `DebugLogViewer`。
4. **崩溃**：测试异常（受控 error、前端 uncaught、panic 命令、panic 工作线程）+ 崩溃报告列表/展开/清空。
5. **一起听**：连接状态、房间、角色、版本、成员数。

底部还有「隐藏调试模式」按钮。后端对 `debug_trigger_crash` 校验 `devModeEnabled`，前端隐藏入口不构成鉴权（`debug_cmd.rs:160-175`）。

### 2.2 DebugLogViewer 能力

`/home/yang/Git/NeriPlayer-Desktop/src/components/debug/DebugLogViewer.vue`：

- `get_recent_logs` 拉后端环形缓冲，1.5 秒轮询一次，800 条上限，新→旧拉取后反转为时间正序展示（`:73-87`）；
- 级别筛选（ERROR/WARN/INFO/DEBUG，单击切换、双击 solo）、按来源 target 下拉筛选、全文搜索（`:89-128`）；
- 暂停/恢复、自动跟随最新、清空视图（只隐藏当前时间点之前的日志，不动后端缓冲/文件）、复制可见行、单行展开看完整 target 并复制（`:168-199,229-280`）；
- 一键 `export_debug_report` 并 `reveal_in_file_manager` 定位、打开日志目录（`:149-166`）；
- 关键稳定性细节：行 key 用内容哈希而非下标（环形缓冲出队后下标会整体前移，`DebugLogViewer.vue:37-52`）；`sameEntries` 避免无变化时重渲染。

### 2.3 后端支撑

- 日志设施：`/home/yang/Git/NeriPlayer-Desktop/src-tauri/src/logging/mod.rs`。
  - 1500 条 `VecDeque` 环形缓冲，单条消息截断 2000 字符；无论文件日志开关如何都收集，保证「现在正在发生什么」可见（`:22-88`）。
  - **入环处做敏感信息打码**：整行 Cookie/Authorization、已知敏感键（MUSIC_U/SESSDATA/access_token 等）替换为 `[REDACTED]`，因为内存日志会随诊断报告带出（`:39-63`）。
  - panic hook 落盘到 `<data_dir>/NeriPlayer/logs/crashes/crash-<时间戳>.txt`：版本、系统、panic 位置、回溯、最近 80 条日志尾巴；最多保留 20 份（`:123-204`）。
  - 文件日志由 `tauri-plugin-log` 按设置 `log_to_file` 写 `<data_dir>/NeriPlayer/logs/`，单文件 8 MiB，保留 8 个；运行时切级别即时生效，切换文件日志需重启。
- 命令：`/home/yang/Git/NeriPlayer-Desktop/src-tauri/src/commands/debug_cmd.rs`。
  - `get_recent_logs`、`export_debug_report`、`list_crash_reports`、`read_crash_report`、`clear_crash_reports`、`debug_trigger_crash`、`reveal_in_file_manager`、`audio_engine_stats`。
  - 崩溃文件名白名单校验（`crash-*.txt`、无路径分隔符/穿越，`debug_cmd.rs:91-104`）。
  - `export_debug_report` 生成纯文本：版本 + OS/Arch + 时间 + 崩溃清单 + 全部内存日志（最多 1500 条，时间升序），固定写日志目录并返回路径，不弹保存对话框（`:17-56`）。
  - 注册见 `src-tauri/src/main.rs:561-593`；`get_app_data_dir` / `get_log_dir` / `get_build_info` / `probe_platform_connectivity` 在 `settings_cmd.rs`。

**一句话概括**：桌面版的调试体验 = 「统一日志出口 + 内存缓冲 + 前端筛选查看 + panic 落盘 + 一键文本诊断包 + 开发者模式保护的探针」。

---

## 3. 本仓库已有什么（可复用基础）

### 3.1 存储与配置

- `desktop/src/main/kotlin/moe/ouom/neriplayer/desktop/core/Storage.kt`
  - `AppDirs`：`dataDir`（XDG_DATA_HOME/NeriPlayer）、`configDir`（XDG_CONFIG_HOME/NeriPlayer）、`cacheDir`、`logDir`（= dataDir/logs，**已建目录但当前无人写入**）。
  - 文件清单：`library.json`、`playlists.json`、`history.json`、`stats.json`、`downloads.json`、`search_history.json`（均在 dataDir）；`settings.json`、`accounts.json`、`sync.json`（均在 configDir，后两者权限收紧到 600）。
  - `JsonFileStore<T>`：原子写（临时文件 + 删除 + 重命名），`NeriJson` 对未知键宽松、`prettyPrint`。
  - `AppSettings`：约 100 个字段的扁平 `@Serializable` data class，可直接 `NeriJson.encodeToString/decodeFromString` —— 这是做「设置快照导出」最好的抓手。
- `SettingsRepository`（同文件 `:248-294`）：内存 `StateFlow` + `replace()`/`update()`，可整体替换设置；高频音量写盘有 400ms 合并。
- `AccountRepository`（`core/Account.kt`）：`accounts.json` 存 `AccountInfo` 列表 + `Map<jarKey, Map<cookieName, cookieValue>>` 明文 Cookie，600 权限；`http.importCookies()` 注入、`save()` 快照、`logout()` 清除。**含凭据，导出时必须谨慎**。
- `SyncConfigStore`（`sync/SyncConfig.kt`）：`sync.json` 存 deviceId、GitHub Token、owner/repo、`useDataSaver`、`autoSync`、`apiBase`、同步时间/状态；文件权限 600，回包由 `SyncApi.maskToken()` 只露前 4 位。

### 3.2 同步（与桌面版「配置备份」不是一回事）

- `backend/api/SyncApi.kt`：命令 `sync.config/configure/perform/disable/status/verifyToken/listRepos/createRepo`；无文件选择、无本地备份包、无导入导出命令。Token 明文只留本机 `sync.json`。
- `sync/SyncModels.kt` + `SyncDataSerializer.kt`：`SyncData` 同步的是**业务数据**——歌单、收藏歌单、最近播放、播放统计、B 站跳过规则、删除墓碑、因果令牌等；三通道序列化（JSON `backup.json`、GZIP(ProtoBuf) `backup-raw.bin`、旧版 Base64(GZIP(ProtoBuf)) `backup.bin`），上限 12 MiB 上传 / 32 MiB 解压。
- `sync/GitHubSyncManager.kt`：远端 GitHub 仓库同步，自动同步由 `AppContainer.observeLocalChangesForAutoSync()` 每 15 秒检查本地条目数变化触发。
- **关键差异**：这套同步不携带 `settings.json`、Cookie、Token；它不是配置迁移工具。

### 3.3 前端与协议

- `backend/BackendMain.kt`：注册全部 `*Api`，`AppApi.pushInitialState()` 在连接时广播 settings/theme/player/queue/lyrics；新增命令需在 `*Api.kt` 注册并在 `BackendMain.kt` 调用 register（该文件集成负责人维护）。
- `backend/api/AppApi.kt`：`settings.get` 返回整个 `AppSettings`，`settings.set` 接受 patch 合并后 `container.settings.replace()` 并广播 —— 已有「整体替换设置」通路，导入快照可直接复用。
- `backend/api/LoginApi.kt`：`login.accounts/qrStart/qrPoll/logout/refreshProfile`；**没有**手动粘贴 Cookie 的命令，`AccountRepository` 的 cookie 快照只在扫码登录成功时写入。
- `qt/qml/globals/ApiGlobal.qml`：`Api.call()` Promise 风格 + `Api.on/off` 事件；新增命令无需改协议框架。
- `qt/qml/components/settings/SyncPanel.qml`：自包含的 GitHub 同步面板（状态卡 + 两步 Token/仓库向导 + 同步选项 + 断开确认）；由 `SettingsPage.qml:2718-2733` 以 Loader 挂载。可作为「配置导入导出」面板的排版/组件范本（`SettingsButton/SettingsSwitchRow/SettingsNote` 等）。
- QML 已有 `FileDialog` 用例（`qt/qml/components/player/NowPlayingCover.qml:352`、`SettingsPage.qml:931`），可做导入/导出路径选择。
- i18n：所有用户可见文案需 `I18n.tr("中文原文")` 并同步 `qt/qml/i18n/translations.js` 的 `en` 表。

### 3.4 日志现状（最大的缺口）

- 全仓库后端日志靠散落的 `println(...)`（Download/Mpris/Player/NeteaseAutoSource/Shortcuts/Login/HomeSections/YouTubeMusicApi 等几十处）和 `backend/CoreServer.kt:234` 的 `internal fun log() = System.err.println("[core] ...")`。
- 日志只流向启动进程的 stdout/stderr；开发时由 `desktop/qt/tools/dev.sh:70` 重定向到 `$TEST_ROOT/backend.log`；用户真实运行时没有持久化日志文件（`logDir` 建了但没用）。
- 没有级别、target/scope、时间戳、环形缓冲、`get_recent_logs`、崩溃 hook、诊断包导出命令；前端 `console.warn` 只在开发工具里可见。
- 没有可用的「构建信息/版本 UUID/运行时指标」命令（`app.bootstrap` 只有 `appVersion = "2.1.0"` 硬编码，`AppApi.kt:151`）。

---

## 4. 怎么分步做（移植方案）

以下按「风险从低到高、依赖从少到多」排序，每步都可独立交付。

### 阶段 A：设置快照导入导出（建议优先，1～2 人日）

1. 后端新增 `ConfigTransferApi.kt`（在 `BackendMain.kt` 注册）：
   - `config.exportSettings`：把 `container.settings.current`（`AppSettings`）编码为 JSON 文本，写入用户经文件对话框选定的路径；命令返回写入路径。
   - `config.importSettings`：读取 JSON → `WireJson.decodeFromString(AppSettings.serializer(), text)` → 校验 `kind/formatVersion`（如 `moe.ouom.neriplayer.config.settings` / 1）→ `container.settings.replace(imported)` → 广播 `settings`/`theme` 事件（与 `settings.set` 一致）。
   - 参数解析用 `Wire.kt` 的 `stringOrNull`，缺参 `badRequest(...)`；文件 IO 用 `withContext(Dispatchers.IO)`。
2. 前端在 `SettingsPage` 的「备份与同步」区附近加两个按钮（复用 `SettingsButton`），用 `FileDialog` 选路径；导入前用 `Library.ConfirmDialog` 提示「会覆盖当前所有设置」。
3. 更新 `desktop/docs/protocol.md`（或新建 `protocol-config.md`）与 `docs/README.md` 索引；i18n 加词条。

**明确不做**：`includeLoginState` / 密码加密 / Android 格式兼容（见 §5）。导出的就是一份人可读的 `AppSettings` JSON，用户可自行 diff/编辑。

### 阶段 B：最小日志查看器（2～3 人日）

1. 后端加一个轻量 `core/AppLog.kt`：线程安全的 `ArrayDeque` 环形缓冲（建议 1000 条，单条消息上限 2000 字符），统一 `fun log(level, target, message)`；把现有 `backend/CoreServer.kt` 的 `log()` 与若干高频 `println` 逐步改道（可只改 backend 层与关键 core 模块，不必一次全改）。
2. 新增命令 `debug.recentLogs { limit, minLevel }` → `[{ timestampMs, level, target, message }]`（新→旧）；在 `BackendMain.kt` 注册。
3. 前端新增 `SettingsPage` 里的「调试」区（或独立面板）：复用 `SyncPanel.qml` 的自包含布局风格，做一个只读列表 + 级别筛选 + 搜索 + 暂停/跟随 + 复制（可裁剪掉 target 下拉与诊断包导出）；轮询 1.5s，用 `Api.call`。
4. 文档与 i18n 同步。

### 阶段 C：可选的诊断信息（各 0.5～1 人日）

- `debug.appInfo`：版本、`AppDirs.dataDir/configDir/logDir`、OS/Arch、当前设置数量等（纯文本/JSON，只读）。
- `debug.exportLog`：把环形缓冲写成 `<logDir>/debug-report-<时间戳>.txt` 并返回路径；QML 用 `Qt.openUrlExternally` 或文件管理器定位。
- 若要做敏感信息保护，在写入缓冲前对 Cookie/Authorization/Token 值做 `[REDACTED]`（参考 `logging/mod.rs:39-63`）——**这一步在 C 之前做才安全**，因为查看器会把日志显示出来。

### 阶段 D：不建议做 / 长期观望（见 §5）

---

## 5. 风险、工作量与「哪些不建议做」

### 不建议做

1. **完整配置克隆（含 Cookie/Token + 密码加密 + Android 互操作）**。
   - 桌面版的 `config_backup.rs` 是一套经过安全审计的独立子系统（KDF 参数固定、AAD、Base64 规范化、篡改检测、60 万轮 PBKDF2 的 3 MiB 上限、Android fixture 互操作），移植它不是「半天加个命令」：需要新增加密依赖、严格测试、还要在本仓库定义与 Android 完全一致的 schema（`AndroidConfigFile` 几十个字段及映射）。收益却很窄——本仓库面向 Linux 桌面，Android 用户直接走 GitHub 云同步同步业务数据即可；配置克隆的主要受众是「换机迁移」，频率极低。
   - 更关键的是**凭据扩散风险**：一旦导出含明文 Cookie/Token 的包（哪怕可选加密），用户就会把它存到网盘/发到聊天工具。本仓库的 `accounts.json` / `sync.json` 已经是 600 权限的本地机密，把它变成可导出的普通文件是安全模型的改变，需要产品层面明确决定，不应由这个调查顺手带出。
2. **panic hook + 崩溃报告体系**。
   - 本仓库后端是 JVM/Kotlin，`Thread.setDefaultUncaughtExceptionHandler` 能拿到未捕获异常，但 `System.err` 已有大部分错误输出；要做得像桌面版（版本 + 回溯 + 最近 80 条日志尾巴 + 轮转 + 前端列表/读取/清空 + 测试触发 + 后端开发者模式校验）是完整的一条链，至少 3～5 人日。
   - 而且本仓库的「崩溃」常常不是后端 JVM panic，而是 Qt/QML 侧或 GStreamer/MPRIS 外部进程问题，panic hook 覆盖面有限。建议先靠阶段 B 的日志 + 用户手动反馈，等确实复现困难再补。
3. **API 详情探针 / 平台连通性探针 / 运行时 FPS/Heap 面板**。
   - 这套是桌面版对齐 Android 探针页的调试工具，依赖大量平台接口命令与 WebView API（`performance.memory`、`requestAnimationFrame`、`navigator.userAgent`）。本仓库是 QML，没有等价的 JS 指标；硬搬要改 C++/QML 且容易与现有 `OnlineApi`/`LoginApi` 的职责重叠。非必要，不做。
4. **在前端直接读写 `settings.json`/`accounts.json` 文件**。
   - 必须全部经后端命令，保持 600 权限与原子写语义；文件对话框只用于选路径。

### 主要风险点

- **配置覆盖不可逆**：导入 `AppSettings` 会覆盖全部设置（含 musicFolders、字体、悬浮歌词位置等）。必须确认对话框 + 建议导出前提示用户先备份；导入失败（坏 JSON/版本不符）要先完整解析成功再 `replace()`，不要写半个文件。
- **并发写冲突**：`SettingsRepository.updateDeferred()` 有 400ms 延迟写盘；导入时若音量滑块正在调整，可能出现「导入后延迟写把旧值写回」。阶段 A 实现时要先 cancel deferred job 或让 `replace()` 与之互斥（可在 `SettingsRepository` 加一个 `flush()`/`cancelDeferred()`，需归属人确认共享文件修改权限）。
- **日志中的隐私**：现有 `println` 里可能有 URL、搜索词、Cookie 名（`LoginApi.kt:119` 打印了 Cookie **键名**）。阶段 B 一旦把日志展示出来/写进诊断包，就要同步加脱敏；不要先上线查看器再补脱敏。
- **共享文件改动**：按 `AGENTS.md` 并行协作规则，`BackendMain.kt`、`Storage.kt`、`AppContainer.kt` 属于集成负责人维护；阶段 A/B 新增命令注册需要先通知集成负责人，或把 register 调用收敛到新 API 的 `object`（仅一行）由集成方统一接入。
- **文档同步**：新增命令/事件必须写进 `desktop/docs/protocol*.md` 与 `docs/README.md`，否则不算完成。

### 工作量估计（仅供排期）

| 阶段 | 后端 | 前端 | 文档/测试 | 小计 |
| --- | --- | --- | --- | --- |
| A 设置快照导入导出 | 1 人日 | 0.5 人日 | 0.5 人日 | ~2 人日 |
| B 日志缓冲 + 查看器 | 1.5 人日 | 1 人日 | 0.5 人日 | ~3 人日 |
| C 诊断信息/导出 | 1 人日 | 0.5 人日 | 0.5 人日 | ~2 人日 |
| D（崩溃/加密/Android 兼容） | — | — | — | 不建议本次做 |

---

## 6. 关键文件路径速查

**参考项目（NeriPlayer-Desktop）**
- 配置对话框：`src/components/settings/ConfigTransferDialogs.vue`
- 配置 store：`src/stores/sync.ts:818-870`
- 导出/导入命令：`src-tauri/src/commands/sync_cmd.rs:1915-2163`（数据结构 `:220-359,727-839`；Android 映射 `:565-725`）
- 加密信封：`src-tauri/src/config_backup.rs`
- Debug 页：`src/views/DebugView.vue`
- 日志查看器：`src/components/debug/DebugLogViewer.vue`
- 调试命令：`src-tauri/src/commands/debug_cmd.rs`
- 日志设施：`src-tauri/src/logging/mod.rs`
- 持久化模块：`src/modules/persistence/userData.ts`

**本仓库（neriplayer）**
- 存储/设置：`desktop/src/main/kotlin/moe/ouom/neriplayer/desktop/core/Storage.kt`
- 账号 Cookie：`.../core/Account.kt`
- 同步配置：`.../sync/SyncConfig.kt`
- 同步数据模型/序列化：`.../sync/SyncModels.kt`、`.../sync/SyncDataSerializer.kt`
- 同步命令：`.../backend/api/SyncApi.kt`
- 应用与设置命令：`.../backend/api/AppApi.kt`（`settings.get/set`、`app.bootstrap`）
- 登录命令：`.../backend/api/LoginApi.kt`
- 命令注册：`.../backend/BackendMain.kt`
- 后端 log 函数：`.../backend/CoreServer.kt:234`
- 前端通信：`desktop/qt/qml/globals/ApiGlobal.qml`
- 同步面板：`desktop/qt/qml/components/settings/SyncPanel.qml`
- 设置页挂载点：`desktop/qt/qml/pages/SettingsPage.qml:2718-2733`
- 协议文档：`desktop/docs/protocol-sync.md`、`desktop/docs/README.md`
