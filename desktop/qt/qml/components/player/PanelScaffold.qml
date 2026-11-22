import QtQuick
import "../"

// 播放器浮层面板的统一骨架：全屏 scrim + 右侧滑入卡片 + 标题栏。
// 面板正文通过默认属性放进卡片（PanelScaffold { ... }），正文区域位于标题栏下方。
Item {
    id: scaffold

    property string title: ""
    /** 卡片宽度：队列/音效 420，音量/睡眠 380（对应 Compose 覆盖面板尺寸）。 */
    property real panelWidth: 420
    property real panelMargin: 12

    default property alias content: contentHost.data

    // 全屏遮罩：点击关闭面板
    Rectangle {
        id: scrim
        anchors.fill: parent
        color: Theme.withAlpha(Theme.scrim, 0.45)
        opacity: 0

        Behavior on opacity {
            NumberAnimation {
                duration: Theme.motionMedium
                easing.type: Easing.OutCubic
            }
        }

        Component.onCompleted: opacity = 1

        MouseArea {
            anchors.fill: parent
            acceptedButtons: Qt.AllButtons
            onClicked: Nav.closePanel()

            // 滚轮在 Qt Quick 中按绘制顺序从前往后分发：scrim 位于面板底层内容
            // 之前，只有在此接受事件，滚轮才不会继续传到被遮住的页面。
            // blocking 确保携带滚轮的 PointerHandler 不会再让后面的 handler 处理。
            WheelHandler {
                target: null
                blocking: true
                acceptedDevices: PointerDevice.Mouse | PointerDevice.TouchPad
                onWheel: function(event) { event.accepted = true }
            }

            // MouseArea 的 hover 事件在 Qt6 里总是 event->ignore()，无法阻止
            // 下层按钮的 HoverHandler 进入 hover 态；用 blocking 的 HoverHandler
            // 吞掉悬停，面板关闭（Loader 卸载）后自动失效。
            HoverHandler {
                target: null
                blocking: true
            }
        }
    }

    Rectangle {
        id: card

        width: Math.min(scaffold.panelWidth, scaffold.width - scaffold.panelMargin * 2)
        height: scaffold.height - scaffold.panelMargin * 2
        anchors.right: parent.right
        anchors.rightMargin: scaffold.panelMargin
        anchors.verticalCenter: parent.verticalCenter
        radius: Theme.radiusXl + 4
        color: Theme.surfaceContainerLow
        clip: true

        // 右侧滑入：卡片从窗口右侧外滑到最终位置
        transform: Translate {
            id: slide
            x: card.width + scaffold.panelMargin
        }

        // 消费卡片空白处的点击，避免冒泡到遮罩把面板关掉。
        // z: -1 垫在标题栏/正文之后：面板自身的按钮与 ListView 仍可交互，
        // 不会像之前那样被这张铺满卡片的 MouseArea 抢走点击。
        // 滚轮/hover 的穿透拦截交给底层的 scrim（blocking handler）负责，
        // 此处不再放 blocking handler，否则会吞掉面板内 ListView 的滚轮。
        MouseArea {
            z: -1
            anchors.fill: parent
            acceptedButtons: Qt.AllButtons
        }

        Column {
            anchors.fill: parent

            // 标题栏
            Item {
                id: titleBar
                width: parent.width
                height: 64

                Text {
                    anchors.left: parent.left
                    anchors.leftMargin: 18
                    anchors.right: closeButton.left
                    anchors.rightMargin: 8
                    anchors.verticalCenter: parent.verticalCenter
                    text: scaffold.title
                    elide: Text.ElideRight
                    color: Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("titleMedium").size
                    font.weight: Font.DemiBold
                    font.letterSpacing: Theme.textStyle("titleMedium").letterSpacing
                }

                IconButton {
                    id: closeButton
                    anchors.right: parent.right
                    anchors.rightMargin: 8
                    anchors.verticalCenter: parent.verticalCenter
                    icon: "close"
                    iconSize: 24
                    size: 48
                    tooltip: I18n.tr("关闭")
                    onClicked: Nav.closePanel()
                }
            }

            // 正文区域：面板内容锚定/定位于此
            Item {
                id: contentHost
                width: parent.width
                height: parent.height - titleBar.height
            }
        }

        Component.onCompleted: slideIn.start()

        SequentialAnimation {
            id: slideIn

            NumberAnimation {
                target: slide
                property: "x"
                from: card.width + scaffold.panelMargin
                to: 0
                duration: Theme.motionMedium
                easing.type: Easing.OutCubic
            }
        }
    }

    // 键盘关闭。用 Shortcut 而不是 Keys，避免焦点在滑杆等控件上时失效。
    Shortcut {
        enabled: scaffold.visible
        sequence: "Escape"
        onActivated: Nav.closePanel()
    }
}
