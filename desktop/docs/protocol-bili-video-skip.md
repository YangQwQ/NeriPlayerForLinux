# B站视频跳过规则 · IPC 协议（protocol-bili-video-skip）

领域：用户自定义「B站视频跳过区间」的规则管理与查询。实现：`backend/api/BiliVideoSkipApi.kt` +
`core/BiliVideoSkip.kt`（模型、归一化、JSON 仓库）。前端编辑器：
`qt/qml/components/settings/BiliVideoSkipDialog.qml`。

方案背景见 `desktop/docs/investigations/bili-video-skip.md`。

本领域**只做规则 CRUD 与查询**：播放中「进度走到区间起点 → 自动 seek 到终点」的 hook
由播放链路单独接线（前端订阅 `player` 事件的 `positionMs`，统一调用 `player.seekTo`；
不要绕过后端 `PlayerManager.seekTo()` 直接调引擎）。本协议不定义跳过动作本身。

## 0. 数据模型

规则以 `(bvid, cid)` 为唯一键——`cid` 是**分P 的 cid**，不是视频 aid，
所以同一个视频的不同分P 各有独立规则。

```jsonc
// BiliVideoSkipRule
{
  "bvid": "BV1GJ411x7h7",              // BVID
  "cid": 137649199,                     // 分P 的 cid
  "intervals": [                        // 已排序、已合并、已裁剪
    {"startMs": 5000, "endMs": 15000}
  ],
  "modifiedAt": 1795176323713,          // 毫秒时间戳，每次真实修改递增
  "isDeleted": false                    // 删除墓碑
}
```

字段约定：

| 字段 | 说明 |
| --- | --- |
| `modifiedAt` | 最后修改时间（毫秒）。同一目标多次修改时**严格递增**，将来接同步时用于冲突合并（时间大者胜） |
| `isDeleted` | 删除墓碑。清空区间是写墓碑而不是删记录，否则同步时旧快照会把已删规则复活 |
| `intervals` | 毫秒区间，左闭右开语义上的「跳到 `endMs`」。排序后**相邻/重叠一定合并** |

限制（`BiliVideoSkipLimits`，与参考实现一致）：

| 限制 | 值 | 行为 |
| --- | --- | --- |
| 单目标区间段数 | 100 | 归一化时截断，保留最早的 100 段 |
| 规则总条数 | 2000 | 归一化时按 `(bvid, cid)` 排序后截断 |
| 单段时间上限 | 24 小时 | 超出按 86400000 截断 |

归一化（写入与读盘都会执行）：

1. `startMs` 裁到 `>= 0`，`endMs` 裁到 `<= durationMs`（`durationMs` 为 0 时用 24 小时上限）；
2. 丢弃 `endMs <= startMs` 的无效段；
3. 按 `(startMs, endMs)` 排序；起止相接或重叠的段合并成一段；
4. 截断到 100 段。

规则级归一化：按 `(bvid, cid)` 去重（`modifiedAt` 大者胜；同时间墓碑优先于活规则，
两个活规则则合并区间）、丢弃空活规则、按 `bvid`/`cid` 排序、截断到 2000 条。

存储：`$XDG_DATA_HOME/NeriPlayer/biliVideoSkip.json`（`AppDirs.dataDir`，随用户数据）。
读盘时会做一次归一化，因此手改过的脏 JSON 不会让后端崩或产生非法区间。

## 1. 命令清单

| method | params | result |
| --- | --- | --- |
| `biliVideoSkip.getRules` | — | `{rules: BiliVideoSkipRule[]}` |
| `biliVideoSkip.getTargets` | `{bvid}` | `{targets: BiliVideoSkipTarget[]}` |
| `biliVideoSkip.setRule` | `{bvid, cid, intervals, durationMs?}` | `{ok:bool, rule: BiliVideoSkipRule\|null}` |

## 2. `biliVideoSkip.getRules`

返回**生效中**的规则（过滤掉 `isDeleted` 墓碑），**新 → 旧**（`modifiedAt` 降序），
供设置页列表直接渲染。

```jsonc
// 请求
{}
// 结果
{"rules": [
  {"bvid": "BV1FPjy6TEiE", "cid": 39252201332,
   "intervals": [{"startMs": 0, "endMs": 25000}, {"startMs": 190000, "endMs": 205000}],
   "modifiedAt": 1795176493984, "isDeleted": false}
]}
```

墓碑规则不会出现在结果里；编辑某个目标时要读回它自己的草稿时也用这个命令，
按 `bvid` + `cid` 在结果里查找即可（墓碑目标返回「没有规则」，等价于空列表）。

## 3. `biliVideoSkip.getTargets`

给一个 BVID，返回它的**全部分P**，供编辑器做 cid 下拉。

```jsonc
// 请求
{"bvid": "BV1FPjy6TEiE"}
// 结果
{"targets": [
  {"bvid": "BV1FPjy6TEiE", "cid": 39252201332, "page": 1,
   "label": "001.周杰伦-晴天", "durationMs": 270000},
  {"bvid": "BV1FPjy6TEiE", "cid": 39252201462, "page": 2,
   "label": "002.周杰伦-夜曲", "durationMs": 227000}
]}
```

| 字段 | 说明 |
| --- | --- |
| `cid` | 分P 的 cid，写规则时原样回传 |
| `page` | 分P 序号（从 1 开始） |
| `label` | 分P 标题（`part`）；为空时回退成 `P<page>` |
| `durationMs` | 该分P 时长，0 表示接口没给（编辑器用它做区间上界校验与提示） |

数据源与降级：

1. `https://api.bilibili.com/x/player/pagelist?bvid=<bvid>`（`data` 是分P 数组，带 `duration`）；
2. 上面失败/被风控时退回 `https://api.bilibili.com/x/web-interface/view?bvid=<bvid>`
   （分P 在 `data.pages` 里，同样带 `duration`）。

不额外引入网络层：直接复用 `OnlineRepository.httpService`（共享 Cookie / gzip / 超时）。
`bvid` 不以 `BV` 开头、视频不存在、网络不可用时都返回**空数组**（命令本身仍成功），
前端应显示「没有取到分P列表」而不是报错弹窗。

> 不用 `BiliApi.videoPages()` 的原因：它只返回 `(cid, part)`，没有 `duration` 与分P 序号，
> 而区间编辑需要时长做上界校验。

## 4. `biliVideoSkip.setRule`

新增 / 更新 / 删除某个 `(bvid, cid)` 的跳过区间。**整条覆盖**语义（不是增量合并）：
前端传完整区间列表，后端归一化后整条替换。

```jsonc
// 请求（正常保存）
{"bvid": "BV1GJ411x7h7", "cid": 137649199, "durationMs": 213000,
 "intervals": [{"startMs": 5000, "endMs": 15000}, {"startMs": 30000, "endMs": 40000}]}

// 请求（删除：空数组 = 写墓碑）
{"bvid": "BV1GJ411x7h7", "cid": 137649199, "intervals": []}

// 成功
{"ok": true, "rule": {"bvid": "BV1GJ411x7h7", "cid": 137649199,
  "intervals": [{"startMs": 5000, "endMs": 15000}, {"startMs": 30000, "endMs": 40000}],
  "modifiedAt": 1795176323713, "isDeleted": false}}

// 目标本来就没有规则、又传空区间：无事可做
{"ok": false, "rule": null}
```

行为：

| 输入 | 行为 |
| --- | --- |
| `intervals` 归一化后非空 | 写活规则，`modifiedAt` 递增 |
| `intervals` 为空/全部无效，且该目标**已有**规则 | 写墓碑（`isDeleted=true`，区间清空） |
| `intervals` 为空，且该目标**从未有过**规则 | 不落盘，返回 `{ok:false, rule:null}` |
| 归一化后与已有规则完全相同 | **不写盘**、不刷新 `modifiedAt`（用户只是点了保存） |
| 传了 `durationMs` | 用它裁剪区间上界（编辑器选中分P 的时长） |

参数校验（违反返回 `bad_request`）：

| 情况 | 错误信息 |
| --- | --- |
| 缺 `bvid` 或为空 | `缺少 bvid` |
| `cid` 缺失 / `<= 0` | `cid 必须是正整数` |

## 5. 事件

### `biliVideoSkip.changed`

每次调用 `setRule` 后广播（无论是否落盘），前端据此刷新规则列表与播放侧缓存。

```jsonc
{"event": "biliVideoSkip.changed",
 "data": {"bvid": "BV1GJ411x7h7", "cid": 137649199,
          "rule": {"bvid": "BV1GJ411x7h7", "cid": 137649199, "intervals": [],
                   "modifiedAt": 1795176323724, "isDeleted": true}}}
```

`rule` 为 `null` 表示这次调用什么都没写（见上表最后一行）。

## 6. 前端接入要点

编辑器（`BiliVideoSkipDialog.qml`）对外只有两类入口：

```qml
// 打开：bvid 必填；cid 传当前播放的实际分P（可为空，空则选第一个分P）；title 只用于展示
biliSkipDialog.openFor(Player.song.remoteId, Player.song.extraId, Player.title)
```

- 入口按 `Player.song.source === "BILIBILI"` 显示（当前曲目不是 B站源时不显示）。
- 对话框会用 `Player.positionMs` / `Player.seekTo` 做「取当前时间」「±1s / ±5s」，
  这些调用都走现成的 `player.seekTo` 命令，因此自动获得内存缓存 seek 的瞬时跳转能力。
- 「起点取当前 / 终点取当前」与微调按钮**只在选中的分P 就是当前播放分P 时**出现；
  否则只允许手动输入时间（`matchesPlayback` 判断，当前曲目没有 `extraId` 时退化为「分P 1」）。
- 文案走 `I18n.tr("中文原文")`，英文词条由集成负责人统一加进 `i18n/translations.js`。

## 7. CID 来源可靠性（已知限制）

- `Song.remoteId` 是 BVID、`Song.extraId` 是 cid；但**搜索结果**（`mapSearchResults`）
  只写 `remoteId`，不写 `extraId`。所以「从搜索直接播放」的曲目在编辑器里只能靠分P 序号猜当前分P。
- 播放取流时 `OnlineRepository.resolvePlayback` 会在 `extraId` 缺失时自己查 `videoPages(bvid)` 取第一个分P，
  这个解析结果目前**没有回填到 `Song`**，编辑器与自动跳过 hook 都看不到。
  若以后需要精确匹配，应在解析处把实际 cid 记下来（`songKey -> (bvid, cid)` 映射或给 `Song` 加字段）。
- 分P 列表来自 B站公开接口，视频被删/私密/地区限制时为空数组；分P 极多（几百）时下拉限高滚动。
- 规则只存本地 JSON，**尚未接用户数据同步**；结构里的 `modifiedAt` / `isDeleted` 是为同步预留的。
