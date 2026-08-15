package com.skycommand.relay.wayline.android

import kotlin.test.Test
import kotlin.test.assertEquals

class MsdkV5WaypointMissionApiContractTest {
    @Test
    fun keepsReturnToStartAsNonTerminalUntilDjiReportsFinished() {
        assertEquals(
            DjiMissionExecutionState.EXECUTING,
            mapWaypointMissionStateName("RETURN_TO_START_POINT"),
        )
        assertEquals(
            DjiMissionExecutionState.COMPLETED,
            mapWaypointMissionStateName("FINISHED"),
        )
    }

    @Test
    fun mapsEveryOtherKnownDjiStateToTheClosedRelayStateSet() {
        val expected = mapOf(
            "PREPARING" to DjiMissionExecutionState.PREPARING,
            "UPLOADING" to DjiMissionExecutionState.PREPARING,
            "RECOVERING" to DjiMissionExecutionState.PREPARING,
            "ENTER_WAYLINE" to DjiMissionExecutionState.ENTER_WAYLINE,
            "EXECUTING" to DjiMissionExecutionState.EXECUTING,
            "INTERRUPTED" to DjiMissionExecutionState.INTERRUPTED,
            "DISCONNECTED" to DjiMissionExecutionState.DISCONNECTED,
            "IDLE" to DjiMissionExecutionState.IDLE,
            "READY" to DjiMissionExecutionState.IDLE,
            "NOT_SUPPORTED" to DjiMissionExecutionState.UNKNOWN,
            "UNKNOWN" to DjiMissionExecutionState.UNKNOWN,
            "SDK_FUTURE_VALUE" to DjiMissionExecutionState.UNKNOWN,
        )

        expected.forEach { (raw, mapped) -> assertEquals(mapped, mapWaypointMissionStateName(raw), raw) }
    }
}
