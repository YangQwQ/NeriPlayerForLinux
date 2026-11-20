import QtQuick
import QtQuick.Effects
import "../ArtworkCache.js" as ArtworkCache

// 播放页背景：当前封面放大 + 高斯模糊 + 暗色叠加。
// Settings.nowPlayingBackground 可选 BLUR（默认）/ GRADIENT / SOLID；
// 没有封面或没有歌曲时退化为带主题色的深色底，避免纯黑一片。
Item {
    id: root

    readonly property string mode: {
        const m = Settings.get("nowPlayingBackground", "BLUR")
        return (m === "GRADIENT" || m === "SOLID") ? m : "BLUR"
    }

    readonly property string coverPath: Player.coverPath
    readonly property string coverUrl: Player.song && Player.song.artworkUrl ? Player.song.artworkUrl : ""

    // 远程封面要先经后端缓存成本地文件（与 CoverArt 相同的解析逻辑）
    property string resolvedPath: ""

    readonly property bool hasCover: source !== ""

    readonly property string source: coverPath !== "" ? "file://" + coverPath
                                            : (resolvedPath !== "" ? "file://" + resolvedPath : "")

    readonly property color accent: np.accent

    clip: true

    NowPlayingTheme { id: np }

    function resolveRemote() {
        if (coverPath !== "" || coverUrl === "") {
            resolvedPath = ""
            return
        }
        if (ArtworkCache.resolved[coverUrl] !== undefined) {
            resolvedPath = ArtworkCache.resolved[coverUrl]
            return
        }
        if (ArtworkCache.pending[coverUrl] === true)
            return
        ArtworkCache.pending[coverUrl] = true
        const requested = coverUrl
        Api.get("artwork.resolve", { url: requested }, function(result, error) {
            delete ArtworkCache.pending[requested]
            if (!error && result && result.path) {
                ArtworkCache.resolved[requested] = result.path
                if (root.coverUrl === requested)
                    root.resolvedPath = result.path
            }
        })
    }

    onCoverUrlChanged: resolveRemote()
    onCoverPathChanged: resolveRemote()
    Component.onCompleted: resolveRemote()

    // ---- 底层：模糊封面 / 渐变 / 纯色 ----
    Item {
        anchors.fill: parent

        Rectangle {
            anchors.fill: parent
            color: root.hasCover ? Theme.surfaceContainerLowest
                                 : Theme.mix(Theme.surface, Theme.primary, 0.22)
        }

        // BLUR：双层封面交叉淡入。切歌时把新封面加载到非活跃层，加载完成后
        // 两层 opacity 互换，避免单 Image 复用纹理导致偶发不刷新，同时得到淡入淡出。
        Item {
            id: blurLayer

            anchors.fill: parent
            visible: root.mode === "BLUR" && root.hasCover

            // 当前显示的层（0 或 1），另一层作为下一首的缓冲。
            property int active: 0
            property string shownSource: ""

            function submitSource(src) {
                if (src === "")
                    return
                if (shownSource === "") {
                    // 首次加载：直接放进活跃层，无需淡入
                    if (active === 0) img0.source = src; else img1.source = src
                    shownSource = src
                    return
                }
                if (src === shownSource)
                    return
                // 加载到非活跃层，由它的 onStatusChanged 触发交叉淡入
                if (active === 0) img1.source = src; else img0.source = src
            }

            onVisibleChanged: {
                if (visible && shownSource === "")
                    submitSource(root.source)
            }

            Component.onCompleted: submitSource(root.source)
            Connections {
                target: root
                function onSourceChanged() { blurLayer.submitSource(root.source) }
            }

            property real crossDuration: Theme.motionLong

            // 层 0
            BlurCoverImage {
                id: img0
                anchors.fill: parent
                objectName: "0"
                layerIndex: 0
                host: blurLayer
                opacity: blurLayer.active === 0 ? 1 : 0
                Behavior on opacity { NumberAnimation { duration: blurLayer.crossDuration; easing.type: Easing.InOutQuad } }
            }
            // 层 1
            BlurCoverImage {
                id: img1
                anchors.fill: parent
                objectName: "1"
                layerIndex: 1
                host: blurLayer
                opacity: blurLayer.active === 1 ? 1 : 0
                Behavior on opacity { NumberAnimation { duration: blurLayer.crossDuration; easing.type: Easing.InOutQuad } }
            }

            // 当非活跃层的图片就绪，切换 active 并更新 shownSource。
            function onLayerReady(index) {
                if (index === active)
                    return
                active = index
                shownSource = index === 0 ? img0.source : img1.source
            }
        }

        // GRADIENT：不用封面，改用强调色做深色渐变（背景图缺失时也能成立）
        Rectangle {
            id: gradientLayer

            anchors.fill: parent
            visible: root.mode === "GRADIENT" || !root.hasCover
            gradient: Gradient {
                GradientStop { position: 0.0; color: Theme.mix(np.accent, np.scrim, 0.5) }
                GradientStop { position: 0.3; color: Theme.mix(np.accent, np.scrim, 0.72) }
                GradientStop { position: 0.55; color: Theme.mix(Theme.primary, np.scrim, 0.62) }
                GradientStop { position: 0.8; color: Theme.mix(np.accent, np.scrim, 0.68) }
                GradientStop { position: 1.0; color: Theme.mix(np.accent, np.scrim, 0.82) }
            }
        }

        // 渐变模式下加一团柔光，避免整块颜色太平
        Item {
            anchors.fill: parent
            visible: root.mode === "GRADIENT" || !root.hasCover
            opacity: 0.55

            Rectangle {
                id: gradientGlow

                width: parent.width * 0.55
                height: width
                x: -width * 0.18
                y: -height * 0.32
                radius: width / 2
                color: Theme.withAlpha(np.accent, 0.55)
                visible: false
            }

            MultiEffect {
                anchors.fill: gradientGlow
                source: gradientGlow
                blurEnabled: true
                blur: 1.0
                blurMax: 64
            }
        }

        // SOLID：主题底色轻染强调色
        Rectangle {
            anchors.fill: parent
            visible: root.mode === "SOLID"
            color: Theme.mix(Theme.surface, np.accent, 0.16)
        }

        // ---- 暗色叠加：保证前景（浅色文字）有足够对比度 ----
        Rectangle {
            anchors.fill: parent
            color: Theme.withAlpha(np.scrim, root.mode === "GRADIENT" ? 0.3 : 0.56)
        }

        // 强调色调和：让背景与封面/进度条属于同一个色相家族
        Rectangle {
            anchors.fill: parent
            visible: root.hasCover && root.mode === "BLUR"
            color: Theme.withAlpha(np.accent, 0.1)
        }
    }

    // ---- 上下压暗：顶栏与底部控制条压住歌词时仍可读 ----
    Rectangle {
        anchors.fill: parent
        gradient: Gradient {
            GradientStop { position: 0.0; color: Theme.withAlpha(np.scrim, 0.42) }
            GradientStop { position: 0.22; color: Theme.withAlpha(np.scrim, 0.0) }
            GradientStop { position: 0.68; color: Theme.withAlpha(np.scrim, 0.0) }
            GradientStop { position: 1.0; color: Theme.withAlpha(np.scrim, 0.55) }
        }
    }
}
