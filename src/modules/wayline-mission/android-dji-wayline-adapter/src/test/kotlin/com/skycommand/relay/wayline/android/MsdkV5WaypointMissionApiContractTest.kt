package com.skycommand.relay.wayline.android

import kotlin.test.Test
import kotlin.test.assertEquals

class MsdkV5WaypointMissionApiContractTest {
    @Test
    fun preservesReturnToStartAndFinishedAsDistinctDjiObservations() {
        assertEquals(
            DjiMissionExecutionState.RETURN_TO_START_POINT,
            mapWaypointMissionStateName("RETURN_TO_START_POINT"),
        )
        assertEquals(
            DjiMissionExecutionState.FINISHED,
            mapWaypointMissionStateName("FINISHED"),
        )
    }

    @Test
    fun preservesEveryKnownDjiStateInTheClosedObservationSet() {
        val expected = mapOf(
            "PREPARING" to DjiMissionExecutionState.PREPARING,
            "UPLOADING" to DjiMissionExecutionState.UPLOADING,
            "RECOVERING" to DjiMissionExecutionState.RECOVERING,
            "ENTER_WAYLINE" to DjiMissionExecutionState.ENTER_WAYLINE,
            "EXECUTING" to DjiMissionExecutionState.EXECUTING,
            "PAUSED" to DjiMissionExecutionState.PAUSED,
            "INTERRUPTED" to DjiMissionExecutionState.INTERRUPTED,
            "DISCONNECTED" to DjiMissionExecutionState.DISCONNECTED,
            "IDLE" to DjiMissionExecutionState.IDLE,
            "READY" to DjiMissionExecutionState.READY,
            "NOT_SUPPORTED" to DjiMissionExecutionState.NOT_SUPPORTED,
            "UNKNOWN" to DjiMissionExecutionState.UNKNOWN,
            "SDK_FUTURE_VALUE" to DjiMissionExecutionState.UNKNOWN,
        )

        expected.forEach { (raw, mapped) -> assertEquals(mapped, mapWaypointMissionStateName(raw), raw) }
    }
}
