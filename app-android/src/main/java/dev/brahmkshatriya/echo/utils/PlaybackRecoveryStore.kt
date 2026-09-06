package dev.brahmkshatriya.echo.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
/**
 * Crash-safe playback recovery (Phase 1 - stability).
 *
 * A lightweight snapshot of "what was playing right now" is updated as
 * playback progresses. After a process death (system kill or a crash while
 * the service was alive) the PlayerService restores queue + position and -
 * depending on [AUTO_RESUME_KEY] - resumes playback.
 */
object PlaybackRecoveryStore {

    private const val PREFS = "playback_recovery"
    private const val KEY_INDEX = "index"
    private const val KEY_POSITION = "position"
    private const val KEY_PLAYING = "playing"
    private const val KEY_TRACK_ID = "track_id"
    private const val KEY_UPDATED = "updated_at"

    /** Snapshot TTL: older than 12h the user has clearly moved on. */
    private const val MAX_AGE_MS = 12 * 60 * 60 * 1000L

    const val AUTO_RESUME_KEY = "auto_resume_after_crash"

    data class Snapshot(
        val index: Int,
        val position: Long,
        val wasPlaying: Boolean,
        val trackId: String?,
        val updatedAt: Long
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(context: Context, player: androidx.media3.common.Player) {
        runCatching {
            val item = player.currentMediaItem
            prefs(context).edit {
                putInt(KEY_INDEX, player.currentMediaItemIndex)
                putLong(KEY_POSITION, player.currentPosition.coerceAtLeast(0))
                putBoolean(KEY_PLAYING, player.isPlaying)
                putString(KEY_TRACK_ID, item?.mediaId)
                putLong(KEY_UPDATED, System.currentTimeMillis())
            }
        }
    }

    fun load(context: Context): Snapshot? = runCatching {
        val p = prefs(context)
        val updated = p.getLong(KEY_UPDATED, 0)
        if (updated == 0L || System.currentTimeMillis() - updated > MAX_AGE_MS) null
        else Snapshot(
            index = p.getInt(KEY_INDEX, 0),
            position = p.getLong(KEY_POSITION, 0),
            wasPlaying = p.getBoolean(KEY_PLAYING, false),
            trackId = p.getString(KEY_TRACK_ID, null),
            updatedAt = updated
        )
    }.getOrNull()

    fun clear(context: Context) {
        runCatching { prefs(context).edit { clear() } }
    }

    fun wasPlaying(context: Context, settings: SharedPreferences): Boolean {
        if (!settings.getBoolean(AUTO_RESUME_KEY, true)) return false
        val snapshot = load(context) ?: return false
        return snapshot.wasPlaying
    }
}
