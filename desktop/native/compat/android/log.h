/*
 * android/log.h 垫片：让上游 dsp/neri_dsp_jni.cpp 在非 Android 平台直接编译。
 *
 * 上游唯一的 Android 依赖就是这个头（以及它提供的 __android_log_print）。
 * 桌面端没有 logcat，把日志写到 stderr 即可——后端的 stdout/stderr 会被
 * AppLog 的标准流桥接收进内存环形缓冲，日志查看器能看到。
 *
 * 只实现 JNI 桥用到的 ANDROID_LOG_WARN / ANDROID_LOG_INFO / ANDROID_LOG_ERROR。
 */
#pragma once

#include <cstdarg>
#include <cstdio>

#define ANDROID_LOG_UNKNOWN 0
#define ANDROID_LOG_DEFAULT 1
#define ANDROID_LOG_VERBOSE 2
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
#define ANDROID_LOG_FATAL 7
#define ANDROID_LOG_SILENT 8

static inline int __android_log_print(int priority, const char* tag, const char* format, ...) {
    const char* level = "I";
    switch (priority) {
        case ANDROID_LOG_VERBOSE:
        case ANDROID_LOG_DEBUG:
            level = "D";
            break;
        case ANDROID_LOG_INFO:
            level = "I";
            break;
        case ANDROID_LOG_WARN:
            level = "W";
            break;
        case ANDROID_LOG_ERROR:
        case ANDROID_LOG_FATAL:
            level = "E";
            break;
        default:
            level = "I";
            break;
    }
    std::fprintf(stderr, "[%s/%s] ", tag == nullptr ? "native" : tag, level);
    va_list args;
    va_start(args, format);
    const int written = std::vfprintf(stderr, format, args);
    va_end(args);
    std::fputc('\n', stderr);
    std::fflush(stderr);
    return written;
}
