import QtQuick

// 播放页专用派生色板。
//
// 播放页的背景固定是「封面模糊 + 暗色叠加」，因此前景必须是浅色：
// 浅色主题下 Theme.onSurfaceColor 是深色，直接铺在暗背景上会看不清。
//
// accent 默认取封面主色（seed）与前景 mix —— 切歌时进度条/红心跟着变是**有意设计**。
// 将来的设置开关 progressColorMode 可覆盖：
//   "COVER"（默认）= 现有行为；"THEME" = 用 Theme.primary 与前景 mix，切歌不再变色。
// 这里只读取偏好，默认保持现有行为；开关本身由 SettingsGlobal.qml 负责。
QtObject {
    id: np

    readonly property color foreground: Theme.onScrimColor
    readonly property color foregroundDim: Theme.withAlpha(foreground, 0.76)
    readonly property color foregroundFaint: Theme.withAlpha(foreground, 0.52)
    readonly property color foregroundGhost: Theme.withAlpha(foreground, 0.26)

    /** 封面主色种子（后端 coverSeed 是 6 位十六进制，不带 #）。 */
    readonly property color seed: {
        const hex = Player.coverSeed === undefined || Player.coverSeed === null
                    ? "" : String(Player.coverSeed).replace(/^#/, "")
        if (/^[0-9A-Fa-f]{6}$/.test(hex))
            return Qt.color("#" + hex)
        return Theme.primary
    }

    /** 进度条等强调色的取色模式："COVER"（默认，跟随封面）/ "THEME"（跟随主题主色）。 */
    readonly property string accentSeedMode: {
        const mode = Settings.get("progressColorMode", "COVER")
        return (mode === "THEME" || mode === "COVER") ? mode : "COVER"
    }

    /** 实际参与 mix 的种子：COVER 用封面主色，THEME 用全局主色。 */
    readonly property color accentSource: accentSeedMode === "THEME" ? Theme.primary : seed

    /**
     * 强调色：种子色在暗背景上向浅色方向提亮，
     * 否则深色封面的种子会与模糊背景混在一起，进度条/红心对比度不足。
     */
    readonly property color accent: Theme.mix(accentSource, foreground, 0.3)
    readonly property color accentDim: Theme.withAlpha(accent, 0.6)

    readonly property color scrim: Theme.scrim

    /** 控制条 hover 圆形底、歌词行 hover 底这类极淡的前景叠加。 */
    readonly property color hoverFill: Theme.withAlpha(foreground, 0.1)
    readonly property color pressedFill: Theme.withAlpha(foreground, 0.16)
}
