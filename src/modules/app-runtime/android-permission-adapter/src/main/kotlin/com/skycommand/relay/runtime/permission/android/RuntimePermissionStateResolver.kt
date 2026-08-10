package com.skycommand.relay.runtime.permission.android

import com.skycommand.relay.runtime.permission.PermissionState

internal object RuntimePermissionStateResolver {
    fun resolve(
        permissions: List<String>,
        isGranted: (String) -> Boolean,
        wasRequested: (String) -> Boolean,
        shouldShowRationale: (String) -> Boolean,
    ): PermissionState {
        if (permissions.all(isGranted)) return PermissionState.GRANTED
        val permanentlyDenied = permissions.any { permission ->
            !isGranted(permission) && wasRequested(permission) && !shouldShowRationale(permission)
        }
        return if (permanentlyDenied) PermissionState.PERMANENTLY_DENIED else PermissionState.DENIED
    }
}
