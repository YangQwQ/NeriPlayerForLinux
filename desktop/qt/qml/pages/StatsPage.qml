import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../components"
import "../components/explore"
import "../components/library"
import "../components/library/Format.js" as Format

// 播放统计页：周期（日/周/月/年/总）+ 排序（按次数/时长/最近）+ 三项汇总卡 + 排行列表。
// 数据来自 stats.periods / stats.summary / stats.top，stats.changed 事件到达后刷新。
DetailScaffold {
    id: page

    property var periods: []
    property var sorts: []
    property string period: "WEEK"
    property string sort: "PLAY_COUNT"
    property var summary: ({ plays: 0, listenMs: 0, trackCount: 0 })
    property var topSongs: []
    property bool loading: true
    property string errorText: ""

    readonly property string currentKey: Player.hasSong && Player.song ? Player.song.key : ""

    title: I18n.tr("播放统计")

    actions: [
        IconButton {
            icon: "delete"
            iconSize: 22
            size: 44
            tooltip: I18n.tr("清空统计")
            onClicked: clearDialog.openFor(I18n.tr("清空播放统计"),
                                           I18n.tr("确定要清空所有播放统计数据吗？此操作不可撤销。"),
                                           I18n.tr("清空"))
        }
    ]

    function reloadPeriods() {
        Api.get("stats.periods", {}, function(result, error) {
            if (error)
                return
            page.periods = result.periods || []
            page.sorts = result.sorts || []
            if (page.periods.length > 0) {
                let hasPeriod = false
                for (let i = 0; i < page.periods.length; i++) {
                    if (page.periods[i].value === page.period)
                        hasPeriod = true
                }
                if (!hasPeriod)
                    page.period = page.periods[0].value
            }
            if (page.sorts.length > 0) {
                let hasSort = false
                for (let i = 0; i < page.sorts.length; i++) {
                    if (page.sorts[i].value === page.sort)
                        hasSort = true
                }
                if (!hasSort)
                    page.sort = page.sorts[0].value
            }
        })
    }

    function reloadSummary() {
        Api.get("stats.summary", { period: page.period }, function(result, error) {
            if (error) {
                page.errorText = error.message || I18n.tr("加载统计汇总失败")
                return
            }
            page.errorText = ""
            page.summary = result.summary || { plays: 0, listenMs: 0, trackCount: 0 }
        })
    }

    function reloadTop() {
        Api.get("stats.top", { period: page.period, sort: page.sort, limit: 50 }, function(result, error) {
            page.loading = false
            if (error) {
                page.errorText = error.message || I18n.tr("加载排行失败")
                return
            }
            page.topSongs = result.songs || []
        })
    }

    function reloadAll() {
        page.loading = true
        page.reloadSummary()
        page.reloadTop()
    }

    // 播放统一走 PageActions：房间听众会被降级为「追加到房间队尾」并弹确认框。
    PageActions {
        id: actions
    }

    function playAt(index) {
        const songs = page.topSongs.map(function(item) { return item.song })
        if (songs.length === 0)
            return
        actions.playSongs(songs, Math.max(0, Math.min(index, songs.length - 1)))
    }

    function clearStats() {
        Api.call("stats.clear").then(function() {
            Toast.show(I18n.tr("播放统计已清空"))
            page.reloadAll()
        }).catch(function(error) {
            Toast.show(error && error.message ? error.message : I18n.tr("清空失败"), "error")
        })
    }

    function onStatsChanged() {
        page.reloadAll()
    }

    onPeriodChanged: page.reloadAll()
    onSortChanged: page.reloadTop()

    Component.onCompleted: {
        Api.on("stats.changed", page.onStatsChanged)
        page.reloadPeriods()
        page.reloadAll()
    }

    Component.onDestruction: {
        Api.off("stats.changed", page.onStatsChanged)
    }

    // 后端连接建立后补拉一次数据
    Connections {
        target: Backend
        function onConnectedChanged() {
            if (Backend.connected) {
                page.reloadPeriods()
                page.reloadAll()
            }
        }
    }

    LoadingState {
        anchors.fill: parent
        visible: page.loading && page.topSongs.length === 0
        text: I18n.tr("正在加载播放统计…")
    }

    ErrorCard {
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        visible: page.errorText !== ""
        message: page.errorText
        onRetryClicked: page.reloadAll()
    }

    ListView {
        id: topList

        anchors.fill: parent
        visible: page.errorText === ""
        clip: true
        model: page.topSongs
        boundsBehavior: Flickable.StopAtBounds
        bottomMargin: Theme.spacingXl

        header: Column {
            width: topList.width

            // 汇总卡：播放次数 / 收听时长 / 歌曲数
            RowLayout {
                x: Theme.spacingLg
                y: Theme.spacingSm
                width: topList.width - Theme.spacingLg * 2
                spacing: 10

                Rectangle {
                    Layout.fillWidth: true
                    implicitHeight: 78
                    radius: Theme.radiusLg
                    color: Theme.surfaceContainerLow

                    Column {
                        x: 14
                        anchors.verticalCenter: parent.verticalCenter
                        spacing: 4

                        Text {
                            text: I18n.tr("播放次数")
                            color: Theme.onSurfaceVariantColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("labelSmall").size
                        }

                        Text {
                            text: String(page.summary.plays || 0) + I18n.tr(" 次")
                            color: Theme.onSurfaceColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("titleLarge").size
                            font.weight: Font.DemiBold
                        }
                    }
                }

                Rectangle {
                    Layout.fillWidth: true
                    implicitHeight: 78
                    radius: Theme.radiusLg
                    color: Theme.surfaceContainerLow

                    Column {
                        x: 14
                        anchors.verticalCenter: parent.verticalCenter
                        spacing: 4

                        Text {
                            text: I18n.tr("收听时长")
                            color: Theme.onSurfaceVariantColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("labelSmall").size
                        }

                        Text {
                            text: Format.formatLongDuration(page.summary.listenMs || 0)
                            elide: Text.ElideRight
                            maximumLineCount: 1
                            width: parent.parent.width - 28
                            color: Theme.onSurfaceColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("titleLarge").size
                            font.weight: Font.DemiBold
                        }
                    }
                }

                Rectangle {
                    Layout.fillWidth: true
                    implicitHeight: 78
                    radius: Theme.radiusLg
                    color: Theme.surfaceContainerLow

                    Column {
                        x: 14
                        anchors.verticalCenter: parent.verticalCenter
                        spacing: 4

                        Text {
                            text: I18n.tr("歌曲数")
                            color: Theme.onSurfaceVariantColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("labelSmall").size
                        }

                        Text {
                            text: String(page.summary.trackCount || 0) + I18n.tr(" 首")
                            color: Theme.onSurfaceColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("titleLarge").size
                            font.weight: Font.DemiBold
                        }
                    }
                }
            }

            // 周期选择
            TabBar {
                y: Theme.spacingMd
                width: topList.width
                options: page.periods
                value: page.period
                onSelected: function(value) { page.period = value }
            }

            // 排序选择
            TabBar {
                y: Theme.spacingSm
                width: topList.width
                options: page.sorts
                value: page.sort
                onSelected: function(value) { page.sort = value }
            }

            SectionHeader {
                width: topList.width
                title: I18n.tr("最常播放")
                icon: "trending_up"
                trailingText: page.topSongs.length > 0 ? page.topSongs.length + I18n.tr(" 首") : ""
            }
        }

        delegate: Item {
            id: topDelegate

            property int itemIndex: index
            property var item: modelData

            width: topList.width
            height: 58

            SongRow {
                anchors.fill: parent
                song: topDelegate.item ? topDelegate.item.song : null
                index: topDelegate.itemIndex
                playing: page.currentKey !== "" && topDelegate.item
                         && topDelegate.item.song
                         && topDelegate.item.song.key === page.currentKey
                extraText: topDelegate.item
                           ? topDelegate.item.playCount + I18n.tr(" 次 · ")
                             + Format.formatLongDuration(topDelegate.item.listenMs)
                           : ""
                onActivated: function(index) { page.playAt(index) }
                onMoreClicked: function(song) { songMenu.openFor(song) }
                onRightClicked: function(song) { songMenu.openFor(song) }
            }
        }

        footer: EmptyState {
            width: topList.width
            visible: page.topSongs.length === 0 && !page.loading
            icon: "bar_chart"
            title: I18n.tr("当前时间范围内还没有播放统计")
            hint: I18n.tr("开始听歌后这里会显示统计数据")
        }
    }

    SongActionsMenu {
        id: songMenu
    }

    ConfirmDialog {
        id: clearDialog

        onConfirmed: page.clearStats()
    }
}
