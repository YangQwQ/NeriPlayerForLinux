package moe.ouom.neriplayer.desktop.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import moe.ouom.neriplayer.desktop.net.HttpService
import moe.ouom.neriplayer.desktop.net.NeriJsonParser
import moe.ouom.neriplayer.desktop.net.array
import moe.ouom.neriplayer.desktop.net.asObject
import moe.ouom.neriplayer.desktop.net.int
import moe.ouom.neriplayer.desktop.net.long
import moe.ouom.neriplayer.desktop.net.obj
import moe.ouom.neriplayer.desktop.net.objects
import moe.ouom.neriplayer.desktop.net.str
import java.io.File

/**
 * B站视频自定义跳过区间（按 BVID + 分P CID 保存）。
 *
 * 一个目标是「视频 + 分P」二元组；同一目标可以有多段区间，播放到区间起点时自动快进到终点。
 * 规则带 [BiliVideoSkipRule.modifiedAt] 与 [BiliVideoSkipRule.isDeleted] 墓碑：
 * 删除不是抹掉记录而是写一条 `isDeleted = true` 的墓碑，将来接入同步时旧快照才不会把已删规则复活。
 *
 * 归一化规则（排序、合并、裁剪）与参考实现一致，**两端行为必须对齐**，
 * 否则 UI 里看到的分段与播放时实际跳过的分段会不一致。
 */
@Serializable
data class BiliVideoSkipInterval(
    val startMs: Long = 0L,
    val endMs: Long = 0L,
) {
    val isValid: Boolean get() = endMs > startMs
}

@Serializable
data class BiliVideoSkipRule(
    val bvid: String = "",
    val cid: Long = 0L,
    val intervals: List<BiliVideoSkipInterval> = emptyList(),
    /** 最后修改时间（毫秒时间戳），同步冲突合并时时间大者胜。 */
    val modifiedAt: Long = 0L,
    /** 删除墓碑：清空区间不删记录，避免同步把旧规则复活。 */
    val isDeleted: Boolean = false,
) {
    val targetKey: String get() = "$bvid|$cid"
}

/** 分P下拉项：cid 用于规则键，label 是分P标题，durationMs 用于时间输入的上界提示。 */
@Serializable
data class BiliVideoSkipTarget(
    val bvid: String = "",
    val cid: Long = 0L,
    val page: Int = 0,
    val label: String = "",
    val durationMs: Long = 0L,
)

object BiliVideoSkipLimits {
    /** 单目标最多区间段数（与参考实现一致，防止单条规则无限膨胀）。 */
    const val MAX_INTERVALS = 100

    /** 最多规则条数（含墓碑）。 */
    const val MAX_RULES = 2000

    /** 单段时间上限：24 小时，避免把毫秒当秒之类的手误写成离谱区间。 */
    const val MAX_TIME_MS = 86_400_000L
}

/**
 * 区间归一化：裁剪到 `[0, durationMs]`、丢弃无效段、按起点排序、相邻/重叠合并、截断到上限。
 *
 * 合并是必须的：连续两段若各跳一次会重新起解码管线两次（每次几百毫秒），
 * 合并后一次跳到覆盖范围终点，用户侧体验与引擎侧的代价都更好。
 */
fun normalizeBiliSkipIntervals(
    intervals: List<BiliVideoSkipInterval>,
    durationMs: Long = 0L,
): List<BiliVideoSkipInterval> {
    val maxEndMs = if (durationMs > 0L) durationMs else BiliVideoSkipLimits.MAX_TIME_MS
    val sorted = intervals
        .map {
            BiliVideoSkipInterval(
                startMs = it.startMs.coerceIn(0L, BiliVideoSkipLimits.MAX_TIME_MS),
                endMs = it.endMs.coerceIn(0L, maxEndMs),
            )
        }
        .filter { it.isValid }
        .sortedWith(compareBy({ it.startMs }, { it.endMs }))
        .take(BiliVideoSkipLimits.MAX_INTERVALS)

    val merged = ArrayList<BiliVideoSkipInterval>(sorted.size)
    for (interval in sorted) {
        val previous = merged.lastOrNull()
        if (previous != null && interval.startMs <= previous.endMs) {
            merged[merged.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, interval.endMs))
        } else {
            merged.add(interval)
        }
    }
    return merged
}

/**
 * 规则归一化：按 (bvid, cid) 去重（modifiedAt 大者胜，同时间墓碑优先、活规则合并区间），
 * 丢弃无效目标与空规则，按 bvid/cid 排序并截断到上限。
 *
 * 用于写入前收敛与读取时清洗脏数据（手改过的 JSON、将来同步来的旧快照）。
 */
fun normalizeBiliSkipRules(rules: List<BiliVideoSkipRule>): List<BiliVideoSkipRule> {
    val byTarget = LinkedHashMap<String, BiliVideoSkipRule>()
    for (raw in rules) {
        val bvid = raw.bvid.trim()
        if (bvid.isEmpty() || raw.cid <= 0L) continue
        val rule = raw.copy(
            bvid = bvid,
            modifiedAt = raw.modifiedAt.coerceAtLeast(0L),
            intervals = if (raw.isDeleted) emptyList() else normalizeBiliSkipIntervals(raw.intervals),
        )
        if (!rule.isDeleted && rule.intervals.isEmpty()) continue
        val previous = byTarget[rule.targetKey]
        when {
            previous == null -> byTarget[rule.targetKey] = rule
            rule.modifiedAt > previous.modifiedAt -> byTarget[rule.targetKey] = rule
            rule.modifiedAt == previous.modifiedAt -> {
                if (previous.isDeleted && !rule.isDeleted) {
                    byTarget[rule.targetKey] = rule
                } else if (!previous.isDeleted && !rule.isDeleted) {
                    byTarget[rule.targetKey] = previous.copy(
                        intervals = normalizeBiliSkipIntervals(previous.intervals + rule.intervals),
                    )
                }
            }
        }
    }
    return byTarget.values
        .sortedWith(compareBy({ it.bvid }, { it.cid }))
        .take(BiliVideoSkipLimits.MAX_RULES)
}

/**
 * 规则仓库：内存 StateFlow + JSON 落盘。
 *
 * 落盘位置固定为 `AppDirs.dataDir/biliVideoSkip.json`（跟随用户数据），
 * 这样备份/迁移用户目录时规则一起走，不需要额外接线。
 */
class BiliVideoSkipRepository(
    private val file: File = File(AppDirs.dataDir, "biliVideoSkip.json"),
) {
    private val store = JsonFileStore(file, ListSerializer(BiliVideoSkipRule.serializer())) { emptyList() }
    private val _rules = MutableStateFlow<List<BiliVideoSkipRule>>(emptyList())

    /** 命令在 IO 线程、播放 hook 在播放线程，读盘标记需要跨线程可见。 */
    @Volatile
    private var loaded = false

    /** 全部规则（含墓碑），按 (bvid, cid) 排序。同步/调试用。 */
    val rules: StateFlow<List<BiliVideoSkipRule>> = _rules.asStateFlow()

    /** 首次访问时读盘；重复调用无副作用。 */
    fun load(): List<BiliVideoSkipRule> {
        if (!loaded) {
            _rules.value = normalizeBiliSkipRules(store.load())
            loaded = true
        }
        return _rules.value
    }

    /** 生效中的规则（过滤墓碑），新→旧，供 UI 列表展示。 */
    fun activeRules(): List<BiliVideoSkipRule> =
        load().filter { !it.isDeleted }
            .sortedWith(compareByDescending<BiliVideoSkipRule> { it.modifiedAt }.thenBy { it.bvid }.thenBy { it.cid })

    /** 某目标的原始规则（含墓碑），没有则 null。 */
    fun ruleFor(bvid: String, cid: Long): BiliVideoSkipRule? =
        load().firstOrNull { it.bvid == bvid && it.cid == cid }

    /**
     * 写入某目标的区间。区间为空（或归一化后为空）时写墓碑而不是删除记录。
     *
     * @return 写入后的规则；若既没有旧规则又要写空区间（无事可做），返回 null。
     */
    @Synchronized
    fun setIntervals(
        bvid: String,
        cid: Long,
        intervals: List<BiliVideoSkipInterval>,
        durationMs: Long = 0L,
    ): BiliVideoSkipRule? {
        val normalizedBvid = bvid.trim()
        if (normalizedBvid.isEmpty() || cid <= 0L) return null
        val previous = ruleFor(normalizedBvid, cid)
        val normalizedIntervals = normalizeBiliSkipIntervals(intervals, durationMs)
        val isDeleted = normalizedIntervals.isEmpty()
        if (previous == null && isDeleted) return null

        // 相同内容不写盘：避免每次打开弹窗点保存都刷新 modifiedAt，
        // 否则将来同步会无谓地把「用户没改」的规则当成新编辑推给其它设备。
        if (previous != null && previous.isDeleted == isDeleted && previous.intervals == normalizedIntervals) {
            return previous
        }

        val now = System.currentTimeMillis()
        val updated = BiliVideoSkipRule(
            bvid = normalizedBvid,
            cid = cid,
            intervals = normalizedIntervals,
            // 严格递增：同一毫秒内的两次编辑也要能分出先后
            modifiedAt = maxOf(now, previous?.modifiedAt?.plus(1L) ?: 0L),
            isDeleted = isDeleted,
        )
        replaceAll(load().filterNot { it.bvid == normalizedBvid && it.cid == cid } + updated)
        return updated
    }

    /** 整体替换（同步合并、导入用）；写入前会做归一化与上限裁剪。 */
    @Synchronized
    fun replaceAll(rules: List<BiliVideoSkipRule>) {
        val normalized = normalizeBiliSkipRules(rules)
        store.save(normalized)
        loaded = true
        _rules.value = normalized
    }
}

/**
 * 进程内单例：后端命令与（后续接线的）播放链路共用同一份内存规则，
 * 避免两处各自加载 JSON 后互相覆盖。
 */
object BiliVideoSkipStore {
    val repository: BiliVideoSkipRepository by lazy { BiliVideoSkipRepository() }
}

/**
 * 读取某视频的分P列表（含时长），供弹窗做 cid 下拉。
 *
 * `BiliApi.videoPages` 只返回 (cid, part)，没有 duration，也没有分P 序号；
 * 而区间编辑需要时长做上界校验与 `mm:ss` 提示，所以这里直接调 pagelist 接口解析完整字段。
 * 主接口风控/失败时退回 `web-interface/view`（它同样带 pages.duration）。
 */
fun fetchBiliVideoSkipTargets(http: HttpService, bvid: String): List<BiliVideoSkipTarget> {
    val normalizedBvid = bvid.trim()
    if (!normalizedBvid.startsWith("BV", ignoreCase = true)) return emptyList()
    val headers = mapOf("Referer" to "https://www.bilibili.com/")

    fun parsePages(pages: kotlinx.serialization.json.JsonArray?): List<BiliVideoSkipTarget> =
        pages?.objects().orEmpty().mapNotNull { item ->
            val cid = item.long("cid")?.takeIf { it > 0L } ?: return@mapNotNull null
            val page = item.int("page") ?: 0
            val part = item.str("part").orEmpty().trim()
            BiliVideoSkipTarget(
                bvid = normalizedBvid,
                cid = cid,
                page = page,
                label = part.ifEmpty { "P$page" },
                durationMs = (item.long("duration") ?: 0L).coerceAtLeast(0L) * 1000L,
            )
        }

    // pagelist：data 直接是分P 数组
    val pageList = NeriJsonParser.parse(
        http.get("https://api.bilibili.com/x/player/pagelist?bvid=$normalizedBvid", headers).orEmpty(),
    ).asObject()?.takeIf { it.int("code") == 0 }
    parsePages(pageList?.array("data")).takeIf { it.isNotEmpty() }?.let { return it }

    // 兜底：view 接口的分P 在 data.pages 里
    val view = NeriJsonParser.parse(
        http.get("https://api.bilibili.com/x/web-interface/view?bvid=$normalizedBvid", headers).orEmpty(),
    ).asObject()?.takeIf { it.int("code") == 0 }
    return parsePages(view?.obj("data")?.array("pages"))
}
