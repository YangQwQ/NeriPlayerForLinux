import QtQuick

// 设置页的字体/背景偏好代理。真正的数据源是全局 Prefs（从后端 Settings 读取，
// 跨重启持久）。这里把属性绑定代理到 Prefs，不保存本地副本：
// 用户改动经 SettingsPage 的 frontendKeyWrite → Settings.set 写入后端 →
// Settings 事件 → Prefs.reload → 全局（Theme/歌词/主窗口背景）与此处显示一起刷新。
// 注意：不能给这些绑定属性本地赋值，否则会断开与 Prefs 的绑定。
QtObject {
    id: prefs

    readonly property var fontOptions: [
        { value: "", label: I18n.tr("系统默认") },
        { value: "Noto Sans CJK SC", label: "Noto Sans CJK SC" },
        { value: "Source Han Sans SC", label: "Source Han Sans SC" },
        { value: "Sarasa Gothic SC", label: "Sarasa Gothic SC" },
        { value: "WenQuanYi Micro Hei", label: "WenQuanYi Micro Hei" }
    ]

    readonly property string appFontFamily: Prefs.appFontFamily
    readonly property string lyricFontFamily: Prefs.lyricFontFamily
    readonly property string appBackgroundImage: Prefs.backgroundImage
    readonly property real appBackgroundBlur: Prefs.backgroundBlur
    readonly property real appBackgroundDim: Prefs.backgroundDim

    function fontLabel(value) {
        for (let i = 0; i < fontOptions.length; i++) {
            if (fontOptions[i].value === value)
                return fontOptions[i].label
        }
        return value || I18n.tr("系统默认")
    }

    // 以下函数保留以兼容 SettingsPage 的调用：实际写入由调用方的 frontendKeyWrite 完成，
    // 这里不修改属性（保持与 Prefs 的绑定）。
    function setAppFont(value) {}
    function setLyricFont(value) {}
    function setBackground(image, blur, dim) {}
    function clearBackground() {}
}
