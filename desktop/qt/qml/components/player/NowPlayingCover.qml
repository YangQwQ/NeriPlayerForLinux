import QtQuick
import QtQuick.Controls
import QtQuick.Dialogs
import QtQuick.Effects
import ".."
import "../motion"

// 播放页大封面：形状可配（CIRCLE / ROUNDED / SQUARE）+ 强调色光晕 + 柔和投影 + 缓冲遮罩。
// 光晕颜色取自当前封面种子（Player.coverSeed），让封面与模糊背景属于同一色相。
Item {
    id: root

    property real coverSize: 280
    /** 强调色光晕。紧凑布局下封面贴着顶栏，外扩的光晕会被裁掉一截，可关闭。 */
    property bool showGlow: true

    signal activity()

    readonly property string shape: {
        const s = Settings.get("nowPlayingCoverShape", "ROUNDED")
        return (s === "CIRCLE" || s === "SQUARE") ? s : "ROUNDED"
    }
    readonly property int shapeRadius: {
        if (shape === "CIRCLE")
            return Math.round(coverSize / 2)
        if (shape === "SQUARE")
            return Theme.radiusXs
        return Math.round(coverSize * 0.082)
    }

    /** 圆形封面才允许旋转（方形/圆角旋转会露出角上的空白）。 */
    readonly property bool rotationEnabled: shape === "CIRCLE"
                                            && Settings.get("nowPlayingCoverRotate", false) === true

    implicitWidth: coverSize
    implicitHeight: coverSize

    NowPlayingTheme { id: np }
    MotionPrefs { id: motion }

    // ---- 强调色光晕 ----
    Item {
        anchors.fill: parent
        anchors.margins: -coverSize * 0.12
        z: -2
        opacity: 0.62
        visible: root.showGlow

        Rectangle {
            id: glowRect

            anchors.fill: parent
            radius: width / 2
            color: Theme.withAlpha(np.accent, 0.45)
            visible: false

            Behavior on color {
                ColorAnimation {
                    duration: motion.themeTransition ? motion.themeDuration : 0
                    easing.type: Easing.Bezier
                    easing.bezierCurve: [0.4, 0.0, 0.2, 1.0]
                }
            }
        }

        MultiEffect {
            anchors.fill: glowRect
            source: glowRect
            blurEnabled: true
            blur: 1.0
            blurMax: 64
        }
    }

    // ---- 柔和投影：矩形 + 模糊，比硬阴影更接近参考图里的浮起感 ----
    Item {
        anchors.fill: parent
        z: -1

        Rectangle {
            id: shadowRect

            anchors.fill: parent
            anchors.topMargin: coverSize * 0.06
            radius: root.shapeRadius
            color: Theme.withAlpha(np.scrim, 0.45)
            visible: false
        }

        MultiEffect {
            anchors.fill: shadowRect
            source: shadowRect
            blurEnabled: true
            blur: 1.0
            blurMax: 36
        }
    }

    // ---- 封面（旋转只作用在这一层，光晕/阴影/缓冲遮罩保持静止） ----
    Item {
        id: coverSpin

        anchors.fill: parent
        rotation: 0

        CoverArt {
            id: cover

            anchors.fill: parent
            radius: root.shapeRadius
            circle: root.shape === "CIRCLE"
            placeholderIconSize: Math.max(28, root.coverSize * 0.22)
            path: Player.song && Player.song.artworkPath ? Player.song.artworkPath : ""
            url: Player.song && Player.song.artworkUrl ? Player.song.artworkUrl : ""
        }
    }

    // 切歌微动效：封面轻微回弹（0.96 → 1）并淡入，让换歌有「落位」感。
    // 用 rotationEnabled 之外的独立 scale 属性，不影响圆形封面的旋转逻辑。
    SequentialAnimation {
        id: coverPop

        NumberAnimation {
            target: coverSpin
            property: "scale"
            from: 0.96
            to: 1.0
            duration: Theme.motionMedium
            easing.type: Easing.OutBack
        }
    }

    // 20 秒一圈的线性旋转；暂停时停在当前角度，切歌回到 0 度。
    NumberAnimation {
        id: spinAnimation

        target: coverSpin
        property: "rotation"
        duration: 20000
        loops: Animation.Infinite
        easing.type: Easing.Linear
    }

    function restartSpin() {
        spinAnimation.stop()
        coverSpin.rotation = 0
        updateSpin()
    }

    function updateSpin() {
        if (!rotationEnabled) {
            spinAnimation.stop()
            coverSpin.rotation = 0
            return
        }
        if (!Player.playing || spinAnimation.running)
            return
        // 从当前角度接着转：暂停/恢复时不会跳回 0 度
        spinAnimation.from = coverSpin.rotation
        spinAnimation.to = coverSpin.rotation + 360
        spinAnimation.start()
    }

    property string _lastSongKey: ""

    function handlePlayerSong() {
        const key = Player.song && Player.song.key ? String(Player.song.key) : ""
        if (key === _lastSongKey)
            return
        _lastSongKey = key
        restartSpin()
        // 首帧封面加载和切歌都做一次回弹；动效关闭时直接保持原状。
        if (motion.pageTransition && key !== "")
            coverPop.restart()
    }

    Connections {
        target: Player

        function onSongChanged() {
            root.handlePlayerSong()
        }

        function onPlayingChanged() {
            root.updateSpin()
        }
    }

    onRotationEnabledChanged: restartSpin()
    Component.onCompleted: restartSpin()

    // ---- 缓冲/准备中的半透明遮罩 ----
    Rectangle {
        id: bufferingScrim

        anchors.fill: parent
        radius: root.shapeRadius
        visible: Player.buffering || Player.state === "PREPARING"
        color: Theme.withAlpha(np.scrim, 0.4)

        NeriIcon {
            anchors.centerIn: parent
            name: "progress_activity"
            iconSize: Math.max(28, root.coverSize * 0.16)
            color: np.foreground

            RotationAnimation on rotation {
                from: 0
                to: 360
                duration: 900
                loops: Animation.Infinite
                running: bufferingScrim.visible
            }
        }
    }

    HoverHandler {
        id: coverHover
        cursorShape: Qt.ArrowCursor
    }

    // 封面盖住了页面底层的鼠标跟踪层，这里自己上报「真实移动」，
    // 否则控制条隐藏后鼠标在封面上移动无法唤回。
    MouseArea {
        anchors.fill: parent
        acceptedButtons: Qt.NoButton
        hoverEnabled: true

        property real lastX: -9999
        property real lastY: -9999

        onPositionChanged: function(mouse) {
            if (Math.abs(mouse.x - lastX) + Math.abs(mouse.y - lastY) < 2)
                return
            lastX = mouse.x
            lastY = mouse.y
            root.activity()
        }
    }

    Connections {
        target: coverHover
        function onHoveredChanged() {
            if (coverHover.hovered)
                root.activity()
        }
    }

    // ---------------------------------------------------------------- 封面右键菜单

    /** 歌曲的远程封面地址 / 本地封面文件（两者可能只有其一）。 */
    readonly property string artworkUrl: Player.song && Player.song.artworkUrl
                                         ? String(Player.song.artworkUrl) : ""
    readonly property string artworkPath: Player.song && Player.song.artworkPath
                                          ? String(Player.song.artworkPath) : ""
    readonly property bool canCopyArtworkLink: artworkUrl !== ""
    readonly property bool canSaveArtwork: artworkUrl !== "" || artworkPath !== ""

    /** 优先用封面本身的扩展名，双击保存时用户不用手改后缀。 */
    function coverFileExtension() {
        const source = artworkPath !== "" ? artworkPath : artworkUrl
        const matched = String(source).toLowerCase().match(/\.(jpe?g|png|webp|gif|bmp|avif)(?:$|[?#])/)
        if (!matched)
            return "jpg"
        return matched[1] === "jpeg" ? "jpg" : matched[1]
    }

    function urlToLocalPath(value) {
        const text = String(value)
        return decodeURIComponent(text.replace(/^file:\/\//, ""))
    }

    function copyToClipboard(value) {
        if (value === "")
            return
        clipHelper.text = value
        clipHelper.selectAll()
        clipHelper.copy()
    }

    function saveArtwork(targetPath) {
        if (!canSaveArtwork || targetPath === "")
            return
        // url / path 同时传：后端优先本地文件，没有再按 url 解析下载，前端无需关心缓存状态
        Api.call("artwork.saveAs", {
                     "url": artworkUrl,
                     "path": artworkPath,
                     "target": targetPath
                 }).then(function(result) {
            if (result && result.ok) {
                Toast.show(I18n.tr("封面已保存：") + result.path, "info")
            } else {
                Toast.show(result && result.error ? result.error : I18n.tr("封面保存失败"), "error")
            }
        }).catch(function(error) {
            Toast.show(error && error.message ? error.message : I18n.tr("封面保存失败"), "error")
        })
    }

    TextEdit {
        id: clipHelper

        visible: false
        width: 1
        height: 1
    }

    TapHandler {
        acceptedButtons: Qt.RightButton
        onSingleTapped: function(eventPoint) {
            root.activity()
            artworkMenu.popup(root, eventPoint.position.x, eventPoint.position.y)
        }
    }

    Menu {
        id: artworkMenu

        width: 224
        padding: Theme.spacingXs

        background: Rectangle {
            radius: Theme.radiusMd
            color: Theme.surfaceContainerHigh
            border.width: 1
            border.color: Theme.withAlpha(Theme.outlineVariant, 0.6)
        }

        component ContextItem: MenuItem {
            id: contextItem

            property string iconName: "content_copy"

            implicitHeight: 42
            padding: Theme.spacingSm

            contentItem: Row {
                spacing: Theme.spacingSm

                NeriIcon {
                    anchors.verticalCenter: parent.verticalCenter
                    name: contextItem.iconName
                    iconSize: 18
                    color: contextItem.enabled ? Theme.onSurfaceVariantColor
                                               : Theme.withAlpha(Theme.onSurfaceVariantColor, 0.38)
                }

                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    text: contextItem.text
                    color: contextItem.enabled ? Theme.onSurfaceColor
                                               : Theme.withAlpha(Theme.onSurfaceColor, 0.38)
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodyMedium").size
                }
            }

            background: Rectangle {
                radius: Theme.radiusSm
                color: contextItem.highlighted ? Theme.withAlpha(Theme.onSurfaceColor, 0.08)
                                               : "transparent"
            }
        }

        ContextItem {
            text: I18n.tr("复制封面链接")
            iconName: "link"
            enabled: root.canCopyArtworkLink
            onTriggered: {
                root.copyToClipboard(root.artworkUrl)
                Toast.show(I18n.tr("已复制封面链接"), "info")
            }
        }

        ContextItem {
            text: I18n.tr("保存封面…")
            iconName: "save_alt"
            enabled: root.canSaveArtwork
            onTriggered: saveDialog.open()
        }
    }

    FileDialog {
        id: saveDialog

        title: I18n.tr("保存封面")
        fileMode: FileDialog.SaveFile
        defaultSuffix: root.coverFileExtension()
        nameFilters: [I18n.tr("图片文件 (*.jpg *.jpeg *.png *.webp *.gif)"), I18n.tr("所有文件 (*)")]
        onAccepted: root.saveArtwork(root.urlToLocalPath(selectedFile))
    }
}
