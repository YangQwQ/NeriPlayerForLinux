import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import ".."
import "../library"

// 「歌曲百科」面板：对齐 Android 的 SongEncyclopediaContent，数据来自后端
// online.songEncyclopedia（网易云匿名公开接口聚合）。文件名沿用历史名称，菜单引用不变。
// 分区只在有真实数据时显示；没有数据的分区不渲染，不用假数据占位。
Dialog {
    id: dialog

    property var song: null

    // loading / ready / unsupported / unavailable / error
    property string state: "loading"
    property var encyclopedia: null
    property string reason: ""
    property string errorMessage: ""
    property int requestToken: 0

    readonly property var info: dialog.encyclopedia && dialog.encyclopedia.basicInfo
                                ? dialog.encyclopedia.basicInfo : ({})
    readonly property var music: dialog.encyclopedia && dialog.encyclopedia.musicInfo
                                 ? dialog.encyclopedia.musicInfo : ({})
    readonly property var credits: dialog.encyclopedia && dialog.encyclopedia.credits
                                   ? dialog.encyclopedia.credits : ({})
    readonly property var artist: dialog.encyclopedia && dialog.encyclopedia.artist
                                  ? dialog.encyclopedia.artist : null
    readonly property var similarSongs: dialog.encyclopedia
                                        ? toArray(dialog.encyclopedia.similarSongs) : []
    readonly property var playlists: dialog.encyclopedia
                                     ? toArray(dialog.encyclopedia.relatedPlaylists) : []
    readonly property var awards: dialog.encyclopedia
                                  ? toArray(dialog.encyclopedia.awards) : []
    readonly property var appearances: dialog.encyclopedia
                                       ? toArray(dialog.encyclopedia.appearances) : []

    function openFor(target) {
        dialog.song = target
        dialog.state = "loading"
        dialog.encyclopedia = null
        dialog.errorMessage = ""
        dialog.open()
        dialog.reload()
    }

    /** 后端返回的数组在 QML 侧统一转成 JS 数组。 */
    function toArray(value) {
        if (value === undefined || value === null)
            return []
        const copy = []
        const length = value.length !== undefined ? Number(value.length) : 0
        for (let i = 0; i < length; ++i)
            copy.push(value[i])
        return copy
    }

    function join(values) {
        return toArray(values).filter(function(v) { return !!String(v || "").trim() }).join("、")
    }

    function formatDate(ms) {
        if (!ms || Number(ms) <= 0)
            return ""
        const d = new Date(Number(ms))
        if (isNaN(d.getTime()))
            return ""
        return d.getFullYear() + "-" + String(d.getMonth() + 1).padStart(2, "0")
               + "-" + String(d.getDate()).padStart(2, "0")
    }

    function formatCount(n) {
        const value = Number(n) || 0
        if (I18n.language === "en") {
            if (value >= 1000)
                return (value / 1000).toFixed(1) + "k"
            return String(value)
        }
        if (value >= 10000)
            return (value / 10000).toFixed(1) + I18n.tr("万")
        return String(value)
    }

    function reload() {
        if (!dialog.song) {
            dialog.state = "unsupported"
            return
        }
        const token = ++dialog.requestToken
        dialog.state = "loading"
        Api.call("online.songEncyclopedia", { song: dialog.song }).then(function(result) {
            if (token !== dialog.requestToken || !dialog.opened)
                return
            if (!result || !result.available) {
                dialog.reason = result && result.reason ? String(result.reason) : "not_available"
                dialog.state = dialog.reason === "not_supported" ? "unsupported" : "unavailable"
                return
            }
            dialog.encyclopedia = result.encyclopedia
            dialog.state = "ready"
        }).catch(function(error) {
            if (token !== dialog.requestToken || !dialog.opened)
                return
            dialog.errorMessage = error && error.message ? String(error.message) : I18n.tr("未知错误")
            dialog.state = "error"
        })
    }

    /** 相似歌曲 → 可播放的 Song（字段来自接口，不猜测缺失值）。 */
    function similarToSong(item) {
        const id = String(item.songId || "")
        return {
            key: "netease:" + id,
            source: "NETEASE",
            title: String(item.name || ""),
            artist: String(item.artist || ""),
            album: item.albumName ? String(item.albumName) : "",
            remoteId: id,
            artworkUrl: item.coverUrl ? String(item.coverUrl) : "",
            durationMs: Number(item.durationMs) || 0
        }
    }

    function playSimilar(item) {
        const list = dialog.similarSongs.map(dialog.similarToSong)
        const song = dialog.similarToSong(item)
        Player.playSongNow(song, list)
        dialog.close()
    }

    function playAllSimilar() {
        const list = dialog.similarSongs.map(dialog.similarToSong)
        if (list.length === 0)
            return
        Player.playSongNow(list[0], list)
        dialog.close()
    }

    function openPlaylist(item) {
        dialog.close()
        Nav.push("onlineCollection", {
                     id: String(item.playlistId || ""),
                     name: String(item.name || ""),
                     source: "NETEASE",
                     coverUrl: item.coverUrl ? String(item.coverUrl) : "",
                     creator: "",
                     playCount: Number(item.playCount) || 0,
                     trackCount: 0,
                     kind: "playlist"
                 })
    }

    function openArtist() {
        if (!dialog.artist)
            return
        dialog.close()
        Nav.push("remoteArtist", {
                     id: String(dialog.artist.artistId || ""),
                     name: String(dialog.artist.name || ""),
                     source: "NETEASE",
                     avatarUrl: dialog.artist.picUrl ? String(dialog.artist.picUrl) : "",
                     songCount: Number(dialog.artist.musicSize) || 0
                 })
    }

    parent: Overlay.overlay
    anchors.centerIn: parent
    modal: true
    width: Math.min(560, parent ? parent.width - 48 : 560)
    height: Math.min(760, parent ? parent.height - 48 : 760)
    padding: 0
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

    background: Rectangle {
        radius: Theme.radiusXxl
        color: Theme.surfaceContainerHigh
        border.width: 1
        border.color: Theme.withAlpha(Theme.outlineVariant, 0.6)
    }

    contentItem: ColumnLayout {
        spacing: 0

        // 标题栏
        RowLayout {
            Layout.fillWidth: true
            Layout.preferredHeight: 60
            spacing: Theme.spacingSm

            NeriIcon {
                Layout.leftMargin: Theme.spacingLg
                name: "book"
                iconSize: 22
                color: Theme.primary
            }
            Text {
                Layout.fillWidth: true
                text: I18n.tr("歌曲百科")
                color: Theme.onSurfaceColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("titleMedium").size
                font.weight: Font.DemiBold
            }
            IconButton {
                Layout.rightMargin: Theme.spacingSm
                icon: "close"
                size: 40
                iconSize: 20
                tooltip: I18n.tr("关闭")
                onClicked: dialog.close()
            }
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 1
            color: Theme.withAlpha(Theme.outlineVariant, 0.5)
        }

        // 加载中
        ColumnLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: dialog.state === "loading"
            Layout.alignment: Qt.AlignHCenter | Qt.AlignVCenter
            spacing: Theme.spacingMd
            BusyIndicator { Layout.alignment: Qt.AlignHCenter; running: true }
            Text {
                Layout.alignment: Qt.AlignHCenter
                text: I18n.tr("正在加载百科…")
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("bodyMedium").size
            }
        }

        // 不可用 / 失败
        ColumnLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: dialog.state === "unsupported" || dialog.state === "unavailable" || dialog.state === "error"
            spacing: Theme.spacingMd
            Layout.alignment: Qt.AlignHCenter | Qt.AlignVCenter

            NeriIcon {
                Layout.alignment: Qt.AlignHCenter
                name: dialog.state === "error" ? "error" : "book"
                iconSize: 40
                color: Theme.onSurfaceVariantColor
            }
            Text {
                Layout.fillWidth: true
                horizontalAlignment: Text.AlignHCenter
                wrapMode: Text.Wrap
                text: dialog.state === "unsupported"
                      ? I18n.tr("当前歌曲不支持百科（仅网易云歌曲）")
                      : dialog.state === "unavailable"
                        ? I18n.tr("这首歌暂无百科资料")
                        : I18n.tr("百科加载失败：") + dialog.errorMessage
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("bodyMedium").size
            }
            LibraryButton {
                Layout.alignment: Qt.AlignHCenter
                visible: dialog.state === "error"
                text: I18n.tr("重试")
                filled: true
                onClicked: dialog.reload()
            }
        }

        // 成功内容
        ScrollView {
            id: contentScroll
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: dialog.state === "ready"
            clip: true

            // ScrollView 的 contentItem 是 Flickable，它的 width 默认取内容宽度，
            // 内层 ColumnLayout 用 parent.width 就会拿到一个随内容收缩的值，导致
            // 卡片只占左侧一小条。显式把 Flickable 宽度钉到视口宽度，内容才能撑满。
            Binding {
                target: contentScroll.contentItem
                property: "width"
                value: contentScroll.availableWidth
            }

            ColumnLayout {
                id: qmlContent
                width: Math.max(1, contentScroll.availableWidth - Theme.spacingXl * 2)
                x: Theme.spacingXl
                spacing: Theme.spacingMd

                // 歌曲详情
                SectionCard {
                    id: detailCard
                    visible: detailCard.detailRows.length > 0
                    title: I18n.tr("歌曲详情")
                    iconName: "library_music"
                    Repeater {
                        model: detailCard.detailRows
                        delegate: InfoRow {
                            label: modelData.label
                            value: modelData.value
                        }
                    }
                    property var detailRows: {
                        const rows = []
                        if (!dialog.encyclopedia)
                            return rows
                        const mi = dialog.music
                        const g = dialog.join(mi.genres)
                        if (g) rows.push({label: I18n.tr("曲风"), value: g})
                        if (mi.language) rows.push({label: I18n.tr("语种"), value: String(mi.language)})
                        if (mi.bpm && Number(mi.bpm) > 0) rows.push({label: I18n.tr("BPM"), value: String(mi.bpm)})
                        const tags = dialog.join(mi.tags)
                        if (tags) rows.push({label: I18n.tr("标签"), value: tags})
                        if (dialog.info.album) rows.push({label: I18n.tr("专辑"), value: String(dialog.info.album)})
                        const date = dialog.formatDate(dialog.encyclopedia.publishTimeMs)
                        if (date) rows.push({label: I18n.tr("发行日期"), value: date})
                        if (dialog.encyclopedia.albumCompany)
                            rows.push({label: I18n.tr("发行公司"), value: String(dialog.encyclopedia.albumCompany)})
                        return rows
                    }
                }

                // 艺人
                SectionCard {
                    visible: !!dialog.artist
                    title: I18n.tr("关于艺人")
                    iconName: "person"
                    Rectangle {
                        Layout.fillWidth: true
                        radius: Theme.radiusMd
                        color: Theme.surfaceContainerLow
                        implicitHeight: artistLayout.implicitHeight + Theme.spacingLg * 2
                        RowLayout {
                            id: artistLayout
                            x: Theme.spacingLg
                            y: Theme.spacingLg
                            width: parent.width - Theme.spacingLg * 2
                            spacing: Theme.spacingMd

                            CoverArt {
                                Layout.preferredWidth: 52
                                Layout.preferredHeight: 52
                                circle: true
                                url: dialog.artist ? (dialog.artist.picUrl || "") : ""
                                placeholderIcon: "person"
                            }
                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2
                                Text {
                                    Layout.fillWidth: true
                                    text: dialog.artist ? String(dialog.artist.name || "") : ""
                                    color: Theme.onSurfaceColor
                                    font.family: Theme.fontFamily
                                    font.pixelSize: Theme.textStyle("titleSmall").size
                                    font.weight: Font.DemiBold
                                    elide: Text.ElideRight
                                }
                                Text {
                                    Layout.fillWidth: true
                                    visible: dialog.artist && typeof dialog.artist.albumSize === "number" && typeof dialog.artist.musicSize === "number"
                                    text: dialog.artist
                                          ? I18n.tr("%1 张专辑 · %2 首歌曲").arg(dialog.artist.albumSize).arg(dialog.artist.musicSize)
                                          : ""
                                    color: Theme.onSurfaceVariantColor
                                    font.family: Theme.fontFamily
                                    font.pixelSize: Theme.textStyle("labelSmall").size
                                }
                            }
                            LibraryButton {
                                text: I18n.tr("查看")
                                onClicked: dialog.openArtist()
                            }
                        }
                    }
                    Text {
                        Layout.fillWidth: true
                        Layout.topMargin: Theme.spacingSm
                        visible: !!dialog.artist && !!dialog.artist.briefDesc
                        text: dialog.artist ? String(dialog.artist.briefDesc) : ""
                        wrapMode: Text.Wrap
                        maximumLineCount: 8
                        elide: Text.ElideRight
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("bodySmall").size
                        lineHeight: 1.4
                    }
                }

                // 创作团队
                SectionCard {
                    id: creditsCard
                    visible: creditsCard.creditRows.length > 0
                    title: I18n.tr("创作团队")
                    iconName: "people"
                    Repeater {
                        model: creditsCard.creditRows
                        delegate: InfoRow {
                            label: modelData.label
                            value: modelData.value
                        }
                    }
                    property var creditRows: {
                        const rows = []
                        const c = dialog.credits
                        const push = function(label, names) {
                            const value = dialog.join(names)
                            if (value) rows.push({label: label, value: value})
                        }
                        push(I18n.tr("作词"), c.lyricists)
                        push(I18n.tr("作曲"), c.composers)
                        push(I18n.tr("编曲"), c.arrangers)
                        push(I18n.tr("制作人"), c.producers)
                        dialog.toArray(c.otherRoles).forEach(function(role) {
                            push(role.role || "", role.names)
                        })
                        return rows
                    }
                }

                // 获奖 / 收录
                SectionCard {
                    visible: dialog.awards.length > 0 || dialog.appearances.length > 0
                    title: I18n.tr("获奖与收录")
                    iconName: "emoji_events"
                    Repeater {
                        model: dialog.awards
                        delegate: ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 2
                            Text {
                                Layout.fillWidth: true
                                text: String(modelData.title || "")
                                color: Theme.onSurfaceColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("bodyMedium").size
                            }
                            Text {
                                Layout.fillWidth: true
                                visible: !!modelData.detail
                                text: String(modelData.detail || "")
                                wrapMode: Text.Wrap
                                color: Theme.onSurfaceVariantColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("labelSmall").size
                            }
                        }
                    }
                    Repeater {
                        model: dialog.appearances
                        delegate: InfoRow {
                            label: I18n.tr("收录")
                            value: String(modelData || "")
                        }
                    }
                }

                // 相似歌曲
                SectionCard {
                    visible: dialog.similarSongs.length > 0
                    title: I18n.tr("喜欢这首歌的人也爱听")
                    iconName: "music_note"
                    Repeater {
                        model: dialog.similarSongs
                        delegate: SongRowItem {
                            Layout.fillWidth: true
                            coverUrl: modelData.coverUrl || ""
                            title: String(modelData.name || "")
                            subtitle: String(modelData.artist || "")
                            indexText: String(index + 1)
                            onClicked: dialog.playSimilar(modelData)
                        }
                    }
                    LibraryButton {
                        Layout.topMargin: Theme.spacingXs
                        text: I18n.tr("播放全部")
                        icon: "play_arrow"
                        onClicked: dialog.playAllSimilar()
                    }
                }

                // 相关歌单
                SectionCard {
                    visible: dialog.playlists.length > 0
                    title: I18n.tr("相关歌单")
                    iconName: "queue_music"
                    Repeater {
                        model: dialog.playlists
                        delegate: SongRowItem {
                            Layout.fillWidth: true
                            coverUrl: modelData.coverUrl || ""
                            title: String(modelData.name || "")
                            subtitle: modelData.playCount
                                      ? I18n.tr("%1 次播放").arg(dialog.formatCount(modelData.playCount))
                                      : ""
                            indexText: ""
                            onClicked: dialog.openPlaylist(modelData)
                        }
                    }
                }

                Text {
                    Layout.fillWidth: true
                    Layout.topMargin: Theme.spacingSm
                    Layout.bottomMargin: Theme.spacingLg
                    text: I18n.tr("资料来源：网易云音乐；缺失字段不展示，不做推断")
                    wrapMode: Text.Wrap
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelSmall").size
                }
            }
        }
    }

    // ---------------------------------------------------------------- 内联组件

    component SectionCard: Rectangle {
        id: card
        property string title: ""
        property string iconName: ""
        default property alias content: bodyColumn.data
        Layout.fillWidth: true
        radius: Theme.radiusLg
        color: Theme.surfaceContainerLow
        implicitHeight: bodyColumn.implicitHeight + Theme.spacingLg * 2

        ColumnLayout {
            id: bodyColumn
            x: Theme.spacingLg
            y: Theme.spacingLg
            width: parent.width - Theme.spacingLg * 2
            spacing: Theme.spacingSm

            RowLayout {
                Layout.fillWidth: true
                spacing: Theme.spacingSm
                NeriIcon {
                    name: card.iconName
                    iconSize: 18
                    color: Theme.primary
                }
                Text {
                    Layout.fillWidth: true
                    text: card.title
                    color: Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("titleSmall").size
                    font.weight: Font.DemiBold
                }
            }
        }
    }

    component InfoRow: RowLayout {
        id: infoRow
        property string label: ""
        property string value: ""
        Layout.fillWidth: true
        spacing: Theme.spacingMd
        Text {
            Layout.preferredWidth: 72
            text: infoRow.label
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodyMedium").size
        }
        Text {
            Layout.fillWidth: true
            text: infoRow.value
            wrapMode: Text.Wrap
            color: Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodyMedium").size
        }
    }

    component SongRowItem: Item {
        id: row
        property string coverUrl: ""
        property string title: ""
        property string subtitle: ""
        property string indexText: ""
        signal clicked()
        implicitHeight: 56
        Layout.fillWidth: true

        RowLayout {
            anchors.fill: parent
            spacing: Theme.spacingMd
            Text {
                visible: !!row.indexText
                text: row.indexText
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("labelLarge").size
                Layout.preferredWidth: 24
            }
            CoverArt {
                Layout.preferredWidth: 40
                Layout.preferredHeight: 40
                radius: 6
                url: row.coverUrl
            }
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 2
                Text {
                    Layout.fillWidth: true
                    text: row.title
                    elide: Text.ElideRight
                    color: Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodyMedium").size
                }
                Text {
                    Layout.fillWidth: true
                    visible: !!row.subtitle
                    text: row.subtitle
                    elide: Text.ElideRight
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelSmall").size
                }
            }
        }
        MouseArea {
            anchors.fill: parent
            cursorShape: Qt.PointingHandCursor
            onClicked: row.clicked()
        }
    }
}
