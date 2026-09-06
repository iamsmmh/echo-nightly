package dev.brahmkshatriya.echo.common.models

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable

/**
 * A platform independent date, stored as milliseconds since the Unix epoch and
 * interpreted in the current system time zone (matching the previous
 * `java.util.Calendar` based behaviour of this class).
 */
@Suppress("MemberVisibilityCanBePrivate")
@Serializable
data class Date(
    val epochTimeMs: Long
) : Comparable<Date> {

    constructor(
        year: Int,
        month: Int? = null,
        day: Int? = null,
    ) : this(
        runCatching {
            LocalDate(year, (month ?: 1).coerceIn(1, 12), (day ?: 1).coerceIn(1, 31))
        }.getOrElse { LocalDate(year, 1, 1) }
            .atStartOfDayIn(TimeZone.currentSystemDefault())
            .toEpochMilliseconds()
    )

    companion object {
        fun Int.toYearDate() = Date(this)
    }

    private val local by lazy {
        Instant.fromEpochMilliseconds(epochTimeMs)
            .toLocalDateTime(TimeZone.currentSystemDefault())
    }

    val year: Int get() = local.year

    val month: Int? get() =
        if (local.monthNumber == 1 && local.dayOfMonth == 1) null
        else local.monthNumber

    val day: Int? get() =
        if (local.dayOfMonth == 1) null
        else local.dayOfMonth

    override fun compareTo(other: Date): Int {
        return epochTimeMs.compareTo(other.epochTimeMs)
    }

    override fun toString(): String = when {
        month == null || day == null -> year.toString()
        else -> formatShortDate(epochTimeMs)
    }
}

/**
 * Formats an epoch timestamp using the platform's locale aware SHORT date
 * format (`DateFormat.getDateInstance(SHORT)` on JVM, `NSDateFormatter` on iOS).
 */
expect fun formatShortDate(epochTimeMs: Long): String
