package com.skycommand.relay.app

import com.skycommand.relay.runtime.RuntimeState

object RelaySurfaceRetention {
    fun shouldRetain(runtime: RuntimeState): Boolean = when (runtime) {
        RuntimeState.WAITING_PERMISSIONS,
        RuntimeState.STARTING_SERVICE,
        RuntimeState.STARTING_MODULES,
        RuntimeState.RUNNING,
        -> true
        RuntimeState.STOPPED,
        RuntimeState.STOPPING,
        RuntimeState.FAILED,
        -> false
    }
}
