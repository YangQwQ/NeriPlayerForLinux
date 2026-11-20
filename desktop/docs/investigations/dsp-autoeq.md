# DSP 音效编辑器 + AutoEQ 移植可行性调查

> 纯调查文档，不含任何代码改动。参考项目：`/home/yang/Git/NeriPlayer-Desktop`（Tauri + Vue，下称「PC 参考版」）；本项目 `/home/yang/Git/neriplayer`（Kotlin 后端 + Qt/QML）。
> 结论建立在逐行阅读下列源码之上：`DspEqEditor.vue`、`DspFrequencyGraph.vue`、`DspNumberControl.vue`、`AudioEffectsSettings.vue`、`src/modules/audioEffects/model.ts`、`listening.ts`、`src/stores/player.ts`、原生 C++ DSP（`src-tauri/native/dsp/`）、Rust FFI（`src-tauri/src/audio/dsp.rs`、`dsp_settings.rs`）以及本项目 `AudioEngine.kt`、`Player.kt`、`Storage.kt`、`PlayerApi.kt`、`PlayerWire.kt`、`EqualizerPanel.qml`、`EffectsPanel.qml`。

## 0. 一句话结论

PC 参考版比我们多的不是「几个滑块」，而是**一整套从 UI → 设置模型 → 实时 DSP 引擎的音效管线**：106 个参数、10 段图形 EQ + 10 段参数 EQ、低音/高音/虚拟低音/响度补偿/温暖度/激励器/人声/立体声/交叉馈送/环绕/混响/压缩器/限幅器/扬声器优化、频响曲线实时预览、预设导入导出与 Android 互通、AutoEQ/APO 文本导入。我们的 EQ 是**ffmpeg `-af equalizer` 滤镜串 + Java Sound 输出**，只有 10 段固定 Q 的峰值滤波 + 一个 `dynaudnorm` 响度开关，且**每次调参都要重启 ffmpeg 进程**（`restartPreservingPosition()`）。

**移植建议**：UI 层（频响曲线、AutoEQ 文本导入、参数模型）**可做且工作量中等**；真正的「实时高质量 DSP 引擎」（C++/Rust 那 9000+ 行）**移植成本极大、风险高，不建议照搬**。推荐分阶段：先补数据模型与 AutoEQ 导入（纯 Kotlin/JS 算法，无需动音频链），再评估用 ffmpeg 现有滤镜能覆盖多少，最后才决定是否需要引入原生 DSP。

---

## 1. 双方现状对照（先看清基线）

### 1.1 我们现有实现（`desktop/`）

| 层 | 文件 | 现状 |
| --- | --- | --- |
| 引擎 | `core/AudioEngine.kt` | ffmpeg 子进程解码为 s16le PCM → `SourceDataLine` 播放。EQ = `buildFilterChain()` 里拼接 `equalizer=f=<freq>:t=q:w=1.2:g=<dB>`；响度 = `dynaudnorm=f=250:g=15:p=0.9`；变速变调 = atempo/asetrate。 |
| 频段 | 同上 `EQ_FREQUENCIES = "31,62,125,250,500,1000,2000,4000,8000,16000"` | 固定 10 段，固定 Q=1.2，固定中心频率，**不可调、不可增删、无 shelf/低通/高通**。 |
| 参数状态 | `core/Player.kt`、`core/Storage.kt` | 只有 `equalizerEnabled` / `equalizerPreset` / `equalizerBands: List<Float>(10)` / `loudnessEnhancer`。无 preamp、无参数 EQ、无其他音效字段。 |
| 协议 | `backend/api/PlayerApi.kt`、`backend/PlayerWire.kt` | `player.setLoudness` / `setEqualizerEnabled` / `setEqualizerPreset` / `setEqualizerBand`；state 回推 `loudness`、`equalizerEnabled`、`equalizerBands`。 |
| UI | `qt/qml/components/settings/EqualizerPanel.qml`、`components/player/EffectsPanel.qml` | 8 个预设（平直/原声/低音增强/高频增强/人声增强/摇滚/爵士/古典）+ 10 个 `SliderRow` + 响度开关。**预设表在两份 QML 里各写死一份**（注释明确说明「后端只按名保存，不内置预设曲线」）。**无频响曲线可视化、无数值输入框、无自定义预设保存**。 |
| 降级 | `AudioEngine.kt:530,664` | `NoopAudioEngine.supportsEffects=false`，没装 ffmpeg 时全部音效不可用（QML 有对应提示）。 |

关键代码事实：
- `setEqualizer(bands)` / `setLoudness()` / `setSpeed()` / `setPitch()` 都调用 `restartPreservingPosition()`（`AudioEngine.kt:256-287`），即**拖动滑块会重启 ffmpeg 进程**并从当前位置续播——有停顿/状态重建成本，无法做实时连续调节。
- 增益只有 `.1f` 精度（`"%.1f".format(gain)`），gain 绝对值 ≤0.05 的段直接跳过。
- 每个 `equalizer` 滤镜是独立 biquad 峰值段，**相邻段会互相叠加**，没有参考版的「精确模式」补偿。

### 1.2 PC 参考版实现

| 层 | 文件 | 现状 |
| --- | --- | --- |
| 设置模型 | `src/modules/audioEffects/model.ts` | `AudioEffectsSound` 40+ 字段，`AudioEffectsSettings`（version 2）含 main/per-output 配置、用户预设、扬声器优化、电源模式、autoHeadroom。 |
| UI | `components/settings/AudioEffectsSettings.vue` | 简单/专家双模式；预设分类网格、用户预设保存/删除、配置导入导出、AutoEQ 导入、频响曲线、按 section 折叠的几十个控件、运行时统计。 |
| 控件 | `DspEqEditor.vue`、`DspFrequencyGraph.vue`、`DspNumberControl.vue` | 可拖拽曲线编辑器、对数频响图、带数值输入框的滑块。 |
| 算法 | `model.ts` | RBJ biquad 系数、频响计算、正则化最小二乘精确 EQ 补偿、响度补偿、AutoEQ 解析、配置迁移。 |
| 实时引擎 | `src-tauri/native/dsp/*.cpp`（C++17）+ `src-tauri/src/audio/{dsp.rs,dsp_settings.rs,effects.rs}`（Rust FFI） | 106 个参数、预建三档质量引擎、原子参数快照、逐样本处理，单/双声道支持，多声道旁路，实时统计（CPU/限幅/压缩衰减）。 |

---

## 2. 他们到底比我们多什么（具体参数 / 交互 / 可视化）

### 2.1 参数层面的硬差距

`model.ts` 的 `AudioEffectsSound` 与 `SOUND_CONTROLS`（含 min/max/step/unit）逐项列出，我们**一个都没有**的字段：

- **EQ 类**：`preampDb`(-24~6)、`equalizerAccurate`（正则化最小二乘补偿，使曲线真的穿过滑块值）、`parametricEnabled` + 最多 10 段 `parametricBands`（每段 `enabled/type/frequencyHz/gainDb/q`，7 种类型：peak / low_shelf / high_shelf / low_pass / high_pass / notch / band_pass）。我们是 10 段固定 Q 峰值，无 preamp，无参数 EQ。
- **音调类**：`bassDb`+`bassFrequencyHz`、`trebleDb`+`trebleFrequencyHz`、`loudnessCompensation`（按音量等响补偿：音量每降 1 dB 低频补 0.3 dB、高频补 0.1 dB，封顶 10/4 dB）、`virtualBass`+`virtualBassFrequencyHz`、`warmth`、`clarity`+`clarityFrequencyHz`、`vocal`、`vocalRemoval`。我们只有一个 on/off 的 `dynaudnorm`。
- **空间类**：`stereoWidth`、`monoBassHz`、`mono`、`swapChannels`、`crossfeed`、`surround`、`reverb`+`reverbRoom`/`reverbDamping`/`reverbPreDelayMs`。
- **动态类**：`compressorEnabled`+threshold(-50~0)/ratio(1~20)/attack(0.5~100ms)/release(20~1000ms)/knee(0~18)/makeup(0~18)、`outputGainDb`、`limiterEnabled`+ceiling(-6~0)/release(20~500ms)。
- **组织架构**：4 个 section 开关（equalizer/tone/space/dynamics），可整体旁路与重置；`powerMode`(eco/balanced/high)、`autoHeadroom`、`perOutputEnabled` + `outputProfiles`（按输出设备名存不同配置，最多 64 套）、`userPresets`（最多 50 套自定义）。我们是全局单一配置。
- **扬声器优化** `SpeakerOptimizerSettings`：`size`(phone/tablet/large)、`protectionHz`、`bassHarmonics`、`loudness`、`clarity`、`stereoExpand`。
- **其他**：变速/变调（我们有）、**唱片效果 vinyl**（我们没有）、`loudnessGainMb`（我们有响度但语义不同）。

原生 C++ 引擎（`neri_dsp_stages.h`）佐证这些不是空字段，而是真的 DSP 处理链：`FilterBankStage`（图形/参数 EQ）、`BassBoostStage`+次声高通、`HarmonicBassStage`（虚拟低音谐波）、`WarmthStage`、`ExciterStage`、`StereoImageStage`、`CrossfeedStage`、`SurroundStage`（6-tap）、`ReverbStage`（8 comb + 4 allpass）、`CompressorStage`、`SpeakerStage`（分频双带压缩）、`OutputStage`（限幅 + 25ms 平滑）。参数布局固定 106 项，前 104 项与 Android 逐项对齐，末尾 2 项是桌面响度补偿扩展（`static_assert(kParamCount == 106)`）。

### 2.2 交互层面的差距

- **可拖拽频响曲线** `DspEqEditor.vue`：SVG viewBox 600×220，10 列频段与滑块严格对齐，横轴对数刻度（首末频段各外扩半个倍频程），纵轴自动量程（≥12 dB，按 6 dB 递进，上限 30）；按住拖动实时改对应段，`Shift`/`Alt` 切 0.1 dB 细调，双击某段归零；拖动时有 tooltip 与 active 高亮。**我们只有离散滑块，拖动还触发 ffmpeg 重启。**
- **数值直接输入** `DspNumberControl.vue` + `EditableRangeValue`：每个控件既是滑块又是输入框，显示带单位的小数位，`defaultValue` 双击复位，轨道从默认值填充到当前值，changed 状态高亮。我们的 `SliderRow` 只有滑块和格式化显示。
- **简单/专家模式切换** `AudioEffectsSettings.vue:247-250`：简单模式只给 5 个「听感旋钮」（低音/人声/亮度/温暖/空间，`listening.ts` 用非线性映射 + 保护限幅），专家模式才展开全部参数；还有「对比原音」一键旁路。
- **预设体系**：分类 tab（common/all/style/tone/scene/device/user）、内置约 29 个预设、用户预设保存/删除、分享码复制/粘贴/文件导入、Android 备份 JSON 导入（`importAudioEffects` 支持 `moe.ouom.neriplayer.config` 备份、完整设置、预设分享码、PC 导出 profile 四种来源）。我们的 8 个预设写死在 QML 两份。
- **运行时统计**：每秒轮询 `get_audio_effects_stats`，显示 active/bypass、CPU 负载%、采样率、压缩/限幅衰减 dB。
- **设置页内搜索定位**：`settings/searchIndex.ts` 索引 `dsp.autoeq` 等条目，可从搜索直接跳到对应控件并自动展开 details。

### 2.3 可视化层面的差距

- `DspFrequencyGraph.vue`：SVG 600×200，对数频率轴 20Hz–20kHz（标注 20/100/1k/10k/20k），线性 dB 轴自动量程 12–60 dB，画出 `audioEffectsFrequencyResponse()` 的完整合成曲线（含 preamp、图形/参数 EQ、bass/treble、响度补偿等所有生效滤波器叠加）。
- `DspEqEditor.vue`：交互式曲线 + 渐变填充面积 + 各段竖线与圆点 + dB 刻度 + 频段列高亮 + tooltip + 下方 10 段竖向滑块带（responsive，≤680px 缩窄）。
- 我们的 QML **完全没有频响图**，只有一个 10 行 `Repeater` 滑块列表。

---

## 3. AutoEQ 是什么、他们怎么实现的、需要哪些数据

### 3.1 概念

[AutoEQ](https://github.com/jaegerbautz/autoeq) 是开源项目，根据耳机/音箱测量数据生成校正 EQ 配置，常见输出是 **Equalizer APO** 的 `ParametricEQ.txt`，形如：

```
Preamp: -6.0 dB
Filter  1: ON  PK    Fc  105.0 Hz  Gain -3.5 dB  Q  1.20
Filter  2: ON  LS    Fc   60.0 Hz  Gain  4.0 dB  Q  0.70
...
```

也有的输出 `GraphicEQ: <Hz> <dB>; <Hz> <dB>; ...` 数值串。它就是一堆滤波器参数，**不是音频，也不需要实时联网**。

### 3.2 他们的实现（`model.ts:505-572` `importAutoEq()`）

纯前端解析，无网络、无预设数据库：
1. `importText()`：去 BOM、trim，限制 128 KiB（UTF-8 字节数与字符数双检）。
2. 逐行：去掉 `#` 注释；拒绝 `NaN`/`Infinity`；识别 `Preamp: x dB`（钳制 -24~6，重复给 warning 取最后一个）；识别 `GraphicEQ: ...`；其他行按 `filter` 指令解析。
3. `APO_TYPES` 映射滤波器缩写：`PK/PEQ→peak`、`LS/LSC/LSQ→low_shelf`、`HS/HSC/HSQ→high_shelf`、`LP/LPQ→low_pass`、`HP/HPQ→high_pass`、`NO→notch`、`BP→band_pass`。用正则抽取 `Fc Hz` / `Gain dB` / `Q`，做范围钳制（freq 20–20000、gain -30~24、Q 0.1–20），越界写 warning。
4. 最多 10 段 PEQ，超出跳过并报告；未知类型/指令跳过并写 warning。
5. `GraphicEQ` 分支：解析成对频点，按频率排序、校验 ≥2 个不同频点，用对数域线性插值 `interpolateLog()` 采样到我们的 10 个图形 EQ 中心频率（31.25…16000），钳制到 ±12 dB，返回 `equalizerBandsDb`。不允许 GraphicEQ 与 Filter 同时存在。
6. 返回 `{ sound: Partial<AudioEffectsSound>, imported, skipped, warnings }`，`player.importAutoEq()`（`stores/player.ts:3161-3166`）把它合并进当前 profile：`enabled=true, presetId='custom', equalizerEnabled=true`，UI 显示「已导入 N 段，跳过 M 行」。

**数据来源**：用户自己从 AutoEQ 官网 / Equalizer APO 配置复制粘贴，或在 `AudioEffectsSettings.vue:293` 点「选择文件」读本地 `.txt/.csv`（限制 1 MB）。**代码里没有任何 autoeq.github.io / squig.link 等网络请求**（已全局搜索确认）。i18n 文案 `dsp.autoeq_help` 也明确写「粘贴或读取 ParametricEQ.txt」。

### 3.3 配套的配置导入导出（同一模块，非 AutoEQ 但与「预设数据」相关）

- `exportAudioEffectsProfile()` / `importAudioEffectsProfile()`：导出 `{ format:'neriplayer-audio-effects-profile', version:2, id, name, displayName, sound, profile }`，字段兼容 Android 预设分享码的顶层 `id/name/sound`。
- `importAudioEffects()`：识别 4 类来源——Android 完整备份（`kind: moe.ouom.neriplayer.config`，从 `settings.strings.audio_effects_settings` 取 JSON）、完整音效设置、Android 预设分享码、PC profile；带深度/大小/键名安全校验（`validateImportedJson`）。
- `migrateLegacyAudioEffects()`：把旧版 5 段 mB 预设迁移成 5 段参数 EQ。

---

## 4. 我们移植的可行路径、工作量与风险

### 4.1 后端要改什么

分三档，按「是否动音频链」区分：

**A 档：只加数据模型与解析（不碰 AudioEngine）**
- 新增 `desktop/src/main/kotlin/.../core/AudioEffectsSettings.kt`（对应 `model.ts`）：`AudioEffectsSound` / `ParametricEqBand` / `AudioEffectsProfile` / `AudioEffectsSettings` 数据类 + `normalize*` + 默认值。Kotlin `@Serializable`，存入 `Storage.kt`（新增一个设置字段，保留现有 `equalizerBands` 做兼容迁移）。
- 新增 `AutoEqParser.kt`：把 `importAutoEq` 的正则/钳制/插值/GraphicEQ 采样逻辑翻成 Kotlin，纯计算、易单测。
- 新增 backend API（`backend/api/AudioEffectsApi.kt`）：`audioEffects.get` / `updateSound` / `updateProfile` / `importAutoEq` / `exportProfile` / `importProfile` / `saveUserPreset` 等，按约定在 `BackendMain.kt` 注册，`PlayerWire` 加 state 字段，更新 `protocol*.md`。
- 这是**「让设置存得下、AutoEQ 能解析」**的必要前置，风险低（不碰播放）。

**B 档：让新增参数真正影响声音（要碰 AudioEngine）**
- 现有引擎是 ffmpeg 命令行滤镜。ffmpeg 自带的 `equalizer` 是 peak、`bass`/`treble` 是 shelf、`lowpass`/`highpass`/`notch`/`bandpass` 都有——**参数 EQ 的 7 种类型大部分能用 ffmpeg 滤镜表达**，preamp 用 `volume=XdB`，输出增益/limiter 用 `alimiter`，压缩用 `acompressor`，混响用 `aecho`，交叉馈送/环绕可以用 `pan`/`-af` 组合勉强模拟。
- 但**虚拟低音（谐波）、温暖度、激励器、精确 EQ 补偿（正则化最小二乘）、增强限量、逐样本平滑、扬声器优化**这些 ffmpeg 没有现成滤镜，靠滤镜串做不出来或要做很多近似。
- 关键风险：`buildFilterChain()` 仍是**命令行拼接 + 重启进程**。参数一多，滤镜串变长、重启变频繁，拖动滑块会明显卡顿甚至爆音。要真正实时，得改成常驻原生处理环（见 C 档）。

**C 档：移植原生 DSP 引擎（高风险，不建议照搬）**
- 参考实现的实时核心是 `src-tauri/native/dsp/` 的 5 个 cpp + 头文件 + bridge（`neri_dsp_engine.cpp` 等，约 1500+ 行 C++）、31 项 host 回归 + 实时零分配测试，以及 Rust FFI（`dsp.rs` 801 行 / `dsp_settings.rs` 959 行 / `effects.rs` 1508 行）。要在 Kotlin/JVM 侧对接，需要 JNI 或把 C++ 编译成 .so 走 JNA/foreign function，还要处理三档预建引擎、原子参数快照、PCM 交错 float 与现有 s16le 管道的转换、实时线程零分配约束、跨平台构建（deb/rpm/AppImage）。**这是「大工程」，不是一次移植能稳的。**

### 4.2 前端要改什么

- 新增 QML 组件对应三件套：`DspFrequencyGraph`（Canvas 画对数频响曲线，数据从后端算好推给前端，或前端用 JS 复刻 `audioEffectsFrequencyResponse`——后者会在 QML/JS 与 Kotlin 两处重复算法，不推荐）、`DspNumberControl`（带输入框的滑块，可基于现有 `SliderRow` 扩展）、可拖拽 `DspEqEditor`（QML `Canvas` + `MouseArea` 实现 pointer capture 语义）。
- 扩展 `EffectsPanel.qml` / `EqualizerPanel.qml` 或新建 `DspSettingsPanel.qml`：预设网格、用户预设、AutoEQ 文本域 + 文件选择、简单/专家模式、section 折叠。
- 所有文案走 `I18n.tr()` 并同步 `translations.js` 的 en 表（遵循 AGENTS.md 约定）。
- 注意：QML 的 `FileDialog`（QtQuick.Dialogs）或自研文件读取方案要确认在无头/offscreen 截图环境下不阻塞；参考版用 HTML `<input type="file">`，QML 侧需要替代方案。

### 4.3 工作量估计

| 范围 | 估计 | 说明 |
| --- | --- | --- |
| 仅 AutoEQ 文本导入（解析 + API + 一个文本域 UI） | **小~中** | 纯算法翻译 + 少量 QML，1~2 人日；不依赖 DSP 引擎改造。 |
| 设置模型 + 参数 EQ/preamp/bass/treble/压缩/限幅（用 ffmpeg 滤镜近似）+ 频响图 | **中~大** | 后端模型/API/迁移 + 滤镜串扩展 + QML 组件，约 5~10 人日，且受「重启进程」限制，实时性差。 |
| 简单/专家模式、预设体系、用户预设、配置导入导出、扬声器优化 | **大** | 完整 UI 体系 + 多套配置存储 + 兼容 Android 导入，10+ 人日。 |
| 完整原生 DSP 引擎（实时处理、三档质量、零分配、跨平台） | **很大** | 对标 9000+ 行 Rust/C++ + 测试，**不建议在本 fork 内全量移植**。 |

### 4.4 主要风险

1. **音频链架构差异**：我们 ffmpeg 子进程 + 重启式调参，无法承接参考版「常驻实时 DSP + 原子参数」的模型；参数一多，卡顿/爆音/seek 边界问题会放大。
2. **音质一致性**：ffmpeg `equalizer` 与其他 biquad 的实现细节（Q 定义、系数精度、溢出处理）与参考版 C++ 不完全一致，同一组参数听感可能不同；`equalizerAccurate` 的正则化补偿在 ffmpeg 上无现成等价物。
3. **兼容性债务**：新增设置字段要兼容现有 `equalizerPreset/Bands`（中文预设名、5 段 mB 旧数据），迁移做不好会让用户配置丢失或听感突变（参考版专门写了 `migrateLegacyAudioEffects`）。
4. **依赖与打包**：原生方案引入 C++ 编译链、JNI/JNA、平台 .so，冲击现有 deb/rpm/AppImage 打包与 CI；GPL-3.0 来源标注也要保留（参考版 README/LICENSE 有明确要求）。
5. **多语言与无障碍**：新增几十个控件要补齐 i18n、aria、键盘操作，参考版每个控件都带 `aria-label`，我们 QML 侧也要跟进。
6. **无 ffmpeg 降级**：`supportsEffects=false` 时所有新音效都要正确禁用并提示，不能出现「开关能开但没声」。

---

## 5. 建议分几步做、每步产出什么

| 步骤 | 目标 | 产出 | 风险 |
| --- | --- | --- | --- |
| **1. 数据模型 + AutoEQ 导入**（不动音频链） | 让设置能存下参考版结构，AutoEQ 文本能解析 | `AudioEffectsSettings.kt`、`AutoEqParser.kt`、`AudioEffectsApi.kt` + 协议文档 + 单元验证（解析几个真实 ParametricEQ.txt） | 低 |
| **2. 频响曲线可视化**（只读展示） | 用现有 EQ 数据在 QML 画出曲线 | `DspFrequencyGraph.qml`（Canvas），后端暴露 `getFrequencyResponse(bands)` 或在 Kotlin 算好返回采样点 | 低 |
| **3. 参数 EQ + 常用音效的 ffmpeg 近似** | 用 ffmpeg 已有滤镜覆盖 peak/shelf/LP/HP/notch/BP、preamp、压缩/限幅 | 扩展 `buildFilterChain()`、`Player.kt` 批量下发 API、QML 参数 EQ 编辑 UI | 中（实时性受限） |
| **4. 预设/用户预设/配置导入导出** | 补齐预设体系与 Android 互通 | 预设管理 API + QML 预设面板 + `export/importProfile` | 中 |
| **5. 简单模式 + 扬声器优化 + 统计**（可选） | 完整对齐参考版体验 | 听感旋钮、扬声器设置、`getAudioEffectsStats` | 中~大 |
| **6. 原生 DSP 引擎**（独立立项评估） | 真正实时、音质一致 | 先做 PoC：把 C++ 核心编成 .so、JNI 跑通一路 EQ，再决定是否全量 | 大，单独决策 |

**建议先做 1+2**：投入小、见效明显（AutoEQ 能导入、能看到曲线），且不触碰最脆弱的音频链；做完再根据用户反馈决定是否进入 3 及以后。

---

## 6. 哪些部分不值得做（理由）

1. **完整移植 C++/Rust 原生 DSP 引擎**（9000+ 行 + 三档预建 + 零分配测试）：投入极大，且需要重做打包/跨平台适配；本 fork 的定位是「Android 功能参考实现」，音效引擎属于底层架构而非功能点，性价比最低。除非有明确的音质刚需，否则**不值得**。
2. **逐字复刻 29 个内置预设 + 分类 UI**：预设本质是一组数值，移植价值在「能导入 AutoEQ/分享码」，不在「多几套预设」。做好导入后用户可自行获得任意预设，内置预设只需保留我们现有 8 个即可。**不值得全量搬运**。
3. **唱片效果 vinyl、per-output 按声卡存配置、运行时 CPU 统计**：属于边缘体验，依赖原生引擎或特定平台 API，前端收益低。**不值得优先**。
4. **简单/专家双模式 + 听感旋钮的非线性映射**（`listening.ts`）：这是产品化包装，在核心音效未落地前做它本末倒置；可在步骤 5 视反馈决定。
5. **频响曲线的「精确模式」正则化最小二乘补偿**（`graphicEqDesignGains`，`model.ts:787-833`）：算法本身不复杂（10×10 矩阵高斯消元 + 3 轮迭代），但它的价值依赖真实 biquad；ffmpeg 滤镜下无法精确复现，强行移植会出现「曲线和听感不符」。**在 B 档音频链下不值得做**，等 C 档 PoC 后再评估。
6. **Android 完整备份 JSON 导入**（`importAudioEffects` 的 `moe.ouom.neriplayer.config` 分支）：字段多、迁移复杂，而桌面用户多半没有 Android 备份。**优先级最低**，若做只做「Android 预设分享码」这一最小子集。

---

## 7. 关键文件路径索引

**参考项目（`/home/yang/Git/NeriPlayer-Desktop`）**
- UI 三件套：`src/components/settings/DspEqEditor.vue`、`DspFrequencyGraph.vue`、`DspNumberControl.vue`
- 设置主页：`src/components/settings/AudioEffectsSettings.vue`
- 数据模型 + AutoEQ + 算法：`src/modules/audioEffects/model.ts`（`importAutoEq` 在 505 行、`audioEffectsFrequencyResponse` 在 871 行、`graphicEqDesignGains` 在 821 行）
- 听感旋钮：`src/modules/audioEffects/listening.ts`
- store 集成：`src/stores/player.ts`（`importAutoEq` 3161 行、音效 watch 3175 行）
- 原生 DSP：`src-tauri/native/dsp/{neri_dsp_params.h,neri_dsp_stages.h,neri_dsp_engine.cpp,...}`、`README.md`（来源/许可/参数布局说明）
- Rust FFI：`src-tauri/src/audio/{dsp.rs,dsp_settings.rs,effects.rs}`

**本项目（`/home/yang/Git/neriplayer/desktop`）**
- `src/main/kotlin/moe/ouom/neriplayer/desktop/core/AudioEngine.kt`（`setEqualizer` 276 行、`buildFilterChain` 294 行、`EQ_FREQUENCIES` 75 行）
- `src/main/kotlin/moe/ouom/neriplayer/desktop/core/Player.kt`（setEqualizer* 801-821 行）
- `src/main/kotlin/moe/ouom/neriplayer/desktop/core/Storage.kt`（EQ 设置字段 120-123 行）
- `src/main/kotlin/moe/ouom/neriplayer/desktop/backend/api/PlayerApi.kt`（63-79 行）、`backend/PlayerWire.kt`（65-67 行）
- `qt/qml/components/settings/EqualizerPanel.qml`、`qt/qml/components/player/EffectsPanel.qml`
