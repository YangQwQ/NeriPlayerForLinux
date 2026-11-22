package moe.ouom.neriplayer.desktop.core

/**
 * 往播放队列里加歌的位置。
 *
 * 与安卓 `QueueInsertPlacement` 对齐：[NEXT] 紧跟当前曲目之后，[END] 追加到队尾。
 */
enum class QueueInsertPlacement { NEXT, END }

/** 一次加歌编辑的结果：新队列与编辑后当前曲目所在下标（编辑器不判断播放状态）。 */
data class QueueEditResult(
    val queue: List<Song>,
    val currentIndex: Int,
)

/**
 * 本地播放队列的「加歌」编辑规则，对齐安卓
 * `modules/playback/logic/.../queue/policy/PlayerQueueEditOwner.kt` 的 `insertNext` / `insertEnd`：
 * 待加的曲目若已经在队列里，**先按身份移除，再插到目标位置**，因此队列里同一首歌只保留一份。
 *
 * 身份判定：桌面版 [Song.key]（`netease:123` / `bili:BVxxx` / `youtube:xxx` / `local:/路径`）
 * 就是安卓的 `stableKey`，且本地歌 key 直接是文件路径，天然唯一，
 * 所以这里直接比较 key 即可，不需要安卓那套本地歌曲的宽松匹配。
 *
 * 只处理本地播放队列；一起听房间队列（服务端按 stableKey + occurrence 的「多重集合」语义）
 * 不走这里——见 [ListenTogetherManager.addSongsToRoomQueue]。
 */
object PlayerQueueEdit {

    /** 批内去重：同一 key 只保留第一次出现，保持调用方给的顺序。 */
    fun dedupe(songs: List<Song>): List<Song> {
        if (songs.size < 2) return songs
        val seen = HashSet<String>(songs.size)
        return songs.filter { seen.add(it.key) }
    }

    /**
     * 把 [songs] 按 [placement] 插入 [queue]。
     *
     * - 每首待加曲目若已在队列中，先从队列移除；批内重复只保留第一次出现，
     *   整批按给定顺序作为一个整体插入（安卓没有批量加歌入口，逐首点「下一首播放」
     *   时后点的会排到先点的前面；这里按整批保持用户给的顺序）。
     * - [currentIndex] 是当前曲目在 [queue] 中的下标；[playingKey] 用来在编辑后重新定位
     *   当前曲目（编辑器不改变正在播放的歌，即使它被「先移除再插入」挪了位置）。
     * - 锚点之前被移除的曲目会修正下标，与 `removeFromQueue` 中
     *   `index < _currentIndex.value -> _currentIndex.value -= 1` 的算术一致；
     *   待加曲目里就包含当前曲目时，`NEXT` 插回它原来的位置（不会前进一格）。
     */
    fun insert(
        queue: List<Song>,
        songs: List<Song>,
        currentIndex: Int,
        playingKey: String?,
        placement: QueueInsertPlacement,
    ): QueueEditResult {
        val batch = dedupe(songs)
        if (batch.isEmpty()) return QueueEditResult(queue, currentIndex)
        val batchKeys = batch.mapTo(HashSet(batch.size)) { it.key }
        // 锚点优先按正在播放的曲目定位：下标万一与当前曲目不一致，「下一首」也应当指向
        // 实际在播的那首之后，而不是一个漂移的格子。
        val playingIndex = playingKey
            ?.let { key -> queue.indexOfFirst { it.key == key } }
            ?: -1
        val anchor = when {
            playingIndex >= 0 -> playingIndex
            currentIndex in queue.indices -> currentIndex
            else -> -1
        }

        val kept = ArrayList<Song>(queue.size + batch.size)
        var removedBeforeAnchor = 0
        queue.forEachIndexed { index, song ->
            if (song.key in batchKeys) {
                if (anchor >= 0 && index < anchor) removedBeforeAnchor++
            } else {
                kept.add(song)
            }
        }
        // 被移除曲目在其后的下标全部左移，锚点也一起左移
        val anchorAfterRemoval = if (anchor >= 0) anchor - removedBeforeAnchor else -1
        val anchorRemoved = anchor >= 0 && queue[anchor].key in batchKeys
        val insertIndex = when (placement) {
            QueueInsertPlacement.NEXT -> when {
                anchorAfterRemoval < 0 -> 0
                // 当前曲目自己也在待加曲目里：它被移除后空出的位置就是「它的下一格之前」
                anchorRemoved -> anchorAfterRemoval
                else -> anchorAfterRemoval + 1
            }
            QueueInsertPlacement.END -> kept.size
        }.coerceIn(0, kept.size)
        kept.addAll(insertIndex, batch)

        val fallbackIndex = if (kept.isEmpty()) -1 else anchorAfterRemoval.coerceIn(0, kept.lastIndex)
        val newCurrentIndex = playingKey
            ?.let { key -> kept.indexOfFirst { it.key == key }.takeIf { it >= 0 } }
            ?: fallbackIndex
        return QueueEditResult(kept, newCurrentIndex)
    }
}
