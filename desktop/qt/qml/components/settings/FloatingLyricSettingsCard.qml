import QtQuick
import "../"

// 悬浮歌词设置卡片内容（供设置页「悬浮歌词」分类内嵌）。
// 显示行组合（含每行显示/隐藏）复用 LineOrderEditor，与歌词页样式一致。
// 只放设置行本身，标题与卡片容器由外层 SettingsSection 提供。
Item {
    id: root

    width: parent ? parent.width : 0
    implicitHeight: column.implicitHeight

    readonly property var lineTypes: [
        { key: "ORIGINAL", label: I18n.tr("原文") },
        { key: "TRANSLATION", label: I18n.tr("翻译") },
        { key: "PHONETIC", label: I18n.tr("音译") }
    ]

    readonly property var windowModeOptions: [
        { value: "LAYER", label: I18n.tr("置顶覆盖层") },
        { value: "NORMAL", label: I18n.tr("普通窗口") }
    ]

    readonly property var lyricColors: [
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

    readonly property string lineOrder: {
        const value = Settings.get("floatingLyricsLineOrder", "ORIGINAL_TRANSLATION_PHONETIC")
        const valid = ["ORIGINAL", "TRANSLATION", "PHONETIC"]
        const parts = String(value || "").split("_")
        const result = []
        for (let i = 0; i < parts.length; i++) {
            if (valid.indexOf(parts[i]) >= 0 && result.indexOf(parts[i]) < 0)
                result.push(parts[i])
        }
        for (let i = 0; i < valid.length; i++) {
            if (result.indexOf(valid[i]) < 0)
                result.push(valid[i])
        }
        return result.join("_")
    }

    function labelFor(key) {
        for (let i = 0; i < root.lineTypes.length; i++) {
            if (root.lineTypes[i].key === key)
                return root.lineTypes[i].label
        }
        return key
    }

    function setLineOrder(order) { Settings.set("floatingLyricsLineOrder", order) }

    Column {
        id: column
        width: parent.width
        spacing: Theme.spacingSm

        SettingsSwitchRow {
            width: parent.width
            title: I18n.tr("启用悬浮歌词")
            description: I18n.tr("在桌面上显示轻量悬浮歌词（快捷键 Ctrl+L）")
            checked: Settings.get("floatingLyricsEnabled", false) === true
            onToggled: function(checked) { Settings.set("floatingLyricsEnabled", checked) }
        }

        SettingsSwitchRow {
            width: parent.width
            title: I18n.tr("应用内隐藏")
            description: I18n.tr("主窗口获得焦点时隐藏悬浮歌词，避免遮挡页面")
            checked: Settings.get("floatingLyricsHideInApp", false) === true
            onToggled: function(checked) { Settings.set("floatingLyricsHideInApp", checked) }
        }

        SettingsSwitchRow {
            width: parent.width
            title: I18n.tr("锁定位置")
            description: I18n.tr("锁定后无法拖动悬浮歌词")
            checked: Settings.get("floatingLyricsLocked", false) === true
            onToggled: function(checked) { Settings.set("floatingLyricsLocked", checked) }
        }

        SettingsLabel {
            width: parent.width
            text: I18n.tr("窗口模式")
        }

        SegmentedOptions {
            width: parent.width
            options: root.windowModeOptions
            value: {
                const mode = Settings.get("floatingLyricsWindowMode", "LAYER")
                return mode === "NORMAL" ? "NORMAL" : "LAYER"
            }
            onSelected: function(value) { Settings.set("floatingLyricsWindowMode", value) }
        }

        SettingsNote {
            width: parent.width
            text: I18n.tr("置顶覆盖层由桌面合成器直接托管，始终浮在最上层；普通窗口是常规窗口，可跨屏拖动但会进任务栏与窗口切换器。")
        }

        Item { width: 1; height: 2 }

        SettingsLabel {
            width: parent.width
            text: I18n.tr("显示行组合")
        }

        SettingsNote {
            width: parent.width
            text: I18n.tr("用眼睛按钮控制每行是否显示，上下箭头调整顺序；字号与透明度在下方单独设置。")
        }

        LineOrderEditor {
            width: parent.width
            order: root.lineOrder
            labelFor: function(key) { return root.labelFor(key) }
            exampleFor: function(key) { return "" }
            previewFontFamily: Prefs.effectiveLyricFont
            showPreview: false
            isVisible: function(key) {
                if (key === "TRANSLATION") return Settings.get("floatingLyricsShowTranslation", true) !== false
                if (key === "PHONETIC") return Settings.get("floatingLyricsShowPhonetic", false) === true
                return true
            }
            onReordered: function(order) { root.setLineOrder(order) }
            onVisibilityChanged: function(key, visible) {
                if (key === "TRANSLATION") Settings.set("floatingLyricsShowTranslation", visible)
                else if (key === "PHONETIC") Settings.set("floatingLyricsShowPhonetic", visible)
            }
        }

        SliderRow {
            width: parent.width
            label: I18n.tr("字号")
            externalValue: Number(Settings.get("floatingLyricsFontSize", 30))
            from: 14
            to: 72
            decimals: 0
            formatValue: function(value) { return Math.round(value) + " px" }
            onCommitted: function(value) { Settings.set("floatingLyricsFontSize", value) }
        }

        SettingsLabel {
            width: parent.width
            text: I18n.tr("渲染方式")
        }

        SegmentedOptions {
            width: parent.width
            options: [
                { value: "SHADOW", label: I18n.tr("阴影") },
                { value: "OUTLINE", label: I18n.tr("描边") }
            ]
            value: Settings.get("floatingLyricsRenderStyle", "SHADOW") === "OUTLINE" ? "OUTLINE" : "SHADOW"
            onSelected: function(value) { Settings.set("floatingLyricsRenderStyle", value) }
        }

        SettingsLabel {
            width: parent.width
            text: I18n.tr("歌词颜色")
        }

        Row {
            width: parent.width
            spacing: Theme.spacingSm

            Repeater {
                model: root.lyricColors

                delegate: ColorSwatch {
                    required property var modelData
                    swatchSize: 28
                    swatchColor: modelData.color
                    selected: Settings.get("floatingLyricsTextColor", "WHITE") === modelData.name
                    onClicked: Settings.set("floatingLyricsTextColor", modelData.name)
                }
            }
        }

        SliderRow {
            width: parent.width
            label: I18n.tr("主歌词不透明度")
            externalValue: Number(Settings.get("floatingLyricsLyricAlpha", 1.0))
            from: 0.1
            to: 1.0
            formatValue: function(value) { return Math.round(value * 100) + "%" }
            onCommitted: function(value) { Settings.set("floatingLyricsLyricAlpha", value) }
        }

        SliderRow {
            width: parent.width
            label: I18n.tr("翻译不透明度")
            externalValue: Number(Settings.get("floatingLyricsTranslationAlpha", 0.75))
            from: 0.1
            to: 1.0
            formatValue: function(value) { return Math.round(value * 100) + "%" }
            onCommitted: function(value) { Settings.set("floatingLyricsTranslationAlpha", value) }
        }

        SliderRow {
            width: parent.width
            label: I18n.tr("音译不透明度")
            enabled: Settings.get("floatingLyricsShowPhonetic", false) === true
            externalValue: Number(Settings.get("floatingLyricsPhoneticAlpha", 0.75))
            from: 0.1
            to: 1.0
            formatValue: function(value) { return Math.round(value * 100) + "%" }
            onCommitted: function(value) { Settings.set("floatingLyricsPhoneticAlpha", value) }
        }

        SliderRow {
            width: parent.width
            label: I18n.tr("音译字号比例")
            enabled: Settings.get("floatingLyricsShowPhonetic", false) === true
            from: 0.4
            to: 1.0
            decimals: 2
            externalValue: Number(Settings.get("lyricPhoneticFontSizeScale", 0.58))
            formatValue: function(value) { return value.toFixed(2) + "x" }
            onCommitted: function(value) { Settings.set("lyricPhoneticFontSizeScale", value) }
        }

        SliderRow {
            width: parent.width
            label: I18n.tr("翻译字号比例")
            from: 0.4
            to: 1.0
            decimals: 2
            externalValue: Number(Settings.get("lyricTranslationFontSizeScale", 0.58))
            formatValue: function(value) { return value.toFixed(2) + "x" }
            onCommitted: function(value) { Settings.set("lyricTranslationFontSizeScale", value) }
        }

        SettingsLabel {
            width: parent.width
            text: I18n.tr("背景颜色")
        }

        Row {
            width: parent.width
            spacing: Theme.spacingSm

            Repeater {
                model: root.lyricColors

                delegate: ColorSwatch {
                    required property var modelData
                    swatchSize: 28
                    swatchColor: modelData.color
                    selected: Settings.get("floatingLyricsBackgroundColor", "BLACK") === modelData.name
                    onClicked: Settings.set("floatingLyricsBackgroundColor", modelData.name)
                }
            }
        }

        SliderRow {
            width: parent.width
            label: I18n.tr("背景不透明度")
            externalValue: Number(Settings.get("floatingLyricsBackgroundAlpha", 0.35))
            from: 0
            to: 1
            formatValue: function(value) { return Math.round(value * 100) + "%" }
            onCommitted: function(value) { Settings.set("floatingLyricsBackgroundAlpha", value) }
        }

        SettingsLabel {
            width: parent.width
            text: I18n.tr("对齐方式")
        }

        SegmentedOptions {
            width: parent.width
            options: [
                { value: "LEFT", label: I18n.tr("居左") },
                { value: "CENTER", label: I18n.tr("居中") },
                { value: "RIGHT", label: I18n.tr("居右") }
            ]
            value: {
                const align = Settings.get("floatingLyricsAlignment", "CENTER")
                return (align === "LEFT" || align === "RIGHT") ? align : "CENTER"
            }
            onSelected: function(value) { Settings.set("floatingLyricsAlignment", value) }
        }

        SettingsNote {
            width: parent.width
            text: I18n.tr("悬浮歌词是独立的桌面窗口；未锁定时可直接拖动，锁定时鼠标穿透（Ctrl+Alt+L 解锁）。")
        }
    }
}
