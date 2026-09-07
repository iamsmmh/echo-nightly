package dev.brahmkshatriya.echo.player.core.radio

import dev.brahmkshatriya.echo.player.core.RetryPolicy
import dev.brahmkshatriya.echo.player.core.recovery.StreamFailure
import dev.brahmkshatriya.echo.player.core.recovery.StreamFailureKind
import dev.brahmkshatriya.echo.player.domain.EchoError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Automatic reconnect, stream fallback, metadata updates, buffering recovery,
 * and station persistence for live radio playback.
 */
class RadioPlaybackRecovery(
    private val scope: CoroutineScope,
    private val manager: RadioPlaybackManager,
    private val recovery: RadioRecoveryManager,
    private val policy: RetryPolicy = RetryPolicy.Playback,
) {

    private val _stationPersisted = MutableStateFlow<IcyStationInfo?>(null)
    val stationPersisted: StateFlow<IcyStationInfo?> = _stationPersisted.asStateFlow()

    fun onStreamStarted(station: IcyStationInfo) {
        _stationPersisted.value = station
    }

    fun persistStation(station: IcyStationInfo, url: String) {
        _stationPersisted.value = station
        recovery.connecting()
    }

    fun recoverFromFailure(
        failure: StreamFailure,
        reconnect: suspend () -> Unit,
    ) {
        if (failure.httpStatus in setOf(301, 302, 307, 308, 429, 500, 502, 503)) {
            recovery.recover(failure, reconnect)
        } else {
            recovery.recover(failure, reconnect)
        }
    }

    fun restoreStation(): IcyStationInfo? = _stationPersisted.value

    fun resetPersistence() {
        _stationPersisted.value = null
    }
}
