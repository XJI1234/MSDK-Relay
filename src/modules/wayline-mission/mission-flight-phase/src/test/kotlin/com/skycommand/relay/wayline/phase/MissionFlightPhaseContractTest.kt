package com.skycommand.relay.wayline.phase

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MissionFlightPhaseContractTest {

    @Test
    fun enterWaylinePublishesStartReachedThenRouteExecutionStartedExactlyOnce() {
        val facts = mutableListOf<MissionPhaseFact>()
        val tracker = MissionFlightPhase.create(MissionPhaseSink { facts += it })
        tracker.arm(missionRevision = 7, deviceGeneration = 2, fileName = "survey.kmz")

        assertIs<MissionSignalAcceptance.Accepted>(
            tracker.accept(MissionExecutionSignal.ENTER_WAYLINE, missionRevision = 7, deviceGeneration = 2),
        )
        tracker.accept(MissionExecutionSignal.ENTER_WAYLINE, missionRevision = 7, deviceGeneration = 2)
        tracker.accept(MissionExecutionSignal.EXECUTING, missionRevision = 7, deviceGeneration = 2)

        assertEquals(
            listOf(
                MissionPhaseFact(7, 2, 1, MissionPhase.START_POINT_REACHED, "survey.kmz"),
                MissionPhaseFact(7, 2, 2, MissionPhase.ROUTE_EXECUTION_STARTED, "survey.kmz"),
            ),
            facts,
        )
    }

    @Test
    fun executionWithoutEnterWaylineDoesNotInventStartPointReached() {
        val facts = mutableListOf<MissionPhaseFact>()
        val diagnostics = mutableListOf<MissionPhaseDiagnosticKind>()
        val tracker = MissionFlightPhase.create(
            sink = MissionPhaseSink { facts += it },
            diagnosticSink = MissionPhaseDiagnosticSink { diagnostics += it.kind },
        )
        tracker.arm(missionRevision = 1, deviceGeneration = 0, fileName = "route.kmz")

        tracker.accept(MissionExecutionSignal.EXECUTING, missionRevision = 1, deviceGeneration = 0)

        assertEquals(
            listOf(MissionPhaseFact(1, 0, 1, MissionPhase.ROUTE_EXECUTION_STARTED, "route.kmz")),
            facts,
        )
        assertEquals(listOf(MissionPhaseDiagnosticKind.ENTRY_STATE_MISSING), diagnostics)
    }

    @Test
    fun ignoresSignalsForReplacedOrInvalidatedTasks() {
        val facts = mutableListOf<MissionPhaseFact>()
        val tracker = MissionFlightPhase.create(MissionPhaseSink { facts += it })
        tracker.arm(missionRevision = 1, deviceGeneration = 0, fileName = "old.kmz")
        tracker.arm(missionRevision = 2, deviceGeneration = 0, fileName = "new.kmz")

        assertIs<MissionSignalAcceptance.IgnoredStale>(
            tracker.accept(MissionExecutionSignal.ENTER_WAYLINE, missionRevision = 1, deviceGeneration = 0),
        )
        tracker.invalidate(missionRevision = 2, deviceGeneration = 0)

        assertIs<MissionSignalAcceptance.IgnoredStale>(
            tracker.accept(MissionExecutionSignal.ENTER_WAYLINE, missionRevision = 2, deviceGeneration = 0),
        )
        assertEquals(emptyList(), facts)
    }

    @Test
    fun isolatesPhaseSinkFailureWithoutDroppingTheFollowingFact() {
        val delivered = mutableListOf<MissionPhaseFact>()
        val diagnostics = mutableListOf<MissionPhaseDiagnosticKind>()
        val tracker = MissionFlightPhase.create(
            sink = MissionPhaseSink {
                delivered += it
                if (it.phase == MissionPhase.START_POINT_REACHED) error("listener failure")
            },
            diagnosticSink = MissionPhaseDiagnosticSink { diagnostics += it.kind },
        )
        tracker.arm(missionRevision = 1, deviceGeneration = 0, fileName = "route.kmz")

        tracker.accept(MissionExecutionSignal.ENTER_WAYLINE, missionRevision = 1, deviceGeneration = 0)

        assertEquals(
            listOf(MissionPhase.START_POINT_REACHED, MissionPhase.ROUTE_EXECUTION_STARTED),
            delivered.map { it.phase },
        )
        assertEquals(listOf(MissionPhaseDiagnosticKind.PHASE_SINK_FAILURE), diagnostics)
    }
}
