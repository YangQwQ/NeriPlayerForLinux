import QtQuick
import QtQuick.Layouts
import ".."
import "../motion"

// 全屏歌词覆盖层：在播放页背景之上压一层暗色遮罩，只留大字号歌词。
// 顶栏（关闭/字号调节）默认隐藏，鼠标移到顶部才淡入；Esc 关闭。
//
// 进出动画由 active 驱动（NowPlayingPage 把 showFullLyrics 绑到 active）：
// 不能直接用 visible 做动画开关——visible=false 会让元素立刻从渲染树消失，Behavior 看不到
// 过渡。这里在内部的 content item 上做 opacity/位移/缩放的 Behavior，退出动画播完再收起宿主。
Item {
    id: root

    /** 外部开关：true=进入，false=退出（退出时先播完动画再隐藏）。 */
    property bool active: false

    signal closeRequested()

    // 宿主visible 由 active 或「仍在播退出动画」决定，内部层负责实际渐变。
    visible: root.active || fade.opacity > 0.01

    MotionPrefs { id: motion }
    readonly property int enterDuration: motion.panelTransition ? Theme.motionMedium : 0

    NowPlayingTheme { id: np }

    readonly property real currentScale: {
        const scale = Settings.get("lyricsFontScale", 1.0)
        return (typeof scale === "number" && scale > 0) ? scale : 1.0
    }

    readonly property real baseFontSize: Math.min(34, Math.max(24, width * 0.026))

    function adjustScale(delta) {
        const next = Math.max(0.7, Math.min(1.8, Math.round((currentScale + delta) * 10) / 10))
        Settings.set("lyricsFontScale", next)
    }

    // 真正的视觉层：淡入 + 轻微上浮放大，退出时反向播放。
    Item {
        id: fade

        anchors.fill: parent
        opacity: root.active ? 1 : 0
        scale: root.active ? 1 : 0.985
        transformOrigin: Item.Center

        Behavior on opacity {
            NumberAnimation { duration: root.enterDuration; easing.type: Easing.OutCubic }
        }
        Behavior on scale {
            NumberAnimation { duration: root.enterDuration; easing.type: Easing.OutCubic }
        }

        property real enterY: root.active ? 0 : 12
        transform: Translate { y: fade.enterY }

        Behavior on enterY {
            NumberAnimation { duration: root.enterDuration; easing.type: Easing.OutCubic }
        }

        Rectangle {
            anchors.fill: parent
            color: Theme.withAlpha(np.scrim, 0.62)

            Behavior on color {
                ColorAnimation {
                    duration: motion.themeTransition ? motion.themeDuration : 0
                    easing.type: Easing.Bezier
                    easing.bezierCurve: [0.4, 0.0, 0.2, 1.0]
                }
            }
        }

        // 歌词正文：宽屏限宽居中，窄屏铺满
        LyricsPane {
            id: lyrics

            anchors.fill: parent
            anchors.leftMargin: Math.max(0, (parent.width - Math.min(parent.width, 1000)) / 2)
            anchors.rightMargin: Math.max(0, (parent.width - Math.min(parent.width, 1000)) / 2)
            anchors.topMargin: Theme.spacingHuge
            anchors.bottomMargin: Theme.spacingHuge
            baseFontSize: root.baseFontSize
            horizontalPadding: 16
        }

        // 顶栏 hover 区域：覆盖顶部 96px
        Item {
            id: topZone

            anchors.top: parent.top
            anchors.left: parent.left
            anchors.right: parent.right
            height: 96

            HoverHandler {
                id: topHover
            }

            Rectangle {
                anchors.fill: parent
                opacity: topHover.hovered ? 1 : 0

                Behavior on opacity {
                    NumberAnimation { duration: Theme.motionMedium; easing.type: Easing.OutCubic }
                }

                gradient: Gradient {
                    GradientStop { position: 0.0; color: Theme.withAlpha(np.scrim, 0.6) }
                    GradientStop { position: 1.0; color: Theme.withAlpha(np.scrim, 0.0) }
                }
            }

            Item {
                anchors.fill: parent
                opacity: topHover.hovered ? 1 : 0

                Behavior on opacity {
                    NumberAnimation { duration: Theme.motionMedium; easing.type: Easing.OutCubic }
                }

                IconButton {
                    anchors.left: parent.left
                    anchors.leftMargin: Theme.spacingMd
                    anchors.verticalCenter: parent.verticalCenter
                    icon: "arrow_back"
                    iconSize: 24
                    size: 44
                    iconColor: np.foreground
                    hoverColor: np.hoverFill
                    tooltip: I18n.tr("返回播放页")
                    onClicked: root.closeRequested()
                }

                RowLayout {
                    anchors.right: parent.right
                    anchors.rightMargin: Theme.spacingMd
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: 2

                    Text {
                        Layout.alignment: Qt.AlignVCenter
                        text: root.currentScale.toFixed(2) + "x"
                        color: np.foregroundFaint
                        font.family: Prefs.effectiveLyricFont
                        font.pixelSize: Theme.textStyle("labelSmall").size
                    }

                    IconButton {
                        Layout.alignment: Qt.AlignVCenter
                        icon: "text_decrease"
                        iconSize: 22
                        size: 40
                        iconColor: np.foreground
                        hoverColor: np.hoverFill
                        tooltip: I18n.tr("缩小歌词字号")
                        onClicked: root.adjustScale(-0.1)
                    }

                    IconButton {
                        Layout.alignment: Qt.AlignVCenter
                        icon: "text_increase"
                        iconSize: 22
                        size: 40
                        iconColor: np.foreground
                        hoverColor: np.hoverFill
                        tooltip: I18n.tr("放大歌词字号")
                        onClicked: root.adjustScale(0.1)
                    }
                }
            }
        }
    }

    // 用 Shortcut 而不是焦点 Keys，避免抢走全局快捷键的焦点
    Shortcut {
        enabled: root.visible
        sequence: "Escape"
        onActivated: root.closeRequested()
    }
}
