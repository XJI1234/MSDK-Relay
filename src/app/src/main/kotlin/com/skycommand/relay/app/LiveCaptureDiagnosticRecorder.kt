package com.skycommand.relay.app

import com.skycommand.relay.diagnostics.DiagnosticJournal
import com.skycommand.relay.diagnostics.DiagnosticLevel
import com.skycommand.relay.stream.camera.observer.CameraFrameObservationState
import com.skycommand.relay.stream.camera.observer.CameraFrameObserverDiagnosticKind
import com.skycommand.relay.stream.camera.observer.CameraFrameSnapshot
import com.skycommand.relay.stream.dji.android.LiveStreamDiagnosticEvent

internal class LiveCaptureDiagnosticRecorder(
    private val journal: DiagnosticJournal,
    private val dispatch: ((() -> Unit) -> Unit) = { task -> task() },
) {
    private var generation = 0L
    private var state = CameraFrameObservationState.UNAVAILABLE
    private var lastSignaledCount = 0L

    @Synchronized
    fun recordCamera(snapshot: CameraFrameSnapshot) {
        if (snapshot.generation < generation || snapshot.generation == 0L) return
        if (snapshot.generation != generation) {
            generation = snapshot.generation
            state = CameraFrameObservationState.UNAVAILABLE
            lastSignaledCount = 0L
        }
        if (snapshot.state == state) {
            lastSignaledCount = maxOf(lastSignaledCount, snapshot.receivedFrameCount)
            return
        }
        val previous = state
        state = snapshot.state
        lastSignaledCount = maxOf(lastSignaledCount, snapshot.receivedFrameCount)
        val detail = "generation=$generation;count=${snapshot.receivedFrameCount}"
        val (level, code, safeDetail) = when (snapshot.state) {
            CameraFrameObservationState.UNOBSERVED ->
                Triple(DiagnosticLevel.INFO, "CAMERA_LISTENER_ACTIVE", detail)
            CameraFrameObservationState.RECEIVING -> {
                val code = if (previous == CameraFrameObservationState.STALLED) "CAMERA_FRAMES_RESUMED" else "CAMERA_FIRST_FRAME"
                Triple(
                    DiagnosticLevel.INFO, code,
                    "$detail;codec=${snapshot.codec};width=${snapshot.width};height=${snapshot.height};frameRate=${snapshot.frameRate}",
                )
            }
            CameraFrameObservationState.STALLED ->
                Triple(DiagnosticLevel.WARN, "CAMERA_FRAMES_STALLED", "$detail;ageMillis=${snapshot.lastFrameAgeMillis}")
            CameraFrameObservationState.UNAVAILABLE ->
                Triple(
                    DiagnosticLevel.INFO, "CAMERA_LISTENER_STOPPED",
                    "generation=$generation;lastSignaledCount=$lastSignaledCount",
                )
        }
        submit { journal.record(level, "camera-frame-observer", code, null, safeDetail) }
    }

    @Synchronized
    fun recordCameraFailure(kind: CameraFrameObserverDiagnosticKind) {
        val lastGeneration = generation
        submit { journal.record(DiagnosticLevel.WARN, "camera-frame-observer", kind.name, null, "lastGeneration=$lastGeneration") }
    }

    fun recordRtmp(event: LiveStreamDiagnosticEvent) {
        val metrics = if (event.fps != null && event.bitrateKbps != null) {
            ";fps=${event.fps};bitrateKbps=${event.bitrateKbps}"
        } else ""
        submit {
            journal.record(
                if (event.kind.isFailure) DiagnosticLevel.WARN else DiagnosticLevel.INFO,
                "live-stream-msdk", event.kind.name, null, "attempt=${event.attempt}$metrics",
            )
        }
    }

    private fun submit(task: () -> Unit) {
        runCatching { dispatch { runCatching(task) } }
    }
}
