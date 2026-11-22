import QtQuick
import QtQuick.Controls

// 后端日志查看器（Dialog 浮层模式）。
//
// 全部日志逻辑与 UI 抽到 LogViewContent.qml，Dialog 与弹出的独立窗口（DesktopLogWindow）
// 都嵌入它，避免复制 600 行列表逻辑。"弹出为独立窗口"按钮在内容标题栏里，
// 点击后创建一个子 Window：生命周期跟随本 Dialog（设置页缓存释放时被销毁），
// 主应用退出时随主窗口关闭，不需要改 Main.qml。
//
// 用法（入口由集成负责人接线）：
//   debugLogViewer.openViewer()
Dialog {
    id: root

    parent: Overlay.overlay
    anchors.centerIn: parent
    modal: true
    width: Math.min(900, parent ? parent.width - Theme.spacingXl * 2 : 900)
    height: Math.min(640, parent ? parent.height - Theme.spacingXl * 2 : 640)
    padding: 0
    closePolicy: Popup.CloseOnEscape

    /** 独立窗口实例（Dialog 的子对象，Dialog 销毁时一并销毁）。 */
    property var logWindow: null

    background: Rectangle {
        radius: Theme.radiusXxl
        color: Theme.surfaceContainerHigh
    }

    contentItem: LogViewContent {
        id: content

        onDetachRequested: root.openStandalone()
        onCloseRequested: root.close()
    }

    // ------------------------------------------------------------------ 对外接口

    function openViewer() {
        root.closeStandalone()
        content.start()
        root.open()
    }

    /** 弹出独立窗口：关闭 Dialog 浮层，让位给并排显示的独立窗口。 */
    function openStandalone() {
        if (root.logWindow) {
            root.logWindow.showViewer()
            root.close()
            return
        }
        const component = Qt.createComponent(Qt.resolvedUrl("DesktopLogWindow.qml"))
        if (component.status !== Component.Ready) {
            console.warn("[logs] DesktopLogWindow.qml:", component.errorString())
            return
        }
        const win = component.createObject(root)
        if (!win) {
            console.warn("[logs] 无法创建独立日志窗口")
            return
        }
        root.logWindow = win
        win.showViewer()
        root.close()
    }

    function closeStandalone() {
        if (root.logWindow) {
            root.logWindow.close()
            root.logWindow.destroy()
            root.logWindow = null
        }
    }

    Component.onDestruction: root.closeStandalone()
}
