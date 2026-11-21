import QtQuick
import "../"

// 歌词「显示行组合」编辑器：音译 / 原文 / 翻译三行可上下排序，每行带显示/隐藏小按钮。
// 普通歌词与悬浮歌词共用本组件，保证两页样式一致。
//
// 调用方提供：
//   order        当前顺序字符串（ORIGINAL / TRANSLATION / PHONETIC，下划线分隔）
//   labelFor(key) 行类型显示名
//   exampleFor(key) 预览示例文本（返回空串则该行不显示示例）
//   scaleFor(key) 预览时的字号比例
//   isVisible(key) 该行是否显示（小眼睛状态）
//   onReordered(order) / onVisibilityChanged(key, visible) 由调用方写设置
Item {
    id: root

    property string order: "ORIGINAL_TRANSLATION_PHONETIC"
    property var labelFor: function(key) { return key }
    property var exampleFor: function(key) { return "" }
    property var scaleFor: function(key) { return 1.0 }
    property var isVisible: function(key) { return true }
    property bool showPreview: true
    property var previewFontFamily: Theme.fontFamily
    property var previewAlignment: Text.AlignLeft

    signal reordered(string order)
    signal visibilityChanged(string key, bool visible)

    readonly property var validKeys: ["ORIGINAL", "TRANSLATION", "PHONETIC"]

    width: parent ? parent.width : 0
    implicitHeight: mainColumn.implicitHeight

    function parts() {
        const raw = String(root.order || "").split("_")
        const result = []
        for (let i = 0; i < raw.length; i++) {
            if (root.validKeys.indexOf(raw[i]) >= 0 && result.indexOf(raw[i]) < 0)
                result.push(raw[i])
        }
        for (let i = 0; i < root.validKeys.length; i++) {
            if (result.indexOf(root.validKeys[i]) < 0)
                result.push(root.validKeys[i])
        }
        return result
    }

    function swap(index, delta) {
        const list = root.parts()
        const target = index + delta
        if (target < 0 || target >= list.length)
            return
        const tmp = list[index]
        list[index] = list[target]
        list[target] = tmp
        root.reordered(list.join("_"))
    }

    Column {
        id: mainColumn
        width: parent.width
        spacing: Theme.spacingSm

        Column {
            width: parent.width
            spacing: 6

            Repeater {
                model: root.parts()

                delegate: Rectangle {
                    required property var modelData
                    required property int index

                    readonly property string kind: modelData
                    readonly property bool shown: root.isVisible(kind)

                    width: parent.width
                    height: 46
                    radius: Theme.radiusSm
                    color: Theme.withAlpha(Theme.onSurfaceColor, 0.04)
                    opacity: shown ? 1.0 : 0.55

                    Row {
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        anchors.leftMargin: Theme.spacingMd
                        anchors.rightMargin: Theme.spacingSm
                        spacing: Theme.spacingSm

                        Text {
                            width: 24
                            anchors.verticalCenter: parent.verticalCenter
                            text: index + 1 + "."
                            color: Theme.onSurfaceVariantColor
                            font.family: Theme.fontFamily
                            font.pixelSize: 13
                        }

                        Text {
                            width: 56
                            anchors.verticalCenter: parent.verticalCenter
                            text: root.labelFor(kind)
                            color: Theme.onSurfaceColor
                            font.family: Theme.fontFamily
                            font.pixelSize: 14
                            font.weight: Font.Medium
                        }

                        Item {
                            width: Math.max(0, parent.width - 24 - 56 - 30 * 3 - Theme.spacingSm * 5)
                            height: 1
                        }

                        // 小眼睛：控制该行是否显示，替代原来分散的「显示翻译 / 显示音译」开关。
                        IconButton {
                            icon: shown ? "visibility" : "visibility_off"
                            iconSize: 18
                            size: 30
                            tooltip: shown ? I18n.tr("隐藏此行") : I18n.tr("显示此行")
                            onClicked: root.visibilityChanged(kind, !shown)
                        }

                        IconButton {
                            icon: "arrow_upward"
                            iconSize: 18
                            size: 30
                            enabled: index > 0
                            onClicked: root.swap(index, -1)
                        }

                        IconButton {
                            icon: "arrow_downward"
                            iconSize: 18
                            size: 30
                            enabled: index < 2
                            onClicked: root.swap(index, 1)
                        }
                    }
                }
            }
        }

        // 实时预览：按当前顺序与字号比例渲染，隐藏的行不出现。
        Rectangle {
            visible: root.showPreview
            width: parent.width
            height: previewColumn.implicitHeight + 24
            radius: Theme.radiusMd
            color: Theme.surfaceContainerHighest
            clip: true

            Column {
                id: previewColumn
                anchors.centerIn: parent
                width: parent.width - 32
                spacing: 6

                Repeater {
                    model: root.parts()

                    delegate: Text {
                        required property var modelData
                        visible: root.isVisible(modelData) && root.exampleFor(modelData) !== ""
                        width: parent.width
                        text: root.exampleFor(modelData)
                        color: modelData === "ORIGINAL" ? Theme.onSurfaceColor : Theme.onSurfaceVariantColor
                        font.family: root.previewFontFamily
                        font.pixelSize: 20 * root.scaleFor(modelData)
                        horizontalAlignment: root.previewAlignment
                        wrapMode: Text.Wrap
                    }
                }
            }
        }
    }
}
