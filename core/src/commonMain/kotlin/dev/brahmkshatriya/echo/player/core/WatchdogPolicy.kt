package dev.brahmkshatriya.echo.player.core

/**
 * Decision core of the playback watchdog (Phase 1 - stability).
 *
 * The platform service layer (Android `PlayerService`, iOS host) observes the
 * engine and asks this policy what to do when playback looks stuck. Keeping
 * the decision logic here means it is identical on every platform and fully
 * unit tested without a device.
 */
enum class StallRecoveryAction {
    /** Everything looks fine. */
    None,

    /** Nudge the pipeline: seek to the last known position (self-heal). */
    SeekResume,

    /** Re-prepare the current item from the network layer. */
    RePrepare,

    /** Re-resolve the track through the extension (URLs may have expired). */
    ReloadItem,

    /** Give up on this track and move to the next queue entry. */
    SkipTrack,

    /** Stop playback and surface the failure to the user. */
    Fail
}

/**
 * Snapshot the watchdog samples from the player.
 *
 * @param isPlaying whether the user intends audio to be playing
 * @param isBuffering whether the engine reports buffering
 * @param positionMs current playback position
 * @param elapsedSinceProgressMs milliseconds since the position advanced
 * @param elapsedSinceChangeMs milliseconds since any state change happened
 * @param recoveryAttempts how many recovery actions were already tried for
 *   the current item
 */
data class StallSample(
    val isPlaying: Boolean,
    val isBuffering: Boolean,
    val positionMs: Long,
    val elapsedSinceProgressMs: Long,
    val elapsedSinceChangeMs: Long,
    val recoveryAttempts: Int
)

data class WatchdogPolicy(
    /** How long playback may stay frozen before the watchdog reacts. */
    val stallTimeoutMs: Long = 20_000,
    /** Hard limit for buffering before re-preparation kicks in. */
    val bufferingTimeoutMs: Long = 25_000,
    /** Maximum recovery attempts on a single item before skipping it. */
    val maxRecoveriesPerItem: Int = 3
) {

    /**
     * @return the recovery action to run for [sample], escalating with each
     *   failed attempt: seek -> re-prepare -> reload -> skip.
     */
    fun decide(sample: StallSample): StallRecoveryAction {
        if (!sample.isPlaying) return StallRecoveryAction.None
        if (!sample.isStalled(stallTimeoutMs, bufferingTimeoutMs))
            return StallRecoveryAction.None
        if (sample.recoveryAttempts >= maxRecoveriesPerItem)
            return StallRecoveryAction.SkipTrack
        return when (sample.recoveryAttempts) {
            0 -> if (sample.isBuffering) StallRecoveryAction.RePrepare
            else StallRecoveryAction.SeekResume
            1 -> StallRecoveryAction.RePrepare
            else -> StallRecoveryAction.ReloadItem
        }
    }

    private fun StallSample.isStalled(
        stallMs: Long,
        bufferingMs: Long
    ): Boolean = if (isBuffering) elapsedSinceChangeMs >= bufferingMs
    else elapsedSinceProgressMs >= stallMs
}

/**
 * Maps a playback failure to the recovery strategy for it
 * (Phase 1 - crash-safe playback recovery).
 *
 * The policy is deliberately conservative: the first attempt only retries the
 * current source, later attempts alternate between a different server and
 * skipping ahead, and a repeated identical failure fast-forwards instead of
 * looping forever.
 */
data class RecoveryPolicy(
    val maxItemRetries: Int = 1,
    val maxConsecutiveFailures: Int = 3,
    val allowServerFallback: Boolean = true
) {

    enum class Action { RetrySameItem, TryNextServer, SkipToNext, StopWithError }

    data class Decision(
        val action: Action,
        val shouldReconnectNetwork: Boolean
    )

    /**
     * @param itemRetries retries already spent on the current item
     * @param consecutiveFailures same-cause failures seen back to back
     * @param serverCount number of playable servers for the item
     * @param isNetworkError whether the root cause looks network-related
     * @param hasNext whether the queue still has a following item
     */
    fun decide(
        itemRetries: Int,
        consecutiveFailures: Int,
        serverCount: Int,
        isNetworkError: Boolean,
        hasNext: Boolean
    ): Decision {
        val network = isNetworkError
        if (consecutiveFailures >= maxConsecutiveFailures) {
            return if (hasNext) Decision(Action.SkipToNext, network)
            else Decision(Action.StopWithError, network)
        }
        if (itemRetries < maxItemRetries) {
            // Retry the same source once; if a different server exists and the
            // failure was network-like, switch to the fallback server instead.
            return if (network && allowServerFallback && serverCount > 1)
                Decision(Action.TryNextServer, true)
            else Decision(Action.RetrySameItem, network)
        }
        return if (hasNext) Decision(Action.SkipToNext, network)
        else Decision(Action.StopWithError, network)
    }
}
