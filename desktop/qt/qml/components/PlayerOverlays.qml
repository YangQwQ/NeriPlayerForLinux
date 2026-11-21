import QtQuick
import "motion"

// 主窗口内的浮层面板宿主：根据 Nav.activePanel 加载对应面板。
// 具体面板实现放在 components/player/ 下（下载/登录面板在 components/panels/）。
// z 要高于底部迷你播放条与导航栏，遮罩才能铺满整个窗口。
//
// 统一的进出动画在这里做：各面板骨架（PanelShell / PanelScaffold）只实现了「出现时的
// 滑入 + 淡入」，关闭时因为 Loader.active 立刻变 false，面板直接消失，没有退场。
// 这里把面板整体包一层 opacity/scale 的 Behavior：
//   - panel 变为非空：Loader 立即加载面板，本层淡入 + 轻微放大（面板骨架自己仍做侧滑）；
//   - panel 变为空：先播完淡出，再把 Loader.active 置 false，避免面板闪一下就没。
Item {
    id: host

    readonly property string panel: Nav.activePanel
    visible: host.panel !== "" || fade.opacity > 0.01
    z: 50

    MotionPrefs { id: motion }
    readonly property int panelDuration: motion.panelTransition ? Theme.motionMedium : 0

    // 动画期间即使 Nav.activePanel 已清空，也保持 Loader 存活以播完淡出。
    property bool shouldLoad: host.panel !== "" || fade.opacity > 0.01

    // 记住最后一个面板源：淡出期间 Nav.activePanel 已变空，若直接把 source 改成 ""
    // 面板内容会瞬间消失，动画就失去意义。等淡出结束（shouldLoad=false）再回落到 ""。
    property string lastSource: ""
    onPanelChanged: {
        if (host.panel !== "")
            host.lastSource = host.sourceForPanel(host.panel)
    }

    function sourceForPanel(name) {
        switch (name) {
        case "queue": return "player/QueuePanel.qml"
        case "volume": return "player/VolumePanel.qml"
        case "effects": return "player/EffectsPanel.qml"
        case "sleep": return "player/SleepTimerPanel.qml"
        case "downloads": return "panels/DownloadsPanel.qml"
        case "login": return "panels/LoginPanel.qml"
        case "comments": return "panels/CommentsPanel.qml"
        case "listenTogether": return "panels/ListenTogetherPanel.qml"
        case "youtubeLogin": return "panels/YouTubeLoginPanel.qml"
        default: return ""
        }
    }

    Item {
        id: fade

        anchors.fill: parent
        opacity: host.panel !== "" ? 1 : 0
        scale: host.panel !== "" ? 1 : 0.97
        transformOrigin: Item.Center

        Behavior on opacity {
            NumberAnimation { duration: host.panelDuration; easing.type: Easing.OutCubic }
        }
        Behavior on scale {
            NumberAnimation { duration: host.panelDuration; easing.type: Easing.OutCubic }
        }

        Loader {
            id: panelLoader

            anchors.fill: parent
            active: host.shouldLoad
            source: host.shouldLoad ? host.lastSource : ""
        }
    }
}
