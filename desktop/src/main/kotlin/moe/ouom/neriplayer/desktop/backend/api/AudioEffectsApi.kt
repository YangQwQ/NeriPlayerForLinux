package moe.ouom.neriplayer.desktop.backend.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import moe.ouom.neriplayer.desktop.backend.CoreServer
import moe.ouom.neriplayer.desktop.backend.ProtocolException
import moe.ouom.neriplayer.desktop.backend.WireJson
import moe.ouom.neriplayer.desktop.backend.badRequest
import moe.ouom.neriplayer.desktop.backend.boolOrNull
import moe.ouom.neriplayer.desktop.backend.encodeJson
import moe.ouom.neriplayer.desktop.backend.intOrNull
import moe.ouom.neriplayer.desktop.backend.objectOrNull
import moe.ouom.neriplayer.desktop.backend.stringOrNull
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.AudioEffectsCapabilities
import moe.ouom.neriplayer.desktop.core.AudioEffectsDsp
import moe.ouom.neriplayer.desktop.core.AudioEffectsSection
import moe.ouom.neriplayer.desktop.core.AudioEffectsSound
import moe.ouom.neriplayer.desktop.core.AutoEqImportResult
import moe.ouom.neriplayer.desktop.core.AutoEqParser
import moe.ouom.neriplayer.desktop.core.GRAPHIC_EQ_FREQUENCIES
import moe.ouom.neriplayer.desktop.core.ParametricEqBand
import moe.ouom.neriplayer.desktop.core.PlayerManager
import moe.ouom.neriplayer.desktop.core.SpeakerOptimizer
import moe.ouom.neriplayer.desktop.core.SpeakerSize

/**
 * 音效（DSP）设置：参数均衡器、AutoEQ 导入、频响曲线，以及**实时生效**。
 *
 * 参数已经接进 AudioEngine 的 ffmpeg 滤镜链（映射集中在
 * `AudioEngine.applyAudioEffectsToFilters()`），所以每个回包都带 `realtimeApplied = true`；
 * 有 ffmpeg 才成立——没装 ffmpeg 时引擎是 Java Sound 回退，音效全部不生效，
 * 此时 `realtimeApplied` 与 `supportsEffects` 一起告诉前端要提示用户。
 *
 * 哪些参数真正改变声音由 [AudioEffectsCapabilities] 决定并原样下发，
 * 前端不需要自己维护一份清单。协议见 desktop/docs/protocol-audio-effects.md。
 *
 * 外放（扬声器）优化整块放在 `sound.speaker` 里，走 native 的 SPEAKER_* 六个槽位；
 * 因为它有独立开关，[AudioEffectsSection] 里也给它留了一个分区，可以单独
 * `audioEffects.setSection {section:"speaker"}` 开关或恢复默认。
 */
object AudioEffectsApi {

    fun register(server: CoreServer, container: AppContainer) {
        val player = container.player

        // 整份 DSP 参数的写入口（播放页/均衡器界面用它，等价于 updateSound 的整条替换）。
        // 放在这里而不是 PlayerApi：音效参数的收口处只有一个，避免两条命令语义分叉。
        server.on("player.setAudioEffects") { params ->
            val sound = parseSound(params["sound"])
            withContext(Dispatchers.IO) { player.setAudioEffects(sound) }
            snapshot(player)
        }

        // 当前音效参数快照 + 频响曲线采样点
        server.on("audioEffects.get") { snapshot(player) }

        // 更新音效参数：给整条 sound 就替换，只给部分字段就按字段合并（其余保持原值）
        server.on("audioEffects.updateSound") { params ->
            if (!params.containsKey("sound") && !hasInlineFields(params)) {
                badRequest("缺少 sound 参数")
            }
            val updated = if (params.containsKey("sound")) {
                parseSound(params.objectOrNull("sound"))
            } else {
                merge(player.audioEffects.value, params)
            }
            withContext(Dispatchers.IO) { player.setAudioEffects(updated) }
            snapshot(player)
        }

        // 分区开关 / 恢复默认：等价于 updateSound 的一次局部变换
        server.on("audioEffects.setSection") { params ->
            val section = AudioEffectsSection.fromWireValue(params.stringOrNull("section"))
                ?: badRequest("缺少或非法 section（equalizer/tone/space/dynamics/speaker）")
            val enabled = params.boolOrNull("enabled")
            val reset = params.boolOrNull("reset") ?: false
            if (enabled == null && !reset) badRequest("缺少 enabled 或 reset")
            withContext(Dispatchers.IO) {
                player.updateAudioEffects { current ->
                    var next = current
                    if (reset) next = next.withSectionDefaults(section)
                    if (enabled != null) next = next.withSectionEnabled(section, enabled)
                    next
                }
            }
            snapshot(player)
        }

        // 总开关：整体旁路 DSP
        server.on("audioEffects.setEnabled") { params ->
            val enabled = params.boolOrNull("enabled") ?: badRequest("缺少 enabled")
            withContext(Dispatchers.IO) { player.updateAudioEffects { it.copy(enabled = enabled) } }
            snapshot(player)
        }

        // 恢复默认：把整份参数重置为出厂默认值
        server.on("audioEffects.reset") {
            withContext(Dispatchers.IO) { player.setAudioEffects(AudioEffectsSound()) }
            snapshot(player)
        }

        // 解析 Equalizer APO / AutoEQ 的 ParametricEQ 文本；apply=true 时同时落盘并生效
        server.on("audioEffects.importAutoEq") { params ->
            val text = params.stringOrNull("text") ?: badRequest("缺少 text 参数")
            val apply = params.boolOrNull("apply") ?: false
            // AutoEQ 只覆盖均衡曲线，音色/空间/动态分区保留用户当前设置
            val result = runCatching {
                AutoEqParser.import(text, player.audioEffects.value)
            }.getOrElse { error ->
                throw ProtocolException("bad_request", error.message ?: "AutoEQ 解析失败")
            }
            if (apply) {
                withContext(Dispatchers.IO) { player.setAudioEffects(result.sound) }
                importResponse(result, player.audioEffectsUpdatedAt, appliedNow(player, result.sound))
            } else {
                importResponse(result, 0L, appliedNow(player, result.sound))
            }
        }

        // 只算曲线不落盘：UI 调整参数时用它做实时预览
        server.on("audioEffects.response") { params ->
            val sound = params.objectOrNull("sound")?.let { parseSound(it) } ?: player.audioEffects.value
            val points = params.intOrNull("points") ?: AudioEffectsDsp.DEFAULT_POINTS
            buildJsonObject {
                put("realtimeApplied", appliedNow(player, sound))
                put("sampleRate", AudioEffectsDsp.SAMPLE_RATE)
                put("points", encodeJson(AudioEffectsDsp.frequencyResponse(sound, points)))
                put("sound", encodeJson(sound.normalized()))
            }
        }
    }

    /** 读取当前参数的一体化回包；updatedAt 取自内存，避免每次拖滑块都读一遍配置文件。 */
    private fun snapshot(player: PlayerManager): JsonObject {
        val sound = player.audioEffects.value
        return soundResponse(sound, player.audioEffectsUpdatedAt, appliedNow(player, sound))
    }

    private fun appliedNow(player: PlayerManager, sound: AudioEffectsSound): Boolean =
        realtimeApplied(player.supportsEffects, sound)

    /** 完整的音效快照：参数 + 曲线采样点 + 能力表。 */
    private fun soundResponse(
        sound: AudioEffectsSound,
        updatedAt: Long,
        realtimeApplied: Boolean,
    ): JsonObject = buildJsonObject {
        put("realtimeApplied", realtimeApplied)
        put("updatedAt", updatedAt)
        put("sound", encodeJson(sound.normalized()))
        put("graphicFrequencies", encodeJson(GRAPHIC_EQ_FREQUENCIES))
        put("points", encodeJson(AudioEffectsDsp.frequencyResponse(sound)))
        put("sampleRate", AudioEffectsDsp.SAMPLE_RATE)
        put("maxParametricBands", AudioEffectsSound.MAX_PARAMETRIC_BANDS)
        put("capabilities", encodeJson(AudioEffectsCapabilities.entries))
    }

    /**
     * `realtimeApplied` 的真实语义是「这组参数现在会改变声音吗」：
     * 需要引擎支持音效（ffmpeg 可用）**且** DSP 总开关与至少一个分区打开。
     * 引擎不支持时前端要显示「未安装 ffmpeg」而不是「参数已保存」。
     */
    private fun realtimeApplied(supportsEffects: Boolean, sound: AudioEffectsSound): Boolean {
        if (!supportsEffects) return false
        val value = sound.normalized()
        return value.enabled &&
            (value.equalizerEnabled || value.toneEnabled || value.spaceEnabled ||
                value.dynamicsEnabled || value.speaker.enabled)
    }

    private fun importResponse(
        result: AutoEqImportResult,
        persistedAt: Long,
        realtimeApplied: Boolean,
    ): JsonObject = buildJsonObject {
        put("realtimeApplied", realtimeApplied)
        put("source", result.source)
        put("imported", result.imported)
        put("skipped", result.skipped)
        put("warnings", encodeJson(result.warnings))
        put("persisted", persistedAt > 0L)
        put("updatedAt", persistedAt)
        put("sound", encodeJson(result.sound))
        put("points", encodeJson(AudioEffectsDsp.frequencyResponse(result.sound)))
        put("capabilities", encodeJson(AudioEffectsCapabilities.entries))
    }

    private fun parseSound(raw: JsonElement?): AudioEffectsSound {
        if (raw == null) badRequest("sound 参数格式无效")
        return runCatching { WireJson.decodeFromJsonElement<AudioEffectsSound>(raw) }
            .getOrElse { badRequest("sound 参数格式无效") }
            .normalized()
    }

    /**
     * 判断这次 updateSound 是「局部更新」而不是「整条替换」。
     * 与 [merge] 支持的字段一一对应：新增可局部更新的字段时两处一起改，
     * 否则前端按字段下发会被当成「缺少 sound 参数」而报错。
     */
    private fun hasInlineFields(params: JsonObject): Boolean = INLINE_FIELDS.any { params.containsKey(it) }

    private val INLINE_FIELDS = listOf(
        "enabled",
        "equalizerEnabled",
        "toneEnabled",
        "spaceEnabled",
        "dynamicsEnabled",
        "preampDb",
        "graphicBandsDb",
        "parametricEnabled",
        "parametricBands",
        "bassDb",
        "bassFrequencyHz",
        "trebleDb",
        "trebleFrequencyHz",
        "clarity",
        "clarityFrequencyHz",
        "stereoWidth",
        "monoBassHz",
        "mono",
        "swapChannels",
        "outputGainDb",
        // 扬声器优化（整块下发；六个参数与开关打包在 speaker 对象里）
        "speaker",
        "ditherEnabled",
        "qualityMode",
        "speedPitchLinked",
    )

    /**
     * 把「只改一部分」的请求合并到当前参数上。
     * 滑块一次只改一个值，整体替换会把没提到的字段清成默认值。
     */
    private fun merge(current: AudioEffectsSound, params: JsonObject): AudioEffectsSound {
        var sound = current
        params.boolOrNull("enabled")?.let { sound = sound.copy(enabled = it) }
        params.boolOrNull("equalizerEnabled")?.let { sound = sound.copy(equalizerEnabled = it) }
        params.boolOrNull("toneEnabled")?.let { sound = sound.copy(toneEnabled = it) }
        params.boolOrNull("spaceEnabled")?.let { sound = sound.copy(spaceEnabled = it) }
        params.boolOrNull("dynamicsEnabled")?.let { sound = sound.copy(dynamicsEnabled = it) }
        params.double("preampDb")?.let { sound = sound.copy(preampDb = it) }
        params.double("bassDb")?.let { sound = sound.copy(bassDb = it) }
        params.double("bassFrequencyHz")?.let { sound = sound.copy(bassFrequencyHz = it) }
        params.double("trebleDb")?.let { sound = sound.copy(trebleDb = it) }
        params.double("trebleFrequencyHz")?.let { sound = sound.copy(trebleFrequencyHz = it) }
        params.double("clarity")?.let { sound = sound.copy(clarity = it) }
        params.double("clarityFrequencyHz")?.let { sound = sound.copy(clarityFrequencyHz = it) }
        params.double("stereoWidth")?.let { sound = sound.copy(stereoWidth = it) }
        params.double("monoBassHz")?.let { sound = sound.copy(monoBassHz = it) }
        params.boolOrNull("mono")?.let { sound = sound.copy(mono = it) }
        params.boolOrNull("swapChannels")?.let { sound = sound.copy(swapChannels = it) }
        params.double("outputGainDb")?.let { sound = sound.copy(outputGainDb = it) }
        params.boolOrNull("parametricEnabled")?.let { sound = sound.copy(parametricEnabled = it) }
        params.boolOrNull("ditherEnabled")?.let { sound = sound.copy(ditherEnabled = it) }
        params.stringOrNull("qualityMode")?.let { sound = sound.copy(qualityMode = it) }
        params.boolOrNull("speedPitchLinked")?.let { sound = sound.copy(speedPitchLinked = it) }
        params.objectOrNull("speaker")?.let { raw ->
            sound = sound.copy(speaker = mergeSpeaker(sound.speaker, raw))
        }
        (params["graphicBandsDb"] as? JsonArray)?.let { array ->
            val gains = array.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
            sound = sound.copy(
                graphicBandsDb = List(GRAPHIC_EQ_FREQUENCIES.size) { index ->
                    gains.getOrNull(index) ?: sound.graphicBandsDb.getOrElse(index) { 0.0 }
                },
            )
        }
        (params["parametricBands"] as? JsonArray)?.let { array ->
            val bands = array.mapNotNull { element ->
                runCatching { WireJson.decodeFromJsonElement<ParametricEqBand>(element) }.getOrNull()
            }
            if (bands.size != array.size) badRequest("parametricBands 中存在格式无效的段")
            sound = sound.copy(parametricBands = bands)
        }
        return sound.normalized()
    }

    /** 读一个数值型参数；写成字符串（QML 的滑块）也能接受。 */
    private fun JsonObject.double(key: String): Double? =
        (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

    /**
     * 合并外放优化参数：`speaker` 整块下发，但允许只给部分字段
     * （QML 拖一个滑块就只发一个值），未提到的字段沿用当前值。
     */
    private fun mergeSpeaker(current: SpeakerOptimizer, raw: JsonElement): SpeakerOptimizer {
        val object_ = raw as? JsonObject ?: badRequest("speaker 参数格式无效")
        var speaker = current
        object_.boolOrNull("enabled")?.let { speaker = speaker.copy(enabled = it) }
        object_.stringOrNull("size")?.let { speaker = speaker.copy(size = SpeakerSize.fromWireValue(it)) }
        object_.double("protectionHz")?.let { speaker = speaker.copy(protectionHz = it) }
        object_.double("bassHarmonics")?.let { speaker = speaker.copy(bassHarmonics = it) }
        object_.double("loudness")?.let { speaker = speaker.copy(loudness = it) }
        object_.double("clarity")?.let { speaker = speaker.copy(clarity = it) }
        object_.double("stereoExpand")?.let { speaker = speaker.copy(stereoExpand = it) }
        return speaker.normalized()
    }
}
