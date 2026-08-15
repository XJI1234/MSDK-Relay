package com.skycommand.relay.device.remote.android

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MsdkV5RemoteControllerMappingTest {
    @Test
    fun usbProductWithoutRemoteControllerKeyIsStillConnected() {
        assertTrue(groundUnitConnected(remoteControllerKey = false, productConnectionKey = true))
    }

    @Test
    fun neitherKeyMeansDisconnected() {
        assertFalse(groundUnitConnected(remoteControllerKey = false, productConnectionKey = false))
    }

    @Test
    fun remoteControllerKeyAloneIsConnected() {
        assertTrue(groundUnitConnected(remoteControllerKey = true, productConnectionKey = false))
    }
}
