import QtQuick

// 设置页「均衡器」卡片内容：预设 + 10 段滑块 + 滤波器开关/响度增强 + 重置。
// 所有交互直接走 Player 全局对象；预设数值沿用 components/player/EffectsPanel.qml 的同一份表，
// 后端只负责按名保存，不会内置预设曲线。显示文字统一经 I18n.tr 包裹，切英文时即时刷新。
Item {
    id: panel

    implicitHeight: rootColumn.implicitHeight
    width: parent ? parent.width : 0

    // 频段标签与后端 EQ_FREQUENCIES（31,62,125,250,500,1000,2000,4000,8000,16000）一致。
    readonly property var frequencies: ["31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"]

    // 预设名必须与后端 Settings.equalizerPreset 的取值一致，数值与 EffectsPanel 共用。
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

    function bandValue(index) {
        const bands = Player.equalizerBands || []
        return index < bands.length ? Number(bands[index]) : 0
    }

    function formatGain(value) {
        const gain = Number(value) || 0
        return (gain >= 0 ? "+" : "") + gain.toFixed(1)
    }

    function applyPreset(name) {
        const bands = panel.presetBands[name]
        if (bands)
            Player.setEqualizerPreset(name, bands)
    }

    Column {
        id: rootColumn
        width: parent.width
        spacing: Theme.spacingSm

        // 后端不支持音效（如未安装 ffmpeg）时给出与播放页音效面板一致的提示。
        SettingsNote {
            width: parent.width
            visible: !Player.supportsEffects
            text: I18n.tr("当前环境未安装 ffmpeg，倍速、变调、响度与均衡器不可用。安装 ffmpeg 后重启应用即可启用。")
        }

        SettingsSwitchRow {
            width: parent.width
            enabled: Player.supportsEffects
            title: I18n.tr("启用均衡器")
            description: I18n.tr("启用后十段滤波器作用于当前播放，关闭则只保存增益不生效")
            checked: Player.equalizerEnabled
            onToggled: function(checked) { Player.setEqualizerEnabled(checked) }
        }

        SettingsSwitchRow {
            width: parent.width
            enabled: Player.supportsEffects
            title: I18n.tr("响度增强")
            description: I18n.tr("使用动态归一化提升整体响度（需要 ffmpeg 音效支持）")
            checked: Player.loudness
            onToggled: function(checked) { Player.setLoudness(checked) }
        }

        Item {
            width: 1
            height: 4
        }

        SettingsLabel {
            width: parent.width
            text: I18n.tr("预设")
        }

        SegmentedOptions {
            width: parent.width
            enabled: Player.supportsEffects
            options: panel.presetLabels.map(function(label) {
                return { value: label, label: I18n.tr(label) }
            })
            value: panel.currentPreset
            onSelected: function(value) { panel.applyPreset(value) }
        }

        SettingsNote {
            width: parent.width
            text: I18n.tr("手动拖动滑块会切换到「自定义」预设。")
        }

        Item {
            width: 1
            height: 4
        }

        SettingsLabel {
            width: parent.width
            text: I18n.tr("频段微调（dB）")
        }

        Repeater {
            model: 10

            delegate: SliderRow {
                required property int index

                width: parent.width
                enabled: Player.supportsEffects && Player.equalizerEnabled
                label: panel.frequencies[index] + " Hz"
                from: -12
                to: 12
                decimals: 1
                // 后端每次只改一段：Player.equalizerBands 变化会即时回灌 externalValue，
                // 预设切换后这里也跟着刷新，不必手动同步。
                externalValue: panel.bandValue(index)
                formatValue: function(value) { return panel.formatGain(value) }
                onCommitted: function(value) { Player.setEqualizerBand(index, value) }
            }
        }

        Item {
            width: 1
            height: 4
        }

        Row {
            spacing: Theme.spacingSm

            SettingsButton {
                text: I18n.tr("重置均衡器")
                enabled: Player.supportsEffects
                onClicked: panel.applyPreset("平直")
            }
        }
    }
}
