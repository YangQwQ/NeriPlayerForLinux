import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import ".."
import "../library"

// 「编辑歌曲信息」对话框：对齐安卓端，可编辑本地歌曲的标题 / 歌手 / 专辑并写回文件标签。
// 非本地歌曲（无 filePath）直接禁用表单并说明原因。写入走 library.updateSongMetadata；
// 后端写回后会更新内存中的 Song 并由 StatePublisher 广播 library.changed。
Dialog {
    id: dialog

    property var song: null
    property bool editable: !!(dialog.song && dialog.song.source === "LOCAL" && dialog.song.filePath)
    property bool busy: false

    function openFor(target) {
        dialog.song = target
        titleField.text = target && target.title ? String(target.title) : ""
        artistField.text = target && target.artist ? String(target.artist) : ""
        albumField.text = target && target.album ? String(target.album) : ""
        dialog.busy = false
        dialog.open()
    }

    function submit() {
        if (!dialog.editable || dialog.busy || !dialog.song)
            return
        dialog.busy = true
        Api.call("library.updateSongMetadata", {
            key: dialog.song.key,
            title: titleField.text,
            artist: artistField.text,
            album: albumField.text
        }).then(function(result) {
            dialog.busy = false
            if (result && result.song)
                dialog.song = result.song
            Toast.show(I18n.tr("歌曲信息已保存"))
            dialog.close()
        }).catch(function(error) {
            dialog.busy = false
            Toast.show((error && error.message ? error.message : I18n.tr("保存歌曲信息失败")), "error")
        })
    }

    parent: Overlay.overlay
    anchors.centerIn: parent
    modal: true
    width: Math.min(440, parent ? parent.width - 48 : 440)
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

        NeriIcon {
            Layout.alignment: Qt.AlignHCenter
            name: "edit"
            iconSize: 36
            color: Theme.onSurfaceVariantColor
        }

        Text {
            Layout.fillWidth: true
            text: I18n.tr("编辑歌曲信息")
            horizontalAlignment: Text.AlignHCenter
            color: Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("titleMedium").size
            font.weight: Font.DemiBold
        }

        Text {
            Layout.fillWidth: true
            visible: !dialog.editable
            text: I18n.tr("仅本地歌曲可编辑元数据；在线歌曲没有可写入的文件。")
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            color: Theme.error
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodyMedium").size
        }

        Text {
            Layout.fillWidth: true
            visible: dialog.editable
            text: I18n.tr("留空将清空该字段")
            horizontalAlignment: Text.AlignHCenter
            wrapMode: Text.Wrap
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodySmall").size
        }

        ColumnLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingSm

            TextField {
                id: titleField
                Layout.fillWidth: true
                enabled: dialog.editable && !dialog.busy
                placeholderText: I18n.tr("标题")
                selectByMouse: true
                color: Theme.onSurfaceColor
                placeholderTextColor: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("bodyLarge").size
                leftPadding: Theme.spacingMd
                rightPadding: Theme.spacingMd
                topPadding: 10
                bottomPadding: 10
                background: Rectangle {
                    radius: Theme.radiusSm
                    color: Theme.surfaceContainerHighest
                    border.width: titleField.activeFocus ? 1 : 0
                    border.color: Theme.primary
                }
            }

            TextField {
                id: artistField
                Layout.fillWidth: true
                enabled: dialog.editable && !dialog.busy
                placeholderText: I18n.tr("歌手")
                selectByMouse: true
                color: Theme.onSurfaceColor
                placeholderTextColor: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("bodyLarge").size
                leftPadding: Theme.spacingMd
                rightPadding: Theme.spacingMd
                topPadding: 10
                bottomPadding: 10
                background: Rectangle {
                    radius: Theme.radiusSm
                    color: Theme.surfaceContainerHighest
                    border.width: artistField.activeFocus ? 1 : 0
                    border.color: Theme.primary
                }
            }

            TextField {
                id: albumField
                Layout.fillWidth: true
                enabled: dialog.editable && !dialog.busy
                placeholderText: I18n.tr("专辑")
                selectByMouse: true
                color: Theme.onSurfaceColor
                placeholderTextColor: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("bodyLarge").size
                leftPadding: Theme.spacingMd
                rightPadding: Theme.spacingMd
                topPadding: 10
                bottomPadding: 10
                background: Rectangle {
                    radius: Theme.radiusSm
                    color: Theme.surfaceContainerHighest
                    border.width: albumField.activeFocus ? 1 : 0
                    border.color: Theme.primary
                }
            }
        }

        Text {
            Layout.fillWidth: true
            visible: !!dialog.song && !!dialog.song.filePath
            text: I18n.tr("文件：") + String((dialog.song && dialog.song.filePath) || "")
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodySmall").size
            wrapMode: Text.WrapAnywhere
        }

        RowLayout {
            Layout.fillWidth: true
            Layout.alignment: Qt.AlignRight
            spacing: Theme.spacingSm

            LibraryButton {
                text: I18n.tr("取消")
                onClicked: dialog.close()
            }

            LibraryButton {
                text: dialog.busy ? I18n.tr("保存中") : I18n.tr("保存")
                filled: true
                enabled: dialog.editable && !dialog.busy
                onClicked: dialog.submit()
            }
        }
    }
}
