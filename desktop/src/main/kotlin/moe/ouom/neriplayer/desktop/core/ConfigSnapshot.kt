package moe.ouom.neriplayer.desktop.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 设置快照的导出 / 校验 / 导入（阶段 A，见 desktop/docs/investigations/config-transfer-debug.md）。
 *
 * 快照里**只有 [AppSettings]**——也就是「偏好」这一层：
 * 不含 Cookie、Token、账号信息、同步配置（它们分别住在 `accounts.json` / `sync.json`，
 * 是 600 权限的本地机密，把它变成可随手分享的普通文件属于安全模型的改变，本期明确不做）。
 * 业务数据（歌单、收藏、历史、统计）也不在快照里，它们走 GitHub 同步。
 *
 * 导入的原则：**先把整份文件校验完，再一次性替换**。
 * 校验失败时不碰内存、不碰磁盘，用户现有的设置原样保留；
 * 只有全部检查通过后才调用 [SettingsRepository.replace]，配合 `JsonFileStore` 的
 * 「临时文件 + 原子重命名」写盘，不存在「写了一半」的中间态。
 *
 * 与本文件配套的协议文档：desktop/docs/protocol-config.md。
 */
object ConfigSnapshot {

    /** 快照文件类型标识（写进文件，导入时校验）。 */
    const val KIND = "moe.ouom.neriplayer.config.settings"

    /** 当前格式版本。导入允许 1..CURRENT 的旧版本，破坏性变更时递增。 */
    const val FORMAT_VERSION = 1

    /** 平台标识：本仓库只有 Linux 桌面版，Android 备份包不在此接受范围内。 */
    const val PLATFORM = "desktop"

    /** 仅作信息展示；真正的版本号在 AppApi.bootstrapPayload 里，这里不参与任何判断。 */
    const val APP_VERSION = "2.1.0"

    /** 快照文件大小上限：设置只有几百个字段，超过这个量级说明选错文件了。 */
    const val MAX_FILE_BYTES = 2L * 1024 * 1024

    const val DEFAULT_PREFIX = "neriplayer-settings-"

    private val SnapshotJson = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private val timestampFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** 快照文件结构。`settings` 用 JsonObject 承载，便于逐字段校验与「局部文件」合并。 */
    @Serializable
    data class SnapshotDocument(
        val kind: String = KIND,
        val formatVersion: Int = FORMAT_VERSION,
        val platform: String = PLATFORM,
        val exportedAt: Long = 0L,
        val appVersion: String = APP_VERSION,
        /** 字段数量，纯粹给人读（导入时以 settings 实际内容为准）。 */
        val fieldCount: Int = 0,
        /** 出于安全考虑被排除的字段名（目前 AppSettings 里没有凭据字段，这里只是兜底）。 */
        val omittedFields: List<String> = emptyList(),
        val settings: JsonObject = JsonObject(emptyMap()),
    )

    /** 一份已经过完整校验、可以直接拿去落地的导入结果。 */
    data class PreparedImport(
        val settings: AppSettings,
        val path: String,
        val exportedAt: Long,
        val formatVersion: Int,
        val platform: String,
        val appVersion: String,
        /** 与当前设置相比真正发生变化的字段。 */
        val changedFields: List<String>,
        /** 文件里缺失、因此保留当前值的字段（手改/裁剪过的文件会出现）。 */
        val keptFields: List<String>,
        /** 文件里不认识、被忽略的字段（多半来自更新版本）。 */
        val ignoredFields: List<String>,
        /** 文件里出现但被丢弃的敏感字段名。 */
        val omittedFields: List<String>,
        /** 自动修正（数值夹取、大小写归一）的说明。 */
        val warnings: List<String>,
    )

    /** 快照相关错误；[code] 会成为协议错误码（见 protocol-config.md）。 */
    class SnapshotException(val code: String, message: String) : RuntimeException(message)

    // ------------------------------------------------------------------ 导出

    /** 默认导出文件名：~/neriplayer-settings-<yyyyMMdd-HHmmss>.json（不覆盖既有文件）。 */
    fun defaultExportFile(now: Long = System.currentTimeMillis()): File {
        val home = File(System.getProperty("user.home"))
        val stamp = LocalDateTime.now().format(timestampFormat)
        var candidate = File(home, "$DEFAULT_PREFIX$stamp.json")
        var index = 2
        while (candidate.exists()) {
            candidate = File(home, "$DEFAULT_PREFIX$stamp-$index.json")
            index += 1
        }
        return candidate
    }

    /**
     * 把设置编码成快照文档。
     *
     * `settings` 里若出现名字像凭据的字段（cookie/token/password…）会被剔除并记录到
     * `omittedFields`——当前 [AppSettings] 并没有这种字段，这是给将来加字段时的兜底，
     * 避免有人不小心把 Token 塞进偏好里之后被顺手导出。
     */
    fun buildDocument(settings: AppSettings, exportedAt: Long = System.currentTimeMillis()): SnapshotDocument {
        val encoded = SnapshotJson.encodeToJsonElement(AppSettings.serializer(), settings).jsonObject
        val omitted = encoded.keys.filter { looksLikeSecretField(it) }.sorted()
        val filtered = JsonObject(encoded.filterKeys { it !in omitted })
        return SnapshotDocument(
            kind = KIND,
            formatVersion = FORMAT_VERSION,
            platform = PLATFORM,
            exportedAt = exportedAt,
            appVersion = APP_VERSION,
            fieldCount = filtered.size,
            omittedFields = omitted,
            settings = filtered,
        )
    }

    /**
     * 把文档写到目标路径（先写临时文件再重命名，与 `JsonFileStore` 的原子写保持一致）。
     * @return 实际写入的字节数
     */
    fun write(document: SnapshotDocument, target: File): Long {
        ensureSafeTarget(target)
        val text = SnapshotJson.encodeToString(SnapshotDocument.serializer(), document)
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) {
            throw SnapshotException("too_large", "快照超过 $MAX_FILE_BYTES 字节，已取消导出")
        }
        try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeBytes(bytes)
            if (target.exists() && !target.delete()) {
                tmp.delete()
                throw SnapshotException("io_error", "无法覆盖已存在的文件：${target.absolutePath}")
            }
            if (!tmp.renameTo(target)) {
                val fallback = runCatching { target.writeBytes(bytes) }.isSuccess
                tmp.delete()
                if (!fallback) throw SnapshotException("io_error", "写入失败：${target.absolutePath}")
            }
        } catch (error: SnapshotException) {
            throw error
        } catch (error: Throwable) {
            throw SnapshotException("io_error", "写入快照失败：${error.message ?: error::class.simpleName}")
        }
        return bytes.size.toLong()
    }

    // ------------------------------------------------------------------ 导入：读取 + 校验

    /**
     * 读取并完整校验一份快照文件；**不修改任何状态**。
     * 任何一步不通过都抛 [SnapshotException]，调用方据此不做替换。
     */
    fun readAndValidate(path: String, currentProvider: () -> AppSettings): PreparedImport {
        val file = File(path.trim())
        if (!file.isAbsolute) {
            throw SnapshotException("bad_request", "path 必须是绝对路径：$path")
        }
        if (!file.exists()) throw SnapshotException("not_found", "文件不存在：${file.absolutePath}")
        if (file.isDirectory) throw SnapshotException("bad_request", "path 是目录而不是文件：${file.absolutePath}")
        if (!file.canRead()) throw SnapshotException("io_error", "文件不可读：${file.absolutePath}")
        if (file.length() > MAX_FILE_BYTES) {
            throw SnapshotException(
                "too_large",
                "文件过大（${file.length()} 字节，上限 $MAX_FILE_BYTES）：${file.absolutePath}",
            )
        }
        val text = runCatching { file.readText() }.getOrElse {
            throw SnapshotException("io_error", "读取失败：${it.message ?: it::class.simpleName}")
        }

        val root = runCatching { SnapshotJson.parseToJsonElement(text).jsonObject }.getOrElse {
            throw SnapshotException("invalid_snapshot", "不是合法的 JSON 对象：${it.message ?: "解析失败"}")
        }

        val kind = root.stringField("kind")
            ?: throw SnapshotException("invalid_snapshot", "缺少 kind 字段，不是 NeriPlayer 设置快照")
        if (kind != KIND) {
            val hint = when {
                kind.startsWith("moe.ouom.neriplayer.config.settings") -> ""
                kind == "moe.ouom.neriplayer.config" || kind.startsWith("moe.ouom.neriplayer.config.") ->
                    "（这是配置备份包或其它子类型，本版本只接受设置快照）"

                kind.contains("neriplayer", ignoreCase = true) -> "（kind 不匹配）"
                else -> "（看起来不是 NeriPlayer 的文件）"
            }
            throw SnapshotException("unsupported_kind", "不支持的文件类型：$kind$hint")
        }

        val version = root.intField("formatVersion")
            ?: throw SnapshotException("invalid_snapshot", "缺少 formatVersion 字段")
        if (version < 1) throw SnapshotException("unsupported_version", "formatVersion 非法：$version")
        if (version > FORMAT_VERSION) {
            throw SnapshotException(
                "unsupported_version",
                "快照版本 $version 高于本程序支持的 $FORMAT_VERSION，请升级客户端后重试",
            )
        }

        val platform = root.stringField("platform") ?: ""
        if (platform.isNotEmpty() && platform != PLATFORM) {
            throw SnapshotException(
                "unsupported_platform",
                "快照来自「$platform」平台，本程序只接受 $PLATFORM 平台的设置快照",
            )
        }

        val settingsObject = (root["settings"] as? JsonObject)
            ?: throw SnapshotException("invalid_snapshot", "缺少 settings 字段或它不是对象")
        if (settingsObject.isEmpty()) {
            throw SnapshotException("invalid_snapshot", "settings 为空，没有可导入的内容")
        }

        val warnings = ArrayList<String>(4)
        val omittedFields = settingsObject.keys.filter { looksLikeSecretField(it) }.sorted().toMutableList()
        if (omittedFields.isNotEmpty()) {
            warnings += "已忽略敏感字段：" + omittedFields.joinToString(", ")
        }
        val rawSettings = JsonObject(settingsObject.filterKeys { it !in omittedFields })

        val knownKeys = knownFieldNames()
        val ignoredFields = rawSettings.keys.filter { it !in knownKeys }.sorted()
        if (ignoredFields.isNotEmpty()) {
            warnings += "已忽略不认识的字段：" + ignoredFields.joinToString(", ")
        }
        // 未知字段不参与解码（decode 会忽略），先剔掉让后续枚举校验只管认识的字段
        val effective = JsonObject(rawSettings.filterKeys { it in knownKeys })

        val keptFields = knownKeys.filter { it !in effective }.sorted()

        val normalized = normalizeEnumCase(effective, warnings)
        // 文件里缺的字段保留当前值：允许「手改过的精简文件」只覆盖它写到的部分。
        // 基准值在「文件已经读完」之后才取：读盘期间播放器可能刚改过音量（updateDeferred），
        // 晚取一点可以让 keptFields 与真正落地的当前状态更接近。
        val currentObject = SnapshotJson.encodeToJsonElement(AppSettings.serializer(), currentProvider()).jsonObject
        val merged = JsonObject(currentObject + normalized)
        val decoded = runCatching {
            SnapshotJson.decodeFromJsonElement(AppSettings.serializer(), merged)
        }.getOrElse { error ->
            throw SnapshotException(
                "invalid_snapshot",
                "设置字段格式非法：${error.message ?: error::class.simpleName}",
            )
        }
        val validated = validateValues(decoded, warnings)

        // 变化字段以「当前设置」为基准，而不是默认值：导入同一份文件时 changedFields 应为空
        val changed = diffFields(
            currentObject,
            SnapshotJson.encodeToJsonElement(AppSettings.serializer(), validated).jsonObject,
        )

        return PreparedImport(
            settings = validated,
            path = file.absolutePath,
            exportedAt = root.longField("exportedAt") ?: 0L,
            formatVersion = version,
            platform = platform.ifEmpty { PLATFORM },
            appVersion = root.stringField("appVersion") ?: "",
            changedFields = changed,
            keptFields = keptFields,
            ignoredFields = ignoredFields,
            omittedFields = omittedFields,
            warnings = warnings,
        )
    }

    // ------------------------------------------------------------------ 字段校验

    /** 已知字段名（用一份默认设置编码出来，避免手写一百个字段名）。 */
    private fun knownFieldNames(): Set<String> =
        SnapshotJson.encodeToJsonElement(AppSettings.serializer(), AppSettings()).jsonObject.keys

    private fun diffFields(before: JsonObject, after: JsonObject): List<String> =
        (before.keys + after.keys).filter { before[it] != after[it] }.sorted()

    private val COLOR_NAMES = listOf(
        "WHITE", "BLACK", "RED", "ORANGE", "YELLOW", "GREEN", "CYAN", "BLUE", "PURPLE", "PINK",
    )

    /** 字符串枚举 → 允许值。大小写不匹配时自动归一，取值不在集合内直接拒绝。 */
    private val ENUM_FIELDS: Map<String, List<String>> = mapOf(
        "darkMode" to listOf("AUTO", "LIGHT", "DARK"),
        "paletteStyle" to listOf(
            "TonalSpot", "Neutral", "Vibrant", "Expressive", "Rainbow",
            "FruitSalad", "Monochrome", "Fidelity", "Content",
        ),
        "colorSpec" to listOf("SPEC_2021", "SPEC_2025"),
        "language" to listOf("zh-CN", "en"),
        "qualityPreference" to listOf("standard", "higher", "exhigh", "lossless", "hires"),
        "downloadQuality" to listOf("standard", "higher", "exhigh", "lossless", "hires"),
        "lyricsAlignment" to listOf("LEFT", "CENTER", "RIGHT"),
        "lyricsHighlight" to listOf("LINE", "WORD"),
        "lyricsScrollEasing" to listOf("LINEAR", "SINE", "QUAD", "CUBIC"),
        "nowPlayingCoverShape" to listOf("CIRCLE", "ROUNDED", "SQUARE"),
        "nowPlayingBackground" to listOf("BLUR", "GRADIENT", "SOLID"),
        "nowPlayingInfoAlignment" to listOf("LEFT", "CENTER", "RIGHT"),
        "floatingLyricsAlignment" to listOf("LEFT", "CENTER", "RIGHT"),
        "floatingLyricsRenderStyle" to listOf("SHADOW", "OUTLINE"),
        "floatingLyricsWindowMode" to listOf("LAYER", "NORMAL"),
        "floatingLyricsTextColor" to COLOR_NAMES,
        "floatingLyricsShadowColor" to COLOR_NAMES,
        "floatingLyricsOutlineColor" to COLOR_NAMES,
        "floatingLyricsBackgroundColor" to COLOR_NAMES,
    )

    /** 大小写写错的枚举值就地归一（`auto` → `AUTO`），取值非法的直接拒绝。 */
    private fun normalizeEnumCase(input: JsonObject, warnings: MutableList<String>): JsonObject {
        var changed = false
        val result = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(input.size)
        for ((key, value) in input) {
            val allowed = ENUM_FIELDS[key]
            if (allowed == null || value !is JsonPrimitive || !value.isString) {
                result[key] = value
                continue
            }
            val text = value.content
            if (text in allowed) {
                result[key] = value
                continue
            }
            val matched = allowed.firstOrNull { it.equals(text, ignoreCase = true) }
            if (matched != null) {
                result[key] = JsonPrimitive(matched)
                changed = true
                warnings += "$key 已归一为 $matched"
            } else {
                throw SnapshotException(
                    "invalid_field",
                    "字段 $key 的取值「$text」不受支持，可选：" + allowed.joinToString(" / "),
                )
            }
        }
        return if (changed) JsonObject(result) else input
    }

    /** 数值夹取 + 明显越界的数组修正。这些都不该让整份文件失败，修正后记进 warnings。 */
    private fun validateValues(input: AppSettings, warnings: MutableList<String>): AppSettings {
        var next = input

        /** 夹取一个浮点字段并记 warning；返回夹取后的值。 */
        fun clampFloat(key: String, value: Float, min: Float, max: Float): Float {
            val clamped = value.coerceIn(min, max)
            if (clamped != value) warnings += "$key 越界（$value），已夹取为 $clamped"
            return clamped
        }

        fun clampInt(key: String, value: Int, min: Int, max: Int): Int {
            val clamped = value.coerceIn(min, max)
            if (clamped != value) warnings += "$key 越界（$value），已夹取为 $clamped"
            return clamped
        }

        next = next.copy(
            volume = clampFloat("volume", next.volume, 0f, 1f),
            playbackSpeed = clampFloat("playbackSpeed", next.playbackSpeed, 0.5f, 3f),
            pitchSemitone = clampFloat("pitchSemitone", next.pitchSemitone, -12f, 12f),
            lyricsFontScale = clampFloat("lyricsFontScale", next.lyricsFontScale, 0.5f, 3f),
            appBackgroundDim = clampFloat("appBackgroundDim", next.appBackgroundDim, 0f, 1f),
            fadeInMs = clampInt("fadeInMs", next.fadeInMs, 0, 10_000),
            fadeOutMs = clampInt("fadeOutMs", next.fadeOutMs, 0, 10_000),
            crossfadeMs = clampInt("crossfadeMs", next.crossfadeMs, 0, 20_000),
            lyricsHighlightDuration = clampInt("lyricsHighlightDuration", next.lyricsHighlightDuration, 0, 5_000),
            lyricsScrollDuration = clampInt("lyricsScrollDuration", next.lyricsScrollDuration, 0, 5_000),
            nowPlayingAutoHideDelayMs = clampInt(
                "nowPlayingAutoHideDelayMs", next.nowPlayingAutoHideDelayMs, 0, 60_000,
            ),
            sleepTimerMinutes = clampInt("sleepTimerMinutes", next.sleepTimerMinutes, 0, 24 * 60),
            cacheLimitMb = clampInt("cacheLimitMb", next.cacheLimitMb, 0, 1024 * 1024),
            downloadConcurrency = clampInt("downloadConcurrency", next.downloadConcurrency, 1, 8),
            appBackgroundBlur = clampInt("appBackgroundBlur", next.appBackgroundBlur, 0, 100),
            floatingLyricsLyricAlpha = clampFloat(
                "floatingLyricsLyricAlpha", next.floatingLyricsLyricAlpha, 0.1f, 1f,
            ),
            floatingLyricsTranslationAlpha = clampFloat(
                "floatingLyricsTranslationAlpha", next.floatingLyricsTranslationAlpha, 0f, 1f,
            ),
            floatingLyricsBackgroundAlpha = clampFloat(
                "floatingLyricsBackgroundAlpha", next.floatingLyricsBackgroundAlpha, 0f, 1f,
            ),
            floatingLyricsFontSize = clampFloat(
                "floatingLyricsFontSize", next.floatingLyricsFontSize, 8f, 120f,
            ),
            floatingLyricsOutlineWidth = clampFloat(
                "floatingLyricsOutlineWidth", next.floatingLyricsOutlineWidth, 0f, 12f,
            ),
            floatingLyricsShadowBlur = clampFloat(
                "floatingLyricsShadowBlur", next.floatingLyricsShadowBlur, 0f, 40f,
            ),
            floatingLyricsMaxWidthDp = clampFloat(
                "floatingLyricsMaxWidthDp", next.floatingLyricsMaxWidthDp, 200f, 4000f,
            ),
            floatingLyricsPositionX = clampFloat(
                "floatingLyricsPositionX", next.floatingLyricsPositionX, 0f, 1f,
            ),
            floatingLyricsPositionY = clampFloat(
                "floatingLyricsPositionY", next.floatingLyricsPositionY, 0f, 1f,
            ),
        )

        // uiScale：0 = 跟随系统，其余是固定的几档；任何 >0 的值都接受，但夹在合理区间
        if (next.uiScale != 0f) {
            val clamped = next.uiScale.coerceIn(0.5f, 3f)
            if (clamped != next.uiScale) {
                warnings += "uiScale 越界（${next.uiScale}），已夹取为 $clamped"
                next = next.copy(uiScale = clamped)
            }
        }

        // 均衡器固定 10 段；长度不对时补齐/截断，避免播放链路拿到非法频段数
        if (next.equalizerBands.size != 10) {
            val fixed = List(10) { index -> next.equalizerBands.getOrElse(index) { 0f }.coerceIn(-20f, 20f) }
            warnings += "equalizerBands 长度 ${next.equalizerBands.size}，已补齐/截断为 10 段"
            next = next.copy(equalizerBands = fixed)
        } else {
            val clamped = next.equalizerBands.map { it.coerceIn(-20f, 20f) }
            if (clamped != next.equalizerBands) {
                warnings += "equalizerBands 存在越界增益，已夹取到 ±20dB"
                next = next.copy(equalizerBands = clamped)
            }
        }

        // 主题种子色：6 位十六进制，非法值退回默认色（否则主题算法会算出奇怪的颜色）
        val seed = next.themeSeedColor.trim().removePrefix("#").uppercase()
        if (seed.length != 6 || seed.any { it !in "0123456789ABCDEF" }) {
            warnings += "themeSeedColor 非法（${next.themeSeedColor}），已回退默认色"
            next = next.copy(themeSeedColor = AppSettings().themeSeedColor)
        } else if (seed != next.themeSeedColor) {
            next = next.copy(themeSeedColor = seed)
        }
        val customSeeds = next.customSeedColors.map { it.trim().removePrefix("#").uppercase() }
            .filter { it.length == 6 && it.all { ch -> ch in "0123456789ABCDEF" } }
        if (customSeeds.size != next.customSeedColors.size) {
            warnings += "customSeedColors 中有非法色值，已丢弃"
            next = next.copy(customSeedColors = customSeeds)
        }

        return next
    }

    // ------------------------------------------------------------------ 工具

    /** 字段名像不像凭据（导出兜底 + 导入时剔除手改文件里塞进来的秘密）。 */
    private fun looksLikeSecretField(name: String): Boolean {
        val lower = name.lowercase()
        return SECRET_NAME_HINTS.any { lower.contains(it) }
    }

    private val SECRET_NAME_HINTS = listOf(
        "cookie", "token", "password", "passwd", "secret", "authorization", "credential", "apikey", "api_key",
    )

    /**
     * 目标路径护栏：拒绝覆盖本就属于「机密/状态」的文件。
     * 用户选到一个目录或选到 `settings.json` 本身时，直接报错比写坏了再解释更好。
     */
    private fun ensureSafeTarget(target: File) {
        val path = canonicalPath(target)
        val protectedFiles = listOf(
            AppDirs.settingsFile,
            AppDirs.accountFile,
            AppDirs.syncConfigFile,
            AppDirs.libraryFile,
            AppDirs.playlistFile,
            AppDirs.historyFile,
            AppDirs.statsFile,
        ).map { canonicalPath(it) }
        if (path in protectedFiles) {
            throw SnapshotException("unsafe_path", "拒绝写入程序自己的数据文件：$path")
        }
        if (target.isDirectory) {
            throw SnapshotException("unsafe_path", "目标是目录，需要指定文件名：$path")
        }
    }

    private fun canonicalPath(file: File): String = runCatching {
        file.canonicalFile.absolutePath
    }.getOrElse { file.absoluteFile.path }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.intField(key: String): Int? =
        (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

    private fun JsonObject.longField(key: String): Long? =
        (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
}
