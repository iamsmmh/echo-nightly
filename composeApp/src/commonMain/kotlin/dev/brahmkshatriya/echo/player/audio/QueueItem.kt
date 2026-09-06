package dev.brahmkshatriya.echo.player.audio

import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.serialization.Serializable

enum class RepeatMode { OFF, ONE, ALL }

/**
 * One entry of the playback queue. Wraps the shared [Track] model together
 * with the extension that produced it, so the stream can later be resolved by
 * the same extension.
 */
@Serializable
data class QueueItem(
    val id: String,
    val track: Track,
    val extensionId: String
) {
    val trackKey: String get() = "$extensionId::${track.id}"
    val title: String get() = track.title
    val authors: String get() = track.artists.joinToString(", ") { it.name }
}

/** A stable snapshot of the queue used for UI and persistence. */
@Serializable
data class QueueSnapshot(
    val items: List<QueueItem>,
    val currentId: String? = null,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleSeed: Long? = null
)

/** Generates unique queue item ids; injectable for deterministic tests. */
fun interface QueueIdGenerator {
    fun next(): String
}

/** Default generator using a counter + wall clock based prefix. */
class TimeBasedQueueIdGenerator : QueueIdGenerator {
    private var counter = 0L
    override fun next(): String =
        "qi-${kotlinx.datetime.Clock.System.now().toEpochMilliseconds()}-${counter++}"
}

/** Generates a random unique id usable from common code. */
fun newQueueId(): String {
    val a = kotlin.random.Random.nextLong()
    val b = kotlin.random.Random.nextLong()
    return "qi-${a.toString(radix = 16)}${b.toString(radix = 16)}"
}

