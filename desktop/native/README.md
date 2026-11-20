# desktop/native · 上游 DSP 引擎的桌面构建

把上游 Android 版的 native 音效引擎（`modules/native/src/main/cpp/dsp/`）编成
Linux 桌面可加载的 `lib_neri.so`，由 Kotlin 后端通过 JNI 逐块处理 PCM。

## 目录

| 路径 | 内容 |
| --- | --- |
| `dsp/` | 从 `modules/native/src/main/cpp/dsp/` **原样复制**的 5 个算法源文件 + JNI 桥 + 头文件 |
| `compat/android/log.h` | 垫片：实现 `__android_log_print`（写 stderr） |
| `compat/glibc_pin.c` | 把 `log10f` 钉到 `GLIBC_2.2.5`，使产物能在旧发行版加载 |
| `CMakeLists.txt` | 独立 CMake 工程，只依赖 JDK 的 `jni.h` |

## 与上游的关系

`dsp/` 下的文件是从上游**逐字节复制**的，只改了必要的、桌面侧才有的部分：

- 只新增 `compat/` 两个垫片文件，**上游源码本身零改动**；
- 唯一的 Android 依赖是 `neri_dsp_jni.cpp` 里的 `#include <android/log.h>`，
  由 `compat/android/log.h` 用 `-I` 优先命中解决；
- 平台相关代码上游已写成双向条件编译（`__aarch64__` / `__x86_64__`），桌面直接命中 x86 分支。

同步上游时把 `modules/native/src/main/cpp/dsp/*.{cpp,h}` 覆盖到 `dsp/` 即可，
不需要重新打补丁。**JNI 导出符号名与包名硬编码绑定**，所以桌面端的桥类声明
（`desktop/src/main/kotlin/moe/ouom/neriplayer/core/player/audio/effects/NeriDspJni.kt`）
必须保留上游包名 `moe.ouom.neriplayer.core.player.audio.effects.AudioEffectsNativeBridge`。

许可证：本仓库与上游同为 GPL-3.0-only，复制/编译其源码在许可范围内，
安装包内随 `/usr/share/doc/neriplayer/copyright` 一起分发同一份许可证。

## 单独构建

```bash
cmake -S desktop/native -B desktop/native/build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build desktop/native/build -j"$(nproc)"
# 产物：desktop/native/build/lib_neri.so
```

`JAVA_HOME` 用来定位 `jni.h`（只需头文件，不链接 `libjvm`）。找不到时 CMake 会从
`javac` 的位置反推。

## 打包装载

`desktop/qt/packaging/build-packages.sh` 的 `stage_native_dsp()` 负责编这个工程
并 `install` 到 staging 的 `/usr/lib/neriplayer/lib_neri.so`；deb/rpm/AppImage
三种格式共用同一份 staging，rpm 的显式 `%files` 里也补了这一行。

后端按「jar 同目录的兄弟文件」定位 `.so`（安装包与 AppImage 都满足），
用 `System.load(绝对路径)` 加载——前端拉起后端时没有传 `-Djava.library.path`。
排障时可用系统属性 `-Dneriplayer.dsp.lib=<path>` 或环境变量
`NERIPLAYER_DSP_LIB=<path>` 覆盖。

## glibc 版本符号（打包必读）

新版 glibc 给 `log10f` 加了更精确的实现，并绑定到新版本符号（Debian 13 = `GLIBC_2.43`），
而 `neri_dsp_primitives.h` 的 `gainToDb()` 用了 `log10f`。**在本机打包出来的 `.so`
会要求 `GLIBC_2.43`，装到旧发行版上直接加载失败。**

`CMakeLists.txt` 默认启用 `NERI_DSP_PIN_GLIBC`，用 `compat/glibc_pin.c` 把引用钉回
`GLIBC_2.2.5`（旧 `log10f` 实现，误差 0.5 ULP，对 dB 换算无影响）。可用
`-DNERI_DSP_PIN_GLIBC=OFF` 关掉。CI 在 `ubuntu-22.04` 上构建，本来就只绑到 2.35 左右。

## 加载失败时的行为

`desktop/core/NativeDsp.kt` 在 `dlopen` 之前会做一次 ELF 校验（魔数 / 64 位小端 /
machine 与本机一致 / 文件长度覆盖所有 `PT_LOAD` 段）。这一步不能省：
**被截断的 `.so` 会让 `ld-linux` 在映射段时 SIGBUS，Java 侧接不住**，表现为播放器直接崩。

任何失败（文件缺失、被截断、架构不符、glibc 版本不符、参数布局不匹配）都只记一条
WARN 日志，然后回退到 ffmpeg 滤镜方案（`AudioEngine.applyAudioEffectsToFilters`），
播放不受影响，`audioEffects.get` 下发的 `capabilities` 会相应把 ffmpeg 表达不了的
参数标为未生效。
