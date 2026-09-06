package com.skycommand.relay.stream.camera.observer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class CameraFrameObserverContractTest {
    @Test
    fun distinguishesRegisteredWithoutFramesFromActualEncodedCameraFrames() {
        var now = 1_000L
        val port = FakePort()
        val observer = CameraFrameObserver.create(port, clock = { now })

        assertEquals(CameraFrameObservationState.UNAVAILABLE, observer.snapshot().state)
        assertIs<CameraFrameObservationStartResult.Started>(observer.start())
        assertEquals(CameraFrameObservationState.UNOBSERVED, observer.snapshot().state)

        port.emit(validFrame())

        assertEquals(
            CameraFrameSnapshot(
                generation = 1,
                state = CameraFrameObservationState.RECEIVING,
                receivedFrameCount = 1,
                lastFrameAgeMillis = 0,
                codec = CameraFrameCodec.H264,
                width = 1920,
                height = 1080,
                frameRate = 30,
            ),
            observer.snapshot(),
        )
    }

    @Test
    fun ignoresInvalidAndOldGenerationFrames() {
        var now = 1_000L
        val port = FakePort()
        val observer = CameraFrameObserver.create(port, clock = { now })
        observer.start()
        val oldListener = port.listener()

        oldListener.onFrame(validFrame(byteCount = 0))
        oldListener.onFrame(validFrame(width = 0))
        assertEquals(CameraFrameObservationState.UNOBSERVED, observer.snapshot().state)
        assertEquals(0, observer.snapshot().receivedFrameCount)

        observer.stop()
        observer.start()
        oldListener.onFrame(validFrame())
        assertEquals(CameraFrameObservationState.UNOBSERVED, observer.snapshot().state)

        port.emit(validFrame(codec = CameraFrameCodec.H265, width = 3840, height = 2160, frameRate = 60))
        assertEquals(CameraFrameObservationState.RECEIVING, observer.snapshot().state)
        assertEquals(1, observer.snapshot().receivedFrameCount)
        assertEquals(CameraFrameCodec.H265, observer.snapshot().codec)
        assertEquals(60, observer.snapshot().frameRate)
    }

    @Test
    fun reportsAStallOnlyAfterFramesWerePreviouslyObserved() {
        var now = 1_000L
        val unobserved = CameraFrameObserver.create(FakePort(), clock = { now })
        unobserved.start()
        now += 60_000
        assertEquals(CameraFrameObservationState.UNOBSERVED, unobserved.evaluate().state)

        val port = FakePort()
        val observer = CameraFrameObserver.create(port, clock = { now })
        observer.start()
        port.emit(validFrame(frameRate = 30))
        now += 5_999
        assertEquals(CameraFrameObservationState.RECEIVING, observer.evaluate().state)
        now += 1
        assertEquals(CameraFrameObservationState.STALLED, observer.evaluate().state)
        assertEquals(6_000, observer.snapshot().lastFrameAgeMillis)
    }

    @Test
    fun registrationFailureLeavesNoActiveGenerationAndDoesNotClaimADeviceDisconnect() {
        val port = FakePort(throwWhenAdding = true)
        val observer = CameraFrameObserver.create(port, clock = { 1_000L })

        assertIs<CameraFrameObservationStartResult.Failed>(observer.start())
        assertEquals(CameraFrameObservationState.UNAVAILABLE, observer.snapshot().state)
        assertEquals(0, observer.snapshot().generation)
        assertNull(observer.snapshot().lastFrameAgeMillis)
    }

    private fun validFrame(
        byteCount: Int = 1_024,
        codec: CameraFrameCodec = CameraFrameCodec.H264,
        width: Int = 1920,
        height: Int = 1080,
        frameRate: Int = 30,
    ) = CameraFrameReceipt(byteCount, codec, width, height, frameRate)

    private class FakePort(
        private val throwWhenAdding: Boolean = false,
    ) : CameraFrameObservationPort {
        private val listeners = mutableListOf<CameraFrameReceiptListener>()

        override fun addReceiveStreamListener(listener: CameraFrameReceiptListener) {
            if (throwWhenAdding) error("platform unavailable")
            listeners += listener
        }

        override fun removeReceiveStreamListener(listener: CameraFrameReceiptListener) {
            listeners -= listener
        }

        fun listener(): CameraFrameReceiptListener = listeners.single()
        fun emit(frame: CameraFrameReceipt) = listeners.toList().forEach { it.onFrame(frame) }
    }
}
