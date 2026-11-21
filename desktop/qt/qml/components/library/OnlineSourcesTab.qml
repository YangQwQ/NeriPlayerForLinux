import QtQuick
import QtQuick.Controls as Controls
import QtQuick.Layouts
// 根目录 components 与本目录存在同名组件（CollectionCard/EmptyState/ErrorCard/LoadingState），
// 用限定导入避免覆盖同目录组件。
import ".." as Root
import "../youtube"

// 媒体库「在线音源」Tab：网易云「我的歌单」/ 哔哩哔哩「收藏夹」/ YouTube「我的歌单」。
// YouTube 没有扫码登录（桌面无 WebView），未登录时直接内嵌「粘贴 Cookie」登录卡。
//
// 数据来源（协议见 desktop/docs/protocol-online.md 与 protocol-login.md）：
//   login.accounts                → 判断当前音源是否已登录
//   online.myPlaylists {source}   → 网易云账号歌单（needLogin=true 表示需要重新登录）
//   online.biliFavorites          → 哔哩哔哩账号收藏夹（同样带 needLogin）
// 登录状态变化通过 accounts.changed 事件刷新（事件数据在 Qt 桥里可能拿不到数组内容，拿不到时重新查询）。
Item {
    id: root

    // ---- 音源 ----
    readonly property var sources: {
        // 读取一次 Settings.values，让这个绑定依赖设置对象；只调 Settings.get 不会建立依赖。
        const settings = Settings.values
        const list = []
        if (Settings.get("neteaseEnabled", true) !== false)
            list.push({ "value": "NETEASE", "label": I18n.tr("网易云") })
        if (Settings.get("bilibiliEnabled", true) !== false)
            list.push({ "value": "BILIBILI", "label": I18n.tr("哔哩哔哩") })
        if (Settings.get("youtubeEnabled", false) === true)
            list.push({ "value": "YOUTUBE", "label": "YouTube" })
        return list
    }
    readonly property bool sourcesEmpty: root.sources.length === 0
    property string source: "NETEASE"
    /** 媒体库把每个音源平铺成独立 Tab 时传 false，隐藏内部的音源切换条。 */
    property bool showSourceTabs: true

    /** 当前固定音源是否被设置禁用（平铺模式用）。 */
    readonly property bool sourceDisabled: {
        for (let i = 0; i < root.sources.length; ++i) {
            if (root.sources[i].value === root.source)
                return false
        }
        return true
    }

    // ---- 账号 ----
    property var accounts: []
    property var status: null
    property bool accountsLoaded: false

    // ---- 歌单 / 收藏夹 ----
    property var collections: []
    property bool loading: false
    property string errorMessage: ""
    property bool needLogin: false
    property int _seq: 0

    // ---- 网易云云盘（二级视图，对齐 Android 的云盘入口）----
    property bool cloudMode: false
    property var cloudSongs: []
    property int cloudTotal: 0
    property int cloudUsedBytes: 0
    property int cloudMaxBytes: 0
    property bool cloudHasMore: false
    property bool cloudLoading: false
    property bool cloudAllLoaded: false

    function openCloud() {
        root.cloudMode = true
        root.cloudSongs = []
        root.cloudTotal = 0
        root.cloudAllLoaded = false
        root.loadCloud(0)
    }

    function closeCloud() {
        root.cloudMode = false
        root.cloudSongs = []
        root.cloudAllLoaded = false
    }

    function loadCloud(offset) {
        if (root.cloudLoading || root.cloudAllLoaded)
            return
        root.cloudLoading = true
        Api.call("online.neteaseCloudSongs", { offset: offset, limit: 200 }).then(function(result) {
            root.cloudLoading = false
            if (!result)
                return
            if (result.needLogin) {
                root.status = null
                root.cloudMode = false
                return
            }
            const incoming = root.toArray(result.songs)
            const merged = offset === 0 ? incoming : root.cloudSongs.concat(incoming)
            root.cloudSongs = merged
            root.cloudTotal = Number(result.totalCount) || 0
            root.cloudUsedBytes = Number(result.usedBytes) || 0
            root.cloudMaxBytes = Number(result.maxBytes) || 0
            root.cloudHasMore = !!result.hasMore && merged.length > 0
            root.cloudAllLoaded = !root.cloudHasMore
        }).catch(function() {
            root.cloudLoading = false
        })
    }

    // ---- 搜索（本地过滤已加载的歌单列表，对齐 Android）----
    property string query: ""
    readonly property var filteredCollections: {
        const q = root.query.trim().toLowerCase()
        if (q === "")
            return root.collections
        return root.collections.filter(function(c) {
            return String(c.name || "").toLowerCase().indexOf(q) >= 0
                    || String(c.creator || "").toLowerCase().indexOf(q) >= 0
                    || String(c.id || "").toLowerCase().indexOf(q) >= 0
                    || String(c.description || "").toLowerCase().indexOf(q) >= 0
        })
    }

    readonly property var currentAccount: {
        for (let i = 0; i < root.accounts.length; ++i) {
            const account = root.accounts[i]
            if (account && String(account.source) === root.source)
                return account
        }
        return null
    }
    readonly property bool loggedIn: root.youtube
                                     ? !!(root.status && root.status.loggedIn)
                                     : root.currentAccount !== null
    readonly property string accountName: {
        const account = root.currentAccount
        if (!account)
            return ""
        if (account.nickname && String(account.nickname) !== "")
            return String(account.nickname)
        if (account.userId && String(account.userId) !== "")
            return "UID " + String(account.userId)
        return I18n.tr("已登录")
    }

    // ---------------------------------------------------------------- 工具函数

    /** C++ 桥下发的数组是 QVariantList 包装对象（Array.isArray 为 false），统一转成 JS 数组。 */
    function toArray(value) {
        const list = []
        if (!value)
            return list
        for (let i = 0; i < value.length; ++i)
            list.push(value[i])
        return list
    }

    function ensureValidSource() {
        // 平铺模式下音源由外层 Tab 固定，不能被设置变化改写。
        if (!root.showSourceTabs)
            return
        for (let i = 0; i < root.sources.length; ++i) {
            if (root.sources[i].value === root.source)
                return
        }
        if (root.sources.length > 0)
            root.source = root.sources[0].value
    }

    function normalizeCollection(item, fallbackSource) {
        if (!item)
            return null
        const id = item.id === undefined || item.id === null ? "" : String(item.id)
        if (id === "")
            return null
        return {
            "id": id,
            "name": item.name ? String(item.name) : I18n.tr("未命名歌单"),
            "creator": item.creator ? String(item.creator) : "",
            "coverUrl": item.coverUrl ? String(item.coverUrl) : "",
            "trackCount": Number(item.trackCount) || 0,
            "description": item.description ? String(item.description) : "",
            "source": item.source ? String(item.source) : fallbackSource,
            "youtubeAlbum": String(item.source || fallbackSource) === "YOUTUBE"
                            && id.indexOf("MPRE") === 0
        }
    }

    function subtitleFor(collection) {
        if (!collection)
            return ""
        const parts = []
        if (collection.creator)
            parts.push(collection.creator)
        if (collection.trackCount > 0)
            parts.push(collection.trackCount + I18n.tr(" 首"))
        return parts.join(" · ")
    }

    readonly property bool youtube: root.source === "YOUTUBE"

    function openCollection(collection) {
        if (!collection)
            return
        // 详情页用 collection.source / id；YouTube 专辑走 albumDetail（MPRE 前缀），其余走歌单页。
        if (collection.youtubeAlbum === true)
            Nav.push("albumDetail", collection)
        else
            Nav.push("onlineCollection", collection)
    }

    // ---------------------------------------------------------------- 数据加载

    function loadYouTubeStatus() {
        Api.get("ytmusic.status", {}, function(result, error) {
            if (error)
                return
            root.status = (result && result.status) ? result.status : null
            root.afterAccountsChanged()
        })
    }

    function loadAccounts() {
        Api.get("login.accounts", {}, function(result, error) {
            if (error) {
                // 后端尚未连接时保持未加载状态，等 Backend.connected 后重试。
                if (error.code === "disconnected")
                    return
                root.accountsLoaded = true
                return
            }
            root.accounts = root.toArray(result && result.accounts)
            root.accountsLoaded = true
            // YouTube 的登录态由 ytmusic.status 判定（账号记录 + Cookie 罐都有效才算登录），
            // 不能只看 login.accounts：残留的账号记录会让界面误判为已登录而只显示空列表。
            if (root.youtube)
                root.loadYouTubeStatus()
            else
                root.afterAccountsChanged()
        })
    }

    function reloadCollections() {
        if (!root.loggedIn || root.source === "" || root.sourceDisabled)
            return
        const targetSource = root.source
        const seq = ++root._seq
        root.loading = true
        root.errorMessage = ""
        root.needLogin = false

        function apply(result, error) {
            if (seq !== root._seq)
                return
            root.loading = false
            if (error) {
                root.collections = []
                root.errorMessage = error.message || I18n.tr("加载失败")
                return
            }
            const list = root.toArray(result && result.collections)
            const items = []
            for (let i = 0; i < list.length; ++i) {
                const collection = root.normalizeCollection(list[i], targetSource)
                if (collection)
                    items.push(collection)
            }
            root.collections = items
            root.needLogin = !!(result && result.needLogin)
            // YouTube 返回 needLogin 说明 Cookie 已失效：回到未登录态，展示粘贴 Cookie 引导。
            if (root.needLogin && targetSource === "YOUTUBE")
                root.status = null
            root.errorMessage = ""
        }

        if (targetSource === "BILIBILI")
            Api.get("online.biliFavorites", {}, apply)
        else if (targetSource === "YOUTUBE")
            Api.get("ytmusic.myPlaylists", {}, apply)
        else
            Api.get("online.myPlaylists", { source: "NETEASE" }, apply)
    }

    /** 登录状态变化后：已登录则拉歌单，未登录则清空展示登录引导。 */
    function afterAccountsChanged() {
        if (root.loggedIn) {
            root.reloadCollections()
            return
        }
        ++root._seq
        root.collections = []
        root.loading = false
        root.errorMessage = ""
        root.needLogin = false
    }

    function refresh() {
        if (!root.accountsLoaded || !root.loggedIn) {
            root.loadAccounts()
            return
        }
        // 先刷新账号信息（昵称可能变化），loadAccounts 成功后会再拉一次歌单。
        root.loadAccounts()
    }

    function onAccountsChanged(data) {
        const list = root.toArray(data)
        if (list.length > 0) {
            root.accounts = list
            root.accountsLoaded = true
            if (root.youtube)
                root.loadYouTubeStatus()
            else
                root.afterAccountsChanged()
            return
        }
        // 数组事件在 Qt 桥里通常只剩空对象，拿不到内容时重新查询账号列表。
        root.loadAccounts()
    }

    function openLogin() {
        // YouTube 面板挂载名固定为 youtubeLogin（由集成方 PlayerOverlays 挂载）
        if (root.youtube)
            Nav.openPanel("youtubeLogin")
        else
            Nav.openLoginPanel(root.source)
    }

    // ---------------------------------------------------------------- 生命周期

    onSourcesChanged: root.ensureValidSource()

    onSourceChanged: {
        ++root._seq
        root.collections = []
        root.loading = false
        root.errorMessage = ""
        root.needLogin = false
        if (root.accountsLoaded && root.loggedIn)
            root.reloadCollections()
        else if (root.accountsLoaded && root.youtube)
            root.loadYouTubeStatus()
    }

    Component.onCompleted: {
        Api.on("accounts.changed", root.onAccountsChanged)
        root.ensureValidSource()
        if (Api.connected)
            root.loadAccounts()
    }

    Component.onDestruction: {
        Api.off("accounts.changed", root.onAccountsChanged)
    }

    // 冷启动时页面可能先于后端连接创建，连接建立后再读账号。
    property Connections backendConnection: Connections {
        target: Backend

        function onConnectedChanged() {
            if (Backend.connected && !root.accountsLoaded)
                root.loadAccounts()
        }
    }

    // ---------------------------------------------------------------- 顶部：音源切换 + 账号状态

    Item {
        id: sourceBar

        anchors.top: parent.top
        anchors.left: parent.left
        anchors.right: parent.right
        // 平铺模式下没有内部音源切换条，未登录时也没有账号信息，整条留白没有意义。
        height: (root.showSourceTabs === false && !(root.accountsLoaded && root.loggedIn)) ? 0 : 44
        clip: true

        TabBar {
            id: sourceTabs

            anchors.left: parent.left
            anchors.verticalCenter: parent.verticalCenter
            visible: root.showSourceTabs && !root.sourcesEmpty
            options: root.sources
            value: root.source
            onSelected: function(value) { root.source = value }
        }

        Row {
            anchors.right: parent.right
            anchors.rightMargin: Theme.spacingLg
            anchors.verticalCenter: parent.verticalCenter
            spacing: Theme.spacingSm
            visible: root.accountsLoaded && root.loggedIn

            Root.NeriIcon {
                anchors.verticalCenter: parent.verticalCenter
                name: "account_circle"
                iconSize: 18
                color: Theme.onSurfaceVariantColor
            }

            Text {
                anchors.verticalCenter: parent.verticalCenter
                width: Math.min(implicitWidth, 180)
                text: root.accountName
                elide: Text.ElideRight
                maximumLineCount: 1
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("labelMedium").size
            }

            Root.IconButton {
                anchors.verticalCenter: parent.verticalCenter
                icon: "refresh"
                iconSize: 20
                size: 36
                tooltip: I18n.tr("刷新")
                onClicked: root.refresh()
            }
        }
    }

    // ---------------------------------------------------------------- 内容

    Item {
        id: contentArea

        anchors.top: sourceBar.bottom
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        clip: true

        // 聚合模式：所有音源都被禁用；平铺模式：当前音源被禁用
        EmptyState {
            anchors.fill: parent
            visible: root.sourcesEmpty || (root.showSourceTabs === false && root.sourceDisabled)
            icon: "cloud_off"
            title: root.showSourceTabs === false ? I18n.tr("音源已禁用") : I18n.tr("在线音源已禁用")
            hint: root.showSourceTabs === false
                  ? I18n.tr("在「设置 - 在线音源」中启用该音源后，即可在此浏览账号歌单")
                  : I18n.tr("在「设置 - 在线音源」中启用网易云 / 哔哩哔哩后，可以在媒体库这里浏览账号歌单")
        }

        LoadingState {
            anchors.top: parent.top
            anchors.left: parent.left
            anchors.right: parent.right
            visible: !root.sourcesEmpty && !root.accountsLoaded
            text: I18n.tr("正在读取账号状态…")
        }

        // 未登录：扫码登录引导（网易云 / 哔哩哔哩）
        SourceLoginPrompt {
            anchors.fill: parent
            visible: !root.sourcesEmpty && root.accountsLoaded && !root.loggedIn && !root.youtube
            source: root.source
            onLoginClicked: root.openLogin()
        }

        // 未登录：YouTube 只能粘贴浏览器 Cookie
        Flickable {
            anchors.fill: parent
            visible: !root.sourcesEmpty && root.accountsLoaded && !root.loggedIn && root.youtube
            clip: true
            contentWidth: width
            contentHeight: youTubeLoginCard.height + Theme.spacingXl * 2
            boundsBehavior: Flickable.StopAtBounds

            YouTubeLoginCard {
                id: youTubeLoginCard

                x: Theme.spacingLg
                y: Theme.spacingLg
                width: Math.min(parent.width - Theme.spacingLg * 2, 560)
                onLoginStateChanged: root.loadAccounts()
            }
        }

        // 已登录内容
        Item {
            id: loggedInContent

            anchors.fill: parent
            visible: !root.sourcesEmpty && root.accountsLoaded && root.loggedIn

            LoadingState {
                anchors.top: parent.top
                anchors.left: parent.left
                anchors.right: parent.right
                visible: root.loading && root.collections.length === 0
                        && root.errorMessage === "" && !root.needLogin
                text: I18n.tr("正在加载") + (root.source === "BILIBILI" ? I18n.tr("收藏夹…")
                                    : root.youtube ? I18n.tr(" YouTube 歌单…") : I18n.tr("我的歌单…"))
            }

            ErrorCard {
                anchors.top: parent.top
                anchors.left: parent.left
                anchors.right: parent.right
                visible: root.errorMessage !== ""
                message: I18n.tr("加载失败：") + root.errorMessage
                onRetryClicked: root.reloadCollections()
            }

            // 接口返回 needLogin（Cookie 失效 / 账号信息缺失）：提示重新登录。
            // YouTube 不走扫码，统一由未登录分支的 Cookie 卡承接。
            SourceLoginPrompt {
                anchors.fill: parent
                visible: root.needLogin && !root.loading && !root.youtube
                source: root.source
                relogin: true
                onLoginClicked: root.openLogin()
            }

            EmptyState {
                anchors.fill: parent
                visible: !root.cloudMode && !root.loading && !root.needLogin && root.errorMessage === ""
                        && (root.collections.length === 0 || root.filteredCollections.length === 0)
                icon: root.query.trim() !== "" ? "search_off"
                      : root.source === "BILIBILI" ? "bookmark_border"
                      : root.youtube ? "smart_display" : "queue_music"
                title: root.query.trim() !== "" ? I18n.tr("没有匹配的歌单")
                       : root.source === "BILIBILI" ? I18n.tr("还没有收藏夹")
                       : root.youtube ? I18n.tr("还没有 YouTube 歌单") : I18n.tr("还没有歌单")
                hint: root.query.trim() !== "" ? I18n.tr("换个关键词试试")
                      : root.youtube
                        ? I18n.tr("YouTube 未登录或账号下没有歌单曲库，登录后可浏览「我的歌单」")
                        : I18n.tr("当前账号下暂无可浏览的内容，点击右上角刷新重试")
            }

            Flickable {
                id: collectionsPane

                anchors.fill: parent
                visible: !root.cloudMode && !root.needLogin && root.errorMessage === ""
                         && root.filteredCollections.length > 0
                clip: true
                contentWidth: width
                contentHeight: collectionsFlow.height + Theme.spacingXl
                boundsBehavior: Flickable.StopAtBounds

                Flow {
                    id: collectionsFlow

                    x: Theme.spacingLg
                    y: Theme.spacingSm
                    width: collectionsPane.width - Theme.spacingLg * 2
                    spacing: Theme.spacingMd

                    // 响应式列数：按可用宽度约 170 一格；卡片宽度随列数伸缩并限制在 190 以内。
                    readonly property int columns: Math.max(1, Math.floor((width + spacing) / 170))
                    readonly property real cardWidth: Math.min(190,
                                                             (width - spacing * (columns - 1)) / columns)

                    // 网易云云盘入口（对齐 Android：歌单列表顶部的卡片式入口）。
                    // Flow 不为 visible:false 让位，非网易云时用 0 宽避免留下空位。
                    CollectionCard {
                        readonly property bool showCloud: root.source === "NETEASE" && root.query.trim() === ""
                        cardWidth: showCloud ? collectionsFlow.cardWidth : 0
                        name: I18n.tr("云盘")
                        subtitle: I18n.tr("上传到网易云的音乐")
                        coverUrl: ""
                        placeholderIcon: "cloud"
                        placeholderColor: Theme.secondaryContainer
                        placeholderIconFilled: true
                        placeholderIconColor: Theme.onSecondaryContainerColor
                        onClicked: root.openCloud()
                    }

                    Repeater {
                        model: root.filteredCollections

                        delegate: CollectionCard {
                            id: collectionCard

                            property var item: modelData

                            cardWidth: collectionsFlow.cardWidth
                            name: item ? item.name : ""
                            subtitle: root.subtitleFor(item)
                            coverUrl: item ? item.coverUrl : ""
                            placeholderIcon: item && item.source === "BILIBILI"
                                             ? "bookmark_border"
                                             : item && item.source === "YOUTUBE"
                                               ? "smart_display" : "music_note"
                            onClicked: root.openCollection(item)
                        }
                    }
                }
            }

            // ---- 网易云云盘视图 ----
            Item {
                anchors.fill: parent
                visible: root.cloudMode

                Column {
                    anchors.top: parent.top
                    anchors.left: parent.left
                    anchors.right: parent.right
                    spacing: Theme.spacingSm

                    // 工具栏：返回 / 标题 / 容量 / 播放全部
                    RowLayout {
                        width: parent.width - Theme.spacingLg * 2
                        anchors.horizontalCenter: parent.horizontalCenter
                        spacing: Theme.spacingSm

                        Root.IconButton {
                            icon: "arrow_back"
                            iconSize: 20
                            size: 36
                            tooltip: I18n.tr("返回")
                            onClicked: root.closeCloud()
                        }

                        Column {
                            Layout.fillWidth: true
                            Text {
                                text: I18n.tr("云盘")
                                color: Theme.onSurfaceColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("titleMedium").size
                                font.weight: Font.DemiBold
                            }
                            Text {
                                text: root.cloudTotal + I18n.tr(" 首 · 已用 ")
                                      + Math.round(root.cloudUsedBytes / 1048576) + " MB / "
                                      + Math.round(root.cloudMaxBytes / 1048576) + " MB"
                                color: Theme.onSurfaceVariantColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("labelSmall").size
                            }
                        }

                        Controls.Button {
                            text: I18n.tr("播放全部")
                            enabled: root.cloudSongs.length > 0
                            onClicked: Player.setQueue(root.cloudSongs, 0, true)
                        }

                        Root.IconButton {
                            icon: "refresh"
                            iconSize: 20
                            size: 36
                            tooltip: I18n.tr("刷新")
                            onClicked: {
                                root.cloudSongs = []
                                root.cloudAllLoaded = false
                                root.loadCloud(0)
                            }
                        }
                    }

                    ListView {
                        width: parent.width - Theme.spacingLg * 2
                        anchors.horizontalCenter: parent.horizontalCenter
                        height: root.cloudMode ? parent.height - 72 : 0
                        clip: true
                        model: root.cloudSongs
                        boundsBehavior: Flickable.StopAtBounds

                        delegate: Item {
                            width: ListView.view.width
                            height: 58
                            property var song: modelData
                            SongRow {
                                anchors.fill: parent
                                song: parent.song
                                index: modelData === undefined ? -1 : index
                                showMore: false
                                onActivated: function(idx) { Player.setQueue(root.cloudSongs, idx, true) }
                            }
                        }

                        footer: Item {
                            width: ListView.view.width
                            height: root.cloudHasMore ? 52 : 8
                            visible: root.cloudHasMore
                            Controls.Button {
                                anchors.centerIn: parent
                                text: root.cloudLoading ? I18n.tr("正在加载更多…") : I18n.tr("加载更多")
                                enabled: !root.cloudLoading
                                onClicked: root.loadCloud(root.cloudSongs.length)
                            }
                        }
                    }
                }

                LoadingState {
                    anchors.centerIn: parent
                    visible: root.cloudMode && root.cloudLoading && root.cloudSongs.length === 0
                    text: I18n.tr("正在加载云盘…")
                }

                EmptyState {
                    anchors.centerIn: parent
                    visible: root.cloudMode && !root.cloudLoading && root.cloudSongs.length === 0
                    icon: "cloud_off"
                    title: I18n.tr("云盘暂无歌曲")
                    hint: I18n.tr("上传到网易云云盘的歌曲会出现在这里")
                }
            }
        }
    }
}
