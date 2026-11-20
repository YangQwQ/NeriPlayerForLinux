import QtQuick
import QtQuick.Controls
import "../"

// 设置页左侧分类导航（宽屏）或顶部分类条（窄屏）。
// 外观对齐 Tauri 参考版的 settings-sidebar：搜索框 + 分组图标/文字 + 选中态 M3 容器色。
// 这里只负责“显示什么 / 点了什么”，具体卡片切换由 SettingsPage 的 currentCategory 驱动。
Item {
    id: root

    property var categories: []
    property var results: []
    property string currentCategory: ""
    property string query: ""
    property bool compact: false

    signal categorySelected(string id)
    signal queryEdited(string text)
    signal searchSubmitted()
    signal searchResultSelected(int index)

    width: parent ? parent.width : 0
    implicitHeight: root.compact ? 76 : 900

    function revealActiveCategory() {
        if (!root.compact)
            return
        for (let i = 0; i < root.categories.length; i++) {
            if (root.categories[i].id === root.currentCategory) {
                navFlick.contentX = Math.max(0, i * 140 - navFlick.width / 2 + 66)
                break
            }
        }
    }

    onCurrentCategoryChanged: root.revealActiveCategory()
    Component.onCompleted: root.revealActiveCategory()

    Rectangle {
        anchors.fill: parent
        color: Theme.surfaceContainerLow
    }

    Column {
        id: layout
        anchors.fill: parent
        anchors.topMargin: root.compact ? Theme.spacingSm : Theme.spacingLg
        anchors.bottomMargin: Theme.spacingSm
        anchors.leftMargin: root.compact ? Theme.spacingSm : Theme.spacingSm
        anchors.rightMargin: root.compact ? Theme.spacingSm : Theme.spacingSm
        spacing: Theme.spacingSm

        // 标题 + 搜索框（窄屏标题压缩为一行，搜索框仍保留）。
        Text {
            id: sidebarTitle
            visible: !root.compact
            width: parent.width
            text: "设置"
            color: Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("headlineSmall").size
            font.weight: Font.DemiBold
        }

        Item {
            id: searchBox
            width: parent.width
            height: 42
            visible: !root.compact

            Rectangle {
                anchors.fill: parent
                radius: Theme.radiusMd
                color: searchField.activeFocus ? Theme.surfaceContainerHigh
                                               : Theme.withAlpha(Theme.surfaceContainerHigh, 0.82)
                border.width: searchField.activeFocus ? 2 : 1
                border.color: searchField.activeFocus ? Theme.primary : "transparent"
            }

            NeriIcon {
                id: searchIcon
                x: Theme.spacingMd
                anchors.verticalCenter: parent.verticalCenter
                name: "search"
                iconSize: 20
                color: Theme.onSurfaceVariantColor
            }

            TextField {
                id: searchField
                x: searchIcon.x + searchIcon.iconSize + Theme.spacingSm
                y: 0
                width: parent.width - x - Theme.spacingMd
                height: parent.height
                text: root.query
                placeholderText: "搜索设置…"
                placeholderTextColor: Theme.onSurfaceVariantColor
                color: Theme.onSurfaceColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("bodyMedium").size
                background: Item {}
                onTextChanged: root.queryEdited(text)
                onAccepted: root.searchSubmitted()
                Keys.onEscapePressed: {
                    text = ""
                    root.queryEdited("")
                }
            }
        }

        // 宽屏：纵向分类列表；窄屏：横向可滚动分类条。
        Flickable {
            id: navFlick
            width: parent.width
            height: root.compact ? parent.height
                                 : parent.height - sidebarTitle.height - searchBox.height
                                   - layout.spacing * 2
            contentWidth: root.compact ? Math.max(width, navRepeater.count * 140) : width
            contentHeight: root.compact ? height : navLayout.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            flickableDirection: root.compact ? Flickable.HorizontalFlick : Flickable.VerticalFlick

            Flow {
                id: navLayout
                width: root.compact ? navFlick.contentWidth : navFlick.width
                flow: root.compact ? Flow.LeftToRight : Flow.TopToBottom
                spacing: Theme.spacingSm

                Repeater {
                    id: navRepeater
                    model: root.query.trim() !== "" ? root.results : root.categories

                    delegate: Item {
                        required property var modelData
                        required property int index

                        width: root.compact ? 132 : navFlick.width
                        height: root.compact ? 52 : navItem.implicitHeight

                        Rectangle {
                            id: navItem
                            property bool active: !root.query.trim()
                                                  && root.currentCategory === modelData.id
                            property bool hovered: navHover.hovered

                            width: parent.width
                            implicitWidth: navRow.implicitWidth + Theme.spacingMd * 2
                            implicitHeight: 60
                            anchors.verticalCenter: root.compact ? parent.verticalCenter : undefined
                            radius: Theme.radiusMd
                            color: active ? Theme.secondaryContainer
                                          : (hovered ? Theme.withAlpha(Theme.onSurfaceColor, 0.06)
                                                     : "transparent")

                            Row {
                                id: navRow
                                anchors.left: parent.left
                                anchors.right: parent.right
                                anchors.verticalCenter: parent.verticalCenter
                                spacing: Theme.spacingMd

                                NeriIcon {
                                    width: 24
                                    anchors.verticalCenter: parent.verticalCenter
                                    name: modelData.icon || "settings"
                                    iconSize: 22
                                    color: navItem.active ? Theme.onSecondaryContainerColor
                                                          : Theme.primary
                                }

                                Column {
                                    width: root.compact ? 84
                                                        : Math.max(0, navRow.width - 24 - Theme.spacingMd * 3 - 20)
                                    anchors.verticalCenter: parent.verticalCenter
                                    spacing: 2

                                    Text {
                                        width: parent.width
                                        text: modelData.title
                                        color: navItem.active ? Theme.onSecondaryContainerColor
                                                              : Theme.onSurfaceColor
                                        font.family: Theme.fontFamily
                                        font.pixelSize: Theme.textStyle("titleSmall").size
                                        font.weight: Font.Medium
                                        elide: Text.ElideRight
                                    }

                                    Text {
                                        visible: !root.compact
                                        width: parent.width
                                        text: modelData.description || ""
                                        color: navItem.active ? Theme.withAlpha(Theme.onSecondaryContainerColor, 0.75)
                                                              : Theme.onSurfaceVariantColor
                                        font.family: Theme.fontFamily
                                        font.pixelSize: Theme.textStyle("labelSmall").size
                                        elide: Text.ElideRight
                                    }
                                }

                                NeriIcon {
                                    visible: !root.compact
                                    anchors.verticalCenter: parent.verticalCenter
                                    name: "chevron_right"
                                    iconSize: 18
                                    color: navItem.active ? Theme.onSecondaryContainerColor
                                                          : Theme.withAlpha(Theme.onSurfaceVariantColor, 0.7)
                                }
                            }

                            HoverHandler {
                                id: navHover
                                cursorShape: Qt.PointingHandCursor
                            }

                            MouseArea {
                                anchors.fill: parent
                                onClicked: {
                                    if (root.query.trim() !== "")
                                        root.searchResultSelected(index)
                                    else
                                        root.categorySelected(modelData.id)
                                }
                            }
                        }
                    }
                }

                Text {
                    visible: root.query.trim() !== "" && root.results.length === 0
                    width: navFlick.width
                    padding: Theme.spacingLg
                    horizontalAlignment: Text.AlignHCenter
                    text: "没有匹配的设置项"
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodySmall").size
                }
            }
        }
    }
}
