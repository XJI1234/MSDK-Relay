package com.skycommand.relay.runtime.permission.android

import android.Manifest

internal object AndroidRuntimePermissionPolicy {
    fun permissionsFor(sdkInt: Int): List<String> = buildList {
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.RECORD_AUDIO)
        if (sdkInt >= 33) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}
