# 追平 Tauri 参考版 · 功能差距调查索引

> 2026-11-20 逐项调查了 Tauri + Vue 参考项目（`/home/yang/Git/NeriPlayer-Desktop`）相对本仓库 Linux 桌面版的功能差距。
> 以下 5 份文档均为**纯调查，不含代码改动**。调查报告含具体文件路径、机制说明、工作量与风险。
> （用户明确排除：波形进度条、Hyper 动态背景，未纳入调查。）

## 总览优先级（按「性价比 = 收益 / 工作量 / 风险」排序）

| 优先级 | 功能 | 工作量 | 风险 | 建议 |
| :---: | :--- | :--- | :--- | :--- |
| ★★★ | [存储管理对话框](storage-management.md) | 1.5–3 人天 | 低（只读统计+白名单清理） | **最适合先做**：先加只读 `storage.usage`，用户能看到占用 |
| ★★★ | [歌词逐字高亮（悬浮歌词窗）](lyrics-share-karaoke.md) | 中 | 低 | 悬浮歌词窗目前只有整行文字，补裁切层收益最大 |
| ★★☆ | [设置快照导入导出](config-transfer-debug.md) 阶段 A | ~2 人天 | 中（导入覆盖不可逆） | 先做不含凭据/加密的最小版 |
| ★★☆ | [AutoEQ 文本导入 + 频响图](dsp-autoeq.md) 步骤 1+2 | 1–2 人日（仅导入） | 低（纯算法/UI，不动音频链） | 见效快；**不要碰实时 DSP 引擎** |
| ★★☆ | [日志查看器](config-transfer-debug.md) 阶段 B | 2–3 人天 | 中（日志隐私须先脱敏） | 加轻量环形缓冲 + 只读查看器 |
| ★☆☆ | [B站视频跳过规则](bili-video-skip.md) | 5–7 人日 | 中（自动 seek 链路） | 功能完整但工作量大，排后面；规则结构一开始就带 `modifiedAt/isDeleted` |
| ★☆☆ | [歌词海报](lyrics-share-karaoke.md) v1 | 2–3 天 | 中（grabToImage 风险需先 spike） | QML 画 1080 图 + 复用现有 `artwork.saveAs` |
| ✗ | 原生 DSP 引擎全量移植 / 崩溃 panic 体系 / GLSL 律动背景 / 原生社交分享 / 配置加密+Android 互操作 | 很大 | 高 | **不建议做**（详见各文档「不建议」章节） |

## 各文档要点

### [dsp-autoeq.md](dsp-autoeq.md)
参考版比我们多的是**整条音效管线**（40+ 参数、10 段参数 EQ、压缩/限幅/环绕/混响、频响曲线、简单/专家模式）。
我们只有 10 段固定 Q 峰值 EQ + 响度开关，且每次调参都重启 ffmpeg 进程。
AutoEQ 是 Equalizer APO 的 ParametricEQ 文本校正参数，参考版纯前端解析、无网络。
**先做** AutoEQ 导入（1–2 人日）与频响图，不碰 9000+ 行原生 DSP 引擎。

### [config-transfer-debug.md](config-transfer-debug.md)
参考版配置导出=整个 AppSettings + 可选凭据（PBKDF2+AES 加密）、可按 Android 备份格式导入；Debug 页有日志环形缓冲（1500 条、脱敏）、网络探针、崩溃报告、诊断包导出。
我们已有整体设置替换通路与 FileDialog 范式，但没有任何本地备份/日志缓冲。
**先做**设置快照导入导出（~2 人日）和最小日志查看器（含脱敏，2–3 人日）；不做完整凭据克隆与 panic 体系。

### [bili-video-skip.md](bili-video-skip.md)
功能=按 BVID+分 P CID 自定义跳过区间（如片头口播），播放时自动 seek；与社区 SponsorBlock 不同（用户自定义、本地存储）。
我们 BVID/CID 数据已具备（Song.remoteId/extraId + `videoPages()` 兜底）。
后端加规则存储+3 命令，前端加编辑弹窗；约 5–7 人日。
**关键**：自动 seek 必须统一走 `Player.kt::seekTo()`（内存缓存入口），绝不直接调 `engine.seekTo()`；一起听中禁用本地跳过。

### [storage-management.md](storage-management.md)
参考版是 M3 对话框，按 4 分组显示字节数/文件数，6 类清理，算法有 canonical 去重与排除前缀。
我们只有 4 个清理按钮、无占用数字；关键区分 `artworkDir`（抽取封面，**不可清**）与 `coverDir`（可清）、`/dev/shm` 内存缓存不算磁盘。
**先做**只读 `storage.usage` + 白名单清理 `coverDir`（1.5–3 人天）；下载目录/配置目录只读，危险操作二次确认。

### [lyrics-share-karaoke.md](lyrics-share-karaoke.md)
参考版「歌词海报」=选句弹窗 + Canvas 2D 画 1080 宽 PNG（不传 RGBA 走 IPC）+ 保存；逐字动画=三层叠字+clip-path 推进+词宽精确定位。
我们已支持 yrc 逐字 + 可调吸附滚动动画，但**悬浮歌词窗只有整行文字**。
**先做**悬浮歌词逐字高亮 → 海报 v1（QML `grabToImage` + 复用 `artwork.saveAs`，零后端绘图改动，需先 spike 离屏渲染）→ 长句滚动/样式扩展。
不建议原生分享、剪贴板图片、Kotlin 画图、GLSL 背景。

## 共性约束

- 共享文件（`BackendMain.kt`、`Storage.kt`、`AppContainer.kt`、`SettingsPage.qml`）由集成负责人统一接线，各功能实现者不要自行改。
- 新增命令/事件必须同步更新 `desktop/docs/protocol*.md`。
- 所有用户可见文案用 `I18n.tr()` 包裹并补 `i18n/translations.js`。
- 每份调查报告末尾均有「关键文件路径速查表」，实现前先按图索骥读源码。
