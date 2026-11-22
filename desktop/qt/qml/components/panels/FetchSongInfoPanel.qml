import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../library"

// 「获取歌曲信息」对话框：从在线源按标题/歌手重新搜索，核对可得的元数据。
// 桌面后端没有「按当前 Song 直接刷新详情」的专用命令，复用 online.search（按关键词
// 搜索歌曲）拿到在线条目，展示供用户对照；本地文件不会改写，避免误覆盖用户标签。
Dialog {
    id: dialog

    property var song: null
    property var results: []
    property bool loading: false
    property string errorText: ""

    function openFor(target) {
        dialog.song = target
        dialog.results = []
        dialog.errorText = ""
        dialog.open()
        dialog.fetch()
    }

    function fetch() {
        if (!dialog.song) {
            dialog.errorText = I18n.tr("当前没有播放歌曲")
            return
        }
        const source = dialog.song.source ? String(dialog.song.source).toUpperCase() : "LOCAL"
        if (source === "LOCAL" || source === "YOUTUBE") {
            dialog.errorText = I18n.tr("当前歌曲没有可查询的在线音源")
            return
        }
        const keyword = String(dialog.song.title || "") + (dialog.song.artist ? " " + dialog.song.artist : "")
        if (keyword.trim() === "") {
            dialog.errorText = I18n.tr("歌曲标题为空，无法在线查询")
            return
        }
        dialog.loading = true
        Api.call("online.search", { source: source, keyword: keyword.trim(), kind: "SONG", page: 1 })
            .then(function(result) {
                dialog.loading = false
                dialog.results = (result && result.songs) ? result.songs : []
                if (dialog.results.length === 0)
                    dialog.errorText = I18n.tr("没有找到匹配的在线曲目")
                else
                    dialog.errorText = ""
            })
            .catch(function(error) {
                dialog.loading = false
                dialog.errorText = error && error.message
                                   ? error.message : I18n.tr("在线查询失败")
            })
    }

    function resultLine(item) {
        const title = item.title || I18n.tr("未知")
        const artist = item.artist ? " · " + item.artist : ""
        const album = item.album ? " · " + item.album : ""
        return title + artist + album
    }

    parent: Overlay.overlay
    anchors.centerIn: parent
    modal: true
    width: Math.min(480, parent ? parent.width - 48 : 480)
    height: Math.min(480, parent ? parent.height - 64 : 480)
    padding: Theme.spacingXl
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

    background: Rectangle {
        radius: Theme.radiusXxl
        color: Theme.surfaceContainerHigh
        border.width: 1
        border.color: Theme.withAlpha(Theme.outlineVariant, 0.6)
    }

    contentItem: ColumnLayout {
        id: body
        spacing: Theme.spacingMd

        Text {
            Layout.fillWidth: true
            text: I18n.tr("获取歌曲信息")
            color: Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("titleMedium").size
            font.weight: Font.DemiBold
        }

        Text {
            Layout.fillWidth: true
            text: I18n.tr("按标题与歌手从在线音源重新查询元数据，结果仅供核对，不会覆盖本地文件。")
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodySmall").size
            wrapMode: Text.Wrap
        }

        LoadingState {
            Layout.fillWidth: true
            visible: dialog.loading
            text: I18n.tr("正在查询在线曲目…")
        }

        Text {
            Layout.fillWidth: true
            visible: !dialog.loading && dialog.errorText !== ""
            text: dialog.errorText
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodyMedium").size
            wrapMode: Text.Wrap
        }

        Flickable {
            Layout.fillWidth: true
            Layout.fillHeight: true
            visible: !dialog.loading && dialog.results.length > 0
            clip: true
            boundsBehavior: Flickable.StopAtBounds
            contentHeight: resultColumn.implicitHeight

            Column {
                id: resultColumn
                width: parent.width
                spacing: Theme.spacingSm

                Repeater {
                    model: dialog.results

                    delegate: Rectangle {
                        required property var modelData
                        width: resultColumn.width
                        radius: Theme.radiusSm
                        color: Theme.surfaceContainerLow
                        implicitHeight: resultText.implicitHeight + Theme.spacingMd * 2

                        Text {
                            id: resultText
                            anchors.left: parent.left
                            anchors.leftMargin: Theme.spacingMd
                            anchors.right: parent.right
                            anchors.rightMargin: Theme.spacingMd
                            anchors.verticalCenter: parent.verticalCenter
                            text: dialog.resultLine(modelData)
                            color: Theme.onSurfaceColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("bodyMedium").size
                            wrapMode: Text.Wrap
                        }
                    }
                }
            }
        }

        RowLayout {
            Layout.fillWidth: true
            Layout.alignment: Qt.AlignRight
            spacing: Theme.spacingMd

            LibraryButton {
                text: I18n.tr("关闭")
                filled: true
                onClicked: dialog.close()
            }
        }
    }
}
