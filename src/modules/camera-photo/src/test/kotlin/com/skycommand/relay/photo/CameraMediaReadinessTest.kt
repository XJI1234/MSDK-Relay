package com.skycommand.relay.photo

import com.skycommand.relay.photo.executor.CameraMediaRecoveryPort
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraMediaReadinessTest {
    @Test
    fun keepsStreamingBlockedUntilTheConnectedCameraConfirmsItsVideoInputWasRecovered() {
        val recovery = Recovery()
        val readiness = CameraMediaReadiness(recovery)

        assertFalse(readiness.isReady())

        readiness.onVideoSourceChanged(available = true)
        assertFalse(readiness.isReady())

        recovery.complete(recovered = true)
        assertTrue(readiness.isReady())
    }

    @Test
    fun ignoresARecoveryCallbackFromThePreviousVideoSourceConnection() {
        val recovery = Recovery()
        val readiness = CameraMediaReadiness(recovery)

        readiness.onVideoSourceChanged(available = true)
        readiness.onVideoSourceChanged(available = false)
        readiness.onVideoSourceChanged(available = true)

        recovery.complete(index = 0, recovered = true)
        assertFalse(readiness.isReady())

        recovery.complete(index = 1, recovered = true)
        assertTrue(readiness.isReady())
    }

    @Test
    fun blocksStreamingDuringPhotoWorkAfterTheVideoInputWasRecovered() {
        val recovery = Recovery()
        val readiness = CameraMediaReadiness(recovery)
        readiness.onVideoSourceChanged(available = true)
        recovery.complete(recovered = true)

        readiness.onCameraMediaBusy()
        assertFalse(readiness.isReady())

        readiness.onCameraMediaReleased()
        assertTrue(readiness.isReady())
    }

    private class Recovery : CameraMediaRecoveryPort {
        private val completions = mutableListOf<(Boolean) -> Unit>()
        override fun recover(completion: (Boolean) -> Unit) {
            completions += completion
        }
        fun complete(index: Int = completions.lastIndex, recovered: Boolean) = completions[index](recovered)
    }
}
