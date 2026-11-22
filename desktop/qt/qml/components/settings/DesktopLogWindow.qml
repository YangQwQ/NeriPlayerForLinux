import QtQuick
import QtQuick.Window

// 独立日志窗口：可拖动、可缩放，放在主窗口旁边一边看日志一边测试。
// 由设置页里的日志 Dialog 弹出，作为 Dialog 的子 Window 创建 —— 生命周期跟随
// Dialog：设置页缓存释放时 Dialog 被销毁会带走本窗口；主应用退出时随主窗口一起关闭，
// 不需要 Main.qml 的 destroyAuxWindows 配合。
Window {
    id: win

    title: I18n.tr("NeriPlayer 运行日志")
    width: 900
    height: 640
    minimumWidth: 520
    minimumHeight: 360
    color: Theme.surfaceContainerHigh

    // 普通应用窗口（默认带系统边框与任务栏图标），用户可自由拖动/缩放/置顶。
    flags: Qt.Window | Qt.WindowTitleHint | Qt.WindowSystemMenuHint
           | Qt.WindowMinimizeButtonHint | Qt.WindowMaximizeButtonHint | Qt.WindowCloseButtonHint

    // 内容区铺满窗口，圆角交给系统窗口边框；背景与 Dialog 保持一致。
    LogViewContent {
        id: content
        anchors.fill: parent
        showDetachButton: false
        onCloseRequested: win.close()
    }

    /** 打开窗口并拉一次最新日志。 */
    function showViewer() {
        content.start()
        win.show()
        win.raise()
        win.requestActivate()
    }
}
