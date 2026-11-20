package moe.ouom.neriplayer.core.player.audio.effects

/**
 * native DSP 的 JNI 声明。
 *
 * 这个文件刻意保留**上游 Android 的包名/类名**（`moe.ouom.neriplayer.core.player.audio.effects.AudioEffectsNativeBridge`）：
 * `desktop/native/` 里的 `lib_neri.so` 是从上游 `modules/native/src/main/cpp/dsp/` 原样复制编译的，
 * 导出符号被硬编码成 `Java_moe_ouom_neriplayer_core_player_audio_effects_AudioEffectsNativeBridge_*`。
 * 换包名/类名就必须改上游 C++，而这里的目标是保持上游源码零改动，所以声明放在上游同名类名下，
 * 加载与业务封装则在 `desktop/core/NativeDsp.kt`。
 *
 * 约定（上游 AudioEffectsNativeBridge 的接口注释）：**播放线程独占使用**，
 * 所有方法都必须在同一个音频线程调用；`setParams` 内部会重建滤波器，跨线程调用是未定义行为。
 * 唯一允许跨线程的是 [nativeSetParams] 的数组内容——但调用本身仍要在音频线程发起。
 */
internal object AudioEffectsNativeBridge {

    @JvmStatic
    external fun nativeParamCount(): Int

    @JvmStatic
    external fun nativeCreate(): Long

    @JvmStatic
    external fun nativeRelease(handle: Long)

    @JvmStatic
    external fun nativeConfigure(handle: Long, sampleRate: Int, channelCount: Int, encoding: Int): Boolean

    @JvmStatic
    external fun nativeSetParams(handle: Long, params: FloatArray): Boolean

    @JvmStatic
    external fun nativeReset(handle: Long)

    @JvmStatic
    external fun nativeProcess(
        handle: Long,
        input: java.nio.ByteBuffer,
        inputOffset: Int,
        output: java.nio.ByteBuffer,
        outputOffset: Int,
        size: Int,
    ): Int

    @JvmStatic
    external fun nativeReadStats(handle: Long, output: LongArray): Boolean
}
