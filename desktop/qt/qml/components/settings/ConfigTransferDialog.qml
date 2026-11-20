import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import QtQuick.Dialogs
import QtCore
import "../"
import "../library"

// 设置快照导入导出对话框（自包含）。
//
// 用法（入口由集成负责人接线）：
//   configTransferDialog.openDialog()
//
// 语义（与后端一致，见 desktop/docs/protocol-config.md）：
// - 导出的文件只包含 AppSettings（偏好），**不含** Cookie / Token / 账号 / 同步配置，
//   也不含歌单、播放历史、统计等业务数据；用户可以自行 diff 或手改。
// - 导入是「先整份校验、再一次性替换」：本对话框先发一次 dryRun 让用户看清会改哪些字段，
//   确认后才真正导入；校验失败时后端不碰任何设置，这里只显示错误。
// - 导入会覆盖当前全部偏好，所以必然经过二次确认（不可撤销，但可以先用导出的文件回滚）。
Dialog {
    id: root

    // ------------------------------------------------------------------ 状态

    /** 正在执行后端命令（导出或导入），期间禁用按钮。 */
    property bool busy: false
    /** 最近一次失败的原因（原样展示后端 message）。 */
    property string errorText: ""
    /** 最近一次成功的结果，结构见 protocol-config.md 的 config.export / config.import。 */
    property var lastResult: null
    /** 最近一次成功操作的类型："export" / "import"。 */
    property string lastAction: ""

    /** dryRun 的结果（导入预览），null 表示还没有预览。 */
    property var preview: null
    /** 等待二次确认的导入路径。 */
    property string pendingImportPath: ""

    readonly property int changedCount: preview && preview.changedCount !== undefined
                                     ? Number(preview.changedCount) : 0
    readonly property var previewWarnings: preview && preview.warnings ? preview.warnings : []
    readonly property var previewChangedFields: preview && preview.changedFields ? preview.changedFields : []

    signal exported(string path)
    signal imported(var result)

    parent: Overlay.overlay
    anchors.centerIn: parent
    modal: true
    width: 560
    padding: 0
    closePolicy: Popup.CloseOnEscape

    // ------------------------------------------------------------------ 对外接口

    function openDialog() {
        root.errorText = ""
        root.lastResult = null
        root.lastAction = ""
        root.preview = null
        root.pendingImportPath = ""
        root.busy = false
        root.open()
    }

    /**
     * 后端断开时把在途操作复位。
     *
     * 导出/导入都是异步 Promise：后端在请求途中退出（崩溃、被 kill）时回调不会再触发，
     * busy 会一直停在 true，按钮永久置灰。这里在断连时主动解锁并提示重试。
     */
    property Connections backendConnection: Connections {
        target: Backend

        function onConnectedChanged() {
            if (Backend.connected)
                return
            if (root.busy) {
                root.busy = false
                root.errorText = I18n.tr("后端连接已断开，请重试")
            }
        }
    }

    // ------------------------------------------------------------------ 导出

    function requestExport() {
        if (root.busy)
            return
        root.errorText = ""
        exportDialog.open()
    }

    function performExport(path) {
        if (root.busy)
            return
        root.busy = true
        root.errorText = ""
        const params = {}
        if (path)
            params.path = path
        Api.call("config.export", params).then(function(result) {
            root.busy = false
            root.lastAction = "export"
            root.lastResult = result || null
            Toast.show(I18n.tr("设置已导出"), "info")
            root.exported(result && result.path ? result.path : "")
        }).catch(function(error) {
            root.busy = false
            root.errorText = error && error.message ? error.message : I18n.tr("导出失败")
        })
    }

    // ------------------------------------------------------------------ 导入（两步：预览 → 确认）

    function requestImport() {
        if (root.busy)
            return
        root.errorText = ""
        importDialog.open()
    }

    function previewImport(path) {
        if (root.busy || !path)
            return
        root.busy = true
        root.errorText = ""
        root.preview = null
        Api.call("config.import", { path: path, dryRun: true }).then(function(result) {
            root.busy = false
            root.preview = result || null
            root.pendingImportPath = path
            confirmImport.openFor(
                I18n.tr("导入设置快照"),
                root.buildConfirmMessage(),
                I18n.tr("导入")
            )
        }).catch(function(error) {
            root.busy = false
            root.preview = null
            root.errorText = error && error.message ? error.message : I18n.tr("文件校验失败")
        })
    }

    function buildConfirmMessage() {
        const lines = []
        lines.push(I18n.tr("将用这份快照覆盖当前全部界面与播放偏好。此操作不可撤销。"))
        if (root.changedCount > 0)
            lines.push(I18n.tr("会改变 ") + root.changedCount + I18n.tr(" 项设置。"))
        else
            lines.push(I18n.tr("与当前设置没有差异。"))
        lines.push(I18n.tr("不含 Cookie、登录状态与歌单数据。"))
        return lines.join("\n")
    }

    function performImport() {
        if (root.busy || root.pendingImportPath === "")
            return
        const path = root.pendingImportPath
        root.busy = true
        root.errorText = ""
        Api.call("config.import", { path: path }).then(function(result) {
            root.busy = false
            root.lastAction = "import"
            root.lastResult = result || null
            root.preview = null
            root.pendingImportPath = ""
            Toast.show(I18n.tr("设置已导入"), "info")
            root.imported(result || null)
        }).catch(function(error) {
            root.busy = false
            root.errorText = error && error.message ? error.message : I18n.tr("导入失败")
        })
    }

    // ------------------------------------------------------------------ 文案辅助

    function levelTitle(level) {
        switch (level) {
        case "DEBUG":
            return I18n.tr("调试")
        case "INFO":
            return I18n.tr("信息")
        case "WARN":
            return I18n.tr("警告")
        case "ERROR":
            return I18n.tr("错误")
        default:
            return level
        }
    }

    function formatBytes(bytes) {
        const value = Number(bytes) || 0
        if (value < 1024)
            return value + " B"
        if (value < 1024 * 1024)
            return (value / 1024).toFixed(1) + " KB"
        return (value / 1024 / 1024).toFixed(1) + " MB"
    }

    function formatTimestamp(ms) {
        const value = Number(ms) || 0
        if (value <= 0)
            return ""
        return Qt.formatDateTime(new Date(value), "yyyy-MM-dd HH:mm:ss")
    }

    // ------------------------------------------------------------------ 界面

    background: Rectangle {
        radius: Theme.radiusXxl
        color: Theme.surfaceContainerHigh
        implicitWidth: 560
        // 内容高度自己算：不要绑 root.implicitHeight（Dialog 由背景推出来，会成环）
        implicitHeight: content.implicitHeight
    }

    contentItem: ColumnLayout {
        id: content

        spacing: 0

        // ---- 标题栏 ----
        Item {
            Layout.fillWidth: true
            Layout.preferredHeight: headerRow.implicitHeight + Theme.spacingXl

            RowLayout {
                id: headerRow
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                anchors.leftMargin: Theme.spacingXl
                anchors.rightMargin: Theme.spacingXl
                spacing: Theme.spacingMd

                NeriIcon {
                    name: "settings_backup_restore"
                    iconSize: 22
                    color: Theme.primary
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 2

                    Text {
                        Layout.fillWidth: true
                        text: I18n.tr("备份与恢复设置")
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("titleMedium").size
                        font.weight: Font.DemiBold
                    }

                    Text {
                        Layout.fillWidth: true
                        text: I18n.tr("把当前偏好存成 JSON 文件，或从备份文件恢复")
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("bodySmall").size
                        wrapMode: Text.Wrap
                    }
                }
            }
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 1
            color: Theme.withAlpha(Theme.outlineVariant, 0.6)
        }

        // ---- 两个操作 ----
        ColumnLayout {
            Layout.fillWidth: true
            Layout.margins: Theme.spacingXl
            spacing: Theme.spacingMd

            // 导出
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: exportBlock.implicitHeight + Theme.spacingMd * 2
                radius: Theme.radiusMd
                color: Theme.surfaceContainerLow

                ColumnLayout {
                    id: exportBlock
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.leftMargin: Theme.spacingMd
                    anchors.rightMargin: Theme.spacingMd
                    spacing: Theme.spacingXs

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: Theme.spacingSm

                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 1

                            Text {
                                Layout.fillWidth: true
                                text: I18n.tr("导出当前设置")
                                color: Theme.onSurfaceColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("bodyMedium").size
                                font.weight: Font.DemiBold
                            }

                            Text {
                                Layout.fillWidth: true
                                text: I18n.tr("保存为可读的 JSON 文件，便于备份或迁移到另一台机器")
                                color: Theme.onSurfaceVariantColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("labelSmall").size
                                wrapMode: Text.Wrap
                            }
                        }

                        LibraryButton {
                            text: root.busy ? I18n.tr("处理中…") : I18n.tr("导出…")
                            icon: "file_upload"
                            enabled: !root.busy
                            onClicked: root.requestExport()
                        }
                    }
                }
            }

            // 导入
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: importBlock.implicitHeight + Theme.spacingMd * 2
                radius: Theme.radiusMd
                color: Theme.surfaceContainerLow

                ColumnLayout {
                    id: importBlock
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.leftMargin: Theme.spacingMd
                    anchors.rightMargin: Theme.spacingMd
                    spacing: Theme.spacingXs

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: Theme.spacingSm

                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 1

                            Text {
                                Layout.fillWidth: true
                                text: I18n.tr("从备份文件恢复")
                                color: Theme.onSurfaceColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("bodyMedium").size
                                font.weight: Font.DemiBold
                            }

                            Text {
                                Layout.fillWidth: true
                                text: I18n.tr("导入会覆盖当前全部偏好，导入前会先校验并显示差异")
                                color: Theme.onSurfaceVariantColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("labelSmall").size
                                wrapMode: Text.Wrap
                            }
                        }

                        LibraryButton {
                            text: root.busy ? I18n.tr("处理中…") : I18n.tr("导入…")
                            icon: "file_download"
                            enabled: !root.busy
                            onClicked: root.requestImport()
                        }
                    }
                }
            }

            // ---- 隐私说明：明确「快照里没有凭据」 ----
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: privacyNote.implicitHeight + Theme.spacingSm * 2
                radius: Theme.radiusSm
                color: Theme.withAlpha(Theme.primary, 0.08)

                RowLayout {
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.leftMargin: Theme.spacingSm
                    anchors.rightMargin: Theme.spacingSm
                    spacing: Theme.spacingSm

                    NeriIcon {
                        name: "lock"
                        iconSize: 16
                        color: Theme.primary
                    }

                    Text {
                        id: privacyNote
                        Layout.fillWidth: true
                        text: I18n.tr("快照只包含偏好设置，不含 Cookie、登录状态、同步 Token，也不含歌单与播放记录。")
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("labelSmall").size
                        wrapMode: Text.Wrap
                    }
                }
            }
        }

        // ---- 结果 / 错误 ----
        Rectangle {
            Layout.fillWidth: true
            Layout.leftMargin: Theme.spacingXl
            Layout.rightMargin: Theme.spacingXl
            visible: root.errorText !== "" || root.lastResult !== null
            implicitHeight: resultColumn.implicitHeight + Theme.spacingMd * 2
            radius: Theme.radiusMd
            color: root.errorText !== ""
                   ? Theme.withAlpha(Theme.error, 0.12)
                   : Theme.withAlpha(Theme.primary, 0.08)

            ColumnLayout {
                id: resultColumn
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                anchors.leftMargin: Theme.spacingMd
                anchors.rightMargin: Theme.spacingMd
                spacing: 2

                Text {
                    Layout.fillWidth: true
                    visible: root.errorText !== ""
                    text: root.errorText
                    color: Theme.error
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodySmall").size
                    wrapMode: Text.Wrap
                }

                Text {
                    Layout.fillWidth: true
                    visible: root.lastResult !== null && root.errorText === ""
                    text: {
                        if (!root.lastResult)
                            return ""
                        if (root.lastAction === "export") {
                            return I18n.tr("已导出 ") + (root.lastResult.fieldCount !== undefined
                                   ? String(root.lastResult.fieldCount) : "?")
                                   + I18n.tr(" 项设置到 ") + root.lastResult.path
                        }
                        if (root.lastAction === "import") {
                            return I18n.tr("已应用 ") + root.changedCount + I18n.tr(" 项设置变更")
                        }
                        return ""
                    }
                    color: Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodySmall").size
                    wrapMode: Text.Wrap
                    // 路径可能很长：允许换行，别撑破对话框
                    elide: Text.ElideNone
                }

                Text {
                    Layout.fillWidth: true
                    visible: root.lastResult !== null && root.lastResult.bytes !== undefined
                    text: root.lastResult && root.lastResult.bytes !== undefined
                          ? I18n.tr("文件大小 ") + root.formatBytes(root.lastResult.bytes) : ""
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelSmall").size
                }
            }
        }

        // ---- 预览：会改哪些字段 ----
        ColumnLayout {
            Layout.fillWidth: true
            Layout.leftMargin: Theme.spacingXl
            Layout.rightMargin: Theme.spacingXl
            Layout.topMargin: Theme.spacingMd
            visible: root.preview !== null
            spacing: Theme.spacingXs

            Text {
                Layout.fillWidth: true
                text: I18n.tr("将变更 ") + root.changedCount + I18n.tr(" 项设置")
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("labelSmall").size
            }

            // 字段名是 ASCII，直接列出前 12 个即可，不翻译
            Text {
                Layout.fillWidth: true
                visible: root.previewChangedFields.length > 0
                text: {
                    const list = root.previewChangedFields.slice(0, 12)
                    let text = list.join(", ")
                    if (root.previewChangedFields.length > list.length)
                        text += " …"
                    return text
                }
                color: Theme.onSurfaceColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("labelSmall").size
                wrapMode: Text.Wrap
            }

            Repeater {
                model: root.previewWarnings

                delegate: Text {
                    required property var modelData

                    Layout.fillWidth: true
                    text: "• " + modelData
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelSmall").size
                    wrapMode: Text.Wrap
                }
            }
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 1
            // 内容列自带 spacingXl 外边距，这里再加一点点就够，别叠成一片空白
            Layout.topMargin: Theme.spacingSm
            color: Theme.withAlpha(Theme.outlineVariant, 0.6)
        }

        RowLayout {
            Layout.fillWidth: true
            Layout.margins: Theme.spacingXl
            spacing: Theme.spacingSm

            Item {
                Layout.fillWidth: true
                implicitHeight: 1
            }

            LibraryButton {
                text: I18n.tr("关闭")
                onClicked: root.close()
            }
        }
    }

    // ------------------------------------------------------------------ 文件选择

    FileDialog {
        id: exportDialog

        title: I18n.tr("导出设置快照")
        fileMode: FileDialog.SaveFile
        defaultSuffix: "json"
        nameFilters: [I18n.tr("设置快照 (*.json)"), I18n.tr("所有文件 (*)")]
        // 默认落在用户主目录，文件名带时间戳，避免覆盖上一次导出的文件。
        // StandardPaths 返回的是 file:// URL，currentFile 要的正好也是 url，直接拼即可。
        currentFile: StandardPaths.standardLocations(StandardPaths.HomeLocation)[0]
                     + "/neriplayer-settings-"
                     + Qt.formatDateTime(new Date(), "yyyyMMdd-HHmmss") + ".json"
        onAccepted: root.performExport(root.pathFromUrl(selectedFile))
        onRejected: root.errorText = ""
    }

    FileDialog {
        id: importDialog

        title: I18n.tr("选择设置快照")
        fileMode: FileDialog.OpenFile
        nameFilters: [I18n.tr("设置快照 (*.json)"), I18n.tr("所有文件 (*)")]
        onAccepted: root.previewImport(root.pathFromUrl(selectedFile))
        onRejected: root.errorText = ""
    }

    ConfirmDialog {
        id: confirmImport

        confirmText: I18n.tr("导入")
        busy: root.busy
        onConfirmed: root.performImport()
    }

    /** FileDialog 给的是 file:// URL，后端要的是文件系统路径。 */
    function pathFromUrl(url) {
        let text = String(url)
        if (text.indexOf("file://") === 0)
            text = text.substring(7)
        try {
            return decodeURIComponent(text)
        } catch (error) {
            return text
        }
    }
}
