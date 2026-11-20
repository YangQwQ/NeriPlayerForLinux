# 桌面端 native DSP 引擎（独立工程，不参与 Qt 前端构建）。
#
# 把上游 modules/native/src/main/cpp/dsp/ 的 5 个纯算法文件 + JNI 桥编成
# lib_neri.so，由 Kotlin 后端用 System.load(绝对路径) 加载，见 core/NativeDsp.kt。
# 之所以独立成一个 CMake 工程：编 Qt 前端不应当需要 JDK，而编这个又必须要有
# jni.h；两者耦合会让 `tools/dev.sh build` 多出一个构建依赖。
#
# 单独调试：
#   cmake -S desktop/native -B desktop/native/build -G Ninja -DCMAKE_BUILD_TYPE=Release
#   cmake --build desktop/native/build
# 产物：desktop/native/build/lib_neri.so（后端在开发期直接从这里加载）
cmake_minimum_required(VERSION 3.20)
project(neri_dsp_desktop LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "构建类型" FORCE)
endif()

set(NERI_NATIVE_DIR "${CMAKE_CURRENT_SOURCE_DIR}")
set(NERI_COMPAT_DIR "${NERI_NATIVE_DIR}/compat")

# 算法源码（从上游原样复制，目录结构与 modules/native/src/main/cpp 保持一致）
set(NERI_DSP_SOURCES
    dsp/neri_dsp_primitives.cpp
    dsp/neri_dsp_filter_stages.cpp
    dsp/neri_dsp_space_stages.cpp
    dsp/neri_dsp_dynamics_stages.cpp
    dsp/neri_dsp_engine.cpp
)

# --- JNI 头 -------------------------------------------------------------------
# CMake >= 3.24 的 FindJNI 在非 Android 平台默认要求 AWT 组件，而 headless JDK
# 没有 libjawt.so，会直接 FATAL_ERROR。编 JNI .so 只需要 jni.h + jni_md.h
# （不链接 libjvm），所以先只请求 JVM，失败再手工从 JAVA_HOME 推导。
find_package(JNI QUIET COMPONENTS JVM)
if(JNI_FOUND AND JNI_INCLUDE_DIRS)
    set(NERI_JNI_INCLUDE_DIRS ${JNI_INCLUDE_DIRS})
else()
    set(_neri_java_home "${JAVA_HOME}")
    if(NOT _neri_java_home)
        execute_process(
            COMMAND /bin/sh -c "dirname $(dirname $(readlink -f $(command -v javac)))"
            OUTPUT_VARIABLE _neri_java_home
            OUTPUT_STRIP_TRAILING_WHITESPACE
            ERROR_QUIET
        )
    endif()
    if(NOT _neri_java_home OR NOT EXISTS "${_neri_java_home}/include/jni.h")
        message(FATAL_ERROR
            "找不到 jni.h：请设置 JAVA_HOME 指向 JDK（需要 JDK 17 或更新），"
            "或用 -DJAVA_HOME=<path> 传进来。")
    endif()
    set(NERI_JNI_INCLUDE_DIRS "${_neri_java_home}/include" "${_neri_java_home}/include/linux")
endif()
message(STATUS "NERI native DSP JNI headers: ${NERI_JNI_INCLUDE_DIRS}")

# --- 算法静态库 ---------------------------------------------------------------
add_library(neri_dsp_core STATIC ${NERI_DSP_SOURCES})
# 实时音频线程按速度优化；警告全开保持与上游一致的干净编译
target_compile_options(neri_dsp_core PRIVATE -O2 -Wall -Wextra)
target_include_directories(neri_dsp_core PUBLIC "${NERI_NATIVE_DIR}")

# --- glibc 版本符号（开发机打包必须看这一条）-----------------------------------
# 新版 glibc 给 log10f 加了更精确的实现并绑到新版本符号（本机 Debian 13 上是
# GLIBC_2.43），而 neri_dsp_primitives.h 的 gainToDb 用了它 ⇒ 在本机编出来的
# lib_neri.so 只能装在同版本或更新的系统上，旧发行版会直接
# "GLIBC_2.43 not found" 打不开。
# 这里把引用钉回最初的 GLIBC_2.2.5 版本符号（旧的 log10f 实现，误差在 0.5 ULP
# 量级，对 dB 换算没有影响）。只在 x86_64 glibc 上启用；链接不过就自动关掉，
# 不会把构建搞死（CI 在 ubuntu-22.04 上编，本来就只会绑到 2.35）。
option(NERI_DSP_PIN_GLIBC "把 log10f 钉到 GLIBC_2.2.5 版本符号以便旧发行版加载" ON)
set(NERI_DSP_PINNED_SOURCE "")
if(NERI_DSP_PIN_GLIBC AND CMAKE_SYSTEM_NAME STREQUAL "Linux" AND CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64")
    include(CheckCSourceCompiles)
    set(CMAKE_REQUIRED_LIBRARIES m)
    # 试编译与 compat/glibc_pin.c 相同的最小片段，确认这套 glibc 真的有 2.2.5 兼容符号
    check_c_source_compiles("
        #include <math.h>
        extern float neri_probe_log10f(float);
        __asm__(\".symver neri_probe_log10f,log10f@GLIBC_2.2.5\");
        __attribute__((visibility(\"hidden\"))) float log10f(float v) { return neri_probe_log10f(v); }
        int main(void) { return (int)log10f(10.0f); }
    " NERI_HAVE_GLIBC_LOG10F_225)
    if(NERI_HAVE_GLIBC_LOG10F_225)
        # 必须直接编进 .so（不能是静态库成员：没人引用就不会被 ld 拉进来）
        set(NERI_DSP_PINNED_SOURCE compat/glibc_pin.c)
        message(STATUS "NERI native DSP: log10f 钉到 GLIBC_2.2.5（产物可在旧发行版加载）")
    else()
        message(STATUS "NERI native DSP: 本机 glibc 无 2.2.5 兼容符号，跳过符号钉版")
    endif()
endif()

# --- JNI 共享库 ---------------------------------------------------------------
add_library(neri_dsp SHARED dsp/neri_dsp_jni.cpp ${NERI_DSP_PINNED_SOURCE})
# 垫片目录必须排在系统头之前：android/log.h 只在本工程的 compat/ 下存在
target_include_directories(neri_dsp PRIVATE
    "${NERI_COMPAT_DIR}"
    "${NERI_NATIVE_DIR}"
    ${NERI_JNI_INCLUDE_DIRS}
)
target_compile_options(neri_dsp PRIVATE -O2 -Wall -Wextra)
target_link_libraries(neri_dsp PRIVATE neri_dsp_core m)
set_target_properties(neri_dsp PROPERTIES
    OUTPUT_NAME "_neri"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
)
