package com.skycommand.relay.device.remote.android

import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MsdkV5RemoteControllerMappingTest {
    @Test
    fun explicitRemoteControllerDisconnectIsReportedAsDisconnected() {
        assertEquals(false, groundUnitConnected(remoteControllerKey = false))
    }

    @Test
    fun unobservedRemoteControllerKeyRemainsUnknown() {
        assertEquals(null, groundUnitConnected(remoteControllerKey = null))
    }

    @Test
    fun remoteControllerKeyAloneIsConnected() {
        assertEquals(true, groundUnitConnected(remoteControllerKey = true))
    }

    @Test
    fun observeDoesNotSeedDisconnectedFromMissingKeyDefaults() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/device/remote/android/MsdkV5RemoteControllerApi.kt"),
            Path("src/modules/device-connection/android-remote-controller-adapter/src/main/kotlin/com/skycommand/relay/device/remote/android/MsdkV5RemoteControllerApi.kt"),
        ).first { it.exists() }.readText()
        assertTrue(source.contains("manager.listen(connectionKey"))
        assertFalse(source.contains("ProductKey.KeyConnection"))
        assertFalse(source.contains("manager.listen(productKey"))
        assertTrue(source.contains("remoteControllerConnected = nextRemoteController"))
        assertFalse(source.contains("nextRemoteController?.let"))
        assertFalse(source.contains("manager.getValue(connectionKey, false)"))
    }

    @Test
    fun preservesAnUnobservedConnectionKeyAsUnknown() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/device/remote/android/MsdkV5RemoteControllerApi.kt"),
            Path("src/modules/device-connection/android-remote-controller-adapter/src/main/kotlin/com/skycommand/relay/device/remote/android/MsdkV5RemoteControllerApi.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("private var remoteControllerConnected: Boolean? = null"))
        assertFalse(source.contains("productConnected"))
        assertFalse(source.contains("next == true"))
    }

    @Test
    fun recordsRawRemoteControllerKeyTransitionsForLinkDiagnosis() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/device/remote/android/MsdkV5RemoteControllerApi.kt"),
            Path("src/modules/device-connection/android-remote-controller-adapter/src/main/kotlin/com/skycommand/relay/device/remote/android/MsdkV5RemoteControllerApi.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("[DEBUG-link-order]"))
        assertTrue(source.contains("RemoteControllerKey.KeyConnection"))
        assertTrue(source.contains("Log.i("))
    }
}
