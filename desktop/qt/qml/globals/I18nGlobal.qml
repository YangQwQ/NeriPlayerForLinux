import QtQuick
import "../i18n/translations.js" as Tr

// 界面国际化。QML 里用 I18n.tr("中文原文") 包裹所有展示给用户的字符串。
// 当前语言来自后端设置 Settings.language（zh-CN / en）；切换后写回设置持久化，
// 并自增 version 让所有 tr() 绑定失效重新求值，实现界面即时刷新。
QtObject {
    id: i18n

    property string language: "zh-CN"
    /** 仅用于让 tr() 绑定随语言变化失效重算，不承载语义。 */
    property int version: 0

    function tr(text) {
        // 建立对 version 的依赖，语言切换时刷新所有绑定
        const v = i18n.version
        if (i18n.language !== "zh-CN" && text !== undefined && text !== null) {
            const table = Tr.translations[i18n.language]
            const key = String(text)
            if (table && table[key] !== undefined)
                return table[key]
        }
        return text
    }

    /** 切换界面语言并持久化到后端设置。 */
    function setLanguage(lang) {
        if (lang === i18n.language)
            return
        i18n.language = lang
        i18n.version++
        if (typeof Settings !== "undefined" && Settings && typeof Settings.set === "function")
            Settings.set("language", lang)
    }

    function initLanguage() {
        const lang = (typeof Settings !== "undefined" && Settings && Settings.get)
            ? Settings.get("language", "zh-CN") : "zh-CN"
        i18n.language = (lang === "en") ? "en" : "zh-CN"
    }

    property Connections backendConnection: Connections {
        target: Backend
        function onEvent(name, data) {
            if (name === "settings" && data && data.language !== undefined) {
                const lang = data.language === "en" ? "en" : "zh-CN"
                if (lang !== i18n.language) {
                    i18n.language = lang
                    i18n.version++
                }
            }
        }
    }

    Component.onCompleted: initLanguage()
}
