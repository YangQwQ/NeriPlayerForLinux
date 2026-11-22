import QtQuick

// 动效偏好统一读取点。
//
// 所有键都用 Settings.get(key, default) 读：后端/设置页尚未接线时自动回退到默认值，
// 组件不需要依赖 AppSettings 里是否已有对应字段。总开关 motionEnabled 关闭时，
// 所有子开关一并失效。
QtObject {
    id: prefs

    readonly property bool enabled: Settings.get("motionEnabled", true) !== false

    readonly property bool pageTransition: enabled && Settings.get("motionPageTransition", true) !== false
    readonly property bool panelTransition: enabled && Settings.get("motionPanelTransition", true) !== false
    readonly property bool themeTransition: enabled && Settings.get("motionThemeTransition", true) !== false
    /** 歌词滚动开关由歌词组件使用；这里仅统一暴露，避免出现第二套读取逻辑。 */
    readonly property bool lyricScroll: enabled && Settings.get("motionLyricScroll", true) !== false

    // 主题色补间时长：设计规格 §1.7 的 420ms（FastOutSlowIn）。
    readonly property int themeDuration: 420
}
