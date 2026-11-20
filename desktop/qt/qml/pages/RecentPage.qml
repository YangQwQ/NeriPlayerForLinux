import QtQuick
import QtQuick.Controls
import "../components"
import "../components/library"
import "../components/library/Format.js" as Format

// 最近播放页（Nav.push("recent")）：history.list 的播放记录，支持清空历史。
DetailScaffold {
    id: page

    title: I18n.tr("最近播放")
    subtitle: page.entries.length > 0 ? I18n.tr("共 ") + page.entries.length + I18n.tr(" 条记录") : ""

    actions: [
        IconButton {
            icon: "delete"
            iconSize: 22
            size: 44
            tooltip: I18n.tr("清空历史")
            visible: page.entries.length > 0
            onClicked: confirmDialog.openFor(I18n.tr("清空最近播放"),
                                             I18n.tr("确定要清空全部最近播放记录吗？此操作不可恢复。"),
                                             I18n.tr("清空"))
        }
    ]

    property var entries: []
    property bool loading: true
    property string errorText: ""

    readonly property string currentKey: Player.hasSong && Player.song ? Player.song.key : ""

    function reload() {
        page.loading = true
        Api.get("history.list", {}, function(result, error) {
            page.loading = false
            if (error) {
                page.errorText = error.message || I18n.tr("加载最近播放失败")
                return
            }
            page.errorText = ""
            page.entries = result.entries || []
        })
    }

    function playEntry(index) {
        const songs = page.entries.map(function(entry) { return entry.song })
        if (songs.length === 0)
            return
        Player.setQueue(songs, Math.max(0, Math.min(index, songs.length - 1)), true)
    }

    function clearHistory() {
        Api.call("history.clear").then(function() {
            page.entries = []
            Toast.show(I18n.tr("最近播放已清空"))
        }).catch(function(error) {
            Toast.show(error && error.message ? error.message : I18n.tr("清空失败"), "error")
        })
    }

    function onHistoryChanged() {
        page.reload()
    }

    Component.onCompleted: {
        Api.on("history.changed", page.onHistoryChanged)
        page.reload()
    }

    Component.onDestruction: {
        Api.off("history.changed", page.onHistoryChanged)
    }

    // 后端连接建立后补拉一次数据（页面可能在后端连接前就已创建）
    Connections {
        target: Backend
        function onConnectedChanged() {
            if (Backend.connected)
                page.reload()
        }
    }

    LoadingState {
        anchors.fill: parent
        visible: page.loading && page.entries.length === 0
        text: I18n.tr("正在加载最近播放…")
    }

    ErrorCard {
        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        visible: page.errorText !== ""
        message: page.errorText
        onRetryClicked: page.reload()
    }

    ListView {
        id: entryList

        anchors.fill: parent
        visible: page.entries.length > 0
        clip: true
        model: page.entries
        boundsBehavior: Flickable.StopAtBounds
        bottomMargin: Theme.spacingXl

        delegate: Item {
            id: entryDelegate

            property int itemIndex: index
            property var item: modelData

            width: entryList.width
            height: 58

            SongRow {
                anchors.fill: parent
                song: entryDelegate.item ? entryDelegate.item.song : null
                index: entryDelegate.itemIndex
                showAlbum: false
                extraText: entryDelegate.item
                           ? Format.formatDateTime(entryDelegate.item.playedAt) + I18n.tr(" · 播放 ")
                             + entryDelegate.item.playCount + I18n.tr(" 次")
                           : ""
                playing: page.currentKey !== "" && entryDelegate.item && entryDelegate.item.song
                         && entryDelegate.item.song.key === page.currentKey
                onActivated: function(index) { page.playEntry(index) }
                onMoreClicked: function(song) { songMenu.openFor(song) }
                onRightClicked: function(song) { songMenu.openFor(song) }
            }
        }
    }

    EmptyState {
        anchors.fill: parent
        visible: !page.loading && page.errorText === "" && page.entries.length === 0
        icon: "history"
        title: I18n.tr("暂无最近播放")
        hint: I18n.tr("播放过的歌曲会出现在这里")
    }

    SongActionsMenu {
        id: songMenu

        onRemoveRequested: function(song) {
            Api.call("history.remove", { songKey: song.key }).then(function() {
                Toast.show(I18n.tr("已从最近播放移除"))
                page.reload()
            }).catch(function(error) {
                Toast.show(error && error.message ? error.message : I18n.tr("移除失败"), "error")
            })
        }
    }

    ConfirmDialog {
        id: confirmDialog

        onConfirmed: page.clearHistory()
    }
}
