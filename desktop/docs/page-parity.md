# Android 上游 vs 本桌面版 · 页面功能对齐清单

> **功能对齐追踪文档**。基于源码静态核实（Android 上游 `app/`、`modules/`；本桌面版 `desktop/qt/qml/`、`desktop/src/main/kotlin/.../backend/api/`），
> 核对日期：**2026-11-21**。本桌面版为 Kotlin 无头后端 + Qt6/QML 前端，命令清单见 `protocol-*.md`。
> 本文档只描述功能覆盖状态，用于后续排期追踪，不代表任何实现优劣比较。

## 图例

| 标记 | 含义 |
| --- | --- |
| ✅ | 已对齐：桌面具备与 Android 同等能力 |
| ⚠️ | 部分：已有基础形态，细节/子项仍有差距 |
| ➖ | 平台不适用：Android 端能力依赖移动端环境，桌面无需移植 |
| 🔜 | 需后端新命令：前端可做，但依赖后端新增协议/接口（后续可做） |

## 一、媒体库

桌面一级 Tab 已平铺为 **本地 / 收藏 / 网易云 / 哔哩哔哩**（YouTube 按设置开关显隐），
对应 Android 的平铺音源 Tab（本地 / 收藏 / 网易云 / YouTube / B站 / 音乐服务器）。

| 功能项 | 状态 | 说明 |
| --- | --- | --- |
| 一级音源 Tab 平铺 | ✅ | 本地/收藏/网易云/B站，YouTube 开关显隐；Android 平铺含音乐服务器 |
| 本地子分类：歌曲/歌手/专辑/歌单 | ✅ | 桌面四分类，Android 仅歌单/歌手，属桌面增强 |
| 收藏页：我喜欢的音乐列表 | ✅ | 桌面已实现歌曲列表 |
| 收藏页富聚合（歌单/歌手/热点） | ⚠️ | Android 为三类聚合；桌面仅歌曲列表 |
| 跨 source 收藏歌单/专辑 | 🔜 | 需后端新增收藏命令，覆盖网易云/B站等多来源 |
| 关注歌手（平台子切换+远端拉取） | 🔜 | 需后端新增关注/取关注列表命令 |
| 周/月热点歌单预览 | 🔜 | 需后端新增热点歌单榜命令 |
| 网易云云盘、歌单/专辑二级分类 | 🔜 | 需后端新增对应分类查询命令 |
| B站收藏夹分类（创建/订阅/合集/系列） | 🔜 | 需后端新增 B站收藏夹分类命令 |
| 音乐服务器（Subsonic/Navidrome） | 🔜 | 桌面零实现，需后端接入 Subsonic 协议栈 |

## 二、首页

聚合入口为后端 `online.home`（`HomeSections.kt`），桌面已有完整栏目骨架，差距集中在细分条。

| 功能项 | 状态 | 说明 |
| --- | --- | --- |
| 继续播放 / 猜你喜欢 / 每日推荐 | ✅ | 已由 `online.home` 聚合 |
| 私人FM / 排行榜 / 雷达 / 推荐歌单 / 新歌 | ✅ | 桌面已实现 |
| B站音乐 / YouTube shelves | ✅ | 桌面已实现 |
| 雷达歌单细分条（时光/宝藏等 5 个） | ⚠️ | 桌面有雷达整体入口，细分条未对齐 |
| 飙升榜/热歌榜等榜单细分 | ⚠️ | 桌面有排行榜入口，细分榜单未对齐 |

## 三、探索

| 功能项 | 状态 | 说明 |
| --- | --- | --- |
| 音源切换（网易云/B站/YouTube） | ✅ | 已实现 |
| 搜索 + 搜索历史 | ✅ | 已实现 |
| 风格标签 / 推荐歌单 | ✅ | 已实现 |
| 搜索结果分类 | ✅ | 已实现 |
| 链接识别 Tab | 🔜 | 需后端新增链接解析命令 |
| B站分 P 选择弹窗 | 🔜 | 依赖视频信息解析命令，桌面未做 |

## 四、播放页

| 功能项 | 状态 | 说明 |
| --- | --- | --- |
| 封面 / 双语逐字歌词 / 进度 / 播放控制 | ✅ | 已实现 |
| 音效 / 队列 / 评论 / 一起听 | ✅ | 已实现 |
| 音量 / 睡眠 / 下载 / 沉浸模式 | ✅ | 已实现 |
| B站视频跳过区间设置入口 | ✅ | 桌面设置页已有入口（见 `protocol-bili-video-skip.md`） |
| 波形进度条 | ⚠️ | 桌面为常规进度条，波形待做 |
| 歌曲百科 | 🔜 | 需后端新增歌曲百科/资料命令 |
| 歌词分享海报 | 🔜 | 需后端配合生成海报数据 |
| 音质切换弹窗 | 🔜 | 需后端新增音质档位查询/切换命令 |
| B站 CC 字幕设置 | 🔜 | 需后端新增字幕轨道命令 |
| 歌手选择器 | 🔜 | 需后端新增歌手列表/切换命令 |
| 歌词页高级模式 | ⚠️ | 桌面有逐字歌词，高级模式细节未对齐 |

## 五、设置页

桌面 10 类：账号与同步 / 常规 / 外观主题 / 音效倍速 / 播放 / 歌词 / 快捷键 / 下载与存储 / 备份同步 / 关于。
已覆盖 Android 8 组，并多出快捷键与 MPRIS（桌面适配项）。

| 功能项 | 状态 | 说明 |
| --- | --- | --- |
| Android 8 组设置项 | ✅ | 桌面 10 类已覆盖并扩展 |
| 快捷键 / MPRIS | ✅ | 桌面平台增强 |
| USB 独占 | ➖ | 移动端音频通路，桌面不适用 |
| 外部歌词（小米岛） | ➖ | 厂商系统特性，桌面不适用 |
| 流量管理 | ➖ | 移动网络场景，桌面不适用 |

## 后续可跟进清单（按性价比排序）

| 优先级 | 跟进项 | 需要的后端命令 | 主要涉及的前端文件 |
| --- | --- | --- | --- |
| 1 | 首页雷达/榜单细分条 | `online.home` 扩展细分字段（`HomeSections.kt`），一般无需新协议 | `pages/HomePage.qml`、`components/home/` |
| 2 | B站视频跳过区间（完善） | 已有 `bili.videoSkip.*`，按 `protocol-bili-video-skip.md` 补齐 | `pages/SettingsPage.qml` 对应设置组件 |
| 3 | 跨 source 收藏歌单/专辑 | `collection.favorite*`（扩展 `protocol-collections.md`） | `pages/LibraryPage.qml`、`components/library/` |
| 4 | 关注歌手 | `artist.follow` / `artist.following`（新） | 收藏页歌手子页、`components/library/` |
| 5 | 热点歌单榜（周/月） | `online.hotPlaylists`（新） | `pages/FavoritePage.qml`、`components/library/` |
| 6 | 网易云云盘/二级分类 | `netease.cloudDisk` / `netease.playlistCategory`（新） | `pages/NeteasePage.qml` |
| 7 | B站收藏夹分类 | `bili.favoriteCategories`（新） | `pages/BiliPage.qml` |
| 8 | 探索链接识别 / B站分 P | `parse.link` / `bili.videoParts`（新） | `pages/ExplorePage.qml`、`components/panels/` |
| 9 | 播放页：音质切换/百科/字幕/歌手选择器 | `audio.quality*`、`song.info`、`bili.subtitle*`、`artist.list`（新） | `components/player/`、`components/panels/` |
| 10 | 音乐服务器 Subsonic/Navidrome | 新增 Subsonic 协议适配层与 `musicServer.*` 命令 | `pages/LibraryPage.qml` 新增音源 Tab |

> 备注：路径为相对 `desktop/qt/qml/` 的简写；后端命令以 `desktop/docs/protocol-*.md` 实际注册名为准。
> 每次对齐完成后请同步更新本文档状态标记与核对日期。
