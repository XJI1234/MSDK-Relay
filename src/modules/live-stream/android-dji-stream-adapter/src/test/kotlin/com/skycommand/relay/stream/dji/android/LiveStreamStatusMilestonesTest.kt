package com.skycommand.relay.stream.dji.android

import kotlin.test.Test
import kotlin.test.assertEquals

class LiveStreamStatusMilestonesTest {
    @Test
    fun logsOnlyFirstStatusAndFirstReportedVideoOutputForEachAttempt() {
        val events = mutableListOf<LiveStreamDiagnosticEvent>()
        val first = LiveStreamStatusMilestones(1, LiveStreamDiagnosticSink(events::add))
        first.onStatus(true, 0, 0)
        repeat(100) { first.onStatus(true, 0, 0) }
        first.onStatus(true, 25, 1600)
        first.onStatus(true, 25, 1600)
        first.onError()
        first.onError()
        val second = LiveStreamStatusMilestones(2, LiveStreamDiagnosticSink(events::add))
        second.onStatus(true, 0, 0)

        assertEquals(
            listOf(
                LiveStreamDiagnosticEvent(LiveStreamDiagnosticKind.FIRST_STATUS, 1, 0, 0),
                LiveStreamDiagnosticEvent(LiveStreamDiagnosticKind.FIRST_VIDEO_OUTPUT, 1, 25, 1600),
                LiveStreamDiagnosticEvent(LiveStreamDiagnosticKind.RUNTIME_ERROR, 1),
                LiveStreamDiagnosticEvent(LiveStreamDiagnosticKind.FIRST_STATUS, 2, 0, 0),
            ),
            events,
        )
    }

    @Test
    fun diagnosticSinkFailureNeverEscapesToTheSdkCallback() {
        val milestones = LiveStreamStatusMilestones(1, LiveStreamDiagnosticSink { error("disk unavailable") })
        milestones.onStatus(true, 0, 0)
        milestones.onStatus(true, 25, 1600)
        milestones.onError()
    }

    @Test
    fun detachedAttemptCannotEmitLateStatusOrErrorDiagnostics() {
        val events = mutableListOf<LiveStreamDiagnosticEvent>()
        val milestones = LiveStreamStatusMilestones(1, LiveStreamDiagnosticSink(events::add))
        milestones.onStatus(true, 0, 0)
        milestones.deactivate()
        milestones.onStatus(true, 25, 1600)
        milestones.onError()

        assertEquals(listOf(LiveStreamDiagnosticEvent(LiveStreamDiagnosticKind.FIRST_STATUS, 1, 0, 0)), events)
    }
}
