package com.skycommand.relay.app

import com.skycommand.relay.runtime.RuntimeState
import com.skycommand.relay.runtime.permission.PermissionState

/** Retains a process resource independently of UI hosts, including failed starts. */
internal class ProcessRelayOwner<T : Any> {
    private var current: T? = null
    @Synchronized fun acquire(create: () -> T): T = current ?: create().also { current = it }
    @Synchronized fun release(resource: T) { if (current === resource) current = null }
}

internal class VisibleRelayStartPolicy {
    private var attempted = false
    private var interrupted = false
    fun interruptedPermissionRequest() { interrupted = true }
    fun shouldStart(state: RuntimeState, permission: PermissionState, explicit: Boolean): Boolean {
        if (state != RuntimeState.STOPPED && state != RuntimeState.FAILED) return false
        if (!explicit && attempted && !interrupted && permission != PermissionState.GRANTED) return false
        attempted = true
        interrupted = false
        return true
    }
}
