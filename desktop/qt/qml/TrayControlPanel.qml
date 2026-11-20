import QtQuick
import QtQuick.Controls
import "components"
import "components/player"

// 托盘控制面板：无边框小窗，从托盘菜单 / Ctrl+M / 托盘图标激活时弹出，失焦自动收起。
Window {
    id: panel

    property Window mainWindow: null

    readonly property real panelWidth: 340
    // 高度固定：面板是可见状态的无边框 Qt.Tool 窗口，运行期改 height 会让内容停在
    // 旧布局里错位（重新显示才恢复），所以新增控件一律在行内解决、不撑高窗口。
    // 底部退出行锚定面板底边（bottomMargin 12 + 高 34），动作列表是流式布局；
    // 内容增多（如悬浮歌词锁定时多出解锁按钮）会顶到分隔线，这里留出足够余量。
    readonly property real panelHeight: 476

    /** 常驻：开启后不因失焦 / 主窗口激活自动收起（Esc、关闭按钮、点动作项仍会收起）。 */
    readonly property bool persistent: Settings.get("trayPanelPersistent", false) === true
    /** 悬浮歌词位置锁定状态：锁定时悬浮窗整窗鼠标穿透，面板里给一个解锁兜底入口。 */
    readonly property bool floatingLyricsLocked: Settings.get("floatingLyricsLocked", false) === true

    width: panelWidth
    height: panelHeight
    flags: Qt.Window | Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint | Qt.Tool
    color: "transparent"
    title: "NeriPlayer 控制面板"
    visible: false

    x: screen ? Math.round(screen.virtualX + screen.width - width - 16) : 0
    y: screen ? Math.round(screen.virtualY + screen.height - height - 16) : 0

    /** 显示面板。force=true 时即使已显示也保持显示（外部命令用，避免误切换成隐藏）。 */
    function showPanel(force) {
        if (visible) {
            if (!force)
                hidePanel()
            else
                raise()
            return
        }
        // 每次弹出都重新贴到屏幕右下角（Wayland 无法拿到托盘图标位置）
        if (screen) {
            x = Math.round(screen.virtualX + screen.width - width - 16)
            y = Math.round(screen.virtualY + screen.height - height - 16)
        }
        // 主窗口隐藏到托盘时，本窗口若是它的 transient child 会被连带隐藏；
        // 这里用 keepVisible 同时断开 transient 关系并确保窗口真正显示出来。
        visible = true
        // 刚弹出的几百毫秒内忽略失焦，等窗口管理器把激活状态送达
        panel._justShown = true
        panel._justShownTimer.restart()
        WindowDrag.keepVisible(panel)
        raise()
        requestActivate()
    }

    function hidePanel() {
        visible = false
    }

    function formatTime(ms) {
        const total = Math.max(0, Math.round((ms || 0) / 1000))
        const hours = Math.floor(total / 3600)
        const minutes = Math.floor((total % 3600) / 60)
        const seconds = total % 60
        const mm = minutes < 10 ? "0" + minutes : String(minutes)
        const ss = seconds < 10 ? "0" + seconds : String(seconds)
        return hours > 0 ? (hours + ":" + mm + ":" + ss) : (mm + ":" + ss)
    }

    /**
     * 动作列表。
     *
     * 高度固定、条目也固定：新增的「解锁悬浮歌词位置」不占一行，
     * 而是挂在「悬浮歌词」这一行的右侧（见 delegate 里的 unlockButton）。
     */
    readonly property var actionModel: [
        { "icon": "home", "label": I18n.tr("显示主窗口"), "action": "showWindow" },
        { "icon": "album", "label": I18n.tr("打开播放页"), "action": "nowplaying" },
        { "icon": "settings", "label": I18n.tr("设置"), "action": "settings" },
        { "icon": "download", "label": I18n.tr("下载管理"), "action": "downloads" },
        { "icon": "lyrics", "label": Settings.get("floatingLyricsEnabled", false) ? I18n.tr("关闭悬浮歌词") : I18n.tr("开启悬浮歌词"), "action": "floating" }
    ]

    // 失焦自动收起。注意这里只看「本面板自身」是否还激活：
    // 主窗口隐藏到托盘时整个应用会失活（Qt.application.state 变 Inactive），
    // 若用应用级状态判断，会刚弹出就被自己收起来——这正是面板弹不出来的原因。
    // 另外刚弹出的一瞬间 active 还是 false，需要忽略这段时间的失焦事件。
    property bool _justShown: false
    property Timer _justShownTimer: Timer {
        interval: 350
        repeat: false
        onTriggered: panel._justShown = false
    }

    onActiveChanged: {
        // 常驻模式下失焦不收起：方便连续点多个动作（如先开悬浮歌词再解锁）
        if (!active && visible && !panel._justShown && !panel.persistent)
            hidePanel()
    }

    // 点击主窗口（同一应用内）时也收起面板
    property Connections mainWindowConnection: Connections {
        target: panel.mainWindow
        function onActiveChanged() {
            // 常驻模式下切到主窗口也不收起
            if (panel.persistent)
                return
            // 面板刚弹出的瞬间主窗口可能正好被激活，这里要和失焦逻辑一样加豁免，
            // 否则从托盘菜单打开面板会被立刻收起来。
            if (panel._justShown || panel._justShownTimer.running)
                return
            if (panel.mainWindow && panel.mainWindow.active && panel.visible && !panel.active)
                panel.hidePanel()
        }
    }

    Shortcut {
        sequences: [StandardKey.Cancel, "Esc", "Escape"]
        enabled: panel.visible
        onActivated: panel.hidePanel()
    }

    // 无边框 Qt.Tool 窗口在 Wayland 下 Shortcut 不一定能拿到按键，内层 Item 的 Keys 兜底。
    // 注意：该 Item 必须不拦截鼠标（否则下面的按钮全点不到），只负责接键盘焦点。
    Item {
        anchors.fill: parent
        focus: true
        Keys.onPressed: function(event) {
            if (event.key === Qt.Key_Escape) {
                panel.hidePanel()
                event.accepted = true
            }
        }
        Component.onCompleted: {
            forceActiveFocus()
            // 创建时就先脱离父窗口：主窗口若一开始就隐藏到托盘，也能独立弹出。
            WindowDrag.detachFromParent(panel)
        }
    }

    // Wayland 下 QWindow.active / 失焦事件不总是可靠：面板打开后若长时间无操作则自动收起。
    // 常驻模式（trayPanelPersistent）下关掉这个兜底——用户明确要求面板留在这里，
    // 收起只能由 Esc / 关闭按钮 / 点动作项触发。
    property bool hovered: false
    property Timer autoHideTimer: Timer {
        interval: 15000
        repeat: false
        running: panel.visible && !panel.hovered && !panel.persistent
        // running 绑定会被 restart() 打断（start/restart 直接写 running），
        // 这里再判断一次，确保常驻模式任何情况下都不会被这个兜底计时器收起。
        onTriggered: if (!panel.persistent) panel.hidePanel()
    }
    onVisibleChanged: if (visible) autoHideTimer.restart()

    // 卡片主体
    Rectangle {
        anchors.fill: parent
        radius: 20
        color: Theme.surfaceContainerLow
        border.width: 1
        border.color: Theme.withAlpha(Theme.outlineVariant, 0.7)

        HoverHandler {
            onHoveredChanged: panel.hovered = hovered
        }

        Column {
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.topMargin: 14
            anchors.leftMargin: 14
            anchors.rightMargin: 14
            // 底部让出「退出」按钮 + 分隔线 + 间隙的高度
            anchors.bottomMargin: 72
            spacing: 12

            // ---- 歌曲信息 ----
            Item {
                width: parent.width
                height: 46

                Row {
                    anchors.left: parent.left
                    anchors.right: closeButton.left
                    anchors.rightMargin: 4
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: 10

                    CoverArt {
                        width: 46
                        height: 46
                        radius: 12
                        path: Player.hasSong && Player.song && Player.song.artworkPath ? Player.song.artworkPath : ""
                        url: Player.hasSong && Player.song && Player.song.artworkUrl ? Player.song.artworkUrl : ""
                    }

                    Column {
                        width: parent.width - 56
                        anchors.verticalCenter: parent.verticalCenter
                        spacing: 2

                        Text {
                            width: parent.width
                            text: Player.hasSong ? Player.title : "未在播放"
                            elide: Text.ElideRight
                            color: Theme.onSurfaceColor
                            font.family: Theme.fontFamily
                            font.pixelSize: 15
                            font.weight: Font.DemiBold
                        }

                        Row {
                            width: parent.width
                            spacing: 5

                            // 播放状态圆点：播放中主色，暂停灰（旧版位置在歌手名左侧）
                            Rectangle {
                                anchors.verticalCenter: parent.verticalCenter
                                width: 6
                                height: 6
                                radius: 3
                                color: Player.playing ? Theme.primary : Theme.outline
                            }

                            Text {
                                width: Math.max(0, parent.width - 11)
                                text: Player.hasSong ? Player.artist : "NeriPlayer"
                                elide: Text.ElideRight
                                color: Theme.onSurfaceVariantColor
                                font.family: Theme.fontFamily
                                font.pixelSize: 12
                            }
                        }
                    }
                }

                IconButton {
                    id: closeButton
                    anchors.right: parent.right
                    anchors.verticalCenter: parent.verticalCenter
                    icon: "close"
                    iconSize: 16
                    size: 28
                    tooltip: "关闭控制面板"
                    onClicked: panel.hidePanel()
                }
            }

            // ---- 传输控制 ----
            Item {
                width: parent.width
                height: 52

                IconButton {
                    anchors.left: parent.left
                    anchors.leftMargin: 12
                    anchors.verticalCenter: parent.verticalCenter
                    icon: "skip_previous"
                    iconSize: 26
                    size: 40
                    onClicked: Player.previous()
                }

                Rectangle {
                    anchors.centerIn: parent
                    width: 52
                    height: 52
                    radius: 26
                    color: Theme.primary

                    NeriIcon {
                        anchors.centerIn: parent
                        name: Player.playing ? "pause" : "play_arrow"
                        filled: true
                        iconSize: 28
                        color: Theme.onPrimaryColor
                    }

                    HoverHandler { cursorShape: Qt.PointingHandCursor }
                    MouseArea {
                        anchors.fill: parent
                        onClicked: Player.togglePlayPause()
                    }
                }

                IconButton {
                    anchors.right: parent.right
                    anchors.rightMargin: 12
                    anchors.verticalCenter: parent.verticalCenter
                    icon: "skip_next"
                    iconSize: 26
                    size: 40
                    onClicked: Player.next()
                }

            }

            // ---- 进度 ----
            // 轨道在上、时间文字在下，两者各自占独立的一行，避免互相压住。
            Item {
                width: parent.width
                height: track.height + 4 + timeRow.height

                Rectangle {
                    id: track
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.top: parent.top
                    height: 4
                    radius: 2
                    color: Theme.surfaceVariant

                    Rectangle {
                        width: parent.width * Player.progress
                        height: parent.height
                        radius: 2
                        color: Theme.primary
                    }

                    MouseArea {
                        anchors.fill: parent
                        anchors.topMargin: -6
                        anchors.bottomMargin: -6
                        onClicked: function(mouse) {
                            if (Player.durationMs > 0)
                                Player.seekTo(mouse.x / width * Player.durationMs)
                        }
                    }
                }

                Item {
                    id: timeRow
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.top: track.bottom
                    anchors.topMargin: 4
                    height: 14

                    Text {
                        anchors.left: parent.left
                        anchors.verticalCenter: parent.verticalCenter
                        text: formatTime(Player.positionMs)
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: 10
                    }

                    Text {
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        text: formatTime(Player.durationMs)
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: 10
                    }
                }
            }

            // ---- 音量 ----
            Row {
                width: parent.width
                spacing: 6

                NeriIcon {
                    anchors.verticalCenter: parent.verticalCenter
                    name: Player.volume <= 0.001 ? "volume_off" : (Player.volume < 0.5 ? "volume_down" : "volume_up")
                    iconSize: 20
                    color: Theme.onSurfaceVariantColor
                }

                PlayerSlider {
                    width: parent.width - 26
                    from: 0
                    to: 1
                    value: Player.volume
                    onMoved: function(value) { Player.setVolume(value) }
                }
            }

            // ---- 动作（与旧版一致：常规动作在上，退出单独贴底部并用分隔线隔开） ----
            Column {
                width: parent.width
                spacing: 2

                Repeater {
                    model: panel.actionModel
                    delegate: Rectangle {
                        required property var modelData
                        width: parent.width
                        height: 32
                        radius: 10
                        color: itemHover.hovered ? Theme.withAlpha(Theme.secondaryContainer, 0.6) : "transparent"

                        Row {
                            anchors.left: parent.left
                            anchors.leftMargin: 8
                            anchors.verticalCenter: parent.verticalCenter
                            spacing: 10

                            NeriIcon {
                                anchors.verticalCenter: parent.verticalCenter
                                name: modelData.icon
                                iconSize: 18
                                color: Theme.onSurfaceVariantColor
                            }

                            Text {
                                anchors.verticalCenter: parent.verticalCenter
                                text: modelData.label
                                color: Theme.onSurfaceColor
                                font.family: Theme.fontFamily
                                font.pixelSize: 13
                            }
                        }

                        HoverHandler {
                            id: itemHover
                            cursorShape: Qt.PointingHandCursor
                        }

                        MouseArea {
                            // 右侧留出解锁按钮的位置，避免点解锁也触发整行动作
                            anchors.fill: parent
                            anchors.rightMargin: unlockButton.visible ? unlockButton.width + 6 : 0
                            onClicked: {
                                panel.hidePanel()
                                switch (modelData.action) {
                                case "showWindow":
                                    if (panel.mainWindow) panel.mainWindow.showMainWindow()
                                    break
                                // 主窗口可能已收进托盘：先把它显示出来再导航，
                                // 否则页面切了但用户看不到（要等下一次点主页才露出来）。
                                case "nowplaying":
                                    if (panel.mainWindow) panel.mainWindow.showMainWindow()
                                    Nav.push("nowplaying")
                                    break
                                case "settings":
                                    if (panel.mainWindow) panel.mainWindow.showMainWindow()
                                    Nav.openTab("settings")
                                    break
                                case "downloads":
                                    if (panel.mainWindow) panel.mainWindow.showMainWindow()
                                    Nav.openDownloadsPanel()
                                    break
                                case "floating":
                                    Settings.set("floatingLyricsEnabled", !(Settings.get("floatingLyricsEnabled", false) === true))
                                    break
                                }
                            }
                        }

                        // 悬浮歌词锁定时，「悬浮歌词」这一行右侧多一个解锁入口。
                        // 放行内而不是单独加一行：面板高度固定（运行期改高度会错位），
                        // 单独加一行又会把动作列表挤到窗口外。
                        // 必须声明在整行 MouseArea 之后，否则会被整行的点击区盖住点不到。
                        Rectangle {
                            id: unlockButton
                            anchors.right: parent.right
                            anchors.rightMargin: 6
                            anchors.verticalCenter: parent.verticalCenter
                            visible: modelData.action === "floating" && panel.floatingLyricsLocked
                            width: visible ? unlockRow.width + 16 : 0
                            height: 24
                            radius: 12
                            color: unlockHover.hovered ? Theme.withAlpha(Theme.primary, 0.16) : Theme.withAlpha(Theme.primary, 0.08)

                            Row {
                                id: unlockRow
                                anchors.centerIn: parent
                                spacing: 4

                                NeriIcon {
                                    anchors.verticalCenter: parent.verticalCenter
                                    name: "lock_open"
                                    iconSize: 14
                                    color: Theme.primary
                                }

                                Text {
                                    anchors.verticalCenter: parent.verticalCenter
                                    text: I18n.tr("解除位置锁定")
                                    color: Theme.primary
                                    font.family: Theme.fontFamily
                                    font.pixelSize: 11
                                }
                            }

                            HoverHandler {
                                id: unlockHover
                                cursorShape: Qt.PointingHandCursor
                            }

                            MouseArea {
                                anchors.fill: parent
                                // 只处理「解锁」，不收起面板：用户可能还要继续操作
                                onClicked: Settings.set("floatingLyricsLocked", false)
                            }
                        }
                    }
                }
            }

        }

        // ---- 底部退出（锚定卡片底边，与上方内容自然分离） ----
        Rectangle {
            id: quitRow
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            anchors.leftMargin: 14
            anchors.rightMargin: 14
            anchors.bottomMargin: 12
            height: 34
            radius: 10
            color: quitHover.hovered ? Theme.withAlpha(Theme.errorContainer, 0.6) : "transparent"

            Rectangle {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.bottom: parent.top
                anchors.bottomMargin: 6
                height: 1
                color: Theme.withAlpha(Theme.outlineVariant, 0.6)
            }

            Row {
                anchors.left: parent.left
                anchors.leftMargin: 8
                anchors.verticalCenter: parent.verticalCenter
                spacing: 10

                NeriIcon {
                    anchors.verticalCenter: parent.verticalCenter
                    name: "logout"
                    iconSize: 18
                    color: Theme.error
                }

                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: "退出 NeriPlayer"
                    color: Theme.error
                    font.family: Theme.fontFamily
                    font.pixelSize: 13
                }
            }

            HoverHandler {
                id: quitHover
                cursorShape: Qt.PointingHandCursor
            }

            MouseArea {
                anchors.fill: parent
                onClicked: {
                    panel.hidePanel()
                    if (panel.mainWindow && panel.mainWindow.quitApp)
                        panel.mainWindow.quitApp()
                }
            }
        }
    }
}
