import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"
import "../library"

// 后端日志查看器（自包含，阶段 B）。
//
// 数据来自 `debug.recentLogs`（后端环形缓冲，1000 条，入环前已脱敏）。
// 协议见 desktop/docs/protocol-config.md。
//
// 用法（入口由集成负责人接线）：
//   debugLogViewer.openViewer()
//
// 设计要点：
// - 后端缓冲是「新 → 旧」下发的，这里反转成时间正序展示，符合终端阅读习惯；
// - 行 key 用「时间戳 + 序号」而不是下标：环形缓冲会淘汰旧条目，下标会整体前移，
//   用下标当 key 会让列表在每次轮询后整片重建；
// - 暂停只冻结「刷新显示」，轮询仍在继续拉取，恢复后立刻显示最新内容；
// - 级别筛选/搜索都在前端做：后端只按 minLevel 粗筛，前端再按级别集合与关键字精筛，
//   这样切换筛选条件不需要重新请求；
// - 复制走隐藏 TextEdit（Qt6 QML 没有 Clipboard 单例），与播放页复制歌词的做法一致。
Dialog {
    id: root

    // ------------------------------------------------------------------ 状态

    /** 原始条目（时间正序），来自最近一次轮询。 */
    property var entries: []
    /** 暂停：冻结当前显示内容，但仍继续轮询。 */
    property bool paused: false
    /** 自动跟随最新一条。用户手动上滑时自动关闭。 */
    property bool follow: true

    property bool loading: false
    property string errorText: ""

    /** 级别筛选（多选）。空数组表示全部级别。 */
    property var levelFilter: []
    /** 全文搜索（大小写不敏感）。 */
    property string searchText: ""

    /** 后端缓冲统计，用于底部提示。 */
    property int backendTotal: 0
    property int backendCapacity: 1000

    /** 轮询间隔：1.5s，与调查文档建议一致。 */
    readonly property int pollIntervalMs: 1500
    /** 单次拉取条数。 */
    readonly property int fetchLimit: 500

    readonly property var allLevels: ["DEBUG", "INFO", "WARN", "ERROR"]

    /** 应用筛选后的可见条目（时间正序）。 */
    readonly property var visibleEntries: {
        const result = []
        const source = root.entries
        const keys = root.searchText.toLowerCase()
        const levels = root.levelFilter
        for (let i = 0; i < source.length; i++) {
            const entry = source[i]
            if (levels.length > 0 && levels.indexOf(entry.level) < 0)
                continue
            if (keys !== "") {
                const haystack = (String(entry.target) + " " + String(entry.message)).toLowerCase()
                if (haystack.indexOf(keys) < 0)
                    continue
            }
            result.push(entry)
        }
        return result
    }

    /** 从打开以来累计记录条数（后端重启会归零，这里跟着后端走）。 */
    readonly property int shownCount: root.visibleEntries.length

    parent: Overlay.overlay
    anchors.centerIn: parent
    modal: true
    width: Math.min(900, parent ? parent.width - Theme.spacingXl * 2 : 900)
    height: Math.min(640, parent ? parent.height - Theme.spacingXl * 2 : 640)
    padding: 0
    closePolicy: Popup.CloseOnEscape

    // ------------------------------------------------------------------ 对外接口

    function openViewer() {
        root.errorText = ""
        root.paused = false
        root.follow = true
        root.open()
        root.reload()
    }

    /** 拉一次后端缓冲。失败不清空已有内容，只记错误信息。 */
    function reload() {
        if (typeof Backend !== "undefined" && Backend && Backend.connected === false) {
            root.loading = true
            root.errorText = ""
            return
        }
        root.loading = true
        Api.call("debug.recentLogs", { limit: root.fetchLimit }).then(function(result) {
            root.loading = false
            root.errorText = ""
            const incoming = []
            const logs = result && result.logs ? result.logs : []
            // 后端给的是新 → 旧，反转成时间正序
            for (let i = logs.length - 1; i >= 0; i--)
                incoming.push(logs[i])
            root.backendTotal = result && result.total !== undefined ? Number(result.total) : incoming.length
            root.backendCapacity = result && result.capacity !== undefined ? Number(result.capacity) : 1000
            if (root.paused)
                return
            root.entries = incoming
            if (root.follow)
                root.scrollToBottom()
        }).catch(function(error) {
            root.loading = false
            root.errorText = error && error.message ? error.message : I18n.tr("读取日志失败")
        })
    }

    function toggleLevel(level) {
        const next = root.levelFilter.slice()
        const index = next.indexOf(level)
        if (index >= 0)
            next.splice(index, 1)
        else
            next.push(level)
        root.levelFilter = next
    }

    function isLevelActive(level) {
        return root.levelFilter.indexOf(level) >= 0
    }

    /** 只留这一个级别（右键/双击用；不需要就当作快捷操作） */
    function soloLevel(level) {
        root.levelFilter = [level]
    }

    function scrollToBottom() {
        // 等一次布局：模型刚更新时 contentHeight 还是旧的
        Qt.callLater(function() {
            if (flick && flick.contentHeight > flick.height)
                flick.contentY = flick.contentHeight - flick.height
        })
    }

    /** 复制当前可见行（与原版查看器一致的「复制所见」语义）。 */
    function copyVisible() {
        const lines = []
        for (let i = 0; i < root.visibleEntries.length; i++) {
            const entry = root.visibleEntries[i]
            let line = "[" + root.formatTime(entry.timestampMs) + "] "
                       + "[" + entry.level + "] [" + entry.target + "] " + entry.message
            if (entry.repeat && entry.repeat > 1)
                line += " (x" + entry.repeat + ")"
            lines.push(line)
        }
        if (lines.length === 0) {
            Toast.show(I18n.tr("没有可复制的日志"), "info")
            return
        }
        clipHelper.text = lines.join("\n")
        clipHelper.selectAll()
        clipHelper.copy()
        Toast.show(I18n.tr("已复制 ") + lines.length + I18n.tr(" 条日志"), "info")
    }

    function copyEntry(entry) {
        if (!entry)
            return
        clipHelper.text = "[" + root.formatTime(entry.timestampMs) + "] "
                          + "[" + entry.level + "] [" + entry.target + "] " + entry.message
        clipHelper.selectAll()
        clipHelper.copy()
        Toast.show(I18n.tr("已复制该行日志"), "info")
    }

    // ------------------------------------------------------------------ 显示辅助

    function formatTime(ms) {
        const value = Number(ms) || 0
        if (value <= 0)
            return "--:--:--"
        return Qt.formatDateTime(new Date(value), "HH:mm:ss")
    }

    function levelColor(level) {
        switch (level) {
        case "ERROR":
            return Theme.error
        case "WARN":
            return Theme.tertiary
        case "DEBUG":
            return Theme.onSurfaceVariantColor
        default:
            return Theme.primary
        }
    }

    function levelLabel(level) {
        switch (level) {
        case "ERROR":
            return I18n.tr("错误")
        case "WARN":
            return I18n.tr("警告")
        case "INFO":
            return I18n.tr("信息")
        case "DEBUG":
            return I18n.tr("调试")
        default:
            return level
        }
    }

    // ------------------------------------------------------------------ 后台轮询

    Timer {
        // 只在对话框可见时轮询：后台不该为一个看不见的面板持续发 IPC
        interval: root.pollIntervalMs
        repeat: true
        running: root.visible
        onTriggered: root.reload()
    }

    property Connections backendConnection: Connections {
        target: Backend

        function onConnectedChanged() {
            if (Backend.connected && root.visible)
                root.reload()
        }
    }

    // ------------------------------------------------------------------ 界面

    background: Rectangle {
        radius: Theme.radiusXxl
        color: Theme.surfaceContainerHigh
    }

    contentItem: ColumnLayout {
        id: content

        spacing: 0

        // ---- 标题栏 ----
        Item {
            Layout.fillWidth: true
            Layout.preferredHeight: headerRow.implicitHeight + Theme.spacingLg * 2

            RowLayout {
                id: headerRow
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                anchors.leftMargin: Theme.spacingXl
                anchors.rightMargin: Theme.spacingXl
                spacing: Theme.spacingMd

                NeriIcon {
                    name: "bug_report"
                    iconSize: 22
                    color: Theme.primary
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 2

                    Text {
                        Layout.fillWidth: true
                        text: I18n.tr("运行日志")
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("titleMedium").size
                        font.weight: Font.DemiBold
                    }

                    Text {
                        Layout.fillWidth: true
                        text: root.paused
                              ? I18n.tr("已暂停刷新，后台仍在收集")
                              : I18n.tr("后端最近日志（已脱敏）")
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("bodySmall").size
                    }
                }

                IconButton {
                    icon: "refresh"
                    iconSize: 18
                    tooltip: I18n.tr("立即刷新")
                    onClicked: {
                        root.follow = true
                        root.reload()
                    }
                }

                IconButton {
                    icon: root.paused ? "play_arrow" : "pause"
                    iconSize: 18
                    tooltip: root.paused ? I18n.tr("继续") : I18n.tr("暂停")
                    onClicked: {
                        root.paused = !root.paused
                        if (!root.paused && root.follow)
                            root.reload()
                    }
                }

                IconButton {
                    icon: "content_copy"
                    iconSize: 18
                    tooltip: I18n.tr("复制可见日志")
                    onClicked: root.copyVisible()
                }

                IconButton {
                    icon: "close"
                    iconSize: 18
                    tooltip: I18n.tr("关闭")
                    onClicked: root.close()
                }
            }
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 1
            color: Theme.withAlpha(Theme.outlineVariant, 0.6)
        }

        // ---- 筛选栏 ----
        RowLayout {
            Layout.fillWidth: true
            Layout.leftMargin: Theme.spacingXl
            Layout.rightMargin: Theme.spacingXl
            Layout.topMargin: Theme.spacingMd
            spacing: Theme.spacingSm

            // 级别筛选 chip（多选）
            Repeater {
                model: root.allLevels

                delegate: Rectangle {
                    id: levelChip

                    required property var modelData

                    readonly property bool active: root.isLevelActive(levelChip.modelData)

                    implicitWidth: chipRow.implicitWidth + Theme.spacingMd * 2
                    implicitHeight: 30
                    radius: Theme.radiusSm
                    color: active ? Theme.secondaryContainer
                                  : (chipHover.hovered ? Theme.withAlpha(Theme.onSurfaceColor, 0.06)
                                                       : "transparent")
                    border.width: active ? 0 : 1
                    border.color: Theme.outlineVariant

                    Behavior on color {
                        ColorAnimation {
                            duration: Theme.motionShort
                        }
                    }

                    Row {
                        id: chipRow
                        anchors.centerIn: parent
                        spacing: 6

                        Text {
                            anchors.verticalCenter: parent.verticalCenter
                            text: root.levelLabel(levelChip.modelData)
                            color: levelChip.active ? Theme.onSecondaryContainerColor
                                                    : root.levelColor(levelChip.modelData)
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("labelMedium").size
                            font.weight: Font.Medium
                        }
                    }

                    HoverHandler {
                        id: chipHover
                        cursorShape: Qt.PointingHandCursor
                    }

                    MouseArea {
                        anchors.fill: parent
                        acceptedButtons: Qt.LeftButton
                        onClicked: root.toggleLevel(levelChip.modelData)
                        onDoubleClicked: root.soloLevel(levelChip.modelData)
                    }
                }
            }

            // 搜索框
            SettingsTextField {
                id: searchField

                Layout.fillWidth: true
                implicitHeight: 34
                placeholderText: I18n.tr("搜索来源或内容")
                text: root.searchText
                onTextChanged: root.searchText = text
            }

            // 跟随最新
            Rectangle {
                implicitWidth: followRow.implicitWidth + Theme.spacingMd * 2
                implicitHeight: 30
                radius: Theme.radiusSm
                color: root.follow ? Theme.secondaryContainer
                                   : (followHover.hovered ? Theme.withAlpha(Theme.onSurfaceColor, 0.06)
                                                          : "transparent")
                border.width: root.follow ? 0 : 1
                border.color: Theme.outlineVariant

                Row {
                    id: followRow
                    anchors.centerIn: parent
                    spacing: 6

                    NeriIcon {
                        anchors.verticalCenter: parent.verticalCenter
                        name: "arrow_downward"
                        iconSize: 14
                        color: root.follow ? Theme.onSecondaryContainerColor : Theme.onSurfaceVariantColor
                    }

                    Text {
                        anchors.verticalCenter: parent.verticalCenter
                        text: I18n.tr("跟随")
                        color: root.follow ? Theme.onSecondaryContainerColor : Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("labelMedium").size
                    }
                }

                HoverHandler {
                    id: followHover
                    cursorShape: Qt.PointingHandCursor
                }

                MouseArea {
                    anchors.fill: parent
                    onClicked: {
                        root.follow = !root.follow
                        if (root.follow)
                            root.scrollToBottom()
                    }
                }
            }
        }

        // ---- 日志列表 ----
        Flickable {
            id: flick

            Layout.fillWidth: true
            Layout.fillHeight: true
            Layout.topMargin: Theme.spacingSm
            Layout.leftMargin: Theme.spacingMd
            Layout.rightMargin: Theme.spacingMd
            clip: true
            contentWidth: width
            contentHeight: logColumn.implicitHeight
            boundsBehavior: Flickable.StopAtBounds

            // 用户自己往上滚时不打断他：一离开底部就关掉跟随
            onMovementEnded: {
                if (contentHeight <= height)
                    return
                const atBottom = contentY >= contentHeight - height - 4
                if (!atBottom && root.follow)
                    root.follow = false
            }

            NeriScrollBar {
                anchors.right: parent.right
                anchors.top: parent.top
                anchors.bottom: parent.bottom
                width: 8
                flickable: flick
            }

            Column {
                id: logColumn

                width: flick.width
                spacing: 1

                Repeater {
                    model: root.visibleEntries

                    delegate: Rectangle {
                        id: logRow

                        required property var modelData

                        readonly property bool hovered: rowHover.hovered
                        readonly property string stamp: root.formatTime(logRow.modelData.timestampMs)

                        width: logColumn.width - Theme.spacingSm
                        x: 0
                        height: rowContent.implicitHeight + Theme.spacingXs * 2
                        radius: Theme.radiusXs
                        color: hovered ? Theme.withAlpha(Theme.onSurfaceColor, 0.05) : "transparent"

                        HoverHandler {
                            id: rowHover
                        }

                        MouseArea {
                            anchors.fill: parent
                            acceptedButtons: Qt.RightButton
                            onClicked: root.copyEntry(logRow.modelData)
                        }

                        RowLayout {
                            id: rowContent

                            anchors.left: parent.left
                            anchors.right: parent.right
                            anchors.verticalCenter: parent.verticalCenter
                            anchors.leftMargin: Theme.spacingSm
                            anchors.rightMargin: Theme.spacingSm
                            spacing: Theme.spacingSm

                            Text {
                                text: logRow.stamp
                                color: Theme.onSurfaceVariantColor
                                font.family: "monospace"
                                font.pixelSize: Theme.textStyle("labelSmall").size
                            }

                            Text {
                                Layout.preferredWidth: 40
                                text: logRow.modelData.level
                                color: root.levelColor(logRow.modelData.level)
                                font.family: "monospace"
                                font.pixelSize: Theme.textStyle("labelSmall").size
                                font.weight: Font.DemiBold
                            }

                            Text {
                                Layout.preferredWidth: Math.min(150, logRow.width * 0.18)
                                text: logRow.modelData.target
                                color: Theme.secondary
                                font.family: "monospace"
                                font.pixelSize: Theme.textStyle("labelSmall").size
                                elide: Text.ElideRight
                            }

                            Text {
                                Layout.fillWidth: true
                                text: logRow.modelData.message
                                        + (logRow.modelData.repeat > 1 ? "  ×" + logRow.modelData.repeat : "")
                                color: Theme.onSurfaceColor
                                font.family: "monospace"
                                font.pixelSize: Theme.textStyle("labelSmall").size
                                wrapMode: Text.WrapAtWordBoundaryOrAnywhere
                            }
                        }
                    }
                }
            }
        }

        // ---- 空 / 错误占位（覆盖在列表区域上，保证在可视区内真正居中） ----
        Item {
            visible: root.shownCount === 0
            anchors.left: flick.left
            anchors.right: flick.right
            anchors.top: flick.top
            anchors.bottom: flick.bottom

            ColumnLayout {
                anchors.centerIn: parent
                spacing: Theme.spacingSm

                NeriIcon {
                    Layout.alignment: Qt.AlignHCenter
                    name: root.errorText !== "" ? "error" : "bug_report"
                    iconSize: 30
                    color: root.errorText !== "" ? Theme.error : Theme.onSurfaceVariantColor
                }

                Text {
                    Layout.fillWidth: true
                    horizontalAlignment: Text.AlignHCenter
                    text: {
                        if (root.errorText !== "")
                            return root.errorText
                        if (root.entries.length > 0)
                            return I18n.tr("没有符合筛选条件的日志")
                        if (root.loading)
                            return I18n.tr("正在读取日志…")
                        return I18n.tr("还没有日志")
                    }
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodySmall").size
                    wrapMode: Text.Wrap
                }
            }
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 1
            color: Theme.withAlpha(Theme.outlineVariant, 0.6)
        }

        // ---- 状态栏 ----
        RowLayout {
            Layout.fillWidth: true
            Layout.margins: Theme.spacingMd
            spacing: Theme.spacingSm

            Text {
                Layout.fillWidth: true
                text: I18n.tr("显示 ") + root.shownCount
                      + I18n.tr(" / 缓冲 ") + root.backendTotal
                      + I18n.tr(" 条（上限 ") + root.backendCapacity + I18n.tr("）")
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("labelSmall").size
                elide: Text.ElideRight
            }

            Text {
                visible: root.levelFilter.length > 0 || root.searchText !== "" || root.paused
                text: I18n.tr("已筛选")
                color: Theme.primary
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("labelSmall").size
            }

            LibraryButton {
                text: I18n.tr("清空筛选")
                enabled: root.levelFilter.length > 0 || root.searchText !== ""
                onClicked: {
                    root.levelFilter = []
                    root.searchText = ""
                    if (searchField)
                        searchField.text = ""
                }
            }
        }
    }

    // 复制用的隐藏编辑框（Qt6 QML 没有 Clipboard 单例）
    property TextEdit clipHelper: TextEdit {
        id: clipHelper
        visible: false
        width: 0
        height: 0
    }
}
