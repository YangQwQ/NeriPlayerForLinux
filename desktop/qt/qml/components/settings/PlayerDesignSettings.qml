import QtQuick
import "../"

// 播放页设置（供设置页「播放页」分组内嵌）。
//
// 这里只有设置行本身，不带标题与卡片容器：外层由 SettingsPage 的 SettingsSection 提供，
// 集成方按 `PlayerDesignSettings { width: parent.width }` 实例化即可。
// 字段与后端 AppSettings.nowPlayingCoverShape / nowPlayingBackground /
// nowPlayingAutoHideControls / nowPlayingAutoHideDelayMs /
// nowPlayingCoverRotate / nowPlayingInfoAlignment / nowPlayingLyricsGlow 一一对应。
Item {
    id: root

    readonly property string coverShape: {
        const shape = Settings.get("nowPlayingCoverShape", "ROUNDED")
        return (shape === "CIRCLE" || shape === "SQUARE") ? shape : "ROUNDED"
    }
    readonly property string background: {
        const mode = Settings.get("nowPlayingBackground", "BLUR")
        return (mode === "GRADIENT" || mode === "SOLID") ? mode : "BLUR"
    }
    readonly property bool autoHide: Settings.get("nowPlayingAutoHideControls", true) !== false
    readonly property real autoHideDelayMs: {
        const value = Settings.get("nowPlayingAutoHideDelayMs", 2600)
        return typeof value === "number" && value > 0 ? value : 2600
    }
    readonly property bool coverRotate: Settings.get("nowPlayingCoverRotate", false) === true
    readonly property string infoAlignment: {
        const value = Settings.get("nowPlayingInfoAlignment", "LEFT")
        return (value === "CENTER" || value === "RIGHT") ? value : "LEFT"
    }
    readonly property bool lyricsGlow: Settings.get("nowPlayingLyricsGlow", true) !== false

    width: parent ? parent.width : 0
    implicitHeight: column.implicitHeight


    Column {
        id: column

        width: parent.width
        spacing: Theme.spacingSm

        SettingsLabel {
            text: I18n.tr("封面形状")
        }

        SegmentedOptions {
            options: [
                { value: "CIRCLE", label: I18n.tr("圆形") },
                { value: "ROUNDED", label: I18n.tr("圆角") },
                { value: "SQUARE", label: I18n.tr("方形") }
            ]
            value: root.coverShape
            onSelected: function(value) {
                Settings.patch({ "nowPlayingCoverShape": value })
            }
        }

        Item {
            width: 1
            height: Theme.spacingSm
        }

        SettingsLabel {
            text: I18n.tr("背景样式")
        }

        SegmentedOptions {
            options: [
                { value: "BLUR", label: I18n.tr("封面模糊") },
                { value: "GRADIENT", label: I18n.tr("渐变") },
                { value: "SOLID", label: I18n.tr("纯色") }
            ]
            value: root.background
            onSelected: function(value) {
                Settings.patch({ "nowPlayingBackground": value })
            }
        }

        SettingsNote {
            text: I18n.tr("封面模糊取自当前歌曲封面并叠加暗色；渐变与纯色使用封面主色/主题色生成。")
        }

        Item {
            width: 1
            height: Theme.spacingSm
        }

        SettingsSwitchRow {
            title: I18n.tr("自动隐藏控制条")
            description: I18n.tr("鼠标静止一段时间后淡出底部控制条，只保留封面与歌词")
            checked: root.autoHide
            onToggled: function(checked) {
                Settings.patch({ "nowPlayingAutoHideControls": checked })
            }
        }

        SliderRow {
            label: I18n.tr("自动隐藏延迟")
            enabled: root.autoHide
            from: 1000
            to: 8000
            externalValue: root.autoHideDelayMs
            formatValue: function(value) {
                return (value / 1000).toFixed(1) + I18n.tr(" 秒")
            }
            onCommitted: function(value) {
                Settings.patch({ "nowPlayingAutoHideDelayMs": Math.round(value) })
            }
        }

        Item {
            width: 1
            height: Theme.spacingSm
        }

        SettingsSwitchRow {
            title: I18n.tr("圆形封面旋转")
            description: I18n.tr("封面形状为圆形时缓慢旋转（约 20 秒一圈），暂停时停在当前角度")
            checked: root.coverRotate
            onToggled: function(checked) {
                Settings.patch({ "nowPlayingCoverRotate": checked })
            }
        }

        Item {
            width: 1
            height: Theme.spacingSm
        }

        SettingsLabel {
            text: I18n.tr("歌曲信息对齐")
        }

        SegmentedOptions {
            options: [
                { value: "LEFT", label: I18n.tr("左对齐") },
                { value: "CENTER", label: I18n.tr("居中") },
                { value: "RIGHT", label: I18n.tr("右对齐") }
            ]
            value: root.infoAlignment
            onSelected: function(value) {
                Settings.patch({ "nowPlayingInfoAlignment": value })
            }
        }

        Item {
            width: 1
            height: Theme.spacingSm
        }

        SettingsSwitchRow {
            title: I18n.tr("歌词辉光")
            description: I18n.tr("当前歌词行加一层柔和光晕，关闭后可省一点 GPU")
            checked: root.lyricsGlow
            onToggled: function(checked) {
                Settings.patch({ "nowPlayingLyricsGlow": checked })
            }
        }
    }
}
