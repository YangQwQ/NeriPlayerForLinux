import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"
import "../library"

// 存储占用详情对话框（自包含）：只读统计 + 白名单清理。
//
// 用法：
//   storageDialog.openDialog()                  // 打开并拉取 storage.usage
//   storageDialog.openDialog("cover_cache")     // 打开并高亮封面缓存行
// 协议见 desktop/docs/protocol-storage.md。
//
// 安全边界：本对话框只有「封面缓存」一项可清理（在线封面，可重新下载）。
// 本地曲库抽取封面（artworkDir）、下载内容、配置与数据索引一律置灰只读——
// 它们的路径在后端白名单里根本不接受，UI 不给入口是为了不让用户误解。
Dialog {
    id: root

    /** 后端返回的 StorageUsageSummary，结构见 protocol-storage.md。 */
    property var usage: null
    property bool loading: false
    property bool clearing: false
    property string loadError: ""

    /** 是否正在等待用户二次确认（清理不可撤销，必须先确认）。 */
    readonly property bool confirmOpen: confirm.opened

    /** 入口指定的关注项 id（用于高亮对应行，可为空）。 */
    property string focusItemId: ""

    /** 需要二次确认的清理目标（目前只有 coverCache）。 */
    property var pendingTargets: []

    readonly property var sections: usage && usage.sections ? usage.sections : []
    readonly property real cleanableBytes: usage && usage.cleanableSizeBytes !== undefined
                                           ? Number(usage.cleanableSizeBytes) : 0
    readonly property int cleanableFiles: usage && usage.cleanableFileCount !== undefined
                                          ? Number(usage.cleanableFileCount) : 0

    signal cleared(var result)

    parent: Overlay.overlay
    anchors.centerIn: parent
    modal: true
    width: 520
    padding: 0
    closePolicy: Popup.CloseOnEscape

    // ------------------------------------------------------------------ 对外接口

    /** 打开对话框并拉取一次统计（`focusItemId` 用于高亮入口关心的那一行）。 */
    function openDialog(focusItemId) {
        root.pendingTargets = []
        if (confirm.opened)
            confirm.close()
        root.loadError = ""
        root.usage = null
        root.focusItemId = focusItemId !== undefined && focusItemId !== null ? String(focusItemId) : ""
        root.open()
        root.reload()
    }

    function reload() {
        if (typeof Backend !== "undefined" && Backend && Backend.connected === false) {
            // 后端还没连上（冷启动/重连）：先别发命令，等 connected 变化后再拉
            root.loading = true
            root.loadError = ""
            return
        }
        root.loading = true
        root.loadError = ""
        Api.call("storage.usage").then(function(result) {
            root.loading = false
            root.usage = result || null
        }).catch(function(error) {
            root.loading = false
            root.loadError = error && error.message ? error.message : I18n.tr("统计失败")
        })
    }

    // 对话框可能在 Backend 连上之前就被打开，连上后补拉一次统计
    property Connections backendConnection: Connections {
        target: Backend

        function onConnectedChanged() {
            if (!Backend.connected)
                return
            if (root.visible && !root.usage && !root.clearing)
                root.reload()
        }
    }

    /** 请求清理（先弹确认框，确认后才真正下发命令）。 */
    function requestClear(targets) {
        if (root.clearing)
            return
        if (root.usage && root.cleanableBytes <= 0) {
            // 缓存已经是空的：不要弹「删除 0 B」的确认框
            Toast.show(I18n.tr("封面缓存已经是空的"), "info")
            return
        }
        root.pendingTargets = (targets && targets.length > 0) ? targets : ["coverCache"]
        confirm.open()
    }

    function performClear() {
        if (root.clearing || root.pendingTargets.length === 0)
            return
        root.clearing = true
        // 确认框的 confirmed 之后还有一次自动 close，这里提前收起避免它悬在界面上
        if (confirm.opened)
            confirm.close()
        Api.call("storage.clearCache", { targets: root.pendingTargets }).then(function(result) {
            root.clearing = false
            const released = result && result.releasedBytes !== undefined ? Number(result.releasedBytes) : 0
            const failed = result && result.failed !== undefined ? Number(result.failed) : 0
            if (failed > 0)
                Toast.show(I18n.tr("已释放 ") + root.formatSize(released)
                           + I18n.tr("，另有 ") + failed + I18n.tr(" 项未能删除"), "error")
            else
                Toast.show(I18n.tr("已释放 ") + root.formatSize(released), "info")
            root.cleared(result || null)
            root.reload()
        }).catch(function(error) {
            root.clearing = false
            Toast.show(I18n.tr("清理失败：") + (error && error.message ? error.message : I18n.tr("未知错误")), "error")
        })
    }

    // ------------------------------------------------------------------ 格式化与文案

    /** 后端只给字节数，格式化放在前端，避免协议里出现会过期的字符串。 */
    function formatSize(bytes) {
        const value = Number(bytes) || 0
        if (value < 1024)
            return value + " B"
        const units = ["KB", "MB", "GB", "TB"]
        let size = value / 1024
        let unit = 0
        while (size >= 1024 && unit < units.length - 1) {
            size /= 1024
            unit++
        }
        return (size >= 10 ? size.toFixed(0) : size.toFixed(1)) + " " + units[unit]
    }

    function formatCount(count) {
        const value = Number(count) || 0
        return value.toLocaleString(Qt.locale(), "f", 0)
    }

    function sectionTitle(id) {
        switch (id) {
        case "cleanable_cache":
            return I18n.tr("可清理缓存")
        case "cache_other":
            return I18n.tr("其他缓存")
        case "downloads":
            return I18n.tr("下载内容")
        case "app_data":
            return I18n.tr("应用数据")
        default:
            return id
        }
    }

    function sectionNote(id) {
        switch (id) {
        case "cleanable_cache":
            return I18n.tr("可以重新生成的缓存，清理后不影响已下载的歌曲")
        case "cache_other":
            return I18n.tr("口径未确认的缓存，暂不提供清理")
        case "downloads":
            return I18n.tr("用户数据，只统计不清理；删除请在下载管理中操作")
        case "app_data":
            return I18n.tr("曲库封面、数据索引与配置，删除会丢失数据")
        default:
            return ""
        }
    }

    function itemTitle(id) {
        switch (id) {
        case "cover_cache":
            return I18n.tr("在线封面缓存")
        case "other_cache":
            return I18n.tr("其他缓存文件")
        case "downloaded_music":
            return I18n.tr("已下载音乐")
        case "downloaded_lyrics":
            return I18n.tr("已下载歌词")
        case "local_covers":
            return I18n.tr("本地曲库封面")
        case "library_data":
            return I18n.tr("媒体库与播放记录")
        case "logs":
            return I18n.tr("运行日志")
        case "config":
            return I18n.tr("设置与登录信息")
        default:
            return id
        }
    }

    function itemNote(id) {
        switch (id) {
        case "cover_cache":
            return I18n.tr("在线歌曲封面的缩略图，可随时重新下载")
        case "other_cache":
            return I18n.tr("缓存目录中不属于以上分类的剩余内容")
        case "downloaded_music":
            return I18n.tr("下载索引记录的体积；与实际磁盘占用可能略有差异")
        case "downloaded_lyrics":
            return I18n.tr("下载目录里的 .lrc 歌词文件")
        case "local_covers":
            return I18n.tr("从本地歌曲标签提取的封面，删除后需重新扫描媒体库")
        case "library_data":
            return I18n.tr("曲库、歌单、播放历史与统计的索引文件")
        case "logs":
            return I18n.tr("程序运行日志")
        case "config":
            return I18n.tr("应用设置与登录状态")
        default:
            return ""
        }
    }

    // ------------------------------------------------------------------ 界面

    // 背景高度跟随内容（不要绑 root.implicitHeight：那是 Dialog 自己由背景算出来的，会成环）
    background: Rectangle {
        radius: Theme.radiusXxl
        color: Theme.surfaceContainerHigh
        implicitWidth: 520
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
                    name: "storage"
                    iconSize: 22
                    color: Theme.primary
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 2

                    Text {
                        Layout.fillWidth: true
                        text: I18n.tr("存储占用详情")
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("titleMedium").size
                        font.weight: Font.DemiBold
                    }

                    Text {
                        Layout.fillWidth: true
                        text: root.loading ? I18n.tr("正在统计存储占用…")
                                           : I18n.tr("统计各目录的实际磁盘占用")
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("bodySmall").size
                    }
                }

                SettingsButton {
                    text: I18n.tr("刷新")
                    icon: "refresh"
                    enabled: !root.loading && !root.clearing
                    onClicked: root.reload()
                }
            }
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 1
            color: Theme.withAlpha(Theme.outlineVariant, 0.6)
        }

        // ---- 统计清单 ----
        // 高度上限 400：条目多时内部滚动（配 NeriScrollBar），避免对话框超出窗口高度
        Flickable {
            id: listFlick

            Layout.fillWidth: true
            Layout.preferredHeight: Math.min(400, listColumn.implicitHeight)
            clip: true
            contentWidth: width
            contentHeight: listColumn.implicitHeight
            boundsBehavior: Flickable.StopAtBounds
            visible: !root.loading && root.loadError === ""

            NeriScrollBar {
                anchors.right: parent.right
                anchors.rightMargin: 4
                anchors.top: parent.top
                anchors.bottom: parent.bottom
                width: 8
                flickable: listFlick
            }

            Column {
                id: listColumn

                width: listFlick.width
                spacing: Theme.spacingSm

                Repeater {
                    model: root.sections

                    delegate: Column {
                        id: sectionBlock

                        required property var modelData

                        width: listColumn.width
                        spacing: Theme.spacingXs

                        Item {
                            width: parent.width
                            height: sectionTitleText.implicitHeight + Theme.spacingSm

                            Text {
                                id: sectionTitleText
                                anchors.left: parent.left
                                anchors.leftMargin: Theme.spacingXl
                                anchors.bottom: parent.bottom
                                text: root.sectionTitle(sectionBlock.modelData.id)
                                color: Theme.onSurfaceColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("labelLarge").size
                                font.weight: Font.DemiBold
                            }
                        }

                        Text {
                            width: parent.width - Theme.spacingXl * 2
                            x: Theme.spacingXl
                            text: root.sectionNote(sectionBlock.modelData.id)
                            visible: text !== ""
                            color: Theme.onSurfaceVariantColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("labelSmall").size
                            wrapMode: Text.Wrap
                        }

                        Repeater {
                            model: sectionBlock.modelData.items

                            delegate: Rectangle {
                                id: itemRow

                                required property var modelData

                                width: sectionBlock.width - Theme.spacingLg * 2
                                x: Theme.spacingLg
                                height: rowContent.implicitHeight + Theme.spacingMd * 2
                                radius: Theme.radiusMd
                                // 入口带 focusItemId 时给对应行描边，帮助用户定位到「封面缓存」
                                readonly property bool focusedItem: root.focusItemId !== ""
                                                                    && root.focusItemId === itemRow.modelData.id
                                border.width: focusedItem ? 1 : 0
                                border.color: Theme.primary
                                color: {
                                    if (!itemRow.modelData.cleanable)
                                        return Theme.withAlpha(Theme.surfaceContainerHighest, 0.45)
                                    if (rowMouse.containsMouse)
                                        return Theme.withAlpha(Theme.primary, 0.08)
                                    return Theme.surfaceContainerLow
                                }

                                HoverHandler {
                                    id: rowMouse
                                    enabled: itemRow.modelData.cleanable
                                    cursorShape: itemRow.modelData.cleanable ? Qt.PointingHandCursor : Qt.ArrowCursor
                                }

                                MouseArea {
                                    anchors.fill: parent
                                    enabled: itemRow.modelData.cleanable
                                    onClicked: root.requestClear([itemRow.modelData.clearTarget])
                                }

                                RowLayout {
                                    id: rowContent

                                    anchors.left: parent.left
                                    anchors.right: parent.right
                                    anchors.verticalCenter: parent.verticalCenter
                                    anchors.leftMargin: Theme.spacingMd
                                    anchors.rightMargin: Theme.spacingMd
                                    spacing: Theme.spacingSm

                                    NeriIcon {
                                        name: itemRow.modelData.cleanable ? "delete_sweep" : "lock"
                                        iconSize: 18
                                        color: itemRow.modelData.cleanable ? Theme.primary
                                                                           : Theme.onSurfaceVariantColor
                                    }

                                    ColumnLayout {
                                        Layout.fillWidth: true
                                        spacing: 1

                                        Text {
                                            Layout.fillWidth: true
                                            text: root.itemTitle(itemRow.modelData.id)
                                            color: Theme.onSurfaceColor
                                            font.family: Theme.fontFamily
                                            font.pixelSize: Theme.textStyle("bodyMedium").size
                                            elide: Text.ElideRight
                                        }

                                        Text {
                                            Layout.fillWidth: true
                                            text: root.itemNote(itemRow.modelData.id)
                                            visible: text !== ""
                                            color: Theme.onSurfaceVariantColor
                                            font.family: Theme.fontFamily
                                            font.pixelSize: Theme.textStyle("labelSmall").size
                                            wrapMode: Text.Wrap
                                        }
                                    }

                                    ColumnLayout {
                                        spacing: 1

                                        Text {
                                            Layout.alignment: Qt.AlignRight
                                            text: root.formatSize(itemRow.modelData.sizeBytes)
                                            color: itemRow.modelData.cleanable ? Theme.onSurfaceColor
                                                                               : Theme.onSurfaceVariantColor
                                            font.family: Theme.fontFamily
                                            font.pixelSize: Theme.textStyle("bodyMedium").size
                                            font.weight: Font.DemiBold
                                        }

                                        Text {
                                            Layout.alignment: Qt.AlignRight
                                            text: root.formatCount(itemRow.modelData.fileCount) + I18n.tr(" 个文件")
                                            color: Theme.onSurfaceVariantColor
                                            font.family: Theme.fontFamily
                                            font.pixelSize: Theme.textStyle("labelSmall").size
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ---- 排除项（内存盘，不占磁盘） ----
                Text {
                    width: parent.width - Theme.spacingXl * 2
                    x: Theme.spacingXl
                    visible: root.usage && root.usage.excluded && root.usage.excluded.length > 0
                    text: {
                        if (!root.usage || !root.usage.excluded)
                            return ""
                        const parts = []
                        for (let i = 0; i < root.usage.excluded.length; i++) {
                            const entry = root.usage.excluded[i]
                            if (entry.reason === "memory")
                                parts.push(I18n.tr("内存盘缓存 ") + root.formatSize(entry.sizeBytes) + I18n.tr("（不计入磁盘占用）"))
                        }
                        return parts.join("；")
                    }
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelSmall").size
                    wrapMode: Text.Wrap
                }
            }
        }

        // ---- 加载 / 错误占位 ----
        Item {
            Layout.fillWidth: true
            Layout.preferredHeight: 160
            visible: root.loading || root.loadError !== ""

            ColumnLayout {
                anchors.centerIn: parent
                spacing: Theme.spacingSm

                NeriIcon {
                    Layout.alignment: Qt.AlignHCenter
                    name: root.loadError !== "" ? "error" : "database"
                    iconSize: 30
                    color: root.loadError !== "" ? Theme.error : Theme.onSurfaceVariantColor
                }

                Text {
                    Layout.fillWidth: true
                    horizontalAlignment: Text.AlignHCenter
                    text: root.loadError !== "" ? root.loadError : I18n.tr("正在统计存储占用…")
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

        // ---- 总计 + 操作 ----
        ColumnLayout {
            Layout.fillWidth: true
            Layout.margins: Theme.spacingXl
            spacing: Theme.spacingSm

            RowLayout {
                Layout.fillWidth: true
                spacing: Theme.spacingSm

                Text {
                    Layout.fillWidth: true
                    text: I18n.tr("总计")
                    color: Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("titleSmall").size
                    font.weight: Font.DemiBold
                }

                Text {
                    text: root.usage ? root.formatSize(root.usage.totalSizeBytes) : "—"
                    color: Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("titleSmall").size
                    font.weight: Font.DemiBold
                }
            }

            // 危险色提示：清理只会删缓存，明确告知不会动用户数据
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: warningText.implicitHeight + Theme.spacingMd * 2
                radius: Theme.radiusMd
                color: Theme.withAlpha(Theme.error, 0.12)

                Text {
                    id: warningText
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    anchors.leftMargin: Theme.spacingMd
                    anchors.rightMargin: Theme.spacingMd
                    text: I18n.tr("只会删除可重新生成的封面缓存，不会删除已下载的音乐、本地曲库封面、歌词或应用配置。")
                    color: Theme.error
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelSmall").size
                    wrapMode: Text.Wrap
                }
            }

            RowLayout {
                Layout.fillWidth: true
                spacing: Theme.spacingSm

                Text {
                    Layout.fillWidth: true
                    text: root.usage ? I18n.tr("可清理 ") + root.formatSize(root.cleanableBytes)
                                     : ""
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelMedium").size
                }

                LibraryButton {
                    text: I18n.tr("关闭")
                    onClicked: root.close()
                }

                LibraryButton {
                    text: root.clearing ? I18n.tr("清理中…") : I18n.tr("清理缓存")
                    icon: "delete_sweep"
                    filled: true
                    enabled: !root.clearing && !root.loading && root.cleanableBytes > 0
                    onClicked: root.requestClear(["coverCache"])
                }
            }
        }
    }

    // ---- 二次确认（复用共享 ConfirmDialog 的交互模式） ----
    ConfirmDialog {
        id: confirm

        titleText: I18n.tr("清理封面缓存")
        message: I18n.tr("将删除在线封面缓存 ")
                 + root.formatSize(root.cleanableBytes)
                 + I18n.tr("（共 ") + root.formatCount(root.cleanableFiles) + I18n.tr(" 个文件）。")
                 + "\n\n"
                 + I18n.tr("此操作不可撤销，但不会删除已下载的音乐、本地曲库封面、歌词或应用配置。")
        confirmText: I18n.tr("清理")
        busy: root.clearing
        onConfirmed: root.performClear()
    }
}
