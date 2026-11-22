package moe.ouom.neriplayer.desktop.net

import java.util.concurrent.ConcurrentHashMap

/**
 * B 站播放失败原因记录。
 *
 * 解析链路（[BiliApi.audioUrl] → [OnlineRepository.resolvePlayback]）对外只返回
 * `AudioInput?`，null 到了界面上会统一变成「无法解析播放地址」，用户和排查者都看不出到底
 * 卡在哪一步。而失败原因差别很大：视频 ID 不对、cid 与视频不匹配、只有 durl 且直链取不到、
 * 付费/会员只给试看、接口风控……处理方式完全不同。
 *
 * 这里不改现有方法签名（尤其返回 `String?` 的 [BiliApi.audioUrl]，自动换源也在调用它），
 * 而是把失败原因按视频 ID 记下来，由上层在拿到 null 时读取。
 * 按 ID 记录是必须的：播放器会预解析下一首，只留「最近一次失败」会被别人的失败覆盖。
 */
object BiliPlaybackDiagnostics {

    private val reasons = ConcurrentHashMap<String, String>()

    @Volatile
    private var lastKey: String? = null

    fun record(videoId: String, message: String) {
        val key = normalize(videoId)
        if (key.isEmpty()) return
        reasons[key] = message
        lastKey = key
    }

    fun clear(videoId: String) {
        val key = normalize(videoId)
        if (key.isNotEmpty()) reasons.remove(key)
    }

    /** 指定视频最近一次失败的可读原因；解析成功后为 null。 */
    fun failureFor(videoId: String?): String? {
        val key = normalize(videoId.orEmpty())
        if (key.isEmpty()) return null
        return reasons[key]
    }

    /** 最近一次记录失败的可读原因（兜底用，正常情况下按 ID 查）。 */
    fun lastFailure(): String? = lastKey?.let { reasons[it] }

    private fun normalize(videoId: String): String {
        // 上层可能拿 Song.key（`bili:<id>`）来查，这里统一去掉前缀
        val trimmed = videoId.trim().removePrefix("bili:")
        if (trimmed.isEmpty()) return ""
        // bvid 只有 `BV` 前缀大小写无关，后半段是大小写敏感的编码，不能整体转换
        return if (trimmed.startsWith("bv", ignoreCase = true)) "BV" + trimmed.substring(2) else trimmed
    }
}
