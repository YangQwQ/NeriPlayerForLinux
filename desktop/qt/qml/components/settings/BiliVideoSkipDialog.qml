import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../"
import "../library"

// B站视频「跳过区间」编辑器（自包含）：选分P → 标若干段不想听的区间 → 保存。
//
// 用法（入口由集成负责人接线）：
//   biliSkipDialog.openFor(Player.song.remoteId, Player.song.extraId, Player.title)
//
// 设计要点：
// - 只做规则管理，不负责播放时的自动跳过（那条 hook 在 Player.kt / 前端播放链路里）；
//   但本对话框需要用 Player.positionMs / Player.seekTo 帮用户卡点，所以会控制播放。
// - 区间归一化（排序/合并/裁剪）以后端为准，这里只在添加前做「起止合法」的即时校验，
//   并顺手合并一次，让列表显示与最终落盘结果一致。
// - 空区间列表保存 = 写删除墓碑（后端语义），不是「什么都不做」。
//
// 协议见 desktop/docs/protocol-bili-video-skip.md。
Dialog {
    id: root

    // ---------------------------------------------------------------- 对外接口

    /** 当前编辑的 B站视频 BVID。 */
    property string bvid: ""
    /** 标题（一般传当前播放歌曲名，只用于展示）。 */
    property string trackTitle: ""
    /** 入口希望默认选中的 cid（当前播放的实际分P），空串表示用第一个分P。 */
    property string initialCid: ""

    // ---------------------------------------------------------------- 状态

    /** `BiliVideoSkipTarget[]`：{bvid, cid, page, label, durationMs}。 */
    property var targets: []
    /** 选中的目标键 `bvid|cid`。 */
    property string selectedKey: ""

    /** 正在编辑的区间草稿（已按后端规则排序合并）。 */
    property var draftIntervals: []
    /** 该目标是否已有保存过的规则（决定空列表保存是否有意义）。 */
    property bool hasSavedRule: false

    property string startText: ""
    property string endText: ""
    property string inputError: ""

    property bool loading: false
    property bool saving: false
    property string loadError: ""

    /** 分P下拉是否展开。 */
    property bool partMenuOpen: false
    /** 二次确认：清空全部区间。 */
    property bool clearConfirmOpen: false

    readonly property var selectedTarget: {
        for (let i = 0; i < root.targets.length; ++i) {
            if (root.targetKey(root.targets[i]) === root.selectedKey)
                return root.targets[i]
        }
        return null
    }

    readonly property real selectedDurationMs: root.selectedTarget && root.selectedTarget.durationMs
                                              ? Number(root.selectedTarget.durationMs) : 0

    /** 选中的分P 是否就是当前正在播放的分P（决定是否显示取时间/微调按钮）。 */
    readonly property bool matchesPlayback: {
        const song = Player.song
        if (!song)
            return false
        const playingBvid = song.remoteId ? String(song.remoteId) : ""
        const target = root.selectedTarget
        if (!target || playingBvid === "" || playingBvid !== root.bvid)
            return false
        const playingCid = song.extraId !== undefined && song.extraId !== null ? String(song.extraId) : ""
        // 当前曲目没带 cid（搜索结果来源）时无法判定分P，交给用户自己按序号选
        if (playingCid === "")
            return target.page === 1
        return playingCid === String(target.cid)
    }

    readonly property bool canControlPlayback: Player.playing || Player.state === "PAUSED"

    /** 正文最大高度：窗口高度减去标题栏、分隔线与底部操作区，避免按钮被挤出屏幕。 */
    readonly property real bodyMaxHeight: {
        const available = root.parent && root.parent.height ? root.parent.height - 260 : 460
        return Math.max(240, Math.min(560, available))
    }

    signal saved(string bvid, string cid)

    // ---------------------------------------------------------------- 对外函数

    function openFor(bvid, cid, title) {
        root.bvid = bvid !== undefined && bvid !== null ? String(bvid) : ""
        root.initialCid = cid !== undefined && cid !== null ? String(cid) : ""
        root.trackTitle = title !== undefined && title !== null ? String(title) : ""
        root.targets = []
        root.selectedKey = ""
        root.draftIntervals = []
        root.hasSavedRule = false
        root.startText = ""
        root.endText = ""
        root.inputError = ""
        root.loadError = ""
        root.partMenuOpen = false
        root.clearConfirmOpen = false
        root.open()
        root.reload()
    }

    /** 拉分P列表 + 已有规则，按 initialCid → 第一个分P 的顺序选中。 */
    function reload() {
        if (root.bvid === "")
            return
        root.loading = true
        root.loadError = ""
        Api.call("biliVideoSkip.getTargets", { bvid: root.bvid }).then(function(result) {
            const list = result && result.targets ? result.targets : []
            if (list.length === 0) {
                root.loading = false
                root.loadError = I18n.tr("没有取到这个视频的分P列表，请检查网络或视频地址。")
                return
            }
            root.targets = list
            let preferred = ""
            for (let i = 0; i < list.length; ++i) {
                if (root.initialCid !== "" && String(list[i].cid) === root.initialCid) {
                    preferred = root.targetKey(list[i])
                    break
                }
            }
            root.selectTarget(preferred !== "" ? preferred : root.targetKey(list[0]))
            root.loading = false
        }).catch(function(error) {
            root.loading = false
            root.loadError = error && error.message ? error.message : I18n.tr("读取分P失败")
        })
    }

    function selectTarget(key) {
        root.selectedKey = key
        root.partMenuOpen = false
        root.inputError = ""
        root.startText = ""
        root.endText = ""
        root.loadDraftFor(key)
    }

    /** 读取该目标已保存的区间作为草稿。 */
    function loadDraftFor(key) {
        root.draftIntervals = []
        root.hasSavedRule = false
        const target = root.selectedTarget
        if (!target)
            return
        Api.call("biliVideoSkip.getRules").then(function(result) {
            // 快速切分P 时旧请求可能后到，key 变了就丢弃结果
            if (root.selectedKey !== key)
                return
            const rules = result && result.rules ? result.rules : []
            for (let i = 0; i < rules.length; ++i) {
                const rule = rules[i]
                if (String(rule.bvid) !== String(target.bvid) || String(rule.cid) !== String(target.cid))
                    continue
                root.hasSavedRule = rule.isDeleted !== true
                root.draftIntervals = rule.intervals || []
                break
            }
        }).catch(function() {
            // 读不到已有规则不影响新建；保存时以后端整条覆盖为准
        })
    }

    // ---------------------------------------------------------------- 区间编辑

    function targetKey(target) {
        return target ? String(target.bvid) + "|" + String(target.cid) : ""
    }

    /** `mm:ss` / `h:mm:ss` / 纯秒数 → 毫秒；非法返回 -1。 */
    function parseTime(text) {
        const raw = String(text === undefined || text === null ? "" : text).trim()
        if (raw === "")
            return -1
        const parts = raw.split(":")
        if (parts.length < 1 || parts.length > 3)
            return -1
        const numbers = []
        for (let i = 0; i < parts.length; ++i) {
            const piece = parts[i].trim()
            if (!/^\d+$/.test(piece))
                return -1
            numbers.push(Number(piece))
        }
        if (numbers.length > 1 && numbers[numbers.length - 1] > 59)
            return -1
        if (numbers.length === 3 && numbers[1] > 59)
            return -1
        let total = 0
        for (let i = 0; i < numbers.length; ++i)
            total = total * 60 + numbers[i]
        return total * 1000
    }

    /** 毫秒 → `mm:ss` / `h:mm:ss`。 */
    function formatTime(ms) {
        const total = Math.floor(Math.max(0, Number(ms) || 0) / 1000)
        const hours = Math.floor(total / 3600)
        const minutes = Math.floor((total % 3600) / 60)
        const seconds = total % 60
        function pad(value) { return value < 10 ? "0" + value : String(value) }
        return hours > 0 ? pad(hours) + ":" + pad(minutes) + ":" + pad(seconds)
                         : pad(minutes) + ":" + pad(seconds)
    }

    /** 前端侧的排序合并（与后端 normalize 同语义，只用于即时反馈）。 */
    function normalizeIntervals(list) {
        const maxEnd = root.selectedDurationMs > 0 ? root.selectedDurationMs : 86400000
        const copy = []
        for (let i = 0; i < list.length; ++i) {
            const item = list[i]
            const startMs = Math.max(0, Math.round(Number(item.startMs) || 0))
            const endMs = Math.min(maxEnd, Math.round(Number(item.endMs) || 0))
            if (endMs > startMs)
                copy.push({ startMs: startMs, endMs: endMs })
        }
        copy.sort(function(a, b) { return a.startMs - b.startMs || a.endMs - b.endMs })
        const merged = []
        for (let i = 0; i < copy.length; ++i) {
            const previous = merged.length > 0 ? merged[merged.length - 1] : null
            if (previous && copy[i].startMs <= previous.endMs)
                previous.endMs = Math.max(previous.endMs, copy[i].endMs)
            else
                merged.push(copy[i])
        }
        return merged
    }

    function addInterval() {
        const target = root.selectedTarget
        if (!target || root.saving)
            return
        const startMs = root.parseTime(root.startText)
        const endMs = root.parseTime(root.endText)
        if (startMs < 0 || endMs < 0) {
            root.inputError = I18n.tr("时间格式不对，请用 mm:ss 或 h:mm:ss。")
            return
        }
        if (endMs <= startMs) {
            root.inputError = I18n.tr("结束时间必须晚于开始时间。")
            return
        }
        if (root.selectedDurationMs > 0 && endMs > root.selectedDurationMs) {
            root.inputError = I18n.tr("结束时间超出了该分P的时长。")
            return
        }
        root.draftIntervals = root.normalizeIntervals(root.draftIntervals.concat([{ startMs: startMs, endMs: endMs }]))
        root.inputError = ""
        root.startText = ""
        root.endText = ""
    }

    function removeInterval(index) {
        const next = []
        for (let i = 0; i < root.draftIntervals.length; ++i) {
            if (i !== index)
                next.push(root.draftIntervals[i])
        }
        root.draftIntervals = next
    }

    function clearIntervals() {
        root.draftIntervals = []
        root.clearConfirmOpen = false
    }

    /** 把当前播放位置填进起（或止）时间输入框。 */
    function fillFromPlayback(field) {
        if (!root.matchesPlayback)
            return
        const text = root.formatTime(Player.positionMs)
        if (field === "start")
            root.startText = text
        else
            root.endText = text
        root.inputError = ""
    }

    /** 播放位置微调：seek 走 Player.seekTo（后端统一入口，会挑缓存文件或 URL）。 */
    function nudgePlayback(deltaMs) {
        if (!root.canControlPlayback)
            return
        const durationMs = Player.durationMs > 0 ? Player.durationMs : root.selectedDurationMs
        let next = Math.max(0, Player.positionMs + deltaMs)
        if (durationMs > 0)
            next = Math.min(durationMs, next)
        Player.seekTo(next)
    }

    // ---------------------------------------------------------------- 保存

    function save() {
        const target = root.selectedTarget
        if (!target || root.saving || root.loading)
            return
        // 本来就没有规则、草稿也是空：保存没有意义，直接关掉而不是报错
        if (root.draftIntervals.length === 0 && !root.hasSavedRule) {
            root.close()
            return
        }
        root.saving = true
        root.inputError = ""
        Api.call("biliVideoSkip.setRule", {
            bvid: String(target.bvid),
            cid: Number(target.cid),
            durationMs: root.selectedDurationMs,
            intervals: root.draftIntervals
        }).then(function(result) {
            root.saving = false
            root.hasSavedRule = !!(result && result.rule && result.rule.isDeleted !== true)
            if (root.draftIntervals.length === 0)
                Toast.show(I18n.tr("已清除该分P的跳过区间"))
            else
                Toast.show(I18n.tr("已保存跳过区间"))
            root.saved(String(target.bvid), String(target.cid))
            root.close()
        }).catch(function(error) {
            root.saving = false
            root.inputError = (error && error.message) ? error.message : I18n.tr("保存失败")
        })
    }

    // ---------------------------------------------------------------- 外观

    parent: Overlay.overlay
    anchors.centerIn: parent
    modal: true
    width: Math.min(560, parent ? parent.width - 48 : 560)
    padding: 0
    closePolicy: Popup.CloseOnEscape
    // 打开期间用户要一边播一边卡点：输入框可聚焦，Esc 关闭不影响播放快捷键
    focus: true

    onClosed: {
        root.partMenuOpen = false
        root.clearConfirmOpen = false
    }

    background: Rectangle {
        radius: Theme.radiusXxl
        color: Theme.surfaceContainerHigh
    }

    contentItem: ColumnLayout {
        id: content

        spacing: 0

        // ---- 标题栏 ----
        Item {
            Layout.fillWidth: true
            Layout.preferredHeight: headerRow.implicitHeight + Theme.spacingXl

            RowLayout {
                id: headerRow

                anchors.left: parent.left
                anchors.right: parent.right
                anchors.verticalCenter: parent.verticalCenter
                anchors.leftMargin: Theme.spacingXl
                anchors.rightMargin: Theme.spacingXl
                spacing: Theme.spacingMd

                NeriIcon {
                    name: "skip_next"
                    iconSize: 22
                    color: Theme.primary
                }

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 2

                    Text {
                        Layout.fillWidth: true
                        text: I18n.tr("视频跳过区间")
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("titleMedium").size
                        font.weight: Font.DemiBold
                    }

                    Text {
                        Layout.fillWidth: true
                        visible: root.trackTitle !== ""
                        text: root.trackTitle
                        color: Theme.onSurfaceVariantColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("bodySmall").size
                        elide: Text.ElideRight
                    }
                }

                SettingsButton {
                    text: I18n.tr("取消")
                    onClicked: root.close()
                }
            }
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 1
            color: Theme.withAlpha(Theme.outlineVariant, 0.6)
        }

        // ---- 正文（可滚动） ----
        Flickable {
            id: bodyFlick

            Layout.fillWidth: true
            // 正文按内容长高，但给标题栏与底部按钮留出空间：
            // 小窗口下靠这条上限保证「取消 / 保存」始终可见，超出部分在正文内滚动。
            Layout.preferredHeight: Math.min(root.bodyMaxHeight, bodyColumn.implicitHeight + Theme.spacingXl)
            clip: true
            contentWidth: width
            contentHeight: bodyColumn.implicitHeight + Theme.spacingXl
            boundsBehavior: Flickable.StopAtBounds

            ColumnLayout {
                id: bodyColumn

                width: bodyFlick.width - Theme.spacingXl * 2
                x: Theme.spacingXl
                y: Theme.spacingMd
                spacing: Theme.spacingSm

                // ---- 加载 / 错误 ----
                Item {
                    Layout.fillWidth: true
                    Layout.preferredHeight: root.loading || root.loadError !== "" ? 120 : 0
                    visible: Layout.preferredHeight > 0

                    ColumnLayout {
                        anchors.centerIn: parent
                        spacing: Theme.spacingSm

                        NeriIcon {
                            Layout.alignment: Qt.AlignHCenter
                            name: root.loadError !== "" ? "error" : "skip_next"
                            iconSize: 28
                            color: root.loadError !== "" ? Theme.error : Theme.onSurfaceVariantColor
                        }

                        Text {
                            Layout.fillWidth: true
                            horizontalAlignment: Text.AlignHCenter
                            text: root.loadError !== "" ? root.loadError : I18n.tr("正在读取分P列表…")
                            color: Theme.onSurfaceVariantColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("bodySmall").size
                            wrapMode: Text.Wrap
                        }

                        SettingsButton {
                            Layout.alignment: Qt.AlignHCenter
                            visible: root.loadError !== ""
                            text: I18n.tr("重试")
                            icon: "refresh"
                            onClicked: root.reload()
                        }
                    }
                }

                // ---- 分P 选择 ----
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: Theme.spacingXs
                    visible: root.targets.length > 0

                    SettingsLabel {
                        Layout.fillWidth: true
                        text: I18n.tr("分P")
                    }

                    Rectangle {
                        id: partButton

                        Layout.fillWidth: true
                        implicitHeight: 44
                        radius: Theme.radiusSm
                        color: "transparent"
                        border.width: 1
                        border.color: partHover.hovered || root.partMenuOpen ? Theme.primary : Theme.outlineVariant

                        HoverHandler {
                            id: partHover
                            cursorShape: Qt.PointingHandCursor
                        }

                        MouseArea {
                            anchors.fill: parent
                            onClicked: root.partMenuOpen = !root.partMenuOpen
                        }

                        RowLayout {
                            anchors.fill: parent
                            anchors.leftMargin: Theme.spacingMd
                            anchors.rightMargin: Theme.spacingMd
                            spacing: Theme.spacingSm

                            Text {
                                Layout.fillWidth: true
                                text: {
                                    const target = root.selectedTarget
                                    if (!target)
                                        return I18n.tr("请选择分P")
                                    const suffix = Number(target.durationMs) > 0
                                                 ? " · " + root.formatTime(target.durationMs) : ""
                                    return String(target.label) + suffix
                                }
                                color: Theme.onSurfaceColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("bodyMedium").size
                                elide: Text.ElideRight
                            }

                            NeriIcon {
                                name: root.partMenuOpen ? "expand_less" : "expand_more"
                                iconSize: 20
                                color: Theme.onSurfaceVariantColor
                            }
                        }
                    }

                    // 展开的分P 列表：用内联列表而不是嵌套 Popup，避免浮层互相抢焦点。
                    // 长视频可能有几百个分P，所以单独限高滚动，不能把整个对话框撑长。
                    Flickable {
                        id: partFlick

                        Layout.fillWidth: true
                        Layout.preferredHeight: Math.min(root.targets.length * 42, 210)
                        visible: root.partMenuOpen
                        clip: true
                        contentWidth: width
                        contentHeight: partList.implicitHeight
                        boundsBehavior: Flickable.StopAtBounds

                        Column {
                            id: partList

                            width: partFlick.width
                            spacing: 2

                            Repeater {
                                model: root.targets

                                delegate: Rectangle {
                                    id: partRow

                                    required property var modelData

                                    readonly property bool selected: root.targetKey(modelData) === root.selectedKey

                                    width: partList.width
                                    height: 40
                                    radius: Theme.radiusSm
                                    color: partRow.selected
                                           ? Theme.secondaryContainer
                                           : (partRowHover.hovered ? Theme.withAlpha(Theme.onSurfaceColor, 0.06) : "transparent")

                                    HoverHandler {
                                        id: partRowHover
                                        cursorShape: Qt.PointingHandCursor
                                    }

                                    MouseArea {
                                        anchors.fill: parent
                                        onClicked: root.selectTarget(root.targetKey(partRow.modelData))
                                    }

                                    Text {
                                        anchors.left: parent.left
                                        anchors.leftMargin: Theme.spacingMd
                                        anchors.right: partDuration.left
                                        anchors.rightMargin: Theme.spacingSm
                                        anchors.verticalCenter: parent.verticalCenter
                                        text: partRow.modelData.label !== "" ? String(partRow.modelData.label)
                                                                             : ("P" + Number(partRow.modelData.page))
                                        color: partRow.selected ? Theme.onSecondaryContainerColor : Theme.onSurfaceColor
                                        font.family: Theme.fontFamily
                                        font.pixelSize: Theme.textStyle("bodyMedium").size
                                        elide: Text.ElideRight
                                    }

                                    Text {
                                        id: partDuration

                                        anchors.right: parent.right
                                        anchors.rightMargin: Theme.spacingMd
                                        anchors.verticalCenter: parent.verticalCenter
                                        text: Number(partRow.modelData.durationMs) > 0
                                              ? root.formatTime(partRow.modelData.durationMs) : ""
                                        color: Theme.onSurfaceVariantColor
                                        font.family: Theme.fontFamily
                                        font.pixelSize: Theme.textStyle("labelSmall").size
                                    }
                                }
                            }
                        }
                    }
                }

                Item {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 4
                }

                // ---- 时间输入 ----
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: Theme.spacingXs
                    visible: root.selectedTarget !== null

                    SettingsLabel {
                        Layout.fillWidth: true
                        text: I18n.tr("添加区间")
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: Theme.spacingSm

                        TextField {
                            id: startField

                            Layout.fillWidth: true
                            implicitHeight: 44
                            placeholderText: I18n.tr("开始 00:10")
                            text: root.startText
                            color: Theme.onSurfaceColor
                            placeholderTextColor: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.72)
                            selectionColor: Theme.primary
                            selectedTextColor: Theme.onPrimaryColor
                            font.family: Theme.fontFamily
                            font.pixelSize: 14
                            leftPadding: 12
                            rightPadding: 12
                            onTextChanged: root.startText = text
                            onAccepted: root.addInterval()

                            background: Rectangle {
                                radius: Theme.radiusSm
                                color: "transparent"
                                border.width: startField.activeFocus ? 2 : 1
                                border.color: startField.activeFocus ? Theme.primary : Theme.outlineVariant
                            }
                        }

                        Text {
                            text: "–"
                            color: Theme.onSurfaceVariantColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("bodyMedium").size
                        }

                        TextField {
                            id: endField

                            Layout.fillWidth: true
                            implicitHeight: 44
                            placeholderText: I18n.tr("结束 00:25")
                            text: root.endText
                            color: Theme.onSurfaceColor
                            placeholderTextColor: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.72)
                            selectionColor: Theme.primary
                            selectedTextColor: Theme.onPrimaryColor
                            font.family: Theme.fontFamily
                            font.pixelSize: 14
                            leftPadding: 12
                            rightPadding: 12
                            onTextChanged: root.endText = text
                            onAccepted: root.addInterval()

                            background: Rectangle {
                                radius: Theme.radiusSm
                                color: "transparent"
                                border.width: endField.activeFocus ? 2 : 1
                                border.color: endField.activeFocus ? Theme.primary : Theme.outlineVariant
                            }
                        }
                    }

                    // 卡点辅助：只有选中的就是正在播放的分P 时才有意义
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: Theme.spacingXs
                        visible: root.matchesPlayback

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: Theme.spacingXs

                            SettingsButton {
                                text: I18n.tr("起点取当前")
                                onClicked: root.fillFromPlayback("start")
                            }

                            Text {
                                Layout.fillWidth: true
                                horizontalAlignment: Text.AlignHCenter
                                text: root.formatTime(Player.positionMs)
                                color: Theme.onSurfaceVariantColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("bodySmall").size
                            }

                            SettingsButton {
                                text: I18n.tr("终点取当前")
                                onClicked: root.fillFromPlayback("end")
                            }
                        }

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: Theme.spacingXs

                            SettingsButton {
                                text: "-5s"
                                enabled: root.canControlPlayback
                                onClicked: root.nudgePlayback(-5000)
                            }

                            SettingsButton {
                                text: "-1s"
                                enabled: root.canControlPlayback
                                onClicked: root.nudgePlayback(-1000)
                            }

                            SettingsButton {
                                icon: Player.playing ? "pause" : "play_arrow"
                                enabled: root.canControlPlayback
                                onClicked: Player.togglePlayPause()
                            }

                            SettingsButton {
                                text: "+1s"
                                enabled: root.canControlPlayback
                                onClicked: root.nudgePlayback(1000)
                            }

                            SettingsButton {
                                text: "+5s"
                                enabled: root.canControlPlayback
                                onClicked: root.nudgePlayback(5000)
                            }

                            Item {
                                Layout.fillWidth: true
                                implicitHeight: 1
                            }
                        }
                    }

                    SettingsNote {
                        Layout.fillWidth: true
                        visible: !root.matchesPlayback
                        text: I18n.tr("当前播放的不是这个分P，取值与微调按钮不可用；仍可手动输入时间。")
                    }

                    SettingsNote {
                        Layout.fillWidth: true
                        text: I18n.tr("播放到这个分P时，进度走到区间起点会自动快进到终点。")
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: Theme.spacingSm

                        Item {
                            Layout.fillWidth: true
                            implicitHeight: 1
                        }

                        SettingsButton {
                            text: I18n.tr("添加")
                            icon: "add"
                            filled: true
                            enabled: !root.saving
                            onClicked: root.addInterval()
                        }
                    }

                    Text {
                        Layout.fillWidth: true
                        visible: root.inputError !== ""
                        text: root.inputError
                        color: Theme.error
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("labelSmall").size
                        wrapMode: Text.Wrap
                    }
                }

                // ---- 区间列表 ----
                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: Theme.spacingXs
                    visible: root.selectedTarget !== null

                    SettingsLabel {
                        Layout.fillWidth: true
                        text: I18n.tr("已添加的区间")
                    }

                    SettingsNote {
                        Layout.fillWidth: true
                        visible: root.draftIntervals.length === 0
                        text: I18n.tr("还没有区间。用上面的输入框或「起点取当前 / 终点取当前」来标一段。")
                    }

                    Repeater {
                        model: root.draftIntervals

                        delegate: Rectangle {
                            id: intervalRow

                            required property var modelData
                            required property int index

                            Layout.fillWidth: true
                            implicitHeight: 44
                            radius: Theme.radiusSm
                            color: Theme.surfaceContainerLow

                            Text {
                                anchors.left: parent.left
                                anchors.leftMargin: Theme.spacingMd
                                anchors.verticalCenter: parent.verticalCenter
                                text: root.formatTime(intervalRow.modelData.startMs)
                                      + " – " + root.formatTime(intervalRow.modelData.endMs)
                                color: Theme.onSurfaceColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("bodyMedium").size
                            }

                            SettingsButton {
                                anchors.right: parent.right
                                anchors.rightMargin: Theme.spacingXs
                                anchors.verticalCenter: parent.verticalCenter
                                icon: "delete"
                                onClicked: root.removeInterval(intervalRow.index)
                            }
                        }
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: Theme.spacingSm

                        Item {
                            Layout.fillWidth: true
                            implicitHeight: 1
                        }

                        SettingsButton {
                            text: I18n.tr("清空区间")
                            icon: "delete_sweep"
                            enabled: root.draftIntervals.length > 0 && !root.saving
                            onClicked: root.clearConfirmOpen = true
                        }
                    }
                }
            }
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 1
            color: Theme.withAlpha(Theme.outlineVariant, 0.6)
        }

        // ---- 底部操作 ----
        RowLayout {
            Layout.fillWidth: true
            Layout.margins: Theme.spacingXl
            spacing: Theme.spacingSm

            SettingsNote {
                Layout.fillWidth: true
                text: root.draftIntervals.length === 0 && root.hasSavedRule
                      ? I18n.tr("保存空列表会删除该分P的跳过规则。")
                      : I18n.tr("规则按「视频 + 分P」保存在本机。")
            }

            SettingsButton {
                text: I18n.tr("保存")
                icon: "save"
                filled: true
                enabled: !root.saving && !root.loading && root.selectedTarget !== null
                onClicked: root.save()
            }
        }
    }

    // 清空区间要二次确认：清空后保存等于删规则，误点代价高于一次确认
    ConfirmDialog {
        id: clearConfirm

        parent: Overlay.overlay
        titleText: I18n.tr("清空区间")
        message: I18n.tr("确定要清空这个分P的所有跳过区间吗？清空后保存会删除该分P的规则。")
        confirmText: I18n.tr("清空")
        onConfirmed: root.clearIntervals()

        onOpened: root.clearConfirmOpen = false
    }

    // 外部只切换 clearConfirmOpen，具体弹窗交给 ConfirmDialog
    onClearConfirmOpenChanged: {
        if (clearConfirmOpen)
            clearConfirm.open()
    }
}
