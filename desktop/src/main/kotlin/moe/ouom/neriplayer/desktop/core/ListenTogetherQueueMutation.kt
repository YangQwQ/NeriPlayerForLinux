package moe.ouom.neriplayer.desktop.core

/**
 * 一起听队列增量同步（worker.js `queueMutation`，schemaVersion=2）。
 *
 * 对齐 Android 参考实现：
 *  - `modules/listentogether/.../control/ListenTogetherQueueMutationBuilder.kt`（构造）
 *  - `modules/listentogether/.../control/ListenTogetherQueueMutationApplier.kt`（应用）
 *  - `modules/listentogether/.../control/ListenTogetherQueueEventPayload.kt`（事件载荷取舍）
 *
 * 为什么需要增量：整队列快照在并发场景下是「最后写入者覆盖全部」，会吃掉另一端的
 * 增删。增量操作只描述「我改了什么」，服务端重放到收到事件时的最新权威队列，
 * 其余已经到达的远端变化得以保留；基线（baseRoomVersion）过期时退回整快照。
 */

/** 构造结果：mutation 可能为空操作（此时调用方应改用整快照）。 */
data class ListenTogetherQueueMutationPlan(
    val mutation: ListenTogetherQueueMutation,
    /**
     * true 表示这次变更无法用增量表达（含空 stableKey、操作数超限等），
     * 必须回退到整队列快照，否则会与服务端房态分叉。
     */
    val requiresSnapshotFallback: Boolean,
)

/**
 * 由「基线队列（房态）」与「目标队列（本地播放器）」构造增量操作。
 *
 * 与 Android 一致：稳定键为空、操作数超过
 * [LISTEN_TOGETHER_MAX_QUEUE_MUTATION_OPERATIONS] 时要求整快照回退。
 */
fun buildListenTogetherQueueMutationPlan(
    baseQueue: List<ListenTogetherTrack>,
    baseVersion: Long,
    targetQueue: List<ListenTogetherTrack>,
    targetCurrentIndex: Int,
): ListenTogetherQueueMutationPlan {
    val baseReferences = queueReferences(baseQueue)
    if (baseReferences.any { it.stableKey.isBlank() } ||
        targetQueue.any { it.stableKey.isBlank() }
    ) {
        return ListenTogetherQueueMutationPlan(
            mutation = ListenTogetherQueueMutation(
                baseRoomVersion = baseVersion.coerceAtLeast(0L),
                operations = emptyList(),
            ),
            requiresSnapshotFallback = true,
        )
    }

    val baseCounts = baseReferences.groupingBy { it.stableKey }.eachCount()
    val nextTargetOccurrence = mutableMapOf<String, Int>()
    val targetEntries = targetQueue.map { track ->
        val occurrence = nextTargetOccurrence.getOrDefault(track.stableKey, 0)
        nextTargetOccurrence[track.stableKey] = occurrence + 1
        val reference = occurrence.takeIf { it < (baseCounts[track.stableKey] ?: 0) }
            ?.let { ListenTogetherQueueReference(track.stableKey, it) }
        QueueTargetEntry(track = track, reference = reference)
    }
    val targetReferences = targetEntries.mapNotNull { it.reference }
    val targetReferenceSet = targetReferences.toSet()
    val removedReferences = baseReferences.filter { it !in targetReferenceSet }
    val retainedReferences = baseReferences.filter { it in targetReferenceSet }
    val desiredReferences = targetReferences

    val removeOperations = if (removedReferences.size > LISTEN_TOGETHER_MAX_QUEUE_MUTATION_OPERATIONS) {
        listOf(
            ListenTogetherQueueOperation(
                type = "remove_many",
                order = removedReferences,
            ),
        )
    } else {
        removedReferences.map { reference ->
            ListenTogetherQueueOperation(type = "remove", target = reference)
        }
    }
    val moveOperations = buildMoveOperations(retainedReferences, desiredReferences)
    val insertOperations = buildInsertOperations(targetEntries)
    val baseOperationCount = removeOperations.size + insertOperations.size
    val orderOperation = if (
        moveOperations.size > LISTEN_TOGETHER_MAX_QUEUE_MUTATION_OPERATIONS - baseOperationCount
    ) {
        // 移动次数过多时用一次紧凑重排代替，避免超过服务端操作数上限
        ListenTogetherQueueOperation(type = "reorder", order = desiredReferences)
    } else {
        null
    }
    val orderingOperations = orderOperation?.let(::listOf) ?: moveOperations
    val operations = removeOperations + orderingOperations + insertOperations
    val currentEntry = targetEntries.getOrNull(targetCurrentIndex)
    return ListenTogetherQueueMutationPlan(
        mutation = ListenTogetherQueueMutation(
            baseRoomVersion = baseVersion.coerceAtLeast(0L),
            operations = operations,
            targetCurrent = currentEntry?.reference,
        ),
        requiresSnapshotFallback =
            baseOperationCount > LISTEN_TOGETHER_MAX_QUEUE_MUTATION_OPERATIONS ||
                operations.size > LISTEN_TOGETHER_MAX_QUEUE_MUTATION_OPERATIONS,
    )
}

private data class QueueTargetEntry(
    val track: ListenTogetherTrack,
    val reference: ListenTogetherQueueReference?,
)

private fun queueReferences(queue: List<ListenTogetherTrack>): List<ListenTogetherQueueReference> {
    val nextOccurrence = mutableMapOf<String, Int>()
    return queue.map { track ->
        val occurrence = nextOccurrence.getOrDefault(track.stableKey, 0)
        nextOccurrence[track.stableKey] = occurrence + 1
        ListenTogetherQueueReference(track.stableKey, occurrence)
    }
}

private fun buildMoveOperations(
    source: List<ListenTogetherQueueReference>,
    target: List<ListenTogetherQueueReference>,
): List<ListenTogetherQueueOperation> {
    if (source == target) return emptyList()
    val working = source.toMutableList()
    val operations = mutableListOf<ListenTogetherQueueOperation>()
    while (working != target && operations.size <= LISTEN_TOGETHER_MAX_QUEUE_MUTATION_OPERATIONS) {
        val mismatchIndex = working.indices.firstOrNull { index ->
            working[index] != target.getOrNull(index)
        } ?: break
        val move = resolveMoveIndices(working, target, mismatchIndex)
        operations += moveOperation(working, move.first, move.second)
    }
    return operations
}

private fun resolveMoveIndices(
    working: List<ListenTogetherQueueReference>,
    target: List<ListenTogetherQueueReference>,
    mismatchIndex: Int,
): Pair<Int, Int> {
    val targetIndex = target.indexOf(working[mismatchIndex])
    if (tryMoveMatches(working, mismatchIndex, targetIndex, target)) {
        return mismatchIndex to targetIndex
    }
    // 保留项来自同一组 occurrence 引用，首个不匹配位置一定有目标项
    val sourceIndex = working.indexOf(target[mismatchIndex])
    return sourceIndex to mismatchIndex
}

private fun tryMoveMatches(
    source: List<ListenTogetherQueueReference>,
    fromIndex: Int,
    toIndex: Int,
    target: List<ListenTogetherQueueReference>,
): Boolean {
    if (fromIndex !in source.indices || toIndex !in source.indices) return false
    val moved = source.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
    return moved == target
}

private fun moveOperation(
    working: MutableList<ListenTogetherQueueReference>,
    fromIndex: Int,
    toIndex: Int,
): ListenTogetherQueueOperation {
    val target = working.removeAt(fromIndex)
    val anchor = working.getOrNull(toIndex)
    working.add(toIndex, target)
    return ListenTogetherQueueOperation(
        type = "move",
        target = target,
        anchor = anchor,
        placement = if (anchor == null) "append" else "before",
    )
}

private fun buildInsertOperations(
    targetEntries: List<QueueTargetEntry>,
): List<ListenTogetherQueueOperation> {
    val operations = mutableListOf<ListenTogetherQueueOperation>()
    var index = 0
    while (index < targetEntries.size) {
        if (targetEntries[index].reference != null) {
            index++
            continue
        }
        // 新插入项连续成块：整块插到下一个保留项之前（块尾没有保留项则追加到队尾）
        var endExclusive = index
        while (endExclusive < targetEntries.size && targetEntries[endExclusive].reference == null) {
            endExclusive++
        }
        val nextReference = targetEntries.getOrNull(endExclusive)?.reference
        val insertionEntries = targetEntries.subList(index, endExclusive)
        insertionEntries.forEach { entry ->
            operations += ListenTogetherQueueOperation(
                type = "insert",
                anchor = nextReference,
                placement = if (nextReference != null) "before" else "append",
                track = entry.track,
            )
        }
        index = endExclusive
    }
    return operations
}

// ---------------------------------------------------------------- 应用

/** 应用结果；[currentRemoved] 表示操作把原来的当前曲目移除了。 */
data class ListenTogetherQueueMutationResult(
    val queue: List<ListenTogetherTrack>,
    val currentIndex: Int,
    val targetCurrentIndex: Int?,
    val currentRemoved: Boolean,
)

/**
 * 在给定队列上重放增量操作（与 worker.js `applyListenTogetherQueueMutation` 同语义）。
 *
 * 用「同一次解析产生的引用对象」做身份比较，队列里的重复 stableKey 也能区分，
 * 因此这里用带身份的内部节点而不是结构相等。
 */
fun applyListenTogetherQueueMutation(
    roomQueue: List<ListenTogetherTrack>,
    roomCurrentIndex: Int,
    mutation: ListenTogetherQueueMutation,
    targetCurrentStableKey: String? = null,
    maxQueueSize: Int = LISTEN_TOGETHER_MAX_QUEUE_SIZE,
): ListenTogetherQueueMutationResult {
    if (mutation.operations.size > LISTEN_TOGETHER_MAX_QUEUE_MUTATION_OPERATIONS) {
        return ListenTogetherQueueMutationResult(
            queue = roomQueue,
            currentIndex = if (roomQueue.isEmpty()) -1 else roomCurrentIndex.coerceIn(0, roomQueue.lastIndex),
            targetCurrentIndex = null,
            currentRemoved = false,
        )
    }
    val nodes = roomQueue.map(::QueueNode).toMutableList()
    // 引用表必须由同一批节点构造：身份比较决定了重复 stableKey 的哪一项被操作
    val references = buildReferenceMap(nodes)
    val nextQueue = nodes
    val previousCurrent = nextQueue.getOrNull(roomCurrentIndex)
    val targetCurrent = resolveReference(mutation.targetCurrent, references)

    mutation.operations.forEach { operation ->
        applyQueueOperation(operation, nextQueue, references, maxQueueSize)
    }

    if (nextQueue.isEmpty()) {
        return ListenTogetherQueueMutationResult(
            queue = emptyList(),
            currentIndex = -1,
            targetCurrentIndex = null,
            currentRemoved = previousCurrent != null,
        )
    }
    val targetCurrentIndex = nextQueue.indexOfNode(targetCurrent)
    val currentCandidate = resolveCurrentCandidate(
        queue = nextQueue,
        previous = previousCurrent,
        target = targetCurrent,
        targetStableKey = targetCurrentStableKey,
    )
    val currentIndex = nextQueue.indexOfNode(currentCandidate)
        ?: roomCurrentIndex.coerceIn(0, nextQueue.lastIndex)
    return ListenTogetherQueueMutationResult(
        queue = nextQueue.map { it.track },
        currentIndex = currentIndex,
        targetCurrentIndex = targetCurrentIndex.takeIf { it >= 0 },
        currentRemoved = previousCurrent != null && nextQueue.indexOfNode(previousCurrent) < 0,
    )
}

/** 队列节点：携带引用身份，避免结构相等的重复曲目被当成同一项。 */
private class QueueNode(val track: ListenTogetherTrack)

private fun applyQueueOperation(
    operation: ListenTogetherQueueOperation,
    nextQueue: MutableList<QueueNode>,
    references: Map<ListenTogetherQueueReference, QueueNode>,
    maxQueueSize: Int,
) {
    when (operation.type) {
        "remove", "move" -> applyRemoveOrMove(
            operation = operation,
            nextQueue = nextQueue,
            references = references,
            move = operation.type == "move",
        )

        "remove_many" -> operation.order.orEmpty().forEach { reference ->
            removeNode(nextQueue, resolveReference(reference, references))
        }

        "insert" -> applyInsert(operation, nextQueue, references, maxQueueSize)
        "reorder" -> applyReorder(operation, nextQueue, references)
        else -> Unit
    }
}

private fun applyRemoveOrMove(
    operation: ListenTogetherQueueOperation,
    nextQueue: MutableList<QueueNode>,
    references: Map<ListenTogetherQueueReference, QueueNode>,
    move: Boolean,
) {
    val target = resolveReference(operation.target, references) ?: return
    val targetIndex = nextQueue.indexOfNode(target)
    if (targetIndex < 0) return
    nextQueue.removeAt(targetIndex)
    if (!move) return
    val anchor = resolveReference(operation.anchor, references)
    nextQueue.add(insertionIndex(operation.placement, anchor, nextQueue), target)
}

private fun applyInsert(
    operation: ListenTogetherQueueOperation,
    nextQueue: MutableList<QueueNode>,
    references: Map<ListenTogetherQueueReference, QueueNode>,
    maxQueueSize: Int,
) {
    val track = operation.track?.takeIf { it.stableKey.isNotBlank() } ?: return
    if (nextQueue.size >= maxQueueSize) return
    val anchor = resolveReference(operation.anchor, references)
    nextQueue.add(insertionIndex(operation.placement, anchor, nextQueue), QueueNode(track))
}

private fun applyReorder(
    operation: ListenTogetherQueueOperation,
    nextQueue: MutableList<QueueNode>,
    references: Map<ListenTogetherQueueReference, QueueNode>,
) {
    val requestedNodes = operation.order.orEmpty().mapNotNull { resolveReference(it, references) }
    if (requestedNodes.isEmpty()) return
    // 保持被重排项原本占用的槽位，只替换槽位上的曲目（与服务端语义一致）
    val selectedSlots = nextQueue.indices.filter { index ->
        requestedNodes.any { node -> node === nextQueue[index] }
    }
    requestedNodes.forEachIndexed { index, node ->
        selectedSlots.getOrNull(index)?.let { slot -> nextQueue[slot] = node }
    }
}

private fun insertionIndex(
    placement: String?,
    anchor: QueueNode?,
    queue: List<QueueNode>,
): Int {
    val anchorIndex = anchor?.let(queue::indexOfNode) ?: -1
    return when {
        placement == "prepend" -> 0
        placement == "before" && anchorIndex >= 0 -> anchorIndex
        else -> queue.size
    }
}

private fun removeNode(queue: MutableList<QueueNode>, target: QueueNode?) {
    if (target == null) return
    val index = queue.indexOfNode(target)
    if (index >= 0) queue.removeAt(index)
}

private fun List<QueueNode>.indexOfNode(target: QueueNode?): Int {
    if (target == null) return -1
    return indexOfFirst { it === target }
}

private fun resolveCurrentCandidate(
    queue: List<QueueNode>,
    previous: QueueNode?,
    target: QueueNode?,
    targetStableKey: String?,
): QueueNode? {
    previous?.takeIf { queue.indexOfNode(it) >= 0 }?.let { return it }
    target?.takeIf { queue.indexOfNode(it) >= 0 }?.let { return it }
    if (targetStableKey.isNullOrBlank()) return null
    return queue.firstOrNull { it.track.stableKey == targetStableKey }
}

private fun buildReferenceMap(
    nodes: List<QueueNode>,
): Map<ListenTogetherQueueReference, QueueNode> {
    val occurrences = mutableMapOf<String, Int>()
    val references = mutableMapOf<ListenTogetherQueueReference, QueueNode>()
    nodes.forEach { node ->
        val stableKey = node.track.stableKey
        if (stableKey.isNotBlank()) {
            val occurrence = occurrences.getOrDefault(stableKey, 0)
            occurrences[stableKey] = occurrence + 1
            references[ListenTogetherQueueReference(stableKey, occurrence)] = node
        }
    }
    return references
}

private fun resolveReference(
    reference: ListenTogetherQueueReference?,
    references: Map<ListenTogetherQueueReference, QueueNode>,
): QueueNode? {
    if (reference == null || reference.stableKey.isBlank() || reference.occurrence < 0) return null
    return references[reference]
}

// ---------------------------------------------------------------- 事件载荷

/**
 * 一次队列上报要携带的内容：要么整队列快照（[queue]），要么增量操作（[mutation]）。
 * 与 Android `ListenTogetherQueueEventPayload` 一致，二者互斥，避免服务端两边都采用。
 */
data class ListenTogetherQueueEventPayload(
    val queue: List<ListenTogetherTrack>?,
    val mutation: ListenTogetherQueueMutation?,
)

/**
 * 决定用增量还是整快照：
 *  - 房态未知或 `schemaVersion < 2` → 只能用整快照（旧服务端不认 mutation）；
 *  - 增量不可构造/超限 → 整快照回落，保证这次队列变化不丢。
 */
fun buildListenTogetherQueueEventPayload(
    room: ListenTogetherRoomState?,
    queue: List<ListenTogetherTrack>,
    currentIndex: Int,
    allowMutation: Boolean = true,
    alwaysIncludeSnapshot: Boolean = true,
): ListenTogetherQueueEventPayload {
    val plan = queueMutationPlan(room, queue, currentIndex, allowMutation)
    if (plan != null && !plan.requiresSnapshotFallback) {
        return ListenTogetherQueueEventPayload(queue = null, mutation = plan.mutation)
    }
    // plan 为空（房态未知 / 旧服务端）或要求回退时都必须带整队列快照，
    // 否则事件会既没有 queue 也没有 mutation，服务端会以「queue update queue required」拒绝。
    val includeSnapshot = alwaysIncludeSnapshot ||
        plan == null ||
        plan.requiresSnapshotFallback ||
        usesLegacyQueueSnapshot(room)
    return ListenTogetherQueueEventPayload(
        queue = if (includeSnapshot) queue else null,
        mutation = null,
    )
}

/** 服务端还停留在 schemaVersion 1 时只能用整队列快照。 */
fun usesLegacyQueueSnapshot(room: ListenTogetherRoomState?): Boolean =
    (room?.schemaVersion ?: 1) < LISTEN_TOGETHER_SCHEMA_VERSION

private fun queueMutationPlan(
    room: ListenTogetherRoomState?,
    queue: List<ListenTogetherTrack>,
    currentIndex: Int,
    allowed: Boolean,
): ListenTogetherQueueMutationPlan? {
    if (!allowed || room == null) return null
    if (room.schemaVersion < LISTEN_TOGETHER_SCHEMA_VERSION) return null
    return buildListenTogetherQueueMutationPlan(
        baseQueue = room.queue,
        baseVersion = room.version,
        targetQueue = queue,
        targetCurrentIndex = currentIndex,
    )
}
