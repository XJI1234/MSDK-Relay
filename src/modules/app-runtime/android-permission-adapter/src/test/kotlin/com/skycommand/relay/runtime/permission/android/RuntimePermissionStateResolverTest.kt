package com.skycommand.relay.runtime.permission.android

import com.skycommand.relay.runtime.permission.PermissionState
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimePermissionStateResolverTest {
    @Test
    fun reportsGrantedOnlyWhenEveryPermissionIsGranted() {
        assertEquals(
            PermissionState.GRANTED,
            RuntimePermissionStateResolver.resolve(
                permissions = listOf("location", "microphone"),
                isGranted = { true },
                wasRequested = { false },
                shouldShowRationale = { false },
            ),
        )
    }

    @Test
    fun reportsDeniedForAFirstTimeOrRationaleEligibleDenial() {
        assertEquals(
            PermissionState.DENIED,
            RuntimePermissionStateResolver.resolve(
                permissions = listOf("location"),
                isGranted = { false },
                wasRequested = { false },
                shouldShowRationale = { false },
            ),
        )
        assertEquals(
            PermissionState.DENIED,
            RuntimePermissionStateResolver.resolve(
                permissions = listOf("location"),
                isGranted = { false },
                wasRequested = { true },
                shouldShowRationale = { true },
            ),
        )
    }

    @Test
    fun reportsPermanentlyDeniedOnlyAfterARecordedNonRationaleDenial() {
        assertEquals(
            PermissionState.PERMANENTLY_DENIED,
            RuntimePermissionStateResolver.resolve(
                permissions = listOf("location", "microphone"),
                isGranted = { permission -> permission == "microphone" },
                wasRequested = { permission -> permission == "location" },
                shouldShowRationale = { false },
            ),
        )
    }
}
