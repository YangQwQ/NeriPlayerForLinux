# 调查：歌词分享海报 + 逐字歌词动画增强

> 纯调查文档，不改代码。调查日期 2026-11-20。
>
> 参考项目：`/home/yang/Git/NeriPlayer-Desktop`（Vue 3 + Tauri 桌面版，下文简称「参考版 / NeriPlayer-Desktop」），
> 其海报实现本身又移植自本仓库 Android 端 `app/.../ui/component/lyrics/LyricShareSheet.kt`。
> 本项目：`/home/yang/Git/neriplayer` 的 `desktop/`（Kotlin 无头后端 + Qt6/QML 前端）。

## 0. 结论速览（TL;DR）

1. **「歌词海报」和「逐字动画」在参考版里是两套独立系统**，不要混为一谈：
   - 海报 = 一次性把勾选的几句歌词**画成静态 PNG**（Canvas 2D 自绘，1080 宽），用于保存/复制图片；**海报上没有逐字高亮**。
   - 逐字动画 = 独立的**悬浮歌词窗口**（`KaraokeLine.vue` + `timeline.ts` + GLSL 背景），和海报无关。
2. 海报的视觉是「**模糊封面铺底 + 对角蒙层 + 中部大号歌词 + 底部毛玻璃信息栏 + 左上角品牌**」，
   几何数值直接照搬 Android `LyricShareSheet.kt`（padding 88 / lyricsTop 246 / 底栏 214 / 封面位 132 圆角 28）。
   生成走**手写 Canvas 2D 绘制**（含手写可分离盒式模糊），不走 html2canvas 之类 DOM→图库。
3. 我们移植海报**不需要动后端 Kotlin 的绘图**：推荐 **QML `Item.grabToImage()`** 画一张全尺寸海报 Item，
   存到临时文件后**复用现有 `artwork.saveAs`**（它接受本地 `path` + 用户选的 `target`）落盘。
   C++ `QImage/QPainter` 是备选（要新增 C++ 类、要重新构建、改动面更大），仅当 `grabToImage` 在离屏/Effect 下不可靠时再上。
4. 逐字动画我们**底子已经不差**（yrc 解析 + 逐词进度 + 可调吸附滚动）。最大缺口不是播放页，
   而是**悬浮歌词窗口完全没有逐字高亮**（只有当前整行文字）；其次是逐字定位用 `FontMetrics.advanceWidth` 求和的近似、
   长句不滚动、样式项偏少。
5. 建议顺序：①悬浮歌词补逐字高亮（收益/代价比最高）→ ②海报 v1（QML grabToImage + 复用 saveAs）→
   ③长句往返滚动、样式扩展。**不建议**：原生社交分享、剪贴板图片、移植 GLSL 律动背景、Kotlin 侧画图、追求像素级一致。

---

## 1. 参考版做了什么

### 1.1 歌词分享 / 海报

#### 入口与交互（`src/components/LyricShareDialog.vue`）

- 从歌词行右键菜单打开（`src/components/LyricsView.vue:868` 挂载 `<LyricShareDialog>`），
  传入 `lines`（可选歌词行）、`initialIndex`（右键点的那句）。
- **两阶段弹窗**：
  1. `select`：勾选要放进海报的歌词行。默认从右键那句**往后**选 5 句（写词顺序的原因，选「正在唱的这段」），
     有「全选 / 清空 / 复制选中文本」。勾选时按「折行后的视觉行数」实时试算预算，
     超过 `MAX_POSTER_VISUAL_LINES = 24` 就不让再勾（并且把浏览器提前翻上去的 checkbox 退回，避免 UI 与模型脱钩）。
  2. `preview`：展示 `<canvas>` 预览，提供「返回 / 复制图片 / 保存图片」。
- **会话号防串图**：`renderGeneration` 在每次打开、每次点生成时自增；封面取字节和渲染都在 `await` 里，
  结果回来发现号变了就整张丢掉，避免旧会话海报盖掉新会话、或切歌后把新歌名拼到旧歌词上。
  出图内容在点击那一刻 `snapshot`（lines/title/artist），避免异步期间改选择导致成图与提交时不一致。
- 封面按**原图字节**取回再转 `blob:` URL 画进画布（`readImageBytes`），
  原因是图床直链会污染画布（tainted canvas），导出取不到像素。

#### 海报画成什么样（`src/modules/lyrics/posterCanvas.ts` / `posterLayout.ts` / `posterTheme.ts`）

- 画布：**1080 宽**，高度按内容在 **1080（方形下限）~2400** 之间伸缩。`CanvasRenderingContext2D` 自绘，最后 `toBlob('image/png')`。
- 从上到下：
  1. **背景**：封面按 1/8 缩小 → 手写可分离盒式模糊 `r=7 × 3 趟` → 放大铺满；先垫一层不透明黑，避免透明 PNG 封面在浅色背景漏白。
     无封面时走三段对角渐变（种子色 `rgb(156,148,80)`，照搬 Android）。
  2. **对角蒙层**：`argb(86,白)@0 → argb(64,黑)@0.48 → argb(118,黑)@1`。
  3. **对比度补暗**：取歌词区亮度 **90 分位**（而非最亮点，避免一个高光把整张压黑），
     白字对比度不足 7:1 时按 0.02 步长补黑，上限 0.35（`extraScrimFor`）。
  4. **底部通栏毛玻璃**：把已画好的底栏条带再缩 1/8、糊 `r=6 × 2 趟` 叠回去，再叠白 `alpha 70/255`。
     Canvas 没有 `backdrop-filter`，只能这样模拟。
  5. **左上角品牌**：圆角图标（`/app-icon.png`）+ 文字「NeriPlayer」，无图标时画三点占位。
  6. **中部歌词**：字号阶梯 `112/96/88/80/72/66/60/54/48/44`，优先选「整句不折行」的字号；
     每句最多折 3 个视觉行；总预算 24 视觉行；超了从末尾丢行并在预览里提示。
     折行实现了**CJK 单字 / 拉丁整词切分 + 行首禁则标点回推 + 超宽词强制拆字**（`wrapText`/`tokenize`，`posterLayout.ts`）。
  7. **底栏**：圆角封面缩略图（无封面时渐变占位块 + 歌名首字）、歌名（alpha 224）、歌手（alpha 142）。
- 颜色/alpha 常量与 Android 同源（`posterTheme.ts` 注释明确写「数值取自安卓 LyricShareSheet.kt」）。

#### 保存 / 分享

- 保存：`saveImageWithDialog`（`src/utils/imageTransfer.ts`）弹 Tauri 原生保存框 → `invoke('save_file_bytes')` 写字节，默认名 `标题-歌手.lyrics.png`。
- 复制图片：`writePosterToClipboard` 把 PNG 字节交给 Tauri `Image.fromBytes` → `writeImage`。
  注释给了重要经验：**不要发原始 RGBA**（1080×2400 经 IPC 摊成 JSON 数字数组约 3600 万字符、序列化 2 秒），发 PNG 只有约 0.9MB。
- 「分享」在桌面对应**保存图片 / 复制图片 / 复制歌词文本**三种，没有系统分享面板。
  Android 侧才有 `ACTION_SEND` + `FileProvider`（`LyricShareSheet.kt:createAndShareLyricCard`，
  海报先存到 `cacheDir/lyric_share_cards/` 再发 uri）。

**一句话概括海报**：`LyricShareDialog.vue`（选句/预览/保存）→ `posterCanvas.ts::renderPoster`（Canvas 2D 画）→
`posterLayout.ts::planPoster`（字号/折行/丢行）→ `posterTheme.ts`（配色）→ `imageTransfer.ts`（落盘/剪贴板）。

### 1.2 逐字动画强在哪

核心文件：`src/components/desktopLyrics/KaraokeLine.vue`、`src/modules/desktopLyrics/{timeline,style,frame}.ts`、
`src/components/desktopLyrics/DesktopLyricsStage.vue`、`src/shaders/hyperBackground.frag`。

1. **三层文字叠加 + `clip-path` 推进**（`KaraokeLine.vue`）：描边层（`-webkit-text-stroke`）、
   未唱渐变层、已唱渐变层，三层用同样的分段保证字形位置一致；已唱层用 `clip-path: inset(...)` 按宽度裁剪。
   渐变填充 + 外发光 + 描边同时存在，KTV 观感明显强于「单色覆盖」。
2. **每帧写样式、不走响应式**：父组件每帧调 `update(timeMs)`，直接改 `style.clipPath` / `style.transform`；
   注释明确「避免每秒 60 次重渲染」。宽度/位移都保留半像素量化，没变化就不写 DOM。
3. **逐字像素定位**：`measure()` 用 DOM 量每个词的 `offsetLeft/offsetWidth`（`wordOffsets`），
   `timeline.ts::highlightWidth` 按词时间插值到「唱到第几个词的哪个位置」，不是按词数均分。
   有 `ResizeObserver` + `document.fonts.ready` 重测，字体加载/缩放后不错位。
4. **时钟锚点模型**（`timeline.ts::anchoredPositionMs`）：主窗口每约 150ms 发一帧
   `{positionMs, anchorAt(Date.now()), rate, isPlaying, offsetMs, lines...}`（`frame.ts`），
   歌词窗口用墙钟本地插值，`pause`/seek/变速都能正确冻结或校正；还专门处理时钟回拨（`SCROLL_CLOCK_CORRECTION_MS=250`）与
   seek 等待期 `clockRunning:false` 冻结。
5. **长句往返滚动**：`longLineScrollOffset` 用**播放时钟**做 ping-pong（延迟 520ms，38px/s，单程 2.2~9s，smoothstep），
   暂停时文字也停住。
6. **样式系统很完整**（`style.ts`）：7 套主题 + `accent` 跟随封面色 + `custom`；
   布局 single/double/triple（double 是 KTV 双行交替）；第二行可翻译/音译/下一句/无；
   karaoke=word/line/off；描边开关+宽度、阴影、发光、字重/字距/透明度都可配并持久化，输入做白名单/步进/颜色校验。
7. **动态背景**（`src/shaders/hyperBackground.frag`）：GLSL 片元着色器，吃 `u_musicLevel/u_beat` 做颜色脉动、
   缩放、带状波、颗粒抖动，HSV 调色。这是「氛围感」的主要来源，但与逐字歌词本身正交。

---

## 2. 我们项目现状（`desktop/`）

| 能力 | 现状 | 关键文件 |
| --- | --- | --- |
| yrc 逐词解析 | 已有：`YrcParser` 解析 `[行起,行时长](词起,词时长,0)词…`，制作信息行首尾过滤，翻译按 1200ms 就近合并 | `core/Lyrics.kt:94-228`，模型 `core/Models.kt:105-132`（`LyricWord{startMs,durationMs,text}`） |
| 在线歌词 | `online.lyrics` 命令，走 `LyricsRepository`（本地 .lrc → 内嵌 → 在线，LRU 120） | `backend/api/OnlineApi.kt:330`，`core/Lyrics.kt:230-309` |
| 播放页逐字高亮 | 已有：`lineProgress` 按 `Player.smoothPositionMs` + 词宽累计；当前行放大/柔光、上下渐隐、点击 seek、翻译副行 | `qt/qml/components/player/LyricsPane.qml:116-161, 380-427` |
| 可调吸附滚动 | 已有：`positionViewAtIndex` 求目标 contentY + `NumberAnimation`，缓动 LINEAR/SINE/QUAD/CUBIC、时长可配、手动滚动暂停/恢复 | `LyricsPane.qml:171-198, 468-513`（`scrollEasing/scrollDuration`） |
| 悬浮歌词 | 有独立无边框置顶窗（layer-shell overlay 或 NORMAL），可拖动/锁定穿透/控制条；**但只显示当前整行文字 + 翻译，没有逐字高亮，没有动态背景** | `qt/qml/FloatingLyricsWindow.qml:40-70, 434-495` |
| 时钟 | `Player.smoothPositionMs`（4Hz 快照 + 平滑），与行切换共用同一时钟 | `qt/qml/globals/PlayerGlobal.qml:47-88, 214-216` |
| 图片落盘 | `artwork.saveAs {url?,path?,target}`：本地文件优先 → URL 下载缓存 → 复制到用户选的绝对路径，扩展名嗅探 | `backend/api/ArtworkApi.kt:86-116, 200-232`；前端模式 `NowPlayingCover.qml:248-269, 352-363`（`FileDialog.SaveFile`） |
| 分享/海报 | **无** | — |

关键差异：我们有 `artwork.resolve/forSong` 先把封面下到本地缓存返回绝对路径，
QML `Image` 直接读本地文件——**没有参考版的画布跨域污染问题**，这是我们的有利条件。

---

## 3. 海报移植可行性

### 3.1 方案 A（推荐）：QML `Item.grabToImage()`

做法：新建一个全尺寸（1080×按内容）隐藏的 QML 海报 Item（`Image` 封面 + `Rectangle` 蒙层/底栏 +
`Text` 歌词/歌名/歌手/品牌），排版参数照 `posterLayout.ts` 的常量；`grabToImage(cb)` 拿到结果后把图片存到临时文件，
再走 `FileDialog.SaveFile` 拿 target，调用 `artwork.saveAs {path: 临时文件, target: 用户路径}` 落盘。

- 优点：
  - 直接复用 QML 的字体（`Prefs.effectiveLyricFont`）、布局与主题，视觉和 app 一致；改样式=改 QML，用 `dev.sh screenshot` 就能迭代。
  - **后端零新增绘图代码**；`artwork.saveAs` 已支持「本地 path → target」，天然适配生成的 PNG。
  - 无跨域/taint 问题（封面已是本地缓存路径）。
- 缺点 / 待验证：
  - `grabToImage` 对**不在已渲染窗口场景里的 Item**可能返回 null；需要实测确认离屏/Hidden Item 能否抓到，
    必要时把海报 Item 放进一个临时窗口或先显示再抓（离屏渲染环境 `QT_QPA_PLATFORM=offscreen` 下也要测）。
  - 参考版的**毛玻璃**是手写盒式模糊；QML 侧 `QtQuick.Effects.MultiEffect` / `OpacityMask` 能否被 `grabToImage` 正确抓取需实测，
    抓不到就退化成「纯色半透明底栏 + 蒙层」，观感略降但可接受。
  - `grabToImage` 逐像素抓的是 Item 当前渲染，高 DPI 下要显式按 1080×N 设置尺寸，别依赖屏幕缩放。
  - QML 侧的 `QImage.save()` / `toDataURL()` 可用性需按 Qt 6 版本实测确认；若不稳，加一个极小的 C++ 桥接写临时文件即可。
  - 参考版那套「CJK/拉丁切词 + 行首禁则 + 视觉行预算」要在 QML/JS 里重写一份；v1 可以先做简化版（按宽度折行 + 上限提示）。
  - 剪贴板图片：Qt Quick 没有现成 API，需 C++ `QClipboard` 桥接（见「不建议」）。

### 3.2 方案 B（备选）：C++ `QImage/QPainter`

新增一个 C++ 类（如 `PosterRenderer`）暴露到 QML context：`QImage(1080, h, Format_ARGB32)` + `QPainter` 画渐变、
`QFontMetrics` 折行、`drawText`、圆角裁剪、手写盒式模糊，`QImage::save()` 出 PNG，`QClipboard::setImage()` 支持复制。

- 优点：确定性高、高 DPI 清晰、保存/剪贴板都直接、不依赖 QML 场景是否渲染。
- 缺点：新增 C++ + 构建 + 打包改动（AGENTS：改 C++ 才需 `dev.sh build`）；要在 C++ 重画一遍文字排版和模糊；
  与 Android 逻辑重复；改动面显著大于方案 A。

### 3.3 后端需要什么

- **绘图：不需要**。Kotlin/JVM 没有 Qt，走 AWT/Skia 还得复制一套字体，得不偿失。
- **落盘：复用 `artwork.saveAs` 即可**——它接受本地文件 `path`，复制到用户选的 target。
  需要的是「先把生成的 PNG 写到临时文件」：方案 A 由 QML `QImage.save(tempPath)` 完成；
  若不愿意让 QML 写文件，加一个通用 ` image.saveBytes {base64, target}` 命令也可，但要先与集成负责人确认协议文档更新范围。
- **数据：不需要新命令**。歌词已经在 `Player.lyrics.lines`（含 `words`、`translation`），封面路径可用 `artwork.forSong` 拿到，
  歌名/歌手在 `Player`。
- **i18n**：所有用户可见文案走 `I18n.tr()` 并同步 `qt/qml/i18n/translations.js` 的 `en` 表（AGENTS 硬性约定）。

### 3.4 工作量粗估

| 项 | 方案 A（QML） | 方案 B（C++） |
| --- | --- | --- |
| 海报渲染组件（封面/蒙层/歌词/底栏/品牌 + 折行预算） | 1~1.5 天 | 2~3 天 |
| 选句 + 预览 + 保存弹窗（含 FileDialog + saveAs 接线 + i18n） | 0.5~1 天 | 0.5~1 天 |
| 模糊/效果抓图验证与降级 | 0.5 天 | 含在渲染里 |
| 剪贴板图片（可选） | 需 C++ 桥，+0.5~1 天 | 含在渲染里 |
| **合计** | **约 2~3 天** | **约 3~5 天** |

### 3.5 风险

- `grabToImage` 在隐藏 Item / 离屏 / 带 Effect 场景的行为是最大不确定性，先做 1 张 demo 验证再铺开。
- 字体：MiSans 在 Linux 多半没装，QML 会回退到 `Prefs.effectiveLyricFont`；中文字形宽度与参考版不同，折行结果会有差异（可接受，别追求逐像素）。
- 高分辨率画布 1080×2400 ≈ 10MB 像素，内存没问题，但别在列表里频繁生成。
- 弹窗挂载点：选句/预览面板若放 `components/panels/` 则由 `PlayerOverlays.qml` 挂载；该文件是共享文件，
  按 AGENTS 需先与集成负责人确认，或先把入口放在 `LyricsPane` 自己的右键菜单里减少交叉改动。
- 参考版用会话号防串图；我们同样要在切歌/关弹窗时作废进行中的生成（QML 里用 generation 计数 + 回调里校验）。

---

## 4. 逐字歌词动画增强路径

我们已有：`Lyrics.kt::YrcParser` + `Models.LyricWord`、`LyricsPane.qml::lineProgress`（按词宽累计）、
可调吸附滚动。**还差**：

1. **悬浮窗口补逐字高亮（最高优先）**：`FloatingLyricsWindow.qml` 目前 `mainText` 只是整行文字。
   在 `mainItem` 里照 `LyricsPane` 加一层「按 `lineProgress` 裁切的强调色副本」即可，数据源用
   `currentLine.words` + `Player.smoothPositionMs`。注意悬浮窗的固定高度/两行上限，逐字层要与主文字完全同布局。
2. **逐字定位从「近似」升级**：`LyricsPane` 用 `FontMetrics.advanceWidth` 逐词求和，没考虑 shaping/kerning/标点间距，
   长句折行后进度也不连续。可选改进：为每个词建隐藏 `Text` 取 `implicitWidth`（`TextMetrics` 同理），
   最接近参考版 DOM 量的 `offsetWidth`；但收益有限，优先级低于第 1 项。
3. **长句往返滚动**：`timeline.ts::longLineScrollOffset` 那套 ping-pong 很好移植（纯数学 + smoothstep），
   悬浮窗和播放页都能用；播放页当前是 `Text.Wrap` 换行，悬浮窗是 `elide`，建议悬浮窗加横向滚动。
4. **样式扩展**：描边开关/宽度、阴影、发光、字重/字距、7 套预设主题 + accent 跟随、KTV 双行交替、音译/下一句副行。
   我们目前只有行/高亮开关、字号缩放、对齐、柔光、翻译；按需求分阶段做。
5. **时钟**：我们是单进程双窗口，共用 `PlayerGlobal`，比参考版跨窗口 IPC 简单，不必照搬锚点模型；
   但要确认 `smoothPositionMs` 在 seek/暂停时的冻结行为与逐字进度一致（`PlayerGlobal.qml:76-88, 214-216`）。
6. **动态律动背景（GLSL）**：参考版 `hyperBackground.frag` 需要音频分析数据（`u_musicLevel/u_beat`），
   我们后端目前没有稳定的节拍/电平数据源，且 layer-shell 窗口上叠 ShaderEffect 性能/兼容风险高——**不建议本期做**。

---

## 5. 分步建议与「不建议」

### 建议顺序

- **Step 1（低风险高收益）**：悬浮歌词窗口补逐字高亮 + 长句往返滚动。只动 `FloatingLyricsWindow.qml`（必要时抽一个公共的逐字计算 JS 模块到 `qt/qml/components/player/`），
  不动后端，截图即可验证。
- **Step 2**：海报 v1（方案 A）。先做「一张 1080 海报能否被 `grabToImage` 正确抓到并保存」的 spike，
  通了再做选句弹窗 + 预览 + `FileDialog` + `artwork.saveAs` + i18n；效果先做「模糊封面底 + 蒙层 + 歌词 + 底栏」四件套，
  模糊抓不到就降级为纯半透明底栏。
- **Step 3**：按需扩展样式（主题/描边/发光、KTV 双行、音译副行）、逐字精确定位、复制图片（加小 C++ 桥）。
- 每步都遵守 AGENTS：只改自己任务文件、共享接线文件先找集成负责人、所有文案 `I18n.tr` + 翻译表、改完 `dev.sh screenshot` 自测。

### 不建议做

- ❌ **html2canvas / DOM→图片**那条路：参考版自己都明确避开了，Qt/QML 里也没有 DOM。
- ❌ **Kotlin 后端画图**：JVM 侧没有 Qt，AWT/BufferedImage 重画一套字体和模糊，成本高、观感还不一致。
- ❌ **原生社交分享 / 剪贴板图片（v1）**：桌面没有 `ACTION_SEND` 等价物；Wayland 下剪贴板图片要 C++ `QClipboard` 桥，
  平台差异大。先只做「保存 PNG + 复制歌词文本」，把「分享」定义为这两个动作。
- ❌ **像素级复刻参考版/安卓海报**：MiSans 字体、盒式模糊实现的细微差别无法也不必对齐，目标是「像、可用、一致」。
- ❌ **移植 GLSL 律动背景**：缺音频电平数据源，layer-shell + ShaderEffect 风险高，与本期主题关系弱。
- ❌ **在前端重新解析 yrc**：后端 `LyricWord` 已经准备好了，别重复解析。
- ❌ **改共享接线文件（`PlayerOverlays.qml` / `AppShell.qml` / `NavGlobal.qml` / `BackendMain.kt`）前不通知集成负责人**：按 AGENTS 必须确认授权。

---

## 6. 关键文件索引

参考版（`/home/yang/Git/NeriPlayer-Desktop`）：
- `src/components/LyricShareDialog.vue` — 选句/预览/保存弹窗
- `src/components/LyricsView.vue:868` — 分享入口
- `src/modules/lyrics/posterCanvas.ts` — Canvas 2D 绘制、模糊、导出、剪贴板
- `src/modules/lyrics/posterLayout.ts` — 字号阶梯/折行/排版预算
- `src/modules/lyrics/posterTheme.ts` — 配色与对比度补暗
- `src/utils/imageTransfer.ts` — 保存对话框 / 字节落盘
- `src/components/desktopLyrics/KaraokeLine.vue` — 三层逐字渲染
- `src/modules/desktopLyrics/{timeline,style,frame}.ts` — 时钟/样式/帧
- `src/components/desktopLyrics/DesktopLyricsStage.vue` — 多行布局
- `src/shaders/hyperBackground.frag` — 律动背景

本项目（`/home/yang/Git/neriplayer/desktop`）：
- `qt/qml/components/player/LyricsPane.qml` — 播放页歌词 + 逐字进度 + 吸附滚动
- `qt/qml/FloatingLyricsWindow.qml` — 悬浮歌词（待补逐字高亮）
- `qt/qml/globals/PlayerGlobal.qml` — `smoothPositionMs` 时钟
- `qt/qml/components/player/NowPlayingCover.qml:248-269,352-363` — `artwork.saveAs` + `FileDialog` 现成范式
- `src/main/kotlin/.../core/Lyrics.kt` — LRC/YRC 解析、翻译合并、仓库
- `src/main/kotlin/.../core/Models.kt:105-132` — `LyricWord/LyricLine/Lyrics`
- `src/main/kotlin/.../backend/api/OnlineApi.kt:330` — `online.lyrics`
- `src/main/kotlin/.../backend/api/ArtworkApi.kt:86-116,200-232` — `artwork.saveAs`
- `docs/protocol-artwork.md` — 封面相关协议（新增保存海报命令时需同步）
