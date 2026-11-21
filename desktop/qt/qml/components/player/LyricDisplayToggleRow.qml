import QtQuick
import ".."

// 歌词显示开关：原文 / 音译 / 翻译 三个独立开关，直接读写 Settings。
// 作为一项加在播放页右上角竖排按钮列里（评论/一起听下方），风格与竖列里的
// 其它圆形 IconButton 一致，不额外占用控制条高度。
// 开关状态与 LyricsPane 的渲染共用同一组设置键。
Item {
    id: root

    readonly property bool showOriginal: Settings.get("showLyricOriginal", true) !== false
    readonly property bool showPhonetic: Settings.get("showLyricPhonetic", false) === true
    readonly property bool showTranslation: Settings.get("showLyricTranslation", true) !== false

    implicitWidth: 40
    implicitHeight: column.implicitHeight

    NowPlayingTheme { id: np }

    Column {
        id: column

        anchors.horizontalCenter: parent.horizontalCenter
        spacing: Theme.spacingXs

        IconButton {
            anchors.horizontalCenter: parent.horizontalCenter
            icon: "text_fields"
            iconSize: 20
            size: 40
            iconColor: root.showOriginal ? np.accent : np.foregroundDim
            hoverColor: np.hoverFill
            tooltip: root.showOriginal ? I18n.tr("隐藏原文") : I18n.tr("显示原文")
            onClicked: Settings.set("showLyricOriginal", !root.showOriginal)
        }

        IconButton {
            anchors.horizontalCenter: parent.horizontalCenter
            icon: "abc"
            iconSize: 20
            size: 40
            iconColor: root.showPhonetic ? np.accent : np.foregroundDim
            hoverColor: np.hoverFill
            tooltip: root.showPhonetic ? I18n.tr("隐藏音译") : I18n.tr("显示音译")
            onClicked: Settings.set("showLyricPhonetic", !root.showPhonetic)
        }

        IconButton {
            anchors.horizontalCenter: parent.horizontalCenter
            icon: "translate"
            iconSize: 20
            size: 40
            iconColor: root.showTranslation ? np.accent : np.foregroundDim
            hoverColor: np.hoverFill
            tooltip: root.showTranslation ? I18n.tr("隐藏翻译") : I18n.tr("显示翻译")
            onClicked: Settings.set("showLyricTranslation", !root.showTranslation)
        }
    }
}
