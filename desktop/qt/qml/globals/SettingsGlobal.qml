import QtQuick

// 应用设置。后端通过 "settings" 事件下发完整设置对象；修改用 Settings.set(key, value)。
QtObject {
    id: settings

    property var values: ({})

    function get(key, fallback) {
        const value = settings.values[key]
        return value === undefined ? fallback : value
    }

    /** 设置任意一个字段，例如 Settings.set("dynamicColor", true)。 */
    function set(key, value) {
        const patch = {}
        patch[key] = value
        Api.get("settings.set", { patch: patch }, function(result, error) {
            if (error) {
                console.warn("[settings] set", key, "failed:", error.message)
                Toast.show(error.message, "error")
                return null
            }
            if (result && result.settings)
                settings.values = result.settings
            return result
        })
    }

    /** 一次设置多个字段。 */
    function patch(changes) {
        Api.get("settings.set", { patch: changes }, function(result, error) {
            if (error) {
                console.warn("[settings] patch failed:", error.message)
                Toast.show(error.message, "error")
                return null
            }
            if (result && result.settings)
                settings.values = result.settings
            return result
        })
    }

    function reload() {
        Api.get("settings.get", {}, function(result, error) {
            if (!error && result && result.settings)
                settings.values = result.settings
        })
    }

    // ---- 常用设置项的便捷读取 ----
    readonly property bool dynamicColor: values.dynamicColor === true
    readonly property bool darkModeAuto: values.darkMode === "AUTO" || values.darkMode === undefined
    readonly property string darkMode: values.darkMode || "AUTO"
    readonly property string themeSeedColor: values.themeSeedColor || "0061A4"
    readonly property string paletteStyle: values.paletteStyle || "TonalSpot"
    readonly property string colorSpec: values.colorSpec || "SPEC_2021"
    readonly property bool onboarded: values.onboardingAccepted === true
    readonly property bool showLyricTranslation: values.showLyricTranslation !== false
    readonly property bool showLyricPhonetic: values.showLyricPhonetic === true
    readonly property bool showLyricOriginal: values.showLyricOriginal !== false

    // ---- 动效 ----
    readonly property bool motionEnabled: values.motionEnabled !== false
    readonly property bool motionPageTransition: values.motionPageTransition !== false
    readonly property bool motionPanelTransition: values.motionPanelTransition !== false
    readonly property bool motionThemeTransition: values.motionThemeTransition !== false
    readonly property bool motionLyricScroll: values.motionLyricScroll !== false
    readonly property string progressColorMode: values.progressColorMode || "COVER"

    // ---- 歌词显示 ----
    readonly property string lyricSource: values.lyricSource || "AUTO"
    readonly property string lyricLineOrder: values.lyricLineOrder || "ORIGINAL_TRANSLATION_PHONETIC"
    readonly property real lyricPhoneticFontSizeScale: {
        const v = values.lyricPhoneticFontSizeScale
        return (typeof v === "number" && v > 0) ? v : 0.58
    }
    readonly property real lyricTranslationFontSizeScale: {
        const v = values.lyricTranslationFontSizeScale
        return (typeof v === "number" && v > 0) ? v : 0.58
    }
    readonly property bool lyricBlurEnabled: values.lyricBlurEnabled !== false
    readonly property real lyricBlurAmount: {
        const v = values.lyricBlurAmount
        return (typeof v === "number" && v >= 0) ? v : 1.5
    }

    property Connections backendConnection: Connections {
        target: Backend
        function onEvent(name, data) {
            if (name === "settings")
                settings.values = data
        }
    }
}
