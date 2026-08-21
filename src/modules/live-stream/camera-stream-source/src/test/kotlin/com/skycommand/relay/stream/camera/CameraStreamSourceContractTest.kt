package com.skycommand.relay.stream.camera

import com.skycommand.relay.stream.video.EncodedVideoFrame
import com.skycommand.relay.stream.video.EncodedVideoListener
import com.skycommand.relay.stream.video.EncodedVideoSource
import com.skycommand.relay.stream.video.SourceFailure
import com.skycommand.relay.stream.video.SourceStartResult
import com.skycommand.relay.stream.video.SourceStopResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CameraStreamSourceContractTest {
    @Test
    fun forwardsH264BytesAndAllStreamMetadataWithoutCopyingThePayload() {
        val fixture = Fixture()
        val frames = mutableListOf<EncodedVideoFrame>()
        assertIs<SourceStartResult.Started>(fixture.source.start(EncodedVideoListener { frames += it }))
        val data = byteArrayOf(9, 8, 7, 6, 5)

        fixture.api.emit(
            data,
            1,
            3,
            CameraStreamInfo(CameraStreamCodec.H264, 1920, 1080, 30, 1234, true),
        )

        assertEquals(1, frames.size)
        assertEquals(data, frames.single().data)
        assertEquals(1, frames.single().offset)
        assertEquals(3, frames.single().length)
        assertEquals(1920, frames.single().width)
        assertEquals(1080, frames.single().height)
        assertEquals(30, frames.single().frameRate)
        assertEquals(1234, frames.single().presentationTimeMs)
        assertEquals(true, frames.single().isKeyFrame)
    }

    @Test
    fun reportsUnsupportedCodecAndInvalidFramesWithoutCallingTheConsumer() {
        val fixture = Fixture()
        val frames = mutableListOf<EncodedVideoFrame>()
        fixture.source.start(EncodedVideoListener { frames += it })
        fixture.api.emit(byteArrayOf(1), 0, 1, CameraStreamInfo(CameraStreamCodec.H265, 1, 1, 1, 0, false))
        fixture.api.emit(byteArrayOf(1), 2, 1, CameraStreamInfo(CameraStreamCodec.H264, 1, 1, 1, 0, false))
        fixture.api.emit(byteArrayOf(1), 0, 1, CameraStreamInfo(CameraStreamCodec.H264, 0, 1, 1, 0, false))

        assertEquals(emptyList(), frames)
        assertEquals(
            listOf(
                CameraStreamSourceDiagnosticKind.UNSUPPORTED_CODEC,
                CameraStreamSourceDiagnosticKind.INVALID_FRAME,
                CameraStreamSourceDiagnosticKind.INVALID_FRAME,
            ),
            fixture.diagnostics,
        )
    }

    @Test
    fun removesExactListenerAndDropsLateCallbacksAndOldGenerationFrames() {
        val fixture = Fixture()
        val firstFrames = mutableListOf<EncodedVideoFrame>()
        fixture.source.start(EncodedVideoListener { firstFrames += it })
        val oldListener = fixture.api.listeners.single()
        assertIs<SourceStopResult.Stopped>(fixture.source.stop())
        oldListener.onReceiveStream(byteArrayOf(1), 0, 1, CameraStreamInfo(CameraStreamCodec.H264, 1, 1, 1, 0, false))

        val secondFrames = mutableListOf<EncodedVideoFrame>()
        fixture.source.start(EncodedVideoListener { secondFrames += it })
        oldListener.onReceiveStream(byteArrayOf(1), 0, 1, CameraStreamInfo(CameraStreamCodec.H264, 1, 1, 1, 0, false))
        fixture.api.emit(byteArrayOf(1), 0, 1, CameraStreamInfo(CameraStreamCodec.H264, 1, 1, 1, 0, false))

        assertEquals(1, fixture.api.removeCalls)
        assertEquals(listOf(oldListener), fixture.api.removed)
        assertEquals(emptyList(), firstFrames)
        assertEquals(1, secondFrames.size)
    }

    @Test
    fun isolatesLifecycleFailuresAndListenerExceptions() {
        val addFailure = Fixture().also { it.api.throwOnAdd = true }
        assertEquals(
            SourceFailure.PLATFORM_FAILURE,
            assertIs<SourceStartResult.Failed>(addFailure.source.start(EncodedVideoListener { })).reason,
        )
        assertEquals(listOf(CameraStreamSourceDiagnosticKind.PLATFORM_FAILURE), addFailure.diagnostics)

        val listenerFailure = Fixture()
        listenerFailure.source.start(EncodedVideoListener { error("consumer failure") })
        listenerFailure.api.emit(byteArrayOf(1), 0, 1, CameraStreamInfo(CameraStreamCodec.H264, 1, 1, 1, 0, false))
        assertEquals(listOf(CameraStreamSourceDiagnosticKind.LISTENER_FAILURE), listenerFailure.diagnostics)

        assertIs<SourceStopResult.AlreadyStopped>(addFailure.source.stop())
        assertIs<SourceStartResult.AlreadyStarted>(listenerFailure.source.start(EncodedVideoListener { }))
        assertIs<SourceStopResult.Stopped>(listenerFailure.source.stop())
        assertIs<SourceStartResult.Started>(listenerFailure.source.start(EncodedVideoListener { }))
    }

    @Test
    fun stopsAndReportsPlatformRemovalFailureWithoutLeakingTheCallback() {
        val fixture = Fixture()
        fixture.source.start(EncodedVideoListener { })
        fixture.api.throwOnRemove = true

        assertEquals(
            SourceFailure.PLATFORM_FAILURE,
            assertIs<SourceStopResult.Failed>(fixture.source.stop()).reason,
        )
        fixture.api.emit(byteArrayOf(1), 0, 1, CameraStreamInfo(CameraStreamCodec.H264, 1, 1, 1, 0, false))
        assertEquals(listOf(CameraStreamSourceDiagnosticKind.PLATFORM_FAILURE), fixture.diagnostics)
    }

    private class Fixture {
        val api = FakeApi()
        val diagnostics = mutableListOf<CameraStreamSourceDiagnosticKind>()
        val source = CameraStreamSource.create(api, CameraStreamSourceDiagnosticSink { diagnostics += it })
    }

    private class FakeApi : CameraStreamApi {
        val listeners = mutableListOf<CameraStreamListener>()
        val removed = mutableListOf<CameraStreamListener>()
        var throwOnAdd = false
        var throwOnRemove = false
        var removeCalls = 0

        override fun addReceiveStreamListener(listener: CameraStreamListener) {
            if (throwOnAdd) error("camera API failure")
            listeners += listener
        }

        override fun removeReceiveStreamListener(listener: CameraStreamListener) {
            removeCalls += 1
            removed += listener
            if (throwOnRemove) error("camera API removal failure")
            listeners -= listener
        }

        fun emit(data: ByteArray, offset: Int, length: Int, info: CameraStreamInfo) {
            listeners.last().onReceiveStream(data, offset, length, info)
        }
    }
}
