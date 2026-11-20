import QtQuick
import ".."

// 设置页标签栏（共享组件）：Material 3 PrimaryScrollableTabRow 风格。
// 一行文字 Tab，选中项用 primary 色文字 + 底部 3dp 下划线指示条；
// 未选中项用 onSurfaceVariant 文字，hover 时浅色底。整行底部有一条通栏
// outlineVariant 细线，指示条压在细线上方。Tab 较多时用 Flickable 横向滚动，
// 不裁切内容。
//
// 接口对齐 components/library/TabBar.qml（FilterChip 风格），方便直接替换：
//   options: [{ value: "songs", label: "歌曲", icon: "music_note" }, ...]
//   value: "songs"
//   onSelected: function(v) { ... }
// 也支持 string[] 或 [{value,label}]，icon 可选。
Item {
    id: root

    property var options: []
    property var value: undefined
    property real horizontalPadding: Theme.spacingLg
    property real itemSpacing: Theme.spacingLg
    property real tabMinWidth: 72
    property real indicatorHeight: 3

    signal selected(var value)

    implicitHeight: 48

    function optionValue(option) {
        if (option !== null && typeof option === "object")
            return option.value
        return option
    }

    function optionLabel(option) {
        if (option !== null && typeof option === "object")
            return option.label !== undefined ? option.label : String(option.value)
        return String(option)
    }

    function optionIcon(option) {
        if (option !== null && typeof option === "object")
            return option.icon !== undefined ? option.icon : ""
        return ""
    }

    // 通栏分隔细线，位于最底层；指示条由 Flickable 内的 delegate 绘制在其上方。
    Rectangle {
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        height: 1
        color: Theme.outlineVariant
    }

    Flickable {
        id: flick

        anchors.fill: parent
        clip: true
        flickableDirection: Flickable.HorizontalFlick
        boundsBehavior: Flickable.StopAtBounds
        contentWidth: row.implicitWidth + root.horizontalPadding * 2
        contentHeight: root.height

        Row {
            id: row

            anchors.left: parent.left
            anchors.leftMargin: root.horizontalPadding
            anchors.verticalCenter: parent.verticalCenter
            height: root.height
            spacing: root.itemSpacing

            Repeater {
                model: root.options

                delegate: Item {
                    id: tab

                    required property var modelData

                    readonly property bool isSelected: String(root.value) === String(root.optionValue(tab.modelData))
                    readonly property string iconName: root.optionIcon(tab.modelData)

                    width: Math.max(root.tabMinWidth, tabContent.implicitWidth + Theme.spacingLg)
                    height: parent.height

                    // hover 浅色底（不含底部指示条区域）
                    Rectangle {
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.top: parent.top
                        anchors.bottom: parent.bottom
                        anchors.bottomMargin: root.indicatorHeight
                        radius: Theme.radiusSm
                        color: tabHover.hovered
                               ? Theme.withAlpha(Theme.onSurfaceColor, 0.06)
                               : "transparent"

                        Behavior on color {
                            ColorAnimation {
                                duration: Theme.motionShort
                            }
                        }
                    }

                    Row {
                        id: tabContent

                        anchors.horizontalCenter: parent.horizontalCenter
                        anchors.verticalCenter: parent.verticalCenter
                        anchors.verticalCenterOffset: -root.indicatorHeight
                        spacing: Theme.spacingSm

                        NeriIcon {
                            name: tab.iconName
                            visible: tab.iconName !== ""
                            iconSize: 18
                            height: 20
                            color: tab.isSelected ? Theme.primary : Theme.onSurfaceVariantColor
                        }

                        Text {
                            text: root.optionLabel(tab.modelData)
                            color: tab.isSelected ? Theme.primary : Theme.onSurfaceVariantColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("labelLarge").size
                            font.weight: tab.isSelected ? Font.DemiBold : Font.Medium
                        }
                    }

                    // 选中项底部指示条：压在通栏分隔细线上方
                    Rectangle {
                        anchors.horizontalCenter: parent.horizontalCenter
                        anchors.bottom: parent.bottom
                        width: Math.max(24, parent.width - Theme.spacingLg)
                        height: root.indicatorHeight
                        radius: root.indicatorHeight / 2
                        color: Theme.primary
                        visible: tab.isSelected
                    }

                    HoverHandler {
                        id: tabHover
                        cursorShape: Qt.PointingHandCursor
                    }

                    MouseArea {
                        anchors.fill: parent
                        onClicked: root.selected(root.optionValue(tab.modelData))
                    }
                }
            }
        }
    }
}
