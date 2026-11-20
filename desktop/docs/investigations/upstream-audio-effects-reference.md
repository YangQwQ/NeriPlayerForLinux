# 上游音效（DSP）功能全集 · 对照表

> 来源：上游 Android 项目 `modules/model/src/main/java/moe/ouom/neriplayer/data/model/playback/effects/`
> 与 `modules/native/src/main/cpp/dsp/`（C++ 引擎，2913 行）+ JNI 桥（`AudioEffectsNativeBridge.kt`）。
> 用于对照我们桌面版 DSP 的实现进度。

## 一、实现架构（上游）

```
Qt/Compose UI
   ↓
AudioEffectsSound（105 参数模型）
   ↓
AudioEffectsNativeBridge.kt  ── JNI (System.loadLibrary("_neri"))
   ↓
native: neri_dsp_engine.cpp（10 个处理阶段 Engine）
   ├─ FilterBankStage  (graphic EQ / parametric EQ / tone / loudness / vocal clarity)
   ├─ BassBoostStage   (virtual bass via harmonic generation)
   ├─ EnhancementLimiter / HarmonicBassStage / WarmthStage / ExciterStage
   ├─ StereoImageStage / CrossfeedStage / SurroundStage
   ├─ ReverbStage      (room / damping / pre-delay)
   ├─ CompressorStage  (threshold/ratio/attack/release/knee/makeup)
   ├─ SpeakerStage     (扬声器优化)
   └─ OutputStage      (output gain / limiter / dither)
   ↓
AudioEffectsAudioSink.kt（Media3 AudioSink，逐块 process PCM）
   ↓
ExoPlayer → 系统音频输出
```

关键点：
- **不是**用 Android 系统 `AudioEffect` API（那只在测试文件里出现），而是自研 C++ 逐采样处理。
- 处理链有 **10 个 stage**，用位掩码 `activeStageMask` 跳过未启用的阶段（省 CPU）。
- 有 `realtime_allocation_test.cpp` 专门保证实时处理路径不分配内存。

## 二、参数全集（40 字段 / 104 native 参数）

### 分区 1：EQUALIZER（均衡）
| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `preampDb` | 0 | 前级增益（防削波） |
| `equalizerEnabled` | true | 图形 EQ 开关 |
| `equalizerBandsDb[10]` | 全 0 | 10 段图形 EQ（增益 dB） |
| `parametricEnabled` | false | 参数 EQ 开关 |
| `parametricBands[]` | 空 | 最多 10 段：`{enabled, type, freq, gainDb, q}`，type 支持 PK/LS/HS/LP/HP/NO/BP |

### 分区 2：TONE（音色）
| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `bassDb` / `bassFrequencyHz` | 0 / 100 | 低音增益与拐点 |
| `trebleDb` / `trebleFrequencyHz` | 0 / 8000 | 高音增益与拐点 |
| `virtualBass` / `virtualBassFrequencyHz` | 0 / 100 | 虚拟低音（谐波生成） |
| `warmth` | 0 | 温暖度 |
| `clarity` / `clarityFrequencyHz` | 0 / 5000 | 清晰度 |
| `vocal` | 0 | 人声增强 |
| `vocalRemoval` | 0 | 人声消除 |

### 分区 3：SPACE（声场）
| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `stereoWidth` | 1 | 立体声宽度 |
| `monoBassHz` / `mono` | 0 / false | 低频单声道化 |
| `swapChannels` | false | 左右声道交换 |
| `crossfeed` | 0 | 交叉馈送（耳机听感） |
| `surround` | 0 | 环绕 |
| `reverb` / `reverbRoom` / `reverbDamping` / `reverbPreDelayMs` | 0 / 0.5 / 0.5 / 10 | 混响（量/房间/阻尼/预延迟） |

### 分区 4：DYNAMICS（动态）
| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `compressorEnabled` | false | 压缩器开关 |
| `compressorThresholdDb` / `Ratio` / `AttackMs` / `ReleaseMs` / `KneeDb` / `MakeupDb` | -18 / 2 / 10 / 150 / 6 / 0 | 压缩器参数 |
| `outputGainDb` | 0 | 输出增益 |
| `limiterEnabled` / `limiterCeilingDb` / `limiterReleaseMs` | true / -1 / 80 | 限幅器 |
| `toneEnabled` / `spaceEnabled` / `dynamicsEnabled` | true | 分区总开关 |

## 三、我们桌面版的实现进度

| 能力 | 状态 |
| --- | --- |
| 数据模型（preamp + 10 段 graphic + parametric 基础） | ✅ 已实现（`core/AudioEffects.kt`） |
| AutoEQ / Equalizer APO 文本导入 | ✅ 已实现 |
| RBJ biquad 频响曲线计算 | ✅ 已实现 |
| **参数作用于音频** | 🔶 阶段一进行中（映射到 ffmpeg 滤镜） |
| TONE 分区 | 🔶 阶段一：bass/treble 可映射 ffmpeg；virtualBass/warmth/clarity/vocal 需 native |
| SPACE 分区 | ❌ 需 native（stereoWidth/crossfeed 可用 ffmpeg stereotools 近似） |
| DYNAMICS 分区 | 🔶 ffmpeg 有 `acompressor`/`alimiter`，可先映射 |
| native DSP 引擎 | ⏳ 阶段二（可行性验证中） |

## 四、ffmpeg 滤镜能力对照（阶段一参考）

| 功能 | ffmpeg 滤镜 | 备注 |
| --- | --- | --- |
| Graphic EQ | `equalizer=f=..:t=q:w=1.2:g=..` | ✅ 现成，10 段可拼 |
| Preamp / Output gain | `volume=..dB` | ✅ |
| Bass / Treble | `bass=g=:/f=:`、`treble=g=:/f=:` | ✅ |
| 压缩器 | `acompressor` | ✅ 参数可映射 |
| 限幅器 | `alimiter` | ✅ |
| 立体声宽度 | `stereotools` / `extrastereo` | ✅ 粗略 |
| 混响 | `aecho` / `afreqshift` 近似 | 🔶 效果差异大 |
| 虚拟低音 / 温暖度 / 激励器 | ❌ 无对应 | 需 native |
| 环绕 / 交叉馈送 | ❌ 无直接对应 | 需 native |
| 扬声器优化 | ❌ | 需 native |

## 五、路线建议

1. **阶段一（进行中）**：用 ffmpeg 滤镜实现 EQUALIZER 全部 + TONE 的 bass/treble + DYNAMICS 的压缩/限幅。让 DSP 面板立刻真正影响声音，并合并旧「均衡器」。
2. **阶段二（可行性验证中）**：移植上游 C++ 引擎，补齐 TONE 剩余项、SPACE、native 级别的质量。
3. **阶段三（未排期）**：扬声器优化、按输出设备独立配置、实时统计（CPU/限幅次数）。
