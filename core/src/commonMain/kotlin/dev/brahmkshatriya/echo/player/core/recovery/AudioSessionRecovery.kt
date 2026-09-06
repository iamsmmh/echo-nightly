package dev.brahmkshatriya.echo.player.core.recovery

/** User intent, not decoder rate, controls resumption after calls / route changes. */
class AudioSessionRecovery {
    enum class Action { NONE, PAUSE, RESUME, REBUILD_PAUSED, REBUILD_AND_RESUME }

    var wantsPlayback: Boolean = false
        private set
    var interrupted: Boolean = false
        private set
    var servicesAvailable: Boolean = true
        private set

    fun play(): Action {
        wantsPlayback = true
        return if (!interrupted && servicesAvailable) Action.RESUME else Action.NONE
    }

    fun pause(): Action {
        wantsPlayback = false
        return Action.PAUSE
    }

    fun interruptionBegan(): Action {
        interrupted = true
        return Action.PAUSE
    }

    fun interruptionEnded(shouldResume: Boolean): Action {
        if (!interrupted) return Action.NONE
        interrupted = false
        if (!shouldResume) wantsPlayback = false
        return if (wantsPlayback && servicesAvailable) Action.RESUME else Action.NONE
    }

    fun routeLost(pauseOnDisconnect: Boolean): Action =
        if (pauseOnDisconnect) pause() else Action.NONE

    fun servicesLost(): Action {
        servicesAvailable = false
        return Action.PAUSE
    }

    fun servicesReset(): Action {
        servicesAvailable = true
        return if (wantsPlayback && !interrupted) Action.REBUILD_AND_RESUME else Action.REBUILD_PAUSED
    }
}
