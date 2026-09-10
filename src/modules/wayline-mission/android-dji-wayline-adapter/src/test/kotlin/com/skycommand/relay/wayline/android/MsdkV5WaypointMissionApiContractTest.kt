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

    @Test
    fun stripsKmzSuffixForMini4StartAndStopNames() {
        assertEquals("默认", djiMissionControlName("默认.kmz"))
        assertEquals("均衡", djiMissionControlName("均衡.kmz"))
        assertEquals("3DGS", djiMissionControlName("3DGS.kmz"))
        assertEquals("route", djiMissionControlName("route.kmz"))
        assertEquals("ROUTE", djiMissionControlName("ROUTE.KMZ"))
        assertEquals("survey", djiMissionControlName("survey.Kmz"))
    }

    @Test
    fun keepsNamesThatAreAlreadyWithoutKmzSuffix() {
        assertEquals("默认", djiMissionControlName("默认"))
        assertEquals("route", djiMissionControlName("route"))
    }

    @Test
    fun doesNotTurnABareKmzSuffixIntoAnEmptyDjiName() {
        assertEquals(".kmz", djiMissionControlName(".kmz"))
        assertEquals(".KMZ", djiMissionControlName(".KMZ"))
    }

    @Test
    fun stripsOnlyATrailingKmzSuffixOnce() {
        assertEquals("mission.kmz", djiMissionControlName("mission.kmz.kmz"))
        assertEquals("a", djiMissionControlName("a.kmz"))
    }

    @Test
    fun registersDjiWaylineExecutingInfoAndWaypointActionListeners() {
        val source = java.io.File("src/main/kotlin/com/skycommand/relay/wayline/android/MsdkV5WaypointMissionApi.kt").readText()
        assertEquals(true, source.contains("addWaylineExecutingInfoListener"))
        assertEquals(true, source.contains("removeWaylineExecutingInfoListener"))
        assertEquals(true, source.contains("onWaylineExecutingInfoUpdate"))
        assertEquals(true, source.contains("onWaylineExecutingInterruptReasonUpdate"))
        assertEquals(true, source.contains("addWaypointActionListener"))
        assertEquals(true, source.contains("removeWaypointActionListener"))
        assertEquals(true, source.contains("onExecutionStart"))
        assertEquals(true, source.contains("onExecutionFinish"))
    }
}
