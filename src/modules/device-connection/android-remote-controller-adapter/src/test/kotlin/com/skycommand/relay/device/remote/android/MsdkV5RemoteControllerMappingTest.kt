package com.skycommand.relay.device.remote.android

import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
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

    @Test
    fun observeDoesNotSeedDisconnectedFromMissingKeyDefaults() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/device/remote/android/MsdkV5RemoteControllerApi.kt"),
            Path("src/modules/device-connection/android-remote-controller-adapter/src/main/kotlin/com/skycommand/relay/device/remote/android/MsdkV5RemoteControllerApi.kt"),
        ).first { it.exists() }.readText()
        assertTrue(source.contains("manager.listen(connectionKey"))
        assertFalse(source.contains("manager.getValue(connectionKey, false)"))
        assertFalse(source.contains("manager.getValue(productKey, false)"))
    }
}
