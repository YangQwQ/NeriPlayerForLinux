import QtQuick
import QtQuick.Controls
import "../"

// DSP 音效面板：对齐上游 Android 的「分区标签页」结构。
//
// 一个 Tab ＝ 一个分区。顶部是总开关卡片，中间是标签栏，下面是当前分区的
// 「分区开关 + 恢复默认 + 参数」。切 Tab 只改 visible，所有内容只创建一次，
// 因此切换不会丢状态（拖动中的滑块、选中的参数段、AutoEQ 文本都保留）。
//
// **这个面板取代了原来的「均衡器」卡片**：以前设置页里的 EqualizerPanel（预设 + 10 段滑块 +
// 响度增强）已经合并到这里的「均衡器」分区，播放页的 EffectsPanel 也改为操作同一套 DSP 参数，
// 两条链路不再各存一份十段增益。旧的 equalizerBands 由后端在首次启动时迁移。
//
// 「倍速」分区不写 DSP 参数：速度/音调是播放器状态（player.setSpeed / player.setPitch），
// 分区开关与联动开关存进音效配置（speedEnabled / speedPitchLinked / storedSpeed）。
// 上游是靠原生 Media3 的 speed 属性；桌面侧由 ffmpeg 的 atempo + 独立的音调滤镜实现。
//
// 数据来自 audioEffects.* 命令（见 desktop/docs/protocol-audio-effects.md）。
// 哪些参数真正生效由后端下发的 capabilities 决定，前端不自己维护清单；
// 未生效的控件标「未生效」并禁用，避免调了没反应。
//
// 提交时机：native DSP 时参数是热更新的，ffmpeg 回退路径要重启解码器。
// 所以绝不逐帧提交：SliderRow 只在松手时发 committed，这里再加一层 120ms 合并窗口 +
// 单请求串行（同值不重复发），把「连续拖多个滑块」压成一次提交。
Item {
    id: root

    width: parent ? parent.width : 0
    implicitHeight: contentColumn.implicitHeight

    // ---- 后端数据 ----
    // sound 是「当前编辑中的参数」：本地立即更新、提交后由后端快照校正，
    // 这样连续拖多个滑块时后一次不会基于过期的旧值。
    property var sound: null
    property var points: []
    property var capabilities: []
    property var graphicFrequencies: [31.25, 62.5, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]
    property int maxParametricBands: 10
    property bool realtimeApplied: false
    property bool unsupported: false
    property bool busy: false
    property string statusMessage: ""

    // ---- AutoEQ 导入 ----
    property var importResult: null

    // ---- 参数 EQ 编辑状态 ----
    property int selectedBand: 0

    // ---- 标签页 ----
    property string currentTab: "presets"

    readonly property var tabs: [
        { "id": "presets", "label": I18n.tr("预设"), "icon": "auto_awesome" },
        { "id": "equalizer", "label": I18n.tr("均衡器"), "icon": "graphic_eq" },
        { "id": "tone", "label": I18n.tr("音色"), "icon": "tune" },
        { "id": "space", "label": I18n.tr("空间"), "icon": "surround_sound" },
        { "id": "dynamics", "label": I18n.tr("动态"), "icon": "bar_chart" },
        { "id": "speaker", "label": I18n.tr("扬声器"), "icon": "speaker" },
        { "id": "speed", "label": I18n.tr("倍速"), "icon": "speed" },
        { "id": "advanced", "label": I18n.tr("高级"), "icon": "settings" }
    ]

    // ---- 提交节流状态 ----
    property var _pending: null
    property bool _inflight: false
    property int _revision: 0
    property int _sentRevision: 0

    readonly property bool ready: sound !== null
    readonly property bool masterEnabled: !!sound && sound.enabled !== false
    readonly property var graphicBands: {
        const value = sound && sound.graphicBandsDb ? sound.graphicBandsDb : []
        const result = []
        for (let i = 0; i < root.graphicFrequencies.length; i++)
            result.push(Number(value[i]) || 0)
        return result
    }
    readonly property var parametricBands: sound && sound.parametricBands ? sound.parametricBands : []
    readonly property int bandCount: parametricBands.length
    readonly property bool equalizerOn: !sound || sound.equalizerEnabled !== false
    readonly property bool toneOn: !sound || sound.toneEnabled !== false
    readonly property bool spaceOn: !sound || sound.spaceEnabled !== false
    readonly property bool dynamicsOn: !sound || sound.dynamicsEnabled !== false
    readonly property bool speakerOn: !!sound && !!sound.speaker && sound.speaker.enabled === true

    function num(key, fallback) {
        if (!sound)
            return fallback
        const value = Number(sound[key])
        return isFinite(value) ? value : fallback
    }

    function bool(key, fallback) {
        if (!sound)
            return fallback
        return sound[key] === undefined || sound[key] === null ? fallback : !!sound[key]
    }

    /** 字符串型字段（qualityMode 这类枚举值）。 */
    function text(key, fallback) {
        if (!sound || sound[key] === undefined || sound[key] === null)
            return fallback
        return String(sound[key])
    }

    /** 扬声器优化对象里的数值（嵌套一层，所以单独取）。 */
    function speakerNum(key, fallback) {
        if (!sound || !sound.speaker)
            return fallback
        const value = Number(sound.speaker[key])
        return isFinite(value) ? value : fallback
    }

    /** 扬声器优化对象里的字符串（尺寸档位）。 */
    function speakerText(key, fallback) {
        if (!sound || !sound.speaker || sound.speaker[key] === undefined)
            return fallback
        return String(sound.speaker[key])
    }

    /** 某个参数是否真的生效；后端没下发能力表时按「生效」处理（旧后端兼容）。 */
    function applied(key) {
        for (let i = 0; i < capabilities.length; i++) {
            if (capabilities[i].key === key)
                return capabilities[i].applied === true
        }
        return true
    }

    function unsupportedReason(key) {
        for (let i = 0; i < capabilities.length; i++) {
            if (capabilities[i].key === key)
                return capabilities[i].reason || ""
        }
        return ""
    }

    // 预设表：与原来的 EqualizerPanel.qml / EffectsPanel.qml 同一份数值，
    // 合并进 DSP 面板后预设仍可用；后端只按名保存，不内置预设曲线。
    readonly property var presetLabels: ["平直", "原声", "低音增强", "高频增强",
                                         "人声增强", "摇滚", "爵士", "古典"]
    readonly property var presetBands: ({
        "平直": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0],
        "原声": [3, 2, 1, 0.5, 0.5, 1.5, 2, 2.5, 2, 1],
        "低音增强": [6, 5, 4, 2, 0, -1, -2, -1, 0, 0],
        "高频增强": [0, 0, -1, -1, 0, 1, 3, 4, 5, 5],
        "人声增强": [-2, -1, 0, 2, 4, 4, 3, 1.5, 0, -1],
        "摇滚": [5, 4, 2, 0, -1, 0, 2, 4, 4, 3],
        "爵士": [3, 2, 1, 2, -1, -1, 0, 1, 2, 3],
        "古典": [4, 3, 2, 0, 0, 0, -1, 0, 2, 3]
    })
    readonly property string currentPreset: {
        const value = Settings.get("equalizerPreset", "平直")
        return typeof value === "string" ? value : "平直"
    }

    readonly property var bandTypeOptions: [
        { "value": "peak", "label": I18n.tr("峰值") },
        { "value": "low_shelf", "label": I18n.tr("低搁架") },
        { "value": "high_shelf", "label": I18n.tr("高搁架") },
        { "value": "low_pass", "label": I18n.tr("低通") },
        { "value": "high_pass", "label": I18n.tr("高通") },
        { "value": "notch", "label": I18n.tr("陷波") },
        { "value": "band_pass", "label": I18n.tr("带通") }
    ]

    readonly property var speakerSizeOptions: [
        { "value": "phone", "label": I18n.tr("手机") },
        { "value": "tablet", "label": I18n.tr("平板") },
        { "value": "large", "label": I18n.tr("大音箱") }
    ]

    readonly property var qualityModeOptions: [
        { "value": "eco", "label": I18n.tr("省电") },
        { "value": "balanced", "label": I18n.tr("均衡") },
        { "value": "high", "label": I18n.tr("高质量") }
    ]

    // 倍速快捷档位：与上游 AudioEffectsSpeedCard 的 SpeedQuickValues 一致。
    readonly property var speedQuickValues: [0.5, 0.75, 0.85, 0.95, 1.0, 1.05, 1.1, 1.25, 1.5, 2.0]
    readonly property var pitchQuickSemitones: [-3, -1, 0, 1, 3]

    readonly property double speedNow: Player.speed
    readonly property double pitchSemitoneNow: Player.pitch
    readonly property bool pitchLinked: bool("speedPitchLinked", true)
    readonly property bool speedOn: bool("speedEnabled", true)

    function bandTypeLabel(type) {
        for (let i = 0; i < bandTypeOptions.length; i++) {
            if (bandTypeOptions[i].value === type)
                return bandTypeOptions[i].label
        }
        return String(type)
    }

    function frequencyLabel(hz) {
        const value = Number(hz) || 0
        if (value >= 1000)
            return (value % 1000 === 0 ? (value / 1000).toFixed(0) : (value / 1000).toFixed(1)) + " kHz"
        return value.toFixed(value < 100 ? 1 : 0) + " Hz"
    }

    function formatGain(value) {
        const gain = Number(value) || 0
        return (gain >= 0 ? "+" : "") + gain.toFixed(1) + " dB"
    }

    function formatAmount(value) {
        return (Number(value) || 0).toFixed(2)
    }

    function formatPercent(value) {
        return Math.round((Number(value) || 0) * 100) + "%"
    }

    function formatMultiplier(value) {
        return (Number(value) || 1).toFixed(2) + "x"
    }

    function formatSemitones(value) {
        const semitone = Number(value) || 0
        return (semitone > 0 ? "+" : "") + semitone.toFixed(1) + I18n.tr(" 半音")
    }

    /**
     * 倍速 → 音调半音：联动模式用「唱片效果」，即 speed 的十二平均律换算。
     * 后端音调量程是 ±12 半音（= 0.5x~2.0x 的比例），所以超过 2x 时联动音调会饱和，
     * 这里先夹住，避免界面显示一个后端不会采用的 +19 半音。
     */
    function speedToSemitone(speed) {
        const value = Math.max(0.25, Number(speed) || 1)
        return Math.max(-12, Math.min(12, 12 * Math.log(value) / Math.log(2)))
    }

    // 频率与 Q 的听感是倍频程的，线性滑块会把 20~20000 Hz 几乎全压在左端；
    // SliderRow 只有线性刻度，这里用 0~1 的对数归一化后再换算回物理量。
    function logRatio(value, min, max) {
        const ratio = Math.log(Math.max(min, Number(value) || min) / min) / Math.log(max / min)
        return Math.max(0, Math.min(1, ratio))
    }

    function logValue(ratio, min, max) {
        const clamped = Math.max(0, Math.min(1, Number(ratio) || 0))
        // 取舍到整数 Hz / 两位小数，避免滑块拖动产生 1234.5678 这种值写进配置文件
        return Math.round(min * Math.pow(max / min, clamped) * 100) / 100
    }

    function selectedBandValue(key, fallback) {
        if (root.bandCount === 0 || root.selectedBand >= root.bandCount)
            return fallback
        const value = root.parametricBands[root.selectedBand][key]
        return value === undefined || value === null ? fallback : value
    }

    // ------------------------------------------------------------------ 数据加载

    function applySnapshot(result, acceptSound) {
        if (!result)
            return
        if (acceptSound === undefined)
            acceptSound = true
        unsupported = false
        // acceptSound=false 表示请求在途期间用户又改了参数：
        // 这时后端回的是上一版，采用它会把刚拖的滑块弹回去，只取曲线与能力表。
        if (result.sound && acceptSound)
            sound = result.sound
        points = result.points || []
        if (result.capabilities)
            capabilities = result.capabilities
        if (result.graphicFrequencies)
            graphicFrequencies = result.graphicFrequencies
        if (result.maxParametricBands)
            maxParametricBands = result.maxParametricBands
        realtimeApplied = result.realtimeApplied === true
        if (selectedBand >= parametricBands.length)
            selectedBand = Math.max(0, parametricBands.length - 1)
    }

    function handleError(error, prefix) {
        if (error && error.code === "unknown_method") {
            unsupported = true
            statusMessage = I18n.tr("后端暂不支持音效命令（audioEffects.*）")
        } else {
            const message = error && error.message ? error.message : I18n.tr("未知错误")
            statusMessage = prefix + "：" + message
            Toast.show(statusMessage, "error")
        }
    }

    function load() {
        // 后端未连接时先不请求，连接后由下方 Connections 重试
        if (!Api.connected)
            return
        Api.call("audioEffects.get").then(function(result) {
            applySnapshot(result)
            statusMessage = ""
        }).catch(function(error) {
            handleError(error, I18n.tr("读取音效参数失败"))
        })
    }

    // ------------------------------------------------------------------ 参数提交
    //
    // 所有控件都走 edit()：先把改动作用在本地 sound 上（界面立即响应），
    // 再交给 120ms 的合并窗口；真正请求时只发最新的一份，并且串行，
    // 保证「连续拖十个滑块」只有最后一次真正生效。

    function edit(mutator) {
        if (unsupported) {
            Toast.show(I18n.tr("后端暂不支持音效命令"), "error")
            return
        }
        if (!sound)
            return
        // 深拷贝后再改：直接改 sound 里的对象会让 QML 认为引用没变而不刷新
        const next = JSON.parse(JSON.stringify(sound))
        if (mutator(next) === false)
            return
        sound = next
        _revision += 1
        _pending = next
        commitTimer.restart()
    }

    function updateField(key, value) {
        edit(function(target) { target[key] = value })
    }

    /** 只改 speaker 对象里的一个字段（其余保持）——后端按字段合并，未提到的不会清空。 */
    function updateSpeaker(key, value) {
        edit(function(target) {
            const speaker = target.speaker ? JSON.parse(JSON.stringify(target.speaker)) : {}
            speaker[key] = value
            target.speaker = speaker
        })
    }

    function pushNow(next, successMessage) {
        _pending = null
        _inflight = true
        _sentRevision = _revision
        busy = true
        Api.call("audioEffects.updateSound", { "sound": next }).then(function(result) {
            _inflight = false
            busy = false
            // 请求在途期间用户又改过参数时，不要把这一版（旧的）写回本地，
            // 否则刚拖的滑块会被后端快照弹回去
            applySnapshot(result, _sentRevision === _revision)
            if (successMessage)
                statusMessage = successMessage
            // 期间又改过参数：立刻把最新的那份发出去
            if (_pending !== null && _sentRevision !== _revision)
                commitTimer.restart()
        }).catch(function(error) {
            _inflight = false
            busy = false
            handleError(error, I18n.tr("保存音效参数失败"))
        })
    }

    function flush() {
        // 上一次请求还没回来时把它留在 _pending，等回调里 restart 再发，
        // 这样「连续拖」不会堆成一串在途请求
        if (_pending === null || _inflight)
            return
        pushNow(_pending, "")
    }

    // 用户点「应用」/导入等需要立即生效的场景直接冲刷
    function commitNow() {
        commitTimer.stop()
        flush()
    }

    function resetSection(section) {
        if (unsupported)
            return
        // 先丢掉还没提交的本地改动，避免恢复默认后又被挂起的那份覆盖
        commitTimer.stop()
        _pending = null
        Api.call("audioEffects.setSection", { "section": section, "reset": true }).then(function(result) {
            applySnapshot(result)
            statusMessage = I18n.tr("已恢复该分区默认值")
        }).catch(function(error) {
            handleError(error, I18n.tr("恢复默认失败"))
        })
    }

    /**
     * 打开分区开关时顺带打开 DSP 总开关：否则用户打开了分区却听不到任何变化，
     * 会以为是开关坏了（与上游 `AudioEffectsProfile.withSectionEnabled` 一致）。
     */
    function toggleSection(field, enabled) {
        if (enabled && !root.masterEnabled)
            updateField("enabled", true)
        updateField(field, enabled)
    }

    /** 应用一组预设曲线：关掉参数均衡并按曲线写十段增益（预设只描述图形均衡）。 */
    function applyPreset(name) {
        const bands = presetBands[name]
        if (!bands)
            return
        edit(function(target) {
            target.graphicBandsDb = bands.slice()
            target.equalizerEnabled = true
            target.parametricEnabled = false
            target.parametricBands = []
        })
        Settings.set("equalizerPreset", name)
        statusMessage = I18n.tr("已应用预设：") + I18n.tr(name)
    }

    function updateGraphicBand(index, gain) {
        edit(function(target) {
            const bands = (target.graphicBandsDb || []).slice()
            for (let i = 0; i < root.graphicFrequencies.length; i++)
                bands[i] = Number(bands[i]) || 0
            bands[index] = Math.max(-12, Math.min(12, Number(gain) || 0))
            target.graphicBandsDb = bands
            target.equalizerEnabled = true
        })
        // 手调与内置预设立场不同：预设名只是界面标签，这里同步成「自定义」
        if (root.currentPreset !== "自定义")
            Settings.set("equalizerPreset", "自定义")
    }

    function updateParametricBand(index, changes) {
        edit(function(target) {
            const bands = (target.parametricBands || []).slice()
            if (index < 0 || index >= bands.length)
                return false
            const current = bands[index]
            bands[index] = {
                "enabled": changes.enabled !== undefined ? changes.enabled : current.enabled,
                "type": changes.type !== undefined ? changes.type : current.type,
                "frequencyHz": changes.frequencyHz !== undefined ? changes.frequencyHz : current.frequencyHz,
                "gainDb": changes.gainDb !== undefined ? changes.gainDb : current.gainDb,
                "q": changes.q !== undefined ? changes.q : current.q
            }
            target.parametricBands = bands
            target.parametricEnabled = true
        })
    }

    function addParametricBand() {
        if (parametricBands.length >= maxParametricBands)
            return
        edit(function(target) {
            const bands = (target.parametricBands || []).slice()
            bands.push({
                "enabled": true,
                "type": "peak",
                "frequencyHz": 1000,
                "gainDb": 0,
                "q": 1.0
            })
            target.parametricBands = bands
            target.parametricEnabled = true
        })
        selectedBand = Math.min(parametricBands.length, maxParametricBands - 1)
    }

    function removeParametricBand(index) {
        edit(function(target) {
            const bands = (target.parametricBands || []).slice()
            if (index < 0 || index >= bands.length)
                return false
            bands.splice(index, 1)
            target.parametricBands = bands
        })
        selectedBand = Math.max(0, Math.min(selectedBand, parametricBands.length - 1))
    }

    function resetAll() {
        if (unsupported)
            return
        commitTimer.stop()
        _pending = null
        Api.call("audioEffects.reset").then(function(result) {
            applySnapshot(result)
            selectedBand = 0
            statusMessage = I18n.tr("已重置音效参数")
        }).catch(function(error) {
            handleError(error, I18n.tr("重置失败"))
        })
    }

    // ------------------------------------------------------------------ 倍速与音调
    //
    // 与上游一致：关闭分区时把当前倍速/音调记下来并恢复 1.0x / 0 半音，
    // 重新打开时取回。联动模式下音调跟着倍速走（唱片效果），不联动时独立可调。

    function setSpeed(value) {
        const speed = Math.max(0.5, Math.min(3.0, Number(value) || 1))
        Player.setSpeed(speed)
        if (root.pitchLinked)
            Player.setPitch(root.speedToSemitone(speed))
        if (root.speedOn && root.sound)
            updateField("storedSpeed", speed)
    }

    function setPitch(value) {
        const semitone = Math.max(-12, Math.min(12, Number(value) || 0))
        Player.setPitch(semitone)
        if (root.speedOn && root.sound)
            updateField("storedPitch", semitone)
    }

    function setPitchLinked(linked) {
        updateField("speedPitchLinked", linked)
        // 打开联动时立刻把音调拉到与当前倍速匹配；关闭时回到 0 半音，
        // 避免「刚关掉联动，声音还停在变调状态」的困惑。
        Player.setPitch(linked ? root.speedToSemitone(Player.speed) : 0)
    }

    function setSpeedEnabled(enabled) {
        // 先用「当前实际值」更新 stored，再改开关与播放器，顺序不能反：
        // 关掉之后再读 Player.speed 已经是 1.0 了。
        if (!enabled) {
            updateField("storedSpeed", Number(Player.speed))
            updateField("storedPitch", Number(Player.pitch))
            Player.setSpeed(1.0)
            Player.setPitch(0)
        } else {
            Player.setSpeed(root.num("storedSpeed", 1.0))
            Player.setPitch(root.num("storedPitch", 0.0))
        }
        updateField("speedEnabled", enabled)
    }

    function resetSpeedSection() {
        Player.setSpeed(1.0)
        Player.setPitch(0)
        edit(function(target) {
            target.speedEnabled = true
            target.storedSpeed = 1.0
            target.storedPitch = 0.0
        })
        statusMessage = I18n.tr("已恢复该分区默认值")
    }

    // ------------------------------------------------------------------ AutoEQ 导入

    function runImport() {
        const text = autoEqTextField.text
        if (!text || text.trim() === "") {
            Toast.show(I18n.tr("请先粘贴 AutoEQ 或 Equalizer APO 的配置文本"), "info")
            return
        }
        if (unsupported) {
            Toast.show(I18n.tr("后端暂不支持音效命令"), "error")
            return
        }
        commitNow()
        busy = true
        Api.call("audioEffects.importAutoEq", { "text": text, "apply": true }).then(function(result) {
            busy = false
            importResult = result || null
            applySnapshot(result)
            selectedBand = 0
            const imported = result && result.imported !== undefined ? result.imported : 0
            const skipped = result && result.skipped ? result.skipped : 0
            statusMessage = I18n.tr("已导入 ") + imported + I18n.tr(" 段")
                           + (skipped > 0 ? I18n.tr("，跳过 ") + skipped + I18n.tr(" 行") : "")
            Toast.show(statusMessage, "info")
        }).catch(function(error) {
            busy = false
            importResult = null
            handleError(error, I18n.tr("AutoEQ 导入失败"))
        })
    }

    /** 供外部（例如从文件读入 / 预设分享）预填导入文本。 */
    function setAutoEqText(text) {
        autoEqTextField.text = text ? String(text) : ""
    }

    function clearImportResult() {
        importResult = null
        autoEqTextField.text = ""
    }

    // ------------------------------------------------------------------ 生命周期

    Timer {
        id: commitTimer
        // 合并窗口：连点/连拖产生的多次改动只发最后一次
        interval: 120
        repeat: false
        onTriggered: root.flush()
    }

    Component.onCompleted: load()

    Connections {
        target: Backend

        function onConnectedChanged() {
            if (Backend.connected)
                root.load()
        }
    }

    // 离开面板前把挂起的改动发出去，避免调整后用户直接切页导致丢失。
    // 退出时 Api 可能已经断开，异常只能吞掉——参数已写进 config 的部分仍然有效。
    Component.onDestruction: {
        commitTimer.stop()
        if (root._pending !== null) {
            try {
                root.pushNow(root._pending, "")
            } catch (e) {
                console.warn("[dsp] 退出时提交音效参数失败:", e)
            }
        }
    }

    // ------------------------------------------------------------------ 主体 UI

    Column {
        id: contentColumn

        width: parent.width
        spacing: Theme.spacingMd

        // ===== 卡片 1：总开关 + 标签栏 =====
        Rectangle {
            width: parent.width
            radius: Theme.radiusXl
            color: Theme.surfaceContainerLow
            implicitHeight: headColumn.height + Theme.spacingLg * 2

            Column {
                id: headColumn

                x: Theme.spacingLg
                y: Theme.spacingLg
                width: parent.width - Theme.spacingLg * 2
                spacing: Theme.spacingSm

                // 标题 + 右侧按钮：用锚定而不是 Row + 定宽占位，
                // 否则英文标签更长时按钮会被挤出卡片右边界。
                Item {
                    width: parent.width
                    height: 36

                    NeriIcon {
                        id: headIcon
                        name: "graphic_eq"
                        iconSize: 22
                        color: Theme.primary
                        anchors.left: parent.left
                        anchors.verticalCenter: parent.verticalCenter
                    }

                    Text {
                        anchors.left: headIcon.right
                        anchors.leftMargin: Theme.spacingSm
                        anchors.right: resetAllButton.left
                        anchors.rightMargin: Theme.spacingSm
                        anchors.verticalCenter: parent.verticalCenter
                        text: I18n.tr("DSP 音效")
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("titleMedium").size
                        font.weight: Font.DemiBold
                        elide: Text.ElideRight
                    }

                    // 与下方分区卡片的「恢复默认」区分：这里是整份参数重置，
                    // 分区卡片只重置当前 Tab 的分区。
                    SettingsButton {
                        id: resetAllButton
                        text: I18n.tr("重置全部音效")
                        icon: "restart_alt"
                        enabled: root.ready && !root.busy
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        onClicked: root.resetAll()
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    title: I18n.tr("启用 DSP 音效")
                    description: I18n.tr("关闭后所有音效参数保留但不参与播放，声音为原始输出")
                    checked: root.masterEnabled
                    onToggled: function(checked) { root.updateField("enabled", checked) }
                }

                // 标签栏：一个 Tab ＝ 一个分区，当前 Tab 高亮。
                // M3 可滚动标签页：选中项 primary 色文字 + 底部下划线指示条，
                // 底部通栏细线；8 个 Tab 在窄窗口下横向滚动，不裁切。
                SettingsTabBar {
                    width: parent.width
                    options: root.tabs.map(function(tab) {
                        return { "value": tab.id, "label": tab.label, "icon": tab.icon }
                    })
                    value: root.currentTab
                    onSelected: function(value) { root.currentTab = String(value) }
                }

                SettingsNote {
                    width: parent.width
                    visible: !Player.supportsEffects && !root.unsupported
                    textColor: Theme.error
                    text: I18n.tr("当前环境未安装 ffmpeg，音效无法作用于播放。安装 ffmpeg 后重启应用即可启用。")
                }

                SettingsNote {
                    width: parent.width
                    visible: root.unsupported
                    textColor: Theme.error
                    text: I18n.tr("后端暂不支持音效命令（audioEffects.*），请确认使用的后端版本与前端一致。")
                }
            }
        }

        // ===== 当前标签页的分区开关（预设/高级没有独立开关） =====
        Rectangle {
            width: parent.width
            radius: Theme.radiusXl
            color: Theme.surfaceContainerLow
            visible: root.currentTab === "equalizer" || root.currentTab === "tone"
                     || root.currentTab === "space" || root.currentTab === "dynamics"
                     || root.currentTab === "speaker" || root.currentTab === "speed"
            implicitHeight: switchColumn.height + Theme.spacingLg * 2

            Column {
                id: switchColumn

                x: Theme.spacingLg
                y: Theme.spacingLg
                width: parent.width - Theme.spacingLg * 2
                spacing: Theme.spacingSm

                Item {
                    width: parent.width
                    height: 36

                    Text {
                        anchors.left: parent.left
                        anchors.right: resetSectionButton.left
                        anchors.rightMargin: Theme.spacingSm
                        anchors.verticalCenter: parent.verticalCenter
                        text: root.tabTitle(root.currentTab)
                        color: Theme.onSurfaceColor
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.textStyle("titleSmall").size
                        font.weight: Font.DemiBold
                        elide: Text.ElideRight
                    }

                    SettingsButton {
                        id: resetSectionButton
                        text: I18n.tr("恢复默认")
                        icon: "restart_alt"
                        enabled: root.ready && !root.busy
                        anchors.right: parent.right
                        anchors.verticalCenter: parent.verticalCenter
                        onClicked: root.resetCurrentTab()
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    visible: root.currentTab === "equalizer"
                    title: I18n.tr("启用均衡器")
                    description: I18n.tr("关闭后这一分区按原声处理，已有的调节会保留")
                    checked: root.equalizerOn
                    onToggled: function(checked) {
                        root.toggleSection("equalizerEnabled", checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    visible: root.currentTab === "tone"
                    title: I18n.tr("启用音色调节")
                    description: I18n.tr("关闭后这一分区按原声处理，已有的调节会保留")
                    checked: root.toneOn
                    onToggled: function(checked) {
                        root.toggleSection("toneEnabled", checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    visible: root.currentTab === "space"
                    title: I18n.tr("启用空间效果")
                    description: I18n.tr("关闭后这一分区按原声处理，已有的调节会保留")
                    checked: root.spaceOn
                    onToggled: function(checked) {
                        root.toggleSection("spaceEnabled", checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    visible: root.currentTab === "dynamics"
                    title: I18n.tr("启用动态处理")
                    description: I18n.tr("关闭后压缩和输出增益按原声处理，仍保留默认的防破音保护")
                    checked: root.dynamicsOn
                    onToggled: function(checked) {
                        root.toggleSection("dynamicsEnabled", checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    visible: root.currentTab === "speaker"
                    title: I18n.tr("启用外放优化")
                    description: I18n.tr("针对外放喇叭的谐波低音、响度与清晰度补偿")
                    checked: root.speakerOn
                    onToggled: function(checked) {
                        if (checked && !root.masterEnabled)
                            root.updateField("enabled", true)
                        root.updateSpeaker("enabled", checked)
                    }
                }

                SettingsSwitchRow {
                    width: parent.width
                    visible: root.currentTab === "speed"
                    title: I18n.tr("启用倍速与音调")
                    description: I18n.tr("关闭后按正常速度播放，再次打开会恢复之前的倍速和音调")
                    checked: root.speedOn
                    onToggled: function(checked) { root.setSpeedEnabled(checked) }
                }

                SettingsNote {
                    width: parent.width
                    // native DSP 随包发布，默认可用；只有后端明确标记某参数为未生效
                    // 时才提示，避免绝大多数用户看到误导信息。
                    visible: !root.unsupported && (
                                 (root.currentTab === "tone" && !root.applied("virtualBass"))
                                 || (root.currentTab === "space" && !root.applied("reverb"))
                                 || (root.currentTab === "dynamics" && !root.applied("compressor"))
                                 || (root.currentTab === "speaker" && !root.applied("speaker"))
                             )
                    textColor: Theme.error
                    text: I18n.tr("当前音频链未启用这个分区的高级参数，调整后不会改变声音；请确认播放引擎已开启音效支持。")
                }
            }
        }

        // ===== 卡片 2：当前标签页的内容 =====
        Rectangle {
            width: parent.width
            radius: Theme.radiusXl
            color: Theme.surfaceContainerLow
            implicitHeight: tabColumn.height + Theme.spacingLg * 2

            Column {
                id: tabColumn

                x: Theme.spacingLg
                y: Theme.spacingLg
                width: parent.width - Theme.spacingLg * 2
                spacing: Theme.spacingSm

                // ------------------------------------------------ 预设
                Column {
                    width: parent.width
                    spacing: Theme.spacingSm
                    visible: root.currentTab === "presets"

                    SettingsLabel {
                        width: parent.width
                        text: I18n.tr("音效预设")
                    }

                    SettingsNote {
                        width: parent.width
                        text: I18n.tr("预设是一组图形均衡曲线（十段）。选择后立即生效，也可以到「均衡器」分区手动微调。")
                    }

                    SegmentedOptions {
                        width: parent.width
                        options: root.presetLabels.map(function(label) {
                            return { value: label, label: I18n.tr(label) }
                        })
                        value: root.currentPreset
                        onSelected: function(value) { root.applyPreset(value) }
                    }

                    SettingsNote {
                        width: parent.width
                        text: I18n.tr("当前预设：") + I18n.tr(root.currentPreset)
                                + I18n.tr("。手动拖动均衡滑块会切换为「自定义」。")
                    }
                }

                // ------------------------------------------------ 均衡器
                Column {
                    width: parent.width
                    spacing: Theme.spacingSm
                    visible: root.currentTab === "equalizer"

                    FrequencyCurve {
                        width: parent.width
                        points: root.points
                        graphicFrequencies: root.graphicFrequencies
                        preampDb: root.num("preampDb", 0)
                    }

                    SliderRow {
                        width: parent.width
                        label: I18n.tr("前级增益（Preamp）")
                        from: -12
                        to: 6
                        decimals: 1
                        externalValue: root.num("preampDb", 0)
                        formatValue: function(value) { return root.formatGain(value) }
                        onCommitted: function(value) { root.updateField("preampDb", value) }
                    }

                    SettingsLabel {
                        width: parent.width
                        text: I18n.tr("图形均衡（十段）")
                    }

                    Repeater {
                        model: root.graphicFrequencies.length

                        delegate: SliderRow {
                            required property int index

                            width: parent.width
                            label: root.frequencyLabel(root.graphicFrequencies[index])
                            from: -12
                            to: 12
                            decimals: 1
                            externalValue: root.graphicBands[index]
                            formatValue: function(value) { return root.formatGain(value) }
                            onCommitted: function(value) { root.updateGraphicBand(index, value) }
                        }
                    }

                    SettingsSwitchRow {
                        width: parent.width
                        title: I18n.tr("参数均衡")
                        description: I18n.tr("用可调频率与 Q 值的滤波器精修曲线，可与图形均衡叠加")
                        checked: root.bool("parametricEnabled", false)
                        onToggled: function(checked) { root.updateField("parametricEnabled", checked) }
                    }

                    Row {
                        width: parent.width
                        spacing: Theme.spacingSm
                        visible: root.bool("parametricEnabled", false)

                        SettingsLabel {
                            width: Math.max(0, parent.width - addButton.width - removeButton.width
                                            - Theme.spacingSm * 2)
                            text: I18n.tr("参数段（最多 ") + root.maxParametricBands + I18n.tr(" 段）")
                        }

                        SettingsButton {
                            id: addButton
                            text: I18n.tr("添加")
                            icon: "add"
                            enabled: root.bandCount < root.maxParametricBands
                            onClicked: root.addParametricBand()
                        }

                        SettingsButton {
                            id: removeButton
                            text: I18n.tr("删除")
                            icon: "delete"
                            enabled: root.bandCount > 0
                            onClicked: root.removeParametricBand(root.selectedBand)
                        }
                    }

                    SettingsNote {
                        width: parent.width
                        visible: root.bool("parametricEnabled", false) && root.bandCount === 0
                        text: I18n.tr("尚无参数均衡段。可以点「添加」手动新增，或用下方 AutoEQ 导入。")
                    }

                    SegmentedOptions {
                        width: parent.width
                        visible: root.bool("parametricEnabled", false) && root.bandCount > 0
                        options: {
                            const list = []
                            for (let i = 0; i < root.bandCount; i++) {
                                const band = root.parametricBands[i]
                                list.push({
                                    "value": String(i),
                                    "label": (i + 1) + ". " + root.bandTypeLabel(band.type)
                                })
                            }
                            return list
                        }
                        value: String(root.selectedBand)
                        onSelected: function(value) { root.selectedBand = Number(value) || 0 }
                    }

                    // 选中段的编辑器：类型 + 开关 + 三个参数滑块
                    Column {
                        width: parent.width
                        spacing: Theme.spacingSm
                        visible: root.bool("parametricEnabled", false)
                                 && root.bandCount > 0 && root.selectedBand < root.bandCount

                        SettingsSwitchRow {
                            width: parent.width
                            title: I18n.tr("启用该段")
                            checked: root.selectedBandValue("enabled", false) === true
                            onToggled: function(checked) {
                                root.updateParametricBand(root.selectedBand, { "enabled": checked })
                            }
                        }

                        SettingsLabel {
                            width: parent.width
                            text: I18n.tr("滤波器类型")
                        }

                        SegmentedOptions {
                            width: parent.width
                            options: root.bandTypeOptions
                            value: root.selectedBandValue("type", "peak")
                            onSelected: function(value) {
                                root.updateParametricBand(root.selectedBand, { "type": String(value) })
                            }
                        }

                        SliderRow {
                            width: parent.width
                            label: I18n.tr("频率")
                            // 0~1 是 log(20..20000) 的归一化位置，不是 Hz
                            from: 0
                            to: 1
                            decimals: 3
                            externalValue: root.logRatio(root.selectedBandValue("frequencyHz", 1000), 20, 20000)
                            formatValue: function(value) {
                                return root.frequencyLabel(root.logValue(value, 20, 20000))
                            }
                            onCommitted: function(value) {
                                root.updateParametricBand(root.selectedBand,
                                                          { "frequencyHz": root.logValue(value, 20, 20000) })
                            }
                        }

                        SliderRow {
                            width: parent.width
                            label: I18n.tr("增益")
                            from: -30
                            to: 24
                            decimals: 1
                            externalValue: root.selectedBandValue("gainDb", 0)
                            formatValue: function(value) { return root.formatGain(value) }
                            onCommitted: function(value) {
                                root.updateParametricBand(root.selectedBand, { "gainDb": value })
                            }
                        }

                        SliderRow {
                            width: parent.width
                            label: I18n.tr("Q 值")
                            // 0~1 是 log(0.1..20) 的归一化位置
                            from: 0
                            to: 1
                            decimals: 3
                            externalValue: root.logRatio(root.selectedBandValue("q", 0.707), 0.1, 20)
                            formatValue: function(value) {
                                return root.logValue(value, 0.1, 20).toFixed(2)
                            }
                            onCommitted: function(value) {
                                root.updateParametricBand(root.selectedBand,
                                                          { "q": root.logValue(value, 0.1, 20) })
                            }
                        }
                    }

                    // ---- AutoEQ 导入（只覆盖均衡曲线，故留在均衡器分区） ----
                    SettingsLabel {
                        width: parent.width
                        text: I18n.tr("AutoEQ 导入")
                    }

                    SettingsNote {
                        width: parent.width
                        text: I18n.tr("粘贴 AutoEQ / Equalizer APO 的配置文本。支持 Preamp、PK/LS/HS/LP/HP/NO/BP 滤波行，以及 GraphicEQ 数值串（按对数插值到十段）。导入会立即作用于播放。")
                    }

                    Rectangle {
                        width: parent.width
                        height: 128
                        radius: Theme.radiusMd
                        color: Theme.surfaceContainerHigh
                        border.width: autoEqTextField.activeFocus ? 1 : 0
                        border.color: Theme.primary

                        ScrollView {
                            anchors.fill: parent
                            anchors.margins: Theme.spacingSm
                            clip: true

                            TextArea {
                                id: autoEqTextField

                                padding: 0
                                color: Theme.onSurfaceColor
                                selectedTextColor: Theme.onPrimaryColor
                                selectionColor: Theme.primary
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("bodySmall").size
                                wrapMode: TextArea.WrapAnywhere
                                selectByMouse: true
                                background: null
                            }
                        }

                        // TextArea 的 placeholder 在离屏渲染里不绘制，用一层文本兜底
                        Text {
                            visible: autoEqTextField.text.length === 0
                            anchors.left: parent.left
                            anchors.right: parent.right
                            anchors.top: parent.top
                            anchors.margins: Theme.spacingSm
                            text: "Preamp: -6.0 dB\nFilter 1: ON PK Fc 105 Hz Gain -3.5 dB Q 1.20"
                            color: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.7)
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("bodySmall").size
                            wrapMode: Text.Wrap
                        }
                    }

                    Row {
                        width: parent.width
                        spacing: Theme.spacingSm

                        SettingsButton {
                            text: root.busy ? I18n.tr("导入中…") : I18n.tr("导入并应用")
                            icon: "file_upload"
                            filled: true
                            enabled: !root.busy
                            onClicked: root.runImport()
                        }

                        SettingsButton {
                            text: I18n.tr("清空")
                            icon: "close"
                            onClicked: root.clearImportResult()
                        }
                    }

                    Column {
                        width: parent.width
                        spacing: 2
                        visible: root.importResult !== null

                        Text {
                            width: parent.width
                            text: {
                                const result = root.importResult
                                if (!result)
                                    return ""
                                const source = result.source === "graphic" ? I18n.tr("GraphicEQ") : I18n.tr("参数滤波器")
                                let text = I18n.tr("来源：") + source + " · "
                                           + I18n.tr("导入 ") + result.imported + I18n.tr(" 段")
                                if (result.skipped)
                                    text += " · " + I18n.tr("跳过 ") + result.skipped + I18n.tr(" 行")
                                return text
                            }
                            color: Theme.onSurfaceColor
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.textStyle("bodySmall").size
                            wrapMode: Text.Wrap
                        }

                        Repeater {
                            model: root.importResult && root.importResult.warnings ? root.importResult.warnings : []

                            delegate: Text {
                                required property string modelData
                                width: parent.width
                                text: "· " + modelData
                                color: Theme.onSurfaceVariantColor
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.textStyle("labelSmall").size
                                wrapMode: Text.Wrap
                            }
                        }
                    }

                    SettingsNote {
                        width: parent.width
                        text: I18n.tr("导入只覆盖均衡曲线（前级 + 图形/参数均衡），音色、空间、动态分区保持当前设置。同一份配置里 GraphicEQ 与 Filter 不能混用。")
                    }
                }

                // ------------------------------------------------ 音色
                Column {
                    width: parent.width
                    spacing: Theme.spacingSm
                    visible: root.currentTab === "tone"

                    SliderRow {
                        width: parent.width
                        label: I18n.tr("低音增益")
                        from: -12
                        to: 12
                        decimals: 1
                        externalValue: root.num("bassDb", 0)
                        formatValue: function(value) { return root.formatGain(value) }
                        onCommitted: function(value) { root.updateField("bassDb", value) }
                    }

                    SliderRow {
                        width: parent.width
                        label: I18n.tr("低音转折频率")
                        from: 0
                        to: 1
                        decimals: 3
                        externalValue: root.logRatio(root.num("bassFrequencyHz", 100), 40, 250)
                        formatValue: function(value) {
                            return root.frequencyLabel(root.logValue(value, 40, 250))
                        }
                        onCommitted: function(value) {
                            root.updateField("bassFrequencyHz", root.logValue(value, 40, 250))
                        }
                    }

                    SliderRow {
                        width: parent.width
                        label: I18n.tr("高音增益")
                        from: -12
                        to: 12
                        decimals: 1
                        externalValue: root.num("trebleDb", 0)
                        formatValue: function(value) { return root.formatGain(value) }
                        onCommitted: function(value) { root.updateField("trebleDb", value) }
                    }

                    SliderRow {
                        width: parent.width
                        label: I18n.tr("高音转折频率")
                        from: 0
                        to: 1
                        decimals: 3
                        externalValue: root.logRatio(root.num("trebleFrequencyHz", 8000), 2000, 14000)
                        formatValue: function(value) {
                            return root.frequencyLabel(root.logValue(value, 2000, 14000))
                        }
                        onCommitted: function(value) {
                            root.updateField("trebleFrequencyHz", root.logValue(value, 2000, 14000))
                        }
                    }

                    SliderRow {
                        width: parent.width
                        label: I18n.tr("清晰度")
                        from: 0
                        to: 1
                        decimals: 2
                        externalValue: root.num("clarity", 0)
                        formatValue: function(value) { return root.formatPercent(value) }
                        onCommitted: function(value) { root.updateField("clarity", value) }
                    }

                    SliderRow {
                        width: parent.width
                        label: I18n.tr("清晰度中心频率")
                        from: 0
                        to: 1
                        decimals: 3
                        externalValue: root.logRatio(root.num("clarityFrequencyHz", 5000), 2000, 10000)
                        formatValue: function(value) {
                            return root.frequencyLabel(root.logValue(value, 2000, 10000))
                        }
                        onCommitted: function(value) {
                            root.updateField("clarityFrequencyHz", root.logValue(value, 2000, 10000))
                        }
                    }

                    SliderRow {
                        width: parent.width
                        enabled: root.applied("virtualBass")
                        label: I18n.tr("虚拟低音")
                        from: 0
                        to: 1
                        decimals: 2
                        externalValue: root.num("virtualBass", 0)
                        formatValue: function(value) { return root.formatPercent(value) }
                        onCommitted: function(value) { root.updateField("virtualBass", value) }
                    }

                    SliderRow {
                        width: parent.width
                        enabled: root.applied("virtualBass")
                        label: I18n.tr("虚拟低音下限频率")
                        from: 0
                        to: 1
                        decimals: 3
                        externalValue: root.logRatio(root.num("virtualBassFrequencyHz", 100), 50, 200)
                        formatValue: function(value) {
                            return root.frequencyLabel(root.logValue(value, 50, 200))
                        }
                        onCommitted: function(value) {
                            root.updateField("virtualBassFrequencyHz", root.logValue(value, 50, 200))
                        }
                    }

                    SliderRow {
                        width: parent.width
                        enabled: root.applied("warmth")
                        label: I18n.tr("温暖度")
                        from: 0
                        to: 1
                        decimals: 2
                        externalValue: root.num("warmth", 0)
                        formatValue: function(value) { return root.formatPercent(value) }
                        onCommitted: function(value) { root.updateField("warmth", value) }
                    }

                    SliderRow {
                        width: parent.width
                        enabled: root.applied("vocal")
                        // 用「人声突出度」而不是「人声增强」：后者已经是预设名的 key，
                        // 复用它会让预设 chips 与这个滑块共用一个英文词条。
                        label: I18n.tr("人声突出度")
                        from: 0
                        to: 1
                        decimals: 2
                        externalValue: root.num("vocal", 0)
                        formatValue: function(value) { return root.formatPercent(value) }
                        onCommitted: function(value) { root.updateField("vocal", value) }
                    }

                    SliderRow {
                        width: parent.width
                        enabled: root.applied("vocalRemoval")
                        label: I18n.tr("人声削弱（卡拉 OK）")
                        from: 0
                        to: 1
                        decimals: 2
                        externalValue: root.num("vocalRemoval", 0)
                        formatValue: function(value) { return root.formatPercent(value) }
                        onCommitted: function(value) { root.updateField("vocalRemoval", value) }
                    }
                }

                // ------------------------------------------------ 空间
                Column {
                    width: parent.width
                    spacing: Theme.spacingSm
                    visible: root.currentTab === "space"

                    SliderRow {
                        width: parent.width
                        label: I18n.tr("立体声宽度")
                        from: 0
                        to: 2
                        decimals: 2
                        externalValue: root.num("stereoWidth", 1)
                        formatValue: function(value) { return root.formatAmount(value) }
                        onCommitted: function(value) { root.updateField("stereoWidth", value) }
                    }

                    SliderRow {
                        width: parent.width
                        label: I18n.tr("低频单声道化（0 为关闭）")
                        from: 0
                        to: 250
                        decimals: 0
                        externalValue: root.num("monoBassHz", 0)
                        formatValue: function(value) {
                            return Number(value) < 40 ? I18n.tr("关闭") : Math.round(value) + " Hz"
                        }
                        onCommitted: function(value) {
                            root.updateField("monoBassHz", Number(value) < 40 ? 0 : value)
                        }
                    }

                    SettingsSwitchRow {
                        width: parent.width
                        title: I18n.tr("强制单声道")
                        description: I18n.tr("左右声道混合为同一内容，适合单耳收听")
                        checked: root.bool("mono", false)
                        onToggled: function(checked) { root.updateField("mono", checked) }
                    }

                    SettingsSwitchRow {
                        width: parent.width
                        title: I18n.tr("左右声道互换")
                        checked: root.bool("swapChannels", false)
                        onToggled: function(checked) { root.updateField("swapChannels", checked) }
                    }

                    SliderRow {
                        width: parent.width
                        enabled: root.applied("crossfeed")
                        label: I18n.tr("交叉馈送")
                        from: 0
                        to: 1
                        decimals: 2
                        externalValue: root.num("crossfeed", 0)
                        formatValue: function(value) { return root.formatPercent(value) }
                        onCommitted: function(value) { root.updateField("crossfeed", value) }
                    }

                    SliderRow {
                        width: parent.width
                        enabled: root.applied("surround")
                        label: I18n.tr("环绕")
                        from: 0
                        to: 1
                        decimals: 2
                        externalValue: root.num("surround", 0)
                        formatValue: function(value) { return root.formatPercent(value) }
                        onCommitted: function(value) { root.updateField("surround", value) }
                    }

                    SliderRow {
                        width: parent.width
                        enabled: root.applied("reverb")
                        label: I18n.tr("混响")
                        from: 0
                        to: 1
                        decimals: 2
                        externalValue: root.num("reverb", 0)
                        formatValue: function(value) { return root.formatPercent(value) }
                        onCommitted: function(value) { root.updateField("reverb", value) }
                    }

                    SliderRow {
                        width: parent.width
                        enabled: root.applied("reverb")
                        label: I18n.tr("混响房间大小")
                        from: 0
                        to: 1
                        decimals: 2
                        externalValue: root.num("reverbRoom", 0.5)
                        formatValue: function(value) { return root.formatPercent(value) }
                        onCommitted: function(value) { root.updateField("reverbRoom", value) }
                    }

                    SliderRow {
                        width: parent.width
                        enabled: root.applied("reverb")
                        label: I18n.tr("混响阻尼")
                        from: 0
                        to: 1
                        decimals: 2
                        externalValue: root.num("reverbDamping", 0.5)
                        formatValue: function(value) { return root.formatPercent(value) }
                        onCommitted: function(value) { root.updateField("reverbDamping", value) }
                    }

                    SliderRow {
                        width: parent.width
                        enabled: root.applied("reverb")
                        label: I18n.tr("混响预延迟")
                        from: 0
                        to: 100
                        decimals: 0
                        externalValue: root.num("reverbPreDelayMs", 10)
                        formatValue: function(value) { return Math.round(value) + "ms" }
                        onCommitted: function(value) { root.updateField("reverbPreDelayMs", Math.round(value)) }
                    }
                }

                // ------------------------------------------------ 动态
                Column {
                    width: parent.width
                    spacing: Theme.spacingSm
                    visible: root.currentTab === "dynamics"

                    SliderRow {
                        width: parent.width
                        label: I18n.tr("输出增益")
                        from: -12
                        to: 12
                        decimals: 1
                        externalValue: root.num("outputGainDb", 0)
                        formatValue: function(value) { return root.formatGain(value) }
                        onCommitted: function(value) { root.updateField("outputGainDb", value) }
                    }

                    SettingsSwitchRow {
                        width: parent.width
                        enabled: root.applied("compressor")
                        title: I18n.tr("压缩器")
                        description: I18n.tr("压小动态、提升平均响度，适合嘈杂环境")
                        checked: root.bool("compressorEnabled", false)
                        onToggled: function(checked) { root.updateField("compressorEnabled", checked) }
                    }

                    Column {
                        width: parent.width
                        spacing: Theme.spacingSm
                        enabled: root.bool("compressorEnabled", false)
                        visible: root.bool("compressorEnabled", false)

                        SliderRow {
                            width: parent.width
                            label: I18n.tr("压缩阈值")
                            from: -50
                            to: 0
                            decimals: 1
                            externalValue: root.num("compressorThresholdDb", -18)
                            formatValue: function(value) { return root.formatGain(value) }
                            onCommitted: function(value) { root.updateField("compressorThresholdDb", value) }
                        }

                        SliderRow {
                            width: parent.width
                            label: I18n.tr("压缩比")
                            from: 1
                            to: 20
                            decimals: 1
                            externalValue: root.num("compressorRatio", 2)
                            formatValue: function(value) { return (Number(value) || 1).toFixed(1) + ":1" }
                            onCommitted: function(value) { root.updateField("compressorRatio", value) }
                        }

                        SliderRow {
                            width: parent.width
                            label: I18n.tr("启动时间")
                            from: 0.5
                            to: 100
                            decimals: 1
                            externalValue: root.num("compressorAttackMs", 10)
                            formatValue: function(value) { return (Number(value) || 0).toFixed(1) + "ms" }
                            onCommitted: function(value) { root.updateField("compressorAttackMs", value) }
                        }

                        SliderRow {
                            width: parent.width
                            label: I18n.tr("释放时间")
                            from: 20
                            to: 1000
                            decimals: 0
                            externalValue: root.num("compressorReleaseMs", 150)
                            formatValue: function(value) { return Math.round(value) + "ms" }
                            onCommitted: function(value) { root.updateField("compressorReleaseMs", Math.round(value)) }
                        }

                        SliderRow {
                            width: parent.width
                            label: I18n.tr("拐点宽度")
                            from: 0
                            to: 18
                            decimals: 1
                            externalValue: root.num("compressorKneeDb", 6)
                            formatValue: function(value) { return (Number(value) || 0).toFixed(1) + " dB" }
                            onCommitted: function(value) { root.updateField("compressorKneeDb", value) }
                        }

                        SliderRow {
                            width: parent.width
                            label: I18n.tr("补偿增益")
                            from: 0
                            to: 18
                            decimals: 1
                            externalValue: root.num("compressorMakeupDb", 0)
                            formatValue: function(value) { return root.formatGain(value) }
                            onCommitted: function(value) { root.updateField("compressorMakeupDb", value) }
                        }
                    }

                    SettingsSwitchRow {
                        width: parent.width
                        enabled: root.applied("limiter")
                        title: I18n.tr("限幅器")
                        description: I18n.tr("限制峰值避免削波破音，默认开启")
                        checked: root.bool("limiterEnabled", true)
                        onToggled: function(checked) { root.updateField("limiterEnabled", checked) }
                    }

                    Column {
                        width: parent.width
                        spacing: Theme.spacingSm
                        enabled: root.bool("limiterEnabled", true)
                        visible: root.bool("limiterEnabled", true)

                        SliderRow {
                            width: parent.width
                            label: I18n.tr("限幅上限")
                            from: -6
                            to: 0
                            decimals: 1
                            externalValue: root.num("limiterCeilingDb", -1)
                            formatValue: function(value) { return root.formatGain(value) }
                            onCommitted: function(value) { root.updateField("limiterCeilingDb", value) }
                        }

                        SliderRow {
                            width: parent.width
                            label: I18n.tr("限幅释放时间")
                            from: 20
                            to: 500
                            decimals: 0
                            externalValue: root.num("limiterReleaseMs", 80)
                            formatValue: function(value) { return Math.round(value) + "ms" }
                            onCommitted: function(value) { root.updateField("limiterReleaseMs", Math.round(value)) }
                        }
                    }
                }

                // ------------------------------------------------ 扬声器
                Column {
                    width: parent.width
                    spacing: Theme.spacingSm
                    visible: root.currentTab === "speaker"

                    SettingsLabel {
                        width: parent.width
                        text: I18n.tr("扬声器类型")
                    }

                    SegmentedOptions {
                        width: parent.width
                        options: root.speakerSizeOptions
                        value: root.speakerText("size", "phone")
                        onSelected: function(value) { root.updateSpeaker("size", String(value)) }
                    }

                    SliderRow {
                        width: parent.width
                        label: I18n.tr("低频保护")
                        from: 0
                        to: 400
                        decimals: 0
                        externalValue: root.speakerNum("protectionHz", 0)
                        formatValue: function(value) {
                            return Number(value) < 40
                                    ? I18n.tr("自动（") + root.frequencyLabel(root.speakerAutoProtection()) + "）"
                                    : Math.round(value) + " Hz"
                        }
                        onCommitted: function(value) {
                            root.updateSpeaker("protectionHz", Number(value) < 40 ? 0 : Math.round(value))
                        }
                    }

                    SliderRow {
                        width: parent.width
                        label: I18n.tr("低音补偿")
                        from: 0
                        to: 1
                        decimals: 2
                        externalValue: root.speakerNum("bassHarmonics", 0.5)
                        formatValue: function(value) { return root.formatPercent(value) }
                        onCommitted: function(value) { root.updateSpeaker("bassHarmonics", value) }
                    }

                    SliderRow {
                        width: parent.width
                        label: I18n.tr("响亮度")
                        from: 0
                        to: 1
                        decimals: 2
                        externalValue: root.speakerNum("loudness", 0.4)
                        formatValue: function(value) { return root.formatPercent(value) }
                        onCommitted: function(value) { root.updateSpeaker("loudness", value) }
                    }

                    SliderRow {
                        width: parent.width
                        label: I18n.tr("清晰度")
                        from: 0
                        to: 1
                        decimals: 2
                        externalValue: root.speakerNum("clarity", 0.3)
                        formatValue: function(value) { return root.formatPercent(value) }
                        onCommitted: function(value) { root.updateSpeaker("clarity", value) }
                    }

                    SliderRow {
                        width: parent.width
                        label: I18n.tr("立体声扩展")
                        from: 0
                        to: 1
                        decimals: 2
                        externalValue: root.speakerNum("stereoExpand", 0.3)
                        formatValue: function(value) { return root.formatPercent(value) }
                        onCommitted: function(value) { root.updateSpeaker("stereoExpand", value) }
                    }

                    SettingsNote {
                        width: parent.width
                        text: I18n.tr("外放优化针对小尺寸喇叭：用谐波补回低音、分频段提升响度并扩展立体声。耳机/大音箱上通常不需要开启。")
                    }
                }

                // ------------------------------------------------ 倍速
                Column {
                    width: parent.width
                    spacing: Theme.spacingSm
                    visible: root.currentTab === "speed"

                    SliderRow {
                        width: parent.width
                        enabled: root.speedOn
                        label: I18n.tr("播放速度")
                        // 与后端 Player.setSpeed 的夹取范围一致（0.5~3.0）；
                        // 上游滑块是 0.25~4.0，但桌面后端不接那两头，给了也是空转。
                        from: 0.5
                        to: 3.0
                        decimals: 2
                        externalValue: root.speedOn ? root.speedNow : root.num("storedSpeed", 1.0)
                        formatValue: function(value) { return root.formatMultiplier(value) }
                        onCommitted: function(value) { root.setSpeed(value) }
                    }

                    // 快捷档位：与上游一致的十个常用倍速
                    SegmentedOptions {
                        width: parent.width
                        enabled: root.speedOn
                        options: root.speedQuickValues.map(function(value) {
                            return { "value": value.toFixed(2), "label": root.formatMultiplier(value) }
                        })
                        value: (root.speedOn ? root.speedNow : root.num("storedSpeed", 1.0)).toFixed(2)
                        // SegmentedOptions 的 enabled 只改透明度，不拦点击，所以这里再判一次：
                        // 倍速分区关闭时点档位不应该偷偷改播放速度。
                        onSelected: function(value) {
                            if (root.speedOn)
                                root.setSpeed(Number(value))
                        }
                    }

                    SliderRow {
                        width: parent.width
                        enabled: root.speedOn && !root.pitchLinked
                        label: I18n.tr("音调")
                        from: -12
                        to: 12
                        decimals: 1
                        externalValue: root.pitchLinked ? root.speedToSemitone(root.speedNow)
                                                        : (root.speedOn ? root.pitchSemitoneNow
                                                                        : root.num("storedPitch", 0.0))
                        formatValue: function(value) { return root.formatSemitones(value) }
                        onCommitted: function(value) { root.setPitch(value) }
                    }

                    SegmentedOptions {
                        width: parent.width
                        enabled: root.speedOn && !root.pitchLinked
                        visible: !root.pitchLinked
                        options: root.pitchQuickSemitones.map(function(value) {
                            return { "value": Number(value).toFixed(1), "label": root.formatSemitones(value) }
                        })
                        value: (root.pitchSemitoneNow).toFixed(1)
                        onSelected: function(value) {
                            if (root.speedOn && !root.pitchLinked)
                                root.setPitch(Number(value))
                        }
                    }

                    SettingsNote {
                        width: parent.width
                        visible: root.pitchLinked
                        text: I18n.tr("音调会随倍速一起变化，就像唱片转快或转慢；关闭下面的开关后可单独调节音调。")
                    }

                    SettingsSwitchRow {
                        width: parent.width
                        title: I18n.tr("变调跟随倍速（唱片效果）")
                        description: I18n.tr("开启后加速声音会变尖、减速会变低沉，适合 Nightcore")
                        checked: root.pitchLinked
                        onToggled: function(checked) { root.setPitchLinked(checked) }
                    }

                    SettingsNote {
                        width: parent.width
                        text: I18n.tr("倍速与变调由后端播放链实现；不可用时仍会保存，下次具备条件时生效。")
                    }
                }

                // ------------------------------------------------ 高级
                Column {
                    width: parent.width
                    spacing: Theme.spacingSm
                    visible: root.currentTab === "advanced"

                    SettingsLabel {
                        width: parent.width
                        text: I18n.tr("处理质量")
                    }

                    SegmentedOptions {
                        width: parent.width
                        options: root.qualityModeOptions
                        value: root.text("qualityMode", "balanced")
                        onSelected: function(value) { root.updateField("qualityMode", String(value)) }
                    }

                    SettingsNote {
                        width: parent.width
                        text: I18n.tr("省电档会关闭 dither 与高精度的滤波计算；均衡/音色/空间的听感差别很小，动态处理受影响更明显。")
                    }

                    SettingsSwitchRow {
                        width: parent.width
                        title: I18n.tr("抖动（dither）")
                        description: I18n.tr("16bit 输出时加入极低电平抖动，减轻量化失真；省电档会自动关闭")
                        checked: root.bool("ditherEnabled", true)
                        onToggled: function(checked) { root.updateField("ditherEnabled", checked) }
                    }

                    Row {
                        width: parent.width
                        spacing: Theme.spacingSm

                        SettingsButton {
                            text: root.busy ? I18n.tr("应用中…") : I18n.tr("立即应用")
                            icon: "check"
                            filled: true
                            enabled: root.ready && !root.busy
                            onClicked: root.commitNow()
                        }

                        SettingsButton {
                            text: I18n.tr("重置全部音效")
                            icon: "restart_alt"
                            enabled: root.ready && !root.busy
                            onClicked: root.resetAll()
                        }
                    }

                    SettingsNote {
                        width: parent.width
                        visible: root.statusMessage !== ""
                        text: root.statusMessage
                    }

                    SettingsNote {
                        width: parent.width
                        text: I18n.tr("重置只把 DSP 参数恢复到出厂默认值，不会改动「倍速」分区的播放速度。")
                    }

                    SettingsNote {
                        width: parent.width
                        text: I18n.tr("参数改动会立即提交；这里只是手动冲刷尚未发出的那次合并提交。")
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ 小工具

    function tabTitle(id) {
        for (let i = 0; i < tabs.length; i++) {
            if (tabs[i].id === id)
                return tabs[i].label
        }
        return ""
    }

    /** 低频保护设为「自动」时按尺寸档位算出来的频率（与后端 SpeakerSize 一致）。 */
    function speakerAutoProtection() {
        const size = root.speakerText("size", "phone")
        if (size === "tablet")
            return 120
        if (size === "large")
            return 70
        return 180
    }

    /** 「恢复默认」按当前 Tab 决定恢复哪个分区；预设与高级走整份重置。 */
    function resetCurrentTab() {
        if (currentTab === "equalizer" || currentTab === "tone" || currentTab === "space"
                || currentTab === "dynamics" || currentTab === "speaker")
            resetSection(currentTab)
        else if (currentTab === "speed")
            resetSpeedSection()
        else if (currentTab === "presets")
            applyPreset("平直")
        else
            resetAll()
    }

    // ==================================================================== 频响曲线
    // 对数频率轴（20 Hz ~ 20 kHz）+ 线性 dB 轴；量程按曲线极值自动放大（≥12 dB，6 dB 一档）。
    component FrequencyCurve: Item {
        id: curve

        property var points: []
        property var graphicFrequencies: []
        property real preampDb: 0

        implicitHeight: 216

        onPointsChanged: canvas.requestPaint()
        onGraphicFrequenciesChanged: canvas.requestPaint()

        readonly property real plotHeight: 172
        readonly property real rangeDb: {
            let peak = Math.max(12, Math.abs(curve.preampDb) + 3)
            for (let i = 0; i < curve.points.length; i++)
                peak = Math.max(peak, Math.abs(Number(curve.points[i].gainDb) || 0))
            return Math.min(30, Math.ceil(peak / 6) * 6)
        }

        readonly property var gridFrequencies: [20, 100, 1000, 10000, 20000]

        function xForFrequency(hz) {
            return canvas.width * Math.log(hz / 20) / Math.log(1000)
        }

        function yForGain(gain) {
            const ratio = Math.max(-1, Math.min(1, gain / curve.rangeDb))
            return plot.height / 2 - ratio * (plot.height / 2 - 4)
        }

        onRangeDbChanged: canvas.requestPaint()
        onPreampDbChanged: canvas.requestPaint()

        Rectangle {
            id: plot

            width: parent.width
            height: curve.plotHeight
            radius: Theme.radiusSm
            color: Theme.surfaceContainerHigh
            border.width: 1
            border.color: Theme.outlineVariant

            Canvas {
                id: canvas

                anchors.fill: parent
                anchors.margins: 1

                onWidthChanged: requestPaint()
                onHeightChanged: requestPaint()

                onPaint: {
                    const ctx = getContext("2d")
                    ctx.reset()

                    const w = width
                    const h = height
                    if (w <= 0 || h <= 0)
                        return

                    // 纵向 dB 网格
                    const range = curve.rangeDb
                    ctx.lineWidth = 1
                    ctx.strokeStyle = Theme.withAlpha(Theme.outlineVariant, 0.9)
                    for (let db = -range; db <= range; db += range / 2) {
                        const y = curve.yForGain(db)
                        ctx.beginPath()
                        ctx.moveTo(0, y)
                        ctx.lineTo(w, y)
                        ctx.stroke()
                    }

                    // 横向倍频程网格
                    for (let i = 0; i < curve.gridFrequencies.length; i++) {
                        const x = curve.xForFrequency(curve.gridFrequencies[i])
                        ctx.beginPath()
                        ctx.moveTo(x, 0)
                        ctx.lineTo(x, h)
                        ctx.stroke()
                    }

                    // 0 dB 基准线更醒目
                    ctx.strokeStyle = Theme.withAlpha(Theme.onSurfaceVariantColor, 0.75)
                    ctx.lineWidth = 1.4
                    ctx.beginPath()
                    ctx.moveTo(0, curve.yForGain(0))
                    ctx.lineTo(w, curve.yForGain(0))
                    ctx.stroke()

                    const points = curve.points || []
                    if (points.length < 2)
                        return

                    // 曲线填充
                    ctx.beginPath()
                    ctx.moveTo(curve.xForFrequency(points[0].frequencyHz), curve.yForGain(points[0].gainDb))
                    for (let i = 1; i < points.length; i++)
                        ctx.lineTo(curve.xForFrequency(points[i].frequencyHz), curve.yForGain(points[i].gainDb))
                    ctx.lineTo(w, curve.yForGain(0))
                    ctx.lineTo(0, curve.yForGain(0))
                    ctx.closePath()
                    const fill = ctx.createLinearGradient(0, 0, 0, h)
                    fill.addColorStop(0, Theme.withAlpha(Theme.primary, 0.34))
                    fill.addColorStop(0.5, Theme.withAlpha(Theme.primary, 0.06))
                    fill.addColorStop(1, Theme.withAlpha(Theme.primary, 0.34))
                    ctx.fillStyle = fill
                    ctx.fill()

                    // 曲线本体
                    ctx.beginPath()
                    ctx.moveTo(curve.xForFrequency(points[0].frequencyHz), curve.yForGain(points[0].gainDb))
                    for (let i = 1; i < points.length; i++)
                        ctx.lineTo(curve.xForFrequency(points[i].frequencyHz), curve.yForGain(points[i].gainDb))
                    ctx.strokeStyle = Theme.primary
                    ctx.lineWidth = 2
                    ctx.lineJoin = "round"
                    ctx.stroke()
                }
            }
        }

        // 频率刻度标签
        Repeater {
            model: curve.gridFrequencies

            delegate: Text {
                required property var modelData

                readonly property real tickX: curve.xForFrequency(modelData)

                x: Math.max(0, Math.min(plot.width - width, plot.x + tickX - width / 2))
                y: plot.y + plot.height + 2
                text: modelData >= 1000 ? (modelData / 1000) + "k" : String(modelData)
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: 10
            }
        }

        // dB 刻度标签
        Repeater {
            model: [-1, 0, 1]

            delegate: Text {
                required property int modelData

                readonly property real gainValue: modelData * curve.rangeDb / 2

                x: 2
                // 标签放在网格线之上（不压线）：0 dB 那条线最常被用来读数
                y: Math.max(0, Math.min(plot.height - height,
                                        plot.y + curve.yForGain(gainValue) - height - 1))
                text: (gainValue > 0 ? "+" : "") + gainValue.toFixed(0) + " dB"
                color: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.9)
                font.family: Theme.fontFamily
                font.pixelSize: 9
            }
        }

        Text {
            anchors.right: parent.right
            y: plot.y + plot.height + 20
            text: I18n.tr("曲线由 RBJ biquad 合成，是这组参数的近似响应；ffmpeg 的 Q 语义略有差异")
            color: Theme.withAlpha(Theme.onSurfaceVariantColor, 0.85)
            font.family: Theme.fontFamily
            font.pixelSize: 9
        }
    }
}
