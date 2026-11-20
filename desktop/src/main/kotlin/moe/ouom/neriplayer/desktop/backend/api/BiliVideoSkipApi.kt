package moe.ouom.neriplayer.desktop.backend.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.ouom.neriplayer.desktop.backend.CoreServer
import moe.ouom.neriplayer.desktop.backend.arrayOrNull
import moe.ouom.neriplayer.desktop.backend.badRequest
import moe.ouom.neriplayer.desktop.backend.encodeJson
import moe.ouom.neriplayer.desktop.backend.longOrNull
import moe.ouom.neriplayer.desktop.backend.stringOrNull
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.BiliVideoSkipInterval
import moe.ouom.neriplayer.desktop.core.BiliVideoSkipStore
import moe.ouom.neriplayer.desktop.core.fetchBiliVideoSkipTargets

/**
 * B站视频自定义跳过区间（按 BVID + 分P CID 记录，播放时自动 seek 过去）。
 *
 * 详细方案见 desktop/docs/investigations/bili-video-skip.md；
 * 协议见 desktop/docs/protocol-bili-video-skip.md。
 *
 * 本文件只做**规则管理与查询**；播放中的自动跳过 hook 由 Player.kt / 前端单独接线，
 * 但那条链路必须复用 [BiliVideoSkipStore.repository] 的同一份内存规则，
 * 且 seek **必须走 PlayerManager.seekTo()**（内存缓存入口），不能直接调 engine.seekTo()。
 */
object BiliVideoSkipApi {

    fun register(server: CoreServer, container: AppContainer) {
        val repository = BiliVideoSkipStore.repository

        // 规则列表：新→旧、过滤墓碑，供设置页列表展示。
        server.on("biliVideoSkip.getRules") {
            val rules = withContext(Dispatchers.IO) { repository.activeRules() }
            buildJsonObject { put("rules", encodeJson(rules)) }
        }

        // 某 bvid 下的分P列表（含 duration），供弹窗选择 CID。
        server.on("biliVideoSkip.getTargets") { params ->
            val bvid = params.stringOrNull("bvid")?.trim()?.takeIf { it.isNotEmpty() }
                ?: badRequest("缺少 bvid")
            val targets = withContext(Dispatchers.IO) {
                fetchBiliVideoSkipTargets(container.online.httpService, bvid)
            }
            buildJsonObject { put("targets", encodeJson(targets)) }
        }

        // 新增/更新/删除某 (bvid,cid) 的跳过区间；空 intervals 视为墓碑删除。
        server.on("biliVideoSkip.setRule") { params ->
            val bvid = params.stringOrNull("bvid")?.trim()?.takeIf { it.isNotEmpty() }
                ?: badRequest("缺少 bvid")
            val cid = params.longOrNull("cid")?.takeIf { it > 0L }
                ?: badRequest("cid 必须是正整数")
            val durationMs = params.longOrNull("durationMs")?.coerceAtLeast(0L) ?: 0L
            val intervals = params.intervalsOrEmpty()
            val written = withContext(Dispatchers.IO) {
                repository.setIntervals(bvid, cid, intervals, durationMs)
            }
            // 规则变化要通知前端刷新（设置页列表、播放页 hook 的规则缓存）
            server.broadcastMap(
                "biliVideoSkip.changed",
                mapOf("bvid" to bvid, "cid" to cid, "rule" to (written?.let { encodeJson(it) })),
            )
            buildJsonObject {
                put("ok", written != null)
                put("rule", written?.let { encodeJson(it) } ?: JsonNull)
            }
        }
    }

    /** `intervals: [{startMs, endMs}]`；缺省或非数组按空处理（空 = 墓碑删除）。 */
    private fun JsonObject.intervalsOrEmpty(): List<BiliVideoSkipInterval> {
        val array = arrayOrNull("intervals") ?: return emptyList()
        return array.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val start = item.longOrNull("startMs") ?: return@mapNotNull null
            val end = item.longOrNull("endMs") ?: return@mapNotNull null
            BiliVideoSkipInterval(startMs = start, endMs = end)
        }
    }
}
