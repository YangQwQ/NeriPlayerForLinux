import QtQuick
import QtQuick.Layouts
import ".."

// 播放页底部控制条（Refined Now Playing 风格）。
//
// 收起态：贴底细进度条 + 时间 + 红心收藏 + 上一首/播放/下一首/随机/循环。
// hover 态：进度条上移到控制条顶部、时间文字移到进度条两端上方；
//           控制条整体长高（播放按钮上下留白相等），二级按钮在播放行左右展开：
//           左：睡眠/下载/评论/音效，右：音量/全屏歌词/播放队列。
// 随机与循环属于核心播放控制，收起态也常驻中间行；左右二级组与音效保持 hover 展开。
// 评论已由右上角竖列移入本控制条左组（见 NowPlayingPage 的 actionsColumn）。
Item {
    id: root

    signal activity()
    signal lyricsRequested()

    /** 页面控制：鼠标活动时显示，静止一段时间后淡出。 */
    property bool controlsVisible: true
    property bool compact: false

    readonly property real sideMargin: compact ? Theme.spacingLg : 28
    /** 播放页背景恒为暗色，浅色主题下的 error 是深红，需要提亮才能看清。 */
    readonly property color favoriteColor: Theme.mix(Theme.error, np.foreground, 0.35)
    /** 鼠标是否停在控制条上（页面据此露出二级按钮）。 */
    readonly property bool hovered: barHover.hovered
    readonly property bool expanded: hovered && controlsVisible

    /** 0 = 收起，1 = hover 展开；控制条高度、进度条位置、时间与二级按钮都由它驱动。 */
    property real expandAmount: expanded ? 1 : 0

    Behavior on expandAmount {
        NumberAnimation {
            duration: Theme.motionMedium
            easing.type: Easing.OutCubic
        }
    }

    // ---- 两态尺寸 ----
    readonly property real rowHeight: compact ? 58 : 64
    readonly property real collapsedHeight: rowHeight + 20
    // 控制条长高到「播放按钮上下留白相等」的高度（见 expanded 布局注释）
    readonly property real expandedHeight: compact ? 106 : 128
    readonly property real expandedRowY: compact ? 44 : 54
    readonly property real expandedProgressY: compact ? 20 : 24
    readonly property real expandedTimeY: compact ? 6 : 8

    /**
     * 页面按「展开后」的高度固定预留底部空间：
     * hover 只在控制条内部展开，歌曲信息/歌词区不会再被顶着上移。
     */
    readonly property real reservedHeight: expandedHeight

    height: collapsedHeight + expandAmount * (expandedHeight - collapsedHeight)
    opacity: controlsVisible ? 1 : 0
    enabled: controlsVisible

    Behavior on opacity {
        NumberAnimation { duration: Theme.motionMedium; easing.type: Easing.OutCubic }
    }

    NowPlayingTheme { id: np }

    function formatTime(ms) {
        const total = Math.max(0, Math.round((ms || 0) / 1000))
        const hours = Math.floor(total / 3600)
        const minutes = Math.floor((total % 3600) / 60)
        const seconds = total % 60
        const ss = seconds < 10 ? "0" + seconds : "" + seconds
        if (hours > 0) {
            const mm = minutes < 10 ? "0" + minutes : "" + minutes
            return hours + ":" + mm + ":" + ss
        }
        return minutes + ":" + ss
    }

    // ---- 底部压暗，按钮压在歌词上时仍可读 ----
    Rectangle {
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.bottom: parent.bottom
        // 高度按预留区算而不是自身高度：展开动画不会再让压暗层跟着长高
        height: root.reservedHeight + 40
        z: -1
        gradient: Gradient {
            GradientStop { position: 0.0; color: Theme.withAlpha(np.scrim, 0.0) }
            GradientStop { position: 0.55; color: Theme.withAlpha(np.scrim, 0.28) }
            GradientStop { position: 1.0; color: Theme.withAlpha(np.scrim, 0.5) }
        }
    }

    // ---- 控制条 hover：展开二级按钮 ----
    HoverHandler {
        id: barHover
    }

    Connections {
        target: barHover
        function onHoveredChanged() {
            if (barHover.hovered)
                root.activity()
        }
    }

    // ---------------------------------------------------------------- 进度条（收起贴底 / hover 上移到顶部）

    PlayerProgressSlider {
        id: progress

        anchors.left: parent.left
        anchors.right: parent.right
        forceExpanded: root.expandAmount > 0.5
        // 收起时贴底；hover 时上移到时间文字下方（控制条顶部）
        y: (root.collapsedHeight - progress.implicitHeight)
           + root.expandAmount * (root.expandedProgressY - (root.collapsedHeight - progress.implicitHeight))
    }

    // ---------------------------------------------------------------- hover 时进度两端上方的当前/总时长

    Item {
        id: progressTimeRow

        anchors.left: parent.left
        anchors.right: parent.right
        y: root.expandedTimeY
        height: Math.max(0, root.expandedProgressY - root.expandedTimeY)
        opacity: root.expandAmount
        visible: opacity > 0.01

        Text {
            anchors.left: parent.left
            anchors.leftMargin: root.sideMargin
            anchors.top: parent.top
            text: root.formatTime(Player.smoothPositionMs)
            color: np.foregroundDim
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("labelMedium").size
            font.weight: Theme.textStyle("labelMedium").weight
        }

        Text {
            anchors.right: parent.right
            anchors.rightMargin: root.sideMargin
            anchors.top: parent.top
            text: root.formatTime(Player.durationMs)
            color: np.foregroundDim
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("labelMedium").size
            font.weight: Theme.textStyle("labelMedium").weight
        }
    }

    // ---------------------------------------------------------------- 主控制行

    Item {
        id: mainRow

        anchors.left: parent.left
        anchors.right: parent.right
        y: root.expandAmount * root.expandedRowY
        height: root.rowHeight

        // 左：红心收藏（两态都在，位置固定，用户指定为左组第一项）
        IconButton {
            id: favoriteButton

            anchors.left: parent.left
            anchors.leftMargin: root.sideMargin
            anchors.verticalCenter: parent.verticalCenter
            icon: Player.favorite ? "favorite" : "favorite_border"
            iconFilled: Player.favorite
            iconSize: 25
            size: 44
            iconColor: Player.favorite ? root.favoriteColor : np.foregroundDim
            hoverColor: np.hoverFill
            tooltip: Player.favorite ? I18n.tr("取消收藏") : I18n.tr("加入我喜欢的音乐")
            onClicked: Player.toggleFavorite()
        }

        // 收起态：时间固定在右下角（hover 后由进度条两端的时间替代）
        Text {
            anchors.right: parent.right
            anchors.rightMargin: root.sideMargin
            anchors.verticalCenter: parent.verticalCenter
            opacity: Math.max(0, 1 - root.expandAmount * 2)
            visible: opacity > 0.01 && !(root.compact && volumePopup.open)
            text: root.formatTime(Player.smoothPositionMs) + " / " + root.formatTime(Player.durationMs)
            color: np.foregroundFaint
            font.family: Theme.fontFamily
            font.pixelSize: Theme.textStyle("labelMedium").size
            font.weight: Theme.textStyle("labelMedium").weight
        }

        // 左二级组：睡眠 / 下载 / 评论 / 音效（hover 展开，紧跟常驻的收藏之后）
        Row {
            anchors.left: parent.left
            anchors.leftMargin: root.sideMargin + 44 + Theme.spacingXs
            anchors.verticalCenter: parent.verticalCenter
            spacing: 2
            opacity: root.expandAmount
            visible: opacity > 0.01

            IconButton {
                icon: "bedtime"
                iconSize: 20
                size: 38
                iconColor: Player.sleepTimer && Player.sleepTimer.active ? np.accent : np.foregroundDim
                hoverColor: np.hoverFill
                tooltip: I18n.tr("睡眠定时器")
                onClicked: Nav.openSleepPanel()
            }

            IconButton {
                icon: "download"
                iconSize: 20
                size: 38
                iconColor: np.foregroundDim
                hoverColor: np.hoverFill
                tooltip: I18n.tr("下载")
                onClicked: Nav.openDownloadsPanel()
            }

            IconButton {
                icon: "comment"
                iconSize: 20
                size: 38
                iconColor: np.foregroundDim
                hoverColor: np.hoverFill
                tooltip: I18n.tr("评论")
                onClicked: {
                    if (typeof Nav.openCommentsPanel === "function")
                        Nav.openCommentsPanel()
                    else
                        Nav.openPanel("comments")
                }
            }

            IconButton {
                icon: "graphic_eq"
                iconSize: 20
                size: 38
                iconColor: Player.equalizerEnabled ? np.accent : np.foregroundDim
                hoverColor: np.hoverFill
                tooltip: I18n.tr("音效与倍速")
                onClicked: Nav.openEffectsPanel()
            }
        }

        // 右二级组：音量 / 歌单 / 全屏歌词 / 播放列表（hover 展开）
        Row {
            anchors.right: parent.right
            anchors.rightMargin: root.sideMargin
            anchors.verticalCenter: parent.verticalCenter
            spacing: 2
            opacity: root.expandAmount
            visible: opacity > 0.01

            // 音量：宽屏滑条常驻（按钮位置固定，hover 不再挤占右组宽度）；
            // 紧凑布局点击图标打开上方弹出面板。宽屏常驻宽度固定，按钮不会因 hover 位移。
            Item {
                id: volumeControl

                property bool revealed: !root.compact
                width: !root.compact ? 38 + Theme.spacingSm + 100 : 38
                height: 38

                // 紧凑布局：点击图标弹出上方小面板；离开按钮/面板后延时收起。
                // 面板放在 volumeControl 内、y 为负，锚定在按钮正上方。
                VolumePopup {
                    id: volumePopup
                    anchors.bottom: volumeControl.top
                    anchors.horizontalCenter: volumeControl.horizontalCenter
                    // 面板高出控制条，需盖住上方的进度条/时间文字；控制条根节点未 clip。
                    z: 30

                    onDismissRequested: {
                        if (!volumeIconHover.hovered && !volumeButton.containsMouse)
                            volumePopup.open = false
                    }
                }

                HoverHandler {
                    id: volumeIconHover
                    onHoveredChanged: {
                        if (!root.compact) {
                            // 宽屏切换过来时收起弹出面板，避免残留
                            volumePopup.open = false
                        } else {
                            if (hovered) {
                                volumePopup.cancelDismiss()
                                root.activity()
                            } else if (volumePopup.open) {
                                volumePopup.scheduleDismiss()
                            }
                        }
                    }
                }

                Row {
                    anchors.verticalCenter: parent.verticalCenter
                    spacing: Theme.spacingSm

                    IconButton {
                        id: volumeButton
                        icon: Player.muted ? "volume_off" : (Player.volume < 0.5 ? "volume_down" : "volume_up")
                        iconSize: 20
                        size: 38
                        iconColor: np.foregroundDim
                        hoverColor: np.hoverFill
                        tooltip: Player.muted ? I18n.tr("取消静音") : I18n.tr("静音")
                        onClicked: {
                            if (root.compact) {
                                volumePopup.open = !volumePopup.open
                                root.activity()
                            } else {
                                Player.toggleMute()
                            }
                        }
                    }

                    PlayerSlider {
                        id: volumeInline
                        visible: !root.compact
                        width: 100
                        height: 38
                        from: 0
                        to: 1
                        value: Player.volume
                        accentColor: np.accent
                        foregroundColor: np.foreground
                        onMoved: Player.setVolume(value)
                    }
                }

                // 点击外部收起：透明大 MouseArea 垫在按钮/面板下方，覆盖整页；
                // 仅在面板打开时启用。hoverEnabled 默认为 false，不影响上方按钮的 HoverHandler。
                MouseArea {
                    anchors.centerIn: parent
                    width: 6000
                    height: 6000
                    z: -1
                    enabled: volumePopup.open
                    onClicked: volumePopup.open = false
                }

            }

            IconButton {
                icon: "lyrics"
                iconSize: 20
                size: 38
                iconColor: np.foregroundDim
                hoverColor: np.hoverFill
                tooltip: I18n.tr("全屏歌词")
                onClicked: root.lyricsRequested()
            }

            // 播放列表：当前播放队列面板（queue_music）。
            IconButton {
                icon: "queue_music"
                iconSize: 20
                size: 38
                iconColor: np.foregroundDim
                hoverColor: np.hoverFill
                tooltip: I18n.tr("播放队列")
                onClicked: Nav.openQueuePanel()
            }
        }

        // 中：随机 / 上一首 / 播放暂停 / 下一首 / 循环（两态常驻，核心播放控制）
        RowLayout {
            anchors.centerIn: parent
            spacing: compact ? 10 : 14

            IconButton {
                Layout.alignment: Qt.AlignVCenter
                icon: "shuffle"
                iconFilled: Player.shuffle
                iconSize: 20
                size: 38
                iconColor: Player.shuffle ? np.accent : np.foregroundDim
                hoverColor: np.hoverFill
                tooltip: Player.shuffle ? I18n.tr("随机播放：开") : I18n.tr("随机播放：关")
                onClicked: Player.toggleShuffle()
            }

            IconButton {
                Layout.alignment: Qt.AlignVCenter
                icon: "skip_previous"
                iconFilled: true
                iconSize: 30
                size: 48
                iconColor: np.foreground
                hoverColor: np.hoverFill
                tooltip: I18n.tr("上一首")
                onClicked: Player.previous()
            }

            Item {
                id: playButton

                Layout.alignment: Qt.AlignVCenter
                Layout.preferredWidth: 60
                Layout.preferredHeight: 60
                width: 60
                height: 60

                Rectangle {
                    anchors.fill: parent
                    radius: width / 2
                    color: playHover.hovered ? np.hoverFill : "transparent"

                    Behavior on color {
                        ColorAnimation { duration: Theme.motionShort }
                    }
                }

                NeriIcon {
                    anchors.centerIn: parent
                    name: Player.playing ? "pause" : "play_arrow"
                    filled: true
                    iconSize: 34
                    color: np.foreground
                }

                HoverHandler {
                    id: playHover
                    cursorShape: Qt.PointingHandCursor
                }

                MouseArea {
                    anchors.fill: parent
                    onClicked: Player.togglePlayPause()
                }
            }

            IconButton {
                Layout.alignment: Qt.AlignVCenter
                icon: "skip_next"
                iconFilled: true
                iconSize: 30
                size: 48
                iconColor: np.foreground
                hoverColor: np.hoverFill
                tooltip: I18n.tr("下一首")
                onClicked: Player.next()
            }

            IconButton {
                Layout.alignment: Qt.AlignVCenter
                icon: Player.repeatMode === "ONE" ? "repeat_one" : "repeat"
                iconFilled: Player.repeatMode !== "OFF"
                iconSize: 20
                size: 38
                iconColor: Player.repeatMode === "OFF" ? np.foregroundDim : np.accent
                hoverColor: np.hoverFill
                tooltip: Player.repeatMode === "ONE" ? I18n.tr("单曲循环")
                       : (Player.repeatMode === "ALL" ? I18n.tr("列表循环") : I18n.tr("不循环"))
                onClicked: Player.cycleRepeatMode()
            }
        }
    }
}
