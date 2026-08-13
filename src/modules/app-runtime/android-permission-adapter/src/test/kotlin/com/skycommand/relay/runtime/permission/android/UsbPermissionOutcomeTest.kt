package com.skycommand.relay.runtime.permission.android

import kotlin.test.Test
import kotlin.test.assertEquals

class UsbPermissionOutcomeTest {
    @Test
    fun keepsAndroidUsbGrantAndDenialDistinct() {
        assertEquals(UsbPermissionOutcome.GRANTED, usbPermissionOutcome(true))
        assertEquals(UsbPermissionOutcome.DENIED, usbPermissionOutcome(false))
    }
}
