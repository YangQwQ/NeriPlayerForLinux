import QtQuick
import "../"

// 普通歌词设置卡片内容（供设置页「歌词」分类内嵌）。
// 包含：歌词来源、显示行自由组合（音译/原文/翻译的顺序与字号比例）、实时预览、模糊与对齐高亮。
// 只放设置行本身，标题与卡片容器由外层 SettingsSection 提供。
// 所有字段通过 Settings.get / Settings.set / Settings.patch 读写，后端字段后补前会回退默认值。
Item {
    id: root

    width: parent ? parent.width : 0
    implicitHeight: column.implicitHeight

    // 行类型元信息：key 与行顺序字符串里的片段一致；label 用 I18n.tr 包裹。
    readonly property var lineTypes: [
        { key: "ORIGINAL", label: I18n.tr("原文"), example: I18n.tr("歌词示例原文 · 让旋律与文字一起流动") },
        { key: "TRANSLATION", label: I18n.tr("翻译"), example: I18n.tr("歌词示例翻译 · let the melody flow with the words") },
        { key: "PHONETIC", label: I18n.tr("音译"), example: I18n.tr("歌词示例音译 · rang xuan lv yu wen zi yi qi liu dong") }
    ]

    readonly property var sourceOptions: [
        { value: "AUTO", label: I18n.tr("自动") },
        { value: "NETEASE", label: I18n.tr("仅网易云") },
        { value: "LOCAL", label: I18n.tr("仅本地文件") }
    ]

    readonly property string lyricSource: {
        const value = Settings.get("lyricSource", "AUTO")
        return (value === "NETEASE" || value === "LOCAL") ? value : "AUTO"
    }
    readonly property bool blurEnabled: Settings.get("lyricBlurEnabled", true) !== false
    readonly property real blurAmount: Number(Settings.get("lyricBlurAmount", 1.5))
    readonly property real phoneticScale: Number(Settings.get("lyricPhoneticFontSizeScale", 0.58))
    readonly property real translationScale: Number(Settings.get("lyricTranslationFontSizeScale", 0.58))

    // 当前行顺序：默认「原文 / 翻译 / 音译」，解析未知值时回退并去重，保证恰好三种。
    property var lineOrder: root.parseOrder(Settings.get("lyricLineOrder", "ORIGINAL_TRANSLATION_PHONETIC"))
    property real baseFontSize: 20

    function parseOrder(value) {
        const valid = ["ORIGINAL", "TRANSLATION", "PHONETIC"]
        const result = []
        const raw = String(value || "").split("_")
        for (let i = 0; i < raw.length; i++) {
            if (valid.indexOf(raw[i]) >= 0 && result.indexOf(raw[i]) < 0)
                result.push(raw[i])
        }
        for (let i = 0; i < valid.length; i++) {
            if (result.indexOf(valid[i]) < 0)
                result.push(valid[i])
        }
        return result
    }

    function labelFor(key) {
        for (let i = 0; i < root.lineTypes.length; i++) {
            if (root.lineTypes[i].key === key)
                return root.lineTypes[i].label
        }
        return key
    }

    function exampleFor(key) {
        for (let i = 0; i < root.lineTypes.length; i++) {
            if (root.lineTypes[i].key === key)
                return root.lineTypes[i].example
        }
        return ""
    }

    function scaleFor(key) {
        if (key === "PHONETIC")
            return root.phoneticScale
        if (key === "TRANSLATION")
            return root.translationScale
        return 1.0
    }

    /** 行顺序或字号比例变化后，用一次 patch 写入全部相关字段。 */
    function commitLines(order, phonetic, translation) {
        const keys = order.slice()
        while (keys.length < 3)
            keys.push(["ORIGINAL", "TRANSLATION", "PHONETIC"][keys.length])
        Settings.patch({
            "lyricLineOrder": keys.join("_"),
            "lyricPhoneticFontSizeScale": Number(phonetic),
            "lyricTranslationFontSizeScale": Number(translation)
        })
    }

    /** 把 index 行与 index-1 行交换（上移），限制在 0..2 范围内。 */
    function moveLineUp(index) {
        if (index <= 0)
            return
        const order = root.lineOrder.slice()
        const tmp = order[index - 1]
        order[index - 1] = order[index]
        order[index] = tmp
        root.lineOrder = order
        root.commitLines(order, root.phoneticScale, root.translationScale)
    }

    /** 把 index 行与 index+1 行交换（下移）。 */
    function moveLineDown(index) {
        if (index >= root.lineOrder.length - 1)
            return
        const order = root.lineOrder.slice()
        const tmp = order[index + 1]
        order[index + 1] = order[index]
        order[index] = tmp
        root.lineOrder = order
        root.commitLines(order, root.phoneticScale, root.translationScale)
    }

    Column {
        id: column
        width: parent.width
        spacing: Theme.spacingSm

        SettingsLabel {
            width: parent.width
            text: I18n.tr("歌词来源")
        }

        SegmentedOptions {
            width: parent.width
            options: root.sourceOptions
            value: root.lyricSource
            onSelected: function(value) { Settings.set("lyricSource", value) }
        }

        SettingsNote {
            width: parent.width
            text: I18n.tr("自动模式优先使用网络歌词，找不到时回落到本地文件；选择网易云或本地文件则只使用对应来源。")
        }

        Item { width: 1; height: 2 }

        SettingsLabel {
            width: parent.width
            text: I18n.tr("显示行组合")
        }

        SettingsNote {
            width: parent.width
            text: I18n.tr("用眼睛按钮控制每行是否显示，上下箭头调整顺序；原文始终以主字号显示。")
        }

        LineOrderEditor {
            width: parent.width
            order: root.lineOrder.join("_")
            labelFor: function(key) { return root.labelFor(key) }
            exampleFor: function(key) { return root.exampleFor(key) }
            scaleFor: function(key) { return root.scaleFor(key) }
            previewFontFamily: Settings.get("lyricFontFamily", "") !== ""
                               ? Settings.get("lyricFontFamily", "") : Theme.fontFamily
            previewAlignment: {
                const align = Settings.get("lyricsAlignment", "LEFT")
                if (align === "CENTER") return Text.AlignHCenter
                if (align === "RIGHT") return Text.AlignRight
                return Text.AlignLeft
            }
            isVisible: function(key) {
                if (key === "TRANSLATION") return Settings.get("showLyricTranslation", true) !== false
                if (key === "PHONETIC") return Settings.get("showLyricPhonetic", false) === true
                return true
            }
            onReordered: function(order) {
                root.lineOrder = root.parseOrder(order)
                root.commitLines(root.lineOrder, root.phoneticScale, root.translationScale)
            }
            onVisibilityChanged: function(key, visible) {
                if (key === "TRANSLATION") Settings.set("showLyricTranslation", visible)
                else if (key === "PHONETIC") Settings.set("showLyricPhonetic", visible)
            }
        }

        SliderRow {
            width: parent.width
            label: I18n.tr("音译字号比例") + " "
            from: 0.4
            to: 1.0
            decimals: 2
            externalValue: root.phoneticScale
            formatValue: function(value) { return value.toFixed(2) + "x" }
            onCommitted: function(value) {
                root.commitLines(root.lineOrder, value, root.translationScale)
            }
        }

        SliderRow {
            width: parent.width
            label: I18n.tr("翻译字号比例") + " "
            from: 0.4
            to: 1.0
            decimals: 2
            externalValue: root.translationScale
            formatValue: function(value) { return value.toFixed(2) + "x" }
            onCommitted: function(value) {
                root.commitLines(root.lineOrder, root.phoneticScale, value)
            }
        }

        Item { width: 1; height: 2 }

        SettingsLabel {
            width: parent.width
            text: I18n.tr("歌词模糊")
        }

        SettingsSwitchRow {
            width: parent.width
            title: I18n.tr("启用歌词模糊")
            description: I18n.tr("非当前行轻微模糊以突出正在演唱的行")
            checked: root.blurEnabled
            onToggled: function(checked) { Settings.set("lyricBlurEnabled", checked) }
        }

        SliderRow {
            width: parent.width
            enabled: root.blurEnabled
            label: I18n.tr("模糊强度") + " "
            from: 0
            to: 5
            decimals: 1
            externalValue: root.blurAmount
            formatValue: function(value) { return value.toFixed(1) }
            onCommitted: function(value) { Settings.set("lyricBlurAmount", value) }
        }

        Item { width: 1; height: 4 }

        SettingsLabel {
            width: parent.width
            text: I18n.tr("对齐与高亮")
        }

        SliderRow {
            width: parent.width
            label: I18n.tr("歌词字号 ")
            from: 0.7
            to: 1.8
            decimals: 2
            externalValue: Number(Settings.get("lyricsFontScale", 1.0))
            formatValue: function(value) { return value.toFixed(2) + "x" }
            onCommitted: function(value) { Settings.set("lyricsFontScale", value) }
        }

        SegmentedOptions {
            width: parent.width
            options: [
                { value: "LEFT", label: I18n.tr("左对齐") },
                { value: "CENTER", label: I18n.tr("居中") },
                { value: "RIGHT", label: I18n.tr("右对齐") }
            ]
            value: {
                const align = Settings.get("lyricsAlignment", "LEFT")
                return (align === "CENTER" || align === "RIGHT") ? align : "LEFT"
            }
            onSelected: function(value) { Settings.set("lyricsAlignment", value) }
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
            value: Settings.get("lyricsHighlight", "LINE") === "WORD" ? "WORD" : "LINE"
            onSelected: function(value) { Settings.set("lyricsHighlight", value) }
        }

        SliderRow {
            width: parent.width
            label: I18n.tr("高亮动画时长 ")
            from: 0
            to: 800
            decimals: 0
            externalValue: Number(Settings.get("lyricsHighlightDuration", 220))
            formatValue: function(value) { return Math.round(value) + "ms" }
            onCommitted: function(value) {
                Settings.set("lyricsHighlightDuration", Math.round(value))
            }
        }

        SettingsSwitchRow {
            width: parent.width
            title: I18n.tr("封面页显示歌词")
            description: I18n.tr("在播放页封面下方显示逐行歌词")
            checked: Settings.get("coverShowsLyrics", true) !== false
            onToggled: function(checked) { Settings.set("coverShowsLyrics", checked) }
        }

        SettingsNote {
            width: parent.width
            text: I18n.tr("歌词显示与模糊设置会立即写入配置，播放页与全屏歌词页实时生效。")
        }
    }
}
