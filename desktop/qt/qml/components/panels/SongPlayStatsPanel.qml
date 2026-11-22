import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../library"
import "../library/Format.js" as Format

// 「播放统计」对话框：调用 stats.song 读取当前歌曲的精确单曲统计（对齐 Android 的 TrackStat 展示）。
// 首次播放只按天分桶（firstStatDay），精确到毫秒的首次时间在桌面端没有采集，故按真实可用粒度展示。
Dialog {
    id: dialog

    property var song: null
    property var stat: null
    property bool loading: false
    property string errorText: ""

    function openFor(target) {
        dialog.song = target
        dialog.stat = null
        dialog.errorText = ""
        dialog.open()
        dialog.reload()
    }

    function reload() {
        if (!dialog.song || !dialog.song.key) {
            dialog.errorText = I18n.tr("当前没有播放歌曲")
            return
        }
        dialog.loading = true
        Api.call("stats.song", { songKey: dialog.song.key }).then(function(result) {
            dialog.loading = false
            const value = result && result.stat ? result.stat : null
            if (value && value.hasData) {
                dialog.stat = value
                dialog.errorText = ""
            } else {
                dialog.stat = null
                dialog.errorText = I18n.tr("这首歌还没有播放统计记录")
            }
        }).catch(function(error) {
            dialog.loading = false
            dialog.errorText = error && error.message ? error.message : I18n.tr("加载播放统计失败")
        })
    }

    function formatStatDay(day) {
        if (!day)
            return I18n.tr("未知")
        const parts = String(day).split("-")
        if (parts.length !== 3)
            return String(day)
        return parts[0] + "/" + parts[1] + "/" + parts[2]
    }

    parent: Overlay.overlay
    anchors.centerIn: parent
    modal: true
    width: Math.min(400, parent ? parent.width - 48 : 400)
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
            text: I18n.tr("播放统计")
            color: Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("titleMedium").size
            font.weight: Font.DemiBold
        }

        Text {
            Layout.fillWidth: true
            text: dialog.song && dialog.song.title ? String(dialog.song.title) : I18n.tr("当前歌曲")
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodyMedium").size
            elide: Text.ElideRight
        }

        LoadingState {
            Layout.fillWidth: true
            visible: dialog.loading
            text: I18n.tr("正在加载播放统计…")
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

        Rectangle {
            Layout.fillWidth: true
            visible: !dialog.loading && dialog.stat !== null
            radius: Theme.radiusMd
            color: Theme.surfaceContainerLow
            implicitHeight: statsColumn.implicitHeight + Theme.spacingLg * 2

            Column {
                id: statsColumn
                x: Theme.spacingLg
                y: Theme.spacingLg
                width: parent.width - Theme.spacingLg * 2
                spacing: Theme.spacingMd

                RowLayout {
                    width: parent.width
                    spacing: Theme.spacingMd
                    Text {
                        Layout.fillWidth: true
                        text: I18n.tr("播放次数")
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("bodyMedium").size
                    }
                    Text {
                        text: String((dialog.stat && dialog.stat.playCount) || 0) + I18n.tr(" 次")
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("bodyMedium").size
                        font.weight: Font.DemiBold
                    }
                }

                RowLayout {
                    width: parent.width
                    spacing: Theme.spacingMd
                    Text {
                        Layout.fillWidth: true
                        text: I18n.tr("累计收听")
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("bodyMedium").size
                    }
                    Text {
                        text: Format.formatLongDuration((dialog.stat && dialog.stat.listenMs) || 0)
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("bodyMedium").size
                        font.weight: Font.DemiBold
                    }
                }

                RowLayout {
                    width: parent.width
                    spacing: Theme.spacingMd
                    visible: !!(dialog.stat && dialog.stat.lastPlayed)
                    Text {
                        Layout.fillWidth: true
                        text: I18n.tr("最近播放")
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("bodyMedium").size
                    }
                    Text {
                        text: Format.formatDateTime((dialog.stat && dialog.stat.lastPlayed) || 0)
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("bodyMedium").size
                        font.weight: Font.DemiBold
                    }
                }

                RowLayout {
                    width: parent.width
                    spacing: Theme.spacingMd
                    visible: !!(dialog.stat && dialog.stat.firstStatDay)
                    Text {
                        Layout.fillWidth: true
                        text: I18n.tr("首次统计日期")
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("bodyMedium").size
                    }
                    Text {
                        text: dialog.formatStatDay(dialog.stat ? dialog.stat.firstStatDay : "")
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("bodyMedium").size
                        font.weight: Font.DemiBold
                    }
                }

                Text {
                    Layout.fillWidth: true
                    text: I18n.tr("听歌记录来自本机播放统计，并非账号完整历史；首次统计仅精确到天")
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelSmall").size
                    wrapMode: Text.Wrap
                }
            }
        }

        RowLayout {
            Layout.fillWidth: true
            Layout.alignment: Qt.AlignRight

            LibraryButton {
                text: I18n.tr("查看完整统计")
                onClicked: {
                    dialog.close()
                    Nav.openByName("stats")
                }
            }

            LibraryButton {
                text: I18n.tr("关闭")
                filled: true
                onClicked: dialog.close()
            }
        }
    }
}
