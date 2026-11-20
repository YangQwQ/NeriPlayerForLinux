package moe.ouom.neriplayer.desktop.core

import moe.ouom.neriplayer.core.player.audio.effects.AudioEffectsNativeBridge
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

/**
 * 上游 native DSP 引擎（`lib_neri.so`）的加载与封装。
 *
 * 引擎来自 `desktop/native/`（源码从 `modules/native/src/main/cpp/dsp/` 原样复制，
 * 只加了一个 `android/log.h` 垫片），提供 10 个处理阶段：图形/参数均衡、低音/高音、
 * 虚拟低音、温暖度、激励器、人声清晰度/消除、立体声宽度、交叉馈送、环绕、混响、
 * 压缩、限幅与 dither。这些都是 ffmpeg 滤镜链表达不了或表达不准的部分。
 *
 * 三条硬约束（都来自上游接口约定与实测）：
 * 1. **一个 handle 只能在一个线程上调用**：`setParams` 内部会重建滤波器。所以 handle
 *    的创建/配置/处理/销毁都发生在播放线程（[NeriDspStage] 内部完成），配置线程只写
 *    `AtomicReference<FloatArray>`，音频线程在块边界取走 —— 无锁的 SPSC 手递手。
 * 2. **输入必须指向直接内存**：JNI 用 `GetDirectBufferAddress`，堆上的 `ByteBuffer`
 *    会返回 NULL（上游直接返回 -1）。
 * 3. **长度必须按帧对齐**：引擎拒绝 `size % frameBytes != 0`。`InputStream.read()`
 *    不保证帧对齐，见 [NeriDspProcessor.processInPlace] 里的尾部透传。
 */
object NativeDsp {

    private const val TAG = "native-dsp"

    /** 打包后与 jar 同目录（/usr/lib/neriplayer/ 或 AppImage 平铺目录）。 */
    private const val LIB_FILE_NAME = "lib_neri.so"

    /** 显式覆盖路径：系统属性优先于环境变量，便于排障。 */
    private const val LIB_PROPERTY = "neriplayer.dsp.lib"
    private const val LIB_ENV = "NERIPLAYER_DSP_LIB"

    private val loadLock = Any()

    /** null=尚未尝试加载；true/false=加载结果（失败后不再重试）。 */
    @Volatile
    private var loaded: Boolean? = null

    @Volatile
    private var loadedFrom: String? = null

    @Volatile
    private var failureReason: String = ""

    /** 运行期判定不可用（例如 `process` 连续失败）后永久关闭，避免每首歌重试。 */
    @Volatile
    private var disabled = false

    /**
     * 引擎当前是否可用。**每次调用都可能触发一次加载尝试**，加载成功后就变成纯读，
     * 所以可以在热路径（构建滤镜链）里直接用。
     */
    val isAvailable: Boolean get() = ensureLoaded()

    /** 给日志/自检用的一句话状态。 */
    val statusText: String
        get() = when {
            disabled -> "已禁用（$failureReason）"
            loaded == true -> "可用（$loadedFrom）"
            loaded == false -> "不可用（$failureReason）"
            else -> "未尝试加载"
        }

    /** 惰性加载；失败只记日志，绝不抛异常 —— 上层据此回退到 ffmpeg 滤镜方案。 */
    fun ensureLoaded(): Boolean {
        if (disabled) return false
        loaded?.let { return it }
        synchronized(loadLock) {
            loaded?.let { return it }
            val ok = attemptLoad()
            loaded = ok
            return ok
        }
    }

    /**
     * 运行期把引擎标记为不可用（加载失败或处理持续报错）。
     * 之后 [isAvailable] 恒为 false，播放链回到 ffmpeg 滤镜方案。
     */
    fun disable(reason: String) {
        if (disabled) return
        disabled = true
        failureReason = reason
        AppLog.warn(TAG, "native DSP 已关闭，回退到 ffmpeg 滤镜：$reason")
    }

    private fun attemptLoad(): Boolean {
        val candidates = candidates()
        for (file in candidates) {
            if (!file.isFile) continue
            // 先做 ELF 校验再交给 dlopen：被截断/架构不符的 .so 会让 ld-linux 在
            // 映射段时直接 SIGBUS/SIGSEGV —— 那是进程级崩溃，Java 侧**接不住**
            // （实测：2KB 的残包触发 SIGBUS，JVM 直接挂掉）。校验失败就换下一个候选。
            val rejection = validateElf(file)
            if (rejection != null) {
                failureReason = "${file.name}：$rejection"
                AppLog.warn(TAG, "跳过 $file：$rejection")
                continue
            }
            try {
                // 用绝对路径 System.load：后端由前端以 `java -jar` 拉起，没有传
                // -Djava.library.path，装到 /usr/lib/neriplayer/ 的 .so 不在默认搜索路径里。
                System.load(file.absolutePath)
            } catch (error: Throwable) {
                // UnsatisfiedLinkError / SecurityException / glibc 版本不符都在这里
                failureReason = "${file.name}：${error.javaClass.simpleName} ${error.message.orEmpty()}".trim()
                AppLog.warn(TAG, "加载 $file 失败：$failureReason")
                continue
            }
            val count = try {
                AudioEffectsNativeBridge.nativeParamCount()
            } catch (error: Throwable) {
                failureReason = "符号解析失败：${error.javaClass.simpleName} ${error.message.orEmpty()}".trim()
                AppLog.warn(TAG, failureReason)
                continue
            }
            if (count != NeriDspParams.COUNT) {
                // 数组布局对不上时宁可不用：下标错位会变成"调 A 响 B"，比没有 DSP 糟得多
                failureReason = "参数布局不匹配：native=$count kotlin=${NeriDspParams.COUNT}"
                AppLog.error(TAG, failureReason)
                continue
            }
            loadedFrom = file.absolutePath
            AppLog.info(TAG, "native DSP 引擎已加载：$loadedFrom（$count 参数）")
            return true
        }
        // 最后再试一次 java.library.path（开发期用 -Djava.library.path 指向构建目录时能命中）
        val fallback = runCatching {
            System.loadLibrary("_neri")
            AudioEffectsNativeBridge.nativeParamCount() == NeriDspParams.COUNT
        }.getOrDefault(false)
        if (fallback) {
            loadedFrom = "java.library.path"
            AppLog.info(TAG, "native DSP 引擎已通过 java.library.path 加载")
            return true
        }
        if (failureReason.isEmpty()) failureReason = "未找到 $LIB_FILE_NAME"
        AppLog.warn(TAG, "native DSP 不可用（$failureReason），音效将走 ffmpeg 滤镜回退方案")
        return false
    }

    /**
     * 在 dlopen 之前做最小 ELF 校验，避免把「装坏了的 .so」交给动态链接器。
     *
     * 为什么必须有这一步：`System.load` 对**损坏但文件存在**的库不是抛异常，而是让
     * ld-linux 在映射 PT_LOAD 段时越界访问，进程直接 SIGBUS——Java 的 try/catch 拦不到，
     * 用户看到的是「播放器打不开」。校验三件事：
     * 1. ELF 魔数 + 64 位小端（本项目只发布 x86_64/aarch64 的 64 位产物）；
     * 2. ELF machine 与本机架构一致（防止把 aarch64 的 .so 装到 x86_64 上）；
     * 3. 文件长度覆盖所有 PT_LOAD 段的末尾（截断包/下载不完整的典型特征）。
     *
     * @return null 表示通过；否则是给日志看的中文原因
     */
    private fun validateElf(file: File): String? = runCatching {
        val length = file.length()
        if (length < 64) return "文件过小（$length 字节），不是有效的 .so"
        java.io.RandomAccessFile(file, "r").use { raf ->
            val ident = ByteArray(16)
            raf.readFully(ident)
            if (ident[0] != 0x7F.toByte() || ident[1] != 'E'.code.toByte() ||
                ident[2] != 'L'.code.toByte() || ident[3] != 'F'.code.toByte()
            ) {
                return "不是 ELF 文件（魔数不符）"
            }
            if (ident[4] != 2.toByte() || ident[5] != 1.toByte()) {
                return "不是 64 位小端 ELF（class=${ident[4]}, data=${ident[5]}）"
            }
            val machine = readU16(raf, 18L)
            val expected = expectedMachine()
            if (expected != 0 && machine != expected) {
                return "架构不符：文件是 machine=$machine，本机是 $expected"
            }
            // 段表：e_phoff(0x20, 8B) / e_phentsize(0x36, 2B) / e_phnum(0x38, 2B)
            val phoff = readU64(raf, 0x20L)
            val phentsize = readU16(raf, 0x36L)
            val phnum = readU16(raf, 0x38L)
            if (phoff <= 0L || phentsize <= 0 || phnum <= 0) return "段表无效"
            var required = 0L
            var index = 0
            while (index < phnum) {
                val base = phoff + index.toLong() * phentsize
                // PT_LOAD 的 p_type 在段描述开头 4 字节
                if (readU32(raf, base) == 1L) {
                    // p_offset(0x08, 8B) 与 p_filesz(0x20, 8B)
                    val offset = readU64(raf, base + 0x08)
                    val fileSize = readU64(raf, base + 0x20)
                    required = maxOf(required, offset + fileSize)
                }
                index += 1
            }
            if (required <= 0L) return "没有可加载的段（PT_LOAD）"
            if (length < required) {
                return "文件被截断：需要 $required 字节，实际只有 $length 字节"
            }
            null
        }
    }.getOrElse { error ->
        // 文件长度不足以容纳它自己声称的段表 —— 截断包的另一个特征
        if (error is java.io.EOFException) {
            "文件被截断（段表超出文件末尾）"
        } else {
            "读取失败：${error.javaClass.simpleName} ${error.message.orEmpty()}".trim()
        }
    }

    /** 本机 ELF machine（0x3E = x86_64，0xB7 = aarch64）；其它架构返回 0 表示不校验。 */
    private fun expectedMachine(): Int = when (System.getProperty("os.arch")?.lowercase()) {
        "amd64", "x86_64" -> 0x3E
        "aarch64", "arm64" -> 0xB7
        else -> 0
    }

    // ELF 头部字段按文件字节序存放；ident[5] 已校验为 1（小端），所以统一按小端读。
    private fun readU16(raf: java.io.RandomAccessFile, at: Long): Int {
        raf.seek(at)
        val low = raf.readUnsignedByte()
        val high = raf.readUnsignedByte()
        return (high shl 8) or low
    }

    private fun readU32(raf: java.io.RandomAccessFile, at: Long): Long {
        raf.seek(at)
        var value = 0L
        repeat(4) { index -> value = value or (raf.readUnsignedByte().toLong() shl (8 * index)) }
        return value
    }

    private fun readU64(raf: java.io.RandomAccessFile, at: Long): Long {
        raf.seek(at)
        var value = 0L
        repeat(8) { index -> value = value or (raf.readUnsignedByte().toLong() shl (8 * index)) }
        return value
    }

    /**
     * 候选 .so 路径，按优先级排列：
     * 1. 系统属性 `neriplayer.dsp.lib` / 环境变量 `NERIPLAYER_DSP_LIB`（手工指定）；
     * 2. jar 同目录（安装包、AppImage）；
     * 3. 从 code source 往上找 `native/build/lib_neri.so`（`tools/dev.sh` 的构建产物布局）；
     * 4. 系统安装路径。
     */
    private fun candidates(): List<File> {
        val result = LinkedHashSet<File>()
        System.getProperty(LIB_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() }?.let { result += File(it) }
        System.getenv(LIB_ENV)?.trim()?.takeIf { it.isNotEmpty() }?.let { result += File(it) }
        val codeDir = codeSourceDir()
        if (codeDir != null) {
            result += File(codeDir, LIB_FILE_NAME)
            var dir: File? = codeDir.absoluteFile
            repeat(6) {
                val current = dir ?: return@repeat
                result += File(current, "native/build/$LIB_FILE_NAME")
                dir = current.parentFile
            }
        }
        result += File("/usr/lib/neriplayer/$LIB_FILE_NAME")
        return result.map { it.absoluteFile }
    }

    /** jar/类目录的父目录；取不到时返回 null（例如自定义 ClassLoader）。 */
    private fun codeSourceDir(): File? = runCatching {
        val source = NativeDsp::class.java.protectionDomain?.codeSource?.location ?: return null
        val file = File(source.toURI())
        if (file.isDirectory) file else file.parentFile
    }.getOrNull()

    /**
     * 一次性建好并配置引擎；播放链在音频线程里调用。
     *
     * @return 配置失败的引擎（采样率/声道/位深不在引擎支持范围内，如 3 声道）返回 null，
     *   调用方直接旁路，不影响播放。
     */
    fun createProcessor(sampleRate: Int, channelCount: Int, encoding: Int, maxBlockBytes: Int): NeriDspProcessor? {
        if (!ensureLoaded()) return null
        val handle = try {
            AudioEffectsNativeBridge.nativeCreate()
        } catch (error: Throwable) {
            disable("nativeCreate 失败：${error.javaClass.simpleName}")
            return null
        }
        if (handle == 0L) {
            disable("nativeCreate 返回空句柄")
            return null
        }
        val processor = NeriDspProcessor(handle, frameBytesFor(encoding, channelCount), maxBlockBytes)
        if (!processor.configure(sampleRate, channelCount, encoding)) {
            processor.close()
            // 记住这条格式不可用：否则调用方重建流水线后会再次选中 native，形成重启循环
            probed["$sampleRate/$channelCount/$encoding"] = false
            AppLog.warn(
                TAG,
                "native DSP 不支持该音频格式（${sampleRate}Hz/${channelCount}ch/编码$encoding），本条流水线走回退方案",
            )
            return null
        }
        return processor
    }

    /**
     * 探测这套格式能不能被引擎处理（建引擎 → configure → 销毁，全程同一个线程）。
     *
     * 为什么需要它：ffmpeg 流水线的**滤镜链在子进程启动时就固定了**。如果起播时以为
     * native 能处理、结果 `configure` 被拒，这条流水线就会"既没有 filters 也没有 DSP"，
     * 声音完全不处理。所以先探一次，探测结果决定这条流水线走哪条路。
     *
     * 结果按 (sampleRate, channels, encoding) 缓存：同一进程里就只有一次开销。
     */
    private val probed = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun probeFormat(sampleRate: Int, channelCount: Int, encoding: Int): Boolean {
        val key = "$sampleRate/$channelCount/$encoding"
        probed[key]?.let { return it }
        val ok = createProcessor(sampleRate, channelCount, encoding, maxBlockBytes = 4096)?.let { processor ->
            processor.close()
            true
        } ?: false
        probed[key] = ok
        if (!ok) {
            AppLog.warn(TAG, "native DSP 不支持 ${sampleRate}Hz/${channelCount}ch/编码$encoding，这条流水线走 ffmpeg 滤镜")
        }
        return ok
    }

    /** 每帧字节数；编码非法时返回 0（调用方据此旁路）。 */
    private fun frameBytesFor(encoding: Int, channelCount: Int): Int {
        val sampleBytes = when (encoding) {
            NeriDspEncoding.PCM_16BIT -> 2
            NeriDspEncoding.PCM_24BIT -> 3
            NeriDspEncoding.PCM_32BIT, NeriDspEncoding.PCM_FLOAT -> 4
            else -> 0
        }
        return sampleBytes * channelCount
    }
}

/** native 引擎句柄的薄封装；**只在创建它的那个线程上使用**（见 [NativeDsp] 的约束 1）。 */
class NeriDspProcessor internal constructor(
    private var handle: Long,
    private val frameBytes: Int,
    maxBlockBytes: Int,
) {
    private var input: ByteBuffer = ByteBuffer.allocateDirect(maxBlockBytes.coerceAtLeast(4096))
        .order(ByteOrder.nativeOrder())
    private var output: ByteBuffer = ByteBuffer.allocateDirect(maxBlockBytes.coerceAtLeast(4096))
        .order(ByteOrder.nativeOrder())

    /** 最近一次 `process` 的状态位（Engine::kStatus*），用于日志。 */
    var lastStatus: Int = 0
        private set

    /** 处理的帧总数（自上次 stats 读取），用于「确实在处理」的证据。 */
    val active: Boolean get() = handle != 0L

    internal fun configure(sampleRate: Int, channelCount: Int, encoding: Int): Boolean = try {
        handle != 0L && AudioEffectsNativeBridge.nativeConfigure(handle, sampleRate, channelCount, encoding)
    } catch (error: Throwable) {
        NativeDsp.disable("nativeConfigure 异常：${error.javaClass.simpleName}")
        false
    }

    /** 104 个参数；调用方保证数组长度正确（[NeriDspParams.COUNT]）。 */
    fun setParams(params: FloatArray): Boolean = try {
        handle != 0L && AudioEffectsNativeBridge.nativeSetParams(handle, params)
    } catch (error: Throwable) {
        NativeDsp.disable("nativeSetParams 异常：${error.javaClass.simpleName}")
        false
    }

    fun reset() {
        if (handle == 0L) return
        runCatching { AudioEffectsNativeBridge.nativeReset(handle) }
    }

    fun readStats(output: LongArray): Boolean =
        handle != 0L && runCatching { AudioEffectsNativeBridge.nativeReadStats(handle, output) }.getOrDefault(false)

    /**
     * 原地处理一块 PCM（16bit 立体声 48kHz 时一帧 4 字节）。
     *
     * `InputStream.read()` 回来长度可能不是帧的整数倍（管道边界、文件尾），而引擎对
     * 非对齐长度直接返回 -1。这里照上游 `AudioEffectsAudioSink.runEngine()` 的做法：
     * 只处理 `length - length % frameBytes`，尾部不足一帧的 1..3 字节**原样留在缓冲里**
     * 往下游写（它们与下一块开头拼成完整帧，单纯透传不会破坏波形连续性）。
     *
     * @return false 表示引擎报错（调用方应旁路并计数），true 表示本块已处理或无需处理
     */
    fun processInPlace(buffer: ByteArray, length: Int): Boolean {
        if (handle == 0L) return false
        val aligned = length - length % frameBytes
        if (aligned <= 0) return true
        if (input.capacity() < aligned) {
            input = ByteBuffer.allocateDirect(aligned).order(ByteOrder.nativeOrder())
            output = ByteBuffer.allocateDirect(aligned).order(ByteOrder.nativeOrder())
        }
        input.clear()
        input.put(buffer, 0, aligned)
        output.clear()
        lastStatus = try {
            // inputOffset/outputOffset 传 0：JNI 的 GetDirectBufferAddress 返回缓冲基址，
            // 数据已经放在基址开头。
            AudioEffectsNativeBridge.nativeProcess(handle, input, 0, output, 0, aligned)
        } catch (error: Throwable) {
            NativeDsp.disable("nativeProcess 异常：${error.javaClass.simpleName}")
            -1
        }
        if (lastStatus < 0) return false
        output.position(0)
        output.limit(aligned)
        output.get(buffer, 0, aligned)
        return true
    }

    fun close() {
        val current = handle
        handle = 0L
        if (current == 0L) return
        runCatching { AudioEffectsNativeBridge.nativeRelease(current) }
            .onFailure { NativeDsp.disable("nativeRelease 异常：${it.javaClass.simpleName}") }
    }
}

/**
 * 播放链上的 native DSP 段落：懒建引擎 + 每块边界同步参数 + 失败计数。
 *
 * 参数来自配置线程写入的 [params]（`AtomicReference<FloatArray>`），音频线程只读，
 * 满足上游「所有 JNI 调用都在同一个音频线程」的约定；`setParams` 实测 0.48 µs/次，
 * 所以每块无条件同步一次，省掉脏标记。
 */
class NeriDspStage(
    private val sampleRate: Int,
    private val channelCount: Int,
    private val encoding: Int,
    private val params: AtomicReference<FloatArray?>,
    private val maxBlockBytes: Int = 1 shl 15,
) {
    private var processor: NeriDspProcessor? = null
    private var attempted = false
    private var bypassed = false
    private var failures = 0
    private var reported = false
    private val stats = LongArray(8)

    /** native 是否真的在这条流上生效（供日志与自检判断）。 */
    val engaged: Boolean get() = processor?.active == true

    /**
     * 音频线程每块调用一次；内部完成参数同步与原地处理。
     *
     * @return false 表示 native 在这条流上已经不可用（引擎创建失败或连续处理失败），
     *   调用方应重建流水线走 ffmpeg 回退路径——否则这段音频会既没有 native 处理、
     *   也没有滤镜处理。
     */
    fun process(buffer: ByteArray, length: Int): Boolean {
        if (bypassed) return true
        val wanted = params.get() ?: return true
        // 引擎建不起来（极罕见，probeFormat 已在起播前校验过同一格式）就返回 false，
        // 让调用方重建流水线走滤镜回退 —— 不能假装成功，否则这段音频谁都没处理。
        val engine = processor ?: obtain() ?: return false
        engine.setParams(wanted)
        if (engine.processInPlace(buffer, length)) {
            failures = 0
            if (!reported) {
                reported = true
                reportEngaged(engine)
            }
            return true
        }
        failures += 1
        if (failures >= MAX_FAILURES) {
            bypassed = true
            processor = null
            engine.close()
            NativeDsp.disable("process 连续失败 $failures 次")
            return false
        }
        return true
    }

    fun close() {
        processor?.close()
        processor = null
        bypassed = true
    }

    private fun obtain(): NeriDspProcessor? {
        if (attempted) return null
        attempted = true
        val created = NativeDsp.createProcessor(sampleRate, channelCount, encoding, maxBlockBytes)
        if (created == null) {
            bypassed = true
            return null
        }
        processor = created
        return created
    }

    private fun reportEngaged(engine: NeriDspProcessor) {
        val mask = if (engine.readStats(stats)) stats[5] else 0L
        AppLog.info(
            TAG,
            "native DSP 已接管 PCM：${sampleRate}Hz/${channelCount}ch/编码$encoding " +
                "限幅 ${stats[2] / 100f}dB 压缩 ${stats[4] / 100f}dB activeStageMask=0x${mask.toString(16)}",
        )
    }

    private companion object {
        const val TAG = "native-dsp"

        /** 连续失败阈值：偶发一次（例如驱动/管道边界）不该立刻放弃整首歌。 */
        const val MAX_FAILURES = 3
    }
}
