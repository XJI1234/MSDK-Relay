package com.skycommand.relay.runtime.permission.android

import android.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidRuntimePermissionPolicyTest {
    @Test
    fun api24RequestsLegacyStorageButNotNotifications() {
        assertEquals(
            setOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ),
            AndroidRuntimePermissionPolicy.permissionsFor(24).toSet(),
        )
    }

    @Test
    fun api32KeepsLegacyStorageAndDoesNotRequestNotifications() {
        assertEquals(
            setOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ),
            AndroidRuntimePermissionPolicy.permissionsFor(32).toSet(),
        )
    }

    @Test
    fun api33ReplacesLegacyStorageWithNotifications() {
        assertEquals(
            setOf(
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
            AndroidRuntimePermissionPolicy.permissionsFor(33).toSet(),
        )
    }
}
