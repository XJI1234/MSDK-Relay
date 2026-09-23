package com.skycommand.relay.app

import com.skycommand.relay.diagnostics.DiagnosticClock
import com.skycommand.relay.diagnostics.DiagnosticJournal
import com.skycommand.relay.stream.camera.observer.CameraFrameCodec
import com.skycommand.relay.stream.camera.observer.CameraFrameObservationState
import com.skycommand.relay.stream.camera.observer.CameraFrameSnapshot
import com.skycommand.relay.stream.camera.observer.CameraFrameObserverDiagnosticKind
import com.skycommand.relay.stream.dji.android.LiveStreamDiagnosticEvent
import com.skycommand.relay.stream.dji.android.LiveStreamDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveCaptureDiagnosticRecorderTest {
    @Test
    fun recordsOnlyFrameTransitionsPerGenerationAndKeepsTheLastSignaledCountOnStop() {
        val journal = journal()
        val recorder = LiveCaptureDiagnosticRecorder(journal)
        recorder.recordCamera(frame(1, CameraFrameObservationState.UNOBSERVED, 0))
        recorder.recordCamera(frame(1, CameraFrameObservationState.RECEIVING, 1))
        recorder.recordCamera(frame(1, CameraFrameObservationState.RECEIVING, 200))
        recorder.recordCamera(frame(1, CameraFrameObservationState.STALLED, 210))
        recorder.recordCamera(frame(1, CameraFrameObservationState.STALLED, 210))
        recorder.recordCamera(frame(1, CameraFrameObservationState.UNAVAILABLE, 0))
        recorder.recordCamera(frame(2, CameraFrameObservationState.UNOBSERVED, 0))
        recorder.recordCamera(frame(2, CameraFrameObservationState.RECEIVING, 1))

        val events = journal.pending(32)
        assertEquals(
            listOf("CAMERA_LISTENER_ACTIVE", "CAMERA_FIRST_FRAME", "CAMERA_FRAMES_STALLED", "CAMERA_LISTENER_STOPPED", "CAMERA_LISTENER_ACTIVE", "CAMERA_FIRST_FRAME"),
            events.map { it.eventCode },
        )
        assertTrue(events[2].safeDetail.contains("count=210"))
        assertTrue(events[3].safeDetail.contains("lastSignaledCount=210"))
        assertTrue(events[5].safeDetail.contains("generation=2"))
    }

    @Test
    fun recordsRegistrationFailureAndRtmpMilestonesWithoutUrlsOrErrorDescriptions() {
        val journal = journal()
        val recorder = LiveCaptureDiagnosticRecorder(journal)
        recorder.recordCameraFailure(CameraFrameObserverDiagnosticKind.PLATFORM_REGISTRATION_FAILURE)
        recorder.recordRtmp(LiveStreamDiagnosticEvent(LiveStreamDiagnosticKind.SETTINGS_APPLIED, 1))
        recorder.recordRtmp(LiveStreamDiagnosticEvent(LiveStreamDiagnosticKind.FIRST_STATUS, 1, 0, 0))
        recorder.recordRtmp(LiveStreamDiagnosticEvent(LiveStreamDiagnosticKind.FIRST_VIDEO_OUTPUT, 1, 25, 1600))
        recorder.recordRtmp(LiveStreamDiagnosticEvent(LiveStreamDiagnosticKind.START_CALLBACK_FAILED, 2))

        val events = journal.pending(32)
        assertEquals(5, events.size)
        assertEquals("PLATFORM_REGISTRATION_FAILURE", events[0].eventCode)
        assertTrue(events[2].safeDetail.contains("fps=0"))
        assertTrue(events[3].safeDetail.contains("bitrateKbps=1600"))
        assertTrue(events[4].safeDetail.contains("attempt=2"))
        assertFalse(events.any { it.safeDetail.contains("rtmp:") || it.safeDetail.contains("errorDescription") })
    }

    @Test
    fun recordsAResumedCameraGenerationWithoutRepeatingTheFirstFrame() {
        val journal = journal()
        val recorder = LiveCaptureDiagnosticRecorder(journal)
        recorder.recordCamera(frame(1, CameraFrameObservationState.UNOBSERVED, 0))
        recorder.recordCamera(frame(1, CameraFrameObservationState.RECEIVING, 1))
        recorder.recordCamera(frame(1, CameraFrameObservationState.STALLED, 50))
        recorder.recordCamera(frame(1, CameraFrameObservationState.RECEIVING, 51))
        recorder.recordCamera(frame(1, CameraFrameObservationState.RECEIVING, 90))

        assertEquals(
            listOf("CAMERA_LISTENER_ACTIVE", "CAMERA_FIRST_FRAME", "CAMERA_FRAMES_STALLED", "CAMERA_FRAMES_RESUMED"),
            journal.pending(32).map { it.eventCode },
        )
    }

    @Test
    fun defersJournalPublicationOffTheCameraCallbackAndIgnoresQueueFailure() {
        val journal = journal()
        val pending = ArrayDeque<() -> Unit>()
        val recorder = LiveCaptureDiagnosticRecorder(journal) { task -> pending.addLast(task) }
        recorder.recordCamera(frame(1, CameraFrameObservationState.UNOBSERVED, 0))
        recorder.recordRtmp(LiveStreamDiagnosticEvent(LiveStreamDiagnosticKind.START_INVOKED, 1))
        assertEquals(emptyList(), journal.pending(32))
        while (pending.isNotEmpty()) pending.removeFirst().invoke()
        assertEquals(listOf("CAMERA_LISTENER_ACTIVE", "START_INVOKED"), journal.pending(32).map { it.eventCode })

        val rejected = LiveCaptureDiagnosticRecorder(journal) { error("executor shut down") }
        rejected.recordCamera(frame(2, CameraFrameObservationState.UNOBSERVED, 0))
        rejected.recordRtmp(LiveStreamDiagnosticEvent(LiveStreamDiagnosticKind.START_INVOKED, 2))
        assertEquals(2, journal.pending(32).size)
    }

    private fun journal() = DiagnosticJournal.create("run-1", 32, DiagnosticClock { 1_000L })

    private fun frame(generation: Long, state: CameraFrameObservationState, count: Long) = CameraFrameSnapshot(
        generation = generation,
        state = state,
        receivedFrameCount = count,
        lastFrameAgeMillis = if (count > 0) 0 else null,
        codec = if (count > 0) CameraFrameCodec.H264 else null,
        width = if (count > 0) 1920 else null,
        height = if (count > 0) 1080 else null,
        frameRate = if (count > 0) 25 else null,
    )
}
