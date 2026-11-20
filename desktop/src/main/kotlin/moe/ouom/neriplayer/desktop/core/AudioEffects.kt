package moe.ouom.neriplayer.desktop.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.math.pow

/**
 * DSP 音效的数据模型、AutoEQ / Equalizer APO 文本解析与频响计算。
 *
 * 参数有两条落地路径，二者互斥：
 * - **native DSP**（默认）：[AudioEffectsSound.toDspParams] 打包成 104 个 float 交给
 *   `lib_neri.so`（见 [NativeDsp]），10 个处理阶段覆盖均衡/音色/声场/动态全部参数；
 * - **ffmpeg 滤镜**（回退）：`AudioEngine.applyAudioEffectsToFilters()` 用滤镜近似，
 *   只能表达均衡/低音高音/声道矩阵/输出增益，混响、压缩、限幅等标为未生效。
 *
 * [AudioEffectsCapabilities] 按当前加载状态给出能力表，界面据此标注「未生效」。
 *
 * 存储刻意独立于 AppSettings：音效参数是叠加在现有 EQ 之上的一层可选配置，
 * 单独文件便于整体替换/撤销，也避免给 Storage.kt 加字段时的兼容迁移。
 */

/** 图形均衡器的 10 个中心频率（与 AudioEngine.EQ_FREQUENCIES 同一条 1/1 倍频程序列，保留小数便于插值）。 */
val GRAPHIC_EQ_FREQUENCIES: List<Double> =
    listOf(31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

/** 前级增益范围（与上游 Android 一致：-12 ~ +6 dB）。 */
const val PREAMP_MIN_DB: Double = -12.0
const val PREAMP_MAX_DB: Double = 6.0

/**
 * 倍速 / 音调的取值边界。
 *
 * 这里刻意与 `Player.setSpeed` 的夹取范围（0.5~3.0）保持一致，而不是照抄上游滑块的
 * 0.25~4.0：后端会夹取到 0.5~3.0，界面若给出 0.25 只会得到「拖了没变化」的假象。
 */
const val MIN_SPEED: Double = 0.5
const val MAX_SPEED: Double = 3.0
const val MIN_PITCH_SEMITONE: Double = -12.0
const val MAX_PITCH_SEMITONE: Double = 12.0

/**
 * 可以单独开关、单独恢复默认的音色分区（对齐上游 Android 的 AudioEffectsSoundSection）。
 * 关闭某个分区时只把它的参数当默认值处理，用户调好的数值仍留在设置里。
 */
@Serializable
enum class AudioEffectsSection {
    @SerialName("equalizer") EQUALIZER,
    @SerialName("tone") TONE,
    @SerialName("space") SPACE,
    @SerialName("dynamics") DYNAMICS,
    @SerialName("speaker") SPEAKER;

    /** 界面展示用的中文名（英文界面由 translations.js 覆盖）。 */
    val label: String
        get() = when (this) {
            EQUALIZER -> "均衡器"
            TONE -> "音色"
            SPACE -> "空间"
            DYNAMICS -> "动态"
            SPEAKER -> "扬声器"
        }

    companion object {
        /** 协议里用序列化名（小写）传分区；未知值返回 null 由调用方报 bad_request。 */
        fun fromWireValue(value: String?): AudioEffectsSection? = when (value?.trim()?.lowercase()) {
            "equalizer" -> EQUALIZER
            "tone" -> TONE
            "space" -> SPACE
            "dynamics" -> DYNAMICS
            "speaker" -> SPEAKER
            else -> null
        }
    }
}

/**
 * 单个参数的支持情况：`applied = false` 的参数会存下来但不影响声音。
 *
 * 前端据此在控件旁标注「未生效」，后端也在 `audioEffects.get` 里下发，
 * 避免界面自己维护一份「哪些参数能用」的清单而与引擎实现漂移。
 */
@Serializable
data class AudioEffectCapability(
    val key: String,
    val section: AudioEffectsSection,
    val applied: Boolean,
    /** 未生效的原因（中文，可直接展示）。 */
    val reason: String = "",
)

/** 参数均衡器的滤波器类型（序列化名沿用 Equalizer APO 的小写写法，前后端与协议文档共用）。 */
@Serializable
enum class ParametricEqBandType {
    @SerialName("peak") PEAK,
    @SerialName("low_shelf") LOW_SHELF,
    @SerialName("high_shelf") HIGH_SHELF,
    @SerialName("low_pass") LOW_PASS,
    @SerialName("high_pass") HIGH_PASS,
    @SerialName("notch") NOTCH,
    @SerialName("band_pass") BAND_PASS;

    /** 界面展示用的中文名（英文界面由 translations.js 覆盖）。 */
    val label: String
        get() = when (this) {
            PEAK -> "峰值"
            LOW_SHELF -> "低频搁架"
            HIGH_SHELF -> "高频搁架"
            LOW_PASS -> "低通"
            HIGH_PASS -> "高通"
            NOTCH -> "陷波"
            BAND_PASS -> "带通"
        }
}

/** 扬声器尺寸档位：决定「低频保护」自动取多少 Hz（对齐上游 SpeakerSize）。 */
@Serializable
enum class SpeakerSize {
    @SerialName("phone") PHONE,
    @SerialName("tablet") TABLET,
    @SerialName("large") LARGE;

    /** 该档位自动使用的低频保护频率。 */
    val protectionHz: Double
        get() = when (this) {
            PHONE -> 180.0
            TABLET -> 120.0
            LARGE -> 70.0
        }

    companion object {
        fun fromWireValue(value: String?): SpeakerSize = when (value?.trim()?.lowercase()) {
            "tablet" -> TABLET
            "large" -> LARGE
            else -> PHONE
        }
    }
}

/**
 * 外放（扬声器）优化。对齐上游 `SpeakerOptimizerSettings` 的六个参数，
 * 由 native DSP 的 SPEAKER_* 六个槽位实现（见 neri_dsp_params.h）。
 *
 * 桌面侧没有「检测当前输出路由」的能力，所以这里不做手机上的
 * 「仅在扬声器外放时生效」判断，只提供一个用户可见的总开关。
 */
@Serializable
data class SpeakerOptimizer(
    val enabled: Boolean = false,
    val size: SpeakerSize = SpeakerSize.PHONE,
    /** 低频保护频率；< 40 Hz 表示跟随 [size] 自动取值。 */
    val protectionHz: Double = 0.0,
    /** 谐波低音补偿力度（0~1）。 */
    val bassHarmonics: Double = 0.5,
    /** 分频段响度提升（0~1）。 */
    val loudness: Double = 0.4,
    /** 外放清晰度（0~1）。 */
    val clarity: Double = 0.3,
    /** 立体声扩展（0~1）。 */
    val stereoExpand: Double = 0.3,
) {
    fun normalized(): SpeakerOptimizer = copy(
        protectionHz = if (protectionHz.isFinite() && protectionHz >= 40.0) {
            protectionHz.coerceAtMost(400.0)
        } else {
            0.0
        },
        bassHarmonics = bassHarmonics.finiteOr(0.5).coerceIn(0.0, 1.0),
        loudness = loudness.finiteOr(0.4).coerceIn(0.0, 1.0),
        clarity = clarity.finiteOr(0.3).coerceIn(0.0, 1.0),
        stereoExpand = stereoExpand.finiteOr(0.3).coerceIn(0.0, 1.0),
    )

    /** 实际送进 DSP 的保护频率：0 表示按尺寸档位自动。 */
    fun effectiveProtectionHz(): Double =
        if (protectionHz >= 40.0) protectionHz else size.protectionHz
}

@Serializable
data class ParametricEqBand(
    val enabled: Boolean = true,
    val type: ParametricEqBandType = ParametricEqBandType.PEAK,
    val frequencyHz: Double = 1000.0,
    val gainDb: Double = 0.0,
    val q: Double = 0.707,
) {
    fun normalized(): ParametricEqBand = copy(
        frequencyHz = frequencyHz.finiteOr(1000.0).coerceIn(20.0, 20000.0),
        gainDb = gainDb.finiteOr(0.0).coerceIn(-30.0, 24.0),
        q = q.finiteOr(0.707).coerceIn(0.1, 20.0),
    )
}

/**
 * 一条音效曲线。字段与上游 Android（`AudioEffectsSound`，104 个参数）同名同义，
 * 分五个分区：EQUALIZER / TONE / SPACE / DYNAMICS / SPEAKER。
 *
 * 图形段与参数段是两条互斥的曲线来源：AutoEQ 的 ParametricEQ 与 GraphicEQ 只取其一，
 * 同时叠加会让校正量翻倍，所以导入时总是整体替换另一侧。
 *
 * 与上游的差异只有两处，都是为了桌面侧的可迁移性：
 * - 没有 `speed/pitch` 一类播放控制字段（桌面侧在 AppSettings 里，且与音效无关）；
 * - 新增 [enabled] 总开关（整体旁路 DSP）。
 */
@Serializable
data class AudioEffectsSound(
    /** 总开关：关闭时播放链完全不挂 DSP 滤镜。 */
    val enabled: Boolean = true,
    // ---------------- EQUALIZER ----------------
    val preampDb: Double = 0.0,
    val equalizerEnabled: Boolean = true,
    val graphicBandsDb: List<Double> = List(GRAPHIC_EQ_FREQUENCIES.size) { 0.0 },
    val parametricEnabled: Boolean = false,
    val parametricBands: List<ParametricEqBand> = emptyList(),
    // ---------------- TONE ----------------
    val toneEnabled: Boolean = true,
    val bassDb: Double = 0.0,
    val bassFrequencyHz: Double = 100.0,
    val trebleDb: Double = 0.0,
    val trebleFrequencyHz: Double = 8000.0,
    val virtualBass: Double = 0.0,
    val virtualBassFrequencyHz: Double = 100.0,
    val warmth: Double = 0.0,
    val clarity: Double = 0.0,
    val clarityFrequencyHz: Double = 5000.0,
    val vocal: Double = 0.0,
    val vocalRemoval: Double = 0.0,
    // ---------------- SPACE ----------------
    val spaceEnabled: Boolean = true,
    val stereoWidth: Double = 1.0,
    val monoBassHz: Double = 0.0,
    val mono: Boolean = false,
    val swapChannels: Boolean = false,
    val crossfeed: Double = 0.0,
    val surround: Double = 0.0,
    val reverb: Double = 0.0,
    val reverbRoom: Double = 0.5,
    val reverbDamping: Double = 0.5,
    val reverbPreDelayMs: Double = 10.0,
    // ---------------- DYNAMICS ----------------
    val dynamicsEnabled: Boolean = true,
    val compressorEnabled: Boolean = false,
    val compressorThresholdDb: Double = -18.0,
    val compressorRatio: Double = 2.0,
    val compressorAttackMs: Double = 10.0,
    val compressorReleaseMs: Double = 150.0,
    val compressorKneeDb: Double = 6.0,
    val compressorMakeupDb: Double = 0.0,
    val outputGainDb: Double = 0.0,
    val limiterEnabled: Boolean = true,
    val limiterCeilingDb: Double = -1.0,
    val limiterReleaseMs: Double = 80.0,
    // ---------------- SPEAKER ----------------
    /** 外放优化：六个参数与开关，由 native DSP 的 SPEAKER_* 槽位实现。 */
    val speaker: SpeakerOptimizer = SpeakerOptimizer(),
    // ---------------- 高级 ----------------
    /** 是否写入 dither（16bit 输出下高档位才有意义）。 */
    val ditherEnabled: Boolean = true,
    /** native 质量档：eco / balanced / high（对应 QualityMode 0/1/2）。 */
    val qualityMode: String = "balanced",
    /** 倍速与音调联动（唱片效果）：音调随倍速一起变。 */
    val speedPitchLinked: Boolean = true,
    /**
     * 倍速分区开关。关闭时播放回到 1.0x / 0 半音，关闭前的值记在
     * [storedSpeed] / [storedPitch] 里，重新打开时恢复。
     *
     * 这几个字段不影响声音处理本身，只是跟着音效配置一起落盘——
     * 桌面侧没有单独存放「音效分区的倍速快照」的地方，放这里比新开一个配置文件简单。
     */
    val speedEnabled: Boolean = true,
    /** 上次关闭倍速分区时的播放速度（恢复用）。 */
    val storedSpeed: Double = 1.0,
    /** 上次关闭倍速分区时的音调（半音，恢复用）。 */
    val storedPitch: Double = 0.0,
) {
    fun normalized(): AudioEffectsSound = copy(
        speaker = speaker.normalized(),
        storedSpeed = storedSpeed.finiteOr(1.0).coerceIn(MIN_SPEED, MAX_SPEED),
        storedPitch = storedPitch.finiteOr(0.0).coerceIn(MIN_PITCH_SEMITONE, MAX_PITCH_SEMITONE),
        qualityMode = when (qualityMode.trim().lowercase()) {
            "eco" -> "eco"
            "high" -> "high"
            else -> "balanced"
        },
        preampDb = preampDb.finiteOr(0.0).coerceIn(PREAMP_MIN_DB, PREAMP_MAX_DB),
        graphicBandsDb = List(GRAPHIC_EQ_FREQUENCIES.size) { index ->
            (graphicBandsDb.getOrNull(index) ?: 0.0).finiteOr(0.0).coerceIn(-12.0, 12.0)
        },
        parametricBands = parametricBands.take(MAX_PARAMETRIC_BANDS).map { it.normalized() },
        bassDb = bassDb.finiteOr(0.0).coerceIn(-12.0, 12.0),
        bassFrequencyHz = bassFrequencyHz.finiteOr(100.0).coerceIn(40.0, 250.0),
        trebleDb = trebleDb.finiteOr(0.0).coerceIn(-12.0, 12.0),
        trebleFrequencyHz = trebleFrequencyHz.finiteOr(8000.0).coerceIn(2000.0, 14000.0),
        virtualBass = virtualBass.finiteOr(0.0).coerceIn(0.0, 1.0),
        virtualBassFrequencyHz = virtualBassFrequencyHz.finiteOr(100.0).coerceIn(50.0, 200.0),
        warmth = warmth.finiteOr(0.0).coerceIn(0.0, 1.0),
        clarity = clarity.finiteOr(0.0).coerceIn(0.0, 1.0),
        clarityFrequencyHz = clarityFrequencyHz.finiteOr(5000.0).coerceIn(2000.0, 10000.0),
        vocal = vocal.finiteOr(0.0).coerceIn(0.0, 1.0),
        vocalRemoval = vocalRemoval.finiteOr(0.0).coerceIn(0.0, 1.0),
        stereoWidth = stereoWidth.finiteOr(1.0).coerceIn(0.0, 2.0),
        // 0 与 40Hz 都表示「不做低切」，中间按 40~250Hz 夹取
        monoBassHz = if (monoBassHz.isFinite() && monoBassHz >= 40.0) monoBassHz.coerceAtMost(250.0) else 0.0,
        crossfeed = crossfeed.finiteOr(0.0).coerceIn(0.0, 1.0),
        surround = surround.finiteOr(0.0).coerceIn(0.0, 1.0),
        reverb = reverb.finiteOr(0.0).coerceIn(0.0, 1.0),
        reverbRoom = reverbRoom.finiteOr(0.5).coerceIn(0.0, 1.0),
        reverbDamping = reverbDamping.finiteOr(0.5).coerceIn(0.0, 1.0),
        reverbPreDelayMs = reverbPreDelayMs.finiteOr(10.0).coerceIn(0.0, 100.0),
        compressorThresholdDb = compressorThresholdDb.finiteOr(-18.0).coerceIn(-50.0, 0.0),
        compressorRatio = compressorRatio.finiteOr(2.0).coerceIn(1.0, 20.0),
        compressorAttackMs = compressorAttackMs.finiteOr(10.0).coerceIn(0.5, 100.0),
        compressorReleaseMs = compressorReleaseMs.finiteOr(150.0).coerceIn(20.0, 1000.0),
        compressorKneeDb = compressorKneeDb.finiteOr(6.0).coerceIn(0.0, 18.0),
        compressorMakeupDb = compressorMakeupDb.finiteOr(0.0).coerceIn(0.0, 18.0),
        outputGainDb = outputGainDb.finiteOr(0.0).coerceIn(-12.0, 12.0),
        limiterCeilingDb = limiterCeilingDb.finiteOr(-1.0).coerceIn(-6.0, 0.0),
        limiterReleaseMs = limiterReleaseMs.finiteOr(80.0).coerceIn(20.0, 500.0),
    )

    fun isSectionEnabled(section: AudioEffectsSection): Boolean = when (section) {
        AudioEffectsSection.EQUALIZER -> equalizerEnabled
        AudioEffectsSection.TONE -> toneEnabled
        AudioEffectsSection.SPACE -> spaceEnabled
        AudioEffectsSection.DYNAMICS -> dynamicsEnabled
        AudioEffectsSection.SPEAKER -> speaker.enabled
    }

    fun withSectionEnabled(section: AudioEffectsSection, value: Boolean): AudioEffectsSound = when (section) {
        AudioEffectsSection.EQUALIZER -> copy(equalizerEnabled = value)
        AudioEffectsSection.TONE -> copy(toneEnabled = value)
        AudioEffectsSection.SPACE -> copy(spaceEnabled = value)
        AudioEffectsSection.DYNAMICS -> copy(dynamicsEnabled = value)
        AudioEffectsSection.SPEAKER -> copy(speaker = speaker.copy(enabled = value))
    }

    /** 把一个分区的全部调节恢复默认，并重新打开该分区。 */
    fun withSectionDefaults(section: AudioEffectsSection): AudioEffectsSound {
        val defaults = AudioEffectsSound()
        return when (section) {
            AudioEffectsSection.EQUALIZER -> copy(
                equalizerEnabled = defaults.equalizerEnabled,
                preampDb = defaults.preampDb,
                graphicBandsDb = defaults.graphicBandsDb,
                parametricEnabled = defaults.parametricEnabled,
                parametricBands = defaults.parametricBands,
            )
            AudioEffectsSection.TONE -> copy(
                toneEnabled = defaults.toneEnabled,
                bassDb = defaults.bassDb,
                bassFrequencyHz = defaults.bassFrequencyHz,
                trebleDb = defaults.trebleDb,
                trebleFrequencyHz = defaults.trebleFrequencyHz,
                virtualBass = defaults.virtualBass,
                virtualBassFrequencyHz = defaults.virtualBassFrequencyHz,
                warmth = defaults.warmth,
                clarity = defaults.clarity,
                clarityFrequencyHz = defaults.clarityFrequencyHz,
                vocal = defaults.vocal,
                vocalRemoval = defaults.vocalRemoval,
            )
            AudioEffectsSection.SPACE -> copy(
                spaceEnabled = defaults.spaceEnabled,
                stereoWidth = defaults.stereoWidth,
                monoBassHz = defaults.monoBassHz,
                mono = defaults.mono,
                swapChannels = defaults.swapChannels,
                crossfeed = defaults.crossfeed,
                surround = defaults.surround,
                reverb = defaults.reverb,
                reverbRoom = defaults.reverbRoom,
                reverbDamping = defaults.reverbDamping,
                reverbPreDelayMs = defaults.reverbPreDelayMs,
            )
            AudioEffectsSection.DYNAMICS -> copy(
                dynamicsEnabled = defaults.dynamicsEnabled,
                compressorEnabled = defaults.compressorEnabled,
                compressorThresholdDb = defaults.compressorThresholdDb,
                compressorRatio = defaults.compressorRatio,
                compressorAttackMs = defaults.compressorAttackMs,
                compressorReleaseMs = defaults.compressorReleaseMs,
                compressorKneeDb = defaults.compressorKneeDb,
                compressorMakeupDb = defaults.compressorMakeupDb,
                outputGainDb = defaults.outputGainDb,
                limiterEnabled = defaults.limiterEnabled,
                limiterCeilingDb = defaults.limiterCeilingDb,
                limiterReleaseMs = defaults.limiterReleaseMs,
            )
            AudioEffectsSection.SPEAKER -> copy(speaker = defaults.speaker)
        }
    }

    /**
     * 实际送进 DSP 的音色：关闭的分区按默认值处理，用户的调节仍保存在设置里。
     * 与上游 `AudioEffectsSound.effective()` 语义一致。
     */
    fun effective(): AudioEffectsSound =
        AudioEffectsSection.entries.fold(this) { sound, section ->
            if (sound.isSectionEnabled(section)) sound else sound.withSectionDefaults(section)
        }

    companion object {
        const val MAX_PARAMETRIC_BANDS: Int = 10
    }
}

// ---------------------------------------------------------------- native DSP 参数布局

/**
 * 与 native `dsp/neri_dsp_params.h` 逐项对应的 104 个下标。
 *
 * 这个布局是 JNI 的 ABI：下标错一位就是"调 A 响 B"，而 native 侧只检查数组长度
 * （`native_dsp_engine.cpp` 里有 `static_assert(kBassGainDb == 70 && kStereoWidth == 81)`），
 * 所以这里照抄上游 `NeriDspParams.kt`，并在 [neutralDspParams] 里用同样的下标做缺省值。
 */
object NeriDspParams {
    const val COUNT = 104
    const val GRAPHIC_BAND_COUNT = 10
    const val PARAMETRIC_BAND_COUNT = 10
    const val PARAMETRIC_BAND_STRIDE = 5

    const val MASTER_ENABLED = 0
    const val QUALITY_MODE = 1
    const val PREAMP_DB = 2
    const val OUTPUT_GAIN_DB = 3
    const val LIMITER_ENABLED = 4
    const val LIMITER_CEILING_DB = 5
    const val LIMITER_RELEASE_MS = 6
    const val DITHER_ENABLED = 7
    const val GRAPHIC_EQ_ENABLED = 8
    const val GRAPHIC_EQ_GAIN_0 = 9
    const val PARAMETRIC_EQ_ENABLED = GRAPHIC_EQ_GAIN_0 + GRAPHIC_BAND_COUNT
    const val PARAMETRIC_BAND_0 = PARAMETRIC_EQ_ENABLED + 1
    const val BAND_ENABLED = 0
    const val BAND_TYPE = 1
    const val BAND_FREQUENCY_HZ = 2
    const val BAND_GAIN_DB = 3
    const val BAND_Q = 4
    const val BASS_GAIN_DB = PARAMETRIC_BAND_0 + PARAMETRIC_BAND_COUNT * PARAMETRIC_BAND_STRIDE
    const val BASS_FREQUENCY_HZ = BASS_GAIN_DB + 1
    const val TREBLE_GAIN_DB = BASS_GAIN_DB + 2
    const val TREBLE_FREQUENCY_HZ = BASS_GAIN_DB + 3
    const val VIRTUAL_BASS_AMOUNT = BASS_GAIN_DB + 4
    const val VIRTUAL_BASS_FREQUENCY_HZ = BASS_GAIN_DB + 5
    const val WARMTH_AMOUNT = BASS_GAIN_DB + 6
    const val EXCITER_AMOUNT = BASS_GAIN_DB + 7
    const val EXCITER_FREQUENCY_HZ = BASS_GAIN_DB + 8
    const val VOCAL_CLARITY_AMOUNT = BASS_GAIN_DB + 9
    const val VOCAL_REMOVAL_AMOUNT = BASS_GAIN_DB + 10
    const val STEREO_WIDTH = BASS_GAIN_DB + 11
    const val MONO_BASS_FREQUENCY_HZ = BASS_GAIN_DB + 12
    const val MONO_MIX = BASS_GAIN_DB + 13
    const val CHANNEL_SWAP = BASS_GAIN_DB + 14
    const val CROSSFEED_AMOUNT = BASS_GAIN_DB + 15
    const val SURROUND_AMOUNT = BASS_GAIN_DB + 16
    const val REVERB_AMOUNT = BASS_GAIN_DB + 17
    const val REVERB_ROOM_SIZE = BASS_GAIN_DB + 18
    const val REVERB_DAMPING = BASS_GAIN_DB + 19
    const val REVERB_PRE_DELAY_MS = BASS_GAIN_DB + 20
    const val COMPRESSOR_ENABLED = BASS_GAIN_DB + 21
    const val COMPRESSOR_THRESHOLD_DB = BASS_GAIN_DB + 22
    const val COMPRESSOR_RATIO = BASS_GAIN_DB + 23
    const val COMPRESSOR_ATTACK_MS = BASS_GAIN_DB + 24
    const val COMPRESSOR_RELEASE_MS = BASS_GAIN_DB + 25
    const val COMPRESSOR_KNEE_DB = BASS_GAIN_DB + 26
    const val COMPRESSOR_MAKEUP_DB = BASS_GAIN_DB + 27
    const val SPEAKER_ENABLED = BASS_GAIN_DB + 28
    const val SPEAKER_HIGH_PASS_HZ = BASS_GAIN_DB + 29
    const val SPEAKER_BASS_HARMONICS = BASS_GAIN_DB + 30
    const val SPEAKER_LOUDNESS = BASS_GAIN_DB + 31
    const val SPEAKER_CLARITY = BASS_GAIN_DB + 32
    const val SPEAKER_STEREO_EXPAND = BASS_GAIN_DB + 33
}

/** native 的 PCM 编码常量，与 Android `AudioFormat` 同值（引擎按这套魔数解码）。 */
object NeriDspEncoding {
    const val PCM_16BIT = 2
    const val PCM_FLOAT = 4
    const val PCM_24BIT = 21
    const val PCM_32BIT = 22
}

/** native 质量档（`QualityMode`）：Eco 0 / Balanced 1 / High 2。 */
private const val DSP_QUALITY_BALANCED = 1f

/** 扬声器优化关闭时的中性缺省：保留上游的 180 Hz 保护高通，但 SPEAKER_ENABLED 恒为 0 时不起作用。 */
private const val NEUTRAL_SPEAKER_HIGH_PASS_HZ = 180f

/** [AudioEffectsSound.qualityMode] → native `QualityMode`（Eco 0 / Balanced 1 / High 2）。 */
private fun qualityModeValue(mode: String): Float = when (mode.trim().lowercase()) {
    "eco" -> 0f
    "high" -> 2f
    else -> DSP_QUALITY_BALANCED
}

/**
 * native 的中性参数（逐项对照上游 `neutralDspParams()`）。
 *
 * **不能拿 `FloatArray(104)` 当默认值**：这个数组里近 20 个位置的非零缺省值都是
 * "不处理"的必要条件，最典型的是 `STEREO_WIDTH = 1`（0 会让 native 把 side 归零，
 * 也就是强制单声道）；其余还有限幅器三件套、10 段参数 EQ 的频率/Q、低音/高音/
 * 虚拟低音/激励器中心频率、压缩器 6 个参数。
 */
fun neutralDspParams(): FloatArray = FloatArray(NeriDspParams.COUNT).also { params ->
    params[NeriDspParams.QUALITY_MODE] = DSP_QUALITY_BALANCED
    params[NeriDspParams.LIMITER_ENABLED] = 1f
    params[NeriDspParams.LIMITER_CEILING_DB] = -1f
    params[NeriDspParams.LIMITER_RELEASE_MS] = 80f
    repeat(NeriDspParams.PARAMETRIC_BAND_COUNT) { band ->
        val base = NeriDspParams.PARAMETRIC_BAND_0 + band * NeriDspParams.PARAMETRIC_BAND_STRIDE
        params[base + NeriDspParams.BAND_FREQUENCY_HZ] = 1_000f
        params[base + NeriDspParams.BAND_Q] = 0.707f
    }
    params[NeriDspParams.BASS_FREQUENCY_HZ] = 100f
    params[NeriDspParams.TREBLE_FREQUENCY_HZ] = 8_000f
    params[NeriDspParams.VIRTUAL_BASS_FREQUENCY_HZ] = 100f
    params[NeriDspParams.EXCITER_FREQUENCY_HZ] = 5_000f
    params[NeriDspParams.STEREO_WIDTH] = 1f
    params[NeriDspParams.REVERB_ROOM_SIZE] = 0.5f
    params[NeriDspParams.REVERB_DAMPING] = 0.5f
    params[NeriDspParams.COMPRESSOR_THRESHOLD_DB] = -18f
    params[NeriDspParams.COMPRESSOR_RATIO] = 2f
    params[NeriDspParams.COMPRESSOR_ATTACK_MS] = 10f
    params[NeriDspParams.COMPRESSOR_RELEASE_MS] = 150f
    params[NeriDspParams.COMPRESSOR_KNEE_DB] = 6f
    params[NeriDspParams.SPEAKER_HIGH_PASS_HZ] = NEUTRAL_SPEAKER_HIGH_PASS_HZ
}

private fun FloatArray.flag(index: Int, value: Boolean) {
    this[index] = if (value) 1f else 0f
}

private fun Double.dspValue(fallback: Double): Float {
    val value = finiteOr(fallback).toFloat()
    return if (value.isFinite()) value else fallback.toFloat()
}

/**
 * [AudioEffectsSound] → 104 个 native 参数。
 *
 * 与上游 `AudioEffectsSettings.resolveDsp()` + `writeSound/writeCharacter/writeSpace/writeDynamics`
 * 是同一套映射：
 * - 分区开关关闭时按默认值处理（[AudioEffectsSound.effective]），与 ffmpeg 回退路径语义一致；
 * - `clarity`/`clarityFrequencyHz` 对应 native 的 exciter（激励器），`vocal` 对应人声清晰度、
 *   `vocalRemoval` 对应中置抵消；
 * - 扬声器优化只在 [SpeakerOptimizer.enabled] 为 true 时写 SPEAKER_* 六个槽位，否则
 *   `SPEAKER_ENABLED = 0`（native 整段旁路）；
 * - 质量档与 dither 由 [AudioEffectsSound.qualityMode] / [AudioEffectsSound.ditherEnabled] 决定。
 *
 * 结果数组会先取 [neutralDspParams] 再逐项覆盖，因此未映射的字段保持上游中性值。
 */
fun AudioEffectsSound.toDspParams(): FloatArray {
    val value = normalized().effective()
    val params = neutralDspParams()
    params.flag(NeriDspParams.MASTER_ENABLED, enabled)
    params[NeriDspParams.QUALITY_MODE] = qualityModeValue(value.qualityMode)
    // dither 只在 16bit 编码下才有意义；Eco 档关闭它以省 CPU
    params.flag(NeriDspParams.DITHER_ENABLED, value.ditherEnabled && value.qualityMode != "eco")

    // ---------------- EQUALIZER ----------------
    params[NeriDspParams.PREAMP_DB] = preampDb.dspValue(0.0)
    params[NeriDspParams.OUTPUT_GAIN_DB] = outputGainDb.dspValue(0.0)
    params.flag(NeriDspParams.GRAPHIC_EQ_ENABLED, equalizerEnabled)
    repeat(NeriDspParams.GRAPHIC_BAND_COUNT) { index ->
        val gain = graphicBandsDb.getOrNull(index) ?: 0.0
        params[NeriDspParams.GRAPHIC_EQ_GAIN_0 + index] = gain.dspValue(0.0)
    }
    params.flag(NeriDspParams.PARAMETRIC_EQ_ENABLED, parametricEnabled)
    parametricBands.take(NeriDspParams.PARAMETRIC_BAND_COUNT).forEachIndexed { index, band ->
        val base = NeriDspParams.PARAMETRIC_BAND_0 + index * NeriDspParams.PARAMETRIC_BAND_STRIDE
        params.flag(base + NeriDspParams.BAND_ENABLED, band.enabled)
        // 枚举的声明顺序与 native FilterType（Peak=0 … BandPass=6）一一对应
        params[base + NeriDspParams.BAND_TYPE] = band.type.ordinal.toFloat()
        params[base + NeriDspParams.BAND_FREQUENCY_HZ] = band.frequencyHz.dspValue(1000.0)
        params[base + NeriDspParams.BAND_GAIN_DB] = band.gainDb.dspValue(0.0)
        params[base + NeriDspParams.BAND_Q] = band.q.dspValue(0.707)
    }

    // ---------------- TONE ----------------
    params[NeriDspParams.BASS_GAIN_DB] = bassDb.dspValue(0.0)
    params[NeriDspParams.BASS_FREQUENCY_HZ] = bassFrequencyHz.dspValue(100.0)
    params[NeriDspParams.TREBLE_GAIN_DB] = trebleDb.dspValue(0.0)
    params[NeriDspParams.TREBLE_FREQUENCY_HZ] = trebleFrequencyHz.dspValue(8000.0)
    params[NeriDspParams.VIRTUAL_BASS_AMOUNT] = virtualBass.dspValue(0.0)
    params[NeriDspParams.VIRTUAL_BASS_FREQUENCY_HZ] = virtualBassFrequencyHz.dspValue(100.0)
    params[NeriDspParams.WARMTH_AMOUNT] = warmth.dspValue(0.0)
    params[NeriDspParams.EXCITER_AMOUNT] = clarity.dspValue(0.0)
    params[NeriDspParams.EXCITER_FREQUENCY_HZ] = clarityFrequencyHz.dspValue(5000.0)
    params[NeriDspParams.VOCAL_CLARITY_AMOUNT] = vocal.dspValue(0.0)

    // ---------------- SPACE ----------------
    params[NeriDspParams.VOCAL_REMOVAL_AMOUNT] = vocalRemoval.dspValue(0.0)
    params[NeriDspParams.STEREO_WIDTH] = stereoWidth.dspValue(1.0)
    params[NeriDspParams.MONO_BASS_FREQUENCY_HZ] = monoBassHz.dspValue(0.0)
    params.flag(NeriDspParams.MONO_MIX, mono)
    params.flag(NeriDspParams.CHANNEL_SWAP, swapChannels)
    params[NeriDspParams.CROSSFEED_AMOUNT] = crossfeed.dspValue(0.0)
    params[NeriDspParams.SURROUND_AMOUNT] = surround.dspValue(0.0)
    params[NeriDspParams.REVERB_AMOUNT] = reverb.dspValue(0.0)
    params[NeriDspParams.REVERB_ROOM_SIZE] = reverbRoom.dspValue(0.5)
    params[NeriDspParams.REVERB_DAMPING] = reverbDamping.dspValue(0.5)
    params[NeriDspParams.REVERB_PRE_DELAY_MS] = reverbPreDelayMs.dspValue(10.0)

    // ---------------- DYNAMICS ----------------
    params.flag(NeriDspParams.COMPRESSOR_ENABLED, compressorEnabled)
    params[NeriDspParams.COMPRESSOR_THRESHOLD_DB] = compressorThresholdDb.dspValue(-18.0)
    params[NeriDspParams.COMPRESSOR_RATIO] = compressorRatio.dspValue(2.0)
    params[NeriDspParams.COMPRESSOR_ATTACK_MS] = compressorAttackMs.dspValue(10.0)
    params[NeriDspParams.COMPRESSOR_RELEASE_MS] = compressorReleaseMs.dspValue(150.0)
    params[NeriDspParams.COMPRESSOR_KNEE_DB] = compressorKneeDb.dspValue(6.0)
    params[NeriDspParams.COMPRESSOR_MAKEUP_DB] = compressorMakeupDb.dspValue(0.0)
    params.flag(NeriDspParams.LIMITER_ENABLED, limiterEnabled)
    params[NeriDspParams.LIMITER_CEILING_DB] = limiterCeilingDb.dspValue(-1.0)
    params[NeriDspParams.LIMITER_RELEASE_MS] = limiterReleaseMs.dspValue(80.0)

    // ---------------- SPEAKER ----------------
    // 关闭时不写 SPEAKER_* 的力度值：native 在 SPEAKER_ENABLED=0 时整段旁路，
    // 保留中性值可以让「打开开关」那一刻的起点可预期。
    if (value.speaker.enabled) {
        params.flag(NeriDspParams.SPEAKER_ENABLED, true)
        params[NeriDspParams.SPEAKER_HIGH_PASS_HZ] = value.speaker.effectiveProtectionHz().toFloat()
        params[NeriDspParams.SPEAKER_BASS_HARMONICS] = value.speaker.bassHarmonics.dspValue(0.5)
        params[NeriDspParams.SPEAKER_LOUDNESS] = value.speaker.loudness.dspValue(0.4)
        params[NeriDspParams.SPEAKER_CLARITY] = value.speaker.clarity.dspValue(0.3)
        params[NeriDspParams.SPEAKER_STEREO_EXPAND] = value.speaker.stereoExpand.dspValue(0.3)
    }
    return params
}

/**
 * 引擎对每个 DSP 参数的支持情况。
 *
 * 这张表是「模型 → 声音」的能力声明：协议按它告诉前端哪些控件真正起作用，
 * 引擎也按它决定挂哪些 ffmpeg 滤镜。新增参数时两处一起改，避免界面与声音不一致。
 *
 * native DSP 引擎可用时几乎全部参数都由它实现（虚拟低音、温暖度、人声、混响、
 * 压缩、限幅都在 C++ 里）；只有在 native 库加载失败、回退到 ffmpeg 滤镜时，
 * 才把 ffmpeg 表达不了的那些标成未生效。因此 [entries] 是按当前加载状态求值的
 * getter 而不是常量——同一台机器上装/不装 lib_neri.so 会得到不同的能力表。
 */
object AudioEffectsCapabilities {

    private fun applied(key: String, section: AudioEffectsSection) =
        AudioEffectCapability(key = key, section = section, applied = true)

    private fun unsupported(key: String, section: AudioEffectsSection, reason: String) =
        AudioEffectCapability(key = key, section = section, applied = false, reason = reason)

    /** 当前参数表：native 可用时全部 applied，否则只有 ffmpeg 滤镜能表达的那部分。 */
    val entries: List<AudioEffectCapability>
        get() = buildEntries(NativeDsp.isAvailable)

    /** 目前真正作用于声音的参数集合，供引擎快速判断是否需要挂滤镜。 */
    val appliedKeys: Set<String>
        get() = entries.asSequence().filter { it.applied }.map { it.key }.toSet()

    fun isApplied(key: String): Boolean = appliedKeys.contains(key)

    private fun buildEntries(native: Boolean): List<AudioEffectCapability> = listOf(
        // EQUALIZER
        applied("preampDb", AudioEffectsSection.EQUALIZER),
        applied("graphicBandsDb", AudioEffectsSection.EQUALIZER),
        applied("parametricBands", AudioEffectsSection.EQUALIZER),

        // TONE
        applied("bassDb", AudioEffectsSection.TONE),
        applied("bassFrequencyHz", AudioEffectsSection.TONE),
        applied("trebleDb", AudioEffectsSection.TONE),
        applied("trebleFrequencyHz", AudioEffectsSection.TONE),
        if (native) {
            applied("virtualBass", AudioEffectsSection.TONE)
        } else {
            unsupported("virtualBass", AudioEffectsSection.TONE, "虚拟低音需要谐波合成，ffmpeg 无等价滤镜")
        },
        applied("clarity", AudioEffectsSection.TONE),
        applied("clarityFrequencyHz", AudioEffectsSection.TONE),
        if (native) {
            applied("warmth", AudioEffectsSection.TONE)
        } else {
            unsupported("warmth", AudioEffectsSection.TONE, "温暖度需要带饱和的非线性处理，ffmpeg 无等价滤镜")
        },
        if (native) {
            applied("vocal", AudioEffectsSection.TONE)
        } else {
            unsupported("vocal", AudioEffectsSection.TONE, "人声增强需要频率相关的动态处理，ffmpeg 无等价滤镜")
        },
        if (native) {
            applied("vocalRemoval", AudioEffectsSection.TONE)
        } else {
            unsupported("vocalRemoval", AudioEffectsSection.TONE, "人声消除需要中置声道分离，ffmpeg 无等价滤镜")
        },

        // SPACE
        applied("stereoWidth", AudioEffectsSection.SPACE),
        applied("mono", AudioEffectsSection.SPACE),
        applied("swapChannels", AudioEffectsSection.SPACE),
        applied("monoBassHz", AudioEffectsSection.SPACE),
        if (native) {
            applied("crossfeed", AudioEffectsSection.SPACE)
        } else {
            unsupported("crossfeed", AudioEffectsSection.SPACE, "交叉馈送需要双耳延时网络，ffmpeg 的 crossfeed 参数语义不同")
        },
        if (native) {
            applied("surround", AudioEffectsSection.SPACE)
        } else {
            unsupported("surround", AudioEffectsSection.SPACE, "环绕需要多抽头延时网络，滤镜串难以稳定实现")
        },
        if (native) {
            applied("reverb", AudioEffectsSection.SPACE)
        } else {
            unsupported("reverb", AudioEffectsSection.SPACE, "混响需要梳状/全通网络，ffmpeg 的 aecho 不等价")
        },

        // DYNAMICS
        if (native) {
            applied("compressor", AudioEffectsSection.DYNAMICS)
        } else {
            unsupported("compressor", AudioEffectsSection.DYNAMICS, "压缩器会与音量归一化互相干扰，ffmpeg 滤镜链未接入")
        },
        if (native) {
            applied("limiter", AudioEffectsSection.DYNAMICS)
        } else {
            unsupported("limiter", AudioEffectsSection.DYNAMICS, "限幅器未接入，防削波仍由输出增益控制")
        },
        applied("outputGainDb", AudioEffectsSection.DYNAMICS),

        // SPEAKER
        // 外放优化是 native 独占的一组处理（谐波低音补偿、分频段响度、立体声扩展），
        // ffmpeg 回退路径无法表达；关闭开关时把整组标为未生效更诚实。
        if (native) {
            applied("speaker", AudioEffectsSection.SPEAKER)
        } else {
            unsupported("speaker", AudioEffectsSection.SPEAKER, "外放优化由 native DSP 实现，ffmpeg 滤镜链无等价方案")
        },
    )
}

/** 音效参数文件的顶层结构；[version] 留给后续迁移用。 */
@Serializable
data class AudioEffectsSettings(
    val version: Int = 1,
    val updatedAt: Long = 0L,
    val sound: AudioEffectsSound = AudioEffectsSound(),
)

/**
 * 旧均衡器设置（`AppSettings.equalizerEnabled/Preset/Bands`）迁移到 DSP 模型。
 *
 * 判断依据是时间戳而不是内容：只要音效文件从未保存过（[AudioEffectsSettings.updatedAt] 为 0），
 * 首次启动时就用旧的十段增益初始化 DSP；一旦用户在新面板里保存过，旧值就不再覆盖它。
 * 这样既不会让老用户丢失已调的曲线，也不会在用户已经用了新面板后又被旧值回滚。
 *
 * 旧预设名（平直/摇滚/…）不写进 DSP 模型：它只是界面标签，曲线本身已经由 [bands] 承载。
 */
fun migrateLegacyEqualizer(enabled: Boolean, bands: List<Float>): AudioEffectsSound {
    val gains = List(GRAPHIC_EQ_FREQUENCIES.size) { index ->
        (bands.getOrNull(index) ?: 0f).toDouble().finiteOr(0.0).coerceIn(-12.0, 12.0)
    }
    return AudioEffectsSound(
        enabled = true,
        equalizerEnabled = enabled,
        graphicBandsDb = gains,
    ).normalized()
}

/** 音效参数存储：configDir 下的独立 JSON（不进 AppSettings，避免动 Storage.kt）。 */
object AudioEffectsStore {

    val file: File get() = File(AppDirs.configDir, "audio-effects.json")

    private val store by lazy {
        JsonFileStore(file, AudioEffectsSettings.serializer()) { AudioEffectsSettings() }
    }

    fun load(): AudioEffectsSettings = store.load()

    fun current(): AudioEffectsSound = load().sound.normalized()

    /**
     * 读取当前参数；如果从未保存过而调用方提供了旧均衡器设置，则用旧值作为初始曲线
     * （迁移只在「从没保存过」时发生，不覆盖用户已有的 DSP 配置）。
     */
    fun currentOrMigrate(legacy: LegacyEqualizer?): AudioEffectsSound {
        val settings = load()
        if (settings.updatedAt > 0L || legacy == null) return settings.sound.normalized()
        return migrateLegacyEqualizer(legacy.enabled, legacy.bands)
    }

    fun save(sound: AudioEffectsSound): AudioEffectsSettings {
        val settings = AudioEffectsSettings(
            updatedAt = System.currentTimeMillis(),
            sound = sound.normalized(),
        )
        store.save(settings)
        return settings
    }
}

/** 旧「均衡器」设置的快照，仅用于迁移。 */
data class LegacyEqualizer(
    val enabled: Boolean,
    val preset: String,
    val bands: List<Float>,
)

/** 频响曲线上的一个采样点。 */
@Serializable
data class FrequencyResponsePoint(
    val frequencyHz: Double,
    val gainDb: Double,
)

/** AutoEQ 文本导入结果。 */
@Serializable
data class AutoEqImportResult(
    /** 解析出来的整条曲线（已归一化），可直接作为当前参数。 */
    val sound: AudioEffectsSound,
    /** 曲线来源："parametric"（Filter 行）或 "graphic"（GraphicEQ）。 */
    val source: String,
    /** 实际导入的段数（GraphicEQ 为原始频点数）。 */
    val imported: Int,
    /** 被跳过的行数（类型不支持、超过 10 段等）。 */
    val skipped: Int,
    val warnings: List<String>,
)

private fun Double.finiteOr(fallback: Double): Double = if (isFinite()) this else fallback

// ------------------------------------------------------------------ 频响计算

/**
 * RBJ biquad 频响计算。
 *
 * 与参考实现（NeriPlayer-Desktop `model.ts`）保持同一套系数与采样率，
 * 只用于「画曲线」：它描述这组参数在理想 biquad 下的合成响应，
 * 不代表当前 ffmpeg 滤镜链的真实输出（ffmpeg 的 Q 语义与段间叠加略有不同）。
 *
 * 参与画曲线的是「稳态频响」这一层：图形/参数均衡、前级增益、低音/高音搁架、
 * 清晰度、输出增益。混响、压缩、限幅、虚拟低音等随时间变化的处理不进曲线
 * （它们影响的是动态与空间感，不是静态幅频响应），避免「曲线动了但听不出变化」。
 */
object AudioEffectsDsp {

    const val SAMPLE_RATE: Double = 48_000.0

    /** 图形均衡每段的 Q，与参考实现一致；比 ffmpeg 默认 Q 更接近「图形 EQ」听感。 */
    private const val GRAPHIC_EQ_Q = 1.41

    /** 低音/高音搁架的 Q：ffmpeg `bass`/`treble` 默认 width_type=q、width=0.5 对应的口径。 */
    private const val SHELF_Q = 0.707

    /** 曲线采样点数（对数频率轴，20 Hz ~ 20 kHz）。 */
    const val DEFAULT_POINTS: Int = 128

    private const val MIN_FREQUENCY_HZ = 20.0
    private const val MAX_FREQUENCY_HZ = 20_000.0

    fun frequencyResponse(
        sound: AudioEffectsSound,
        points: Int = DEFAULT_POINTS,
    ): List<FrequencyResponsePoint> {
        val filters = effectiveFilters(sound)
        val offsetDb = flatGainDb(sound)
        val count = points.coerceIn(2, 512)
        return List(count) { index ->
            val ratio = index.toDouble() / (count - 1)
            val frequency = MIN_FREQUENCY_HZ * (MAX_FREQUENCY_HZ / MIN_FREQUENCY_HZ).pow(ratio)
            val gain = filters.sumOf { magnitudeDb(it, frequency) } + offsetDb
            FrequencyResponsePoint(frequencyHz = frequency, gainDb = gain)
        }
    }

    /** 单个频点的合成增益（dB），滑块实时预览用不到，但测试与 UI 刻度需要统一口径。 */
    fun gainAt(sound: AudioEffectsSound, frequencyHz: Double): Double =
        effectiveFilters(sound).sumOf { magnitudeDb(it, frequencyHz) } + flatGainDb(sound)

    /** 只有真正生效的参数参与计算；分区关闭时按默认值处理。 */
    private fun effectiveFilters(sound: AudioEffectsSound): List<Biquad> {
        val value = sound.normalized().effective()
        if (!value.enabled) return emptyList()
        return buildList {
            if (value.equalizerEnabled) {
                value.graphicBandsDb.forEachIndexed { index, gain ->
                    // 0 dB 的段对响应没有贡献，省一次计算
                    if (gain != 0.0) {
                        add(biquad(ParametricEqBandType.PEAK, GRAPHIC_EQ_FREQUENCIES[index], gain, GRAPHIC_EQ_Q))
                    }
                }
                if (value.parametricEnabled) {
                    for (band in value.parametricBands) {
                        if (band.enabled) add(biquad(band.type, band.frequencyHz, band.gainDb, band.q))
                    }
                }
            }
            if (value.toneEnabled) {
                if (value.bassDb != 0.0) {
                    add(biquad(ParametricEqBandType.LOW_SHELF, value.bassFrequencyHz, value.bassDb, SHELF_Q))
                }
                if (value.trebleDb != 0.0) {
                    add(biquad(ParametricEqBandType.HIGH_SHELF, value.trebleFrequencyHz, value.trebleDb, SHELF_Q))
                }
                // clarity 是 0~1 的力度旋钮，界面上没有增益刻度，按 +0~6 dB 的峰值近似
                if (value.clarity > 0.0) {
                    add(
                        biquad(
                            ParametricEqBandType.PEAK,
                            value.clarityFrequencyHz,
                            value.clarity * 6.0,
                            1.0,
                        ),
                    )
                }
            }
        }
    }

    /** 与频率无关的增益：前级 + 输出增益（音量本身不计入频响曲线）。 */
    private fun flatGainDb(sound: AudioEffectsSound): Double {
        val value = sound.normalized()
        if (!value.enabled) return 0.0
        var total = 0.0
        if (value.equalizerEnabled) total += value.preampDb
        if (value.dynamicsEnabled) total += value.outputGainDb
        return total
    }

    private data class Biquad(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double,
    )

    private fun biquad(
        type: ParametricEqBandType,
        frequencyHz: Double,
        gainDb: Double,
        q: Double,
    ): Biquad {
        // 频率夹在 (5 Hz, 0.45·fs)：越界后 ω 逼近 0 或 π，系数会病态
        val frequency = frequencyHz.coerceIn(5.0, SAMPLE_RATE * 0.45)
        val omega = 2.0 * Math.PI * frequency / SAMPLE_RATE
        val cos = Math.cos(omega)
        val alpha = Math.sin(omega) / (2.0 * q.coerceIn(0.1, 24.0))
        val a = Math.pow(10.0, gainDb / 40.0)
        val root = 2.0 * Math.sqrt(a) * alpha
        return when (type) {
            ParametricEqBandType.PEAK -> normalize(
                1 + alpha * a, -2 * cos, 1 - alpha * a,
                1 + alpha / a, -2 * cos, 1 - alpha / a,
            )
            ParametricEqBandType.LOW_SHELF -> normalize(
                a * (a + 1 - (a - 1) * cos + root), 2 * a * (a - 1 - (a + 1) * cos),
                a * (a + 1 - (a - 1) * cos - root),
                a + 1 + (a - 1) * cos + root, -2 * (a - 1 + (a + 1) * cos),
                a + 1 + (a - 1) * cos - root,
            )
            ParametricEqBandType.HIGH_SHELF -> normalize(
                a * (a + 1 + (a - 1) * cos + root), -2 * a * (a - 1 + (a + 1) * cos),
                a * (a + 1 + (a - 1) * cos - root),
                a + 1 - (a - 1) * cos + root, 2 * (a - 1 - (a + 1) * cos),
                a + 1 - (a - 1) * cos - root,
            )
            ParametricEqBandType.LOW_PASS -> normalize(
                (1 - cos) / 2, 1 - cos, (1 - cos) / 2,
                1 + alpha, -2 * cos, 1 - alpha,
            )
            ParametricEqBandType.HIGH_PASS -> normalize(
                (1 + cos) / 2, -(1 + cos), (1 + cos) / 2,
                1 + alpha, -2 * cos, 1 - alpha,
            )
            ParametricEqBandType.NOTCH -> normalize(
                1.0, -2 * cos, 1.0,
                1 + alpha, -2 * cos, 1 - alpha,
            )
            ParametricEqBandType.BAND_PASS -> normalize(
                alpha, 0.0, -alpha,
                1 + alpha, -2 * cos, 1 - alpha,
            )
        }
    }

    private fun normalize(
        b0: Double, b1: Double, b2: Double,
        a0: Double, a1: Double, a2: Double,
    ): Biquad = Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

    private fun magnitudeDb(filter: Biquad, frequencyHz: Double): Double {
        val omega = 2.0 * Math.PI * frequencyHz / SAMPLE_RATE
        val cos1 = Math.cos(omega)
        val cos2 = Math.cos(2 * omega)
        val sin1 = Math.sin(omega)
        val sin2 = Math.sin(2 * omega)
        val numeratorReal = filter.b0 + filter.b1 * cos1 + filter.b2 * cos2
        val numeratorImaginary = -filter.b1 * sin1 - filter.b2 * sin2
        val denominatorReal = 1.0 + filter.a1 * cos1 + filter.a2 * cos2
        val denominatorImaginary = -filter.a1 * sin1 - filter.a2 * sin2
        val numerator = numeratorReal * numeratorReal + numeratorImaginary * numeratorImaginary
        val denominator = denominatorReal * denominatorReal + denominatorImaginary * denominatorImaginary
        if (numerator <= 0.0 || denominator <= 0.0) return -120.0
        return 10.0 * Math.log10(numerator / denominator)
    }
}

// ------------------------------------------------------------------ AutoEQ 解析
/**
 * Equalizer APO / AutoEQ 文本解析。
 *
 * 支持的语法：
 * - `Preamp: -6.0 dB`
 * - `Filter 1: ON PK Fc 105 Hz Gain -3.5 dB Q 1.20`
 *   类型：PK/PEQ、LS/LSC/LSQ、HS/HSC/HSQ、LP/LPQ、HP/HPQ、NO、BP；
 *   `Filter:` 省略序号也可以；搁架类允许用 `S`（斜率）代替 `Q`。
 * - `GraphicEQ: 20 -1.5; 21 -1.3; ...`（成对频点，按对数域插值到 10 段）
 *
 * 不支持的指令（Include、Copy、Channel 等）与未知滤波器类型跳过并写 warning，
 * 而不是整份报错：AutoEQ 导出的文件常带一些与本应用无关的行。
 */
object AutoEqParser {

    /** 128 KiB：足够放几十段滤波器的配置，也挡住误粘贴大文件。 */
    const val MAX_TEXT_BYTES: Int = 128 * 1024

    private const val NUMBER = """[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?"""

    private val preampPattern = Regex("""^preamp\s*:\s*($NUMBER)\s*dB\s*$""", RegexOption.IGNORE_CASE)
    private val graphicPattern = Regex("""^graphiceq\s*:(.*)$""", RegexOption.IGNORE_CASE)
    private val filterCommand = Regex("""^filter(?:\s|\d|:|$)""", RegexOption.IGNORE_CASE)
    private val filterPattern = Regex("""^filter\s*\d*\s*:\s*(on|off)\s+([a-z]+)\b(.*)$""", RegexOption.IGNORE_CASE)
    private val graphicPoint = Regex("""^\s*($NUMBER)\s+($NUMBER)\s*$""")
    private val nonFinite = Regex("""\b(?:NaN|Infinity)\b""", RegexOption.IGNORE_CASE)

    /** Equalizer APO 的滤波器缩写 → 本应用的滤波器类型。 */
    private val apoTypes: Map<String, ParametricEqBandType> = mapOf(
        "PK" to ParametricEqBandType.PEAK,
        "PEQ" to ParametricEqBandType.PEAK,
        "LS" to ParametricEqBandType.LOW_SHELF,
        "LSC" to ParametricEqBandType.LOW_SHELF,
        "LSQ" to ParametricEqBandType.LOW_SHELF,
        "HS" to ParametricEqBandType.HIGH_SHELF,
        "HSC" to ParametricEqBandType.HIGH_SHELF,
        "HSQ" to ParametricEqBandType.HIGH_SHELF,
        "LP" to ParametricEqBandType.LOW_PASS,
        "LPQ" to ParametricEqBandType.LOW_PASS,
        "HP" to ParametricEqBandType.HIGH_PASS,
        "HPQ" to ParametricEqBandType.HIGH_PASS,
        "NO" to ParametricEqBandType.NOTCH,
        "BP" to ParametricEqBandType.BAND_PASS,
    )

    /**
     * 解析一份配置文本。
     *
     * @param base 非均衡分区（音色/空间/动态）的保留来源：AutoEQ 只负责均衡曲线，
     *   导入不应把用户已经调好的低音/高音/立体声等设置一起清成默认值。
     * @throws IllegalArgumentException 文本为空/超限、含 NaN/Infinity、格式非法，
     *   或 GraphicEQ 与 Filter 同时出现（无法合成一条曲线）。
     */
    fun import(text: String, base: AudioEffectsSound = AudioEffectsSound()): AutoEqImportResult {
        val content = prepare(text)
        val lines = content.split(Regex("""\r?\n"""))
            .map { it.replace(Regex("""\s*#.*$"""), "").trim() }
            .filter { it.isNotEmpty() }

        val warnings = mutableListOf<String>()
        var preampDb = 0.0
        var preampFound = false
        var skipped = 0
        val bands = mutableListOf<ParametricEqBand>()
        var graphic: String? = null

        for (line in lines) {
            if (nonFinite.containsMatchIn(line)) {
                throw IllegalArgumentException("AutoEQ 参数必须是有限数值")
            }
            if (line.startsWith("preamp", ignoreCase = true) && line.contains(':')) {
                val match = preampPattern.matchEntire(line)
                    ?: throw IllegalArgumentException("前级增益格式无效：$line")
                if (preampFound) warnings += "存在多个前级增益，以最后一个为准"
                preampFound = true
                preampDb = clamp(importedNumber(match.groupValues[1], "前级增益"), PREAMP_MIN_DB, PREAMP_MAX_DB, "前级增益", warnings)
                continue
            }
            val graphicMatch = graphicPattern.matchEntire(line)
            if (graphicMatch != null) {
                if (graphic != null) throw IllegalArgumentException("只支持一个 GraphicEQ 定义")
                graphic = graphicMatch.groupValues[1]
                continue
            }
            if (!filterCommand.containsMatchIn(line)) {
                warnings += "已忽略不支持的指令：" + line.take(80)
                continue
            }
            val filter = filterPattern.matchEntire(line)
                ?: throw IllegalArgumentException("Filter 格式无效：$line")
            val type = apoTypes[filter.groupValues[2].uppercase()]
            if (type == null) {
                skipped++
                warnings += "已跳过不支持的滤波器类型 " + filter.groupValues[2]
                continue
            }
            val tail = filter.groupValues[3]
            val gainType = type == ParametricEqBandType.PEAK ||
                type == ParametricEqBandType.LOW_SHELF ||
                type == ParametricEqBandType.HIGH_SHELF
            val frequencyHz = readParameter(tail, "Fc", "Hz", required = true)
                ?: throw IllegalArgumentException("Filter 缺少 Fc 频率：$line")
            if (frequencyHz <= 0.0) throw IllegalArgumentException("滤波器频率必须大于 0")
            // 搁架滤波器在 APO 里用 Q 或 S（斜率）描述，取到哪个都当 Q 用，缺省按 0.707
            val qValue = readParameter(tail, "Q", "", required = false)
                ?: readParameter(tail, "S", "", required = false)
                ?: 0.707
            val band = ParametricEqBand(
                enabled = filter.groupValues[1].equals("on", ignoreCase = true),
                type = type,
                frequencyHz = clamp(frequencyHz, 20.0, 20000.0, "滤波器频率", warnings),
                gainDb = if (gainType) {
                    clamp(
                        readParameter(tail, "Gain", "dB", required = true)
                            ?: throw IllegalArgumentException("Filter 缺少 Gain 增益：$line"),
                        -30.0, 24.0, "滤波器增益", warnings,
                    )
                } else {
                    0.0
                },
                q = clamp(qValue, 0.1, 20.0, "滤波器 Q 值", warnings),
            )
            if (bands.size >= AudioEffectsSound.MAX_PARAMETRIC_BANDS) {
                skipped++
                warnings += "参数均衡最多 ${AudioEffectsSound.MAX_PARAMETRIC_BANDS} 段，已跳过额外滤波器"
            } else {
                bands += band
            }
        }

        val graphicText = graphic
        if (graphicText != null) {
            if (bands.isNotEmpty()) throw IllegalArgumentException("不能同时导入 GraphicEQ 和参数 Filter")
            val points = graphicText.split(';')
                .filter { it.isNotBlank() }
                .map { entry ->
                    val match = graphicPoint.matchEntire(entry)
                        ?: throw IllegalArgumentException("GraphicEQ 频率和增益格式无效：${entry.trim()}")
                    val frequency = importedNumber(match.groupValues[1], "GraphicEQ 频率")
                    if (frequency <= 0.0) throw IllegalArgumentException("GraphicEQ 频率必须大于 0")
                    frequency to importedNumber(match.groupValues[2], "GraphicEQ 增益")
                }
                .sortedBy { it.first }
            if (points.size < 2 || points.zipWithNext().any { (left, right) -> left.first == right.first }) {
                throw IllegalArgumentException("GraphicEQ 至少需要两个不同频率的点")
            }
            val gains = GRAPHIC_EQ_FREQUENCIES.map { center ->
                clamp(interpolateLog(center, points), -12.0, 12.0, "$center Hz 增益", warnings)
            }
            return AutoEqImportResult(
                sound = base.copy(
                    enabled = true,
                    preampDb = preampDb,
                    equalizerEnabled = true,
                    graphicBandsDb = gains,
                    parametricBands = emptyList(),
                    parametricEnabled = false,
                ).normalized(),
                source = "graphic",
                imported = points.size,
                skipped = skipped,
                warnings = warnings,
            )
        }

        if (bands.isEmpty()) throw IllegalArgumentException("未找到有效的 AutoEQ 滤波器或 GraphicEQ")
        return AutoEqImportResult(
            sound = base.copy(
                enabled = true,
                preampDb = preampDb,
                equalizerEnabled = true,
                graphicBandsDb = List(GRAPHIC_EQ_FREQUENCIES.size) { 0.0 },
                parametricBands = bands,
                parametricEnabled = true,
            ).normalized(),
            source = "parametric",
            imported = bands.size,
            skipped = skipped,
            warnings = warnings,
        )
    }

    private fun prepare(text: String): String {
        if (text.isBlank()) throw IllegalArgumentException("导入内容为空")
        val trimmed = text.trim().removePrefix("\uFEFF")
        if (trimmed.length > MAX_TEXT_BYTES || trimmed.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) {
            throw IllegalArgumentException("导入内容超过 ${MAX_TEXT_BYTES / 1024} KiB 限制")
        }
        return trimmed
    }

    private fun importedNumber(token: String, label: String): Double {
        val value = token.toDoubleOrNull()
            ?: throw IllegalArgumentException("${label}必须是有限数值")
        if (!value.isFinite()) throw IllegalArgumentException("${label}必须是有限数值")
        return value
    }

    private fun clamp(value: Double, min: Double, max: Double, label: String, warnings: MutableList<String>): Double {
        val clamped = value.coerceIn(min, max)
        if (clamped != value) warnings += "${label}超出范围，已限制到 $clamped"
        return clamped
    }

    /**
     * 在 filter 行剩余文本里读一个 `Key <number>[unit]` 参数。
     * 没有该键时返回 null（可选参数）；键在但数值不合法时直接报错，避免静默套用默认值。
     */
    private fun readParameter(tail: String, key: String, unit: String, required: Boolean): Double? {
        val suffix = if (unit.isBlank()) "" else "\\s*" + Regex.escape(unit)
        val pattern = Regex("""\b$key\s+($NUMBER)$suffix(?=\s|$)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(tail)
        if (match == null) {
            if (required || Regex("""\b$key\b""", RegexOption.IGNORE_CASE).containsMatchIn(tail)) {
                throw IllegalArgumentException("Filter 的 $key 参数无效或缺失")
            }
            return null
        }
        return importedNumber(match.groupValues[1], key)
    }

    /** 对数频率轴上的线性插值；超出两端时取端点值。 */
    private fun interpolateLog(frequency: Double, points: List<Pair<Double, Double>>): Double {
        if (frequency <= points.first().first) return points.first().second
        if (frequency >= points.last().first) return points.last().second
        val index = points.indexOfFirst { it.first >= frequency }
        val (leftHz, leftGain) = points[index - 1]
        val (rightHz, rightGain) = points[index]
        val progress = Math.log(frequency / leftHz) / Math.log(rightHz / leftHz)
        return leftGain + progress * (rightGain - leftGain)
    }
}
