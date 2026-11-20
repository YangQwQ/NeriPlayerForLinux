import QtQuick

// 全局外观偏好：界面字体、歌词字体、程序背景。
// 值存后端 AppSettings（Settings.values），由设置页通过 Settings.set 写入；
// Theme、歌词组件、主窗口都读这里，实现一处设置、全局生效、跨重启持久化。
QtObject {
    id: prefs

    readonly property string defaultFont: "Noto Sans CJK SC"

    property string appFontFamily: ""
    property string lyricFontFamily: ""
    property string backgroundImage: ""
    property int backgroundBlur: 0
    property real backgroundDim: 0

    /** 实际用于整个界面的字体族。 */
    readonly property string effectiveAppFont: appFontFamily !== "" ? appFontFamily : defaultFont
    /** 歌词字体：未单独设置时跟随界面字体。 */
    readonly property string effectiveLyricFont: lyricFontFamily !== "" ? lyricFontFamily : effectiveAppFont

    function reload() {
        if (typeof Settings === "undefined" || !Settings)
            return
        appFontFamily = Settings.get("appFontFamily", "")
        lyricFontFamily = Settings.get("lyricFontFamily", "")
        backgroundImage = Settings.get("appBackgroundImage", "")
        backgroundBlur = Settings.get("appBackgroundBlur", 0)
        backgroundDim = Settings.get("appBackgroundDim", 0)
    }

    Component.onCompleted: reload()

    property Connections backendConnection: Connections {
        target: Backend
        function onEvent(name, data) {
            if (name === "settings")
                prefs.reload()
        }
    }
}
