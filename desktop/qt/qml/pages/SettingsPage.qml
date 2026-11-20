import QtQuick
import QtQuick.Controls
import QtQuick.Dialogs
import "../components"
import "../components/settings"

// 设置页：卡片式分组，对齐旧 Compose 设置页（主题 / 媒体库 / 播放 / 播放页 / 下载 / 悬浮歌词 /
// 后台与系统控制 / 音乐源 / 同步 / 快捷键 / 关于）。所有修改即时通过 Settings / Api 生效。
Item {
    id: page

    // 与其它页面一致的宽窄布局判定（设置页只需用于信息展示）。
    readonly property bool wide: width >= Theme.wideBreakpoint

    // ---------------------------------------------------------------- 常量表

    // 宽屏左侧分类栏宽度：比 Theme.navRailWidth（232）更宽，让左侧留白更舒服。
    // 用本页私有常量而非改 Theme，避免影响其它页面的导航栏。
    readonly property real settingsRailWidth: 280

    readonly property var presetSeedColors: [
        "0061A4", "6750A4", "B3261E", "C425A8",
        "00897B", "388E3C", "FBC02D", "E65100"
    ]

    readonly property var languageOptions: [
        { value: "zh-CN", label: I18n.tr("中文（简体）") },
        { value: "en", label: "English" }
    ]

    readonly property var darkModeOptions: [
        { value: "AUTO", label: I18n.tr("跟随系统") },
        { value: "LIGHT", label: I18n.tr("浅色") },
        { value: "DARK", label: I18n.tr("深色") }
    ]

    readonly property var uiScaleOptions: [
        { value: 0, label: I18n.tr("跟随系统") },
        { value: 1.0, label: "100%" },
        { value: 1.25, label: "125%" },
        { value: 1.5, label: "150%" },
        { value: 1.75, label: "175%" },
        { value: 2.0, label: "200%" },
        { value: 2.5, label: "250%" }
    ]

    readonly property var paletteStyles: [
        { value: "TonalSpot", label: I18n.tr("柔和色点"), description: I18n.tr("接近 Android 默认动态色，平衡耐看。") },
        { value: "Neutral", label: I18n.tr("低饱和"), description: I18n.tr("减少彩度，页面更克制。") },
        { value: "Vibrant", label: I18n.tr("鲜明"), description: I18n.tr("主色更亮更突出。") },
        { value: "Expressive", label: I18n.tr("表现力"), description: I18n.tr("色相更跳跃，适合个性化主题。") },
        { value: "Rainbow", label: I18n.tr("彩虹"), description: I18n.tr("色相分布更宽，色彩更丰富。") },
        { value: "FruitSalad", label: I18n.tr("果味"), description: I18n.tr("互补色更强，风格活泼。") },
        { value: "Monochrome", label: I18n.tr("单色"), description: I18n.tr("接近黑白灰，弱化主题色。") },
        { value: "Fidelity", label: I18n.tr("忠实取色"), description: I18n.tr("更贴近你选择的种子色。") },
        { value: "Content", label: I18n.tr("内容色"), description: I18n.tr("沿用内容本身的取色。") }
    ]

    readonly property var colorSpecOptions: [
        { value: "SPEC_2021", label: "2021" },
        { value: "SPEC_2025", label: "2025" }
    ]

    readonly property var qualityOptions: [
        { value: "standard", label: I18n.tr("标准") },
        { value: "higher", label: I18n.tr("较高") },
        { value: "exhigh", label: I18n.tr("极高") },
        { value: "lossless", label: I18n.tr("无损") },
        { value: "hires", label: "Hi-Res" }
    ]

    readonly property var concurrencyOptions: [
        { value: 1, label: I18n.tr("1 个") },
        { value: 2, label: I18n.tr("2 个") },
        { value: 3, label: I18n.tr("3 个") },
        { value: 4, label: I18n.tr("4 个") }
    ]

    readonly property var renderStyleOptions: [
        { value: "SHADOW", label: I18n.tr("阴影") },
        { value: "OUTLINE", label: I18n.tr("描边") }
    ]

    readonly property var alignmentOptions: [
        { value: "LEFT", label: I18n.tr("居左") },
        { value: "CENTER", label: I18n.tr("居中") },
        { value: "RIGHT", label: I18n.tr("居右") }
    ]

    // 悬浮歌词窗口形态：LAYER 走 layer-shell 置顶覆盖层，NORMAL 是普通窗口（可跨屏拖动）。
    readonly property var floatingWindowModeOptions: [
        { value: "LAYER", label: I18n.tr("置顶覆盖层") },
        { value: "NORMAL", label: I18n.tr("普通窗口") }
    ]

    readonly property var floatingLyricColors: [
        { name: "WHITE", label: I18n.tr("白色"), color: "#FFFFFF" },
        { name: "BLACK", label: I18n.tr("黑色"), color: "#000000" },
        { name: "RED", label: I18n.tr("红色"), color: "#F44336" },
        { name: "ORANGE", label: I18n.tr("橙色"), color: "#FF9800" },
        { name: "YELLOW", label: I18n.tr("黄色"), color: "#FFEB3B" },
        { name: "GREEN", label: I18n.tr("绿色"), color: "#4CAF50" },
        { name: "CYAN", label: I18n.tr("青色"), color: "#00BCD4" },
        { name: "BLUE", label: I18n.tr("蓝色"), color: "#2196F3" },
        { name: "PURPLE", label: I18n.tr("紫色"), color: "#9C27B0" },
        { name: "PINK", label: I18n.tr("粉色"), color: "#E91E63" }
    ]

    // ---------------------------------------------------------------- 分类导航与搜索

    // 分类顺序参考上游：账号与同步（含音乐源登录）→ 常规（含语言）→ 外观与主题
    // → 音效与倍速 → 播放设置 → 歌词 → 快捷键 → 下载与存储 → 备份与同步 → 关于。
    // 快捷键条目最多、内容自成一屏，因此从「常规」里拆出独立分类。
    //
    // 「音效与倍速」与「播放设置」原本是同一个分类：DSP 面板改成标签页后自带
    // 预设/均衡器/音色/空间/动态/扬声器/倍速八个 Tab，内容自成一屏，与「淡入淡出、
    // 音质、B 站跳过」这类播放行为没有共同语境，因此拆成两个分类
    // （上游安卓端同样是 AudioEffects 与 Playback 两个独立设置页）。
    readonly property var categoryDefs: [
        { id: "accountSync", title: I18n.tr("账号与同步"), description: I18n.tr("音乐源登录 · 一起听 · 云端同步"), icon: "account_circle", sectionIds: ["sources", "listenTogether", "sync"] },
        { id: "general", title: I18n.tr("常规"), description: I18n.tr("首页栏目 · 后台 · 语言"), icon: "tune", sectionIds: ["homeCards", "background", "language"] },
        { id: "appearance", title: I18n.tr("外观与主题"), description: I18n.tr("深浅色 · 字体 · 背景 · 播放页"), icon: "palette", sectionIds: ["theme", "appFonts", "appBackground", "playerDesign"] },
        { id: "audioEffects", title: I18n.tr("音效与倍速"), description: I18n.tr("均衡器 · 音色 · 空间 · 动态 · 扬声器 · 倍速"), icon: "graphic_eq", sectionIds: ["dsp"] },
        { id: "playback", title: I18n.tr("播放设置"), description: I18n.tr("倍速 · 淡入淡出 · 音质 · 跳过区间"), icon: "play_circle", sectionIds: ["playback", "fade", "quality", "biliSkip"] },
        { id: "lyrics", title: I18n.tr("歌词"), description: I18n.tr("悬浮歌词样式 · 歌词字体"), icon: "lyrics", sectionIds: ["floatingLyrics"] },
        { id: "shortcuts", title: I18n.tr("快捷键"), description: I18n.tr("应用内与全局快捷键"), icon: "keyboard", sectionIds: ["shortcuts"] },
        { id: "downloadStorage", title: I18n.tr("下载与存储"), description: I18n.tr("媒体库 · 下载 · 缓存清理"), icon: "download", sectionIds: ["library", "downloads", "dataCache"] },
        { id: "backupSync", title: I18n.tr("备份与同步"), description: I18n.tr("歌单 / 收藏备份与恢复"), icon: "backup", sectionIds: ["backupSync"] },
        { id: "about", title: I18n.tr("关于"), description: I18n.tr("版本与开源信息"), icon: "info", sectionIds: ["about"] }
    ]

    property string currentCategory: "audioEffects"
    property string searchQuery: ""

    readonly property var sectionIndex: ({
        "sources": musicSourceSection,
        "listenTogether": secListenTogether, "sync": secSync,
        "theme": secTheme, "appFonts": secAppFonts, "appBackground": secAppBackground,
        "playerDesign": secPlayerDesign,
        "playback": secPlayback, "fade": secFade,
        "dsp": secDsp, "quality": secQuality, "biliSkip": secBiliSkip,
        "floatingLyrics": secFloatingLyrics,
        "library": secLibrary, "downloads": secDownloads, "dataCache": secDataCache,
        "backupSync": secBackupSync,
        "homeCards": secHomeCards, "background": secBackground,
        "language": secLanguage, "shortcuts": shortcutSection, "about": secAbout
    })

    readonly property var _searchIndex: [
        { title: I18n.tr("主题设置"), keywords: "深浅色 主题色 动态取色 调色 缩放", category: "appearance", section: "theme" },
        { title: I18n.tr("应用字体"), keywords: "界面字体 字体 字族  font family 中文 预览", category: "appearance", section: "appFonts" },
        { title: I18n.tr("程序背景"), keywords: "背景 图片 模糊 暗化 壁纸 background blur dim", category: "appearance", section: "appBackground" },
        { title: I18n.tr("媒体库"), keywords: "音乐文件夹 扫描 封面", category: "downloadStorage", section: "library" },
        { title: I18n.tr("DSP 音效"), keywords: "音效 DSP 预设 均衡器 EQ 频段 十段 音色 空间 动态 扬声器 外放 倍速 变调 equalizer preset effect speaker", category: "audioEffects", section: "dsp" },
        { title: I18n.tr("播放"), keywords: "倍速 变调 播放速度 音调 播放行为", category: "playback", section: "playback" },
        { title: I18n.tr("淡入淡出"), keywords: "淡入 淡出 交叉淡入 渐变 crossfade fade 时长", category: "playback", section: "fade" },
        { title: I18n.tr("视频跳过区间"), keywords: "B站 跳过 片头 interval skip bilibili", category: "playback", section: "biliSkip" },
        { title: I18n.tr("播放页"), keywords: "封面 控制条 歌词对齐 背景", category: "appearance", section: "playerDesign" },
        { title: I18n.tr("在线播放音质"), keywords: "音质 码率 hires 无损 quality", category: "playback", section: "quality" },
        { title: I18n.tr("下载设置"), keywords: "下载目录 并发 通知", category: "downloadStorage", section: "downloads" },
        { title: I18n.tr("悬浮歌词"), keywords: "桌面歌词 字体 颜色 透明度", category: "lyrics", section: "floatingLyrics" },
        { title: I18n.tr("首页栏目"), keywords: "推荐 排行榜 歌单 私人FM 平铺 网格 homeGridMode", category: "general", section: "homeCards" },
        { title: I18n.tr("后台与系统控制"), keywords: "托盘 MPRIS 通知 启动", category: "general", section: "background" },
        { title: I18n.tr("音乐源"), keywords: "网易云 B站 bilibili YouTube 登录 退出 自动换源 账号", category: "accountSync", section: "sources" },
        { title: I18n.tr("一起听"), keywords: "房间 同步播放", category: "accountSync", section: "listenTogether" },
        { title: I18n.tr("同步"), keywords: "GitHub Token 仓库 自动同步", category: "accountSync", section: "sync" },
        { title: I18n.tr("备份与同步"), keywords: "备份 恢复 歌单 收藏 云端 backup sync", category: "backupSync", section: "backupSync" },
        { title: I18n.tr("数据与缓存"), keywords: "清理 缓存 历史 统计 下载记录", category: "downloadStorage", section: "dataCache" },
        { title: I18n.tr("快捷键"), keywords: "键盘 全局快捷键 录制", category: "shortcuts", section: "shortcuts" },
        { title: I18n.tr("语言"), keywords: "语言 Language 中文 英文 简体 切换 国际化 i18n", category: "general", section: "language" },
        { title: I18n.tr("关于"), keywords: "版本 开源 许可证", category: "about", section: "about" }
    ]

    readonly property var searchResults: {
        const query = page.searchQuery.trim().toLowerCase()
        if (query === "")
            return []
        const matches = []
        for (let i = 0; i < page.categoryDefs.length; i++) {
            const cat = page.categoryDefs[i]
            if ((cat.title + " " + cat.description + " " + cat.icon).toLowerCase().indexOf(query) >= 0)
                matches.push({ title: cat.title, description: cat.description, icon: cat.icon,
                               category: cat.id, section: null })
        }
        for (let i = 0; i < page._searchIndex.length; i++) {
            const item = page._searchIndex[i]
            if ((item.title + " " + item.keywords).toLowerCase().indexOf(query) >= 0)
                matches.push({ title: item.title, description: I18n.tr("跳转到该设置卡片"), icon: "tune",
                               category: item.category, section: item.section })
        }
        return matches.slice(0, 20)
    }

    readonly property string currentCategoryTitle: {
        for (let i = 0; i < page.categoryDefs.length; i++) {
            if (page.categoryDefs[i].id === page.currentCategory)
                return page.categoryDefs[i].title
        }
        return I18n.tr("设置")
    }

    readonly property string currentCategoryDescription: {
        for (let i = 0; i < page.categoryDefs.length; i++) {
            if (page.categoryDefs[i].id === page.currentCategory)
                return page.categoryDefs[i].description
        }
        return ""
    }

    property var _visitedCategories: []

    /** 首次进入某个分类时展开第一张卡片，避免整页只有折叠标题、信息密度过低。 */
    function expandFirstCard(categoryId) {
        for (let i = 0; i < page.categoryDefs.length; i++) {
            const cat = page.categoryDefs[i]
            if (cat.id !== categoryId)
                continue
            for (let j = 0; j < cat.sectionIds.length; j++) {
                const sectionId = cat.sectionIds[j]
                if (sectionId === "shortcuts" || sectionId === "about" || sectionId === "backupSync")
                    continue
                const section = page.sectionIndex[sectionId]
                if (section) {
                    section.expanded = true
                    return
                }
            }
        }
    }

    function selectCategory(id) {
        if (page._visitedCategories.indexOf(id) < 0) {
            page._visitedCategories.push(id)
            page.expandFirstCard(id)
        }
        page.currentCategory = id
        flick.contentY = 0
    }

    function revealSection(sectionId) {
        const section = page.sectionIndex[sectionId]
        if (!section)
            return
        section.expanded = true
        sectionScrollTimer.targetSection = section
        sectionScrollTimer.start()
    }

    function openFirstSearchResult() {
        if (page.searchResults.length === 0)
            return
        const result = page.searchResults[0]
        page.searchQuery = ""
        page.selectCategory(result.category)
        if (result.section)
            page.revealSection(result.section)
        else
            flick.contentY = 0
    }

    function openSearchResult(index) {
        const result = page.searchResults[index]
        if (!result)
            return
        page.searchQuery = ""
        page.selectCategory(result.category)
        if (result.section)
            page.revealSection(result.section)
        else
            flick.contentY = 0
    }

    // ---------------------------------------------------------------- 页面状态

    property var accounts: []
    property var syncConfig: ({})
    property var syncState: ({})
    property bool syncUnsupported: false
    property bool syncBusy: false
    property string syncStatusMessage: ""
    property string appVersion: "1.4.7"

    // 前端本地偏好（字体 / 背景）：后端 AppSettings 无对应字段，settings.set 会静默忽略未知键。
    // 这里以内存态为准；Settings.set 仍按需求写入键名（若后续后端补齐字段即可自动持久化）。
    FontPrefs { id: fontPrefs }

    function frontendKeyWrite(key, value) {
        Settings.set(key, value)
    }

    /** 淡入淡出：统一打包成 player.setFadeOptions 一次调用，失败只提示不影响 UI。 */
    function applyFadeOptions(overrides) {
        const changes = overrides || {}
        function field(key, fallback) {
            return changes[key] !== undefined ? changes[key] : page.sv(key, fallback)
        }
        function flag(key, fallback) {
            return changes[key] !== undefined ? changes[key] === true : page.sb(key, fallback)
        }
        const payload = {
            fadeEnabled: flag("fadeEnabled", true),
            fadeInMs: Math.round(Number(field("fadeInMs", 300))),
            fadeOutMs: Math.round(Number(field("fadeOutMs", 300))),
            crossfadeEnabled: flag("crossfadeEnabled", false),
            crossfadeMs: Math.round(Number(field("crossfadeMs", 2000)))
        }
        Api.call("player.setFadeOptions", payload).catch(function(error) {
            console.warn("[settings] setFadeOptions failed:", error && error.message)
            Toast.show(I18n.tr("设置淡入淡出失败（后端暂不支持）：")
                       + (error && error.message ? error.message : I18n.tr("未知错误")), "error")
        })
    }

    function chooseBackgroundImage() {
        try {
            backgroundFileDialog.open()
        } catch (error) {
            Toast.show(I18n.tr("无法打开文件选择器：") + error, "error")
        }
    }

    property int songCount: 0
    property bool scanning: false
    property int scanTotal: 0
    property int scanProcessed: 0
    property int scanFound: 0
    property string scanError: ""
    property bool clearingCache: false

    property var _onScan: null
    property var _onLibraryChanged: null
    property var _onAccountsChanged: null
    property var _onSyncState: null

    // 仅供截图验证：>0 时把设置页滚动到该位置；-1 表示不启用（--screenshot 模式下自动定位到快捷键卡片）。
    property int __debugScrollTo: -1
    /** 仅供截图：指定要打开的分类（空串则沿用旧行为切快捷键）。 */
    property string __debugCategory: ""
    /**
     * 仅供截图：把 DSP 面板切到指定标签页（presets/equalizer/tone/space/dynamics/speaker/speed/advanced）。
     * 空串表示不动。面板是 Loader 里的独立对象，所以这里通过它的 currentTab 属性转发。
     */
    property string __debugDspTab: ""

    function applyDebugDspTab() {
        if (page.__debugDspTab === "" || !dspLoader.item)
            return
        dspLoader.item.currentTab = page.__debugDspTab
    }

    /**
     * 截图验证用：从 `--page settings --arg '{"category":"…","dspTab":"…"}'` 里取分类与标签页。
     * 设置页是 tab 路由，Nav 不会把 arg 传下来，所以这里直接读命令行。
     */
    function readDebugArg() {
        const raw = page.cliArgument("--arg")
        if (!raw)
            return
        let arg = null
        try {
            arg = JSON.parse(raw)
        } catch (error) {
            return
        }
        if (!arg || typeof arg !== "object")
            return
        if (typeof arg.category === "string")
            page.__debugCategory = arg.category
        if (typeof arg.dspTab === "string")
            page.__debugDspTab = arg.dspTab
    }

    // ---------------------------------------------------------------- 工具函数

    function sv(key, fallback) {
        const values = Settings.values
        if (!values)
            return fallback
        const value = values[key]
        return (value === undefined || value === null) ? fallback : value
    }

    function sb(key, fallback) {
        const value = page.sv(key, fallback)
        return value === true
    }

    function cliArgument(name) {
        const args = Qt.application.arguments
        const index = args.indexOf(name)
        return (index >= 0 && index + 1 < args.length) ? args[index + 1] : ""
    }

    // 截图验证辅助：滚动到指定位置或快捷键卡片；分类视图下先切到快捷键分类。
    function scrollToDebugTarget() {
        if (!flick)
            return
        page.readDebugArg()
        // 截图验证：可用 __debugCategory 指定分类（默认仍切到快捷键，兼容既有用法）。
        if (page.__debugCategory !== "")
            page.selectCategory(page.__debugCategory)
        else if (page.__debugScrollTo < 0)
            page.selectCategory("shortcuts")
        page.applyDebugDspTab()
        const maxY = Math.max(0, flick.contentHeight - flick.height)
        const shortcutY = shortcutSection.mapToItem(contentColumn, 0, 0).y
        const target = page.__debugScrollTo >= 0 ? page.__debugScrollTo
                                                 : (page.__debugCategory !== "" ? 0
                                                                                : Math.max(0, shortcutY - 8))
        flick.contentY = Math.max(0, Math.min(target, maxY))
    }

    function setSetting(key, value) {
        Settings.set(key, value)
    }

    /** 界面缩放：设置值 <= 0 表示跟随系统，这里换算成实际百分比文案。 */
    function effectiveScaleText() {
        const value = Number(page.sv("uiScale", 0))
        if (!(value > 0))
            return Math.round((screen ? screen.devicePixelRatio : 1) * 100) + I18n.tr("% · 跟随系统")
        return Math.round(value * 100) + I18n.tr("% · 设置中手动指定")
    }

    function screenText() {
        if (!screen)
            return I18n.tr("未知")
        return Math.round(screen.width) + "×" + Math.round(screen.height) + I18n.tr(" 像素")
    }

    function patchSettings(changes) {
        Settings.patch(changes)
    }

    /** 读取首页栏目开关（Settings.homeCards 的嵌套字段）。 */
    function homeCardEnabled(key, fallback) {
        const cards = page.sv("homeCards", null)
        if (!cards)
            return fallback !== false
        return cards[key] !== false
    }

    /** 写入首页栏目开关：后端只认整个 homeCards 对象，需整体 patch。 */
    function setHomeCard(key, value) {
        const cards = page.sv("homeCards", null) || {}
        const next = {}
        for (const name in cards)
            next[name] = cards[name]
        next[key] = value
        page.patchSettings({ "homeCards": next })
    }

    function sanitizeHex(text) {
        if (text === undefined || text === null)
            return ""
        const value = String(text).trim().replace(/^#/, "").toUpperCase()
        return /^[0-9A-F]{6}$/.test(value) ? value : ""
    }

    function formatTime(millis) {
        if (!millis)
            return ""
        const date = new Date(millis)
        function pad(value) {
            return value < 10 ? "0" + value : "" + value
        }
        return date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate())
                + " " + pad(date.getHours()) + ":" + pad(date.getMinutes())
    }

    function pathFromUrl(url) {
        let text = String(url)
        if (text.indexOf("file://") === 0)
            text = text.substring(7)
        try {
            return decodeURIComponent(text)
        } catch (error) {
            return text
        }
    }

    function paletteDescription(style) {
        for (let i = 0; i < page.paletteStyles.length; i++) {
            if (page.paletteStyles[i].value === style)
                return page.paletteStyles[i].description
        }
        return ""
    }

    // ---------------------------------------------------------------- 主题

    function selectPresetSeed(hex) {
        if (page.sb("followSystemTheme", true)) {
            Toast.show(I18n.tr("「跟随系统主题色」已开启，请先关闭后再选择主题色"), "info")
            return
        }
        page.patchSettings({ themeSeedColor: hex, followSystemTheme: false, dynamicColor: false })
    }

    function applyCustomSeed() {
        if (page.sb("followSystemTheme", true)) {
            Toast.show(I18n.tr("「跟随系统主题色」已开启，请先关闭后再应用自定义主题色"), "info")
            return
        }
        const sanitized = page.sanitizeHex(seedField.text)
        if (sanitized === "") {
            Toast.show(I18n.tr("请输入 6 位十六进制色值，例如 0061A4"), "error")
            return
        }
        page.patchSettings({ themeSeedColor: sanitized, followSystemTheme: false, dynamicColor: false })
        seedField.text = sanitized
        Toast.show(I18n.tr("主题色已更新为 #") + sanitized, "info")
    }

    // ---------------------------------------------------------------- 媒体库

    function applyScanState(state) {
        if (!state)
            return
        page.scanning = state.running === true
        page.scanTotal = state.total !== undefined ? state.total : 0
        page.scanProcessed = state.processed !== undefined ? state.processed : 0
        page.scanFound = state.found !== undefined ? state.found : 0
        page.scanError = state.error ? String(state.error) : ""
    }

    readonly property string libraryStatusText: {
        if (page.scanning)
            return I18n.tr("正在扫描 ") + page.scanProcessed + " / " + page.scanTotal
                    + I18n.tr("，已找到 ") + page.scanFound + I18n.tr(" 首")
        if (page.scanError !== "")
            return I18n.tr("上次扫描出错：") + page.scanError
        return I18n.tr("媒体库共 ") + page.songCount + I18n.tr(" 首歌曲")
    }

    function chooseMusicFolder() {
        try {
            musicFolderDialog.open()
        } catch (error) {
            Toast.show(I18n.tr("无法打开文件夹选择器：") + error, "error")
        }
    }

    function addMusicFolder(path) {
        if (!path)
            return
        Api.call("library.addFolder", { path: path }).then(function(result) {
            if (result && result.settings)
                Settings.values = result.settings
            Toast.show(I18n.tr("已添加文件夹：") + path, "info")
        }).catch(function(error) {
            Toast.show(I18n.tr("添加文件夹失败：") + (error && error.message ? error.message : I18n.tr("未知错误")), "error")
        })
    }

    function removeMusicFolder(path) {
        Api.call("library.removeFolder", { path: path }).then(function(result) {
            if (result && result.settings)
                Settings.values = result.settings
            Toast.show(I18n.tr("已移除文件夹"), "info")
        }).catch(function(error) {
            Toast.show(I18n.tr("移除文件夹失败：") + (error && error.message ? error.message : I18n.tr("未知错误")), "error")
        })
    }

    function rescanLibrary() {
        page.scanning = true
        page.scanError = ""
        Api.call("library.scan").then(function(result) {
            page.scanning = false
            if (result && result.count !== undefined)
                page.songCount = result.count
            Toast.show(I18n.tr("扫描完成，共 ") + (result && result.count !== undefined ? result.count : 0) + I18n.tr(" 首歌曲"), "info")
        }).catch(function(error) {
            page.scanning = false
            Toast.show(I18n.tr("扫描失败：") + (error && error.message ? error.message : I18n.tr("未知错误")), "error")
        })
    }

    /** 清空本机封面缓存（后端 artwork.clearCache）。 */
    function clearArtworkCache() {
        page.clearingCache = true
        Api.call("artwork.clearCache").then(function(result) {
            page.clearingCache = false
            const removed = result && result.removed !== undefined ? Number(result.removed) : 0
            Toast.show(I18n.tr("已清除 ") + removed + I18n.tr(" 个封面缓存文件"), "info")
        }).catch(function(error) {
            page.clearingCache = false
            Toast.show(I18n.tr("清除缓存失败：") + (error && error.message ? error.message : I18n.tr("未知错误")), "error")
        })
    }

    /** 通用清理命令：调用成功后刷新快照并提示；失败只提示，不影响页面渲染。 */
    function runCleanup(method, successMessage) {
        Api.call(method).then(function() {
            Toast.show(successMessage, "info")
            page.loadSnapshot()
        }).catch(function(error) {
            Toast.show(I18n.tr("清理失败：") + (error && error.message ? error.message : I18n.tr("未知错误")), "error")
        })
    }

    function clearFinishedDownloads() {
        page.runCleanup("download.clearFinished", I18n.tr("已清理已完成的下载记录"))
    }

    function clearHistory() {
        page.runCleanup("history.clear", I18n.tr("已清除播放历史"))
    }

    function clearStats() {
        page.runCleanup("stats.clear", I18n.tr("已清除播放统计"))
    }

    // ---------------------------------------------------------------- 下载

    function chooseDownloadFolder() {
        try {
            downloadFolderDialog.open()
        } catch (error) {
            Toast.show(I18n.tr("无法打开文件夹选择器：") + error, "error")
        }
    }

    // ---------------------------------------------------------------- 音乐源 / 账号

    function applyAccounts(list) {
        // C++ 桥现在会下发真正的 JS 数组；同时兼容旧式 QVariantList 序列对象。
        const result = []
        if (!list)
            return void (page.accounts = result)
        for (let i = 0; i < list.length; ++i)
            result.push(list[i])
        page.accounts = result
    }

    function findAccount(source) {
        for (let i = 0; i < page.accounts.length; i++) {
            const account = page.accounts[i]
            if (account && account.source === source)
                return account
        }
        return null
    }

    function accountName(account) {
        if (!account)
            return I18n.tr("未登录")
        if (account.nickname)
            return account.nickname
        if (account.userId)
            return "UID " + account.userId
        return I18n.tr("已登录")
    }

    function accountDetail(account, fallback) {
        if (!account)
            return fallback
        let text = account.userId ? ("UID " + account.userId) : I18n.tr("已登录")
        if (account.vip)
            text += I18n.tr(" · 会员")
        return text
    }

    function loadAccounts() {
        Api.call("login.accounts").then(function(result) {
            page.applyAccounts(result && result.accounts)
        }).catch(function(error) {
            console.warn("[settings] login.accounts failed:", error && error.message)
        })
    }

    function logoutAccount(source) {
        Api.call("login.logout", { source: source }).then(function() {
            Toast.show(I18n.tr("已退出登录"), "info")
            page.loadAccounts()
        }).catch(function(error) {
            Toast.show(I18n.tr("退出登录失败：") + (error && error.message ? error.message : I18n.tr("未知错误")), "error")
        })
    }

    /** 音乐源「登录 / 退出」统一入口：YouTube 走 Cookie 面板与 ytmusic.logout，其它两源扫码登录。 */
    function toggleSourceAccount(source) {
        if (page.findAccount(source)) {
            page.logoutSource(source)
            return
        }
        if (source === "YOUTUBE")
            Nav.openYouTubeLoginPanel()
        else
            Nav.openLoginPanel(source)
    }

    function logoutSource(source) {
        if (source !== "YOUTUBE") {
            page.logoutAccount(source)
            return
        }
        Api.call("ytmusic.logout").then(function() {
            Toast.show(I18n.tr("已退出 YouTube Music"), "info")
            page.loadAccounts()
        }).catch(function(error) {
            Toast.show(I18n.tr("退出失败：") + (error && error.message ? error.message : I18n.tr("未知错误")), "error")
        })
    }

    // ---------------------------------------------------------------- 同步

    function syncErrorText(error, prefix) {
        const message = error && error.message ? error.message : I18n.tr("未知错误")
        return prefix + "：" + message
    }

    function handleSyncError(error, prefix) {
        if (error && error.code === "unknown_method") {
            page.syncUnsupported = true
            page.syncStatusMessage = I18n.tr("后端暂不支持同步命令（sync.*）")
            Toast.show(I18n.tr("后端暂不支持同步命令"), "error")
        } else {
            page.syncStatusMessage = page.syncErrorText(error, prefix)
            Toast.show(page.syncStatusMessage, "error")
        }
    }

    function applySyncConfig(config) {
        if (!config)
            return
        page.syncConfig = config
        tokenField.text = ""
        tokenField.placeholderText = config.tokenSet
                ? I18n.tr("已保存（留空保持不变）")
                : I18n.tr("ghp_xxx（需要 repo 权限）")
        ownerField.text = config.owner ? config.owner : ""
        repoField.text = config.repo ? config.repo : ""
    }

    function loadSyncConfig() {
        Api.call("sync.config").then(function(config) {
            page.syncUnsupported = false
            page.syncStatusMessage = ""
            page.applySyncConfig(config)
        }).catch(function(error) {
            if (error && error.code === "unknown_method") {
                page.syncUnsupported = true
                page.syncStatusMessage = I18n.tr("后端暂不支持同步命令（sync.*）")
            } else {
                page.syncStatusMessage = page.syncErrorText(error, I18n.tr("读取同步配置失败"))
            }
        })
    }

    function saveSyncConfig() {
        if (page.syncUnsupported) {
            Toast.show(I18n.tr("后端暂不支持同步命令"), "error")
            return
        }
        const payload = {
            "owner": ownerField.text.trim(),
            "repo": repoField.text.trim()
        }
        const token = tokenField.text.trim()
        if (token !== "")
            payload.token = token
        page.syncBusy = true
        Api.call("sync.configure", payload).then(function(config) {
            page.syncBusy = false
            page.applySyncConfig(config)
            page.syncStatusMessage = I18n.tr("同步配置已保存")
            Toast.show(I18n.tr("同步配置已保存"), "info")
            page.loadSyncConfig()
        }).catch(function(error) {
            page.syncBusy = false
            page.handleSyncError(error, I18n.tr("保存同步配置失败"))
        })
    }

    function performSync() {
        if (page.syncUnsupported) {
            Toast.show(I18n.tr("后端暂不支持同步命令"), "error")
            return
        }
        page.syncBusy = true
        page.syncStatusMessage = I18n.tr("正在同步…")
        Api.call("sync.perform").then(function(result) {
            page.syncBusy = false
            const message = result && result.message ? result.message : I18n.tr("同步完成")
            page.syncStatusMessage = message
            Toast.show(message, result && result.success ? "info" : "error")
            page.loadSyncConfig()
        }).catch(function(error) {
            page.syncBusy = false
            page.handleSyncError(error, I18n.tr("同步失败"))
        })
    }

    /** 断开同步：清掉本地保存的仓库配置与 Token。 */
    function disableSync() {
        if (page.syncUnsupported) {
            Toast.show(I18n.tr("后端暂不支持同步命令"), "error")
            return
        }
        page.syncBusy = true
        Api.call("sync.disable").then(function() {
            page.syncBusy = false
            page.syncStatusMessage = ""
            Toast.show(I18n.tr("已断开同步"), "info")
            page.loadSyncConfig()
        }).catch(function(error) {
            page.syncBusy = false
            page.handleSyncError(error, I18n.tr("断开同步失败"))
        })
    }

    function syncConfigValue(key, fallback) {
        const config = page.syncConfig
        if (!config)
            return fallback
        const value = config[key]
        return (value === undefined || value === null) ? fallback : value
    }

    function updateSyncFlag(key, value) {
        if (page.syncUnsupported) {
            Toast.show(I18n.tr("后端暂不支持同步命令"), "error")
            return
        }
        const payload = {}
        payload[key] = value
        page.syncBusy = true
        Api.call("sync.configure", payload).then(function(config) {
            page.syncBusy = false
            page.applySyncConfig(config)
            Toast.show(value ? I18n.tr("已开启") : I18n.tr("已关闭"), "info")
        }).catch(function(error) {
            page.syncBusy = false
            page.handleSyncError(error, I18n.tr("更新同步选项失败"))
        })
    }

    readonly property bool syncConfigured: page.syncConfig && page.syncConfig.configured === true

    readonly property string syncStatusText: {
        if (page.syncUnsupported)
            return I18n.tr("后端暂不支持同步命令（sync.*）")
        if (page.syncBusy || (page.syncState && page.syncState.running))
            return I18n.tr("正在同步…")
        if (page.syncStatusMessage !== "")
            return page.syncStatusMessage
        const last = page.syncConfig && page.syncConfig.lastSyncAt
                ? page.formatTime(page.syncConfig.lastSyncAt)
                : (page.syncState && page.syncState.lastSyncAt ? page.formatTime(page.syncState.lastSyncAt) : "")
        let text = last !== "" ? (I18n.tr("上次同步：") + last) : I18n.tr("尚未同步")
        if (page.syncState && page.syncState.message)
            text += " · " + page.syncState.message
        return text
    }

    readonly property color syncStatusColor: {
        if (page.syncUnsupported)
            return Theme.error
        if (page.syncState && page.syncState.success === false && page.syncStatusMessage !== "")
            return Theme.error
        return Theme.onSurfaceVariantColor
    }

    // ---------------------------------------------------------------- 数据加载

    function loadSnapshot() {
        Api.call("app.bootstrap").then(function(snapshot) {
            if (!snapshot)
                return
            if (snapshot.appVersion)
                page.appVersion = snapshot.appVersion
            if (snapshot.library) {
                page.songCount = snapshot.library.songCount !== undefined ? snapshot.library.songCount : page.songCount
                page.applyScanState({
                    "running": snapshot.library.scanning,
                    "total": snapshot.library.scanTotal,
                    "processed": snapshot.library.scanProcessed,
                    "found": snapshot.library.scanFound
                })
            }
            if (snapshot.accounts)
                page.applyAccounts(snapshot.accounts)
            if (snapshot.sync)
                page.syncState = snapshot.sync
        }).catch(function(error) {
            console.warn("[settings] bootstrap failed:", error && error.message)
        })
    }

    function refreshFromEvents() {
        page.loadSnapshot()
        page.loadAccounts()
        page.loadSyncConfig()
    }

    Component.onCompleted: {
        // 默认落在「音效与倍速」：DSP 标签页是这一页最主要的操作对象
        page.selectCategory("audioEffects")
        if (Backend.connected) {
            page.loadSnapshot()
            page.loadAccounts()
            page.loadSyncConfig()
        }

        page._onScan = function(data) {
            page.applyScanState(data)
        }
        page._onLibraryChanged = function(data) {
            if (data && data.songCount !== undefined)
                page.songCount = data.songCount
        }
        page._onAccountsChanged = function(data) {
            page.applyAccounts(data)
        }
        page._onSyncState = function(data) {
            if (!data)
                return
            page.syncState = data
            if (data.message)
                page.syncStatusMessage = ""
        }
        // 截图验证辅助：离屏截图时内容较长，自动滚动到快捷键卡片；正常启动不受影响。
        if (page.cliArgument("--page") === "settings"
                && Qt.application.arguments.indexOf("--screenshot") >= 0)
            shortcutDebugScroll.start()
        Api.on("library.scan", page._onScan)
        Api.on("library.changed", page._onLibraryChanged)
        Api.on("accounts.changed", page._onAccountsChanged)
        Api.on("sync.state", page._onSyncState)
    }

    Component.onDestruction: {
        Api.off("library.scan", page._onScan)
        Api.off("library.changed", page._onLibraryChanged)
        Api.off("accounts.changed", page._onAccountsChanged)
        Api.off("sync.state", page._onSyncState)
    }

    Connections {
        target: Settings
        function onValuesChanged() {
            if (seedField.activeFocus)
                return
            const seed = page.sv("themeSeedColor", "0061A4").toUpperCase()
            if (seedField.text.toUpperCase() !== seed)
                seedField.text = seed
        }
    }

    Connections {
        target: Backend
        function onConnectedChanged() {
            if (Backend.connected)
                page.refreshFromEvents()
        }
    }

    // ---------------------------------------------------------------- 文件夹选择器

    FolderDialog {
        id: musicFolderDialog
        title: I18n.tr("选择音乐文件夹")
        onAccepted: page.addMusicFolder(page.pathFromUrl(selectedFolder))
    }

    FolderDialog {
        id: downloadFolderDialog
        title: I18n.tr("选择下载目录")
        onAccepted: {
            const path = page.pathFromUrl(selectedFolder)
            if (!path)
                return
            page.setSetting("downloadDirectory", path)
            Toast.show(I18n.tr("下载目录已设为：") + path, "info")
        }
    }

    FileDialog {
        id: backgroundFileDialog
        title: I18n.tr("选择程序背景图片")
        nameFilters: [I18n.tr("图片文件 (*.jpg *.jpeg *.png *.webp *.gif)"), I18n.tr("所有文件 (*)")]
        onAccepted: {
            const path = page.pathFromUrl(selectedFile)
            if (!path)
                return
            fontPrefs.setBackground(path, fontPrefs.appBackgroundBlur, fontPrefs.appBackgroundDim)
            page.frontendKeyWrite("appBackgroundImage", path)
            Toast.show(I18n.tr("背景图片已选择（仅本次会话）"), "info")
        }
    }

    // ---------------------------------------------------------------- 页面骨架

    SettingsSidebar {
        id: sidebar
        anchors.top: parent.top
        anchors.left: parent.left
        width: page.wide ? page.settingsRailWidth : page.width
        height: page.wide ? parent.height : 76
        categories: page.categoryDefs
        results: page.searchResults
        currentCategory: page.currentCategory
        query: page.searchQuery
        compact: !page.wide
        onCategorySelected: function(id) { page.selectCategory(id) }
        onQueryEdited: function(text) { page.searchQuery = text }
        onSearchSubmitted: function() { page.openFirstSearchResult() }
        onSearchResultSelected: function(index) { page.openSearchResult(index) }
    }

    Flickable {
        id: flick
        anchors.top: page.wide ? parent.top : sidebar.bottom
        anchors.bottom: parent.bottom
        anchors.left: page.wide ? sidebar.right : parent.left
        anchors.right: parent.right
        contentWidth: width
        contentHeight: contentColumn.height
        clip: true
        boundsBehavior: Flickable.StopAtBounds

        Column {
            id: contentColumn
            x: Math.max(0, (flick.width - width) / 2)
            width: Math.min(flick.width, 1180)
            spacing: 0

            // 右栏固定头部：显示当前分类标题与说明（与左侧导航的选中项一致）。
            Item {
                width: parent.width
                height: 84
                Column {
                    x: Theme.spacingLg
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: 2

                    Text {
                        text: page.currentCategoryTitle
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("headlineSmall").size
                        font.weight: Font.DemiBold
                    }

                    Text {
                        text: page.currentCategoryDescription
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("bodySmall").size
                    }
                }
            }

            Item {
                id: catAccountSync
                width: parent.width
                height: visible ? innerAccountSync.height + 12 : 0
                visible: page.currentCategory === "accountSync"

                Column {
                    id: innerAccountSync
                    width: parent.width
                    spacing: 12

            // ============================================================ 音乐源（紧凑卡片，账号统一管理）
            SettingsSection {
                id: musicSourceSection
                width: parent.width - 24
                x: 12
                title: I18n.tr("音乐源")
                description: I18n.tr("网易云 / 哔哩哔哩 / YouTube 的开关与账号登录")
                icon: "cloud"

                Column {
                    width: parent.width
                    spacing: Theme.spacingSm

                    MusicSourceCard {
                        displayName: I18n.tr("网易云音乐")
                        iconName: "music_note"
                        description: I18n.tr("未登录：扫码登录后可解锁每日推荐与私人 FM")
                        loginButtonText: I18n.tr("扫码登录")
                        settingValue: page.sb("neteaseEnabled", true)
                        account: page.findAccount("NETEASE")
                        onToggled: function(value) { page.setSetting("neteaseEnabled", value) }
                        onLoginClicked: page.toggleSourceAccount("NETEASE")
                    }

                    MusicSourceCard {
                        displayName: I18n.tr("哔哩哔哩")
                        iconName: "smart_display"
                        description: I18n.tr("未登录：可搜索视频音频轨，收藏夹需要登录")
                        loginButtonText: I18n.tr("扫码登录")
                        settingValue: page.sb("bilibiliEnabled", true)
                        account: page.findAccount("BILIBILI")
                        onToggled: function(value) { page.setSetting("bilibiliEnabled", value) }
                        onLoginClicked: page.toggleSourceAccount("BILIBILI")
                    }

                    MusicSourceCard {
                        displayName: "YouTube Music"
                        iconName: "smart_display"
                        description: I18n.tr("未登录：只能搜索公开内容，登录需从浏览器复制 Cookie")
                        loginButtonText: I18n.tr("登录")
                        settingValue: page.sb("youtubeEnabled", false)
                        account: page.findAccount("YOUTUBE")
                        onToggled: function(value) { page.setSetting("youtubeEnabled", value) }
                        onLoginClicked: page.toggleSourceAccount("YOUTUBE")
                    }

                    SettingsSwitchRow {
                        width: parent.width
                        title: I18n.tr("自动换源")
                        description: I18n.tr("网易云不可播放（VIP / 版权 / 接口限制）时，自动到 B 站搜索同名歌曲播放")
                        checked: page.sb("neteaseAutoSourceSwitch", true)
                        onToggled: function(checked) { page.setSetting("neteaseAutoSourceSwitch", checked) }
                    }

                    SettingsNote {
                        width: parent.width
                        text: I18n.tr("登录凭据（Cookie）仅保存在本机配置目录。关闭某个音源后，它的栏目与账号歌单会在首页 / 媒体库中隐藏。")
                    }
                }
            }

            // ============================================================ 一起听
            SettingsSection {
                id: secListenTogether
                width: parent.width - 24
                x: 12
                title: I18n.tr("一起听")
                description: I18n.tr("与手机端实时同步播放的自建房间")
                icon: "group"

                SettingsRow {
                    width: parent.width
                    title: I18n.tr("一起听房间")
                    description: I18n.tr("需要先配置自己的 NeriPlayer-LTW 服务端地址；面板里可创建或加入房间")
                    SettingsButton {
                        text: I18n.tr("打开一起听")
                        onClicked: Nav.openListenTogetherPanel()
                    }
                }

                SettingsNote {
                    width: parent.width
                    text: I18n.tr("桌面端与 Android 端共用同一套一起听协议，可在同一个房间里同步播放。")
                }
            }

            // ============================================================ 同步
            SettingsSection {
                id: secSync
                width: parent.width - 24
                x: 12
                title: I18n.tr("同步")
                description: I18n.tr("通过自己的 GitHub 仓库跨设备同步歌单、收藏、最近播放与统计")
                icon: "sync"

                SettingsRow {
                    width: parent.width
                    title: page.syncUnsupported
                           ? I18n.tr("后端暂不支持")
                           : (page.syncConfigured
                              ? I18n.tr("已配置：") + (page.syncConfig.repoFullName
                                             ? page.syncConfig.repoFullName
                                             : ((page.syncConfig.owner || "") + "/" + (page.syncConfig.repo || "")))
                              : I18n.tr("未配置"))
                    description: page.syncStatusText
                    descriptionColor: page.syncStatusColor

                    SettingsButton {
                        text: page.syncBusy ? I18n.tr("处理中…") : I18n.tr("立即同步")
                        enabled: !page.syncBusy && !page.syncUnsupported
                        onClicked: page.performSync()
                    }

                    SettingsButton {
                        text: I18n.tr("断开同步")
                        enabled: !page.syncBusy && page.syncConfigured
                        onClicked: page.disableSync()
                    }
                }

                Item {
                    width: 1
                    height: 10
                    visible: !page.syncUnsupported
                }

                SettingsLabel {
                    width: parent.width
                    text: "GitHub Token"
                    visible: !page.syncUnsupported
                }

                SettingsTextField {
                    id: tokenField
                    width: parent.width
                    visible: !page.syncUnsupported
                    placeholderText: I18n.tr("ghp_xxx（需要 repo 权限）")
                    echoMode: TextInput.Password
                }

                SettingsNote {
                    width: parent.width
                    visible: !page.syncUnsupported
                    text: I18n.tr("在 GitHub → Settings → Developer settings → Personal access tokens 生成 Token，建议 fine-grained 且只授权同步仓库。Token 只保存在本机，回显时打码。")
                }

                Item {
                    width: 1
                    height: 10
                    visible: !page.syncUnsupported
                }

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("仓库账号 / 组织")
                    visible: !page.syncUnsupported
                }

                SettingsTextField {
                    id: ownerField
                    width: parent.width
                    visible: !page.syncUnsupported
                    placeholderText: I18n.tr("GitHub 用户名或组织")
                }

                Item {
                    width: 1
                    height: 8
                    visible: !page.syncUnsupported
                }

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("仓库名")
                    visible: !page.syncUnsupported
                }

                SettingsTextField {
                    id: repoField
                    width: parent.width
                    visible: !page.syncUnsupported
                    placeholderText: "neriplayer-sync"
                }

                Item {
                    width: 1
                    height: 6
                    visible: !page.syncUnsupported
                }

                SettingsSwitchRow {
                    width: parent.width
                    visible: !page.syncUnsupported
                    title: I18n.tr("自动同步")
                    description: I18n.tr("歌单、收藏、最近播放或统计变化后自动同步")
                    checked: page.syncConfigValue("autoSync", true)
                    onToggled: function(checked) {
                        page.updateSyncFlag("autoSync", checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    visible: !page.syncUnsupported
                    title: I18n.tr("省流模式")
                    description: I18n.tr("只同步必要的结构化数据，减少流量占用")
                    checked: page.syncConfigValue("useDataSaver", false)
                    onToggled: function(checked) {
                        page.updateSyncFlag("useDataSaver", checked)
                    }
                }

                Item {
                    width: 1
                    height: 4
                    visible: !page.syncUnsupported
                }

                Row {
                    spacing: Theme.spacingSm
                    visible: !page.syncUnsupported

                    SettingsButton {
                        text: I18n.tr("保存配置")
                        enabled: !page.syncBusy
                        onClicked: page.saveSyncConfig()
                    }

                    SettingsButton {
                        text: I18n.tr("立即同步")
                        enabled: !page.syncBusy && page.syncConfigured
                        onClicked: page.performSync()
                    }
                }
            }

                }
            }
            Item {
                id: catAppearance
                width: parent.width
                height: visible ? innerAppearance.height + 12 : 0
                visible: page.currentCategory === "appearance"

                Column {
                    id: innerAppearance
                    width: parent.width
                    spacing: 12

            // ============================================================ 主题设置
            SettingsSection {
                id: secTheme
                width: parent.width - 24
                x: 12
                title: I18n.tr("主题设置")
                description: I18n.tr("深浅色、动态取色与调色风格")
                icon: "color_lens"

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("深浅色模式")
                }

                SegmentedOptions {
                    width: parent.width
                    options: page.darkModeOptions
                    value: page.sv("darkMode", "AUTO")
                    onSelected: function(value) {
                        page.setSetting("darkMode", value)
                    }
                }

                Item {
                    width: 1
                    height: 6
                }

                SettingsNote {
                    width: parent.width
                    text: I18n.tr("选择「跟随系统」时，深浅色与主题强调色都会使用桌面环境（KDE / GNOME）的配置。")
                }

                Item {
                    width: 1
                    height: 6
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("跟随系统主题色")
                    description: I18n.tr("使用桌面环境的强调色作为主题种子色（优先于下方主题色）")
                    checked: page.sb("followSystemTheme", true)
                    onToggled: function(checked) {
                        page.setSetting("followSystemTheme", checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("动态取色（封面）")
                    description: I18n.tr("跟随当前歌曲封面主题色，无封面时回落到下方主题色")
                    checked: page.sb("dynamicColor", false)
                    onToggled: function(checked) {
                        page.setSetting("dynamicColor", checked)
                    }
                }

                Item {
                    width: 1
                    height: 10
                }

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("预设主题色")
                }

                Row {
                    width: parent.width
                    spacing: 10

                    Repeater {
                        model: page.presetSeedColors

                        delegate: ColorSwatch {
                            required property var modelData
                            swatchSize: 34
                            swatchColor: "#" + modelData
                            enabled: !page.sb("followSystemTheme", true)
                            selected: page.sv("themeSeedColor", "0061A4").toUpperCase() === String(modelData).toUpperCase()
                            onClicked: page.selectPresetSeed(String(modelData))
                        }
                    }
                }

                Item {
                    width: 1
                    height: 6
                }

                SettingsNote {
                    width: parent.width
                    visible: page.sb("followSystemTheme", true)
                    text: I18n.tr("「跟随系统主题色」已开启，预设与自定义主题色暂时不可选；关闭该开关后即可手动指定。")
                }

                Item {
                    width: 1
                    height: 10
                }

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("自定义主题色（6 位 HEX）")
                }

                Row {
                    width: parent.width
                    spacing: 10

                    SettingsTextField {
                        id: seedField
                        width: Math.min(240, parent.width - 96)
                        text: page.sv("themeSeedColor", "0061A4").toUpperCase()
                        placeholderText: "RRGGBB"
                        enabled: !page.sb("followSystemTheme", true)
                        maximumLength: 7
                    }

                    SettingsButton {
                        text: I18n.tr("应用")
                        enabled: !page.sb("followSystemTheme", true)
                        onClicked: page.applyCustomSeed()
                    }
                }

                Item {
                    width: 1
                    height: 12
                }

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("调色风格")
                }

                SegmentedOptions {
                    width: parent.width
                    options: page.paletteStyles.map(function(item) {
                        return { "value": item.value, "label": item.label }
                    })
                    value: page.sv("paletteStyle", "TonalSpot")
                    onSelected: function(value) {
                        page.setSetting("paletteStyle", value)
                    }
                }

                Item {
                    width: 1
                    height: 4
                }

                SettingsNote {
                    width: parent.width
                    text: page.paletteDescription(page.sv("paletteStyle", "TonalSpot"))
                }

                Item {
                    width: 1
                    height: 12
                }

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("色彩规范")
                }

                SegmentedOptions {
                    width: parent.width
                    options: page.colorSpecOptions
                    value: page.sv("colorSpec", "SPEC_2021")
                    onSelected: function(value) {
                        page.setSetting("colorSpec", value)
                    }
                }

                Item {
                    width: 1
                    height: 12
                }

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("界面缩放")
                }

                SegmentedOptions {
                    width: parent.width
                    options: page.uiScaleOptions
                    value: page.sv("uiScale", 0)
                    onSelected: function(value) {
                        page.setSetting("uiScale", value)
                    }
                }

                Item {
                    width: 1
                    height: 4
                }

                SettingsNote {
                    width: parent.width
                    text: I18n.tr("界面立即按比例缩放，窗口尺寸在下次启动时生效。桌面本身已开启缩放时（例如 KDE 200%），可在此手动指定。")
                }

                Item {
                    width: 1
                    height: 6
                }

                // 与旧 Compose 版一致：展示当前实际生效的缩放/布局/屏幕信息
                SettingsInfoRow {
                    width: parent.width
                    label: I18n.tr("当前生效")
                    value: page.effectiveScaleText()
                }

                SettingsInfoRow {
                    width: parent.width
                    label: I18n.tr("布局")
                    value: wide ? I18n.tr("横屏（宽窗口，内容铺满 + 多栏）") : I18n.tr("紧凑（窄窗口，单栏）")
                }

                SettingsInfoRow {
                    width: parent.width
                    label: I18n.tr("屏幕")
                    value: page.screenText()
                }
            }

            // ============================================================ 应用字体
            SettingsSection {
                id: secAppFonts
                width: parent.width - 24
                x: 12
                title: I18n.tr("应用字体")
                description: I18n.tr("界面与歌词的字体族（仅本次会话，全局应用待接线）")
                icon: "text_fields"

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("界面字体")
                }
                SegmentedOptions {
                    width: parent.width
                    options: fontPrefs.fontOptions
                    value: fontPrefs.appFontFamily
                    onSelected: function(value) {
                        fontPrefs.setAppFont(value)
                        page.frontendKeyWrite("appFontFamily", value)
                    }
                }
                Text {
                    width: parent.width
                    text: I18n.tr("预览：") + I18n.tr("NeriPlayer 让音乐继续 ")
                          + fontPrefs.fontLabel(fontPrefs.appFontFamily)
                    color: Theme.onSurfaceVariantColor
                    font.family: fontPrefs.appFontFamily !== "" ? fontPrefs.appFontFamily : Theme.fontFamily
                    font.pixelSize: 15
                    wrapMode: Text.Wrap
                }

                Item { width: 1; height: 10 }

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("歌词字体")
                }
                SegmentedOptions {
                    width: parent.width
                    options: fontPrefs.fontOptions
                    value: fontPrefs.lyricFontFamily
                    onSelected: function(value) {
                        fontPrefs.setLyricFont(value)
                        page.frontendKeyWrite("lyricFontFamily", value)
                    }
                }
                Text {
                    width: parent.width
                    text: I18n.tr("预览：") + I18n.tr("歌词字体示例 ♪ 让旋律与文字一起流动")
                    color: Theme.onSurfaceVariantColor
                    font.family: fontPrefs.lyricFontFamily !== "" ? fontPrefs.lyricFontFamily : Theme.fontFamily
                    font.pixelSize: 17
                    wrapMode: Text.Wrap
                }

                Item { width: 1; height: 6 }

                SettingsNote {
                    width: parent.width
                    text: I18n.tr("字体选择会立即写入设置并在此处预览；后端暂未提供字体字段，重启后需重新选择，全局应用待后续接线。")
                }
            }

            // ============================================================ 程序背景
            SettingsSection {
                id: secAppBackground
                width: parent.width - 24
                x: 12
                title: I18n.tr("程序背景")
                description: I18n.tr("自定义图片、模糊与暗化（仅本次会话，全局应用待接线）")
                icon: "image"

                SettingsRow {
                    width: parent.width
                    title: I18n.tr("背景图片")
                    description: fontPrefs.appBackgroundImage !== ""
                                 ? fontPrefs.appBackgroundImage
                                 : I18n.tr("未选择（使用主题默认背景）")
                    SettingsButton {
                        text: I18n.tr("选择图片")
                        icon: "image"
                        onClicked: page.chooseBackgroundImage()
                    }
                    SettingsButton {
                        text: I18n.tr("清除")
                        enabled: fontPrefs.appBackgroundImage !== ""
                        onClicked: {
                            fontPrefs.clearBackground()
                            page.frontendKeyWrite("appBackgroundImage", "")
                            page.frontendKeyWrite("appBackgroundBlur", 0)
                            page.frontendKeyWrite("appBackgroundDim", 0)
                        }
                    }
                }

                Item { width: 1; height: 8 }

                // 背景预览：选中图片时显示，模糊/暗化只作为视觉近似（主窗口渲染待接线）。
                Rectangle {
                    width: parent.width
                    height: 120
                    radius: Theme.radiusMd
                    color: Theme.surfaceContainerHighest
                    clip: true
                    Image {
                        anchors.fill: parent
                        source: fontPrefs.appBackgroundImage !== "" ? "file://" + fontPrefs.appBackgroundImage : ""
                        fillMode: Image.PreserveAspectCrop
                        opacity: 1 - fontPrefs.appBackgroundDim
                        visible: fontPrefs.appBackgroundImage !== ""
                    }
                    NeriIcon {
                        anchors.centerIn: parent
                        name: "image"
                        iconSize: 34
                        color: Theme.onSurfaceVariantColor
                        visible: fontPrefs.appBackgroundImage === ""
                    }
                }

                Item { width: 1; height: 8 }

                SliderRow {
                    width: parent.width
                    label: I18n.tr("背景模糊 ") + Math.round(fontPrefs.appBackgroundBlur)
                    from: 0
                    to: 20
                    decimals: 0
                    externalValue: fontPrefs.appBackgroundBlur
                    onCommitted: function(value) {
                        page.frontendKeyWrite("appBackgroundBlur", Math.round(value))
                    }
                }

                SliderRow {
                    width: parent.width
                    label: I18n.tr("背景暗化 ") + Math.round(fontPrefs.appBackgroundDim * 100) + "%"
                    from: 0
                    to: 1
                    decimals: 2
                    externalValue: fontPrefs.appBackgroundDim
                    onCommitted: function(value) {
                        page.frontendKeyWrite("appBackgroundDim", value)
                    }
                }

                SettingsNote {
                    width: parent.width
                    text: I18n.tr("图片路径、模糊与暗化会写入设置并在此预览；后端无对应字段且主窗口背景渲染尚未接线，全局效果待后续实现。")
                }
            }

            // ============================================================ 播放页
            SettingsSection {
                id: secPlayerDesign
                width: parent.width - 24
                x: 12
                title: I18n.tr("播放页")
                description: I18n.tr("封面、控制条、歌曲信息与歌词的显示方式")
                icon: "fullscreen"

                // 用 Loader 加载播放页外观组件；文件缺失时退化为空，避免设置页整体加载失败
                Loader {
                    width: parent.width
                    source: Qt.resolvedUrl("../components/settings/PlayerDesignSettings.qml")
                    active: true
                    visible: status === Loader.Ready
                    height: status === Loader.Ready && item ? item.implicitHeight : 0
                }

                Item {
                    width: 1
                    height: 12
                }

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("歌曲信息与歌词")
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("显示歌词翻译")
                    description: I18n.tr("在线歌词包含翻译时同时显示")
                    checked: page.sb("showLyricTranslation", true)
                    onToggled: function(checked) { page.setSetting("showLyricTranslation", checked) }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("封面页显示歌词")
                    description: I18n.tr("在播放页封面下方显示逐行歌词")
                    checked: page.sb("coverShowsLyrics", true)
                    onToggled: function(checked) { page.setSetting("coverShowsLyrics", checked) }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("显示“正在播放”标题")
                    description: I18n.tr("控制播放页顶部是否显示标题")
                    checked: page.sb("showNowPlayingTitle", true)
                    onToggled: function(checked) { page.setSetting("showNowPlayingTitle", checked) }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("滚动显示长歌曲名")
                    description: I18n.tr("标题超出宽度时平滑滚动")
                    checked: page.sb("songTitleMarquee", true)
                    onToggled: function(checked) { page.setSetting("songTitleMarquee", checked) }
                }

                Item {
                    width: 1
                    height: 4
                }

                SliderRow {
                    width: parent.width
                    label: I18n.tr("歌词字号 ") + Number(page.sv("lyricsFontScale", 1.0)).toFixed(2) + "x"
                    from: 0.7
                    to: 1.8
                    decimals: 2
                    externalValue: Number(page.sv("lyricsFontScale", 1.0))
                    onCommitted: function(value) { page.setSetting("lyricsFontScale", value) }
                }

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("歌词对齐")
                }
                SegmentedOptions {
                    width: parent.width
                    options: [
                        { value: "LEFT", label: I18n.tr("左对齐") },
                        { value: "CENTER", label: I18n.tr("居中") },
                        { value: "RIGHT", label: I18n.tr("右对齐") }
                    ]
                    value: page.sv("lyricsAlignment", "LEFT")
                    onSelected: function(value) { page.setSetting("lyricsAlignment", value) }
                }

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("歌词高亮方式")
                }
                SegmentedOptions {
                    width: parent.width
                    options: [
                        { value: "LINE", label: I18n.tr("整句亮起") },
                        { value: "WORD", label: I18n.tr("逐字亮起") }
                    ]
                    value: page.sv("lyricsHighlight", "LINE")
                    onSelected: function(value) { page.setSetting("lyricsHighlight", value) }
                }

                SliderRow {
                    width: parent.width
                    label: I18n.tr("高亮动画时长 ") + Math.round(page.sv("lyricsHighlightDuration", 220)) + "ms"
                    from: 0
                    to: 800
                    decimals: 0
                    externalValue: Number(page.sv("lyricsHighlightDuration", 220))
                    onCommitted: function(value) { page.setSetting("lyricsHighlightDuration", Math.round(value)) }
                }

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("歌词滚动吸附动画")
                }
                SegmentedOptions {
                    width: parent.width
                    options: [
                        { value: "CUBIC", label: I18n.tr("三次曲线") },
                        { value: "QUAD", label: I18n.tr("二次曲线") },
                        { value: "SINE", label: I18n.tr("正弦") },
                        { value: "LINEAR", label: I18n.tr("线性") }
                    ]
                    value: page.sv("lyricsScrollEasing", "CUBIC")
                    onSelected: function(value) { page.setSetting("lyricsScrollEasing", value) }
                }
                SliderRow {
                    width: parent.width
                    label: I18n.tr("滚动动画时长 ") + Math.round(page.sv("lyricsScrollDuration", 420)) + "ms"
                    from: 80
                    to: 1200
                    decimals: 0
                    externalValue: Number(page.sv("lyricsScrollDuration", 420))
                    onCommitted: function(value) { page.setSetting("lyricsScrollDuration", Math.round(value)) }
                }
            }

                }
            }
            Item {
                id: catPlayback
                width: parent.width
                height: visible ? innerPlayback.height + 12 : 0
                visible: page.currentCategory === "playback"

                Column {
                    id: innerPlayback
                    width: parent.width
                    spacing: 12

            // ============================================================ 播放行为
            SettingsSection {
                id: secPlayback
                width: parent.width - 24
                x: 12
                title: I18n.tr("播放行为")
                description: I18n.tr("响度增强与睡眠定时默认时长")
                icon: "play_circle"

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("响度增强")
                    description: I18n.tr("对整体音量做动态归一化，安静段落更清楚；与 DSP 动态分区相互独立")
                    checked: Player.loudness
                    onToggled: function(checked) { Player.setLoudness(checked) }
                }

                Item { width: 1; height: 6 }

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("睡眠定时默认时长")
                }

                SegmentedOptions {
                    width: parent.width
                    options: [
                        { value: 15, label: I18n.tr("15 分钟") },
                        { value: 30, label: I18n.tr("30 分钟") },
                        { value: 60, label: I18n.tr("60 分钟") },
                        { value: 90, label: I18n.tr("90 分钟") }
                    ]
                    value: Number(page.sv("sleepTimerMinutes", 30))
                    onSelected: function(value) { page.setSetting("sleepTimerMinutes", Number(value)) }
                }

                SettingsNote {
                    width: parent.width
                    text: I18n.tr("播放速度与音调在「音效与倍速」分类里调节（倍速标签页），会同时作用于当前播放。")
                }
            }

            // ============================================================ 淡入淡出
            SettingsSection {
                id: secFade
                width: parent.width - 24
                x: 12
                title: I18n.tr("淡入淡出")
                description: I18n.tr("播放开始/结束的渐变与切歌交叉淡入（需后端 player.setFadeOptions）")
                icon: "graphic_eq"

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("启用淡入淡出")
                    description: I18n.tr("播放与暂停时按下方时长渐变音量")
                    checked: page.sb("fadeEnabled", true)
                    onToggled: function(checked) {
                        page.setSetting("fadeEnabled", checked)
                        page.applyFadeOptions({ fadeEnabled: checked })
                    }
                }

                Item { width: 1; height: 6 }

                SliderRow {
                    width: parent.width
                    enabled: page.sb("fadeEnabled", true)
                    label: I18n.tr("淡入时长 ") + Math.round(page.sv("fadeInMs", 300)) + "ms"
                    from: 100
                    to: 2000
                    decimals: 0
                    externalValue: Number(page.sv("fadeInMs", 300))
                    formatValue: function(value) { return Math.round(value) + "ms" }
                    onCommitted: function(value) {
                        page.setSetting("fadeInMs", Math.round(value))
                        page.applyFadeOptions({ fadeInMs: Math.round(value) })
                    }
                }

                SliderRow {
                    width: parent.width
                    enabled: page.sb("fadeEnabled", true)
                    label: I18n.tr("淡出时长 ") + Math.round(page.sv("fadeOutMs", 300)) + "ms"
                    from: 100
                    to: 2000
                    decimals: 0
                    externalValue: Number(page.sv("fadeOutMs", 300))
                    formatValue: function(value) { return Math.round(value) + "ms" }
                    onCommitted: function(value) {
                        page.setSetting("fadeOutMs", Math.round(value))
                        page.applyFadeOptions({ fadeOutMs: Math.round(value) })
                    }
                }

                Item { width: 1; height: 6 }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("交叉淡入")
                    description: I18n.tr("切歌时前后歌曲音量交叉过渡")
                    checked: page.sb("crossfadeEnabled", false)
                    onToggled: function(checked) {
                        page.setSetting("crossfadeEnabled", checked)
                        page.applyFadeOptions({ crossfadeEnabled: checked })
                    }
                }

                SliderRow {
                    width: parent.width
                    enabled: page.sb("crossfadeEnabled", false)
                    label: I18n.tr("交叉淡入时长 ") + Math.round(page.sv("crossfadeMs", 2000)) + "ms"
                    from: 500
                    to: 8000
                    decimals: 0
                    externalValue: Number(page.sv("crossfadeMs", 2000))
                    formatValue: function(value) { return Math.round(value) + "ms" }
                    onCommitted: function(value) {
                        page.setSetting("crossfadeMs", Math.round(value))
                        page.applyFadeOptions({ crossfadeMs: Math.round(value) })
                    }
                }

                SettingsNote {
                    width: parent.width
                    text: I18n.tr("时长设置会保存到设置并尝试同步给播放器；若后端尚未构建该命令会提示错误，设置本身仍保留。")
                }
            }

            // ============================================================ B站视频跳过区间
            Item {
                id: secBiliSkip
                width: parent.width - 24
                x: 12
                height: biliSkipCard.height

                SettingsSection {
                    id: biliSkipCard
                    width: parent.width
                    title: I18n.tr("视频跳过区间")
                    description: I18n.tr("B站音频按视频+分P记录片头等区间，播放时自动跳过")
                    icon: "fast_forward"

                    SettingsRow {
                        width: parent.width
                        title: I18n.tr("管理跳过区间")
                        description: I18n.tr("规则按「视频 + 分P」保存在本机")
                        SettingsButton {
                            text: I18n.tr("打开")
                            onClicked: biliSkipDialog.openFor(
                                Player.song ? String(Player.song.remoteId || "") : "",
                                Player.song ? String(Player.song.extraId || "") : "",
                                Player.song ? String(Player.song.title || "") : "")
                        }
                    }
                }

                // 对话框是独立 Popup，不占布局高度。
                Loader {
                    id: biliSkipDialogLoader
                    source: Qt.resolvedUrl("../components/settings/BiliVideoSkipDialog.qml")
                    active: true
                    onStatusChanged: if (status === Loader.Error)
                                         console.warn("[settings] BiliVideoSkipDialog.qml 尚未就绪")
                }
                property var biliSkipDialog: biliSkipDialogLoader.item
            }

            // ============================================================ 在线播放音质
            SettingsSection {
                id: secQuality
                width: parent.width - 24
                x: 12
                title: I18n.tr("在线播放音质")
                description: I18n.tr("在线歌曲解析时使用的默认码率（与下载音质独立）")
                icon: "high_quality"

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("默认播放音质")
                }

                SegmentedOptions {
                    width: parent.width
                    options: page.qualityOptions
                    value: page.sv("qualityPreference", "exhigh")
                    onSelected: function(value) {
                        page.setSetting("qualityPreference", value)
                    }
                }

                Item {
                    width: 1
                    height: 6
                }

                SettingsNote {
                    width: parent.width
                    text: I18n.tr("后端按 standard / higher / exhigh / lossless / hires 映射解析码率；实际可用档位取决于音源与登录状态，无损与 Hi-Res 档位在部分接口上会回落到较高码率。")
                }
            }

                }
            }
            Item {
                id: catAudioEffects
                width: parent.width
                height: visible ? innerAudioEffects.height + 12 : 0
                visible: page.currentCategory === "audioEffects"

                Column {
                    id: innerAudioEffects
                    width: parent.width
                    spacing: 12

            // ============================================================ 音效与倍速（DSP 面板）
            // 面板按标签页组织（预设/均衡器/音色/空间/动态/扬声器/倍速/高级），
            // 内容自成一屏，与「淡入淡出、音质、跳过区间」这类播放行为没有共同语境，
            // 所以单独成分类（上游安卓端同样是 AudioEffects 与 Playback 两个设置页）。
            SettingsSection {
                id: secDsp
                width: parent.width - 24
                x: 12
                title: I18n.tr("音效与倍速")
                description: I18n.tr("预设 · 均衡器 · 音色 · 空间 · 动态 · 扬声器 · 倍速")
                icon: "graphic_eq"
                expanded: true

                // 面板自包含内容；文件缺失时静默退化，不阻塞设置页。
                Loader {
                    id: dspLoader
                    width: parent.width
                    source: Qt.resolvedUrl("../components/settings/DspSettingsPanel.qml")
                    active: true
                    visible: status === Loader.Ready
                    onStatusChanged: if (status === Loader.Error)
                                         console.warn("[settings] DspSettingsPanel.qml 尚未就绪")
                    height: status === Loader.Ready && item ? item.implicitHeight : 0
                }
            }

                }
            }
            Item {
                id: catLyrics
                width: parent.width
                height: visible ? innerLyrics.height + 12 : 0
                visible: page.currentCategory === "lyrics"

                Column {
                    id: innerLyrics
                    width: parent.width
                    spacing: 12

            // ============================================================ 悬浮歌词
            SettingsSection {
                id: secFloatingLyrics
                width: parent.width - 24
                x: 12
                title: I18n.tr("悬浮歌词")
                description: I18n.tr("桌面悬浮歌词的样式与配色")
                icon: "lyrics"

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("启用悬浮歌词")
                    description: I18n.tr("在桌面上显示轻量悬浮歌词（快捷键 Ctrl+L）")
                    checked: page.sb("floatingLyricsEnabled", false)
                    onToggled: function(checked) {
                        page.setSetting("floatingLyricsEnabled", checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("应用内隐藏")
                    description: I18n.tr("主窗口获得焦点时隐藏悬浮歌词，避免遮挡页面")
                    checked: page.sb("floatingLyricsHideInApp", false)
                    onToggled: function(checked) {
                        page.setSetting("floatingLyricsHideInApp", checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("锁定位置")
                    description: I18n.tr("锁定后无法拖动悬浮歌词")
                    checked: page.sb("floatingLyricsLocked", false)
                    onToggled: function(checked) {
                        page.setSetting("floatingLyricsLocked", checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("显示翻译")
                    description: I18n.tr("有翻译歌词时一起显示在第二行")
                    checked: page.sb("floatingLyricsShowTranslation", true)
                    onToggled: function(checked) {
                        page.setSetting("floatingLyricsShowTranslation", checked)
                    }
                }

                Item {
                    width: 1
                    height: 6
                }

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("窗口模式")
                }

                SegmentedOptions {
                    width: parent.width
                    options: page.floatingWindowModeOptions
                    value: page.sv("floatingLyricsWindowMode", "LAYER")
                    onSelected: function(value) {
                        page.setSetting("floatingLyricsWindowMode", value)
                    }
                }

                SettingsNote {
                    width: parent.width
                    text: I18n.tr("置顶覆盖层由桌面合成器直接托管，始终浮在最上层；普通窗口是常规窗口，可跨屏拖动但会进任务栏与窗口切换器。")
                }

                Item {
                    width: 1
                    height: 8
                }

                SliderRow {
                    width: parent.width
                    label: I18n.tr("字号")
                    externalValue: page.sv("floatingLyricsFontSize", 30)
                    from: 14
                    to: 72
                    decimals: 0
                    formatValue: function(value) {
                        return Math.round(value) + " px"
                    }
                    onCommitted: function(value) {
                        page.setSetting("floatingLyricsFontSize", value)
                    }
                }

                Item {
                    width: 1
                    height: 6
                }

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("渲染方式")
                }

                SegmentedOptions {
                    width: parent.width
                    options: page.renderStyleOptions
                    value: page.sv("floatingLyricsRenderStyle", "SHADOW")
                    onSelected: function(value) {
                        page.setSetting("floatingLyricsRenderStyle", value)
                    }
                }

                Item {
                    width: 1
                    height: 10
                }

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("歌词颜色")
                }

                Row {
                    width: parent.width
                    spacing: Theme.spacingSm

                    Repeater {
                        model: page.floatingLyricColors

                        delegate: ColorSwatch {
                            required property var modelData
                            swatchSize: 28
                            swatchColor: modelData.color
                            selected: page.sv("floatingLyricsTextColor", "WHITE") === modelData.name
                            onClicked: page.setSetting("floatingLyricsTextColor", modelData.name)
                        }
                    }
                }

                Item {
                    width: 1
                    height: 8
                }

                SliderRow {
                    width: parent.width
                    label: I18n.tr("主歌词不透明度")
                    externalValue: page.sv("floatingLyricsLyricAlpha", 1.0)
                    from: 0.1
                    to: 1.0
                    formatValue: function(value) {
                        return Math.round(value * 100) + "%"
                    }
                    onCommitted: function(value) {
                        page.setSetting("floatingLyricsLyricAlpha", value)
                    }
                }

                SliderRow {
                    width: parent.width
                    label: I18n.tr("翻译不透明度")
                    externalValue: page.sv("floatingLyricsTranslationAlpha", 0.75)
                    from: 0.1
                    to: 1.0
                    formatValue: function(value) {
                        return Math.round(value * 100) + "%"
                    }
                    onCommitted: function(value) {
                        page.setSetting("floatingLyricsTranslationAlpha", value)
                    }
                }

                Item {
                    width: 1
                    height: 8
                }

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("背景颜色")
                }

                Row {
                    width: parent.width
                    spacing: Theme.spacingSm

                    Repeater {
                        model: page.floatingLyricColors

                        delegate: ColorSwatch {
                            required property var modelData
                            swatchSize: 28
                            swatchColor: modelData.color
                            selected: page.sv("floatingLyricsBackgroundColor", "BLACK") === modelData.name
                            onClicked: page.setSetting("floatingLyricsBackgroundColor", modelData.name)
                        }
                    }
                }

                Item {
                    width: 1
                    height: 8
                }

                SliderRow {
                    width: parent.width
                    label: I18n.tr("背景不透明度")
                    externalValue: page.sv("floatingLyricsBackgroundAlpha", 0.35)
                    from: 0
                    to: 1
                    formatValue: function(value) {
                        return Math.round(value * 100) + "%"
                    }
                    onCommitted: function(value) {
                        page.setSetting("floatingLyricsBackgroundAlpha", value)
                    }
                }

                Item {
                    width: 1
                    height: 6
                }

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("对齐方式")
                }

                SegmentedOptions {
                    width: parent.width
                    options: page.alignmentOptions
                    value: page.sv("floatingLyricsAlignment", "CENTER")
                    onSelected: function(value) {
                        page.setSetting("floatingLyricsAlignment", value)
                    }
                }

                Item {
                    width: 1
                    height: 6
                }

                SettingsNote {
                    width: parent.width
                    text: I18n.tr("悬浮歌词是独立的桌面窗口；未锁定时可直接拖动，锁定时鼠标穿透（Ctrl+Alt+L 解锁）。")
                }
            }

                }
            }
            Item {
                id: catDownloadStorage
                width: parent.width
                height: visible ? innerDownloadStorage.height + 12 : 0
                visible: page.currentCategory === "downloadStorage"

                Column {
                    id: innerDownloadStorage
                    width: parent.width
                    spacing: 12

            // ============================================================ 媒体库
            SettingsSection {
                id: secLibrary
                width: parent.width - 24
                x: 12
                title: I18n.tr("媒体库")
                description: I18n.tr("本地音乐文件夹与扫描")
                icon: "library_music"

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("音乐文件夹")
                }

                FolderList {
                    width: parent.width
                    folders: page.sv("musicFolders", [])
                    onAddRequested: page.chooseMusicFolder()
                    onRemoveRequested: function(path) {
                        page.removeMusicFolder(path)
                    }
                    onRescanRequested: page.rescanLibrary()
                }

                Item {
                    width: 1
                    height: 8
                }

                Text {
                    width: parent.width
                    text: page.libraryStatusText
                    color: page.scanError !== "" ? Theme.error : Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: 12
                    font.weight: Font.Medium
                    wrapMode: Text.Wrap
                }

            }

            // ============================================================ 下载设置
            SettingsSection {
                id: secDownloads
                width: parent.width - 24
                x: 12
                title: I18n.tr("下载设置")
                description: I18n.tr("下载目录、音质与并发数")
                icon: "download"

                SettingsRow {
                    width: parent.width
                    title: I18n.tr("下载目录")
                    description: {
                        const directory = page.sv("downloadDirectory", "")
                        return directory !== "" ? directory : I18n.tr("默认：~/Music/NeriPlayer")
                    }

                    SettingsButton {
                        text: I18n.tr("选择目录")
                        onClicked: page.chooseDownloadFolder()
                    }

                    SettingsButton {
                        text: I18n.tr("恢复默认")
                        onClicked: page.setSetting("downloadDirectory", "")
                    }
                }

                Item {
                    width: 1
                    height: 10
                }

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("下载音质")
                }

                SegmentedOptions {
                    width: parent.width
                    options: page.qualityOptions
                    value: page.sv("downloadQuality", "exhigh")
                    onSelected: function(value) {
                        page.setSetting("downloadQuality", value)
                    }
                }

                Item {
                    width: 1
                    height: 12
                }

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("并发下载")
                }

                SegmentedOptions {
                    width: parent.width
                    options: page.concurrencyOptions
                    value: page.sv("downloadConcurrency", 2)
                    onSelected: function(value) {
                        page.setSetting("downloadConcurrency", value)
                    }
                }

                Item {
                    width: 1
                    height: 6
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("下载完成时通知")
                    description: I18n.tr("全部任务完成后发送系统通知")
                    checked: page.sb("downloadNotifyOnComplete", true)
                    onToggled: function(checked) {
                        page.setSetting("downloadNotifyOnComplete", checked)
                    }
                }

                Item {
                    width: 1
                    height: 4
                }

                SettingsButton {
                    text: I18n.tr("打开下载管理")
                    icon: "download"
                    onClicked: Nav.openDownloadsPanel()
                }
            }

            // ============================================================ 数据与缓存
            SettingsSection {
                id: secDataCache
                width: parent.width - 24
                x: 12
                title: I18n.tr("数据与缓存")
                description: I18n.tr("清理封面缓存、下载记录与本地统计")
                icon: "storage"

                // 存储占用详情：按目录统计磁盘占用，清理入口在对话框内（只清可再生缓存）。
                SettingsRow {
                    width: parent.width
                    title: I18n.tr("存储占用详情")
                    description: I18n.tr("统计各目录的实际磁盘占用")
                    SettingsButton {
                        text: I18n.tr("查看")
                        onClicked: if (storageDialogLoader.item) storageDialogLoader.item.open()
                    }
                }

                SettingsRow {
                    width: parent.width
                    title: I18n.tr("封面缓存")
                    description: I18n.tr("在线歌曲封面在本机的缩略图，可安全清理")
                    SettingsButton {
                        text: page.clearingCache ? I18n.tr("清理中…") : I18n.tr("清除缓存")
                        enabled: !page.clearingCache
                        onClicked: page.clearArtworkCache()
                    }
                }

                SettingsRow {
                    width: parent.width
                    title: I18n.tr("已完成的下载记录")
                    description: I18n.tr("只清空下载列表中的已完成任务，不删除已下载的文件")
                    SettingsButton {
                        text: I18n.tr("清理记录")
                        onClicked: page.clearFinishedDownloads()
                    }
                }

                SettingsRow {
                    width: parent.width
                    title: I18n.tr("播放历史")
                    description: I18n.tr("最近播放记录与最常播放列表")
                    SettingsButton {
                        text: I18n.tr("清除历史")
                        onClicked: page.clearHistory()
                    }
                }

                SettingsRow {
                    width: parent.width
                    title: I18n.tr("播放统计")
                    description: I18n.tr("按天 / 周 / 月累计的播放次数统计")
                    SettingsButton {
                        text: I18n.tr("清除统计")
                        onClicked: page.clearStats()
                    }
                }

                Item {
                    width: 1
                    height: 4
                }

                SettingsNote {
                    width: parent.width
                    text: I18n.tr("以上清理操作不可撤销；下载文件本体请在「下载管理」中删除。")
                }
            }

                }
            }
            Item {
                id: catGeneral
                width: parent.width
                height: visible ? innerGeneral.height + 12 : 0
                visible: page.currentCategory === "general"

                Column {
                    id: innerGeneral
                    width: parent.width
                    spacing: 12

                // 存储管理对话框（独立 Popup，不占布局高度）
                Loader {
                    id: storageDialogLoader
                    source: Qt.resolvedUrl("../components/settings/StorageManagementDialog.qml")
                    active: true
                    onStatusChanged: if (status === Loader.Error)
                                         console.warn("[settings] StorageManagementDialog.qml 尚未就绪")
                }


            // ============================================================ 首页栏目
            SettingsSection {
                id: secHomeCards
                width: parent.width - 24
                x: 12
                title: I18n.tr("首页栏目")
                description: I18n.tr("选择首页展示哪些推荐栏目（未登录 / 音源禁用时对应栏目不会出现）")
                icon: "dashboard"

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("首页使用平铺网格")
                    description: I18n.tr("开启后首页推荐栏目以平铺网格展示，关闭时为纵向列表")
                    checked: page.sb("homeGridMode", false)
                    onToggled: function(checked) { page.setSetting("homeGridMode", checked) }
                }

                Item {
                    width: 1
                    height: 4
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("继续播放")
                    description: I18n.tr("最近播放的歌曲")
                    checked: page.homeCardEnabled("continuePlaying", true)
                    onToggled: function(checked) { page.setHomeCard("continuePlaying", checked) }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("猜你喜欢")
                    description: I18n.tr("根据收听口味生成的歌曲推荐")
                    checked: page.homeCardEnabled("guessYouLike", true)
                    onToggled: function(checked) { page.setHomeCard("guessYouLike", checked) }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("每日推荐")
                    description: I18n.tr("每天更新的 30 首推荐（需登录网易云）")
                    checked: page.homeCardEnabled("dailyRecommend", true)
                    onToggled: function(checked) { page.setHomeCard("dailyRecommend", checked) }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("私人 FM")
                    description: I18n.tr("根据口味连续推荐的歌曲（需登录网易云）")
                    checked: page.homeCardEnabled("privateFm", true)
                    onToggled: function(checked) { page.setHomeCard("privateFm", checked) }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("B站音乐")
                    description: I18n.tr("哔哩哔哩音乐榜与音乐区热门视频")
                    checked: page.homeCardEnabled("biliMusic", true)
                    onToggled: function(checked) { page.setHomeCard("biliMusic", checked) }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("排行榜")
                    description: I18n.tr("热歌榜、飙升榜与新歌榜")
                    checked: page.homeCardEnabled("ranks", true)
                    onToggled: function(checked) { page.setHomeCard("ranks", checked) }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("推荐歌单")
                    description: I18n.tr("个性化推荐、精品与热门歌单")
                    checked: page.homeCardEnabled("playlists", true)
                    onToggled: function(checked) { page.setHomeCard("playlists", checked) }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("推荐新歌")
                    description: I18n.tr("最近发布的新歌推荐")
                    checked: page.homeCardEnabled("newSongs", true)
                    onToggled: function(checked) { page.setHomeCard("newSongs", checked) }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("雷达歌单")
                    description: I18n.tr("时光 / 宝藏 / 新歌 / 乐迷 / 神秘雷达")
                    checked: page.homeCardEnabled("radar", true)
                    onToggled: function(checked) { page.setHomeCard("radar", checked) }
                }
            }

            // ============================================================ 后台与系统控制
            SettingsSection {
                id: secBackground
                width: parent.width - 24
                x: 12
                title: I18n.tr("后台与系统控制")
                description: I18n.tr("托盘、通知与系统媒体控制")
                icon: "desktop_windows"

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("关闭窗口时最小化到托盘")
                    description: I18n.tr("关闭窗口只是隐藏，音乐继续播放；点击托盘图标可打开控制面板")
                    checked: page.sb("closeToTray", true)
                    onToggled: function(checked) {
                        page.setSetting("closeToTray", checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("最小化时隐藏到托盘")
                    description: I18n.tr("把窗口最小化时收进托盘，继续后台播放")
                    checked: page.sb("minimizeToTray", true)
                    onToggled: function(checked) {
                        page.setSetting("minimizeToTray", checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("控制面板常驻")
                    description: I18n.tr("托盘控制面板失去焦点时不自动关闭，方便连续操作")
                    checked: page.sb("trayPanelPersistent", false)
                    onToggled: function(checked) {
                        page.setSetting("trayPanelPersistent", checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("切歌时发送系统通知")
                    description: I18n.tr("仅当窗口隐藏 / 最小化时提示，避免打扰")
                    checked: page.sb("notifyOnSongChange", true)
                    onToggled: function(checked) {
                        page.setSetting("notifyOnSongChange", checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("启用系统媒体控制（MPRIS）")
                    description: I18n.tr("注册 org.mpris.MediaPlayer2.neriplayer，可用媒体键与桌面媒体组件控制")
                    checked: page.sb("mprisEnabled", true)
                    onToggled: function(checked) {
                        page.setSetting("mprisEnabled", checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("启动时恢复上次队列")
                    description: I18n.tr("打开应用后继续显示上次的播放队列与进度")
                    checked: page.sb("resumeLastQueue", true)
                    onToggled: function(checked) {
                        page.setSetting("resumeLastQueue", checked)
                    }
                }

                Item {
                    width: 1
                    height: 4
                }

                SettingsNote {
                    width: parent.width
                    text: I18n.tr("托盘与 MPRIS 需要桌面环境提供系统托盘 / D-Bus 会话总线；无托盘的极简桌面下关闭窗口将直接退出。")
                }
            }

            // ============================================================ 语言
            SettingsSection {
                id: secLanguage
                width: parent.width - 24
                x: 12
                title: I18n.tr("语言")
                description: I18n.tr("选择应用界面使用的语言，切换后立即生效")
                icon: "language"
                expanded: true

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("界面语言")
                }

                SegmentedOptions {
                    width: parent.width
                    options: page.languageOptions
                    value: I18n.language
                    onSelected: function(value) { I18n.setLanguage(value) }
                }

                Item {
                    width: 1
                    height: 8
                }

                SettingsNote {
                    width: parent.width
                    text: I18n.tr("切换语言后立即刷新界面并保存到本地设置；部分翻译缺失处会回退显示中文原文。")
                }
            }

                }
            }

            // ============================================================ 快捷键（独立分类：条目多、内容自成一屏）
            Item {
                id: catShortcuts
                width: parent.width
                height: visible ? innerShortcuts.height + 12 : 0
                visible: page.currentCategory === "shortcuts"

                Column {
                    id: innerShortcuts
                    width: parent.width
                    spacing: 12

            // ============================================================ 快捷键
            SettingsSection {
                id: shortcutSection
                width: parent.width - 24
                x: 12
                title: I18n.tr("快捷键")
                description: I18n.tr("应用内与全局快捷键")
                icon: "keyboard"
                expanded: true

                // 动作表、录制与保存都在 ShortcutEditor 内部完成（读取 Settings.values.shortcuts）。
                ShortcutEditor {
                    width: parent.width
                }
            }

                }
            }

            // ============================================================ 备份与同步（面板由另一 agent 提供）
            Item {
                id: catBackupSync
                width: parent.width
                height: visible ? innerBackupSync.height + 12 : 0
                visible: page.currentCategory === "backupSync"

                Column {
                    id: innerBackupSync
                    width: parent.width
                    spacing: 12

                    // 设置快照导入导出（本机备份/迁移，不含凭据）
                    SettingsSection {
                        width: parent.width - 24
                        x: 12
                        title: I18n.tr("备份与恢复设置")
                        description: I18n.tr("把当前偏好存成 JSON 文件，或从备份文件恢复")
                        icon: "save"

                        SettingsRow {
                            width: parent.width
                            title: I18n.tr("导出当前设置")
                            description: I18n.tr("保存为可读的 JSON 文件，便于备份或迁移到另一台机器")
                            SettingsButton {
                                text: I18n.tr("导出…")
                                onClicked: if (configDialogLoader.item) configDialogLoader.item.openDialog()
                            }
                        }

                        SettingsRow {
                            width: parent.width
                            title: I18n.tr("导入设置快照")
                            description: I18n.tr("导入会覆盖当前全部偏好，导入前会先校验并显示差异")
                            SettingsButton {
                                text: I18n.tr("导入…")
                                onClicked: if (configDialogLoader.item) configDialogLoader.item.openDialog()
                            }
                        }

                        SettingsNote {
                            width: parent.width
                            text: I18n.tr("快照只包含偏好设置，不含 Cookie、登录状态、同步 Token，也不含歌单与播放记录。")
                        }
                    }

                    // 调试：后端最近日志（已脱敏）
                    SettingsSection {
                        width: parent.width - 24
                        x: 12
                        title: I18n.tr("调试")
                        description: I18n.tr("后端最近日志（已脱敏）")
                        icon: "bug_report"

                        SettingsRow {
                            width: parent.width
                            title: I18n.tr("运行日志")
                            description: I18n.tr("查看后端最近日志；敏感信息已脱敏")
                            SettingsButton {
                                text: I18n.tr("查看")
                                onClicked: if (debugViewerLoader.item) debugViewerLoader.item.openViewer()
                            }
                        }
                    }

                    // SyncPanel 自包含标题与内容；外层只提供滚动定位用的 id，不再套 SettingsSection。
                    Item {
                        id: secBackupSync
                        width: parent.width - 24
                        x: 12
                        height: backupLoader.height

                        // SyncPanel 由同步 agent 提供；文件缺失时静默退化，不阻塞设置页。
                        Loader {
                            id: backupLoader
                            width: parent.width
                            source: Qt.resolvedUrl("../components/settings/SyncPanel.qml")
                            active: true
                            visible: status === Loader.Ready
                            onStatusChanged: if (status === Loader.Error)
                                                 console.warn("[settings] SyncPanel.qml 尚未就绪")
                            height: status === Loader.Ready && item ? item.implicitHeight : 0
                        }
                    }
                }

                // 两个对话框都是独立 Popup，不占布局高度。
                Loader {
                    id: configDialogLoader
                    source: Qt.resolvedUrl("../components/settings/ConfigTransferDialog.qml")
                    active: true
                    onStatusChanged: if (status === Loader.Error)
                                         console.warn("[settings] ConfigTransferDialog.qml 尚未就绪")
                }
                Loader {
                    id: debugViewerLoader
                    source: Qt.resolvedUrl("../components/settings/DebugLogViewer.qml")
                    active: true
                    onStatusChanged: if (status === Loader.Error)
                                         console.warn("[settings] DebugLogViewer.qml 尚未就绪")
                }
            }

            Item {
                id: catAbout
                width: parent.width
                height: visible ? innerAbout.height + 12 : 0
                visible: page.currentCategory === "about"

                Column {
                    id: innerAbout
                    width: parent.width
                    spacing: 12

            // ============================================================ 关于
            SettingsSection {
                id: secAbout
                width: parent.width - 24
                x: 12
                title: I18n.tr("关于")
                description: I18n.tr("版本信息与开源地址")
                icon: "info"
                // 关于只有几条信息，保持展开；其余分组默认折叠
                expanded: true

                SettingsInfoRow {
                    width: parent.width
                    label: I18n.tr("应用版本")
                    value: "NeriPlayer Desktop " + page.appVersion
                }

                SettingsInfoRow {
                    width: parent.width
                    label: I18n.tr("前端框架")
                    value: I18n.tr("Qt 6 / QML（Material 3 视觉复刻）")
                }

                SettingsInfoRow {
                    width: parent.width
                    label: I18n.tr("后端")
                    value: I18n.tr("Kotlin 无头后端 · Unix socket 行分隔 JSON")
                }

                SettingsInfoRow {
                    width: parent.width
                    label: I18n.tr("许可证")
                    value: "GNU General Public License v3.0"
                }

                SettingsInfoRow {
                    width: parent.width
                    label: I18n.tr("项目主页")
                    value: "https://github.com/cwuom/NeriPlayer"
                }

                Item {
                    width: 1
                    height: 10
                }

                Row {
                    width: parent.width
                    spacing: Theme.spacingSm

                    SettingsButton {
                        text: I18n.tr("项目主页")
                        icon: "open_in_new"
                        onClicked: Qt.openUrlExternally("https://github.com/cwuom/NeriPlayer")
                    }

                    SettingsButton {
                        text: I18n.tr("Linux 版仓库")
                        onClicked: Qt.openUrlExternally("https://github.com/YangQwQ/NeriPlayerForLinux")
                    }

                    SettingsButton {
                        text: I18n.tr("问题反馈")
                        onClicked: Qt.openUrlExternally("https://github.com/YangQwQ/NeriPlayerForLinux/issues")
                    }
                }

                Item {
                    width: 1
                    height: 4
                }

                SettingsNote {
                    width: parent.width
                    text: I18n.tr("本页面所有设置会立即写入后端 settings.json；主题相关修改会触发 material-kolor 重新计算调色板。")
                }
            }

                }
            }
            Item {
                width: 1
                height: 24
            }
        }
    }

    // 搜索结果定位：等分类切换与卡片展开完成后再把目标卡片滚到可视区。
    Timer {
        id: sectionScrollTimer
        interval: 80
        repeat: false
        property var targetSection: null
        onTriggered: {
            if (!targetSection || !flick)
                return
            const y = targetSection.mapToItem(contentColumn, 0, 0).y
            flick.contentY = Math.max(0, Math.min(y - 12, flick.contentHeight - flick.height))
        }
    }

    // 截图验证辅助：等动作表加载、布局稳定后再滚动。
    Timer {
        id: shortcutDebugScroll
        interval: 1500
        repeat: false
        onTriggered: page.scrollToDebugTarget()
    }
}
