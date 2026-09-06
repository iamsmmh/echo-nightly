package dev.brahmkshatriya.echo.player.domain

/**
 * Shared time formatting used by the player UIs and the sleep timer.
 * Lives in the shared module so it can be unit tested on every target
 * without linking the Compose UI.
 */

/** Formats milliseconds as `m:ss` or `h:mm:ss` for long tracks. */
fun formatMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return buildString {
        if (hours > 0) {
            append(hours)
            append(':')
            append(twoDigits(minutes))
        } else {
            append(minutes)
        }
        append(':')
        append(twoDigits(seconds))
    }
}

fun twoDigits(value: Long): String = if (value < 10) "0$value" else value.toString()
