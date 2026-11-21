# DSP 音效 · IPC 协议（protocol-audio-effects）

领域：DSP 音效的**参数数据模型、AutoEQ / Equalizer APO 文本导入、频响曲线计算，以及实时生效**。
实现：`backend/api/AudioEffectsApi.kt` + `core/AudioEffects.kt` + `core/AudioEngine.kt`；
前端：`qt/qml/components/settings/DspSettingsPanel.qml`。

## 0. 生效范围（务必先读）

**这些参数已经接进 AudioEngine 的 ffmpeg 滤镜链，会真正改变声音。**

模型映射集中在 `AudioEngine.applyAudioEffectsToFilters()` 一个方法里，
将来换成原生 DSP 只需替换那一处，模型 / 协议 / 界面都不用动。

| 分区 | 参数 | 状态 | ffmpeg 实现 |
| --- | --- | --- | --- |
| EQUALIZER | `preampDb` | ✅ 生效 | `volume=XdB` |
| EQUALIZER | `graphicBandsDb[10]` | ✅ 生效 | 每段 `equalizer=f=<中心频率>:t=q:w=1.2:g=X` |
| EQUALIZER | `parametricBands[*]` | ✅ 生效 | peak→`equalizer`、low_shelf→`bass`、high_shelf→`treble`、low_pass→`lowpass`、high_pass→`highpass`、notch→`bandreject`、band_pass→`bandpass` |
| TONE | `bassDb` / `bassFrequencyHz` | ✅ 生效 | `bass=f=..:t=q:w=0.707:g=..` |
| TONE | `trebleDb` / `trebleFrequencyHz` | ✅ 生效 | `treble=f=..:t=q:w=0.707:g=..` |
| TONE | `clarity` / `clarityFrequencyHz` | ✅ 生效 | 峰值近似：`equalizer=f=..:g=clarity*6` |
| TONE | `virtualBass` / `virtualBassFrequencyHz` | ❌ 未生效 | 需要谐波合成，ffmpeg 无等价滤镜 |
| TONE | `warmth` / `vocal` / `vocalRemoval` | ❌ 未生效 | 需要带饱和的非线性 / 中置分离 |
| SPACE | `stereoWidth` | ✅ 生效 | `pan=stereo` 的中侧矩阵 |
| SPACE | `mono` / `swapChannels` | ✅ 生效 | 同一个 `pan` 滤镜 |
| SPACE | `monoBassHz` | ✅ 生效 | `asplit` + `lowpass` + 单声道 `pan` + `amix` 子图 |
| SPACE | `crossfeed` / `surround` / `reverb` | ❌ 未生效 | 需要多抽头延时网络 |
| DYNAMICS | `outputGainDb` | ✅ 生效 | `volume=XdB` |
| DYNAMICS | `compressor*` / `limiter*` | ❌ 未生效 | 本阶段未接入 |

未生效的参数**完整保存在设置里**（切换回可用的引擎时不需要重新调），
但引擎不会为它们生成任何滤镜——避免「界面能调但听感对不上」。
哪些参数生效由后端的 `capabilities` 表下发（见 §2），前端据此禁用/标注控件。

前提是**引擎支持音效**（装了 ffmpeg）。没有 ffmpeg 时引擎回退到 Java Sound，
所有 DSP 参数都不生效，此时 `realtimeApplied = false`，前端要显示「未安装 ffmpeg」。

音效参数**刻意不写进 `AppSettings`**（`Storage.kt`），而是独立 JSON 文件，
便于整体替换/撤销音效配置，也避免给主设置加字段做兼容迁移。

## 1. 命令

| method | params | result | 说明 |
| --- | --- | --- | --- |
| `audioEffects.get` | — | 见 §2 快照 | 读取当前参数 + 频响采样点 + 能力表 |
| `audioEffects.updateSound` | `{sound}` 或部分字段 | 同 `get` | 更新参数并立即生效（见 §3 合并语义） |
| `player.setAudioEffects` | `{sound}` | 同 `get` | 整份替换的等价入口（播放页可直接用） |
| `audioEffects.setSection` | `{section, enabled?, reset?}` | 同 `get` | 分区开关 / 恢复该分区默认值 |
| `audioEffects.setEnabled` | `{enabled}` | 同 `get` | DSP 总开关（整体旁路） |
| `audioEffects.reset` | — | 同 `get` | 全部恢复出厂默认 |
| `audioEffects.importAutoEq` | `{text, apply?}` | 见 §4 | 解析 AutoEQ/APO 文本；`apply=true` 时同时落盘并生效 |
| `audioEffects.response` | `{sound?, points?}` | 见 §5 | 只算曲线不落盘（参数预览用） |

`section` 取值：`equalizer` / `tone` / `space` / `dynamics` / `speaker`（小写，见 `AudioEffectsSection`）。

## 2. 快照回包

```json
{
  "realtimeApplied": true,
  "updatedAt": 1795176183774,
  "sound": {
    "enabled": true,
    "preampDb": -3.0,
    "equalizerEnabled": true,
    "graphicBandsDb": [6, 5, 4, 2, 0, -1, -2, -3, -4, -5],
    "parametricEnabled": true,
    "parametricBands": [
      { "enabled": true, "type": "peak", "frequencyHz": 105.0, "gainDb": -3.5, "q": 1.2 }
    ],
    "toneEnabled": true,
    "bassDb": 4.0, "bassFrequencyHz": 100.0,
    "trebleDb": 0.0, "trebleFrequencyHz": 8000.0,
    "virtualBass": 0.0, "virtualBassFrequencyHz": 100.0,
    "warmth": 0.0, "clarity": 0.0, "clarityFrequencyHz": 5000.0,
    "vocal": 0.0, "vocalRemoval": 0.0,
    "spaceEnabled": true,
    "stereoWidth": 1.0, "monoBassHz": 0.0, "mono": false, "swapChannels": false,
    "crossfeed": 0.0, "surround": 0.0,
    "reverb": 0.0, "reverbRoom": 0.5, "reverbDamping": 0.5, "reverbPreDelayMs": 10.0,
    "dynamicsEnabled": true,
    "compressorEnabled": false, "compressorThresholdDb": -18.0, "compressorRatio": 2.0,
    "compressorAttackMs": 10.0, "compressorReleaseMs": 150.0, "compressorKneeDb": 6.0,
    "compressorMakeupDb": 0.0,
    "outputGainDb": 0.0,
    "limiterEnabled": true, "limiterCeilingDb": -1.0, "limiterReleaseMs": 80.0,
    "speaker": {
      "enabled": false, "size": "phone", "protectionHz": 180.0,
      "bassHarmonics": 0.0, "loudness": 0.0, "clarity": 0.0, "stereoExpand": 0.0
    },
    "qualityMode": "balanced", "ditherEnabled": true,
    "speedEnabled": false, "speedPitchLinked": true, "storedSpeed": 1.0, "storedPitch": 1.0
  },
  "graphicFrequencies": [31.25, 62.5, 125, 250, 500, 1000, 2000, 4000, 8000, 16000],
  "points": [{ "frequencyHz": 20.0, "gainDb": -3.42 }],
  "sampleRate": 48000,
  "maxParametricBands": 10,
  "capabilities": [
    { "key": "preampDb", "section": "equalizer", "applied": true, "reason": "" },
    { "key": "reverb", "section": "space", "applied": false,
      "reason": "混响需要梳状/全通网络，ffmpeg 的 aecho 不等价" }
  ]
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `realtimeApplied` | bool | **参数此刻是否真的在改变声音**：引擎支持音效 且 总开关与至少一个分区打开 |
| `updatedAt` | long | 上次落盘的毫秒时间戳；`0` 表示从未保存（此时用默认值 / 迁移旧均衡器） |
| `sound` | object | 归一化后的完整参数，见 §6 |
| `graphicFrequencies` | double[10] | 10 个图形段中心频率（Hz），供 UI 打标签 |
| `points` | object[] | 频响采样点，默认 128 点，对数频率轴 20 Hz ~ 20 kHz |
| `sampleRate` | number | 计算频响用的采样率（固定 48000） |
| `maxParametricBands` | int | 参数段上限（10） |
| `capabilities` | object[] | 每个参数是否生效 + 未生效原因（中文，可直接展示） |
| `sound.speaker` | object | 扬声器优化：`enabled` / `size`(phone/tablet/large) / `protectionHz` / `bassHarmonics` / `loudness` / `clarity` / `stereoExpand` |
| `sound.qualityMode` | string | 处理质量：`eco` / `balanced` / `high` |
| `sound.ditherEnabled` | boolean | 输出抖动（Eco 档自动关闭） |
| `sound.speedEnabled` / `speedPitchLinked` | boolean | 倍速分区开关 / 倍速与音调联动 |
| `sound.storedSpeed` / `storedPitch` | number | 分区关闭时保留的倍速/音调值 |

## 3. `audioEffects.updateSound` 的两种写法

**A. 整条替换**（传 `sound` 对象）：未提供的子字段取模型默认值。

```json
{ "sound": { "preampDb": -3, "graphicBandsDb": [0,0,0,0,0,2,0,0,0,0] } }
```

**B. 局部更新**（字段直接摊在 params 上）：未提供的字段**保留当前值**。

```json
{ "preampDb": -2.5, "bassDb": 6.0 }
```

滑块一次只改一个值，用写法 B；切换预设 / AutoEQ 导入 / 播放页整体下发用写法 A。
可局部更新的字段：`enabled`、四个分区开关、`preampDb`、`graphicBandsDb`、
`parametricEnabled`、`parametricBands`、`bassDb`、`bassFrequencyHz`、`trebleDb`、
`trebleFrequencyHz`、`clarity`、`clarityFrequencyHz`、`stereoWidth`、`monoBassHz`、
`mono`、`swapChannels`、`outputGainDb`。

其中 `graphicBandsDb` 少于 10 个元素时缺的位置沿用旧值（不会把其他段清零）；
`parametricBands` 是**整体替换**（数组语义），要改单段请先读快照再写回。

回包与 `audioEffects.get` 完全一致。

### 3.1 生效时机与代价

改参数会重启 ffmpeg 解码进程（滤镜图在启动时固定），实测**约 35～55 ms**，
不丢播放位置（`restartPreservingPosition`）。因此：

- 参数**没变化时不会重启**（引擎做 `AudioEffectsSound` 等值比较），重复下发零成本；
- 前端必须**松手才提交**，并做合并（见 §9），否则拖滑块会连续重启、卡顿甚至爆音。

ffmpeg 支持用 `azmq` 在运行期改滤波参数，但需要 libzmq 与 JVM 侧 ZMQ 客户端依赖，
本项目没有引入，所以本阶段保持「重启 + 防抖」。

## 4. `audioEffects.importAutoEq`

参数：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `text` | string | 是 | 配置文本，上限 **128 KiB**（字符数与 UTF-8 字节数双检） |
| `apply` | bool | 否 | `true` = 解析成功后就生效并写盘；默认 `false` 只返回解析结果 |

回包：

```json
{
  "realtimeApplied": true,
  "source": "parametric",
  "imported": 4,
  "skipped": 0,
  "warnings": ["已忽略不支持的指令：Include: other.txt"],
  "persisted": true,
  "updatedAt": 1795176183774,
  "sound": { "...": "同 §2" },
  "points": [{ "frequencyHz": 20.0, "gainDb": -6.48 }],
  "capabilities": [{ "key": "preampDb", "section": "equalizer", "applied": true, "reason": "" }]
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `source` | string | `"parametric"`（Filter 行）或 `"graphic"`（GraphicEQ） |
| `imported` | int | 实际导入段数；GraphicEQ 时为**原始频点数** |
| `skipped` | int | 被跳过的行数（类型不支持 / 超过 10 段） |
| `warnings` | string[] | 逐条告警（中文，可直接展示给用户） |
| `persisted` | bool | 是否已生效并写盘（`apply=false` 时为 false） |
| `updatedAt` | long | 写入时间；未写入时为 0 |

**导入只替换均衡曲线**（`preampDb` / `graphicBandsDb` / `parametricBands`），
音色、空间、动态分区保持用户当前设置——导入一份耳机校正曲线不该把低音/立体声设置清掉。

解析失败抛 `bad_request`，`message` 就是可直接展示的中文原因（如「导入内容为空」
「GraphicEQ 至少需要两个不同频率的点」）。

### 4.1 支持的语法

**前级增益**

```
Preamp: -6.5 dB
```

必须带 `dB`。多个 Preamp 取最后一个并给 warning。范围钳制 -12 ~ +6（与上游 Android 一致）。

**参数滤波器行**

```
Filter  1: ON  PK    Fc   105.0 Hz  Gain  -3.5 dB  Q  1.20
```

- `Filter` 后的序号可省略（`Filter: ON PK ...` 也接受）；
- `ON` / `OFF` 决定该段 `enabled`（`OFF` 的段仍会被导入，只是不参与处理）；
- 类型映射：

  | APO 缩写 | 本应用类型 | ffmpeg 滤镜 |
  | --- | --- | --- |
  | `PK` `PEQ` | `peak` | `equalizer` |
  | `LS` `LSC` `LSQ` | `low_shelf` | `bass` |
  | `HS` `HSC` `HSQ` | `high_shelf` | `treble` |
  | `LP` `LPQ` | `low_pass` | `lowpass` |
  | `HP` `HPQ` | `high_pass` | `highpass` |
  | `NO` | `notch` | `bandreject` |
  | `BP` | `band_pass` | `bandpass` |

- `Fc` 必填且必须带 `Hz`；只接受阿拉伯数字（`1k` 这种简写会报错）；
- `Gain`（必须带 `dB`）只对 `PK`/`LS`/`HS` 必填，其他类型缺省为 0；
- `Q` 可选，缺省 `0.707`；搁架滤波器也接受 `S`（斜率）并当作 Q 使用；
- 范围钳制：频率 20 ~ 20000 Hz、增益 -30 ~ +24 dB、Q 0.1 ~ 20；
- 超过 10 段的额外滤波器计入 `skipped` 并给 warning。

**GraphicEQ**

```
GraphicEQ: 20 -6.0; 50 -4.0; 100 -1.0; 200 0.0; 1000 0.0; 10000 -6.0; 20000 -10.0
```

成对 `频率 增益`，用 `;` 分隔。解析后按频率排序、**在对数频率轴上线性插值**
到 10 个图形段中心频率（31.25 ~ 16000 Hz），每段钳制 -12 ~ +12 dB。

GraphicEQ 与 Filter 行**不能混用**（同时出现直接报错）——两条曲线叠加会把校正量翻倍。
导入时总是整体替换另一侧：GraphicEQ 导入会清空 `parametricBands`，
Filter 导入会把 `graphicBandsDb` 全部置 0。

### 4.2 明确跳过 / 拒绝的内容

- 跳过并 warning：`Include:`、`Copy:`、`Channel:`、`Device:` 等非滤波器指令，
  以及未知的滤波器类型缩写；
- 直接报错：文本为空、超 128 KiB、含 `NaN`/`Infinity`、`Filter` 行格式非法、
  缺少 `Fc`、频率 ≤ 0、GraphicEQ 点数少于 2 或存在重复频率；
- 任何越界数值都会被钳制并写一条 warning（不会静默丢弃）。

## 5. `audioEffects.response`（只算曲线）

参数：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `sound` | object | 否 | 待算的曲线；缺省用当前参数 |
| `points` | int | 否 | 采样点数，默认 128，钳制 2 ~ 512 |

回包：

```json
{
  "realtimeApplied": true,
  "sampleRate": 48000,
  "sound": { "...": "归一化后的曲线" },
  "points": [{ "frequencyHz": 20.0, "gainDb": 0.0 }]
}
```

不读写存储，用于 UI 拖动参数时的轻量预览。

## 6. `AudioEffectsSound` 字段与范围

| 分区 | 字段 | 默认值 | 归一化范围 | 生效 |
| --- | --- | --- | --- | --- |
| — | `enabled` | `true` | — | 总开关 |
| EQUALIZER | `preampDb` | `0` | -12 ~ +6 dB | ✅ |
| EQUALIZER | `equalizerEnabled` | `true` | — | ✅ |
| EQUALIZER | `graphicBandsDb[10]` | 全 0 | 每段 -12 ~ +12 dB | ✅ |
| EQUALIZER | `parametricEnabled` | `false` | — | ✅ |
| EQUALIZER | `parametricBands[*]` | `[]` | ≤ 10 段，见 §7 | ✅ |
| TONE | `toneEnabled` | `true` | — | ✅ |
| TONE | `bassDb` | `0` | -12 ~ +12 dB | ✅ |
| TONE | `bassFrequencyHz` | `100` | 40 ~ 250 Hz | ✅ |
| TONE | `trebleDb` | `0` | -12 ~ +12 dB | ✅ |
| TONE | `trebleFrequencyHz` | `8000` | 2000 ~ 14000 Hz | ✅ |
| TONE | `virtualBass` | `0` | 0 ~ 1 | ❌ |
| TONE | `virtualBassFrequencyHz` | `100` | 50 ~ 200 Hz | ❌ |
| TONE | `warmth` | `0` | 0 ~ 1 | ❌ |
| TONE | `clarity` | `0` | 0 ~ 1 | ✅（等效 +0~6 dB 峰值） |
| TONE | `clarityFrequencyHz` | `5000` | 2000 ~ 10000 Hz | ✅ |
| TONE | `vocal` | `0` | 0 ~ 1 | ❌ |
| TONE | `vocalRemoval` | `0` | 0 ~ 1 | ❌ |
| SPACE | `spaceEnabled` | `true` | — | ✅ |
| SPACE | `stereoWidth` | `1` | 0 ~ 2（1 = 原样，0 = 单声道，2 = 最宽） | ✅ |
| SPACE | `monoBassHz` | `0` | 0 或 40 ~ 250 Hz（0 = 关闭） | ✅ |
| SPACE | `mono` | `false` | — | ✅ |
| SPACE | `swapChannels` | `false` | — | ✅ |
| SPACE | `crossfeed` | `0` | 0 ~ 1 | ❌ |
| SPACE | `surround` | `0` | 0 ~ 1 | ❌ |
| SPACE | `reverb` | `0` | 0 ~ 1 | ❌ |
| SPACE | `reverbRoom` | `0.5` | 0 ~ 1 | ❌ |
| SPACE | `reverbDamping` | `0.5` | 0 ~ 1 | ❌ |
| SPACE | `reverbPreDelayMs` | `10` | 0 ~ 100 ms | ❌ |
| DYNAMICS | `dynamicsEnabled` | `true` | — | ✅ |
| DYNAMICS | `outputGainDb` | `0` | -12 ~ +12 dB | ✅ |
| DYNAMICS | `compressorEnabled` | `false` | — | ❌ |
| DYNAMICS | `compressorThresholdDb` | `-18` | -50 ~ 0 dB | ❌ |
| DYNAMICS | `compressorRatio` | `2` | 1 ~ 20 | ❌ |
| DYNAMICS | `compressorAttackMs` | `10` | 0.5 ~ 100 ms | ❌ |
| DYNAMICS | `compressorReleaseMs` | `150` | 20 ~ 1000 ms | ❌ |
| DYNAMICS | `compressorKneeDb` | `6` | 0 ~ 18 dB | ❌ |
| DYNAMICS | `compressorMakeupDb` | `0` | 0 ~ 18 dB | ❌ |
| DYNAMICS | `limiterEnabled` | `true` | — | ❌ |
| DYNAMICS | `limiterCeilingDb` | `-1` | -6 ~ 0 dB | ❌ |
| DYNAMICS | `limiterReleaseMs` | `80` | 20 ~ 500 ms | ❌ |

**分区关闭的语义**：只把该分区的参数当默认值处理，用户调好的数值仍留在设置里
（与上游 `AudioEffectsSound.effective()` 一致）。

## 7. 参数均衡段（`ParametricEqBand`）

| 字段 | 类型 | 默认值 | 范围 | 说明 |
| --- | --- | --- | --- | --- |
| `enabled` | bool | `true` | — | 关闭后该段不参与处理 |
| `type` | string | `"peak"` | 7 种见下 | 滤波器类型（**小写下划线**） |
| `frequencyHz` | double | `1000` | 20 ~ 20000 | 中心/截止频率 |
| `gainDb` | double | `0` | -30 ~ +24 | 增益（LP/HP/NO/BP 忽略此值） |
| `q` | double | `0.707` | 0.1 ~ 20 | Q 值 |

`type` 取值：`peak`、`low_shelf`、`high_shelf`、`low_pass`、`high_pass`、`notch`、`band_pass`。

## 8. 频响曲线的算法口径

- 采样率固定 48000 Hz，按 RBJ Audio EQ Cookbook 公式设计 biquad 系数；
- 图形均衡每段 Q 固定 **1.41**；低音/高音搁架 Q 固定 **0.707**（对齐 ffmpeg 的 `bass`/`treble`）；
- **只有生效的参数参与画曲线**（图形/参数均衡、前级、低音/高音、清晰度、输出增益），
  混响/压缩/限幅等未接入的不画，避免「曲线动了但听不出变化」；
- 分区关闭时按默认值处理；`enabled = false` 时曲线是平的；
- 各段幅频响应按 `20·log10|H|` 逐点叠加，再加上前级与输出增益；
- 频率轴是**对数**的：第 `i` 个采样点 `f = 20 · (20000/20)^(i/(n-1))`，即 20 Hz ~ 20 kHz；
- 曲线描述的是「这组参数在理想 biquad 下的合成响应」，
  与 ffmpeg 滤镜链的真实输出**近似但不完全一致**（ffmpeg 的 Q 语义、段间叠加略有差异）：
  实测搁架的中心频率处增益约为设定值的一半，符合搁架滤波器的定义。

## 9. 前端接入提示

- `DspSettingsPanel.qml` 是**自包含**面板（自带标题与卡片容器），
  外层设置页用 `Loader` 挂载并提供 `width` 即可，与 `SyncPanel.qml` 同一套约定；
- **提交时机**：`SliderRow` 只在松手时发 `committed`，面板再加一层 120ms 合并窗口 +
  单请求串行，把「连续拖多个滑块」压成一次提交（每次都重启 ffmpeg 代价约 35～55ms）；
- 提交时先用本地 `sound` 做乐观更新（界面立即响应），请求回来后再用后端快照校正；
  若请求在途期间用户又改过，**不要**把这一版写回本地，否则滑块会被弹回去；
- 频响曲线 `FrequencyCurve.points` 变化时自动重绘，父级换数据后不需要手动调用；
- 频率与 Q 的滑块内部用 0~1 的对数归一化位置，**不是物理量**，
  展示值经 `logValue()` 换算后再显示；
- 未生效的控件按 `capabilities` 禁用并标注原因，不要在前端另写一份清单；
- 所有用户可见文案走 `I18n.tr()`；新增中文原文需同步 `i18n/translations.js` 的 `en` 表。

## 10. 存储

| 项 | 值 |
| --- | --- |
| 路径 | `$XDG_CONFIG_HOME/NeriPlayer/audio-effects.json` |
| 顶层结构 | `{ "version": 1, "updatedAt": <long>, "sound": {…} }` |
| 写入方式 | 临时文件 + 原子重命名（`JsonFileStore`） |
| 读取容错 | 文件缺失/损坏时回落到默认值，不抛错 |

**旧均衡器迁移**：`AppSettings.equalizerBands` / `equalizerPreset` / `equalizerEnabled`
会在**音效文件从未保存过**（`updatedAt == 0`）时被迁移成 `graphicBandsDb`；
保存过一次之后旧值不再覆盖。旧预设名不进 DSP 模型（只是界面标签，曲线本身已由十段增益承载）。
