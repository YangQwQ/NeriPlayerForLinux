# 上游 native DSP 移植可行性验证（阶段二前置）

> 结论先行：**技术上完全可行，且比预期简单得多**。上游 `dsp/` 的 5 个 C++ 源文件在本机 Linux x86_64
> **零改动**交叉编译通过（Android 依赖只集中在 `neri_dsp_jni.cpp` 的 2 个 include）；
> JNI 在 JDK 17 与 jlink 精简运行时下都跑通了，实测 +6 dB 图形 EQ 精确落在 `+6.000 dB`；
> 全链路（EQ/低音/空间/混响/压缩/扬声器全开）只占实时率的 **1.8%**，GC 停顿不是问题。
>
> **但**：不要照搬"逐块调 JNI"的接法。桌面 `readLoop` 每块 32 KiB = **170 ms 音频**，
> 与 Android 的小缓冲完全不是一个量级，接法要按桌面特性重新设计（见 §4）。
>
> 真正的风险不在技术，而在**参数/UI 语义的对齐**（§7 风险 1：`stereoWidth` 默认 0 = 强制单声道）
> 与**发行版 glibc 版本兼容**（§6 风险）。

验证环境：Debian 13 (trixie) / GLIBC 2.43 / g++ 16.2.0 / CMake 4.4.5 /
JDK 17.0.21 (`/usr/lib/jvm/java-17-openjdk-amd64`) / 全部产物在 `/tmp/dspport/`，仓库内无改动。

---

## 1. 编译验证：通过，源码零改动

### 1.1 上游代码的 Android 依赖面（比想象小得多）

grep 整个 `dsp/` 目录，Android 特有的东西只有 3 处，**全部集中在 `neri_dsp_jni.cpp`**：

| 位置 | 内容 | 处理方式 |
| --- | --- | --- |
| `neri_dsp_jni.cpp:3` | `#include <android/log.h>` | 提供 `compat/android/log.h` 垫片，用 `-I` 优先命中 |
| `neri_dsp_jni.cpp:4` | `#include <jni.h>` | JDK 自带，`-I$JAVA_HOME/include -I.../include/linux` |
| `neri_dsp_jni.cpp:9` | `#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, ...)` | 垫片里实现成 `vfprintf(stderr, ...)` |

五个纯算法文件（`primitives` / `filter_stages` / `space_stages` / `dynamics_stages` / `engine`）
**一行 Android 头都没引**。这是本次验证最省事的发现：移植面 = 一个 JNI 桥文件。

平台相关的代码都是**双向条件编译**，x86_64 分支上游已经写好：

```c
// neri_dsp_engine.cpp:20-46  —— aarch64 用 fpcr，x86 用 MXCSR，桌面直接命中 x86 分支
#if defined(__x86_64__) || defined(__i386__)
    #include <xmmintrin.h>
    saved_ = _mm_getcsr();
    _mm_setcsr(saved_ | 0x8040u);   // 冲掉 denormal，避免混响/滤波器尾音拖慢
#endif
```

类型方面也**没有要改的**：`jint/jlong/jfloatArray/jobject` 都是 JDK 标准；`int16_t/int32_t/uint8_t`
是 `<cstdint>` 的；PCM 编码常量刻意写成与 Android `AudioFormat` 同值（`2/4/21/22`），
桌面侧照抄这几个魔数即可，不依赖 `android.media.AudioFormat`。

### 1.2 最小 CMakeLists（`/tmp/dspport/CMakeLists.txt`）

关键点只有三个：include 根是 `dsp/` 的父目录（源码写的是 `#include "dsp/xxx.h"`）、
垫片目录排在 JNI 头之前、`OUTPUT_NAME` 保持 `_neri`（跟上游 `System.loadLibrary("_neri")` 对齐）。

```cmake
set(NERI_CPP_ROOT "${CMAKE_CURRENT_SOURCE_DIR}")
set(NERI_COMPAT_INCLUDE "${CMAKE_CURRENT_SOURCE_DIR}/compat")

add_library(neri_dsp_core STATIC
    dsp/neri_dsp_primitives.cpp dsp/neri_dsp_filter_stages.cpp
    dsp/neri_dsp_space_stages.cpp dsp/neri_dsp_dynamics_stages.cpp
    dsp/neri_dsp_engine.cpp)
target_compile_options(neri_dsp_core PRIVATE -O2 -Wall -Wextra)
target_include_directories(neri_dsp_core PUBLIC "${NERI_CPP_ROOT}")

add_library(neri_dsp SHARED dsp/neri_dsp_jni.cpp)
target_include_directories(neri_dsp PRIVATE "${NERI_COMPAT_INCLUDE}" ${NERI_JNI_INCLUDE_DIRS})
target_link_libraries(neri_dsp PRIVATE neri_dsp_core)
set_target_properties(neri_dsp PROPERTIES OUTPUT_NAME "_neri")
```

### 1.3 遇到的唯一错误：`find_package(JNI)` 默认要 AWT

**错误原文**（第一次 configure 就撞上）：

```
CMake Error at /usr/share/cmake-4.4/Modules/FindPackageHandleStandardArgs.cmake:290 (message):
  Could NOT find JNI (missing: AWT)
Call Stack (most recent call first):
  /usr/share/cmake-4.4/Modules/FindJNI.cmake:706 (find_package_handle_standard_args)
  CMakeLists.txt:31 (find_package)
```

**原因**：CMake ≥ 3.24 的 `FindJNI` 在非 Android 平台**默认要求 `AWT` + `JVM` 两个组件**
（`FindJNI.cmake:213-216` 把 `JNI_FIND_REQUIRED_AWT` 设成 TRUE）。而本机装的是
`openjdk-17-jdk-headless`，**没有 `libjawt.so`**，于是 FATAL_ERROR。

注意 `find_package(JNI COMPONENTS)`（空列表）**不能**绕过——空列表会被当成"未指定组件"，
仍然落回 AWT+JVM 默认值（实测输出 `found components: AWT JVM`）。

**修复**：显式只要 JVM，失败再手工探测。编一个 JNI `.so` 本来就只需要 `jni.h` + `jni_md.h`，
**不链接 libjvm**（`JNI_CreateJavaVM` 才需要）：

```cmake
find_package(JNI QUIET COMPONENTS JVM)
if(JNI_FOUND AND JNI_INCLUDE_DIRS)
    set(NERI_JNI_INCLUDE_DIRS ${JNI_INCLUDE_DIRS})
else()
    # 回退：从 JAVA_HOME 或 javac -XshowSettings 推导
    set(NERI_JNI_INCLUDE_DIRS "${_neri_java_home}/include" "${_neri_java_home}/include/linux")
endif()
```

> 顺带记一个坑：`find_package(Java REQUIRED COMPONENTS Development)` **能成功**，
> 但它不导出 `Java_INCLUDE_DIR` / `Java_INCLUDE_DIRS`（实测两者都为**空**），
> 不能用来定位 `jni.h`。

**修复后编译全程无警告**（`-Wall -Wextra` 干净通过）：

```
-- NERI JNI headers: /usr/lib/jvm/java-21-openjdk-amd64/include;.../include/linux
[ 60%] Built target neri_dsp_core
[ 90%] Linking CXX shared library lib_neri.so
[100%] Built target dsp_smoke
```

产物：`lib_neri.so` 82 KB（strip 后 70 KB），8 个 JNI 符号全部正确导出：

```
$ nm -D --defined-only build/lib_neri.so | grep Java_
T Java_..._AudioEffectsNativeBridge_nativeCreate
T Java_..._AudioEffectsNativeBridge_nativeConfigure
T Java_..._AudioEffectsNativeBridge_nativeSetParams
T Java_..._AudioEffectsNativeBridge_nativeProcess
T Java_..._AudioEffectsNativeBridge_nativeReadStats
（共 8 个：paramCount / create / release / configure / setParams / reset / process / readStats）
```

> 导出符号名是按 Kotlin 包名硬编码的长名字（`Java_moe_ouom_neriplayer_core_player_audio_effects_...`），
> 所以**桌面端的桥类必须放在同一个包名下**，否则链接不到。这一点已由实测确认（§2）。

---

## 2. JNI 调用验证：通过

### 2.1 测试方式

仓库里没有可直接复用的 JVM 测试，`/tmp/dspport/jvm/` 下自建了 5 个类：

- `AudioEffectsNativeBridge.java` —— 复刻上游 Kotlin 桥的包名/类名/`static native` 签名
- `DspJvmTest` / `DspStressTest` / `DspEdgeTest` / `DspIntegrationTest` / `DspRealtimeTest`

用 **JDK 17**（`/usr/lib/jvm/java-17-openjdk-amd64`）编译运行，喂 2 s / 48 kHz / PCM16 立体声
1 kHz 正弦（幅度 0.25），参数只开图形 EQ 的 1000 Hz 段 +6 dB，用 Goertzel 测单频能量。

### 2.2 实测输出：+6.000 dB，与理论值完全一致

```
=== NeriDsp JNI feasibility test ===
java.version = 17.0.21.1
os.arch      = amd64
nativeParamCount = 104
nativeCreate -> handle=140527573415664
nativeConfigure(48000, 2, PCM16) -> true
nativeSetParams(+6dB @ 1000Hz) -> true
input  1kHz amp = 0.249994
output 1kHz amp = 0.498798
delta           = +6.000 dB   (expect ~ +6 dB)
nativeReadStats -> true  processedFrames=96000  processingNanos=2296487  activeStageMask=0x211

blocks=12 chunk=32768B total=2.4 ms for 2.0 s audio
CPU load    = 0.12% of realtime
per block   = 0.197 ms (budget per 32768B block = 170.7 ms)

JNI TEST PASS
```

同一份源码另编了一个纯 C++ 冒烟测试（`tests/dsp_smoke.cpp`）交叉验证，结果一致
（`input=0.249994 / output=0.498798 / +6.000 dB`），说明 JVM 层没有引入任何偏差。
`activeStageMask=0x211` 也符合预期：`0x1`(Equalizer) + `0x10`(Stereo) + `0x200`(Output)。

`System.loadLibrary("_neri")` 与 `System.load(绝对路径)` 两种加载方式**都验证通过**
（前者用 `-Djava.library.path=<dir>`）。

### 2.3 jlink 精简运行时也 OK

用与 `build-packages.sh` 完全相同的模块列表跑 `jlink`（52 MB 产物），JNI 正常加载并工作。
这一点必须单独确认，因为 `java.base` 之外的模块裁剪一旦把 `libjvm` 的 JNI 支持裁掉就前功尽弃——实测没有。

### 2.4 边界行为（决定接入写法，都实测过）

| 场景 | 结果 | 含义 |
| --- | --- | --- |
| 48k/44.1k/96k/192k/768k × PCM16/24/32/FLOAT | 全 `true` | 采样率/编码覆盖够用 |
| 8000 Hz 以下（7999） | `false` | 上限 768 kHz、下限 8 kHz |
| 单声道 `channelCount=1` | `true` | 支持；内部取 `0.5*(L+R)` |
| 3 声道 | `false` | **最多 2 声道**，多声道文件必须先降混 |
| `size` 非 4 字节帧对齐 | `-1`（拒绝） | 见下面 §2.5，桌面 readLoop 必然会踩 |
| `size=0` | `2`（成功） | 无 payload 不报错 |
| **heap `ByteBuffer`** | `-1` 拒绝 | `GetDirectBufferAddress` 对堆内存返回 NULL |
| direct `ByteBuffer` | `2` 成功 | 必须用 `allocateDirect` |
| **in-place（input==output）** | `2` 成功 | 可以省掉一块缓冲 |

### 2.5 关键坑：`InputStream.read()` 的长度不保证帧对齐

`Engine::process` 判断 `size % frameBytes() != 0` 就直接返回 `-1`。桌面 `readLoop` 的
`inputStream.read(buffer)` 在管道边界/文件末尾完全可能出现奇数长度。实测：

```
size=32768  -> 2
size=32767  -> -1   (拒绝：非 4 字节帧对齐)
size=32766  -> -1
size=32765  -> -1
```

**接法**：`n = read - read % 4`，尾部 `read % 4` 个字节原样透传（`System.arraycopy`），
下块开头再补上——Android 侧 `AudioEffectsAudioSink.runEngine()` 就是这么做的
（`val alignedSize = size - size % pcm.frameBytes` + 尾部逐字节拷贝）。这段逻辑可以照抄。

---

## 3. 性能与实时性：全链路 1.8% 实时率，GC 不是问题

### 3.1 分项开销

| 项目 | 实测 |
| --- | --- |
| 空 JNI 调用 round-trip | **0.020 µs** |
| `process()` idle 路径（master 关，仅 memmove） | **0.09 µs / 次** |
| `process()` 仅 EQ 开启 | 166 µs / 32 KiB 块 |
| `setParams(104 floats)` | **0.48 µs / 次** |
| `byte[] → direct` 拷贝 32 KiB | **0.82 µs** |
| `direct → byte[]` 拷贝 32 KiB | **0.76 µs** |
| `arraycopy` 32 KiB 基线 | 0.73 µs |

结论：拷贝开销（约 1.6 µs/块）相对 DSP 计算（数百 µs）可以忽略，**不必为了省拷贝去改
readLoop 的 `ByteArray` 体系**。`setParams` 便宜到可以在每块开头无条件调用（省掉脏标记逻辑）。

### 3.2 全链路压力（High 质量档、32 帧小块、所有模块全开）

```
=== B. 全链路每块耗时 / 尾延迟 ===
full chain (High quality, 32-frame blocks):
  audio=75.1s wall=1.376s -> 1.83% of realtime
  per block: p50=3.058 ms  p99=4.343 ms  max=4.653 ms
  block deadline = 170.7 ms
  activeStageMask=0x3ff recoveries=0 limiterEvents=0
```

`activeStageMask=0x3ff` 表示 10 个 stage **全部激活**，`recoveries=0` 表示没有触发过
NaN/Inf 兜底（这套引擎自带崩溃自愈：检测到非有限值就回滚到干信号，见 `blockFinite`）。

### 3.3 实时性真正的指标：音频线程"多久没被调度"

只看 `process()` 耗时是不够的——JVM 侧的 STW GC 表现为**调用之间的间隙**，而不是单次调用变慢。
所以直接测相邻两次处理之间的 gap，并对照桌面真实约束：

- 每块 32 KiB = 1024 帧 = **170 ms 音频**（这是桌面最大的安全余量，见 §4）
- `SourceDataLine.open(format, SAMPLE_RATE * BYTES_PER_FRAME / 25)` = **40 ms 硬件缓冲**

```
=== 场景 1：空闲系统（无分配压力）===
  gap p50=2.49 ms  p99=2.65 ms  p99.9=3.62 ms  max=3.62 ms
  gap > 单块时长(170.0 ms): 0 / 200
  gap > line 缓冲(40 ms, 会 underrun): 0 / 200

=== 场景 2：后台持续分配（模拟媒体库扫描/JSON 解析/日志）===
  gap p50=2.53 ms  p99=8.72 ms  p99.9=15.16 ms  max=16.99 ms
  gap > 单块时长(170.0 ms): 0 / 900
  gap > line 缓冲(40 ms, 会 underrun): 0 / 900

=== 场景 3：显式 System.gc() 强制 Full GC（最坏情况，每 180 ms 一次）===
  gap p50=2.55 ms  p99=3.32 ms  p99.9=4.77 ms  max=4.77 ms
  gap > line 缓冲(40 ms, 会 underrun): 0 / 400
```

用后端实际的启动参数（`-Xms32m -Xmx256m -XX:+UseG1GC`）跑的。GC 日志独立统计：

```
Pause 停顿样本 179 次（含 5 次 Full GC）
  最小 0.026 ms  中位 1.705 ms  p99 5.304 ms  最大 6.504 ms
  >10 ms: 0 次        >40 ms: 0 次
```

**GC 对实时音频完全不是问题**，理由不是"JVM 很快"，而是两头都有极大余量：
堆只有 256 MB 上限、DSP 段本身**零分配**（`process()` 只在 native 侧写预分配的 `std::vector`），
而且**每块 170 ms 的音频处理窗口远大于 6.5 ms 的最坏 GC 停顿**。

这跟 Android 侧不同：Android 的 `Media3 AudioSink` 缓冲是几十毫秒级，JNI+GC 的抖动必须
小心；桌面这边的读窗口是**一个数量级更宽的**。反过来说，接入时不要把这个余量挥霍掉
——见下节的 `IOWait` 提醒。

---

## 4. 接入点评估：`applyVolume` **之前**，且不要照搬逐块 JNI

### 4.1 位置：插在 `applyVolume` 之前（= 用户音量之前）

`AudioEngine.kt:420-479`（`FfmpegAudioEngine.readLoop`）：

```
read(buffer, 32 KiB)
  → applyVolume(buffer, read)      // ← DSP 应插在它之前
  → line.write(buffer, ...)
```

`applyVolume` 内部顺序是：`fadeGain.gainAndAdvance()` → `mixPcm()` → `scalePcm(volume)` → `pcmTap`。

**为什么必须在 `applyVolume` 之前**（实测证据）：引擎里的压缩器/限幅器阈值写在 **dBFS**，
它们对**绝对电平**敏感。用压缩器（阈值 -6 dBFS、8:1）喂不同电平的同一信号：

```
输入电平    输入RMS       输出RMS       增益(dB)
1.00       20847        8108         -8.20      ← 强烈压缩
0.50       10424        8330         -1.95
0.20        4170        4170         +0.00      ← 完全不起作用
0.05        1042        1042         +0.00      ← 完全不起作用
```

如果 DSP 放在 `applyVolume` 之后，用户把音量拉到 20% 就等于**让压缩器和限幅器彻底失效**
（信号还没进 DSP 就已经被衰减到阈值以下）。放在之前，音效处理的是**满幅原始信号**，
行为与用户音量解耦——这也是 Android 侧的顺序（`AudioEffectsAudioSink` 在 `AudioSink`
里处理，而音量在更下游应用）。

> 附带好处：`fadeGain` 的淡入淡出仍在 DSP 之后，切歌/暂停不会让混响尾巴被"放大"，
> 淡出仍然干净。`pcmTap`（频谱/可视化取数）当前在 `scalePcm` 之后、且 `abs(gain-1)<0.001`
> 时提前 return 会跳过 tap——这是既有小 bug，接 DSP 时顺手把 `pcmTap` 挪到 DSP 之后统一调用。

### 4.2 不要照搬 Android 的"逐块 `line.write` 后立刻 JNI process"

Android 的 `handleBuffer` 是 Media3 每来一块就处理一块。桌面 `readLoop` 的结构看起来一样，
但**块的中断语义不同**——`line.write` 是**阻塞**调用，写满 40 ms 缓冲后音频线程会**卡在 write 里**：

```
read(32 KiB) = 170 ms 音频
  → process()  约 3-13 ms
  → line.write() 阻塞约 170-40 = 130 ms  ← 音频线程大部分时间在这里睡觉
```

也就是说，**每 170 ms 音频只做一次 DSP**，中间那次 `write` 的阻塞既不是问题（反而给了
GC/DSP 巨大的调度余量），但也意味着：**DSP 参数变更的延迟上限是 170 ms**。
`setParams` 本身只要 0.48 µs，但要等到下一次循环才生效。

对"拖动 EQ 滑块实时听效果"这个体验，170 ms 延迟是**可以接受的**（人耳对音色变化的分辨
远低于对咔哒声的分辨）。如果后续觉得拖拽手感不够跟手，再把 `read` 块缩到 8 KiB
（= 42 ms 音频）即可，成本是 JNI 调用次数 ×4（仍只占 0.1% 实时率）。

### 4.3 具体改法（三处）

1. **`applyVolume` 之前加 `dspProcess(buffer, read)`**：一个 `DirectByteBuffer` 常驻，
   `put` 进 PCM → `process(in, 0, in, 0, aligned)`（in-place，§2.4 已验证可行）→ `get` 回 `ByteArray`。
   对齐逻辑照 §2.5。缓冲在 `startPipeline` 里按 `readLoop` 的 `1 shl 15` 一起分配。
2. **参数变更走原子快照**：音频线程不读 `AppSettings`。配置线程算好 104 长的 `FloatArray`
   放进 `AtomicReference`，音频线程每块开头 `get()` 一次并 `setParams`。
   **必须 `reset()`**：换歌/seek 时滤镜状态要清（`AudioEffectsAudioSink.flush()` 里 `engine?.reset()` 同理）。
3. **`configure()` 跟着线路走**：`FfmpegAudioEngine` 固定 48k/双声道/PCM16 → 常量即可；
   但 `JavaSoundAudioEngine` 用文件原始格式（采样率、声道数、位深都可能不同）→ 必须在
   `startStream()` 拿到 `format` 之后 `configure(sampleRate, channels, encoding)`，
   且 `channels > 2` 时直接旁路（引擎最多 2 声道）。

### 4.4 线程安全提醒（必读）

`Engine::setParams` 里有 `rebuildStages()`（切换 quality 档时重建全部滤波器、可能 `resize`
内部 `std::vector`），`process()` 同时在读这些结构。**上游明确约定"播放线程独占使用；
所有方法都必须在同一个音频线程调用"**（`AudioEffectsNativeBridge.kt` 的接口注释）。

实测跨线程并发跑 3 s（131 万次 `process` + 31 万次 `setParams` 交替，其中一半随机切 quality）
**没有崩溃、`process` 全程返回非负**——但这是 **UB，不能当地证**：数据竞争没崩溃不代表结果正确。

**接法**：不要在配置线程直接 `setParams`。用一个 SPSC 的手递手：
配置线程只写 `AtomicReference<FloatArray>`，音频线程在块边界取出来调 `setParams`。
这既满足"同一线程"约定，又不需要加锁（音频线程全程无锁）。

---

## 5. 打包建议：**不要塞进 Qt 的 CMake，单独编再放进 staging**

### 5.1 现状

- `desktop/qt/CMakeLists.txt` 只管 Qt 前端：`install(TARGETS neriplayer RUNTIME DESTINATION lib/neriplayer)`
  + `install(DIRECTORY qml DESTINATION share/neriplayer)`。它现在**完全不碰 JVM**。
- `packaging/build-packages.sh` 的 `stage_payload()` 依次做：Qt 前端（CMake）→ 后端 jar（Gradle）
  → jlink 运行时 → 启动脚本/desktop/图标。**已经是一个"多工具链编排脚本"**，天然适合加第三步。
- 三种格式（deb/rpm/AppImage）**共享同一份 `$STAGE`**，所以只需在 `stage_payload()` 里加一句
  `install`，deb/rpm/AppImage 自动全部覆盖。rpm 需要在 `neriplayer.spec.in` 的 `%files`
  里补一行（该文件是显式文件列表，实测 `%files` 里有 `/usr/lib/neriplayer/runtime` 等条目）。

### 5.2 推荐做法：独立子目录 + 独立 CMake 工程，由 `build-packages.sh` 调用

三个方案对比：

| 方案 | 评价 |
| --- | --- |
| A. 塞进 `desktop/qt/CMakeLists.txt`（`add_subdirectory`） | ❌ 破坏前端构建的最小依赖面：编前端就得有 JDK；且 `qt/CMakeLists.txt` 是前端负责人维护的共享文件 |
| B. 手写 `g++ -shared` 命令塞进 `build-packages.sh` | ❌ 依赖列表/优化选项/警告全散在 shell 里，和上游 `dsp/CMakeLists.txt` 脱钩，后续升级上游要手工对齐 |
| **C. 新增 `desktop/native/CMakeLists.txt`，脚本第三段调用** | ✅ **推荐**：独立工具链、可单独 `cmake --build` 调试、与上游 CMake 结构对齐、不污染前端构建 |

具体落地（新增文件，均不动现有源码逻辑）：

```
desktop/native/
├── CMakeLists.txt          # 参照 /tmp/dspport/CMakeLists.txt
├── compat/android/log.h    # 垫片（也可考虑直接把 __android_log_print 换掉，见下）
├── jni/neri_dsp_jni.cpp    # 从上游复制（或 submodule 引用 modules/native/src/main/cpp/dsp）
└── src/                    # 上游 dsp/*.cpp/h 的副本
```

`build-packages.sh` 的 `stage_payload()` 里，在"jlink 运行时"之后加：

```bash
echo "==> 构建 native DSP"
cmake -S "$DESKTOP_DIR/native" -B "$BUILD_DIR/native" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DJAVA_HOME="$JAVA_HOME" >/dev/null
cmake --build "$BUILD_DIR/native" -j"$(nproc)"
install -m 755 "$BUILD_DIR/native/lib_neri.so" \
    "$STAGE/usr/lib/neriplayer/lib_neri.so"
```

`neriplayer.spec.in` 的 `%files` 补：`/usr/lib/neriplayer/lib_neri.so`。

### 5.3 运行期不能靠 `java.library.path`（已实测的坑）

`desktop/qt/src/backendclient.cpp:354-366` 拉起后端时**没有传 `-Djava.library.path`**。
当前能工作是因为 `lib_neri.so` 若放在 `runtime/lib/` 下会被 `-jar` 场景默认搜索到——
但 `$STAGE/usr/lib/neriplayer/` **不在** `java.library.path` 里。两条路：

- **推荐**：`System.load("/usr/lib/neriplayer/lib_neri.so")` 用绝对路径（`System.load` 接受绝对路径，
  已实测通过）。后端可按 `NERIPLAYER_HOME` 或 `Class.protectionDomain` 推出安装前缀。
- 备选：在 `backendclient.cpp` 的 args 里加 `-Djava.library.path=...`。
  但那要改前端共享文件 `backendclient.cpp`，且 AppImage 布局又是另一套相对路径。

**倾向第一条**：`lib_neri.so` 由后端自己按"jar 所在目录的兄弟文件"定位，零前端改动。

### 5.4 分发包依赖

`lib_neri.so` 只依赖 `libstdc++ / libm / libgcc_s / libc`（`ldd` 实测），Debian/Ubuntu
基础系统都有。但**版本符号有个真问题**，见下节。

---

## 6. 三个必须知道的风险

### 风险 1（高）：`stereoWidth` 缺省 0 会把音乐压成单声道

这是"参数语义"而不是"移植技术"的问题，但踩上就是**静默毁掉立体声**。

`StereoImageStage::process`（`neri_dsp_space_stages.cpp:84-124`）的核心是：
`side *= monoMix_ ? 0.0f : lerp(widthStart, widthEnd, ...)`，即 `side = 0.5*(L-R) * width`。
**`stereoWidth = 0` ⇒ 所有 `side` 归零 ⇒ 输出左右完全相同的 `mid`。**

实测（左声道 1 kHz、右声道 3 kHz 输入，只开 master）：

```
stereoWidth=-1.0  Lrms=5644  Rrms=4985
stereoWidth= 0.0  Lrms=5633  Rrms=5632     ← 左右变成一样：立体声被抹平
stereoWidth= 1.0  Lrms=11266 Rrms=0        ← 注意：1.0 在这套实现里不是"中性"
stereoWidth= 2.0  Lrms=16899 Rrms=5633
```

正确的中性值是 **1.0**，上游 Kotlin 的 `neutralDspParams()` 也确实写了
`params[STEREO_WIDTH] = 1f`（`NeriDspParams.kt:116`），`AudioEffectsSound.stereoWidth` 默认也是 `1f`。

**如果桌面侧图省事直接用 `FloatArray(104)` 全零当默认参数，第一耳朵就是"高音没了、声音挤在中间"。**
实测对照（用正确的 neutral 参数，左 1 kHz / 右 3 kHz 输入）：

```
中性参数输出: Lrms=5655 (1kHz)  Rrms=2829 (3kHz)
输入:         Lrms=5657 (1kHz)  Rrms=2828 (3kHz)   ← 左右独立、电平不变
activeStageMask=0x200
```

**接法**：`neutralDspParams()` 逻辑必须一并移植（它有 104 个下标里的近 20 个非零缺省值：
limiter 三件套、10 段参数 EQ 的频率/Q、5 个模块的中心频率、压缩器 6 个参数、`SPEAKER_HIGH_PASS_HZ`）。
最省事的做法是把 `NeriDspParams.kt` 的 `neutralDspParams()` 直接从 `modules/model` 复制/复用到
桌面 `core/AudioEffects.kt`——它是纯数据构造，没有 Android 依赖。

### 风险 2（中）：glibc 版本符号把包"锁"在构建机上

本机（GLIBC 2.43）编出来的 `.so` 需要一个 **`GLIBC_2.43`** 版本符号：

```
$ objdump -T build/lib_neri.so | grep -o "GLIBC_[0-9.]*" | sort -u
GLIBC_2.2.5
GLIBC_2.27
GLIBC_2.29
GLIBC_2.43   ← 唯一那个
$ objdump -T build/lib_neri.so | grep "GLIBC_2.43"
... (GLIBC_2.43) log10f
```

**只有 `log10f` 一个**。原因是新版 glibc 为 `log10f` 加了新的（更精确的）实现并绑到 2.43，
而 `dbToDb` / `dbToGain`（`neri_dsp_primitives.h:14-18`）用到了它。用 `g++-12` 重编结果相同
——这是**链接期 glibc 选择**，不是编译器版本问题。

后果：在 Ubuntu 22.04（CI 的 `runs-on`）或 Debian 12 上装不上（`GLIBC_2.43 not found`）。
好在 CI 就在 22.04 上构建，那边只会绑到 2.35 左右，**CI 产物没问题**；
有问题的是**在开发机上打包**（本机 2.43 → 用户端旧系统直接崩）。

三个可选处理：

1. **CI 构建**（推荐）：`.github/workflows/desktop-packages.yml` 已经是 `ubuntu-22.04`，
   在 CI 里编 `.so` 天然绑旧符号，什么都不用改。
2. **开发机打包时钉住旧符号**（实测有效）：
   ```c
   __asm__(".symver log10f,log10f@GLIBC_2.2.5");
   ```
   单独编一个 `.c` 加进 `neri_dsp_core` 再 `-lm` 即可，实测产物只剩 `GLIBC_2.2.5`。
3. 用 `-D_FORTIFY_SOURCE` / 静态 `-static-libstdc++` **没用**——问题在 libm 不在 libstdc++。

> 建议把这一条写进打包脚本的注释，否则半年后有人在本机打包发版就是"装完打不开"。

### 风险 3（低）：验证范围只覆盖了 `dsp/`，没有覆盖 `usb/` 与 `crash/`

`modules/native` 上游还有 `usb/`（独占 USB 输出）与 `crash/`，本次**没有纳入验证范围**。
如果后续要接 USB 独占，`libusb` + Android 专属的 `usb/` 代码是另一个量级的工程
（`tests/usb/` 下有 30 多个测试文件），不要顺手带上。

---

## 7. 工作量与风险

### 7.1 真实估计

假设执行者已熟悉本仓库、且**只做桌面端**（不碰 Android）：

| 阶段 | 内容 | 人日 |
| --- | --- | --- |
| **A. 编译落地** | 建 `desktop/native/`（CMake + 垫片 + 源码同步策略）、`build-packages.sh` 加一段、rpm `%files` 补一行、`.so` 定位（`System.load` 绝对路径）、CI 验证 | **1.5 – 2** |
| **B. JNI 桥 + 引擎生命周期** | Kotlin 桥（含 `ensureLoaded` / `LinkageError` 兜底，可照抄上游 `AudioEffectsNativeBridge.kt`）、`configure` 跟随线路格式、`reset` 时机（换歌/seek/切格式）、采样率/声道不匹配时旁路 | **2 – 3** |
| **C. 接入 `AudioEngine`** | 插在 `applyVolume` 前、direct buffer 常驻、帧对齐 + 尾部透传、音量与 DSP 顺序、`pcmTap` 归位、`JavaSoundAudioEngine` 与 `FfmpegAudioEngine` 两处对称改（**注意这两个类各有一份 `readLoop`/`applyVolume`，代码是复制粘贴的**） | **2.5 – 3.5** |
| **D. 参数通道** | `neutralDspParams()` 移植、`AtomicReference` 手递手、`AppSettings` → 104 参数的映射（`writeSound/writeCharacter/writeSpace/writeDynamics` 那套逻辑），以及和现有 ffmpeg EQ 的**关系决策**（并存？替换？并存会双重 EQ） | **2 – 3** |
| **E. 稳定性与验收** | 边界（非对齐 read、0 字节、超长音频、切歌、seek）、长时间不 underrun、跨采样率、内存不涨、Windows 无关；`selfTest` 补用例 | **2 – 3** |
| **合计** | 不含 UI | **10 – 14.5 人日** |

**不含**：音效设置 UI（那是 `dsp-autoeq.md` 的范围）、预设导入导出、AutoEQ 解析。

如果只做"最小可用"（只接图形 EQ + 低音 + 高音，其余参数恒为中性值），可以压到
**6 – 8 人日**——因为 D 阶段能砍掉大半，E 阶段矩阵也小一半。

### 7.2 主要风险（按严重度）

| # | 风险 | 影响 | 缓解 |
| --- | --- | --- | --- |
| 1 | **和现有 ffmpeg 滤镜链重复处理**。`buildFilterChain()` 已经拼了 `equalizer=f=...` 和 `dynaudnorm`。DSP 接上后若不清掉 ffmpeg 那段，就是**双重 EQ + 双重响度**，声音直接毁掉且很难查 | 高 | 接线时必须同时改 `buildFilterChain()`：DSP 可用时不再拼 `equalizer`/`dynaudnorm`；或者先只做"旁路验证"（DSP 全关，A/B 无差异）再逐模块打开 |
| 2 | **`stereoWidth` 缺省 0 → 单声道**（§6 风险 1） | 高 | 移植 `neutralDspParams()`，并加一条断言：`configure` 后若 `STEREO_WIDTH == 0` 记警告 |
| 3 | **跨线程 `setParams` 是 UB**（`rebuildStages` 会 resize `vector`）（§4.4） | 中高 | 严格 SPSC：配置线程只写 `AtomicReference`，音频线程块边界调用 |
| 4 | **非帧对齐 `read` 返回 `-1`**（§2.5），错误处理写不好会静默静音 | 中 | 对齐 + 尾部透传；`process` 返回 `< 0` 时**旁路并计数**，连续 N 次失败就永久关闭 DSP 并上报 |
| 5 | `JavaSoundAudioEngine` 任意采样率/声道数，`channels > 2` 引擎拒绝 | 中 | `configure` 失败即旁路，不报错（不影响播放） |
| 6 | glibc 版本绑定（§6 风险 2） | 中 | CI 构建 / 开发机 `.symver` 钉符号 |
| 7 | 上游 `dsp/` 是**活代码**（Android 侧还在改），复制到 `desktop/native/` 会分叉 | 中 | 要么 submodule/软链复用 `modules/native/src/main/cpp/dsp`（推荐，垫片机制已证明零改动可行），要么接受手动同步 |
| 8 | `QualityMode::High` 下 `blockFrames=32`（`blockFramesFor`），每 32 帧一次 `steady_clock::now()` + `std::copy` 进 dry buffer | 低 | 实测 1.8% 实时率，不用管 |
| 9 | `FfmpegAudioEngine` 和 `JavaSoundAudioEngine` 的 `readLoop`/`applyVolume` 是复制粘贴的两份 | 低 | 接线时抽公共函数，别只改一份 |

### 7.3 结论

**建议做，并且把它放在"阶段三"而不是阶段二**。理由：

- 技术上没有拦路虎——编译、JNI、性能、GC 四项全部实测通过，源码零改动；
- 但它是**唯一一个会改变"声音本身"的改动**，验证成本高（必须人耳 + 仪器双确认），
  且和现有的 ffmpeg 滤镜链有**必须先决策的冲突**（风险 1）；
- 前置条件明确：先把 `neutralDspParams()` 和参数模型落到 `core/AudioEffects.kt`（纯数据，
  无原生依赖，风险为零），再接 native——这样万一 native 那条路走不通，前面的工作全部可复用。

---

## 8. 关键文件路径速查

| 用途 | 路径 |
| --- | --- |
| 上游 DSP 算法（5 个 .cpp，无 Android 依赖） | [`modules/native/src/main/cpp/dsp/`](../../../modules/native/src/main/cpp/dsp/) — `neri_dsp_primitives.cpp` / `neri_dsp_filter_stages.cpp` / `neri_dsp_space_stages.cpp` / `neri_dsp_dynamics_stages.cpp` / `neri_dsp_engine.cpp` |
| 上游 JNI 桥（唯一有 Android 依赖的文件） | `modules/native/src/main/cpp/dsp/neri_dsp_jni.cpp` |
| 104 参数布局（与 Kotlin 逐项对应） | `modules/native/src/main/cpp/dsp/neri_dsp_params.h` |
| 上游 CMake（`-Oz`→`-O2` 覆盖、`ANDROID` 分支） | `modules/native/src/main/cpp/dsp/CMakeLists.txt`、`cmake/NeriNativeCompileOptions.cmake` |
| Kotlin JNI 桥（可照抄的 `ensureLoaded`/兜底写法） | `modules/playback/runtime/.../effects/AudioEffectsNativeBridge.kt` |
| Media3 接入（对齐/尾部透传/reset 时机的参考） | `modules/playback/runtime/.../effects/AudioEffectsAudioSink.kt` |
| 104 参数 + `neutralDspParams()` 缺省值 | `modules/model/.../effects/NeriDspParams.kt` |
| 桌面音频引擎（`readLoop` / `applyVolume` / `buildFilterChain`） | `desktop/src/main/kotlin/.../core/AudioEngine.kt`（`FfmpegAudioEngine:420`、`JavaSoundAudioEngine:715`） |
| 桌面音效数据模型（参数落点） | `desktop/src/main/kotlin/.../core/AudioEffects.kt` |
| 打包脚本（`stage_payload()` 加一段即可） | `desktop/qt/packaging/build-packages.sh` |
| 后端 JVM 启动参数（缺 `java.library.path`） | `desktop/qt/src/backendclient.cpp:354-366` |
| rpm 显式文件列表（要补 `.so`） | `desktop/qt/packaging/neriplayer.spec.in` |
| 验证工程（本次产物，不在仓库内） | `/tmp/dspport/` — `CMakeLists.txt`、`compat/android/log.h`、`tests/dsp_smoke.cpp`、`jvm/**` |
