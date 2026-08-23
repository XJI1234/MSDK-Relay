package com.skycommand.relay.runtime.permission.android

import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UsbPermissionOutcomeTest {
    @Test
    fun keepsAndroidUsbGrantAndDenialDistinct() {
        assertEquals(UsbPermissionOutcome.GRANTED, usbPermissionOutcome(true))
        assertEquals(UsbPermissionOutcome.DENIED, usbPermissionOutcome(false))
    }

    @Test
    fun usbReceiverSurvivesActivityRecreationByUsingTheApplicationContext() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/runtime/permission/android/AndroidPermissionPlatform.kt"),
            Path("src/modules/app-runtime/android-permission-adapter/src/main/kotlin/com/skycommand/relay/runtime/permission/android/AndroidPermissionPlatform.kt"),
        ).first { it.exists() }.readText()
        assertTrue(source.contains("activity.applicationContext"))
        assertTrue(source.contains("registerReceiver"))
        assertTrue(source.contains("unregisterReceiver"))
    }
}
