import QtQuick
import QtQuick.Controls
import QtQuick.Window
import "components"

// 悬浮歌词窗口：无边框、透明、置顶、不抢焦点，可拖动；锁定时整窗鼠标穿透。
// 样式/位置都来自设置（Settings.values），不依赖主窗口状态。
//
// 两种窗口形态（设置 floatingLyricsWindowMode）：
//  - LAYER：wlr-layer-shell overlay 层，由合成器按锚点摆放，永远压在所有窗口之上，
//           但无法拖到别的屏幕（位置只能改 margin 模拟）。
//  - NORMAL：普通无边框窗口 + WindowStaysOnTopHint，可跨屏拖动，层级不如 overlay 稳。
// layer-shell 的 shell 角色一旦建立便不能在同一 QWindow 上重复设置；形态切换由
// Main.qml 销毁旧窗口后重建，本窗口实例从创建到销毁始终只属于一种形态。
Window {
    id: win

    /** 主窗口（用于「应用内隐藏」判断）。 */
    property Window mainWindow: null
    /** 拖动时的位置预览层（由 Main 创建并注入）。 */
    property Window previewWindow: null

    readonly property bool locked: Settings.get("floatingLyricsLocked", false) === true
    readonly property bool hideInApp: Settings.get("floatingLyricsHideInApp", false) === true
    readonly property bool enabledSetting: Settings.get("floatingLyricsEnabled", false) === true
    readonly property bool appActive: mainWindow ? mainWindow.active : false

    /**
     * 打开主窗口并跳到指定页面。
     *
     * 只调 Nav.push 的话，主窗口收在托盘时页面切换发生在后台，用户看不到内容，
     * 要等窗口自己弹出来之后才「突然」停在播放页。所以先显式显示主窗口。
     */
    function openInMainWindow(routeName) {
        if (win.mainWindow)
            win.mainWindow.showMainWindow()
        Nav.push(routeName)
    }

    readonly property var lyricLines: Player.lyrics && Player.lyrics.lines ? Player.lyrics.lines : []
    readonly property int currentIndex: Player.currentLyricIndex()
    readonly property var currentLine: currentIndex >= 0 && currentIndex < lyricLines.length ? lyricLines[currentIndex] : null

    /**
     * 悬浮歌词显示行顺序：原文 / 翻译 / 音译 任意排列，下划线分隔。
     * 与播放页 LyricsPane 的 lyricLineOrder 同源，但悬浮歌词有自己的一份设置。
     */
    readonly property var displayLines: {
        const valid = ["ORIGINAL", "TRANSLATION", "PHONETIC"]
        const raw = String(Settings.get("floatingLyricsLineOrder", "ORIGINAL_TRANSLATION_PHONETIC") || "").split("_")
        const order = []
        for (let i = 0; i < raw.length; i++) {
            if (valid.indexOf(raw[i]) >= 0 && order.indexOf(raw[i]) < 0)
                order.push(raw[i])
        }
        for (let i = 0; i < valid.length; i++) {
            if (order.indexOf(valid[i]) < 0)
                order.push(valid[i])
        }
        return order
    }

    /** 某一类行是否启用：翻译/音译各有独立开关，原文始终显示。 */
    function kindEnabled(kind) {
        if (kind === "TRANSLATION")
            return Settings.get("floatingLyricsShowTranslation", true) !== false
        if (kind === "PHONETIC")
            return Settings.get("floatingLyricsShowPhonetic", false) === true
        return true
    }

    /** 当前行指定类型的文本；没有歌词时用歌名/歌手作为原文占位。 */
    function textFor(kind) {
        if (kind === "ORIGINAL")
            return currentLine ? currentLine.text : (Player.hasSong ? (Player.title + " - " + Player.artist) : "NeriPlayer")
        if (!currentLine)
            return ""
        if (kind === "TRANSLATION") {
            const translated = Player.lyrics && Player.lyrics.translated ? Player.lyrics.translated : null
            if (translated && currentIndex < translated.length && translated[currentIndex].text)
                return translated[currentIndex].text
            return currentLine.translation ? String(currentLine.translation) : ""
        }
        if (kind === "PHONETIC")
            return currentLine.phonetic ? String(currentLine.phonetic) : ""
        return ""
    }

    /** 实际要渲染的行（已过滤关闭的类型与空文本），每项 {key, text}。 */
    readonly property var visibleLines: {
        const rows = []
        for (let i = 0; i < win.displayLines.length; i++) {
            const kind = win.displayLines[i]
            if (!win.kindEnabled(kind))
                continue
            const text = win.textFor(kind)
            if (text)
                rows.push({ key: kind, text: text })
        }
        return rows
    }

    readonly property string mainText: textFor("ORIGINAL")
    readonly property string translationText: textFor("TRANSLATION")

    // ---------------------------------------------------------------- 逐字高亮
    //
    // 当前行有 yrc 逐词时间时，把主歌词拆成「未唱层 + 已唱层」两层同布局的文字，
    // 已唱层用一个按进度推进宽度的裁切矩形盖在上面。没有逐词数据（普通 LRC）时
    // 只画未唱层，观感与引入逐字之前完全一致。
    readonly property var currentWords: (currentLine && currentLine.words) ? currentLine.words : []
    readonly property bool karaokeActive: currentWords.length > 0

    /**
     * 逐字模式下「未唱」的透明度。
     *
     * 已唱层直接沿用用户设置的颜色，未唱层把它压暗——这样用户换文字颜色时两层一起跟着变，
     * 不需要额外加一个「高亮色」设置项。0.42 与播放页歌词未唱行一致。
     */
    readonly property real karaokeUnplayedAlpha: 0.42

    /**
     * 当前行每个词的排版宽度（与主歌词同字体度量）。
     *
     * 单独抽成一个只随「歌词行 / 字号 / 字体」变化的绑定：逐字进度每帧都要用这些宽度，
     * 若把 advanceWidth 塞进 wordProgress，30Hz 刷新下每帧都要重新测一轮字宽，
     * 在 layer-shell 覆盖层上是纯浪费。没有逐词数据时返回 null。
     */
    readonly property var wordWidths: {
        // 显式读一次字号/字体：这两个值变化时（用户改字号、换歌词字体）本绑定要重算，
        // 否则缓存的词宽会和实际排版对不上。
        const fontSize = win.baseFontSize
        const family = Prefs.effectiveLyricFont
        const words = win.currentWords
        if (!words || words.length === 0 || fontSize <= 0)
            return null
        const widths = []
        let total = 0
        for (let i = 0; i < words.length; i++) {
            const width = lyricMetrics.advanceWidth(String(words[i].text || ""))
            widths.push(width)
            total += width
        }
        return total > 0 ? { widths: widths, total: total } : null
    }

    /**
     * 当前行已唱比例（0..1），算法与播放页 LyricsPane 的 lineProgress 一致：
     * 有逐词时间就按「词时间插值 + 词宽累计」，没有就返回 0（上层回退整行显示）。
     *
     * 时钟必须用 Player.smoothPositionMs：行切换（Player.currentLyricIndex）也用同一个
     * 时钟，否则 4Hz 快照的 positionMs 滞后会让逐字进度和换行错半拍。
     */
    readonly property real wordProgress: {
        const data = win.wordWidths
        if (!data)
            return 0
        const words = win.currentWords
        const clock = Player.smoothPositionMs
        let passedWidth = 0
        for (let i = 0; i < words.length; i++) {
            const start = Number(words[i].startMs)
            const duration = Math.max(0, Number(words[i].durationMs))
            if (clock < start)
                return Math.max(0, Math.min(1, passedWidth / data.total))
            if (clock < start + duration) {
                // 词内按时间线性插值，唱到第几个词的哪个位置就亮多少，
                // 不按词数均分（中文词可能一两个字、还可能带空格/标点）
                const inner = duration > 0 ? (clock - start) / duration : 1
                return Math.max(0, Math.min(1, (passedWidth + data.widths[i] * inner) / data.total))
            }
            passedWidth += data.widths[i]
        }
        // 最后一个词唱完后整行亮满，保持到下一行开始（不会亮完就跳字）
        return 1
    }

    readonly property real baseFontSize: (Settings.get("floatingLyricsFontSize", 30) || 30)
    readonly property string renderStyle: Settings.get("floatingLyricsRenderStyle", "SHADOW")
    readonly property string textColor: colorOf(Settings.get("floatingLyricsTextColor", "WHITE"))
    readonly property string shadowColor: colorOf(Settings.get("floatingLyricsShadowColor", "BLACK"))
    readonly property string outlineColor: colorOf(Settings.get("floatingLyricsOutlineColor", "BLACK"))
    readonly property real outlineWidth: Settings.get("floatingLyricsOutlineWidth", 2.0) || 2.0
    readonly property real shadowBlur: Settings.get("floatingLyricsShadowBlur", 6.0) || 6.0
    readonly property real lyricAlpha: Settings.get("floatingLyricsLyricAlpha", 1.0) || 1.0
    readonly property real backgroundAlpha: Settings.get("floatingLyricsBackgroundAlpha", 0.35) || 0.35
    readonly property string backgroundColor: colorOf(Settings.get("floatingLyricsBackgroundColor", "BLACK"))
    readonly property int alignment: {
        switch (Settings.get("floatingLyricsAlignment", "CENTER")) {
        case "LEFT": return Text.AlignLeft
        case "RIGHT": return Text.AlignRight
        default: return Text.AlignHCenter
        }
    }

    // 行字号：原文用基础字号，翻译/音译按各自比例缩小（与播放页、设置预览一致）。
    readonly property real phoneticScale: {
        const v = Settings.get("lyricPhoneticFontSizeScale", 0.58)
        return (typeof v === "number" && v > 0) ? v : 0.58
    }
    readonly property real translationScale: {
        const v = Settings.get("lyricTranslationFontSizeScale", 0.58)
        return (typeof v === "number" && v > 0) ? v : 0.58
    }
    function fontScaleFor(kind) {
        return kind === "PHONETIC" ? win.phoneticScale
             : (kind === "TRANSLATION" ? win.translationScale : 1.0)
    }
    function alphaFor(kind) {
        if (kind === "TRANSLATION")
            return Settings.get("floatingLyricsTranslationAlpha", 0.75)
        if (kind === "PHONETIC")
            return Settings.get("floatingLyricsPhoneticAlpha", 0.75)
        return win.lyricAlpha
    }

    // 窗口尺寸固定（与旧 Compose 版一致）：宽度取设置的最大宽度，高度由字号推导。
    // 不要跟随歌词内容自适应——每换一行就改窗口尺寸会让合成器在拖动时出现残影，
    // 视觉上也会「忽大忽小」。超长歌词在固定宽度内换行/省略即可。
    readonly property real maxWidthDp: {
        const value = Number(Settings.get("floatingLyricsMaxWidthDp", 900))
        return isNaN(value) || value <= 0 ? 900 : value
    }
    readonly property real windowWidth: {
        const screenWidth = screen ? screen.width : maxWidthDp
        return Math.max(160, Math.min(maxWidthDp, screenWidth))
    }
    readonly property real windowHeight: {
        // 按启用的行类型预留高度：音译行过去之所以「开了也没显示」，一是渲染没有接
        // phonetic，二是窗口高度只算了原文+翻译，多出来的行会被裁掉。
        const lineHeight = baseFontSize * 1.6
        let rows = lineHeight
        if (kindEnabled("TRANSLATION"))
            rows += baseFontSize * translationScale * 1.5
        if (kindEnabled("PHONETIC"))
            rows += baseFontSize * phoneticScale * 1.5
        const controlBarInset = locked ? 0 : 30
        return Math.max(64, rows + 28 + controlBarInset)
    }

    // 形态在窗口创建时从已到达的设置中一次读定；layer-shell 角色在本实例的
    // 生命周期内不会更改（运行期切换由 Main 销毁并重建窗口完成）。
    readonly property string windowMode: (Settings.values["floatingLyricsWindowMode"] === "NORMAL") ? "NORMAL" : "LAYER"
    readonly property bool useLayerShell: windowMode === "LAYER" && LayerShell.available()

    // 显式尺寸：在 attach/show 之前算好，避免依赖窗口布局尚未就绪的 width/height。
    // layer-shell 的 margin 位移也按 QScreen.availableGeometry 计算，与此保持一致。
    readonly property int attachWidth: Math.round(windowWidth)
    readonly property int attachHeight: Math.round(windowHeight)

    readonly property int availW: Math.max(1, Screen.availableWidth > 0 ? Screen.availableWidth : (win.screen ? win.screen.width : 1920))
    readonly property int availH: Math.max(1, Screen.availableHeight > 0 ? Screen.availableHeight : (win.screen ? win.screen.height : 1080))
    readonly property int availX: win.screen ? win.screen.virtualX : 0
    readonly property int availY: win.screen ? win.screen.virtualY : 0

    /** attach 后才能显示：WAYLAND 下窗口 show 会创建不可更改的 xdg-toplevel shell 角色。 */
    property bool attached: false
    // layer-shell 的 shell 集成在窗口每次 show 时建立。若 visible 反复 true↔false，
    // 重新 show 会再次 get() 到新的 layer 对象并报 "already has a shell integration"。
    // 因此本实例生命周期内只 show 一次；开关关闭时销毁本窗口，重新开启时由 Main
    // 创建一个全新的窗口，而不是在本窗口上 flip visible。
    property bool shouldShow: enabledSetting && Player.hasSong && !(hideInApp && appActive)
    visible: false
    // 本实例创建时开关是否已打开。只有「从打开变成关闭」才销毁本窗口；
    // 若窗口是在开关关闭期间预建的，就原地待命，等用户打开开关再由 showOnce 显示。
    property bool _enabledAtCreate: Settings.get("floatingLyricsEnabled", false) === true
    // 关闭开关后的销毁由 requestClose() 统一发起，避免在设置回执的同一个调用栈里
    // 反复触发（Settings.values 会被多次赋新对象），也防止重复重建。
    property bool _closing: false

    function showOnce() {
        if (win._closing || win.visible || !win.attached || !win.shouldShow)
            return
        win.visible = true
        showFallback.stop()
    }

    /** 关掉开关：销毁本实例并让 Main 建一个待命的新窗口（绝不 flip visible）。 */
    function requestClose() {
        if (win._closing)
            return
        win._closing = true
        showFallback.stop()
        // 与 Main.qml::rebuildFloatingLyricsWindow() 同样的顺序：先交出所有权，
        // close + destroy，再用 callLater 跨过延迟删除去创建新窗口。新窗口此时开关
        // 仍为关闭，会保持隐藏待命；它的 Settings 连接负责在重新打开时显示。
        // 若不在此刻交接，Main 在开关打开时看不到新窗口，开关就彻底失效。
        const owner = win.mainWindow
        if (owner && owner.floatingLyrics === win)
            owner.floatingLyrics = null
        if (typeof LayerShell !== "undefined" && LayerShell)
            LayerShell.detach(win)
        win.close()
        win.destroy()
        if (owner && typeof owner.ensureAuxWindows === "function")
            Qt.callLater(function() { owner.ensureAuxWindows() })
    }

    // 后端 player 快照可能晚于窗口创建到达。定时兜底只在「尚未显示」
    // 时运行；showOnce() 成功后立即停止，不会 periodic flip visible。
    // 注意：这里不会因开关关闭而自毁——本实例可能是在开关关闭期间由 Main 预建的，
    // 需要留在原地等待启用；只有 onValuesChanged 里的显式 true→false 才销毁。
    property Timer showFallback: Timer {
        interval: 120
        repeat: true
        running: !win.visible && !win._closing
        onTriggered: win.showOnce()
    }

    flags: Qt.Window | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint
           | Qt.WindowDoesNotAcceptFocus
           | (locked ? Qt.WindowTransparentForInput : 0)
           | Qt.NoDropShadowWindowHint
           | Qt.MSWindowsFixedSizeDialogHint   // 禁止最大化/缩放，避免悬浮歌词被拉成全屏
    color: "transparent"
    title: "NeriPlayer 悬浮歌词"
    // 尺寸由歌词行数与设置决定，不允许用户（或窗口管理器）改变，否则会覆盖计算好的布局。
    width: attachWidth
    height: attachHeight
    minimumWidth: attachWidth
    maximumWidth: attachWidth
    minimumHeight: attachHeight
    maximumHeight: attachHeight

    // layer-shell 窗口不存在「提到最前」的概念（层级由 layer 决定），
    // 仅在回退路径下需要周期性 raise。
    onVisibleChanged: {
        if (visible && !win.useLayerShell)
            keepOnTopTimer.restart()
    }

    /** 拖动中把目标位置画到全屏预览层上（窗口本身不动，避免合成器重排）。 */
    function updatePreview() {
        if (!win.previewWindow || !win._dragState || !win.screen)
            return
        // 预览层是为 layer-shell 的「改 margin 模拟移动」准备的；普通窗口直接移动窗口本身，
        // 再叠一层全屏预览只会遮住正在被合成器拖动的窗口。
        if (!win.useLayerShell)
            return
        const drag = win._dragState
        const roomW = Math.max(0, rootAvailW() - win.width)
        const roomH = Math.max(0, rootAvailH() - win.height)
        const px = Math.max(0, Math.min(roomW, drag.pressMarginX + drag.dx))
        const py = Math.max(0, Math.min(roomH, drag.pressMarginY + drag.dy))
        // 预览层铺满当前屏幕，内部绘制用「屏幕内相对坐标」
        win.previewWindow.boxX = px
        win.previewWindow.boxY = py
        win.previewWindow.boxW = win.width
        win.previewWindow.boxH = win.height
        win.previewWindow.active = true
    }

    /** 结束预览。 */
    function hidePreview() {
        if (win.previewWindow)
            win.previewWindow.active = false
    }

    property var _dragState: null

    property Timer keepOnTopTimer: Timer {
        interval: 60
        repeat: false
        onTriggered: WindowDrag.keepOnTop(win)
    }

    function rootAvailW()  { return availW }
    function rootAvailH()  { return availH }

    function availWidth()  { return Math.max(1, availW - win.width) }
    function availHeight() { return Math.max(1, availH - win.height) }

    function targetPixelX() {
        return Math.max(0, Math.round(availWidth() * win.normalizedX))
    }
    function targetPixelY() {
        return Math.max(0, Math.round(availHeight() * win.normalizedY))
    }

    // layer-shell 下位置是 margin；Wayland 普通窗口的位置由合成器决定（客户端既写不了
    // 也读不到），只有 X11/offscreen 这类普通平台才能自己摆。
    readonly property bool selfPositionsWindow: !useLayerShell && !WindowDrag.usesSystemMove()

    readonly property real normalizedX: {
        const value = Settings.get("floatingLyricsPositionX", 0.5)
        return typeof value === "number" ? Math.max(0, Math.min(1, value)) : 0.5
    }
    readonly property real normalizedY: {
        const value = Settings.get("floatingLyricsPositionY", 0.85)
        return typeof value === "number" ? Math.max(0, Math.min(1, value)) : 0.85
    }

    // 位置绑定：拖动期间关闭，避免「写设置 → 归一化坐标变化 → x/y 重新绑定」把窗口拽回去。
    property bool dragging: false

    // Wayland 下 startSystemMove() 由合成器接管指针，MouseArea 收不到 released，
    // 拖完 dragging 会一直为 true（Binding 永久失效）、位置也存不下来。
    // 这里做兜底：位移事件停止后视为拖动结束，提交位置并解除 dragging。
    property Timer dragEndGuard: Timer {
        interval: 250
        repeat: false
        onTriggered: {
            if (!win.dragging)
                return
            dragArea.commitDrag()
        }
    }

    /** 是否交给合成器拖动（NORMAL 形态 + Wayland）。layer-shell 只能靠改 margin 模拟。 */
    readonly property bool usesSystemMoveDrag: !win.useLayerShell && WindowDrag.usesSystemMove()
    /** 是否由我们自己写 x/y 拖动（NORMAL 形态 + X11/offscreen）。 */
    readonly property bool manualMoveDrag: !win.useLayerShell && !WindowDrag.usesSystemMove()

    // 合成器拖动期间 Qt 收不到鼠标移动/释放事件（指针被合成器抓走），
    // 这里按帧轮询全局指针位置：既能判断拖动是否还在进行，也能算出总位移用于保存位置。
    property Timer systemMovePoll: Timer {
        interval: 80
        repeat: true
        running: win.dragging && win.usesSystemMoveDrag
        onTriggered: {
            const drag = win._dragState
            if (!drag)
                return
            const cur = WindowDrag.globalCursorPos()
            if (cur.x === drag.lastCursorX && cur.y === drag.lastCursorY)
                return
            drag.lastCursorX = cur.x
            drag.lastCursorY = cur.y
            drag.dx = cur.x - drag.pressCursorX
            drag.dy = cur.y - drag.pressCursorY
            // 指针还在动说明拖动没结束，把「停顿即结束」的兜底计时器往后推
            dragEndGuard.restart()
        }
    }

    // 位置绑定只在「窗口位置能由客户端自己决定」时使用：
    // layer-shell 窗口的位置由 margin 决定，写 x/y 会被合成器忽略（甚至冲突）；
    // Wayland 普通窗口同理（合成器说了算），只有 X11/offscreen 才有意义。
    //
    // 拖动结束后位置写设置是异步的（等后端回执 → normalizedX/Y 才变），期间绑定
    // 会把窗口拽回旧位置，视觉上闪一下。这里在回执到达前短暂冻结 X11 的位置绑定。
    property bool _freezePosition: false
    property Timer freezePositionTimer: Timer {
        interval: 400
        repeat: false
        onTriggered: win._freezePosition = false
    }

    Binding {
        target: win
        property: "x"
        when: !win.dragging && !win._freezePosition && win.selfPositionsWindow
        value: Math.round(win.screen ? availX + (availW - win.width) * win.normalizedX : 0)
    }
    Binding {
        target: win
        property: "y"
        when: !win.dragging && !win._freezePosition && win.selfPositionsWindow
        value: Math.round(win.screen ? availY + (availH - win.height) * win.normalizedY : 0)
    }

    // 逐字进度要按词宽累计，必须用与主歌词完全相同的字体度量（族/字号/字重）。
    // 悬浮窗字号只有一档，建一个 FontMetrics 就够，不必每帧重算。
    FontMetrics {
        id: lyricMetrics

        font: Qt.font({
            family: Prefs.effectiveLyricFont,
            pixelSize: win.baseFontSize,
            weight: Font.Bold
        })
    }

    function colorOf(name) {
        switch (String(name || "").toUpperCase()) {
        case "BLACK": return "#000000"
        case "WHITE": return "#FFFFFF"
        case "RED": return "#F44336"
        case "ORANGE": return "#FF9800"
        case "YELLOW": return "#FFEB3B"
        case "GREEN": return "#4CAF50"
        case "CYAN": return "#00BCD4"
        case "BLUE": return "#2196F3"
        case "PURPLE": return "#9C27B0"
        case "PINK": return "#E91E63"
        default: return "#FFFFFF"
        }
    }

    function savePosition() {
        if (!screen || width <= 0 || height <= 0)
            return
        const nx = Math.max(0, Math.min(1, (x - screen.virtualX) / Math.max(1, screen.width - width)))
        const ny = Math.max(0, Math.min(1, (y - screen.virtualY) / Math.max(1, screen.height - height)))
        Settings.patch({ "floatingLyricsPositionX": Number(nx.toFixed(3)), "floatingLyricsPositionY": Number(ny.toFixed(3)) })
    }

    // 拖动结束后的位置持久化。
    //
    // 之前用 onXChanged/onYChanged 触发保存，但 Wayland 下 win.x/win.y 由合成器决定、
    // 经常读到 0 或滞后值，会把 (0,0) 误存进设置，于是下次启动窗口跑到左上角。
    // 现在只在拖动真正结束时，用「拖动前的归一化位置 + 本次位移」算出目标比例再保存。
    function savePositionAt(nx, ny) {
        if (!isFinite(nx) || !isFinite(ny))
            return
        const cx = Math.max(0, Math.min(1, nx))
        const cy = Math.max(0, Math.min(1, ny))
        Settings.patch({
            "floatingLyricsPositionX": Number(cx.toFixed(3)),
            "floatingLyricsPositionY": Number(cy.toFixed(3))
        })
        // 位置的实际下发统一由 Settings.onValuesChanged 完成（设置回执会再次进入该
        // 处理函数）。这里绝不能再调用 LayerShell API：重复取 shell 集成会在同窗口上
        // 报 "already has a shell integration"，反而让 margin 永远停在旧值。
    }

    /** 把当前设置里的比例下发到 layer-shell 的 margin（窗口已附着）。 */
    function applyPlacement() {
        if (win.useLayerShell && win.attached) {
            LayerShell.setPositionRatio(win, win.normalizedX, win.normalizedY)
            // 开关音译/翻译或改字号后行数、窗口高度会变，同步 shell 尺寸，避免多出的行被裁掉。
            LayerShell.setDesiredSize(win, win.attachWidth, win.attachHeight)
        }
    }

    // 悬浮窗默认不抢焦点（由 WindowDoesNotAcceptFocus 保证，无需再重设 flags）
    // 同时脱离父窗口：主窗口最小化到托盘时，悬浮歌词应当继续显示。
    Component.onCompleted: {
        WindowDrag.detachFromParent(win)
        if (win.useLayerShell) {
            // 本实例唯一一次 attach，且严格在窗口 show 之前：显式传入已算好的尺寸，
            // 不依赖窗口布局的 width/height。attached 控制 showOnce()，保证不会先创建
            // 普通 xdg-toplevel shell 角色。
            win.attached = LayerShell.attach(win, win.normalizedX, win.normalizedY,
                                             win.attachWidth, win.attachHeight)
            console.log("[lyrics] attach mode=", win.windowMode, "ok=", win.attached,
                        "size=", win.attachWidth, win.attachHeight)
        } else {
            // 普通窗口无需 layer-shell，直接放行显示
            win.attached = true
            console.log("[lyrics] window mode=", win.windowMode,
                        "layerShell available=", LayerShell.available())
        }
        // 开关关闭期间创建的实例保持隐藏待命；显示条件满足（含用户打开开关）时
        // 由 showOnce 放行。绝不在这里因开关关闭而自毁，否则 Main 只在 toggle 时
        // 重建，窗口会一直缺席直到用户手动切两次开关。
        win.showOnce()
    }

    // 设置里的位置被外部改动（拖动保存后的回执、设置页滑杆、其它实例）时，
    // 在这个统一入口把新比例下发到 margin。layer-shell 的 x/y 绑定已停用，
    // 不会自动跟随设置变化。
    property Connections settingsConnection: Connections {
        target: Settings
        function onValuesChanged() {
            const enabled = Settings.get("floatingLyricsEnabled", false) === true
            // 只有显式的「打开 → 关闭」才销毁本实例；再次打开时 Main 的
            // ensureAuxWindows() 会创建新窗口。绝不能只改 visible —— 那会让 layer-shell
            // 在同一 QWindow 上重复建立 shell 角色。
            if (win._enabledAtCreate && !enabled) {
                win._enabledAtCreate = false
                win.requestClose()
                return
            }
            win._enabledAtCreate = enabled
            if (!enabled || win.dragging)
                return
            win.applyPlacement()
            win.showOnce()
        }
    }

    // 后端 player 快照可能晚于窗口创建：song 变化时补足首次显示条件。
    property Connections playerConnection: Connections {
        target: Player
        function onSongChanged() { win.showOnce() }
    }
    property Connections mainWindowConnection: Connections {
        target: win.mainWindow
        function onActiveChanged() { win.showOnce() }
    }

    // 窗口级 hover 检测：放在 Window 根上，覆盖整个窗口区域。
    HoverHandler {
        id: windowHover
        enabled: !win.locked
    }

    Rectangle {
        id: background

        anchors.fill: parent
        radius: 14
        color: Theme.withAlpha(win.backgroundColor, win.backgroundAlpha)

        Column {
            id: content

            anchors.centerIn: parent
            width: Math.max(0, win.width - 28)
            spacing: 2

            // 按浮点歌词显示顺序渲染每一行（原文 / 音译 / 翻译）。
            // 原文行保留逐字高亮（未唱层 + 已唱层裁切），其余行按各自比例/透明度画普通文本。
            Repeater {
                model: win.visibleLines

                delegate: Item {
                    required property var modelData
                    readonly property string kind: modelData.key
                    readonly property string rowText: modelData.text
                    readonly property bool isOriginal: kind === "ORIGINAL"
                    readonly property int rowPixelSize: Math.max(10, Math.round(win.baseFontSize * win.fontScaleFor(kind)))
                    readonly property real rowAlpha: win.alphaFor(kind)

                    width: parent.width
                    // 每行最多两行文本；原文行留出逐字分层所需的空间。
                    height: Math.min(rowLine.implicitHeight, rowPixelSize * 1.6 * 2)

                    // 已唱层裁切窗口的横向基准（仅原文逐字模式用）。
                    readonly property real textWidth: Math.min(width, rowLine.contentWidth)
                    readonly property real highlightX: win.alignment === Text.AlignLeft
                                                       ? 0
                                                       : (win.alignment === Text.AlignRight
                                                          ? Math.max(0, width - textWidth)
                                                          : Math.max(0, (width - textWidth) / 2))

                    // SHADOW 样式：单个偏移副本。不要用多个 Repeater 副本叠阴影——
                    // 透明置顶窗口里每换一行都要重建/重绘十几层文本，拖动时会留下残影。
                    Text {
                        visible: win.renderStyle === "SHADOW"
                        x: (parent.width - width) / 2 + win.shadowBlur * 0.4
                        y: win.shadowBlur * 0.4
                        width: parent.width
                        text: rowText
                        horizontalAlignment: win.alignment
                        wrapMode: Text.Wrap
                        maximumLineCount: 2
                        elide: Text.ElideRight
                        color: win.shadowColor
                        opacity: rowAlpha * 0.6
                        font.family: Prefs.effectiveLyricFont
                        font.pixelSize: rowPixelSize
                        font.weight: Font.Bold
                    }

                    Text {
                        id: rowLine
                        width: parent.width
                        text: rowText
                        horizontalAlignment: win.alignment
                        wrapMode: Text.Wrap
                        maximumLineCount: 2
                        elide: Text.ElideRight
                        color: win.textColor
                        // 逐字模式下原文底层压暗，才能看出「唱到哪」；其它行保持自身透明度
                        opacity: rowAlpha * (isOriginal && win.karaokeActive ? win.karaokeUnplayedAlpha : 1)
                        font.family: Prefs.effectiveLyricFont
                        font.pixelSize: rowPixelSize
                        font.weight: Font.Bold
                        // OUTLINE 样式交回 Qt 文字引擎（单个 item，描边随字形一起光栅化）
                        style: win.renderStyle === "OUTLINE" ? Text.Outline : Text.Normal
                        styleColor: win.outlineColor
                        font.preferShaping: true
                    }

                    // 已唱层：同一段文字用满亮度再画一遍，被宽度随进度推进的整块矩形裁切。
                    // 只对原文行 + 有逐词数据时启用；翻译/音译行没有词时间轴，不参与逐字。
                    Item {
                        x: 0
                        y: 0
                        width: Math.max(0, parent.highlightX + parent.textWidth * win.wordProgress)
                        height: parent.height
                        visible: isOriginal && win.karaokeActive && width > 0
                        clip: true

                        Text {
                            width: parent.width
                            text: rowText
                            horizontalAlignment: win.alignment
                            wrapMode: Text.Wrap
                            maximumLineCount: 2
                            elide: Text.ElideRight
                            color: win.textColor
                            opacity: rowAlpha
                            font.family: Prefs.effectiveLyricFont
                            font.pixelSize: rowPixelSize
                            font.weight: Font.Bold
                            style: win.renderStyle === "OUTLINE" ? Text.Outline : Text.Normal
                            styleColor: win.outlineColor
                            font.preferShaping: true
                        }
                    }
                }
            }
        }

        // 鼠标交互层（锁定状态下窗口整体穿透，不会收到事件）
        MouseArea {
            id: dragArea
            anchors.fill: parent
            enabled: !win.locked
            hoverEnabled: true
            cursorShape: pressed ? Qt.ClosedHandCursor : Qt.OpenHandCursor
            onPressed: function(mouse) {
                // 拖动期间窗口保持静止，因此 mouse.x/y 就是「指针相对窗口」的准确坐标，
                // 用它算总位移不会受窗口移动影响（这是本方案的关键）。
                const cursor = WindowDrag.globalCursorPos()
                win._dragState = {
                    pressMouseX: mouse.x,
                    pressMouseY: mouse.y,
                    pressMarginX: win.targetPixelX(),
                    pressMarginY: win.targetPixelY(),
                    pressCursorX: cursor.x,
                    pressCursorY: cursor.y,
                    lastCursorX: cursor.x,
                    lastCursorY: cursor.y,
                    dx: 0,
                    dy: 0
                }
                win.dragging = true
                if (win.usesSystemMoveDrag) {
                    // 普通窗口才交给合成器拖动：layer-shell 窗口没有 xdg-toplevel，
                    // startSystemMove 不起作用，位置只能靠改 margin。
                    WindowDrag.startMove(win)
                    dragEndGuard.restart()
                }
            }
            onPositionChanged: function(mouse) {
                if (!win.dragging)
                    return
                // 只有「合成器接管指针」的拖动收不到 released，才需要靠停顿判定结束；
                // 其它路径（layer-shell 改 margin / X11 手动移动）release 会正常送达，
                // 这里不能重开兜底计时器，否则拖到一半停手会被误判成拖动结束。
                if (win.usesSystemMoveDrag && dragEndGuard.running)
                    dragEndGuard.restart()
                const drag = win._dragState
                if (!drag)
                    return
                // 普通窗口在 X11/offscreen 下位置由客户端决定：直接跟着指针走，
                // layer-shell 那套「全屏预览 + 改 margin」在这里既没必要也会闪。
                if (win.manualMoveDrag) {
                    if (!win.screen)
                        return
                    const roomW = Math.max(0, rootAvailW() - win.width)
                    const roomH = Math.max(0, rootAvailH() - win.height)
                    win.x = Math.round(win.screen.virtualX
                                       + Math.max(0, Math.min(roomW, drag.pressMarginX + mouse.x - drag.pressMouseX)))
                    win.y = Math.round(win.screen.virtualY
                                       + Math.max(0, Math.min(roomH, drag.pressMarginY + mouse.y - drag.pressMouseY)))
                    return
                }
                if (!win.screen)
                    return
                drag.dx = mouse.x - drag.pressMouseX
                drag.dy = mouse.y - drag.pressMouseY
                win.updatePreview()
            }
            onReleased: commitDrag()
            onCanceled: commitDrag()
            onDoubleClicked: win.openInMainWindow("nowplaying")

            /** 拖动结束后把位置换算成屏幕比例保存。 */
            function commitDrag() {
                if (!win.dragging)
                    return
                win.dragging = false
                dragEndGuard.stop()
                if (!win.screen) {
                    hidePreview()
                    win._dragState = null
                    return
                }
                const roomW = Math.max(1, rootAvailW() - win.width)
                const roomH = Math.max(1, rootAvailH() - win.height)

                // 位移通常来自「指针相对窗口」的差值（拖动期间窗口静止，因此精确）；
                // 但两种普通窗口路径下窗口会跟着指针动：
                //  - X11 手动拖动：指针一旦跑出窗口就收不到位移，改用拖动期间的累计位移；
                //  - Wayland 合成器拖动：窗口跟着动，累计位移由 systemMovePoll 轮询得到。
                const drag = win._dragState
                const dx = drag ? (drag.dx || 0) : 0
                const dy = drag ? (drag.dy || 0) : 0
                const baseX = drag ? drag.pressMarginX : win.targetPixelX()
                const baseY = drag ? drag.pressMarginY : win.targetPixelY()
                if (baseX < 0 || baseY < 0) {
                    hidePreview()
                    win._dragState = null
                    return
                }
                const px = Math.max(0, Math.min(roomW, baseX + dx))
                const py = Math.max(0, Math.min(roomH, baseY + dy))
                win.savePositionAt(px / roomW, py / roomH)
                // X11/offscreen 下窗口刚被拖到新位置，等设置回执更新归一化坐标前
                // 先冻结位置绑定，避免窗口闪回旧位置。
                if (win.manualMoveDrag) {
                    win._freezePosition = true
                    win.freezePositionTimer.restart()
                }
                hidePreview()
                win._dragState = null
            }
        }

        // 锁定时也能通过快捷键（Ctrl+Alt+L）解锁；这里显示一个很小的提示点
        Rectangle {
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            anchors.margins: 6
            visible: win.locked
            width: 6
            height: 6
            radius: 3
            color: Theme.withAlpha(win.textColor, 0.35)
        }

        // hover 控制条（旧版行为：鼠标移到悬浮窗任意位置就浮出简易控制栏）
        // 注意：不能用「控制条自身的 HoverHandler」驱动显隐——它隐藏时收不到 hover，
        // 会形成死锁永远不显示。这里改用整窗的 HoverHandler 驱动。

        Rectangle {
            id: controlBar

            // 6 个 30x30 按钮 + 5 个 2px 间距 + 左右各 8px 内边距。
            // 不用 Row.implicitWidth：本 Rectangle 的宽度反过来会影响 Row 的布局，
            // 形成绑定循环时算出的宽度偏小，表现为只画出最后一个按钮。
            readonly property int buttonCount: 6
            readonly property real buttonSize: 30
            readonly property real buttonSpacing: 2

            anchors.horizontalCenter: parent.horizontalCenter
            anchors.bottom: parent.bottom
            anchors.bottomMargin: 4
            height: 30
            width: buttonCount * buttonSize + (buttonCount - 1) * buttonSpacing + 16
            radius: 10
            // 注意：不要把字符串色值传给 Theme.withAlpha（它按 .r/.g/.b 取分量，字符串会得到
            // undefined 并退化成纯黑）。控制栏背景本来就是半透明黑，直接写 rgba。
            color: Qt.rgba(0, 0, 0, 0.55)
            // 显示条件：鼠标在窗口内且未锁定
            // 用覆盖整窗的 dragArea 的 hover 状态驱动：MouseArea 设了 cursorShape 后会独占
            // hover 事件，单独的 HoverHandler 在这些场景下收不到。
            readonly property bool shown: (windowHover.hovered || dragArea.containsMouse) && !win.locked
            opacity: shown ? 1 : 0
            visible: opacity > 0
            Behavior on opacity { NumberAnimation { duration: Theme.motionShort } }

            Row {
                id: controlRow
                anchors.centerIn: parent
                spacing: 2

                // 固定 / 解锁：放第一位，让中间播放键保持视觉居中（与旧版一致）
                IconButton {
                    icon: win.locked ? "lock" : "lock_open"
                    iconSize: 17
                    size: 30
                    iconColor: Qt.rgba(1, 1, 1, 0.9)
                    tooltip: win.locked ? "取消固定（可拖动）" : "固定位置"
                    onClicked: Settings.set("floatingLyricsLocked", !win.locked)
                }
                IconButton {
                    icon: "skip_previous"
                    iconSize: 17
                    size: 30
                    iconColor: Qt.rgba(1, 1, 1, 0.9)
                    tooltip: "上一首"
                    onClicked: Player.previous()
                }
                IconButton {
                    icon: Player.playing ? "pause" : "play_arrow"
                    iconSize: 17
                    size: 30
                    iconColor: Qt.rgba(1, 1, 1, 0.95)
                    tooltip: Player.playing ? "暂停" : "播放"
                    onClicked: Player.togglePlayPause()
                }
                IconButton {
                    icon: "skip_next"
                    iconSize: 17
                    size: 30
                    iconColor: Qt.rgba(1, 1, 1, 0.9)
                    tooltip: "下一首"
                    onClicked: Player.next()
                }
                IconButton {
                    icon: "album"
                    iconSize: 17
                    size: 30
                    iconColor: Qt.rgba(1, 1, 1, 0.9)
                    tooltip: "打开播放页"
                    onClicked: win.openInMainWindow("nowplaying")
                }
                IconButton {
                    icon: "close"
                    iconSize: 17
                    size: 30
                    iconColor: "#FF8A80"
                    tooltip: "关闭悬浮歌词"
                    onClicked: Settings.set("floatingLyricsEnabled", false)
                }
            }
        }
    }
}
