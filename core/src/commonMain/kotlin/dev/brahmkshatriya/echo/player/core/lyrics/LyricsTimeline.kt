package dev.brahmkshatriya.echo.player.core.lyrics

import dev.brahmkshatriya.echo.common.models.Lyrics

object LrcParser {
    private val timestamp = Regex("\\[(\\d{1,4}):([0-5]\\d)(?:[.:](\\d{1,3}))?]")
    private val offsetTag = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
    private val metadataTag = Regex("^\\[(ar|ti|al|by|re|ve|length|offset):.*]$", RegexOption.IGNORE_CASE)

    fun parse(text: String, durationMs: Long? = null): Lyrics.Lyric {
        require(text.length <= 1_048_576) { "Lyrics are too large" }
        val offset = offsetTag.findAll(text).lastOrNull()?.groupValues?.get(1)?.toLongOrNull()?.coerceIn(-86_400_000, 86_400_000) ?: 0
        val entries = mutableListOf<Pair<Long, String>>()
        val plain = mutableListOf<String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (metadataTag.matches(line)) return@forEach
            val times = timestamp.findAll(line).toList()
            if (times.isEmpty()) { plain += raw; return@forEach }
            val words = timestamp.replace(line, "").trim()
            times.forEach { match ->
                val minutes = match.groupValues[1].toLong()
                val seconds = match.groupValues[2].toLong()
                val fraction = match.groupValues[3].padEnd(3, '0').toLong()
                entries += (minutes * 60_000 + seconds * 1_000 + fraction + offset).coerceAtLeast(0) to words
            }
        }
        if (entries.isEmpty()) return Lyrics.Simple(plain.joinToString("\n").trim())
        val ordered = entries.distinct().groupBy { it.first }.toSortedMapCompat()
        val starts = ordered.keys.toList()
        return Lyrics.Timed(starts.mapIndexed { index, start ->
            Lyrics.Item(ordered.getValue(start).joinToString("\n") { it.second }, start,
                starts.getOrNull(index + 1) ?: durationMs?.takeIf { it > start } ?: Long.MAX_VALUE)
        })
    }

    private fun <T> Map<Long, T>.toSortedMapCompat(): Map<Long, T> = entries.sortedBy { it.key }.associate { it.key to it.value }
}

data class LyricLine(val words: List<Lyrics.Item>, val startMs: Long, val endMs: Long) {
    val text: String get() = words.joinToString(" ") { it.text }
}

/** Immutable timing index; binary search costs O(log lines) per playback tick. */
class LyricsTimeline(lyric: Lyrics.Lyric) {
    val synced: Boolean = lyric !is Lyrics.Simple
    private val fillGaps = when (lyric) { is Lyrics.Timed -> lyric.fillTimeGaps; is Lyrics.WordByWord -> lyric.fillTimeGaps; else -> false }
    val lines: List<LyricLine> = when (lyric) {
        is Lyrics.Simple -> lyric.text.lines().map { LyricLine(listOf(Lyrics.Item(it, 0, 0)), 0, 0) }
        is Lyrics.Timed -> lyric.list.filter { it.startTime >= 0 && it.endTime >= it.startTime }
            .map { LyricLine(listOf(it), it.startTime, it.endTime) }.sortedBy { it.startMs }
        is Lyrics.WordByWord -> lyric.list.mapNotNull { words ->
            val valid = words.filter { it.startTime >= 0 && it.endTime >= it.startTime }.sortedBy { it.startTime }
            if (valid.isEmpty()) null else LyricLine(valid, valid.first().startTime, valid.maxOf { it.endTime })
        }.sortedBy { it.startMs }
    }

    fun activeLine(positionMs: Long): Int {
        if (!synced || lines.isEmpty() || positionMs < lines.first().startMs) return -1
        var low = 0
        var high = lines.lastIndex
        while (low <= high) {
            val middle = (low + high).ushr(1)
            if (lines[middle].startMs <= positionMs) low = middle + 1 else high = middle - 1
        }
        if (high < 0) return -1
        val end = if (fillGaps && high < lines.lastIndex) lines[high + 1].startMs else lines[high].endMs
        return if (positionMs < end) high else -1
    }

    fun wordProgress(line: Int, word: Int, positionMs: Long): Float {
        val item = lines.getOrNull(line)?.words?.getOrNull(word) ?: return 0f
        if (positionMs < item.startTime) return 0f
        if (item.endTime <= item.startTime) return 1f
        return ((positionMs - item.startTime).toDouble() / (item.endTime - item.startTime)).toFloat().coerceIn(0f, 1f)
    }
}
