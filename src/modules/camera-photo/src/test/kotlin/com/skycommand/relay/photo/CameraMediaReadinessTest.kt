package com.skycommand.relay.photo

import kotlin.test.Test
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

}
