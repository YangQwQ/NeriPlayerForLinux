import QtQuick
import QtQuick.Controls
import ".."

// 歌曲「更多」菜单（共享组件，QtQuick.Controls Menu）。
// 统一承载：下一首播放 / 添加到播放队列 / 添加到歌单 / 收藏 / 下载占位 /
// 从当前列表移除 / 上移 / 下移。
//
// 用法：
//   SongActionsMenu {
//       id: songMenu
//       onClickedRemoveRequested: ...   // 实际信号名为 onRemoveRequested
//   }
//   songMenu.openFor(song, { playlistId: "xxx", showMove: true, anchor: moreButton })
//
// 属性：
//   song        当前操作的 Song 对象
//   playlistId  非空时显示「从当前列表移除」，并在信号里回传（页面据此调用 collections.removeSong）
//   showMove    bool，显示「上移 / 下移」菜单项（歌单详情用）
//   favorite    当前收藏状态（打开时异步查询）
// 信号：
//   removeRequested(song)      从当前列表移除
//   moveRequested(song, delta) delta = -1 上移 / +1 下移
//   favoriteToggled(song, bool) 收藏状态切换后
//   infoRequested(song)        查看歌曲信息（由页面打开 SongInfoDialog）
Menu {
    id: root

    property var song: null
    property string playlistId: ""
    property bool showMove: false
    property bool favorite: false

    signal removeRequested(var song)
    signal moveRequested(var song, int delta)
    signal favoriteToggled(var song, bool favorite)
    signal infoRequested(var song)

    readonly property string songSource: root.song && root.song.source
                                         ? String(root.song.source).toUpperCase() : "LOCAL"
    readonly property string songAlbum: root.song && root.song.album ? String(root.song.album) : ""
    readonly property string songArtist: root.song && root.song.artist ? String(root.song.artist) : ""
    // 本地库按名字过滤；网易云/B 站可用名字搜索兜底；YouTube 必须带 id。
    readonly property bool albumLinkAvailable: root.songAlbum !== ""
                                               && (root.songSource !== "YOUTUBE" || !!(root.song && root.song.albumId))
    readonly property bool artistLinkAvailable: root.songArtist !== ""
                                                && (root.songSource !== "YOUTUBE" || !!(root.song && root.song.artistId))

    width: 240
    padding: Theme.spacingXs

    /** 打开专辑详情页；在线音源优先用 albumId，本地库按专辑名过滤。 */
    function openAlbum() {
        const song = root.song
        if (!song || root.songAlbum === "")
            return
        Nav.push("albumDetail", {
                     "source": root.songSource,
                     "id": song.albumId ? String(song.albumId) : root.songAlbum,
                     "name": root.songAlbum,
                     "coverUrl": song.artworkUrl ? String(song.artworkUrl) : "",
                     "creator": root.songArtist,
                     "trackCount": 0
                 })
    }

    /** 多位歌手的 "A / B" 串取第一位的名字（在线音源歌手页只对应一位歌手）。 */
    function primaryArtist(text) {
        const slash = String(text || "").indexOf("/")
        return slash > 0 ? String(text).substring(0, slash).trim() : String(text || "")
    }

    /** 打开统一歌手页；在线音源优先用 artistId，本地库按歌手名过滤。 */
    function openArtist() {
        const song = root.song
        if (!song || root.songArtist === "")
            return
        const name = root.songSource === "LOCAL" ? root.songArtist : primaryArtist(root.songArtist)
        Nav.push("remoteArtist", {
                     "source": root.songSource,
                     "id": song.artistId ? String(song.artistId) : name,
                     "name": name
                 })
    }

    /**
     * 加歌到播放队列；房间听众的加歌由后端自动转发成房间队列请求。
     *
     * 提示策略：
     *  - `roomQueue=true`：这次进的是**房间队列**，必须这么说——说成「已添加到下一首」
     *    是撒谎（本地队列随即被房间同步覆盖）；
     *  - 本地成功：后端 Player 已经自己弹了「已添加到下一首播放 / 已添加到播放队列」，
     *    这里不再重复弹，避免两条几乎相同的提示同时冒出来；
     *  - 失败：房间拒绝（如控制者关闭听众控制）时后端会把可操作的中文原因放在 error 里。
     */
    function enqueueSongs(songs, playNext) {
        const list = songs || []
        if (list.length === 0)
            return
        Api.call(playNext ? "player.enqueueNext" : "player.enqueue", { songs: list })
            .then(function(result) {
                if (result && result.roomQueue)
                    Toast.show(I18n.tr("已添加到房间队列"), "info")
            })
            .catch(function(error) {
                Toast.show(error && error.message ? error.message : I18n.tr("添加失败"), "error")
            })
    }

    /** 把当前歌曲加入下载队列（后端 download.enqueue，进度见下载面板）。 */
    function enqueueDownload() {
        const target = root.song
        if (!target)
            return
        Api.call("download.enqueue", { songs: [target] }).then(function(result) {
            const queued = result && result.queued !== undefined ? Number(result.queued) : 0
            if (result && result.message) {
                Toast.show(String(result.message), "error")
            } else if (queued > 0) {
                Toast.show(I18n.tr("已加入下载队列：") + (target.title || I18n.tr("歌曲")), "info")
            } else {
                Toast.show(I18n.tr("未能加入下载队列"), "error")
            }
        }).catch(function(error) {
            Toast.show(I18n.tr("下载失败：") + (error && error.message ? error.message : I18n.tr("未知错误")), "error")
        })
    }

    /** 打开菜单；options 可含 playlistId / showMove / anchor 或 x/y（用于定位）。 */
    function openFor(song, options) {
        if (!song)
            return
        root.song = song
        root.playlistId = options && options.playlistId ? String(options.playlistId) : ""
        root.showMove = !!(options && options.showMove)
        root.favorite = false

        Api.call("collections.isFavorite", { songKey: song.key }).then(function(result) {
            root.favorite = !!(result && result.favorite)
        }).catch(function() {})

        const opts = options || {}
        const parentWidth = root.parent ? root.parent.width : 1180
        const parentHeight = root.parent ? root.parent.height : 720
        if (opts.anchor) {
            const anchor = opts.anchor
            const point = anchor.mapToItem(root.parent, anchor.width, 0)
            root.x = Math.max(0, Math.min(point.x, Math.max(0, parentWidth - root.width)))
            root.y = Math.max(0, Math.min(point.y, Math.max(0, parentHeight - root.height)))
        } else if (opts.x !== undefined && opts.y !== undefined) {
            // 右键菜单：x/y 是调用方换算到菜单父项坐标系的鼠标位置，clamp 到父边界避免弹出屏外。
            root.x = Math.max(0, Math.min(Number(opts.x), Math.max(0, parentWidth - root.width)))
            root.y = Math.max(0, Math.min(Number(opts.y), Math.max(0, parentHeight - root.height)))
        }
        root.popup()
    }

    function toggleFavorite() {
        if (!root.song)
            return
        const song = root.song
        Api.call("collections.toggleFavorite", { song: song }).then(function(result) {
            root.favorite = !!(result && result.favorite)
            Toast.show(root.favorite ? I18n.tr("已收藏") : I18n.tr("已取消收藏"))
            root.favoriteToggled(song, root.favorite)
        }).catch(function(error) {
            Toast.show(error && error.message ? error.message : I18n.tr("操作失败"), "error")
        })
    }

    background: Rectangle {
        radius: Theme.radiusMd
        color: Theme.surfaceContainerHigh
        border.width: 1
        border.color: Theme.withAlpha(Theme.outlineVariant, 0.6)
    }

    // 统一的菜单项样式（M3 文本项：高 44、圆角 8、hover 叠 onSurface α0.08）
    component ActionItem: MenuItem {
        id: actionItem

        padding: Theme.spacingMd
        implicitHeight: 44

        contentItem: Text {
            text: actionItem.text
            leftPadding: Theme.spacingSm
            verticalAlignment: Text.AlignVCenter
            color: actionItem.enabled ? Theme.onSurfaceColor
                                     : Theme.withAlpha(Theme.onSurfaceColor, 0.38)
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodyMedium").size
        }

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
        text: I18n.tr("下一首播放")
        onTriggered: {
            if (root.song)
                root.enqueueSongs([root.song], true)
        }
    }

    ActionItem {
        text: I18n.tr("查看信息")
        onTriggered: {
            if (root.song)
                root.infoRequested(root.song)
        }
    }

    ActionItem {
        text: I18n.tr("添加到播放队列")
        onTriggered: {
            if (root.song)
                root.enqueueSongs([root.song], false)
        }
    }

    ActionItem {
        text: I18n.tr("添加到歌单…")
        onTriggered: {
            if (root.song)
                playlistPicker.openFor(root.song)
        }
    }

    ActionItem {
        text: root.favorite ? I18n.tr("取消收藏") : I18n.tr("收藏")
        onTriggered: root.toggleFavorite()
    }

    ActionItem {
        text: I18n.tr("下载")
        onTriggered: root.enqueueDownload()
    }

    Divider {
        visible: root.albumLinkAvailable || root.artistLinkAvailable
    }

    ActionItem {
        visible: root.albumLinkAvailable
        text: I18n.tr("查看专辑")
        onTriggered: root.openAlbum()
    }

    ActionItem {
        visible: root.artistLinkAvailable
        text: I18n.tr("查看歌手")
        onTriggered: root.openArtist()
    }

    Divider {
        visible: root.playlistId !== "" || root.showMove
    }

    ActionItem {
        visible: root.playlistId !== ""
        text: I18n.tr("从当前列表移除")
        onTriggered: {
            if (root.song)
                root.removeRequested(root.song)
        }
    }

    ActionItem {
        visible: root.showMove
        text: I18n.tr("上移")
        onTriggered: {
            if (root.song)
                root.moveRequested(root.song, -1)
        }
    }

    ActionItem {
        visible: root.showMove
        text: I18n.tr("下移")
        onTriggered: {
            if (root.song)
                root.moveRequested(root.song, 1)
        }
    }

    PlaylistPickerDialog {
        id: playlistPicker
    }
}
