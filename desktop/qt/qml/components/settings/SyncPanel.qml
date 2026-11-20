import QtQuick
import "../"
import "../library" as Library

// 备份与同步完整面板（自包含）：状态概览 + GitHub 引导 + 立即同步 + 自动同步选项 + 关闭同步。
// 所有配置读写都走 sync.* 协议命令（见 desktop/docs/protocol-sync.md），
// 与 SettingsPage 里的简易同步卡片互不依赖；父容器只需给 width，高度走 implicitHeight。
Item {
    id: root

    width: parent ? parent.width : 0
    implicitHeight: contentColumn.implicitHeight

    // ---- 数据状态 ----
    property var config: null
    property var syncState: null
    property bool busy: false
    property bool unsupported: false
    property string statusMessage: ""

    // ---- 引导向导状态 ----
    property bool wizardOpen: false
    property int wizardPhase: 1
    property string wizardToken: ""
    property string wizardLogin: ""
    property string wizardError: ""
    property bool verifying: false
    property bool listingRepos: false
    property bool creatingRepo: false
    property int repoMode: 0            // 0=新建仓库 1=使用已有仓库
    property string newRepoName: "neriplayer-backup"
    property string selectedRepo: ""    // owner/repo（从仓库列表选）
    property string manualRepo: ""      // owner/repo（手动输入）
    property var repos: []

    property var _eventHandlers: []

    readonly property bool configured: config && config.configured === true
    readonly property string repoFullName: {
        if (!config)
            return ""
        if (config.repoFullName && config.repoFullName !== "")
            return config.repoFullName
        return ((config.owner || "") + "/" + (config.repo || "")).replace(/^\/$/, "")
    }
    readonly property bool running: syncState && syncState.running === true

    // ------------------------------------------------------------------ 工具函数

    function errorText(error, prefix) {
        const message = error && error.message ? error.message : I18n.tr("未知错误")
        return prefix + "：" + message
    }

    function handleError(error, prefix) {
        if (error && error.code === "unknown_method") {
            unsupported = true
            statusMessage = I18n.tr("后端暂不支持同步命令（sync.*）")
            Toast.show(I18n.tr("后端暂不支持同步命令"), "error")
        } else {
            statusMessage = errorText(error, prefix)
            Toast.show(statusMessage, "error")
        }
    }

    function formatTime(millis) {
        if (!millis)
            return ""
        const date = new Date(millis)
        function pad(value) {
            return value < 10 ? "0" + value : "" + value
        }
        return date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate())
                + " " + pad(date.getHours()) + ":" + pad(date.getMinutes())
    }

    function configValue(key, fallback) {
        if (!config)
            return fallback
        const value = config[key]
        return (value === undefined || value === null) ? fallback : value
    }

    function statusText() {
        if (unsupported)
            return I18n.tr("后端暂不支持同步命令（sync.*）")
        if (busy || running)
            return I18n.tr("正在同步…")
        if (statusMessage !== "")
            return statusMessage
        const last = config && config.lastSyncAt ? formatTime(config.lastSyncAt)
                                                 : (syncState && syncState.lastSyncAt ? formatTime(syncState.lastSyncAt) : "")
        let text = last !== "" ? (I18n.tr("上次同步：") + last) : I18n.tr("尚未同步")
        const message = syncState && syncState.message ? syncState.message : (config && config.lastStatus ? config.lastStatus : "")
        if (message)
            text += " · " + message
        return text
    }

    function isErrorStatus() {
        if (unsupported)
            return true
        if (syncState && syncState.success === false && statusMessage !== "")
            return true
        return false
    }

    // ------------------------------------------------------------------ 数据加载

    function load() {
        // 后端尚未连接时先不请求：否则会弹出「后端未连接」的误导性错误；连接后由下方 Connections 重试。
        if (!Api.connected)
            return
        Api.call("sync.config").then(function(result) {
            unsupported = false
            config = result || null
        }).catch(function(error) {
            handleError(error, I18n.tr("读取同步配置失败"))
        })
        Api.call("sync.status").then(function(result) {
            syncState = result || null
        }).catch(function(error) {
            // status 失败不打断面板；config 的错误提示已足够
            console.warn("[sync] status failed:", error && error.message)
        })
    }

    /** 局部更新布尔开关（autoSync / useDataSaver）。 */
    function updateFlag(key, value) {
        if (unsupported) {
            Toast.show(I18n.tr("后端暂不支持同步命令"), "error")
            return
        }
        const payload = {}
        payload[key] = value
        busy = true
        Api.call("sync.configure", payload).then(function(result) {
            busy = false
            config = result || config
            Toast.show(value ? I18n.tr("已开启") : I18n.tr("已关闭"), "info")
        }).catch(function(error) {
            busy = false
            handleError(error, I18n.tr("更新同步选项失败"))
        })
    }

    function saveApiBase() {
        if (unsupported)
            return
        const value = apiBaseField.text.trim()
        busy = true
        Api.call("sync.configure", { "apiBase": value }).then(function(result) {
            busy = false
            config = result || config
            statusMessage = I18n.tr("同步配置已保存")
            Toast.show(I18n.tr("同步配置已保存"), "info")
        }).catch(function(error) {
            busy = false
            handleError(error, I18n.tr("保存同步配置失败"))
        })
    }

    function performSync() {
        if (unsupported) {
            Toast.show(I18n.tr("后端暂不支持同步命令"), "error")
            return
        }
        busy = true
        statusMessage = I18n.tr("正在同步…")
        Api.call("sync.perform").then(function(result) {
            busy = false
            const message = result && result.message ? result.message : I18n.tr("同步完成")
            statusMessage = message
            Toast.show(message, result && result.status === "success" ? "info" : "error")
            load()
        }).catch(function(error) {
            busy = false
            handleError(error, I18n.tr("同步失败"))
        })
    }

    function disableSync(clearRepo) {
        if (unsupported) {
            Toast.show(I18n.tr("后端暂不支持同步命令"), "error")
            return
        }
        busy = true
        Api.call("sync.disable", { "clearRepo": clearRepo === true }).then(function(result) {
            busy = false
            config = result || null
            statusMessage = ""
            wizardOpen = false
            Toast.show(I18n.tr("已断开同步"), "info")
            load()
        }).catch(function(error) {
            busy = false
            handleError(error, I18n.tr("断开同步失败"))
        })
    }

    // ------------------------------------------------------------------ 引导向导

    function openWizard() {
        wizardPhase = 1
        wizardToken = ""
        wizardLogin = ""
        wizardError = ""
        verifying = false
        listingRepos = false
        creatingRepo = false
        repoMode = 0
        newRepoName = "neriplayer-backup"
        selectedRepo = ""
        manualRepo = config && config.repoFullName ? config.repoFullName : ""
        repos = []
        wizardOpen = true
    }

    /** 第一步：校验 Token，成功后进入第二步并拉取仓库列表。 */
    function verifyToken() {
        const token = wizardToken.trim()
        if (token === "") {
            wizardError = I18n.tr("请先输入 GitHub Token")
            return
        }
        wizardError = ""
        verifying = true
        Api.call("sync.verifyToken", { "token": token }).then(function(result) {
            verifying = false
            if (result && result.ok) {
                wizardLogin = result.login || ""
                wizardPhase = 2
                listRepos()
            } else {
                wizardError = (result && result.message) ? result.message : I18n.tr("Token 校验失败")
            }
        }).catch(function(error) {
            verifying = false
            wizardError = errorText(error, I18n.tr("Token 校验失败"))
        })
    }

    function listRepos() {
        listingRepos = true
        Api.call("sync.listRepos", { "token": wizardToken.trim() }).then(function(result) {
            listingRepos = false
            repos = (result && result.repos) ? result.repos : []
        }).catch(function(error) {
            listingRepos = false
            repos = []
            wizardError = errorText(error, I18n.tr("获取仓库列表失败"))
        })
    }

    /** 第二步：根据模式创建或选用仓库，然后保存配置。 */
    function finishWizard() {
        const token = wizardToken.trim()
        if (token === "") {
            wizardError = I18n.tr("请先输入 GitHub Token")
            return
        }
        const fullName = repoMode === 0 ? "" : (selectedRepo || manualRepo).trim()
        if (repoMode === 1 && !isValidRepo(fullName)) {
            wizardError = I18n.tr("仓库格式应为 owner/repo")
            return
        }
        wizardError = ""
        if (repoMode === 0) {
            creatingRepo = true
            const name = newRepoName.trim() || "neriplayer-backup"
            Api.call("sync.createRepo", { "token": token, "name": name }).then(function(result) {
                creatingRepo = false
                if (result && result.ok) {
                    saveWizardConfig(token, result.fullName)
                } else {
                    wizardError = (result && result.message) ? result.message : I18n.tr("创建仓库失败")
                }
            }).catch(function(error) {
                creatingRepo = false
                wizardError = errorText(error, I18n.tr("创建仓库失败"))
            })
        } else {
            saveWizardConfig(token, fullName)
        }
    }

    function isValidRepo(fullName) {
        const parts = String(fullName).split("/")
        return parts.length === 2 && parts[0].trim() !== "" && parts[1].trim() !== ""
    }

    function saveWizardConfig(token, fullName) {
        if (!isValidRepo(fullName)) {
            wizardError = I18n.tr("仓库格式应为 owner/repo")
            return
        }
        const parts = fullName.split("/")
        busy = true
        Api.call("sync.configure", {
            "token": token,
            "owner": parts[0].trim(),
            "repo": parts[1].trim()
        }).then(function(result) {
            busy = false
            config = result || config
            wizardOpen = false
            statusMessage = I18n.tr("同步配置已保存")
            Toast.show(I18n.tr("同步配置已保存"), "info")
            load()
        }).catch(function(error) {
            busy = false
            handleError(error, I18n.tr("保存同步配置失败"))
        })
    }

    // ------------------------------------------------------------------ 事件订阅

    function onSyncState(data) {
        if (data)
            syncState = data
    }

    Component.onCompleted: {
        load()
        Api.on("sync.state", onSyncState)
    }

    Component.onDestruction: {
        Api.off("sync.state", onSyncState)
    }

    Connections {
        target: Backend

        function onConnectedChanged() {
            if (Backend.connected)
                root.load()
        }
    }

    // ------------------------------------------------------------------ 主体 UI

    Column {
        id: contentColumn

        width: parent.width
        spacing: Theme.spacingMd

        // ===== 状态概览卡 =====
        Rectangle {
            width: parent.width
            radius: Theme.radiusXl
            color: Theme.surfaceContainerLow
            implicitHeight: statusInner.height + Theme.spacingLg * 2

            Column {
                id: statusInner

                x: Theme.spacingLg
                y: Theme.spacingLg
                width: parent.width - Theme.spacingLg * 2
                spacing: Theme.spacingSm

                // 标题 + 状态徽标
                Row {
                    width: parent.width
                    spacing: Theme.spacingSm

                    NeriIcon {
                        name: "cloud_sync"
                        iconSize: 22
                        color: Theme.primary
                        anchors.verticalCenter: parent.verticalCenter
                    }

                    Text {
                        text: I18n.tr("备份与同步")
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("titleMedium").size
                        font.weight: Font.DemiBold
                        anchors.verticalCenter: parent.verticalCenter
                    }

                    Item {
                        width: Math.max(0, parent.width - 22 - Theme.spacingSm * 2
                                        - statusTitleText.implicitWidth - pillRow.implicitWidth)
                        height: 1
                    }

                    Row {
                        id: pillRow

                        anchors.verticalCenter: parent.verticalCenter
                        spacing: 4

                        Rectangle {
                            width: pillText.implicitWidth + 16
                            height: 26
                            radius: 13
                            color: root.configured ? Theme.primaryContainer : Theme.surfaceContainerHighest

                            Text {
                                id: pillText
                                anchors.centerIn: parent
                                text: root.configured ? I18n.tr("已启用") : I18n.tr("未配置")
                                color: root.configured ? Theme.onPrimaryContainerColor : Theme.onSurfaceVariantColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("labelSmall").size
                                font.weight: Font.Medium
                            }
                        }
                    }
                }

                // 隐藏的宽度测量文本，保证标题与徽标不重叠
                Text {
                    id: statusTitleText
                    visible: false
                    text: I18n.tr("备份与同步")
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("titleMedium").size
                    font.weight: Font.DemiBold
                }

                // 仓库信息
                Row {
                    width: parent.width
                    spacing: Theme.spacingSm
                    visible: root.configured

                    Text {
                        text: I18n.tr("同步仓库")
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("bodyMedium").size
                        anchors.verticalCenter: parent.verticalCenter
                    }
                    Text {
                        text: root.repoFullName
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("bodyMedium").size
                        font.weight: Font.Medium
                        elide: Text.ElideRight
                        anchors.verticalCenter: parent.verticalCenter
                    }
                }

                // 状态说明 / 错误
                Text {
                    width: parent.width
                    text: root.statusText()
                    color: root.isErrorStatus() ? Theme.error : Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodySmall").size
                    wrapMode: Text.Wrap
                }

                // 操作按钮
                Flow {
                    width: parent.width
                    spacing: Theme.spacingSm

                    SettingsButton {
                        text: root.busy || root.running ? I18n.tr("同步中…") : I18n.tr("立即同步")
                        icon: "sync"
                        filled: true
                        enabled: root.configured && !root.busy && !root.running && !root.unsupported
                        onClicked: root.performSync()
                    }

                    SettingsButton {
                        text: I18n.tr("重新配置")
                        icon: "settings"
                        enabled: !root.busy && !root.unsupported
                        onClicked: root.openWizard()
                    }

                    SettingsButton {
                        text: I18n.tr("断开同步")
                        icon: "link_off"
                        enabled: root.configured && !root.busy
                        onClicked: root.confirmDisable()
                    }
                }
            }
        }

        // ===== 引导向导卡（未配置或主动重新配置时显示） =====
        Rectangle {
            width: parent.width
            radius: Theme.radiusXl
            color: Theme.surfaceContainerLow
            visible: root.wizardOpen || !root.configured
            implicitHeight: wizardInner.visible ? wizardInner.height + Theme.spacingLg * 2 : 0

            Column {
                id: wizardInner

                x: Theme.spacingLg
                y: Theme.spacingLg
                width: parent.width - Theme.spacingLg * 2
                spacing: Theme.spacingSm

                // 标题
                Row {
                    width: parent.width
                    spacing: Theme.spacingSm

                    NeriIcon {
                        name: "key"
                        iconSize: 20
                        color: Theme.primary
                        anchors.verticalCenter: parent.verticalCenter
                    }
                    Text {
                        text: I18n.tr("GitHub 同步设置")
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("titleMedium").size
                        font.weight: Font.DemiBold
                        anchors.verticalCenter: parent.verticalCenter
                    }
                }

                // 步骤 1：Token
                Item {
                    width: parent.width
                    height: wizardPhase === 1 ? step1Column.implicitHeight : 0
                    visible: wizardPhase === 1
                    clip: true

                    Column {
                        id: step1Column
                        width: parent.width
                        spacing: Theme.spacingSm

                        Item { width: 1; height: Theme.spacingXs }

                        SettingsLabel {
                            width: parent.width
                            text: I18n.tr("第 1 步 · GitHub Personal Access Token")
                        }

                        SettingsTextField {
                            id: wizardTokenField
                            width: parent.width
                            echoMode: TextInput.Password
                            placeholderText: "ghp_xxxxxxxxxxxx"
                            text: root.wizardToken
                            onTextChanged: root.wizardToken = text
                            Keys.onReturnPressed: root.verifyToken()
                        }

                        SettingsNote {
                            width: parent.width
                            text: I18n.tr("在 GitHub → Settings → Developer settings → Personal access tokens 生成，需勾选 repo 权限。Token 只保存在本机，不会回传明文。")
                        }

                        Row {
                            spacing: Theme.spacingSm
                            layoutDirection: Qt.RightToLeft

                            SettingsButton {
                                text: root.verifying ? I18n.tr("验证中…") : I18n.tr("验证 Token")
                                icon: root.verifying ? "progress_activity" : "check_circle"
                                filled: true
                                enabled: !root.verifying && root.wizardToken.trim() !== ""
                                onClicked: root.verifyToken()
                            }

                            SettingsButton {
                                text: I18n.tr("取消")
                                visible: root.configured
                                enabled: !root.verifying
                                onClicked: root.wizardOpen = false
                            }
                        }
                    }
                }

                // 步骤 2：仓库
                Item {
                    width: parent.width
                    height: wizardPhase === 2 ? step2Column.implicitHeight : 0
                    visible: wizardPhase === 2
                    clip: true

                    Column {
                        id: step2Column
                        width: parent.width
                        spacing: Theme.spacingSm

                        Item { width: 1; height: Theme.spacingXs }

                        SettingsLabel {
                            width: parent.width
                            text: I18n.tr("第 2 步 · 选择同步仓库")
                        }

                        // 已验证的账号
                        Row {
                            spacing: Theme.spacingSm
                            NeriIcon {
                                name: "check_circle"
                                iconSize: 18
                                color: Theme.primary
                                anchors.verticalCenter: parent.verticalCenter
                            }
                            Text {
                                text: I18n.tr("已验证：") + root.wizardLogin
                                color: Theme.onSurfaceColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("bodyMedium").size
                                anchors.verticalCenter: parent.verticalCenter
                            }
                        }

                        // 模式选择：新建 / 已有（用 Item + 内部行避免 Row 忽略子项锚点）
                        Row {
                            spacing: Theme.spacingLg

                            Item {
                                width: radioNew.implicitWidth
                                height: radioNew.implicitHeight
                                enabled: !root.creatingRepo

                                Row {
                                    id: radioNew
                                    spacing: 6
                                    Rectangle {
                                        width: 18
                                        height: 18
                                        radius: 9
                                        border.width: root.repoMode === 0 ? 5 : 2
                                        border.color: root.repoMode === 0 ? Theme.primary : Theme.outline
                                        color: "transparent"
                                        anchors.verticalCenter: parent.verticalCenter
                                    }
                                    Text {
                                        text: I18n.tr("创建新仓库")
                                        color: Theme.onSurfaceColor
                                        font.family: Theme.fontFamily
                                        font.pixelSize: Theme.textStyle("bodyMedium").size
                                        anchors.verticalCenter: parent.verticalCenter
                                    }
                                }

                                MouseArea {
                                    anchors.fill: parent
                                    onClicked: root.repoMode = 0
                                }
                            }

                            Item {
                                width: radioExisting.implicitWidth
                                height: radioExisting.implicitHeight
                                enabled: !root.creatingRepo

                                Row {
                                    id: radioExisting
                                    spacing: 6
                                    Rectangle {
                                        width: 18
                                        height: 18
                                        radius: 9
                                        border.width: root.repoMode === 1 ? 5 : 2
                                        border.color: root.repoMode === 1 ? Theme.primary : Theme.outline
                                        color: "transparent"
                                        anchors.verticalCenter: parent.verticalCenter
                                    }
                                    Text {
                                        text: I18n.tr("使用已有仓库")
                                        color: Theme.onSurfaceColor
                                        font.family: Theme.fontFamily
                                        font.pixelSize: Theme.textStyle("bodyMedium").size
                                        anchors.verticalCenter: parent.verticalCenter
                                    }
                                }

                                MouseArea {
                                    anchors.fill: parent
                                    onClicked: {
                                        root.repoMode = 1
                                        root.listRepos()
                                    }
                                }
                            }
                        }

                        // 新建仓库名
                        Column {
                            width: parent.width
                            spacing: Theme.spacingXs
                            visible: root.repoMode === 0

                            SettingsLabel { width: parent.width; text: I18n.tr("新仓库名称") }
                            SettingsTextField {
                                id: newRepoField
                                width: parent.width
                                text: root.newRepoName
                                placeholderText: "neriplayer-backup"
                                onTextChanged: root.newRepoName = text
                            }
                            SettingsNote {
                                width: parent.width
                                text: I18n.tr("将创建一个私有仓库用于跨设备备份；两端需使用相同的仓库与省流设置。")
                            }
                        }

                        // 已有仓库：列表 + 手动输入
                        Column {
                            width: parent.width
                            spacing: Theme.spacingXs
                            visible: root.repoMode === 1

                            SettingsLabel { width: parent.width; text: I18n.tr("可写仓库（点击选择）") }

                            Rectangle {
                                width: parent.width
                                height: root.listingRepos ? 48
                                        : Math.min(180, Math.max(44, repoListColumn.implicitHeight + Theme.spacingSm * 2))
                                radius: Theme.radiusSm
                                color: Theme.surfaceContainerHighest

                                Text {
                                    anchors.centerIn: parent
                                    visible: root.listingRepos
                                    text: I18n.tr("正在获取仓库列表…")
                                    color: Theme.onSurfaceVariantColor
                                    font.family: Theme.fontFamily
                                    font.pixelSize: Theme.textStyle("bodySmall").size
                                }

                                Flickable {
                                    anchors.fill: parent
                                    anchors.margins: Theme.spacingSm
                                    visible: !root.listingRepos
                                    contentWidth: width
                                    contentHeight: repoListColumn.implicitHeight
                                    clip: true
                                    boundsBehavior: Flickable.StopAtBounds

                                    Column {
                                        id: repoListColumn
                                        width: parent.width
                                        spacing: 2

                                        Repeater {
                                            model: root.repos

                                            delegate: Rectangle {
                                                width: parent.width
                                                height: 34
                                                radius: Theme.radiusSm
                                                color: root.selectedRepo === modelData
                                                       ? Theme.withAlpha(Theme.primary, 0.16) : "transparent"

                                                Row {
                                                    anchors.left: parent.left
                                                    anchors.leftMargin: Theme.spacingSm
                                                    anchors.verticalCenter: parent.verticalCenter
                                                    spacing: Theme.spacingSm

                                                    NeriIcon {
                                                        name: "folder"
                                                        iconSize: 16
                                                        color: Theme.primary
                                                        anchors.verticalCenter: parent.verticalCenter
                                                    }
                                                    Text {
                                                        text: modelData
                                                        color: Theme.onSurfaceColor
                                                        font.family: Theme.fontFamily
                                                        font.pixelSize: Theme.textStyle("bodySmall").size
                                                        elide: Text.ElideRight
                                                        anchors.verticalCenter: parent.verticalCenter
                                                    }
                                                }

                                                MouseArea {
                                                    anchors.fill: parent
                                                    onClicked: {
                                                        root.selectedRepo = modelData
                                                        root.manualRepo = modelData
                                                    }
                                                }
                                            }
                                        }

                                        Text {
                                            width: parent.width
                                            visible: !root.listingRepos && root.repos.length === 0
                                            text: I18n.tr("没有可写仓库，请手动填写或在下方新建")
                                            color: Theme.onSurfaceVariantColor
                                            font.family: Theme.fontFamily
                                            font.pixelSize: Theme.textStyle("bodySmall").size
                                        }
                                    }
                                }
                            }

                            SettingsLabel { width: parent.width; text: I18n.tr("仓库全名（owner/repo）") }
                            SettingsTextField {
                                width: parent.width
                                text: root.manualRepo
                                placeholderText: "owner/neriplayer-backup"
                                onTextChanged: {
                                    root.manualRepo = text
                                    if (text !== root.selectedRepo)
                                        root.selectedRepo = ""
                                }
                            }
                        }

                        // 错误提示
                        Text {
                            width: parent.width
                            visible: root.wizardError !== ""
                            text: root.wizardError
                            color: Theme.error
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("bodySmall").size
                            wrapMode: Text.Wrap
                        }

                        // 操作按钮
                        Row {
                            spacing: Theme.spacingSm
                            layoutDirection: Qt.RightToLeft

                            SettingsButton {
                                text: root.creatingRepo ? I18n.tr("创建中…") : I18n.tr("完成配置")
                                icon: "check"
                                filled: true
                                enabled: !root.creatingRepo && !root.busy
                                onClicked: root.finishWizard()
                            }

                            SettingsButton {
                                text: I18n.tr("上一步")
                                enabled: !root.creatingRepo && !root.busy
                                onClicked: root.wizardPhase = 1
                            }

                            SettingsButton {
                                text: I18n.tr("取消")
                                visible: root.configured
                                enabled: !root.creatingRepo && !root.busy
                                onClicked: root.wizardOpen = false
                            }
                        }
                    }
                }
            }
        }

        // ===== 同步选项（已配置时显示） =====
        Rectangle {
            width: parent.width
            radius: Theme.radiusXl
            color: Theme.surfaceContainerLow
            visible: root.configured && !root.unsupported
            implicitHeight: optionsInner.height + Theme.spacingLg * 2

            Column {
                id: optionsInner
                x: Theme.spacingLg
                y: Theme.spacingLg
                width: parent.width - Theme.spacingLg * 2
                spacing: Theme.spacingSm

                Text {
                    text: I18n.tr("同步选项")
                    color: Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("titleMedium").size
                    font.weight: Font.DemiBold
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("自动同步")
                    description: I18n.tr("歌单、收藏、最近播放或统计变化后自动同步（后端每 15 秒检查一次）")
                    checked: root.configValue("autoSync", true) === true
                    enabled: !root.busy
                    onToggled: function(value) { root.updateFlag("autoSync", value) }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("省流模式")
                    description: I18n.tr("用二进制省流通道传输，减少流量；两端设置必须一致")
                    checked: root.configValue("useDataSaver", false) === true
                    enabled: !root.busy
                    onToggled: function(value) { root.updateFlag("useDataSaver", value) }
                }

                Item { width: 1; height: Theme.spacingXs }

                SettingsLabel {
                    width: parent.width
                    text: I18n.tr("GitHub API 地址（留空使用默认）")
                }

                SettingsTextField {
                    id: apiBaseField
                    width: parent.width
                    placeholderText: "https://api.github.com"
                    text: root.configValue("apiBase", "")
                    enabled: !root.busy
                }

                Row {
                    spacing: Theme.spacingSm
                    layoutDirection: Qt.RightToLeft

                    SettingsButton {
                        text: I18n.tr("保存 API 地址")
                        enabled: !root.busy
                        onClicked: root.saveApiBase()
                    }
                }

                SettingsNote {
                    width: parent.width
                    text: I18n.tr("自动同步频率由后端固定为每 15 秒检查本地变化，暂不支持自定义间隔。")
                }
            }
        }

        // ===== 关闭同步确认对话框 =====
        Library.ConfirmDialog {
            id: disableConfirm

            titleText: I18n.tr("断开同步")
            message: I18n.tr("断开后本机将不再上传或下载备份数据；仓库与配置会保留，重新填入 Token 即可恢复。")
            confirmText: I18n.tr("断开同步")
            onConfirmed: root.disableSync(false)
        }
    }

    // 由按钮显式打开确认框（避免 ConfirmDialog 的 openFor 覆盖固定文案）
    function confirmDisable() {
        disableConfirm.open()
    }
}
