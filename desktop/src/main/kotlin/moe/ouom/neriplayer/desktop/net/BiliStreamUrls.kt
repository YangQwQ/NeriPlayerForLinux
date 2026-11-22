package moe.ouom.neriplayer.desktop.net

import java.net.URI

/**
 * B 站直链候选排序。
 *
 * playurl 给每条流一个 `baseUrl` 和若干 `backupUrl`，它们指向不同 CDN：`upos-sz-mirror*`
 * 是直连镜像，`*.mcdn.bilivideo.cn` 是调度层（偶发 TLS 握手中断），`*.edge.mountaintoys.cn`
 * 是边缘节点。播放器一次只拿一条 URL（失败后不会再换），所以要在解析阶段就把最稳的排在最前，
 * 排序规则与 Android 端 `prioritizeBiliStreamUrls` 一致。
 */
object BiliStreamUrls {

    fun prioritize(primaryUrl: String?, backupUrls: List<String>?): List<String> =
        buildList {
            if (!primaryUrl.isNullOrBlank()) add(primaryUrl.trim())
            backupUrls.orEmpty().filter { it.isNotBlank() }.forEach { add(it.trim()) }
        }.distinct()
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<String>> { score(it.value) }.thenBy { it.index }
            )
            .map { it.value }

    private fun score(url: String): Int {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        if (host.isEmpty()) return 0
        val biliVideo = host.endsWith(".bilivideo.com") || host == "bilivideo.com" ||
            host.endsWith(".bilivideo.cn") || host == "bilivideo.cn"
        return when {
            host.startsWith("upos-") && biliVideo -> 3
            biliVideo -> 2
            host.endsWith("mountaintoys.cn") -> 1
            else -> 0
        }
    }
}
