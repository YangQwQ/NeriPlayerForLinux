import QtQuick
import QtQuick.Controls
import ".."
import "../settings"
import "../library"

// 播放页「切换音质」对话框。
// 复用 quality 子任务提供的 player.switchQuality：只对当前歌曲临时生效，不改全局默认。
// 档位命名与设置页保持一致：standard / higher / exhigh / lossless / hires。
Dialog {
    id: dialog

    property var song: null
    property string selected: "exhigh"

    readonly property var qualityOptions: [
        { value: "standard", label: I18n.tr("标准") },
        { value: "higher", label: I18n.tr("较高") },
        { value: "exhigh", label: I18n.tr("极高") },
        { value: "lossless", label: I18n.tr("无损") },
        { value: "hires", label: "Hi-Res" }
    ]

    function labelOf(value) {
        for (let i = 0; i < dialog.qualityOptions.length; i++) {
            if (dialog.qualityOptions[i].value === value)
                return dialog.qualityOptions[i].label
        }
        return String(value || "")
    }

    function openFor(target) {
        dialog.song = target
        const current = Settings.get("qualityPreference", "exhigh")
        dialog.selected = qualityOptions.some(function(item) { return item.value === current })
                          ? String(current) : "exhigh"
        dialog.open()
    }

    function apply() {
        Api.call("player.switchQuality", { quality: dialog.selected }).then(function() {
            Toast.show(I18n.tr("已切换到") + labelOf(dialog.selected))
            dialog.close()
        }).catch(function(error) {
            Toast.show(error && error.message ? error.message : I18n.tr("切换音质失败"), "error")
        })
    }

    parent: Overlay.overlay
    anchors.centerIn: parent
    modal: true
    width: Math.min(420, parent ? parent.width - 48 : 420)
    padding: Theme.spacingXl
    closePolicy: Popup.CloseOnEscape | Popup.CloseOnPressOutside

    background: Rectangle {
        radius: Theme.radiusXxl
        color: Theme.surfaceContainerHigh
        border.width: 1
        border.color: Theme.withAlpha(Theme.outlineVariant, 0.6)
    }

    contentItem: Column {
        id: body
        spacing: Theme.spacingMd
        width: parent ? parent.width : 360

        Text {
            width: parent.width
            text: I18n.tr("切换音质")
            color: Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("titleMedium").size
            font.weight: Font.DemiBold
        }

        Text {
            width: parent.width
            text: dialog.song && dialog.song.title ? String(dialog.song.title) : I18n.tr("当前歌曲")
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodyMedium").size
            elide: Text.ElideRight
        }

        Text {
            width: parent.width
            text: I18n.tr("选择音质")
            color: Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodyMedium").size
        }

        SegmentedOptions {
            width: parent.width
            options: dialog.qualityOptions
            value: dialog.selected
            onSelected: function(value) { dialog.selected = String(value) }
        }

        Text {
            width: parent.width
            text: I18n.tr("当前默认音质：") + dialog.labelOf(Settings.get("qualityPreference", "exhigh"))
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodySmall").size
            wrapMode: Text.Wrap
        }

        Text {
            width: parent.width
            text: I18n.tr("本次切换仅对当前歌曲临时生效，不改变全局默认音质设置。实际可用档位取决于音源与登录状态。")
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodySmall").size
            wrapMode: Text.Wrap
        }

        Item { width: 1; height: Theme.spacingSm }

        Row {
            anchors.right: parent.right
            spacing: Theme.spacingMd

            LibraryButton {
                text: I18n.tr("取消")
                onClicked: dialog.close()
            }

            LibraryButton {
                text: I18n.tr("切换")
                filled: true
                onClicked: dialog.apply()
            }
        }
    }
}
