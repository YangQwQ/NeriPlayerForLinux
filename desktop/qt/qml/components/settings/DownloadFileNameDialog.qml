import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"

// 下载文件名模板编辑对话框（对齐 Android 存储与缓存）。
// openFor(current) 打开；accepted(template) 回传非空模板，空串表示恢复默认。
Dialog {
    id: root

    readonly property string defaultTemplate: "%title% - %artist% - %album% - %source%"
    readonly property var placeholders: [
        "%title%", "%artist%", "%album%", "%source%",
        "%id%", "%audioId%", "%subAudioId%", "%hash%",
    ]
    property string templateText: ""

    signal accepted(string template)

    parent: Overlay.overlay
    anchors.centerIn: parent
    modal: true
    width: Math.min(620, parent ? parent.width - 32 : 620)
    padding: Theme.spacingXl
    closePolicy: Popup.CloseOnEscape

    function openFor(current) {
        root.templateText = current && current !== "" ? current : root.defaultTemplate
        field.text = root.templateText
        root.open()
    }

    /** 前端预览：用示例字段做占位符替换，与后端渲染规则保持一致。 */
    function preview(text) {
        const t = (text && text.trim() !== "") ? text : root.defaultTemplate
        const demo = {
            "%title%": I18n.tr("歌曲标题"),
            "%artist%": I18n.tr("歌手"),
            "%album%": I18n.tr("专辑"),
            "%source%": "netease",
            "%id%": "123456",
            "%audioId%": "123456",
            "%subAudioId%": "0",
            "%hash%": "a1b2c3d4e5f6",
        }
        let out = t
        for (const key in demo)
            out = out.split(key).join(demo[key])
        if (out.trim() === "")
            out = root.defaultTemplate.split("%title%").join(demo["%title%"])
                      .split("%artist%").join(demo["%artist%"])
                      .split("%album%").join(demo["%album%"])
                      .split("%source%").join(demo["%source%"])
        return out + ".mp3"
    }

    background: Rectangle {
        radius: Theme.radiusXxl
        color: Theme.surfaceContainerHigh
        implicitWidth: 620
        implicitHeight: dlgColumn.implicitHeight + Theme.spacingXl * 2
    }

    contentItem: ColumnLayout {
        id: dlgColumn

        spacing: Theme.spacingLg

        Text {
            Layout.fillWidth: true
            text: I18n.tr("下载文件名格式")
            color: Theme.onSurfaceColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("titleMedium").size
            font.weight: Font.DemiBold
        }

        Text {
            Layout.fillWidth: true
            text: I18n.tr("使用占位符自定义下载文件名，扩展名会自动附加。")
            wrapMode: Text.Wrap
            color: Theme.onSurfaceVariantColor
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("bodyMedium").size
        }

        TextField {
            id: field

            Layout.fillWidth: true
            text: root.templateText
            selectByMouse: true
            placeholderText: root.defaultTemplate
            onTextChanged: root.templateText = text
        }

        // 占位符快捷插入
        Flow {
            Layout.fillWidth: true
            spacing: Theme.spacingSm

            Repeater {
                model: root.placeholders

                Button {
                    text: modelData
                    flat: true
                    onClicked: {
                        field.insert(field.cursorPosition, modelData)
                        field.forceActiveFocus()
                    }
                }
            }
        }

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 4

            Text {
                Layout.fillWidth: true
                text: I18n.tr("预览")
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("labelMedium").size
            }
            Text {
                Layout.fillWidth: true
                text: root.preview(root.templateText)
                wrapMode: Text.WrapAnywhere
                color: Theme.primary
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("bodyMedium").size
            }
        }

        RowLayout {
            Layout.fillWidth: true
            spacing: Theme.spacingSm

            Button {
                text: I18n.tr("恢复默认")
                flat: true
                onClicked: {
                    root.templateText = root.defaultTemplate
                    field.text = root.templateText
                }
            }

            Item { Layout.fillWidth: true }

            Button {
                text: I18n.tr("取消")
                flat: true
                onClicked: root.close()
            }

            Button {
                text: I18n.tr("确定")
                highlighted: true
                onClicked: {
                    const value = root.templateText.trim()
                    root.accepted(value === root.defaultTemplate ? "" : value)
                    root.close()
                }
            }
        }
    }
}
