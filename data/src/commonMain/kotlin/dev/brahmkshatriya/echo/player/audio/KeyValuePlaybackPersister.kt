package dev.brahmkshatriya.echo.player.audio

import kotlinx.serialization.json.Json

/**
 * Persists the playback session as JSON inside a [dev.brahmkshatriya.echo.player.platform.KeyValueStore].
 */
class KeyValuePlaybackPersister(
    private val store: dev.brahmkshatriya.echo.player.platform.KeyValueStore,
    private val json: Json
) : PlaybackPersister {

    override fun save(state: PlaybackState) {
        runCatching {
            store.putString(KEY_QUEUE, json.encodeToString(PlaybackState.serializer(), state))
        }
    }

    override fun restore(): PlaybackState? {
        val raw = store.getString(KEY_QUEUE) ?: return null
        return runCatching { json.decodeFromString(PlaybackState.serializer(), raw) }
            .onFailure { store.remove(KEY_QUEUE) }
            .getOrNull()
    }

    override fun clear() {
        store.remove(KEY_QUEUE)
    }

    private companion object {
        const val KEY_QUEUE = "echo.playback.session"
    }
}

