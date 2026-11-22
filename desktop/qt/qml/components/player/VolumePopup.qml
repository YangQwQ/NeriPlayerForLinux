import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"

// 紧凑布局下的音量小面板：锚定在音量按钮上方弹出，避免横向展开把右组按钮挤出边界。
// 形态对齐 VolumePanel（M3 surface 卡片 + 静音图标 + PlayerSlider），但不带全屏 scrim。
Item {
    id: popup

    property bool open: false
    /** 自动收起延时（ms），0 表示不自动收起；鼠标移入面板或按钮会暂停计时。 */
    property int hideDelay: 500

    /** 请求收起，由宿主决定是否真正关闭（例如还停在按钮上时忽略）。 */
    signal dismissRequested()

    width: 200
    height: 64
    visible: false
    z: 20

    NowPlayingTheme { id: np }

    readonly property string volumeIcon: {
        if (Player.muted || Player.volume <= 0.001)
            return "volume_off"
        if (Player.volume < 0.5)
            return "volume_down"
        return "volume_up"
    }

    Rectangle {
        id: card
        anchors.fill: parent
        radius: Theme.radiusLg
        // 播放页恒为暗色背景：不透明暗色卡片，浅色主题下也不会出现浅底深字的割裂感，
        // 且不会透出下方的进度时间文字。
        color: Theme.mix(Theme.scrim, Theme.onScrimColor, 0.08)
        border.width: 1
        border.color: Theme.withAlpha(Theme.onScrimColor, 0.18)

        RowLayout {
            anchors.fill: parent
            anchors.leftMargin: Theme.spacingMd
            anchors.rightMargin: Theme.spacingMd
            anchors.topMargin: Theme.spacingSm
            anchors.bottomMargin: Theme.spacingSm
            spacing: Theme.spacingSm

            IconButton {
                Layout.alignment: Qt.AlignVCenter
                Layout.preferredWidth: 38
                Layout.preferredHeight: 38
                icon: popup.volumeIcon
                iconSize: 20
                size: 38
                iconColor: Theme.onScrimColor
                hoverColor: Theme.withAlpha(Theme.onScrimColor, 0.12)
                tooltip: Player.muted ? I18n.tr("取消静音") : I18n.tr("静音")
                onClicked: Player.toggleMute()
            }

            PlayerSlider {
                id: slider
                Layout.alignment: Qt.AlignVCenter
                Layout.fillWidth: true
                Layout.preferredHeight: 38
                from: 0
                to: 1
                value: Player.volume
                accentColor: np.accent
                foregroundColor: np.foreground
                onMoved: Player.setVolume(value)
            }
        }
    }

    // 鼠标停在面板上（拖动滑块时）暂停自动收起；移出后走延时收起，
    // 避免鼠标在按钮与面板之间移动时因 hover 事件的先后顺序被误关。
    HoverHandler {
        id: panelHover
        onHoveredChanged: {
            if (hovered)
                hideTimer.stop()
            else
                popup.scheduleDismiss()
        }
    }

    Timer {
        id: hideTimer
        interval: popup.hideDelay
        repeat: false
        onTriggered: popup.dismissRequested()
    }

    NumberAnimation {
        id: fadeIn
        target: popup
        property: "opacity"
        from: 0
        to: 1
        duration: Theme.motionShort
        easing.type: Easing.OutCubic
    }

    NumberAnimation {
        id: fadeOut
        target: popup
        property: "opacity"
        from: 1
        to: 0
        duration: Theme.motionShort
        easing.type: Easing.OutCubic
        onFinished: popup.visible = false
    }

    onOpenChanged: {
        if (open) {
            hideTimer.stop()
            fadeOut.stop()
            popup.opacity = 0
            popup.visible = true
            fadeIn.restart()
        } else {
            hideTimer.stop()
            if (popup.visible)
                fadeOut.restart()
            else
                popup.visible = false
        }
    }

    /** 外部请求延时收起（按钮 hover 离开时调用，面板内悬停会暂停计时）。 */
    function scheduleDismiss() {
        if (panelHover.hovered)
            return
        if (hideDelay > 0)
            hideTimer.restart()
        else
            popup.dismissRequested()
    }

    function cancelDismiss() {
        hideTimer.stop()
    }
}
