package com.skycommand.relay.app

import com.skycommand.relay.photo.CameraMediaReadiness
import com.skycommand.relay.stream.camera.observer.CameraFrameObservationState
import com.skycommand.relay.stream.camera.observer.CameraFrameObserver
import com.skycommand.relay.stream.dji.android.LiveStreamDiagnosticEvent
import com.skycommand.relay.stream.dji.android.LiveStreamDiagnosticKind

internal class CameraZeroFrameRecovery(
    private val mediaReadiness: CameraMediaReadiness,
    private val frames: CameraFrameObserver,
    private val diagnostic: (String, Long, String) -> Unit,
) {
    fun onRtmpEvent(event: LiveStreamDiagnosticEvent) {
        if (event.kind != LiveStreamDiagnosticKind.FIRST_STATUS || event.fps != 0 || event.bitrateKbps != 0) return
        if (frames.snapshot().state != CameraFrameObservationState.UNOBSERVED) return
        mediaReadiness.inspectBeforeZeroFrameRecovery { state ->
            diagnostic(
                "CAMERA_ZERO_FRAME_MEDIA_STATE_BEFORE_RECOVERY",
                event.attempt,
                "cameraMode=${state.cameraMode ?: "unknown"};playingBack=${state.playingBack ?: "unknown"}",
            )
            if (!mediaReadiness.recoverAfterZeroFrameStart { recovered ->
                    diagnostic(if (recovered) "CAMERA_ZERO_FRAME_RECOVERY_SUCCEEDED" else "CAMERA_ZERO_FRAME_RECOVERY_FAILED", event.attempt, "")
                }
            ) return@inspectBeforeZeroFrameRecovery
            diagnostic("CAMERA_ZERO_FRAME_RECOVERY_STARTED", event.attempt, "")
        }
    }
}
