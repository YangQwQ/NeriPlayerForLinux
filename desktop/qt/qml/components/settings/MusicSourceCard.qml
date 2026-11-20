import QtQuick
import "../"

// 音乐源紧凑卡片：左侧图标 + 名称 + 登录状态，右侧开关 + 登录/登出按钮在同一行。
// 通过 page 上下文的 page.findAccount / page.toggleSourceAccount / page.setSetting 复用现有逻辑。
Item {
    id: root

    property string source: ""
    property string displayName: ""
    property string iconName: "cloud"
    property string enabledKey: ""
    property bool enabledDefault: true
    property string description: ""
    property string loginButtonText: I18n.tr("登录")
    property string loginButtonIcon: "login"
    property bool settingValue: true
    property var account: null

    signal toggled(bool value)
    signal loginClicked()

    width: parent ? parent.width : 0
    implicitHeight: 70

    Rectangle {
        anchors.fill: parent
        radius: Theme.radiusLg
        color: Theme.withAlpha(Theme.surfaceContainerHigh, 0.7)
    }

    Item {
        anchors.fill: parent
        anchors.margins: Theme.spacingMd

        NeriIcon {
            id: sourceIcon
            width: 28
            height: 28
            anchors.left: parent.left
            anchors.verticalCenter: parent.verticalCenter
            name: root.iconName
            iconSize: 26
            color: root.settingValue ? Theme.primary : Theme.outline
        }

        Column {
            anchors.left: sourceIcon.right
            anchors.leftMargin: Theme.spacingSm
            anchors.right: controls.left
            anchors.rightMargin: Theme.spacingSm
            anchors.verticalCenter: parent.verticalCenter
            spacing: 2

            Text {
                width: parent.width
                text: root.displayName
                color: Theme.onSurfaceColor
                font.family: Theme.fontFamily
                font.pixelSize: 14
                font.weight: Font.Medium
                elide: Text.ElideRight
            }

            Text {
                width: parent.width
                text: root.account
                      ? (I18n.tr("已登录 · ") + root.account.nickname
                         + (root.account.vip ? I18n.tr(" · 会员") : ""))
                      : root.description
                color: root.account ? Theme.primary : Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: 11
                elide: Text.ElideRight
            }
        }

        Row {
            id: controls
            anchors.right: parent.right
            anchors.verticalCenter: parent.verticalCenter
            spacing: Theme.spacingSm

            Item {
                width: 42
                height: 24
                anchors.verticalCenter: parent.verticalCenter

                Rectangle {
                    anchors.fill: parent
                    radius: height / 2
                    color: root.settingValue ? Theme.primary : Theme.surfaceContainerHighest
                    border.width: root.settingValue ? 0 : 2
                    border.color: Theme.outline
                }

                Rectangle {
                    width: root.settingValue ? 18 : 12
                    height: width
                    radius: width / 2
                    anchors.verticalCenter: parent.verticalCenter
                    x: root.settingValue ? parent.width - width - 3 : 6
                    color: root.settingValue ? Theme.onPrimaryColor : Theme.outline

                    Behavior on x {
                        NumberAnimation { duration: Theme.motionShort }
                    }
                }

                HoverHandler { cursorShape: Qt.PointingHandCursor }

                MouseArea {
                    anchors.fill: parent
                    onClicked: root.toggled(!root.settingValue)
                }
            }

            Rectangle {
                width: 92
                height: 30
                radius: Theme.radiusSm
                color: Theme.withAlpha(Theme.primary, 0.10)
                border.width: 1
                border.color: Theme.withAlpha(Theme.primary, 0.35)

                Row {
                    anchors.centerIn: parent
                    spacing: 4

                    NeriIcon {
                        name: root.account ? "logout" : root.loginButtonIcon
                        iconSize: 14
                        color: Theme.primary
                        anchors.verticalCenter: parent.verticalCenter
                    }
                    Text {
                        text: root.account ? I18n.tr("退出登录") : root.loginButtonText
                        color: Theme.primary
                        font.family: Theme.fontFamily
                        font.pixelSize: 12
                        font.weight: Font.Medium
                        anchors.verticalCenter: parent.verticalCenter
                    }
                }

                HoverHandler { cursorShape: Qt.PointingHandCursor }

                MouseArea {
                    anchors.fill: parent
                    onClicked: root.loginClicked()
                }
            }
        }
    }
}
