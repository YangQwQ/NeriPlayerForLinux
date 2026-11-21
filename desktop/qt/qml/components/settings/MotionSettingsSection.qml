import QtQuick
import "../"

// 动效设置卡片内容（供设置页「动效」分类内嵌）。
// 只放设置行本身，标题与卡片容器由外层 SettingsSection 提供。
// 所有字段均通过 Settings.get / Settings.set / Settings.patch 读写，便于后端字段后补。
// 歌词滚动、封面旋转、控制条自动隐藏等原本散落在「播放页」里的动效项，统一归到此处，
// 同一设置项只在本卡片出现一次。
Item {
    id: root

    width: parent ? parent.width : 0
    implicitHeight: column.implicitHeight

    readonly property bool motionEnabled: Settings.get("motionEnabled", true) !== false
    readonly property string progressColorMode: {
        const mode = Settings.get("progressColorMode", "COVER")
        return (mode === "THEME" || mode === "COVER") ? mode : "COVER"
    }
    readonly property string lyricScrollEasing: {
        const value = Settings.get("lyricsScrollEasing", "CUBIC")
        return (value === "QUAD" || value === "SINE" || value === "LINEAR") ? value : "CUBIC"
    }

    function setMotion(key, value) { Settings.set(key, value) }

    Column {
        id: column
        width: parent.width
        spacing: Theme.spacingSm

        SettingsSwitchRow {
            width: parent.width
            title: I18n.tr("启用动效")
            description: I18n.tr("总开关，关闭后所有界面动画退化为瞬时切换")
            checked: root.motionEnabled
            onToggled: function(checked) { root.setMotion("motionEnabled", checked) }
        }

        Item { width: 1; height: 2 }

        SettingsLabel {
            width: parent.width
            text: I18n.tr("界面过渡")
        }

        SettingsSwitchRow {
            width: parent.width
            enabled: root.motionEnabled
            title: I18n.tr("页面切换动画")
            description: I18n.tr("页面切换时的淡入与轻微上浮")
            checked: Settings.get("motionPageTransition", true) !== false
            onToggled: function(checked) { root.setMotion("motionPageTransition", checked) }
        }

        SettingsSwitchRow {
            width: parent.width
            enabled: root.motionEnabled
            title: I18n.tr("面板浮层动画")
            description: I18n.tr("浮层面板出现与消失时的淡入淡出和缩放")
            checked: Settings.get("motionPanelTransition", true) !== false
            onToggled: function(checked) { root.setMotion("motionPanelTransition", checked) }
        }

        SettingsSwitchRow {
            width: parent.width
            enabled: root.motionEnabled
            title: I18n.tr("主题切换过渡")
            description: I18n.tr("切换深浅色或主题色时的颜色渐变")
            checked: Settings.get("motionThemeTransition", true) !== false
            onToggled: function(checked) { root.setMotion("motionThemeTransition", checked) }
        }

        Item { width: 1; height: 2 }

        SettingsLabel {
            width: parent.width
            text: I18n.tr("歌词滚动动画")
        }

        SettingsSwitchRow {
            width: parent.width
            enabled: root.motionEnabled
            title: I18n.tr("歌词滚动动画")
            description: I18n.tr("歌词自动跟随时的平滑滚动")
            checked: Settings.get("motionLyricScroll", true) !== false
            onToggled: function(checked) { root.setMotion("motionLyricScroll", checked) }
        }

        SegmentedOptions {
            width: parent.width
            enabled: root.motionEnabled && Settings.get("motionLyricScroll", true) !== false
            options: [
                { value: "CUBIC", label: I18n.tr("三次曲线") },
                { value: "QUAD", label: I18n.tr("二次曲线") },
                { value: "SINE", label: I18n.tr("正弦") },
                { value: "LINEAR", label: I18n.tr("线性") }
            ]
            value: root.lyricScrollEasing
            onSelected: function(value) { root.setMotion("lyricsScrollEasing", value) }
        }

        SliderRow {
            width: parent.width
            enabled: root.motionEnabled && Settings.get("motionLyricScroll", true) !== false
            label: I18n.tr("滚动动画时长 ")
            from: 80
            to: 1200
            decimals: 0
            externalValue: Number(Settings.get("lyricsScrollDuration", 420))
            formatValue: function(value) { return Math.round(value) + "ms" }
            onCommitted: function(value) {
                root.setMotion("lyricsScrollDuration", Math.round(value))
            }
        }

        Item { width: 1; height: 2 }

        SettingsLabel {
            width: parent.width
            text: I18n.tr("播放页动效")
        }

        SettingsSwitchRow {
            width: parent.width
            enabled: root.motionEnabled
            title: I18n.tr("圆形封面旋转")
            description: I18n.tr("封面形状为圆形时缓慢旋转（约 20 秒一圈），暂停时停在当前角度")
            checked: Settings.get("nowPlayingCoverRotate", false) === true
            onToggled: function(checked) { root.setMotion("nowPlayingCoverRotate", checked) }
        }

        SettingsSwitchRow {
            width: parent.width
            enabled: root.motionEnabled
            title: I18n.tr("自动隐藏控制条")
            description: I18n.tr("鼠标静止一段时间后淡出底部控制条，只保留封面与歌词")
            checked: Settings.get("nowPlayingAutoHideControls", true) !== false
            onToggled: function(checked) { root.setMotion("nowPlayingAutoHideControls", checked) }
        }

        SliderRow {
            width: parent.width
            enabled: root.motionEnabled && Settings.get("nowPlayingAutoHideControls", true) !== false
            label: I18n.tr("自动隐藏延迟")
            from: 1000
            to: 8000
            decimals: 0
            externalValue: Number(Settings.get("nowPlayingAutoHideDelayMs", 2600))
            formatValue: function(value) {
                return (value / 1000).toFixed(1) + I18n.tr(" 秒")
            }
            onCommitted: function(value) {
                root.setMotion("nowPlayingAutoHideDelayMs", Math.round(value))
            }
        }

        SettingsSwitchRow {
            width: parent.width
            enabled: root.motionEnabled
            title: I18n.tr("悬浮歌词显现动画")
            description: I18n.tr("悬浮歌词显示与隐藏时的淡入淡出；关闭后即时显现")
            checked: Settings.get("floatingLyricsShowAnimation", true) !== false
            onToggled: function(checked) { root.setMotion("floatingLyricsShowAnimation", checked) }
        }

        Item { width: 1; height: 2 }

        SettingsLabel {
            width: parent.width
            text: I18n.tr("进度条颜色")
        }

        SegmentedOptions {
            width: parent.width
            options: [
                { value: "COVER", label: I18n.tr("跟随封面色") },
                { value: "THEME", label: I18n.tr("跟随主题色") }
            ]
            value: root.progressColorMode
            onSelected: function(value) { root.setMotion("progressColorMode", value) }
        }

        SettingsNote {
            width: parent.width
            text: I18n.tr("播放页进度条默认取当前封面的主色，切歌时会随之变化")
        }
    }
}
