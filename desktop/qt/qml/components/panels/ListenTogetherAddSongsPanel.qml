import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../player"
import "../library"
import "../listentogether"

// 「添加歌曲到房间队列」弹窗（一起听面板专用）。
//
// 不重造歌曲选择器：搜索结果与本地播放队列都用媒体库的 SongRow 展示，
// 点击整行即把这首歌交给父面板加入房间队列（父面板按控制者/听众分流）。
// 已在房间队列里的歌曲会在右侧标记「已在队列」并跳过，避免重复请求后端。
//
// host 由父面板注入（ListenTogetherPanel），复用它的字段口径：
//   host.canShareToRoom(song)      这首歌能否进入房间（本地歌曲 / 缺远端 ID / YouTube 会被过滤）
//   host.songIdentity(item)        歌曲身份（channel:audioId），用于判重
//   host.roomQueueIdentities       房间队列里已有歌曲的身份数组
Dialog {
    id: root

    property var host: null
    property bool canAdd: true

    signal addRequested(var song)

    property string source: "NETEASE"
    property string keyword: ""
    property var songs: []
    property bool loading: false
    property string errorText: ""
    property int _seq: 0

    readonly property var sourceChips: [
        { "id": "NETEASE", "label": I18n.tr("网易云") },
        { "id": "BILIBILI", "label": I18n.tr("哔哩哔哩") },
        { "id": "QUEUE", "label": I18n.tr("播放队列") }
    ]
    readonly property bool searchMode: root.source !== "QUEUE"

    parent: Overlay.overlay
    anchors.centerIn: parent
    modal: true
    width: 480
    padding: Theme.spacingXl
    closePolicy: Popup.CloseOnEscape

    /** 打开弹窗前重置状态：搜索模式清空结果，播放队列模式立即列出本机可分享队列。 */
    function openFor() {
        root.errorText = ""
        root.loading = false
        if (root.searchMode)
            root.songs = root.keyword === "" ? [] : root.songs
        else
            root.loadQueue()
        root.open()
    }

    function selectable(song) {
        if (!song)
            return false
        return root.host ? root.host.canShareToRoom(song) : true
    }

    function alreadyInQueue(song) {
        if (!song || !root.host)
            return false
        const identities = root.host.roomQueueIdentities
        return identities ? identities.indexOf(root.host.songIdentity(song)) >= 0 : false
    }

    function loadQueue() {
        const queue = Player.queue !== undefined && Player.queue !== null ? Player.queue : []
        const result = []
        for (let i = 0; i < queue.length; ++i) {
            if (root.selectable(queue[i]))
                result.push(queue[i])
        }
        root.songs = result
    }

    function search(text) {
        const trimmed = String(text === undefined || text === null ? "" : text).trim()
        if (trimmed === "" || !root.searchMode)
            return
        root.keyword = trimmed
        root.loading = true
        root.errorText = ""
        const seq = ++root._seq
        Api.call("online.search", { "source": root.source, "keyword": trimmed, "page": 1 }).then(function(result) {
            if (seq !== root._seq)
                return
            const songs = result && result.songs ? result.songs : []
            const filtered = []
            for (let i = 0; i < songs.length; ++i) {
                if (root.selectable(songs[i]))
                    filtered.push(songs[i])
            }
            root.songs = filtered
            root.loading = false
        }).catch(function(error) {
            if (seq !== root._seq)
                return
            root.songs = []
            root.errorText = error && error.message ? error.message : I18n.tr("搜索失败")
            root.loading = false
        })
    }

    function changeSource(id) {
        if (root.source === id)
            return
        root.source = id
        root.errorText = ""
        root.songs = []
        if (id === "QUEUE")
            root.loadQueue()
        else if (root.keyword !== "")
            root.search(root.keyword)
    }

    function activate(song) {
        if (!song || !root.canAdd)
            return
        if (root.alreadyInQueue(song)) {
            Toast.show(I18n.tr("该歌曲已在房间队列中"), "info")
            return
        }
        root.addRequested(song)
    }

    background: Rectangle {
        radius: Theme.radiusXxl
        color: Theme.surfaceContainerHigh
        implicitWidth: 480
        implicitHeight: body.implicitHeight + Theme.spacingXl * 2
    }

    contentItem: ColumnLayout {
        id: body

        spacing: Theme.spacingMd

        Text {
            Layout.fillWidth: true
            text: I18n.tr("添加歌曲到房间队列")
            color: Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("titleMedium").size
            font.weight: Font.DemiBold
        }

        Text {
            Layout.fillWidth: true
            text: I18n.tr("房间队列由各端共享；本地歌曲与 YouTube 歌曲不能加入。")
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodySmall").size
            wrapMode: Text.WordWrap
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingSm

            Repeater {
                model: root.sourceChips

                delegate: PanelButton {
                    text: modelData.label
                    chip: true
                    selected: root.source === modelData.id
                    enabledButton: root.canAdd
                    onClicked: root.changeSource(modelData.id)
                }
            }

            Item {
                Layout.fillWidth: true
                implicitHeight: 1
            }
        }

        LtwTextField {
            Layout.fillWidth: true
            visible: root.searchMode
            placeholderText: I18n.tr("搜索歌曲或歌手，回车搜索")
            enabled: root.canAdd
            onAccepted: root.search(text)
        }

        ListView {
            id: songList

            Layout.fillWidth: true
            Layout.preferredHeight: Math.min(348, Math.max(0, root.songs.length) * 58)
            visible: root.songs.length > 0
            clip: true
            model: root.songs
            boundsBehavior: Flickable.StopAtBounds

            delegate: Item {
                id: songRow

                required property var modelData
                required property int index

                readonly property bool inQueue: root.alreadyInQueue(songRow.modelData)

                width: songList.width
                height: 58

                SongRow {
                    anchors.fill: parent
                    song: songRow.modelData
                    index: -1
                    showIndex: false
                    showMore: false
                    linksEnabled: false
                    extraText: songRow.inQueue ? I18n.tr("已在队列") : ""
                    onActivated: function(index) { root.activate(songRow.modelData) }
                    onRightClicked: function(song) { root.activate(song) }
                }
            }
        }

        Text {
            Layout.fillWidth: true
            visible: root.loading || root.errorText !== "" || root.songs.length === 0
            text: {
                if (root.loading)
                    return I18n.tr("正在搜索…")
                if (root.errorText !== "")
                    return I18n.tr("搜索失败：") + root.errorText
                if (!root.searchMode)
                    return I18n.tr("当前播放队列里没有可加入房间的歌曲")
                return root.keyword === "" ? I18n.tr("输入关键词搜索在线歌曲")
                                         : I18n.tr("没有搜索到可加入房间的歌曲")
            }
            color: root.errorText !== "" ? Theme.error : Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodyMedium").size
            wrapMode: Text.WordWrap
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingSm

            Item {
                Layout.fillWidth: true
                implicitHeight: 1
            }

            PanelButton {
                text: I18n.tr("完成")
                onClicked: root.close()
            }
        }
    }
}
