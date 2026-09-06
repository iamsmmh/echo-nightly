package dev.brahmkshatriya.echo.player.core.recovery

import dev.brahmkshatriya.echo.player.core.recovery.AudioSessionRecovery.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioSessionRecoveryTest {
    @Test fun playingSessionResumesOnlyWithSystemPermission() {
        val session = AudioSessionRecovery()
        assertEquals(Action.RESUME, session.play())
        assertEquals(Action.PAUSE, session.interruptionBegan())
        assertTrue(session.interrupted)
        assertEquals(Action.RESUME, session.interruptionEnded(true))
        assertFalse(session.interrupted)
    }

    @Test fun explicitPauseDuringCallPreventsUnexpectedAudio() {
        val session = AudioSessionRecovery()
        session.play()
        session.interruptionBegan()
        session.pause()
        assertEquals(Action.NONE, session.interruptionEnded(true))
    }

    @Test fun headphoneOrBluetoothLossDuringInterruptionPreventsResume() {
        val session = AudioSessionRecovery()
        session.play()
        session.interruptionBegan()
        assertEquals(Action.PAUSE, session.routeLost(true))
        assertEquals(Action.NONE, session.interruptionEnded(true))
    }

    @Test fun aPausedSessionNeverAutoplays() {
        val session = AudioSessionRecovery()
        session.interruptionBegan()
        assertEquals(Action.NONE, session.interruptionEnded(true))
        assertEquals(Action.REBUILD_PAUSED, session.servicesReset())
    }

    @Test fun deniedResumeClearsIntent() {
        val session = AudioSessionRecovery()
        session.play()
        session.interruptionBegan()
        assertEquals(Action.NONE, session.interruptionEnded(false))
        assertFalse(session.wantsPlayback)
        assertEquals(Action.REBUILD_PAUSED, session.servicesReset())
    }

    @Test fun mediaServerResetRebuildsOnlyWhenRequested() {
        val session = AudioSessionRecovery()
        session.play()
        assertEquals(Action.PAUSE, session.servicesLost())
        assertEquals(Action.NONE, session.play())
        assertEquals(Action.REBUILD_AND_RESUME, session.servicesReset())
        assertTrue(session.servicesAvailable)
    }

    @Test fun resetDuringInterruptionRebuildsPausedUntilItEnds() {
        val session = AudioSessionRecovery()
        session.play()
        session.interruptionBegan()
        session.servicesLost()
        assertEquals(Action.REBUILD_PAUSED, session.servicesReset())
        assertEquals(Action.RESUME, session.interruptionEnded(true))
    }

    @Test fun duplicateEndNotificationDoesNotResume() {
        val session = AudioSessionRecovery()
        assertEquals(Action.NONE, session.interruptionEnded(true))
        session.play()
        assertEquals(Action.NONE, session.interruptionEnded(true))
    }

    @Test fun routePreferenceIsRespected() {
        val session = AudioSessionRecovery()
        session.play()
        assertEquals(Action.NONE, session.routeLost(false))
        assertTrue(session.wantsPlayback)
    }
}
