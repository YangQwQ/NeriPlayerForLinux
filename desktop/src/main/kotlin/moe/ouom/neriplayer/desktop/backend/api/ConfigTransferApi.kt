package moe.ouom.neriplayer.desktop.backend.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.ouom.neriplayer.desktop.backend.CoreServer
import moe.ouom.neriplayer.desktop.backend.ProtocolException
import moe.ouom.neriplayer.desktop.backend.badRequest
import moe.ouom.neriplayer.desktop.backend.boolOrNull
import moe.ouom.neriplayer.desktop.backend.encodeAny
import moe.ouom.neriplayer.desktop.backend.encodeJson
import moe.ouom.neriplayer.desktop.backend.intOrNull
import moe.ouom.neriplayer.desktop.backend.stringOrNull
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.AppLog
import moe.ouom.neriplayer.desktop.core.ConfigSnapshot
import java.io.File

/**
 * 设置快照导入导出 + 调试日志查看。
 *
 * 详细方案见 desktop/docs/investigations/config-transfer-debug.md（阶段 A + B）；
 * 协议见 desktop/docs/protocol-config.md。
 *
 * 安全边界（改这个文件前先读）：
 * - 快照**只含 AppSettings**（偏好），不含 Cookie / Token / 账号 / 同步配置，
 *   也不含歌单/历史/统计等业务数据——那些分别属于 `accounts.json` / `sync.json` 和 GitHub 同步；
 * - 导入是「先整份校验、再一次性 replace」，任何一步失败都不碰当前设置；
 * - 日志缓冲里的内容在**入环前**就已经脱敏（见 [AppLog]），这里只做过滤与分页。
 */
object ConfigTransferApi {

    /** `debug.recentLogs` 单次返回上限（与环形缓冲容量一致）。 */
    private const val MAX_LOGS = AppLog.CAPACITY

    fun register(server: CoreServer, container: AppContainer) {
        // 把既有的 println / stderr 日志 tee 进环形缓冲，让日志查看器能看到真实输出。
        // 放在这里是因为 BackendMain 已由集成负责人固定，本文件是唯一由本功能负责的注册点。
        if (AppLog.installStdoutBridge()) {
            AppLog.info("app-log", "标准流日志桥接已安装（NERI_APP_LOG_BRIDGE=0 可关闭）")
        }

        // ---------------------------------------------------------------- 导出
        // 参数：path?（绝对路径，省略则写到 ~/neriplayer-settings-<时间戳>.json）
        server.on("config.export") { params ->
            val requested = params.stringOrNull("path")?.trim()?.takeIf { it.isNotEmpty() }
            withContext(Dispatchers.IO) {
                val settings = container.settings.current
                val document = ConfigSnapshot.buildDocument(settings)
                val target = requested?.let { File(it) } ?: ConfigSnapshot.defaultExportFile()
                val bytes = runCatching { ConfigSnapshot.write(document, target) }
                    .getOrElse { error -> throw snapshotError(error) }
                AppLog.info(
                    "config",
                    "exported settings snapshot: ${target.absolutePath} " +
                        "(fields=${document.fieldCount}, bytes=$bytes)",
                )
                buildJsonObject {
                    put("path", target.absolutePath)
                    put("bytes", bytes)
                    put("fieldCount", document.fieldCount)
                    put("exportedAt", document.exportedAt)
                    put("kind", document.kind)
                    put("formatVersion", document.formatVersion)
                    put("omittedFields", encodeAny(document.omittedFields))
                }
            }
        }

        // ---------------------------------------------------------------- 导入
        // 参数：path（必填，绝对路径）、dryRun?（true 时只校验并回报差异，不替换）
        server.on("config.import") { params ->
            val path = params.stringOrNull("path")?.trim()?.takeIf { it.isNotEmpty() }
                ?: badRequest("缺少 path")
            val dryRun = params.boolOrNull("dryRun") == true
            withContext(Dispatchers.IO) {
                // 传 lambda 而不是当场取值：SettingsRepository 的 400ms 延迟写总是落盘「当时」的内存状态，
                // 所以基准值取得越晚，keptFields 与真正生效的当前状态越接近。
                val prepared = runCatching {
                    ConfigSnapshot.readAndValidate(path) { container.settings.current }
                }.getOrElse { error -> throw snapshotError(error) }

                if (dryRun) {
                    AppLog.info(
                        "config",
                        "validated settings snapshot (dry run): ${prepared.path} " +
                            "(changed=${prepared.changedFields.size})",
                    )
                    return@withContext importResult(prepared, applied = false)
                }

                // 校验全部通过后才替换：内存立即生效，磁盘走 JsonFileStore 的原子写。
                container.settings.replace(prepared.settings)
                // settings.set 的既有通路会广播 settings/theme，这里保持一致，
                // 否则前端要等下一次设置变化才刷新（主题色尤其明显）。
                server.broadcast("settings", encodeJson(container.settings.current))
                server.broadcast("theme", AppApi.themePayload(container))
                applyRuntimeSettings(container, prepared.settings)

                AppLog.info(
                    "config",
                    "imported settings snapshot: ${prepared.path} " +
                        "(changed=${prepared.changedFields.size}, kept=${prepared.keptFields.size})",
                )
                importResult(prepared, applied = true)
            }
        }

        // ---------------------------------------------------------------- 日志
        // 参数：limit?（默认 200，上限 1000）、minLevel?（DEBUG/INFO/WARN/ERROR，默认 DEBUG=全部）
        server.on("debug.recentLogs") { params ->
            val limit = (params.intOrNull("limit") ?: DEFAULT_LOG_LIMIT).coerceIn(1, MAX_LOGS)
            val rawLevel = params.stringOrNull("minLevel")
            val minLevel = if (rawLevel.isNullOrBlank()) {
                null
            } else {
                AppLog.Level.parse(rawLevel)
                    ?: badRequest("minLevel 非法：$rawLevel（可选 DEBUG / INFO / WARN / ERROR）")
            }
            val entries = AppLog.recent(limit, minLevel)
            buildJsonObject {
                // 新 → 旧，前端反转后按时间正序展示
                put("logs", buildJsonArray {
                    entries.forEach { entry ->
                        add(buildJsonObject {
                            put("timestampMs", entry.timestampMs)
                            put("level", entry.level)
                            put("target", entry.target)
                            put("message", entry.message)
                            put("repeat", entry.repeat)
                        })
                    }
                })
                put("total", AppLog.size())
                put("recordedTotal", AppLog.recordedCount())
                put("capacity", AppLog.CAPACITY)
                put("truncatedMessageLength", AppLog.MAX_MESSAGE_LENGTH)
            }
        }
    }

    private const val DEFAULT_LOG_LIMIT = 200

    // ------------------------------------------------------------------ 导入后的运行时生效

    /**
     * 把快照里的**音频相关**设置立即作用到播放引擎。
     *
     * `AppSettings` 是纯数据，`PlayerManager` 只在构造时从设置里读一次音量/速度/音高/均衡器，
     * 所以只 `replace()` 的话「导入后音量、语速还是旧的，得重启才生效」。
     * 这里通过 PlayerManager 的公开 setter 重放一遍：这些 setter 内部都用**当前内存设置**
     * 做 copy（`updateDeferred { it.copy(...) }` / `update {}`），不会把旧值写回去。
     *
     * 顺序有讲究：先设均衡器（`setEqualizerPreset` 会强制 `equalizerEnabled = true`），
     * 再按快照里的开关纠正，避免「导入时均衡器是关的却被打开」。
     */
    private fun applyRuntimeSettings(container: AppContainer, settings: moe.ouom.neriplayer.desktop.core.AppSettings) {
        val player = container.player
        runCatching { player.setVolume(settings.volume) }
            .onFailure { AppLog.warn("config", "导入后应用音量失败：${it.message}") }
        runCatching { player.setSpeed(settings.playbackSpeed) }
            .onFailure { AppLog.warn("config", "导入后应用语速失败：${it.message}") }
        runCatching { player.setPitch(settings.pitchSemitone) }
            .onFailure { AppLog.warn("config", "导入后应用音高失败：${it.message}") }
        runCatching {
            player.setFadeOptions(
                fadeEnabled = settings.fadeEnabled,
                fadeInMs = settings.fadeInMs,
                fadeOutMs = settings.fadeOutMs,
                crossfadeEnabled = settings.crossfadeEnabled,
                crossfadeMs = settings.crossfadeMs,
            )
        }.onFailure { AppLog.warn("config", "导入后应用淡入淡出失败：${it.message}") }
        runCatching { player.setLoudness(settings.loudnessEnhancer) }
            .onFailure { AppLog.warn("config", "导入后应用响度增强失败：${it.message}") }
        if (settings.equalizerEnabled) {
            runCatching { player.setEqualizerPreset(settings.equalizerPreset, settings.equalizerBands) }
                .onFailure { AppLog.warn("config", "导入后应用均衡器失败：${it.message}") }
        } else {
            runCatching { player.setEqualizerEnabled(false) }
                .onFailure { AppLog.warn("config", "导入后关闭均衡器失败：${it.message}") }
        }
    }

    // ------------------------------------------------------------------ 结果与错误

    /** 导入结果（dryRun 与真正导入共用同一份结构，`applied` 区分）。 */
    private fun importResult(prepared: ConfigSnapshot.PreparedImport, applied: Boolean): JsonObject =
        buildJsonObject {
            put("applied", applied)
            put("path", prepared.path)
            put("formatVersion", prepared.formatVersion)
            put("platform", prepared.platform)
            put("exportedAt", prepared.exportedAt)
            put("appVersion", prepared.appVersion)
            put("changedFields", encodeAny(prepared.changedFields))
            put("keptFields", encodeAny(prepared.keptFields))
            put("ignoredFields", encodeAny(prepared.ignoredFields))
            put("omittedFields", encodeAny(prepared.omittedFields))
            put("warnings", encodeAny(prepared.warnings))
            put("changedCount", prepared.changedFields.size)
            if (applied) put("settings", encodeJson(prepared.settings))
        }

    /** 快照异常 → 协议错误；错误码在 protocol-config.md 里有表，前端按 code 给提示。 */
    private fun snapshotError(error: Throwable): ProtocolException = when (error) {
        is ConfigSnapshot.SnapshotException -> ProtocolException(error.code, error.message ?: "导入失败")
        is ProtocolException -> error
        else -> ProtocolException(
            "io_error",
            error.message ?: error::class.simpleName ?: "未知错误",
        )
    }
}
