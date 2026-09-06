package dev.brahmkshatriya.echo.player.domain

import kotlin.time.ExperimentalTime

/**
 * Current wall-clock time in milliseconds since the Unix epoch.
 * (kotlinx-datetime 0.7 delegates timekeeping to the Kotlin stdlib's
 * experimental kotlin.time.Clock, so the opt-in lives only here.)
 */
@OptIn(ExperimentalTime::class)
fun nowEpochMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
