package com.skycommand.relay.app

import com.skycommand.relay.photo.CameraMediaReadiness
import com.skycommand.relay.photo.executor.CameraMediaStateSnapshot
import com.skycommand.relay.photo.executor.CameraMediaRecoveryPort
import com.skycommand.relay.stream.camera.observer.CameraFrameObservationPort
import com.skycommand.relay.stream.camera.observer.CameraFrameObserver
import com.skycommand.relay.stream.camera.observer.CameraFrameReceipt
import com.skycommand.relay.stream.camera.observer.CameraFrameReceiptListener
import com.skycommand.relay.stream.dji.android.LiveStreamDiagnosticEvent
import com.skycommand.relay.stream.dji.android.LiveStreamDiagnosticKind
import kotlin.test.Test
import kotlin.test.assertEquals

class CameraZeroFrameRecoveryTest {
    @Test
    fun recoversExactlyOnceWhenTheFirstRtmpStatusHasZeroFpsAndNoCameraFrame() {
        val recovery = RecordingRecovery()
        val readiness = CameraMediaReadiness(recovery)
        readiness.onVideoSourceChanged(available = true)
        recovery.completeStartup()
        val frames = CameraFrameObserver.create(FakeFramePort())
        frames.start()
        val fallback = CameraZeroFrameRecovery(readiness, frames) { _, _, _ -> }

        fallback.onRtmpEvent(LiveStreamDiagnosticEvent(LiveStreamDiagnosticKind.FIRST_STATUS, 1, 0, 0))
        fallback.onRtmpEvent(LiveStreamDiagnosticEvent(LiveStreamDiagnosticKind.FIRST_STATUS, 1, 0, 0))
        fallback.onRtmpEvent(LiveStreamDiagnosticEvent(LiveStreamDiagnosticKind.FIRST_STATUS, 2, 24, 3_000))

        assertEquals(1, recovery.zeroFrameCalls)
    }

    @Test
    fun doesNotRecoverWhenRtmpReportsFramesOrTheCameraObserverHasSeenOne() {
        val recovery = RecordingRecovery()
        val readiness = CameraMediaReadiness(recovery)
        readiness.onVideoSourceChanged(available = true)
        recovery.completeStartup()
        val port = FakeFramePort()
        val frames = CameraFrameObserver.create(port)
        frames.start()
        val fallback = CameraZeroFrameRecovery(readiness, frames) { _, _, _ -> }

        fallback.onRtmpEvent(LiveStreamDiagnosticEvent(LiveStreamDiagnosticKind.FIRST_STATUS, 1, 24, 3_000))
        port.emit(CameraFrameReceipt(1, com.skycommand.relay.stream.camera.observer.CameraFrameCodec.H264, 1920, 1080, 30))
        fallback.onRtmpEvent(LiveStreamDiagnosticEvent(LiveStreamDiagnosticKind.FIRST_STATUS, 2, 0, 0))

        assertEquals(0, recovery.zeroFrameCalls)
    }

    @Test
    fun recordsTheReadOnlyCameraMediaStateBeforeZeroFrameRecovery() {
        val recovery = RecordingRecovery()
        val readiness = CameraMediaReadiness(recovery)
        readiness.onVideoSourceChanged(available = true)
        recovery.completeStartup()
        val frames = CameraFrameObserver.create(FakeFramePort())
        frames.start()
        val diagnostics = mutableListOf<Triple<String, Long, String>>()
        val fallback = CameraZeroFrameRecovery(readiness, frames) { kind, attempt, detail ->
            diagnostics += Triple(kind, attempt, detail)
        }

        fallback.onRtmpEvent(LiveStreamDiagnosticEvent(LiveStreamDiagnosticKind.FIRST_STATUS, 7, 0, 0))

        val expected: Set<Triple<String, Long, String>> = setOf(
                Triple("CAMERA_ZERO_FRAME_RECOVERY_STARTED", 7, ""),
                Triple("CAMERA_ZERO_FRAME_RECOVERY_SUCCEEDED", 7, ""),
                Triple("CAMERA_ZERO_FRAME_MEDIA_STATE_BEFORE_RECOVERY", 7, "cameraMode=PHOTO_NORMAL;playingBack=false"),
            )

        assertEquals(expected, diagnostics.toSet())
    }

    private class RecordingRecovery : CameraMediaRecoveryPort {
        private var startup: ((Boolean) -> Unit)? = null
        var zeroFrameCalls = 0

        override fun recover(completion: (Boolean) -> Unit) {
            startup = completion
        }

        override fun recoverAfterZeroFrameStart(completion: (Boolean) -> Unit) {
            zeroFrameCalls += 1
            completion(true)
        }

        override fun inspectBeforeZeroFrameRecovery(completion: (CameraMediaStateSnapshot) -> Unit) {
            completion(CameraMediaStateSnapshot(cameraMode = "PHOTO_NORMAL", playingBack = false))
        }

        fun completeStartup() = startup!!.invoke(true)
    }

    private class FakeFramePort : CameraFrameObservationPort {
        private var listener: CameraFrameReceiptListener? = null

        override fun addReceiveStreamListener(listener: CameraFrameReceiptListener) {
            this.listener = listener
        }

        override fun removeReceiveStreamListener(listener: CameraFrameReceiptListener) = Unit

        fun emit(receipt: CameraFrameReceipt) {
            listener?.onFrame(receipt)
        }
    }
}
