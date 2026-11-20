/*
 * glibc 版本符号钉版（仅 x86_64 glibc）。
 *
 * 上游 neri_dsp_primitives.h 的 gainToDb() 用了 log10f，而新版 glibc 给 log10f
 * 加了更精确的实现并绑定到新版本符号（Debian 13 上是 GLIBC_2.43）。在本机直接
 * 编出来的 lib_neri.so 因此只依赖 libm 的新符号，装到旧发行版（Ubuntu 22.04 /
 * Debian 12）上会 "GLIBC_2.43 not found" 直接加载失败。
 *
 * 处理方式：本文件在 .so 内部提供本地的 log10f，内部调用钉到 GLIBC_2.2.5 的
 * 兼容符号。这样整个 .so 对 log10f 的引用都落在 2.2.5，旧系统也能加载。
 * 旧实现与新实现的差异在 0.5 ULP 量级，对 dB 换算没有可听影响。
 *
 * 符号可见性设为 hidden：不把自己的 log10f 导出给宿主进程，避免影响其它库。
 */
#include <features.h>

#if defined(__x86_64__) && defined(__linux__) && defined(__GLIBC__)

extern float neri_log10f_compat(float);
__asm__(".symver neri_log10f_compat,log10f@GLIBC_2.2.5");

__attribute__((visibility("hidden"))) float log10f(float value) {
    return neri_log10f_compat(value);
}

#endif
