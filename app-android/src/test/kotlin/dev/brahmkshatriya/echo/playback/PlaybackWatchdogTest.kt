package dev.brahmkshatriya.echo.playback

import androidx.media3.common.Player
import kotlin.test.*

class PlaybackWatchdogTest {
    @Test fun bufferingWithPlayIntentIsMonitoredButPauseFocusLossAndEndAreNot() {
        assertTrue(PlaybackWatchdog.shouldMonitor(true, Player.STATE_BUFFERING, Player.PLAYBACK_SUPPRESSION_REASON_NONE))
        assertTrue(PlaybackWatchdog.shouldMonitor(true, Player.STATE_READY, Player.PLAYBACK_SUPPRESSION_REASON_NONE))
        assertFalse(PlaybackWatchdog.shouldMonitor(false, Player.STATE_READY, Player.PLAYBACK_SUPPRESSION_REASON_NONE))
        assertFalse(PlaybackWatchdog.shouldMonitor(true, Player.STATE_READY, Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS))
        assertFalse(PlaybackWatchdog.shouldMonitor(true, Player.STATE_IDLE, Player.PLAYBACK_SUPPRESSION_REASON_NONE))
        assertFalse(PlaybackWatchdog.shouldMonitor(true, Player.STATE_ENDED, Player.PLAYBACK_SUPPRESSION_REASON_NONE))
    }
    @Test fun normalProgressAndBackwardSeeksRefreshTheWatchdogClock() {
        assertTrue(PlaybackWatchdog.progressChanged(-1, 0))
        assertTrue(PlaybackWatchdog.progressChanged(0, 5000))
        assertTrue(PlaybackWatchdog.progressChanged(5000, 1000))
        assertFalse(PlaybackWatchdog.progressChanged(5000, 5000))
        assertFalse(PlaybackWatchdog.progressChanged(5000, 5100))
    }
}
