package moe.ouom.neriplayer.desktop.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 歌词来源策略，决定歌词仓库里本地 / 在线两条来源的尝试顺序。
 *
 * - AUTO：本地 → 在线（原有回退链）
 * - NETEASE：在线优先，未命中再试本地
 * - LOCAL：本地优先，未命中再试在线补全（保留既有在线补全能力）
 *
 * 这里不直接引用 AppSettings 里尚不存在的属性：把当前设置编码成 JsonObject
 * 后按 key 读，字段缺失（集成负责人尚未加 lyricSource）或值非法时安全回退 AUTO。
 */
enum class LyricSourcePolicy {
    AUTO, NETEASE, LOCAL;

    /** 是否先尝试本地文件 / 内嵌歌词。 */
    val localFirst: Boolean get() = this != NETEASE

    /** 是否允许在首选来源未命中后回退到另一种来源。 */
    val allowsFallback: Boolean get() = true

    companion object {
        /** 设置字段名，对应 AppSettings 将来新增的 `val lyricSource: String`。 */
        const val SETTINGS_KEY = "lyricSource"

        /** 从应用当前设置安全读取；字段不存在或值非法时回退 AUTO。 */
        fun from(settings: SettingsRepository): LyricSourcePolicy {
            val obj = runCatching {
                NeriJson.encodeToString(settings.current).let { NeriJson.parseToJsonElement(it) }
            }.getOrNull() as? JsonObject ?: return AUTO
            val raw = obj[SETTINGS_KEY] as? JsonPrimitive
            return raw?.content?.trim()?.uppercase()?.let { value ->
                entries.firstOrNull { it.name == value }
            } ?: AUTO
        }
    }
}
