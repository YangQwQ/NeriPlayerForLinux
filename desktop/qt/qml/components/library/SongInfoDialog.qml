import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import ".."
import "Format.js" as Format

// 歌曲信息对话框（共享组件）：展示当前歌曲的标题 / 歌手 / 专辑 / 来源 / 时长 / 音轨号。
// 纯前端展示，不调用后端；字段对应 core.Models.Song。
//
// 用法：songInfoDialog.openFor(song)
Dialog {
    id: root

    property var song: null

    readonly property string songSource: root.song && root.song.source
                                         ? String(root.song.source).toUpperCase() : "LOCAL"

    /** 音源枚举值 → 界面文案（与后端 MediaSource 对应）。 */
    function sourceLabel(source) {
        switch (source) {
        case "LOCAL":
            return I18n.tr("本地")
        case "NETEASE":
            return I18n.tr("网易云")
        case "BILIBILI":
            return I18n.tr("哔哩哔哩")
        case "YOUTUBE":
            return "YouTube"
        default:
            return source || I18n.tr("未知")
        }
    }

    function songValue(text) {
        return text ? String(text) : I18n.tr("未知")
    }

    function openFor(target) {
        root.song = target
        root.open()
    }

    parent: Overlay.overlay
    anchors.centerIn: parent
    modal: true
    width: 420
    padding: Theme.spacingXl
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

    background: Rectangle {
        radius: Theme.radiusXxl
        color: Theme.surfaceContainerHigh
        border.width: 1
        border.color: Theme.withAlpha(Theme.outlineVariant, 0.6)
        implicitWidth: 420
        implicitHeight: infoColumn.implicitHeight + Theme.spacingXl * 2
    }

    contentItem: ColumnLayout {
        id: infoColumn

        spacing: Theme.spacingMd

        Text {
            Layout.fillWidth: true
            text: I18n.tr("歌曲信息")
            color: Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("titleMedium").size
            font.weight: Font.DemiBold
        }

        // 一行「标签: 值」的复用组件
        component InfoRow: RowLayout {
            id: infoRow

            property string labelText: ""
            property string valueText: ""

            spacing: Theme.spacingSm

            Text {
                Layout.preferredWidth: 64
                text: infoRow.labelText
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("bodyMedium").size
            }

            Text {
                Layout.fillWidth: true
                text: infoRow.valueText
                wrapMode: Text.Wrap
                color: Theme.onSurfaceColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("bodyMedium").size
            }
        }

        InfoRow {
            labelText: I18n.tr("标题")
            valueText: root.song ? Format.songTitle(root.song) : I18n.tr("未知")
        }

        InfoRow {
            labelText: I18n.tr("歌手")
            valueText: root.song ? songValue(root.song.artist) : I18n.tr("未知")
        }

        InfoRow {
            labelText: I18n.tr("专辑")
            valueText: root.song ? songValue(root.song.album) : I18n.tr("未知")
        }

        InfoRow {
            labelText: I18n.tr("来源")
            valueText: sourceLabel(root.songSource)
        }

        InfoRow {
            labelText: I18n.tr("时长")
            valueText: Format.formatDuration(root.song ? root.song.durationMs : 0)
        }

        InfoRow {
            labelText: I18n.tr("音轨号")
            valueText: root.song && root.song.trackNumber > 0 ? String(root.song.trackNumber) : I18n.tr("未知")
        }

        Item {
            Layout.fillWidth: true
            Layout.preferredHeight: 1
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingSm

            Item {
                Layout.fillWidth: true
                implicitHeight: 1
            }

            LibraryButton {
                text: I18n.tr("关闭")
                filled: true
                onClicked: root.close()
            }
        }
    }
}
