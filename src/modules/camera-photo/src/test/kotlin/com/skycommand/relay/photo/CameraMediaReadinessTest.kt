package com.skycommand.relay.photo

import com.skycommand.relay.photo.executor.CameraMediaRecoveryPort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraMediaReadinessTest {
    @Test
    fun allowsStreamingWhenTheVideoSourceConnects() {
        val readiness = CameraMediaReadiness()

        assertFalse(readiness.isReady())

        readiness.onVideoSourceChanged(available = true)
        assertTrue(readiness.isReady())
    }

    @Test
    fun blocksStreamingWhenTheVideoSourceDisconnects() {
        val readiness = CameraMediaReadiness()

        readiness.onVideoSourceChanged(available = true)
        readiness.onVideoSourceChanged(available = false)
        assertFalse(readiness.isReady())
    }

    @Test
    fun blocksStreamingDuringPhotoWorkAfterTheVideoSourceConnects() {
        val readiness = CameraMediaReadiness()
        readiness.onVideoSourceChanged(available = true)

        readiness.onCameraMediaBusy()
        assertFalse(readiness.isReady())

        readiness.onCameraMediaReleased()
        assertTrue(readiness.isReady())
    }

    @Test
    fun waitsForOnePlaybackCheckBeforeAllowingTheNewVideoSourceToStart() {
        val recovery = RecordingRecovery()
        val readiness = CameraMediaReadiness(recovery)

        readiness.onVideoSourceChanged(available = true)

        assertEquals(1, recovery.calls)
        assertFalse(readiness.isReady())

        recovery.complete(true)
        assertTrue(readiness.isReady())
    }

    private class RecordingRecovery : CameraMediaRecoveryPort {
        var calls = 0
        private var completion: ((Boolean) -> Unit)? = null

        override fun recover(completion: (Boolean) -> Unit) {
            calls += 1
            this.completion = completion
        }

        fun complete(recovered: Boolean) {
            completion!!.invoke(recovered)
        }
    }

}
