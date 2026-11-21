import QtQuick
import "../library" as Library

// 首页「平铺网格」栏目容器：与 HomeSectionRow 同接口，但内容用 Grid 铺开而不是横向滚动。
// 仅在 HomePage 的 homeGridMode 开启时替换 HomeSectionRow 使用；加载/登录/错误逻辑仍在 HomePage。
// kind 语义与 HomeSectionRow 一致：songs / continue / collections / list / groups。
Item {
    id: root

    property string title: ""
    property string icon: ""
    property string kind: "songs"
    property var items: []
    property var groups: []
    property string actionText: ""

    property int currentGroupIndex: 0
    property int maxColumns: 6

    onGroupsChanged: {
        if (!groups || groups.length === 0)
            currentGroupIndex = 0
        else if (currentGroupIndex >= groups.length)
            currentGroupIndex = 0
    }

    readonly property bool isGroups: kind === "groups" && groups !== null && groups !== undefined
                                  && groups.length > 0
    readonly property var currentGroup: {
        if (!isGroups)
            return null
        const index = Math.max(0, Math.min(currentGroupIndex, groups.length - 1))
        return groups[index]
    }
    readonly property string effectiveKind: {
        if (!isGroups)
            return kind
        return currentGroup && currentGroup.kind === "collections" ? "collections" : "songs"
    }
    readonly property var effectiveItems: {
        if (isGroups) {
            if (!currentGroup)
                return []
            return effectiveKind === "collections"
                    ? (currentGroup.collections || [])
                    : (currentGroup.songs || [])
        }
        return items || []
    }
    // 网格模式展示更多内容：歌曲/歌单最多 24 项，继续播放最多 12 项。
    readonly property int maxItems: effectiveKind === "continue" ? 12 : 24
    readonly property int count: Math.min(effectiveItems.length, maxItems)
    readonly property var displayItems: effectiveItems.length > maxItems
                                     ? effectiveItems.slice(0, maxItems)
                                     : effectiveItems
    readonly property string effectiveAction: effectiveItems.length === 0
                                              ? ""
                                              : (effectiveKind === "collections"
                                                 ? I18n.tr("更多")
                                                 : (effectiveKind === "continue" ? I18n.tr("最近播放") : I18n.tr("播放全部")))

    readonly property var tabOptions: {
        const result = []
        if (!groups)
            return result
        for (let i = 0; i < groups.length; i++)
            result.push({ "value": i, "label": groups[i] && groups[i].title ? groups[i].title : "" })
        return result
    }

    readonly property real tabsHeight: isGroups ? 34 + Theme.spacingSm : 0

    // 卡片宽度：歌曲/歌单 150，继续播放 220（横向卡）。
    readonly property real cardWidth: effectiveKind === "continue" ? 220 : 150
    readonly property real gridSpacing: Theme.spacingMd

    // 列数随宽度自适应：宽屏 5~6 列，窄屏 2~3 列。
    readonly property int columns: {
        if (root.width <= 0)
            return 3
        const available = root.width + gridSpacing
        const fit = Math.floor(available / (cardWidth + gridSpacing))
        const capped = Math.min(root.maxColumns, fit)
        return Math.max(2, capped)
    }

    // 每张卡片统一按封面卡高度（约 204）计算，继续播放卡按 72。
    readonly property real cellHeight: effectiveKind === "continue" ? 72 : 204
    readonly property int rowCount: count > 0 ? Math.ceil(count / columns) : 0
    readonly property real gridHeight: rowCount > 0
                                     ? rowCount * cellHeight + (rowCount - 1) * gridSpacing
                                     : 0

    implicitHeight: 44 + Theme.spacingXs + tabsHeight + gridHeight

    signal actionClicked(string kind, var items)
    signal songActivated(var items, int index)
    signal songRightClicked(var song, var songs, int index)
    signal collectionActivated(var collection)

    Library.SectionHeader {
        id: sectionHeader

        width: root.width
        height: 44
        title: root.title
        icon: root.icon
        actionText: root.effectiveAction
        onActionClicked: root.actionClicked(root.effectiveKind, root.effectiveItems)
    }

    // tab 组切换（与 HomeSectionRow 保持一致）
    Item {
        id: tabsArea

        anchors.top: sectionHeader.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        height: root.tabsHeight
        visible: root.isGroups

        Flickable {
            id: tabsFlick

            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            height: 34
            contentWidth: Math.max(width, tabBar.width)
            contentHeight: 34
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            flickableDirection: Flickable.HorizontalFlick

            Library.TabBar {
                id: tabBar

                options: root.tabOptions
                value: root.currentGroupIndex
                onSelected: function(value) {
                    root.currentGroupIndex = Number(value)
                }
            }
        }
    }

    // ---- 平铺网格 ----
    Grid {
        id: cardGrid

        anchors.top: tabsArea.bottom
        anchors.topMargin: root.isGroups ? 0 : Theme.spacingXs
        anchors.horizontalCenter: parent.horizontalCenter
        // 宽度取实际列数所需，而不是铺满父项——否则 Grid 内容靠左排、右侧留白，左右边距不等。
        width: Math.min(root.width, root.columns * root.cardWidth + (root.columns - 1) * root.gridSpacing)
        columns: root.columns
        spacing: root.gridSpacing
        visible: root.count > 0

        Repeater {
            model: root.effectiveKind === "continue" ? root.displayItems : []

            delegate: ContinuePlayingCard {
                width: root.cardWidth
                height: implicitHeight
                song: modelData
                playing: Player.hasSong && Player.song !== null && Player.song.key === modelData.key && Player.playing
                onActivated: root.songActivated(root.displayItems, index)
                onRightClicked: function(song) { root.songRightClicked(song, root.displayItems, index) }
            }
        }

        Repeater {
            model: root.effectiveKind === "songs" ? root.displayItems : []

            delegate: TrackCard {
                width: root.cardWidth
                height: implicitHeight
                song: modelData
                playing: Player.hasSong && Player.song !== null && Player.song.key === modelData.key && Player.playing
                onActivated: root.songActivated(root.displayItems, index)
                onRightClicked: function(song) { root.songRightClicked(song, root.displayItems, index) }
            }
        }

        Repeater {
            model: root.effectiveKind === "collections" ? root.displayItems : []

            delegate: Library.CollectionCard {
                width: root.cardWidth
                height: implicitHeight
                cardWidth: root.cardWidth
                name: modelData.name ? modelData.name : ""
                subtitle: modelData.trackCount > 0 ? modelData.trackCount + I18n.tr(" 首")
                                                   : (modelData.creator ? modelData.creator : "")
                coverUrl: modelData.coverUrl ? modelData.coverUrl : ""
                onClicked: root.collectionActivated(modelData)
            }
        }
    }
}
