package com.skycommand.relay.stream.camera.observer

import kotlin.test.Test
import kotlin.test.assertNotNull

class CameraFrameObserverPresenceTest {
    @Test
    fun exposesTheProductionCameraFrameObserverContract() {
        assertNotNull(Class.forName("com.skycommand.relay.stream.camera.observer.CameraFrameObserver"))
    }
}
