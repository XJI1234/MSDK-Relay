package com.skycommand.relay.app

import com.skycommand.relay.runtime.RuntimeState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RelaySurfaceRetentionTest {
    @Test
    fun keepsARunningOrStartingRelayWhenTheScreenIsRecreated() {
        for (state in listOf(
            RuntimeState.WAITING_PERMISSIONS,
            RuntimeState.STARTING_SERVICE,
            RuntimeState.STARTING_MODULES,
            RuntimeState.RUNNING,
        )) {
            assertTrue(RelaySurfaceRetention.shouldRetain(state), state.name)
        }
    }

    @Test
    fun releasesAStoppedFailedOrStoppingRelayWithTheScreen() {
        for (state in listOf(RuntimeState.STOPPED, RuntimeState.FAILED, RuntimeState.STOPPING)) {
            assertFalse(RelaySurfaceRetention.shouldRetain(state), state.name)
        }
    }
}
