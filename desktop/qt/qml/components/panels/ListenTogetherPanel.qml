import QtQuick
import QtQuick.Controls
import ".."
import "../player"
import "../library"
import "../listentogether"

// 一起听（Listen Together）面板。
//
// 打开方式：Nav.openPanel("listenTogether")（集成方在 PlayerOverlays 里按名字挂载本文件）。
// 交互全部在这里完成：服务端地址配置 / 创建或加入房间 / 成员与角色 / 当前歌曲 /
// 控制按钮 / 房间队列增删序 / 房间设置 / 连接状态与错误。
// 协议见 desktop/docs/protocol-listen-together.md。
PanelShell {
    id: panel

    property var status: ({})
    property bool busy: false
    property bool editingServer: false
    property string commandError: ""

    // ---------------------------------------------------------------- 状态推导

    readonly property bool configured: panel.status.configured === true
    readonly property string connection: panel.status.connection ? String(panel.status.connection) : "disconnected"
    readonly property bool inRoom: {
        if (panel.status.roomId === undefined || panel.status.roomId === null)
            return false
        return String(panel.status.roomId) !== ""
    }
    readonly property bool isController: panel.status.isController === true
    readonly property string selfUuid: panel.status.userUuid ? String(panel.status.userUuid) : ""
    readonly property string roomId: panel.inRoom ? String(panel.status.roomId) : ""
    readonly property string roomStatus: panel.status.roomStatus ? String(panel.status.roomStatus) : ""
    readonly property var members: panel.inRoom ? panel.toArray(panel.status.members) : []
    readonly property var roomQueue: panel.inRoom ? panel.toArray(panel.status.queue) : []
    readonly property var track: panel.inRoom && panel.status.track !== undefined && panel.status.track !== null
                                ? panel.status.track : null
    readonly property var playback: panel.inRoom && panel.status.playback !== undefined && panel.status.playback !== null
                                    ? panel.status.playback : ({})
    readonly property var settings: panel.inRoom && panel.status.settings !== undefined && panel.status.settings !== null
                                    ? panel.status.settings : null
    readonly property string inviteLink: panel.status.inviteLink ? String(panel.status.inviteLink) : ""
    readonly property bool roomPlaying: String(panel.playback.state || "") === "playing"
    readonly property real playbackDurationMs: {
        const byPlayback = Number(panel.playback.durationMs || 0)
        if (byPlayback > 0)
            return byPlayback
        return panel.track !== null ? Number(panel.track.durationMs || 0) : 0
    }
    readonly property real roomPositionMs: Number(panel.playback.positionMs || 0)
    readonly property string defaultServerUrl: panel.status.defaultServerUrl
                                               ? String(panel.status.defaultServerUrl)
                                               : "https://neriplayer.hancat.work"
    readonly property bool allowMemberControl: panel.settings !== null && panel.settings.allowMemberControl === true
    readonly property bool autoPauseOnMemberChange: panel.settings !== null
                                                    && panel.settings.autoPauseOnMemberChange === true
    readonly property bool shareAudioLinks: panel.settings !== null && panel.settings.shareAudioLinks === true

    // 房间共享流状态：后端在 ListenTogetherSnapshot 里暴露（字段名见汇报）。
    // usingSharedStream = true 表示听众当前正用控制者分享的播放地址；
    // sharedStreamAvailable = false 表示控制者开启了共享但当前曲目没有可用地址（已回退本机解析）。
    readonly property bool usingSharedStream: panel.status.usingSharedStream === true
                                              || panel.status.usingSharedAudioUrl === true
    readonly property bool sharedStreamAvailable: panel.status.sharedStreamAvailable === true
                                                  || panel.status.sharedAudioUrlAvailable === true
    // 房间当前曲目是否带共享地址（快照字段缺失时用 streamUrl / streamUrls 兜底判断）。
    readonly property bool trackHasSharedUrl: {
        if (panel.track === null)
            return false
        if (panel.track.streamUrl !== undefined && panel.track.streamUrl !== null
                && String(panel.track.streamUrl) !== "")
            return true
        return panel.toArray(panel.track.streamUrls).length > 0
    }
    readonly property bool sharedStreamRequested: !panel.isController && panel.shareAudioLinks && panel.track !== null
    readonly property bool sharedStreamUnavailable: panel.sharedStreamRequested
                                                    && panel.sharedStreamAvailable === false
    readonly property bool sharedStreamInUse: panel.sharedStreamRequested
                                              && (panel.usingSharedStream || (panel.sharedStreamAvailable && panel.trackHasSharedUrl))
    readonly property string sharedStreamHint: {
        if (!panel.sharedStreamRequested)
            return ""
        if (panel.usingSharedStream)
            return I18n.tr("正在使用房间共享的音频地址")
        if (panel.sharedStreamUnavailable)
            return I18n.tr("共享地址不可用，已回退本机解析")
        if (panel.sharedStreamAvailable && panel.trackHasSharedUrl)
            return I18n.tr("正在使用房间共享的音频地址")
        return ""
    }

    readonly property string trackTitle: panel.track !== null && panel.track.name !== undefined
                                         ? String(panel.track.name) : I18n.tr("暂无歌曲")
    readonly property string trackArtist: panel.track !== null && panel.track.artist !== undefined
                                          ? String(panel.track.artist) : ""
    readonly property string trackAlbum: panel.track !== null && panel.track.album !== undefined
                                         && panel.track.album !== null ? String(panel.track.album) : ""
    readonly property string trackCover: panel.track !== null && panel.track.coverUrl !== undefined
                                         && panel.track.coverUrl !== null ? String(panel.track.coverUrl) : ""

    readonly property string errorText: {
        if (panel.commandError !== "")
            return panel.commandError
        if (panel.status.error !== undefined && panel.status.error !== null && String(panel.status.error) !== "")
            return String(panel.status.error)
        return ""
    }
    readonly property string noticeText: panel.status.notice !== undefined && panel.status.notice !== null
                                         ? String(panel.status.notice) : ""

    // ---------------------------------------------------------------- 工具函数

    function toArray(value) {
        if (value === undefined || value === null)
            return []
        const copy = []
        const length = value.length !== undefined ? Number(value.length) : 0
        for (let i = 0; i < length; ++i)
            copy.push(value[i])
        return copy
    }

    function messageOf(error) {
        if (error && error.message)
            return String(error.message)
        if (typeof error === "string" && error !== "")
            return error
        return I18n.tr("未知错误")
    }

    function formatTime(ms) {
        const total = Math.max(0, Math.floor(Number(ms || 0) / 1000))
        const minutes = Math.floor(total / 60)
        const seconds = total % 60
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds
    }

    function roleLabel(member) {
        return member && String(member.role || "") === "controller" ? I18n.tr("控制者") : I18n.tr("听众")
    }

    // ---------------------------------------------------------------- 命令调用

    function refresh() {
        Api.get("listenTogether.status", {}, function(result, error) {
            if (!error && result && typeof result === "object")
                panel.status = result
        })
    }

    function refreshQuiet() {
        if (panel.busy)
            return
        Api.get("listenTogether.status", {}, function(result, error) {
            if (!error && result && typeof result === "object")
                panel.status = result
        })
    }

    function run(method, params, done) {
        panel.busy = true
        panel.commandError = ""
        Api.get(method, params || {}, function(result, error) {
            panel.busy = false
            if (error) {
                panel.commandError = panel.messageOf(error)
                if (done)
                    done(null)
                return
            }
            if (result && typeof result === "object")
                panel.status = result
            if (done)
                done(result)
        })
    }

    function saveServer() {
        panel.run("listenTogether.configure", {
                      "serverUrl": serverField.text,
                      "nickname": nicknameField.text
                  }, function(result) {
                      if (result && result.configured === true)
                          panel.editingServer = false
                  })
    }

    function createRoom() {
        panel.run("listenTogether.createRoom", {})
    }

    function joinRoom() {
        const target = joinField.text.trim()
        if (target === "") {
            panel.commandError = I18n.tr("请先粘贴邀请链接或输入房间号")
            return
        }
        panel.run("listenTogether.joinRoom", { "inviteOrRoomId": target })
    }

    function leaveRoom() {
        panel.run("listenTogether.leaveRoom", {})
    }

    function requestControl(action) {
        panel.run("listenTogether.requestControl", { "action": action })
    }

    function requestControlWithPosition(action, positionMs) {
        panel.run("listenTogether.requestControl", {
                      "action": action,
                      "positionMs": Math.max(0, Math.round(positionMs))
                  })
    }

    function updateSetting(key, value) {
        const params = {}
        params[key] = value
        panel.run("listenTogether.updateSettings", params)
    }

    function togglePlay() {
        if (panel.isController)
            Player.togglePlayPause()
        else
            panel.requestControl(panel.roomPlaying ? "pause" : "play")
    }

    function skipNext() {
        if (panel.isController)
            Player.next()
        else
            panel.requestControl("next")
    }

    function skipPrevious() {
        if (panel.isController)
            Player.previous()
        else
            panel.requestControl("previous")
    }

    function seekToRatio(ratio) {
        if (panel.playbackDurationMs <= 0)
            return
        const positionMs = Math.round(panel.playbackDurationMs * Math.max(0, Math.min(1, ratio)))
        if (panel.isController)
            Player.seekTo(positionMs)
        else
            panel.requestControlWithPosition("seek", positionMs)
    }

    function syncProgress() {
        if (panel.isController) {
            panel.run("listenTogether.sendControl", { "type": "HEARTBEAT" })
            Toast.show(I18n.tr("已把本地进度同步到房间"), "info")
        } else {
            Player.seekTo(panel.roomPositionMs)
            Toast.show(I18n.tr("已对齐到房间进度"), "info")
        }
    }

    function copyInviteLink() {
        if (panel.inviteLink === "")
            return
        clipboardHelper.text = panel.inviteLink
        clipboardHelper.selectAll()
        clipboardHelper.copy()
        Toast.show(I18n.tr("邀请链接已复制"), "info")
    }

    // ---------------------------------------------------------------- 房间队列增删

    /**
     * 房间队列是否可编辑：控制者改本地队列后由后端 publishLocalState 同步，
     * 听众则需要服务端允许成员控制（action 只对听众有效，控制者调会被拒绝）。
     */
    readonly property bool roomQueueEditable: panel.connection === "connected" && panel.inRoom
                                              && (panel.isController || panel.allowMemberControl)

    /** 房间曲目 / 播放器歌曲的来源 → 一起听协议的 channelId。 */
    function queueChannel(source) {
        switch (String(source || "").toUpperCase()) {
        case "NETEASE":
            return "netease"
        case "BILIBILI":
            return "bilibili"
        case "YOUTUBE":
            return "youtubeMusic"
        default:
            return "local"
        }
    }

    /**
     * 歌曲身份 "channelId:audioId"，用于判断是否已在房间队列里。
     * 房间曲目用 channelId/audioId，播放器歌曲用 source/remoteId，两者在此对齐。
     */
    function songIdentity(item) {
        if (!item)
            return ""
        const channel = item.channelId !== undefined && item.channelId !== null
                        ? String(item.channelId) : panel.queueChannel(item.source)
        const audio = item.audioId !== undefined && item.audioId !== null
                      ? String(item.audioId) : String(item.remoteId || "")
        return channel + ":" + audio
    }

    /** 本地歌曲、缺远端 ID 的歌曲以及 YouTube 歌曲不能进入房间（后端会直接拒绝）。 */
    function canShareToRoom(song) {
        if (!song)
            return false
        const source = String(song.source || "LOCAL").toUpperCase()
        if (source === "LOCAL" || source === "YOUTUBE")
            return false
        return String(song.remoteId || "") !== ""
    }

    readonly property var roomQueueIdentities: {
        const list = []
        for (let i = 0; i < panel.roomQueue.length; ++i)
            list.push(panel.songIdentity(panel.roomQueue[i]))
        return list
    }

    /**
     * 房间曲目 → 本地播放队列下标。
     * 控制者的房间队列是从本地队列里过滤出来的（本地歌曲不入房间），
     * 所以房间下标与本地下标通常不同，改本地队列前必须先换算。
     */
    function localQueueIndexOf(track) {
        if (!track)
            return -1
        const identity = panel.songIdentity(track)
        const queue = Player.queue !== undefined && Player.queue !== null ? Player.queue : []
        for (let i = 0; i < queue.length; ++i) {
            if (panel.songIdentity(queue[i]) === identity)
                return i
        }
        return -1
    }

    function addToRoomQueue(song) {
        if (!song || panel.busy)
            return
        if (!panel.canShareToRoom(song)) {
            Toast.show(I18n.tr("该歌曲不能加入一起听（本地歌曲或缺少远端 ID）"), "error")
            return
        }
        if (panel.roomQueueIdentities.indexOf(panel.songIdentity(song)) >= 0) {
            Toast.show(I18n.tr("该歌曲已在房间队列中"), "info")
            return
        }
        if (panel.isController) {
            Player.enqueue([song])
            Toast.show(I18n.tr("已添加到房间队列"), "info")
            return
        }
        // 听众走 requestControl，成功提示等后端快照回来再给
        panel.run("listenTogether.requestControl",
                  { "action": "addToQueue", "track": song },
                  function(result) {
                      if (result)
                          Toast.show(I18n.tr("已添加到房间队列"), "info")
                  })
    }

    function removeFromRoomQueue(index) {
        if (panel.busy || index < 0 || index >= panel.roomQueue.length || panel.roomQueue.length <= 1)
            return
        if (panel.isController) {
            const localIndex = panel.localQueueIndexOf(panel.roomQueue[index])
            if (localIndex < 0)
                return
            Player.removeFromQueue(localIndex)
            return
        }
        panel.run("listenTogether.requestControl", { "action": "removeFromQueue", "index": index })
    }

    function moveInRoomQueue(from, to) {
        if (panel.busy || from === to || from < 0 || to < 0
                || from >= panel.roomQueue.length || to >= panel.roomQueue.length)
            return
        if (panel.isController) {
            const localFrom = panel.localQueueIndexOf(panel.roomQueue[from])
            const localTo = panel.localQueueIndexOf(panel.roomQueue[to])
            if (localFrom < 0 || localTo < 0)
                return
            Player.moveInQueue(localFrom, localTo)
            return
        }
        panel.run("listenTogether.requestControl",
                  { "action": "moveInQueue", "fromIndex": from, "toIndex": to })
    }

    /** 只展示队列前 5 首；「查看全部」在面板内展开完整列表。 */
    property bool queueExpanded: false

    function openAddSongs() {
        addSongsPanel.openFor()
    }

    function confirmLeaveRoom() {
        leaveConfirm.openFor(I18n.tr("离开房间"),
                             panel.isController
                             ? I18n.tr("你是控制者，离开后房间将由服务端关闭，其他成员会断开。确定要离开吗？")
                             : I18n.tr("确定要离开这个房间吗？离开后需要重新加入才能继续一起听。"),
                             I18n.tr("离开"))
    }

    // ---------------------------------------------------------------- 生命周期

    title: I18n.tr("一起听")
    cardWidth: 440

    PanelButton {
        visible: panel.inRoom
        text: I18n.tr("离开")
        chip: true
        enabledButton: !panel.busy
        onClicked: panel.confirmLeaveRoom()
    }

    Component.onCompleted: {
        Api.on("listenTogether.state", onListenTogetherState)
        panel.refresh()
    }

    Component.onDestruction: Api.off("listenTogether.state", onListenTogetherState)

    function onListenTogetherState(data) {
        if (data && typeof data === "object")
            panel.status = data
    }

    // 面板可能在 Backend 连上之前就被打开（--panel 启动测试），连上后重新拉数据
    property Connections backendConnection: Connections {
        target: Backend

        function onConnectedChanged() {
            if (Backend.connected)
                panel.refresh()
        }
    }

    // 房间内每秒刷新一次服务端推算的进度；不在房间时不轮询
    property Timer refreshTimer: Timer {
        interval: 1200
        repeat: true
        running: panel.visible && panel.inRoom && !panel.busy
        onTriggered: panel.refreshQuiet()
    }

    content: Flickable {
        id: flick

        anchors.fill: parent
        clip: true
        contentWidth: width
        contentHeight: contentColumn.height + Theme.spacingLg * 2
        boundsBehavior: Flickable.StopAtBounds

        // 房间队列增删 / 离开房间的弹窗。PanelShell 的默认属性是标题栏操作区，
        // 所以这两个 Popup 挂在 content 内部，避免被当成标题栏按钮。
        Item {
            id: dialogHost

            width: 0
            height: 0

            ListenTogetherAddSongsPanel {
                id: addSongsPanel

                host: panel
                canAdd: panel.roomQueueEditable
                onAddRequested: function(song) { panel.addToRoomQueue(song) }
            }

            ConfirmDialog {
                id: leaveConfirm

                onConfirmed: panel.leaveRoom()
            }
        }

        Column {
            id: contentColumn

            x: Theme.spacingLg
            y: Theme.spacingMd
            width: flick.width - Theme.spacingLg * 2
            spacing: Theme.spacingMd

            TextEdit {
                id: clipboardHelper
                visible: false
                width: 0
                height: 0
            }

            // ------------------------------------------------ 错误 / 提示横幅
            Rectangle {
                width: parent.width
                height: bannerColumn.implicitHeight + Theme.spacingMd
                radius: Theme.radiusMd
                visible: panel.errorText !== "" || panel.noticeText !== ""
                color: panel.errorText !== ""
                       ? Theme.withAlpha(Theme.error, 0.10)
                       : Theme.withAlpha(Theme.primary, 0.08)

                Row {
                    id: bannerColumn

                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.leftMargin: Theme.spacingMd
                    anchors.rightMargin: Theme.spacingMd
                    spacing: Theme.spacingSm

                    NeriIcon {
                        name: panel.errorText !== "" ? "error" : "info"
                        iconSize: 18
                        color: panel.errorText !== "" ? Theme.error : Theme.primary
                        anchors.verticalCenter: parent.verticalCenter
                    }

                    Text {
                        width: parent.width - 26
                        text: panel.errorText !== "" ? panel.errorText : panel.noticeText
                        color: panel.errorText !== "" ? Theme.error : Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("bodySmall").size
                        wrapMode: Text.WordWrap
                        anchors.verticalCenter: parent.verticalCenter
                    }
                }
            }

            // ------------------------------------------------ A. 服务端配置
            Column {
                width: parent.width
                spacing: Theme.spacingSm
                visible: !panel.configured || panel.editingServer

                Text {
                    text: panel.configured ? I18n.tr("修改一起听服务端") : I18n.tr("连接一起听服务端")
                    color: Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("titleSmall").size
                    font.weight: Font.DemiBold
                }

                Text {
                    width: parent.width
                    text: I18n.tr("一起听需要一个 LTW 服务端（可自建 NeriPlayer-LTW，或使用示例部署）。")
                          + I18n.tr("桌面版与 Android 客户端共用同一套协议，可以进同一个房间。")
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodySmall").size
                    wrapMode: Text.WordWrap
                }

                LtwTextField {
                    id: serverField

                    width: parent.width
                    // 未配置时直接填后端给的默认服务端，用户点「保存并连接」即可；仍可手动改。
                    text: {
                        const saved = panel.status.serverUrl !== undefined
                                      ? String(panel.status.serverUrl || "") : ""
                        return saved !== "" ? saved : panel.defaultServerUrl
                    }
                    placeholderText: panel.defaultServerUrl
                }

                LtwTextField {
                    id: nicknameField

                    width: parent.width
                    text: panel.status.nickname !== undefined ? String(panel.status.nickname || "") : ""
                    placeholderText: I18n.tr("昵称（1-24 位中文/字母/数字，留空自动生成）")
                }

                Row {
                    spacing: Theme.spacingSm

                    PanelButton {
                        text: panel.busy ? I18n.tr("连接中…") : I18n.tr("保存并连接")
                        filled: true
                        enabledButton: !panel.busy
                        onClicked: panel.saveServer()
                    }

                    PanelButton {
                        text: I18n.tr("取消")
                        visible: panel.configured
                        enabledButton: !panel.busy
                        onClicked: panel.editingServer = false
                    }
                }

                Text {
                    width: parent.width
                    text: I18n.tr("示例服务端：") + panel.defaultServerUrl
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelSmall").size
                    elide: Text.ElideRight
                }
            }

            // ------------------------------------------------ B. 大厅：创建 / 加入
            Column {
                width: parent.width
                spacing: Theme.spacingMd
                visible: panel.configured && !panel.editingServer && !panel.inRoom

                // 已连接服务端摘要
                Rectangle {
                    width: parent.width
                    height: serverSummary.implicitHeight + Theme.spacingMd
                    radius: Theme.radiusLg
                    color: Theme.surfaceContainerHigh

                    Row {
                        id: serverSummary

                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        anchors.leftMargin: Theme.spacingMd
                        anchors.rightMargin: Theme.spacingMd
                        spacing: Theme.spacingSm

                        NeriIcon {
                            name: "cloud_sync"
                            iconSize: 20
                            color: Theme.onSurfaceVariantColor
                            anchors.verticalCenter: parent.verticalCenter
                        }

                        Column {
                            width: parent.width - 20 - editServerButton.width - Theme.spacingSm * 2
                            anchors.verticalCenter: parent.verticalCenter
                            spacing: 2

                            Text {
                                width: parent.width
                                text: panel.status.serverUrl !== undefined
                                      ? String(panel.status.serverUrl || "") : ""
                                color: Theme.onSurfaceColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("bodyMedium").size
                                elide: Text.ElideRight
                            }

                            Text {
                                width: parent.width
                                text: panel.inRoom
                                      ? (panel.isController ? I18n.tr("当前身份：控制者") : I18n.tr("当前身份：听众"))
                                      : I18n.tr("未加入房间")
                                color: Theme.onSurfaceVariantColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("labelSmall").size
                            }
                        }

                        PanelButton {
                            id: editServerButton

                            text: I18n.tr("修改")
                            chip: true
                            anchors.verticalCenter: parent.verticalCenter
                            onClicked: panel.editingServer = true
                        }
                    }
                }

                // 创建房间
                Rectangle {
                    width: parent.width
                    height: createColumn.implicitHeight + Theme.spacingMd * 2
                    radius: Theme.radiusLg
                    color: Theme.surfaceContainerHigh

                    Column {
                        id: createColumn

                        x: Theme.spacingMd
                        y: Theme.spacingMd
                        width: parent.width - Theme.spacingMd * 2
                        spacing: Theme.spacingSm

                        Text {
                            text: I18n.tr("创建房间")
                            color: Theme.onSurfaceColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("titleSmall").size
                            font.weight: Font.DemiBold
                        }

                        Text {
                            width: parent.width
                            text: I18n.tr("以当前播放的在线歌曲和播放队列创建房间，你是控制者。")
                                  + I18n.tr("本地歌曲不能进入房间。")
                            color: Theme.onSurfaceVariantColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("bodySmall").size
                            wrapMode: Text.WordWrap
                        }

                        Row {
                            spacing: Theme.spacingSm

                            NeriIcon {
                                name: Player.hasSong ? "music_note" : "error"
                                iconSize: 16
                                color: Player.hasSong ? Theme.primary : Theme.onSurfaceVariantColor
                                anchors.verticalCenter: parent.verticalCenter
                            }

                            Text {
                                width: createColumn.width - 24
                                text: Player.hasSong
                                      ? (I18n.tr("当前歌曲：") + Player.title + " - " + Player.artist)
                                      : I18n.tr("当前没有播放中的歌曲，请先播放一首在线歌曲")
                                color: Player.hasSong ? Theme.onSurfaceColor
                                                      : Theme.onSurfaceVariantColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("bodySmall").size
                                elide: Text.ElideRight
                                anchors.verticalCenter: parent.verticalCenter
                            }
                        }

                        PanelButton {
                            text: panel.busy ? I18n.tr("创建中…") : I18n.tr("创建房间")
                            filled: true
                            enabledButton: !panel.busy
                            onClicked: panel.createRoom()
                        }
                    }
                }

                // 加入房间
                Rectangle {
                    width: parent.width
                    height: joinColumn.implicitHeight + Theme.spacingMd * 2
                    radius: Theme.radiusLg
                    color: Theme.surfaceContainerHigh

                    Column {
                        id: joinColumn

                        x: Theme.spacingMd
                        y: Theme.spacingMd
                        width: parent.width - Theme.spacingMd * 2
                        spacing: Theme.spacingSm

                        Text {
                            text: I18n.tr("加入房间")
                            color: Theme.onSurfaceColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("titleSmall").size
                            font.weight: Font.DemiBold
                        }

                        Text {
                            width: parent.width
                            text: I18n.tr("粘贴对方分享的邀请链接，或输入 6 位房间号。")
                                  + I18n.tr("首次加入需要邀请链接（链接里带有加入密钥），房间号适用于重连已加入的房间。")
                            color: Theme.onSurfaceVariantColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("bodySmall").size
                            wrapMode: Text.WordWrap
                        }

                        LtwTextField {
                            id: joinField

                            width: parent.width
                            placeholderText: I18n.tr("neriplayer://listen-together/join?… 或 6 位房间号")
                            onAccepted: panel.joinRoom()
                        }

                        PanelButton {
                            text: panel.busy ? I18n.tr("加入中…") : I18n.tr("加入房间")
                            filled: true
                            enabledButton: !panel.busy
                            onClicked: panel.joinRoom()
                        }
                    }
                }
            }

            // ------------------------------------------------ C. 房间内
            Column {
                width: parent.width
                spacing: Theme.spacingMd
                visible: panel.inRoom && !panel.editingServer

                // 房间头部
                Rectangle {
                    width: parent.width
                    height: roomHeader.implicitHeight + Theme.spacingMd * 2
                    radius: Theme.radiusLg
                    color: Theme.surfaceContainerHigh

                    Column {
                        id: roomHeader

                        x: Theme.spacingMd
                        y: Theme.spacingMd
                        width: parent.width - Theme.spacingMd * 2
                        spacing: Theme.spacingSm

                        Item {
                            width: parent.width
                            height: 30

                            Text {
                                anchors.left: parent.left
                                anchors.verticalCenter: parent.verticalCenter
                                text: I18n.tr("房间 ") + panel.roomId
                                color: Theme.onSurfaceColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("titleSmall").size
                                font.weight: Font.DemiBold
                            }

                            LtwStatusChip {
                                anchors.right: parent.right
                                anchors.verticalCenter: parent.verticalCenter
                                connection: panel.connection
                                inRoom: panel.inRoom
                                roomStatus: panel.roomStatus
                            }
                        }

                        Row {
                            spacing: Theme.spacingSm

                            Rectangle {
                                height: 24
                                width: roleChipText.implicitWidth + 16
                                radius: 12
                                color: panel.isController
                                       ? Theme.withAlpha(Theme.tertiary, 0.16)
                                       : Theme.withAlpha(Theme.primary, 0.12)

                                Text {
                                    id: roleChipText

                                    anchors.centerIn: parent
                                    text: panel.isController ? I18n.tr("控制者") : I18n.tr("听众")
                                    color: panel.isController ? Theme.tertiary : Theme.primary
                                    font.family: Theme.fontFamily
                                    font.pixelSize: Theme.textStyle("labelSmall").size
                                    font.weight: Font.Medium
                                }
                            }

                            Text {
                                text: panel.roomStatus === "controller_offline"
                                      ? I18n.tr("控制者离线，等待重连…")
                                      : (panel.isController ? I18n.tr("你在控制整个房间的播放")
                                                            : I18n.tr("跟随控制者同步播放"))
                                color: Theme.onSurfaceVariantColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("labelSmall").size
                                anchors.verticalCenter: parent.verticalCenter
                            }
                        }

                        // 邀请链接（仅控制者持有加入密钥）
                        Column {
                            width: parent.width
                            spacing: 4
                            visible: panel.isController && panel.inviteLink !== ""

                            Text {
                                text: I18n.tr("邀请链接")
                                color: Theme.onSurfaceVariantColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("labelSmall").size
                            }

                            Row {
                                width: parent.width
                                spacing: Theme.spacingSm

                                Rectangle {
                                    width: parent.width - copyButton.width - Theme.spacingSm
                                    height: 40
                                    radius: Theme.radiusSm
                                    color: "transparent"
                                    border.width: 1
                                    border.color: Theme.outlineVariant

                                    Text {
                                        anchors.left: parent.left
                                        anchors.right: parent.right
                                        anchors.verticalCenter: parent.verticalCenter
                                        anchors.leftMargin: 12
                                        anchors.rightMargin: 12
                                        text: panel.inviteLink
                                        color: Theme.onSurfaceColor
                                        font.family: Theme.fontFamily
                                        font.pixelSize: Theme.textStyle("labelSmall").size
                                        elide: Text.ElideRight
                                    }
                                }

                                PanelButton {
                                    id: copyButton

                                    text: I18n.tr("复制")
                                    chip: true
                                    anchors.verticalCenter: parent.verticalCenter
                                    onClicked: panel.copyInviteLink()
                                }
                            }
                        }
                    }
                }

                // 成员列表
                Rectangle {
                    width: parent.width
                    height: memberColumn.implicitHeight + Theme.spacingMd * 2
                    radius: Theme.radiusLg
                    color: Theme.surfaceContainerHigh

                    Column {
                        id: memberColumn

                        x: Theme.spacingMd
                        y: Theme.spacingMd
                        width: parent.width - Theme.spacingMd * 2
                        spacing: 2

                        Text {
                            text: I18n.tr("成员（") + panel.members.length + "）"
                            color: Theme.onSurfaceColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("titleSmall").size
                            font.weight: Font.DemiBold
                        }

                        Repeater {
                            model: panel.members

                            delegate: LtwMemberRow {
                                width: memberColumn.width
                                member: modelData
                                isSelf: String(modelData.userUuid || "") === panel.selfUuid
                            }
                        }
                    }
                }

                // 当前播放
                Rectangle {
                    width: parent.width
                    height: nowPlayingColumn.implicitHeight + Theme.spacingMd * 2
                    radius: Theme.radiusLg
                    color: Theme.surfaceContainerHigh

                    Column {
                        id: nowPlayingColumn

                        x: Theme.spacingMd
                        y: Theme.spacingMd
                        width: parent.width - Theme.spacingMd * 2
                        spacing: Theme.spacingSm

                        Row {
                            width: parent.width
                            spacing: Theme.spacingMd

                            CoverArt {
                                width: 56
                                height: 56
                                radius: Theme.radiusSm
                                path: ""
                                url: panel.trackCover
                                placeholderIconSize: 22
                                anchors.verticalCenter: parent.verticalCenter
                            }

                            Column {
                                width: parent.width - 56 - Theme.spacingMd
                                anchors.verticalCenter: parent.verticalCenter
                                spacing: 2

                                Text {
                                    width: parent.width
                                    text: panel.trackTitle
                                    color: Theme.onSurfaceColor
                                    font.family: Theme.fontFamily
                                    font.pixelSize: Theme.textStyle("titleSmall").size
                                    elide: Text.ElideRight
                                }

                                Text {
                                    width: parent.width
                                    text: panel.trackArtist
                                    color: Theme.onSurfaceVariantColor
                                    font.family: Theme.fontFamily
                                    font.pixelSize: Theme.textStyle("bodySmall").size
                                    elide: Text.ElideRight
                                }

                                Text {
                                    width: parent.width
                                    visible: panel.trackAlbum !== ""
                                    text: panel.trackAlbum
                                    color: Theme.onSurfaceVariantColor
                                    font.family: Theme.fontFamily
                                    font.pixelSize: Theme.textStyle("labelSmall").size
                                    elide: Text.ElideRight
                                }
                            }
                        }

                        LtwProgressBar {
                            width: parent.width
                            positionMs: panel.roomPositionMs
                            durationMs: panel.playbackDurationMs
                            seekEnabled: panel.isController ? panel.connection === "connected" && panel.roomPlaying
                                                            : panel.allowMemberControl && panel.connection === "connected"
                            onSeekRequested: function(ratio) { panel.seekToRatio(ratio) }
                        }

                        Row {
                            width: parent.width

                            Text {
                                text: panel.formatTime(panel.roomPositionMs)
                                color: Theme.onSurfaceVariantColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("labelSmall").size
                            }

                            Item {
                                width: Math.max(0, parent.width - 100)
                                height: 1
                            }

                            Text {
                                text: panel.formatTime(panel.playbackDurationMs)
                                color: Theme.onSurfaceVariantColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("labelSmall").size
                            }
                        }

                        Item {
                            width: parent.width
                            // 行高取最高按钮（播放键 56），再把每个按钮垂直居中：
                            // Row 按子项顶部对齐，混用 44/56/40 的尺寸时中心会错开。
                            height: 56

                            Row {
                                id: controlRow

                                anchors.centerIn: parent
                                spacing: Theme.spacingSm

                                IconButton {
                                    anchors.verticalCenter: parent.verticalCenter
                                    icon: "skip_previous"
                                    iconSize: 26
                                    size: 44
                                    tooltip: panel.isController ? I18n.tr("上一首") : I18n.tr("请求上一首")
                                    enabled: panel.connection === "connected" && panel.roomQueue.length > 0
                                    onClicked: panel.skipPrevious()
                                }

                                IconButton {
                                    anchors.verticalCenter: parent.verticalCenter
                                    icon: panel.roomPlaying ? "pause" : "play_arrow"
                                    iconFilled: true
                                    iconSize: 32
                                    size: 56
                                    tooltip: panel.isController ? (panel.roomPlaying ? I18n.tr("暂停") : I18n.tr("播放"))
                                                                : (panel.roomPlaying ? I18n.tr("请求暂停") : I18n.tr("请求播放"))
                                    enabled: panel.connection === "connected" && panel.track !== null
                                             && (panel.isController || panel.allowMemberControl)
                                    onClicked: panel.togglePlay()
                                }

                                IconButton {
                                    anchors.verticalCenter: parent.verticalCenter
                                    icon: "skip_next"
                                    iconSize: 26
                                    size: 44
                                    tooltip: panel.isController ? I18n.tr("下一首") : I18n.tr("请求下一首")
                                    enabled: panel.connection === "connected" && panel.roomQueue.length > 0
                                    onClicked: panel.skipNext()
                                }

                                IconButton {
                                    anchors.verticalCenter: parent.verticalCenter
                                    icon: "sync"
                                    iconSize: 22
                                    size: 40
                                    tooltip: panel.isController ? I18n.tr("把本地进度同步到房间") : I18n.tr("对齐到房间进度")
                                    enabled: panel.connection === "connected" && panel.track !== null
                                    onClicked: panel.syncProgress()
                                }
                            }
                        }

                        Text {
                            width: parent.width
                            visible: !panel.isController
                            text: panel.allowMemberControl
                                  ? I18n.tr("听众控制已开启：播放/暂停/切歌按钮会向服务端发送请求，由服务端校验后生效")
                                  : I18n.tr("控制者已关闭听众控制，当前只能跟随播放")
                            color: Theme.onSurfaceVariantColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("labelSmall").size
                            wrapMode: Text.WordWrap
                        }

                        // 听众侧的共享流状态提示：跟随房间设置与快照状态变化。
                        Text {
                            width: parent.width
                            visible: !panel.isController && panel.sharedStreamHint !== ""
                            text: panel.sharedStreamHint
                            color: panel.sharedStreamUnavailable ? Theme.tertiary
                                                                 : Theme.primary
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("labelSmall").size
                            wrapMode: Text.WordWrap
                        }
                    }
                }

                // 房间队列：默认展示前 5 首，可展开全部；控制者与（被授权的）听众都能增删与换序
                Rectangle {
                    width: parent.width
                    height: queueColumn.implicitHeight + Theme.spacingMd * 2
                    radius: Theme.radiusLg
                    color: Theme.surfaceContainerHigh

                    Column {
                        id: queueColumn

                        x: Theme.spacingMd
                        y: Theme.spacingMd
                        width: parent.width - Theme.spacingMd * 2
                        spacing: Theme.spacingXs

                        Item {
                            width: parent.width
                            height: 32

                            Text {
                                anchors.left: parent.left
                                anchors.verticalCenter: parent.verticalCenter
                                text: I18n.tr("房间队列（") + panel.roomQueue.length + "）"
                                color: Theme.onSurfaceColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("titleSmall").size
                                font.weight: Font.DemiBold
                            }

                            PanelButton {
                                id: addSongsButton

                                anchors.right: parent.right
                                anchors.verticalCenter: parent.verticalCenter
                                text: I18n.tr("添加歌曲")
                                chip: true
                                enabledButton: panel.roomQueueEditable && !panel.busy
                                onClicked: panel.openAddSongs()
                            }
                        }

                        Text {
                            width: parent.width
                            visible: panel.roomQueue.length === 0
                            text: panel.isController
                                  ? I18n.tr("房间队列是空的，添加一首在线歌曲开始一起听")
                                  : I18n.tr("房间队列是空的")
                            color: Theme.onSurfaceVariantColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("bodySmall").size
                            wrapMode: Text.WordWrap
                        }

                        Text {
                            width: parent.width
                            visible: !panel.isController && !panel.allowMemberControl
                            text: I18n.tr("控制者已关闭听众控制，暂时不能修改房间队列")
                            color: Theme.onSurfaceVariantColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("labelSmall").size
                            wrapMode: Text.WordWrap
                        }

                        Repeater {
                            model: panel.queueExpanded ? panel.roomQueue.length
                                                       : Math.min(panel.roomQueue.length, 5)

                            delegate: Item {
                                id: queueRow

                                width: queueColumn.width
                                height: 32

                                readonly property var item: panel.roomQueue[index]
                                readonly property bool current: index === panel.status.currentIndex
                                readonly property bool editable: panel.roomQueueEditable && !panel.busy

                                Text {
                                    anchors.left: parent.left
                                    anchors.right: rowActions.left
                                    anchors.rightMargin: Theme.spacingSm
                                    anchors.verticalCenter: parent.verticalCenter
                                    text: (index + 1) + ". " + (item && item.name !== undefined ? item.name : "")
                                          + (item && item.artist !== undefined && item.artist !== ""
                                             ? " - " + item.artist : "")
                                    color: queueRow.current ? Theme.primary : Theme.onSurfaceVariantColor
                                    font.family: Theme.fontFamily
                                    font.pixelSize: Theme.textStyle("bodySmall").size
                                    elide: Text.ElideRight
                                    font.weight: queueRow.current ? Font.Medium : Font.Normal
                                }

                                Row {
                                    id: rowActions

                                    anchors.right: parent.right
                                    anchors.verticalCenter: parent.verticalCenter
                                    spacing: 0

                                    IconButton {
                                        icon: "arrow_upward"
                                        iconSize: 15
                                        size: 28
                                        visible: queueRow.editable
                                        enabled: queueRow.editable && index > 0
                                        opacity: enabled ? 1 : 0.35
                                        tooltip: I18n.tr("上移")
                                        onClicked: panel.moveInRoomQueue(index, index - 1)
                                    }

                                    IconButton {
                                        icon: "arrow_downward"
                                        iconSize: 15
                                        size: 28
                                        visible: queueRow.editable
                                        enabled: queueRow.editable && index < panel.roomQueue.length - 1
                                        opacity: enabled ? 1 : 0.35
                                        tooltip: I18n.tr("下移")
                                        onClicked: panel.moveInRoomQueue(index, index + 1)
                                    }

                                    IconButton {
                                        icon: "delete"
                                        iconSize: 15
                                        size: 28
                                        visible: queueRow.editable
                                        // 后端拒绝移除队列里的最后一首歌（会退出房间）
                                        enabled: queueRow.editable && panel.roomQueue.length > 1
                                        opacity: enabled ? 1 : 0.35
                                        tooltip: I18n.tr("从房间队列移除")
                                        onClicked: panel.removeFromRoomQueue(index)
                                    }
                                }
                            }
                        }

                        PanelButton {
                            visible: panel.roomQueue.length > 5
                            text: panel.queueExpanded
                                  ? I18n.tr("收起")
                                  : I18n.tr("展开全部 ") + panel.roomQueue.length + I18n.tr(" 首")
                            chip: true
                            onClicked: panel.queueExpanded = !panel.queueExpanded
                        }
                    }
                }

                // 房间设置（控制者）
                Rectangle {
                    width: parent.width
                    height: settingsColumn.implicitHeight + Theme.spacingMd * 2
                    radius: Theme.radiusLg
                    color: Theme.surfaceContainerHigh
                    visible: panel.isController

                    Column {
                        id: settingsColumn

                        x: Theme.spacingMd
                        y: Theme.spacingMd
                        width: parent.width - Theme.spacingMd * 2
                        spacing: Theme.spacingXs

                        Text {
                            text: I18n.tr("房间设置")
                            color: Theme.onSurfaceColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("titleSmall").size
                            font.weight: Font.DemiBold
                        }

                        LtwSettingRow {
                            width: parent.width
                            title: I18n.tr("允许听众控制")
                            description: I18n.tr("听众可请求播放/暂停/跳转，服务端校验后直接生效")
                            checked: panel.allowMemberControl
                            enabledSwitch: panel.connection === "connected" && !panel.busy
                            onToggled: function(value) { panel.updateSetting("allowMemberControl", value) }
                        }

                        LtwSettingRow {
                            width: parent.width
                            title: I18n.tr("成员变化时自动暂停")
                            description: I18n.tr("新成员加入或成员离开时暂停房间播放")
                            checked: panel.autoPauseOnMemberChange
                            enabledSwitch: panel.connection === "connected" && !panel.busy
                            onToggled: function(value) { panel.updateSetting("autoPauseOnMemberChange", value) }
                        }

                        LtwSettingRow {
                            width: parent.width
                            title: I18n.tr("共享音频直链")
                            description: I18n.tr("开启后控制者会把解析到的播放地址分享给房间成员，听众可直接用该地址播放，减少各端解析失败/音源不一致")
                            checked: panel.shareAudioLinks
                            enabledSwitch: panel.connection === "connected" && !panel.busy
                            onToggled: function(value) { panel.updateSetting("shareAudioLinks", value) }
                        }
                    }
                }

                // 离开房间（二次确认：控制者离开会关闭房间）
                PanelButton {
                    text: I18n.tr("离开房间")
                    outlined: true
                    enabledButton: !panel.busy
                    onClicked: panel.confirmLeaveRoom()
                }
            }
        }
    }
}
