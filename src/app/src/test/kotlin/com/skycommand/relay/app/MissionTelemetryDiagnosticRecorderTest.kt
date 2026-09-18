package com.skycommand.relay.app

import com.skycommand.relay.diagnostics.DiagnosticClock
import com.skycommand.relay.diagnostics.DiagnosticJournal
import com.skycommand.relay.diagnostics.DiagnosticLevel
import com.skycommand.relay.wayline.phase.MissionExecutionRawState
import com.skycommand.relay.wayline.state.ExecutionState
import com.skycommand.relay.wayline.state.MissionSnapshot
import com.skycommand.relay.wayline.state.UploadState
import com.skycommand.relay.wayline.state.WaypointActionPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MissionTelemetryDiagnosticRecorderTest {
    @Test
    fun recordsDjiExecutionStateWaypointProgressAndInterrupt() {
        val journal = DiagnosticJournal.create("run-1", 16, FixedClock)
        val recorder = MissionTelemetryDiagnosticRecorder(journal)

        recorder.record(
            MissionSnapshot(
                revision = 4,
                missionRevision = 2,
                deviceGeneration = 7,
                file = null,
                upload = UploadState.UPLOADED,
                execution = ExecutionState.EXECUTING,
                missionDjiExecutionState = MissionExecutionRawState.ENTER_WAYLINE,
                waylineExecutingMissionFileName = "默认",
                waylineId = 0,
                currentWaypointIndex = 12,
            ),
        )
        recorder.record(
            MissionSnapshot(
                revision = 5,
                missionRevision = 2,
                deviceGeneration = 7,
                file = null,
                upload = UploadState.UPLOADED,
                execution = ExecutionState.FAILED,
                missionDjiExecutionState = MissionExecutionRawState.INTERRUPTED,
                waylineExecutingMissionFileName = "默认",
                waylineId = 0,
                currentWaypointIndex = 12,
                waylineInterruptErrorCode = "RC_PAUSE_STOP",
                waylineInterruptErrorDescription = "flight pause",
                waypointActionGroup = 1,
                waypointActionId = 2,
                waypointActionPhase = WaypointActionPhase.FINISH,
                waypointActionErrorCode = "ACTION_FAILED",
                waypointActionErrorDescription = "gimbal timeout",
            ),
        )

        val events = journal.pending(32)
        assertEquals(3, events.size)
        assertEquals("wayline-mission", events[0].module)
        assertEquals("WAYLINE_STATE_CHANGED", events[0].eventCode)
        assertEquals(DiagnosticLevel.INFO, events[0].level)
        assertTrue(events[0].safeDetail.contains("missionRevision=2;deviceGeneration=7"))
        assertTrue(events[0].safeDetail.contains("missionDjiExecutionState=null->ENTER_WAYLINE"))
        assertTrue(events[0].safeDetail.contains("currentWaypointIndex=null->12"))
        assertEquals("WAYLINE_STATE_CHANGED", events[1].eventCode)
        assertTrue(events[1].safeDetail.contains("missionDjiExecutionState=ENTER_WAYLINE->INTERRUPTED"))
        assertTrue(events[1].safeDetail.contains("execution=EXECUTING->FAILED"))
        assertEquals("WAYLINE_INTERRUPT", events[2].eventCode)
        assertEquals(DiagnosticLevel.WARN, events[2].level)
        assertTrue(events[2].safeDetail.contains("djiErrorCode=RC_PAUSE_STOP"))
        assertTrue(events[2].safeDetail.contains("djiErrorDescription=flight pause"))
    }

    @Test
    fun ignoresUnchangedSnapshotsAndResetsTheBaseline() {
        val journal = DiagnosticJournal.create("run-1", 8, FixedClock)
        val recorder = MissionTelemetryDiagnosticRecorder(journal)
        val snapshot = MissionSnapshot(
            revision = 1,
            missionRevision = 1,
            deviceGeneration = 1,
            file = null,
            upload = UploadState.UPLOADED,
            execution = ExecutionState.EXECUTING,
            missionDjiExecutionState = MissionExecutionRawState.EXECUTING,
            currentWaypointIndex = 3,
        )
        recorder.record(snapshot)
        journal.acknowledge("run-1", 1)
        recorder.record(snapshot)
        assertEquals(emptyList(), journal.pending(32))
        recorder.reset()
        recorder.record(snapshot)
        assertEquals(1, journal.pending(32).size)
        assertTrue(journal.pending(32).single().safeDetail.contains("missionDjiExecutionState=null->EXECUTING"))
    }

    private object FixedClock : DiagnosticClock {
        override fun currentTimeMillis(): Long = 0
    }
}
