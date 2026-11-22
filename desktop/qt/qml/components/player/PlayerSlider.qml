import QtQuick
import QtQuick.Controls

// 带 M3 配色的滑杆。父级把 value 绑定到数据源，并在 moved 里写回数据源即可。
// 默认用主界面 Theme 配色；播放页恒暗背景可覆盖 accentColor / foregroundColor。
Item {
    id: root

    property real value: 0
    property real from: 0
    property real to: 1
    property real stepSize: 0
    property bool enabledSlider: true

    // 上色场景可覆盖：播放页暗背景传 NowPlayingTheme 的 accent / foreground 系颜色。
    property color accentColor: Theme.primary
    property color foregroundColor: Theme.onSurfaceColor

    readonly property bool pressed: slider.pressed

    signal moved(real value)
    signal released()

    implicitHeight: 40

    Slider {
        id: slider

        anchors.fill: parent
        from: root.from
        to: root.to
        stepSize: root.stepSize
        value: root.value
        enabled: root.enabledSlider

        onMoved: root.moved(value)
        onPressedChanged: {
            if (!pressed)
                root.released()
        }

        background: Rectangle {
            x: 0
            y: slider.height / 2 - height / 2
            width: slider.width
            height: 4
            radius: 2
            color: Theme.withAlpha(root.foregroundColor, 0.16)

            Rectangle {
                width: parent.width * slider.visualPosition
                height: parent.height
                radius: 2
                color: slider.enabled ? root.accentColor : Theme.withAlpha(root.foregroundColor, 0.28)
            }
        }

        handle: Rectangle {
            x: slider.visualPosition * (slider.width - width)
            y: slider.height / 2 - height / 2
            width: 16
            height: 16
            radius: 8
            color: slider.enabled ? root.accentColor : Theme.withAlpha(root.foregroundColor, 0.38)
            scale: slider.pressed ? 1.15 : 1.0

            Behavior on scale {
                NumberAnimation {
                    duration: Theme.motionShort
                }
            }
        }
    }
}
