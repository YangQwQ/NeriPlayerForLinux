import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import ".."
import "../library"

// 「收藏到网易云歌单」对话框：对齐 Android 播放页「收藏到网易云歌单」。
// 打开时读 online.myPlaylists 列出账号自建歌单（Android 只列 creator=自己），
// 选择后用 online.addToNeteasePlaylist 真正调用网易云写歌单接口。
Dialog {
    id: dialog

    property var song: null
    property var playlists: []
    property bool loading: false
    property bool submitting: false
    property bool needLogin: false
    property string errorMessage: ""
    property var accounts: []
    property string myNickname: ""
    property string myUserId: ""

    /** 未登录 / 风控时可用的替代方案：网易云网页版与歌曲链接。 */
    readonly property string songLink: dialog.song && dialog.song.remoteId
        ? "https://music.163.com/song?id=" + dialog.song.remoteId
        : ""

    function openFor(target) {
        dialog.song = target
        dialog.playlists = []
        dialog.needLogin = false
        dialog.errorMessage = ""
        dialog.open()
        Api.call("login.accounts").then(function(result) {
            const list = result && result.accounts ? result.accounts : []
            for (let i = 0; i < list.length; ++i) {
                const account = list[i]
                if (account && account.source === "NETEASE") {
                    dialog.myNickname = String(account.nickname || "")
                    dialog.myUserId = String(account.userId || "")
                    break
                }
            }
            dialog.reload()
        }).catch(function() {
            dialog.reload()
        })
    }

    function reload() {
        dialog.loading = true
        dialog.errorMessage = ""
        Api.call("online.myPlaylists", { source: "NETEASE" }).then(function(result) {
            dialog.loading = false
            dialog.needLogin = !!(result && result.needLogin)
            const all = result && result.collections ? result.collections : []
            // 网易云只有自建歌单可写；收藏的他人歌单不出现在选择列表。
            dialog.playlists = all.filter(function(item) {
                if (!item || item.source !== "NETEASE") return false
                if (!item.creator) return true
                return item.creator === dialog.myNickname || ("UID " + dialog.myUserId) === item.creator
            })
        }).catch(function(error) {
            dialog.loading = false
            dialog.errorMessage = error && error.message ? error.message : I18n.tr("读取歌单失败")
        })
    }

    function addTo(playlist) {
        if (!dialog.song || !playlist || dialog.submitting)
            return
        dialog.submitting = true
        Api.call("online.addToNeteasePlaylist", {
            playlistId: playlist.id,
            songs: [dialog.song]
        }).then(function(result) {
            dialog.submitting = false
            if (result && result.ok) {
                const added = result.added || 0
                const existing = result.alreadyExists || 0
                Toast.show(I18n.tr("已添加到") + "「" + playlist.name + "」" +
                    (added > 0 ? I18n.tr("，新增 ") + added + I18n.tr(" 首") : "") +
                    (existing > 0 ? I18n.tr("，已在歌单 ") + existing + I18n.tr(" 首") : ""))
                dialog.close()
                return
            }
            dialog.needLogin = !!(result && result.needLogin)
            dialog.errorMessage = result && result.error ? String(result.error) : I18n.tr("收藏失败")
            if (result && result.needsWebVerification)
                dialog.errorMessage += "：" + I18n.tr("请在网易云网页版完成安全校验后重试")
            Toast.show(dialog.errorMessage, "error")
        }).catch(function(error) {
            dialog.submitting = false
            dialog.errorMessage = error && error.message ? error.message : I18n.tr("收藏失败")
            Toast.show(dialog.errorMessage, "error")
        })
    }

    function copySongLink() {
        if (dialog.songLink === "")
            return
        clipboardHelper.text = dialog.songLink
        clipboardHelper.selectAll()
        clipboardHelper.copy()
        Toast.show(I18n.tr("已复制歌曲链接"), "info")
    }

    TextEdit {
        id: clipboardHelper
        visible: false
        width: 1
        height: 1
    }

    parent: Overlay.overlay
    anchors.centerIn: parent
    modal: true
    width: Math.min(460, parent ? parent.width - 48 : 460)
    height: Math.min(520, parent ? parent.height - 64 : 520)
    padding: Theme.spacingXl
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

    background: Rectangle {
        radius: Theme.radiusXxl
        color: Theme.surfaceContainerHigh
        border.width: 1
        border.color: Theme.withAlpha(Theme.outlineVariant, 0.6)
    }

    contentItem: ColumnLayout {
        spacing: Theme.spacingMd

        Text {
            Layout.fillWidth: true
            text: I18n.tr("收藏到网易云歌单")
            color: Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("titleMedium").size
            font.weight: Font.DemiBold
        }

        Text {
            Layout.fillWidth: true
            text: I18n.tr("选择你自己的网易云歌单，将当前歌曲写入你的网易云账号。")
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodySmall").size
            wrapMode: Text.Wrap
        }

        // 未登录 / 风控降级：明确说明不能假装成功，给出可执行替代。
        Rectangle {
            Layout.fillWidth: true
            visible: dialog.needLogin || dialog.errorMessage !== ""
            radius: Theme.radiusSm
            color: Theme.withAlpha(Theme.error, 0.10)
            border.width: 1
            border.color: Theme.withAlpha(Theme.error, 0.35)

            ColumnLayout {
                anchors.fill: parent
                anchors.margins: Theme.spacingMd
                spacing: Theme.spacingSm

                Text {
                    Layout.fillWidth: true
                    text: dialog.needLogin
                        ? I18n.tr("请先登录网易云音乐账号，再收藏到歌单。")
                        : dialog.errorMessage
                    color: Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodySmall").size
                    wrapMode: Text.Wrap
                }

                RowLayout {
                    Layout.fillWidth: true
                    spacing: Theme.spacingSm

                    LibraryButton {
                        visible: dialog.needLogin
                        text: I18n.tr("去登录")
                        filled: true
                        onClicked: {
                            dialog.close()
                            Nav.openLoginPanel("NETEASE")
                        }
                    }

                    LibraryButton {
                        visible: dialog.songLink !== ""
                        text: I18n.tr("复制歌曲链接")
                        onClicked: dialog.copySongLink()
                    }

                    LibraryButton {
                        text: I18n.tr("前往网页版操作")
                        onClicked: Qt.openUrlExternally("https://music.163.com/")
                    }
                }
            }
        }

        LoadingState {
            Layout.fillWidth: true
            visible: dialog.loading
            text: I18n.tr("正在读取歌单…")
        }

        Flickable {
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: !dialog.loading && !dialog.needLogin && dialog.playlists.length > 0
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            contentHeight: listColumn.implicitHeight

            Column {
                id: listColumn
                width: parent.width
                spacing: Theme.spacingXs

                Repeater {
                    model: dialog.playlists

                    delegate: Rectangle {
                        required property var modelData
                        width: listColumn.width
                        height: 56
                        radius: Theme.radiusSm
                        color: rowMouse.containsMouse ? Theme.surfaceContainerLow : "transparent"

                        NeriIcon {
                            anchors.left: parent.left
                            anchors.leftMargin: Theme.spacingSm
                            anchors.verticalCenter: parent.verticalCenter
                            name: "queue_music"
                            iconSize: 22
                            color: Theme.onSurfaceVariantColor
                        }

                        Column {
                            anchors.left: parent.left
                            anchors.leftMargin: Theme.spacingMd + 26
                            anchors.right: countLabel.left
                            anchors.rightMargin: Theme.spacingSm
                            anchors.verticalCenter: parent.verticalCenter
                            spacing: 2

                            Text {
                                width: parent.width
                                text: modelData.name
                                elide: Text.ElideRight
                                color: Theme.onSurfaceColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("bodyMedium").size
                            }

                            Text {
                                width: parent.width
                                text: I18n.tr("我创建的歌单")
                                elide: Text.ElideRight
                                color: Theme.onSurfaceVariantColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("bodySmall").size
                            }
                        }

                        Text {
                            id: countLabel
                            anchors.right: parent.right
                            anchors.rightMargin: Theme.spacingMd
                            anchors.verticalCenter: parent.verticalCenter
                            text: (modelData.trackCount || 0) + I18n.tr(" 首")
                            color: Theme.onSurfaceVariantColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("bodySmall").size
                        }

                        MouseArea {
                            id: rowMouse
                            anchors.fill: parent
                            hoverEnabled: true
                            enabled: !dialog.submitting
                            cursorShape: Qt.PointingHandCursor
                            onClicked: dialog.addTo(modelData)
                        }
                    }
                }
            }
        }

        Text {
            Layout.fillWidth: true
            visible: !dialog.loading && !dialog.needLogin && dialog.playlists.length === 0 && dialog.errorMessage === ""
            text: I18n.tr("还没有可写入的网易云歌单，请先在网易云创建歌单。")
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodySmall").size
            wrapMode: Text.Wrap
        }

        RowLayout {
            Layout.fillWidth: true
            Layout.alignment: Qt.AlignRight

            LibraryButton {
                text: I18n.tr("关闭")
                filled: true
                onClicked: dialog.close()
            }
        }
    }
}
