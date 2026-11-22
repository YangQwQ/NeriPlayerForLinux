import QtQuick
import QtQuick.Controls
import ".."
import "../library"

// 播放页右上角「更多」菜单：歌曲信息 / 编辑 / 获取 / 切换音质 / 收藏 / 统计 / 百科。
// 每项复用现有 API 或展示面板：
//  - 查看信息 → 共享 SongInfoDialog（Song 模型字段）
//  - 编辑信息 / 歌曲百科 → 占位面板（后端无写入/百科接口）
//  - 获取信息 → online.search 按关键词查询
//  - 切换音质 → player.switchQuality（quality 子任务契约，临时切当前歌）
//  - 收藏到歌单 → collections.list / addSongs（仅本地歌单）
//  - 播放统计 → stats.song（按 song.key 精确查询单曲统计）
Menu {
    id: menu

    property var currentSong: Player.song

    readonly property bool songAvailable: !!(menu.currentSong && (menu.currentSong.key || menu.currentSong.title))

    width: 240
    padding: Theme.spacingXs

    background: Rectangle {
        radius: Theme.radiusMd
        color: Theme.surfaceContainerHigh
        border.width: 1
        border.color: Theme.withAlpha(Theme.outlineVariant, 0.6)
    }

    /** 从右上角按钮锚点弹出，clamp 到父窗口以内。 */
    function openFor(anchor) {
        menu.currentSong = Player.song
        const point = anchor.mapToItem(menu.parent, anchor.width, anchor.height)
        const parentWidth = menu.parent ? menu.parent.width : 1180
        const parentHeight = menu.parent ? menu.parent.height : 720
        menu.x = Math.max(0, Math.min(point.x, Math.max(0, parentWidth - menu.width)))
        menu.y = Math.max(0, Math.min(point.y - menu.height, Math.max(0, parentHeight - menu.height)))
        menu.popup()
    }

    component ActionItem: MenuItem {
        id: actionItem

        padding: Theme.spacingMd
        implicitHeight: 44

        contentItem: Row {
            spacing: Theme.spacingSm
            anchors.verticalCenter: parent.verticalCenter

            NeriIcon {
                anchors.verticalCenter: parent.verticalCenter
                name: actionItem.iconName
                iconSize: 20
                color: actionItem.enabled ? Theme.onSurfaceVariantColor
                                          : Theme.withAlpha(Theme.onSurfaceVariantColor, 0.38)
            }

            Text {
                text: actionItem.text
                anchors.verticalCenter: parent.verticalCenter
                color: actionItem.enabled ? Theme.onSurfaceColor
                                          : Theme.withAlpha(Theme.onSurfaceColor, 0.38)
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("bodyMedium").size
            }
        }

        property string iconName: "info"

        background: Rectangle {
            radius: Theme.radiusSm
            color: actionItem.highlighted ? Theme.withAlpha(Theme.onSurfaceColor, 0.08) : "transparent"
        }
    }

    component Divider: MenuSeparator {
        contentItem: Rectangle {
            implicitWidth: 200
            implicitHeight: 1
            color: Theme.withAlpha(Theme.outlineVariant, 0.7)
        }
    }

    ActionItem {
        enabled: menu.songAvailable
        text: I18n.tr("查看歌曲信息")
        iconName: "info"
        onTriggered: infoDialog.openFor(menu.currentSong)
    }

    ActionItem {
        enabled: menu.songAvailable
        text: I18n.tr("编辑歌曲信息")
        iconName: "edit"
        onTriggered: editDialog.openFor(menu.currentSong)
    }

    ActionItem {
        enabled: menu.songAvailable
        text: I18n.tr("获取歌曲信息")
        iconName: "refresh"
        onTriggered: fetchDialog.openFor(menu.currentSong)
    }

    Divider {}

    ActionItem {
        enabled: menu.songAvailable
        text: I18n.tr("切换音质")
        iconName: "high_quality"
        onTriggered: qualityDialog.openFor(menu.currentSong)
    }

    ActionItem {
        enabled: menu.songAvailable
        text: I18n.tr("收藏到网易云歌单")
        iconName: "favorite"
        onTriggered: favoriteDialog.openFor(menu.currentSong)
    }

    ActionItem {
        enabled: menu.songAvailable
        text: I18n.tr("播放统计")
        iconName: "bar_chart"
        onTriggered: statsDialog.openFor(menu.currentSong)
    }

    ActionItem {
        enabled: menu.songAvailable
        text: I18n.tr("歌曲百科")
        iconName: "book"
        onTriggered: encyclopediaDialog.openFor(menu.currentSong)
    }

    // ---- 各功能面板：复用共享歌曲信息对话框，其它为播放页专用占位面板 ----
    SongInfoDialog {
        id: infoDialog
    }

    EditSongInfoPanel {
        id: editDialog
    }

    FetchSongInfoPanel {
        id: fetchDialog
    }

    QualitySwitchPanel {
        id: qualityDialog
    }

    NeteaseFavoritePanel {
        id: favoriteDialog
    }

    SongPlayStatsPanel {
        id: statsDialog
    }

    SongEncyclopediaPlaceholderPanel {
        id: encyclopediaDialog
    }
}
