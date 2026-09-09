package dev.brahmkshatriya.echo.player.audio

import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

/**
 * Deterministic, queue-safe shuffle + repeat management shared by Android and iOS.
 *
 * Invariants (all covered by QueueManagerTest):
 *  - The original (un-shuffled) queue is always preserved.
 *  - Toggling shuffle keeps the current item playing and places it first in the
 *    shuffled order, so shuffle never immediately repeats the current track.
 *  - Re-shuffling keeps the current item first and re-orders only the rest.
 *  - [next]/[previous] respect the repeat mode (ALL wraps around, OFF stops at
 *    the queue boundaries, ONE repeats only on natural track completion).
 */
class QueueManager(
    private val ids: QueueIdGenerator = TimeBasedQueueIdGenerator(),
    private val random: Random = Random.Default
) {

    private val mutex = Mutex()

    /** The un-shuffled queue. */
    private val original = mutableListOf<QueueItem>()

    /** The shuffled queue; only meaningful while [state.shuffleEnabled] is true. */
    private val shuffled = mutableListOf<QueueItem>()

    private val _state = MutableStateFlow(QueueSnapshot(emptyList()))
    val state: StateFlow<QueueSnapshot> = _state.asStateFlow()

    val items: List<QueueItem> get() = _state.value.items
    val currentId: String? get() = _state.value.currentId
    val current: QueueItem? get() = _state.value.currentId?.let { id -> items.firstOrNull { it.id == id } }
    val size: Int get() = _state.value.items.size

    private var shuffleSeed: Long? = null

    // ------------------------------------------------------------------ API

    suspend fun setQueue(
        tracks: List<Track>,
        extensionId: String,
        startTrackId: String?,
        shuffle: Boolean = false
    ): QueueItem? = mutex.withLock {
        original.clear()
        shuffled.clear()
        original.addAll(tracks.map { track -> QueueItem(ids.next(), track, extensionId) })
        val current = original.firstOrNull { it.track.id == startTrackId } ?: original.firstOrNull()
        if (shuffle) {
            applyShuffleFrom(current)
        } else {
            shuffled.clear()
            shuffleSeed = null
        }
        // The snapshot must carry the resolved start item, otherwise `current`,
        // `advance` and `rewind` all operate on a queue with no position.
        publish(shuffle, currentId = current?.id)
        return current
    }

    suspend fun replaceItems(items: List<QueueItem>, currentId: String?): QueueItem? = mutex.withLock {
        original.clear()
        shuffled.clear()
        original.addAll(items)
        if (_state.value.shuffleEnabled) {
            applyShuffleFrom(original.firstOrNull { it.id == currentId })
        }
        publish(currentId = original.firstOrNull { it.id == currentId }?.id ?: original.firstOrNull()?.id)
        return _state.value.currentId?.let { id -> _state.value.items.firstOrNull { it.id == id } }
    }

    /** Restores both orders atomically without reshuffling or losing repeat mode. */
    suspend fun restore(snapshot: QueueSnapshot): QueueItem? = mutex.withLock {
        val effective = snapshot.items.distinctBy { it.id }
        val byId = effective.associateBy { it.id }
        val savedOriginal = snapshot.originalItems.mapNotNull { byId[it.id] }.distinctBy { it.id }
        original.clear()
        original.addAll(savedOriginal + effective.filterNot { item -> savedOriginal.any { it.id == item.id } })
        shuffled.clear()
        if (snapshot.shuffleEnabled) shuffled.addAll(effective)
        shuffleSeed = snapshot.shuffleSeed
        _state.value = snapshot.copy(repeatMode = snapshot.repeatMode)
        publish(snapshot.shuffleEnabled, effective.firstOrNull { it.id == snapshot.currentId }?.id ?: effective.firstOrNull()?.id)
        current
    }

    suspend fun getCurrent(): QueueItem? = mutex.withLock { current }

    /**
     * The item that follows the current one.
     *
     * @param userInitiated `true` when the user explicitly pressed next; in
     * [RepeatMode.ONE] a user initiated next still moves to the following
     * track, while a natural completion repeats the current track.
     */
    suspend fun peekNext(userInitiated: Boolean): QueueItem? = mutex.withLock {
        val snapshot = _state.value
        val list = snapshot.items
        if (list.isEmpty()) return null
        val index = list.indexOfFirst { it.id == snapshot.currentId }
        if (index == -1) return list.firstOrNull()
        if (!userInitiated && snapshot.repeatMode == RepeatMode.ONE) return list[index]
        if (index < list.size - 1) list[index + 1]
        else if (snapshot.repeatMode == RepeatMode.ALL) list.firstOrNull()
        else null
    }

    /** The item before the current one; wraps around in [RepeatMode.ALL]. */
    suspend fun peekPrevious(): QueueItem? = mutex.withLock {
        val snapshot = _state.value
        val list = snapshot.items
        if (list.isEmpty()) return null
        val index = list.indexOfFirst { it.id == snapshot.currentId }
        if (index == -1) return list.firstOrNull()
        if (index > 0) list[index - 1]
        else if (snapshot.repeatMode == RepeatMode.ALL) list.lastOrNull()
        else list.firstOrNull()
    }

    /** Moves the current position to the next item (see [peekNext]). */
    suspend fun advance(userInitiated: Boolean): QueueItem? = mutex.withLock {
        val snapshot = _state.value
        val list = snapshot.items
        if (list.isEmpty()) return null
        val index = list.indexOfFirst { it.id == snapshot.currentId }
        if (index == -1) {
            _state.value = snapshot.copy(currentId = list.first().id)
            return list.first()
        }
        if (!userInitiated && snapshot.repeatMode == RepeatMode.ONE) return list[index]
        val next = if (index < list.size - 1) list[index + 1]
        else if (snapshot.repeatMode == RepeatMode.ALL) list.firstOrNull()
        else null
        if (next != null) _state.value = snapshot.copy(currentId = next.id)
        return next
    }

    /** Moves the current position to the previous item. */
    suspend fun rewind(): QueueItem? = mutex.withLock {
        val snapshot = _state.value
        val list = snapshot.items
        if (list.isEmpty()) return null
        val index = list.indexOfFirst { it.id == snapshot.currentId }
        val prev = when {
            index == -1 -> list.firstOrNull()
            index > 0 -> list[index - 1]
            snapshot.repeatMode == RepeatMode.ALL -> list.lastOrNull()
            else -> list.firstOrNull()
        }
        if (prev != null) _state.value = snapshot.copy(currentId = prev.id)
        return prev
    }

    suspend fun jumpTo(itemId: String): QueueItem? = mutex.withLock {
        val list = _state.value.items
        val item = list.firstOrNull { it.id == itemId } ?: return null
        _state.value = _state.value.copy(currentId = item.id)
        return item
    }

    /** Inserts [items] right after the current position ("play next"). */
    suspend fun addNext(items: List<QueueItem>) = mutex.withLock {
        if (items.isEmpty()) return
        val snapshot = _state.value
        val currentIndexInOriginal = original.indexOfFirst { it.id == snapshot.currentId }
        val insertOriginalAt = if (currentIndexInOriginal == -1) original.size else currentIndexInOriginal + 1
        original.addAll(insertOriginalAt, items)
        if (snapshot.shuffleEnabled) {
            val currentIndexInShuffled = shuffled.indexOfFirst { it.id == snapshot.currentId }
            val insertShuffledAt = if (currentIndexInShuffled == -1) shuffled.size else currentIndexInShuffled + 1
            shuffled.addAll(insertShuffledAt, items)
        }
        publish()
    }

    /** Appends [items] to the end of the queue ("play later"). */
    suspend fun addLater(items: List<QueueItem>) = mutex.withLock {
        if (items.isNotEmpty()) {
            original.addAll(items)
            // Keep the shuffled order in sync, otherwise "play later" items are
            // invisible until the queue is re-shuffled.
            if (_state.value.shuffleEnabled) shuffled.addAll(items)
            publish()
        }
    }

    suspend fun remove(itemId: String): QueueItem? = mutex.withLock {
        val item = original.firstOrNull { it.id == itemId } ?: return null
        val snapshot = _state.value
        // Position of the removed item in the order the user is looking at, so
        // playback can continue with whatever now sits in that slot.
        val removedIndex = snapshot.items.indexOfFirst { it.id == itemId }
        original.remove(item)
        shuffled.remove(item)
        if (snapshot.currentId == itemId) {
            val list = effectiveItems()
            val next = if (list.isEmpty()) null else list[removedIndex.coerceIn(0, list.lastIndex)]
            _state.value = snapshot.copy(items = list, currentId = next?.id, originalItems = original.toList())
            return next
        }
        publish()
        return null
    }

    /** Moves the queue entry at [from] to [to] in the effective order. */
    suspend fun move(from: Int, to: Int) = mutex.withLock {
        val list = effectiveItems().toMutableList()
        if (from !in list.indices || to !in list.indices || from == to) return
        val item = list.removeAt(from)
        list.add(to, item)
        replaceEffectiveOrder(list)
        publish()
    }

    suspend fun clear() = mutex.withLock {
        original.clear()
        shuffled.clear()
        shuffleSeed = null
        _state.value = QueueSnapshot(emptyList())
    }

    /**
     * Toggles shuffle. The current item is kept (and becomes the first item of
     * the shuffled order); the original order is preserved for toggling back.
     */
    suspend fun toggleShuffle(): Boolean = mutex.withLock {
        val snapshot = _state.value
        if (snapshot.shuffleEnabled) {
            shuffleSeed = null
            _state.value = snapshot.copy(
                items = original.toList(),
                shuffleEnabled = false,
                shuffleSeed = null
            )
            false
        } else {
            applyShuffleFrom(current)
            publish(true)
            true
        }
    }

    /** Re-orders the shuffled remainder under a new seed (current stays first). */
    suspend fun reshuffle() = mutex.withLock {
        if (!_state.value.shuffleEnabled) return
        applyShuffleFrom(current)
        publish()
    }

    suspend fun setRepeatMode(mode: RepeatMode) = mutex.withLock {
        _state.value = _state.value.copy(repeatMode = mode)
    }

    suspend fun cycleRepeatMode(): RepeatMode = mutex.withLock {
        val next = when (_state.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        _state.value = _state.value.copy(repeatMode = next)
        return next
    }

    // -------------------------------------------------------------- internals

    private fun effectiveItems(): List<QueueItem> =
        if (_state.value.shuffleEnabled) shuffled.toList() else original.toList()


    private fun applyShuffleFrom(currentItem: QueueItem?) {
        val seed = random.nextLong()
        shuffleSeed = seed
        val rest = original.filter { it.id != currentItem?.id }
        shuffled.clear()
        currentItem?.let { shuffled.add(it) }
        shuffled.addAll(rest.shuffled(Random(seed)))
    }

    private fun replaceEffectiveOrder(newOrder: List<QueueItem>) {
        if (_state.value.shuffleEnabled) {
            shuffled.clear()
            shuffled.addAll(newOrder)
        } else {
            original.clear()
            original.addAll(newOrder)
        }
    }

    private fun publish(
        shuffleEnabledNow: Boolean = _state.value.shuffleEnabled,
        currentId: String? = _state.value.currentId
    ) {
        // Resolve the enabled flag *before* picking the effective list: reading
        // `_state.value.shuffleEnabled` here would publish the pre-toggle order.
        val enabled = shuffleEnabledNow && shuffled.isNotEmpty()
        _state.value = _state.value.copy(
            items = if (enabled) shuffled.toList() else original.toList(),
            currentId = currentId,
            shuffleEnabled = enabled,
            shuffleSeed = shuffleSeed,
            originalItems = original.toList()
        )
    }
}
