# NeriPlayer 桌面版 · 文档索引

本目录只保留**索引与约定**，不再堆叠冗长的历史盘点。要看实现细节请直接读源码，符号名与文件路径都在下表。

## 一、协议（后端命令与事件）

| 文档 | 覆盖范围 |
| --- | --- |
| [protocol.md](protocol.md) | IPC 行分隔 JSON 基础协议、请求/响应/事件信封、错误码 |
| [protocol-online.md](protocol-online.md) | `online.*` 探索/搜索/详情/歌词/播放地址 |
| [protocol-library.md](protocol-library.md) | `library.*` 本地媒体库与扫描 |
| [protocol-collections.md](protocol-collections.md) | `collections.*` 歌单/收藏、`history.*`、`stats.*` |
| [protocol-download.md](protocol-download.md) | `download.*` 下载队列与离线索引 |
| [protocol-sync.md](protocol-sync.md) | `sync.*` GitHub 跨设备同步 |
| [protocol-artwork.md](protocol-artwork.md) | `artwork.*` 封面解析/缓存/另存 |
| [protocol-comments.md](protocol-comments.md) | `comments.*` 评论 |
| [protocol-youtube.md](protocol-youtube.md) | `ytmusic.*` YouTube Music |
| [protocol-listen-together.md](protocol-listen-together.md) | `listenTogether.*` 一起听 |
| [protocol-login.md](protocol-login.md) | `login.*` 扫码登录与账号态 |
| [protocol-audio-effects.md](protocol-audio-effects.md) | `audioEffects.*` DSP 音效 / AutoEQ / 频响曲线 |
| [protocol-storage.md](protocol-storage.md) | `storage.*` 存储占用统计与缓存清理 |
| [protocol-bili-video-skip.md](protocol-bili-video-skip.md) | `biliVideoSkip.*` B站分P跳过区间 |
| [protocol-config.md](protocol-config.md) | `config.*` 设置快照导入导出、`debug.recentLogs` 日志 |
| [core-api-inventory.md](core-api-inventory.md) | 核心对象图、状态流、数据模型、设置项字段的**索引** |

## 二、前端（Qt6/QML）

| 文档 | 覆盖范围 |
| --- | --- |
| [../qt/docs/qt-dev-guide.md](../qt/docs/qt-dev-guide.md) | 构建/运行/截图、QML 全局对象（Api/Player/Theme/Settings/Nav/Toast/I18n/Prefs）、组件与文件所有权、验收要求 |
| [../qt/docs/design-spec.md](../qt/docs/design-spec.md) | 视觉与交互设计规格：主题色角色、字体排版、间距/圆角/尺寸、页面与组件规格 |

## 三、仓库协作

| 文档 | 覆盖范围 |
| --- | --- |
| [/AGENTS.md](../../AGENTS.md) | 目录速查、构建命令、编码约定、并行协作规则、环境注意 |
| [upstream-sync.md](upstream-sync.md) | 与上游 `cwuom/NeriPlayer` 的同步方式 |

## 四、找东西的通用方法

```bash
# 后端命令注册在哪
grep -rn 'server.on("' desktop/src/main/kotlin/.../backend/api/

# 设置项字段与默认值
grep -n 'data class AppSettings' -A 260 desktop/src/main/kotlin/.../core/Storage.kt

# QML 全局对象
ls desktop/qt/qml/globals/

# 路由名 → 页面文件
grep -n 'sourceForRoute' -A 40 desktop/qt/qml/AppShell.qml
```

约定：新增命令/事件必须同步更新对应的 `protocol-*.md`；新增设置项必须加进 `AppSettings`（`Storage.kt`），否则 `settings.set` 会被严格反序列化丢弃。
