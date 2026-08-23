package com.skycommand.relay.stream.whip.publisher

import com.skycommand.relay.stream.video.EncodedVideoFrame
import com.skycommand.relay.stream.video.EncodedVideoListener
import com.skycommand.relay.stream.video.EncodedVideoSource
import com.skycommand.relay.stream.video.SourceStartResult
import com.skycommand.relay.stream.video.SourceStopResult
import com.skycommand.relay.stream.video.SourceFailure
import com.skycommand.relay.stream.whip.config.ValidatedWhipStreamConfig
import com.skycommand.relay.stream.whip.state.WhipStreamMetrics
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WhipPublisherContractTest {
    @Test
    fun reportsPublishingOnlyAfterTransportKeyFrameAndParameterSetsAreReady() {
        val fixture = Fixture()
        val started = assertIs<WhipPublisherStartResult.Accepted>(fixture.publisher.start(config(), fixture.source, fixture.events))

        assertEquals(WhipPublisherState.CONNECTING, fixture.publisher.snapshot().state)

        fixture.transport.connected()
        fixture.source.emit(frame(keyFrame = true, bytes = annexB(0x65)))
        assertEquals(WhipPublisherState.CONNECTING, fixture.publisher.snapshot().state)
        fixture.source.emit(frame(keyFrame = false, bytes = annexB(0x67, 0x68)))

        assertEquals(WhipPublisherState.PUBLISHING, fixture.publisher.snapshot().state)
        assertEquals(listOf("publishing:${started.generation}"), fixture.events.events)
        assertEquals(WhipStreamMetrics(resolution = "1920x1080", fps = 30.0), fixture.events.metrics)
    }

    @Test
    fun flushesBufferedParameterSetsAndKeyframeWhenTransportConnects() {
        val fixture = Fixture()
        fixture.publisher.start(config(), fixture.source, fixture.events)
        fixture.source.emit(frame(keyFrame = true, bytes = annexB(0x67, 0x68, 0x65)))
        assertEquals(WhipPublisherState.CONNECTING, fixture.publisher.snapshot().state)
        assertEquals(0, fixture.transport.sent.size)

        fixture.transport.connected()
        assertEquals(WhipPublisherState.PUBLISHING, fixture.publisher.snapshot().state)
        assertEquals(1, fixture.transport.sent.size)
        assertEquals(listOf("publishing:${fixture.publisher.snapshot().generation}"), fixture.events.events)
    }

    @Test
    fun acceptsLengthPrefixedH264AndDropsFramesUnderBackpressureWithoutBlocking() {
        val fixture = Fixture()
        fixture.transport.sendResult = WhipTransportSendResult.Backpressured
        fixture.publisher.start(config(), fixture.source, fixture.events)
        fixture.transport.connected()

        fixture.source.emit(frame(keyFrame = false, bytes = avcc(0x67, 0x68)))
        fixture.source.emit(frame(keyFrame = true, bytes = avcc(0x67, 0x68, 0x65)))
        assertEquals(2, fixture.publisher.snapshot().droppedFrames)
        assertEquals(WhipPublisherState.CONNECTING, fixture.publisher.snapshot().state)

        fixture.transport.sendResult = WhipTransportSendResult.Accepted
        fixture.source.emit(frame(keyFrame = true, bytes = avcc(0x67, 0x68, 0x65)))
        assertEquals(WhipPublisherState.PUBLISHING, fixture.publisher.snapshot().state)
    }

    @Test
    fun rejectsInvalidConfigurationTransportFailureAndSourceFailureSafely() {
        val invalid = Fixture()
        assertEquals(
            WhipPublisherStartRejection.INVALID_CONFIGURATION,
            assertIs<WhipPublisherStartResult.Rejected>(invalid.publisher.start(
                ValidatedWhipStreamConfig("rtmp://computer/live/drone/whip"),
                invalid.source,
                invalid.events,
            )).reason,
        )
        assertEquals(0, invalid.transport.openCalls)
        assertEquals(0, invalid.source.startCalls)

        val transportFailure = Fixture().also {
            it.transport.openResult = WhipTransportOpenResult.Rejected(WhipTransportRejection.ENCODED_H264_UNAVAILABLE)
        }
        assertEquals(
            WhipPublisherStartRejection.TRANSPORT_REJECTED,
            assertIs<WhipPublisherStartResult.Rejected>(transportFailure.publisher.start(config(), transportFailure.source, transportFailure.events)).reason,
        )
        assertEquals(WhipPublisherState.FAILED, transportFailure.publisher.snapshot().state)
        assertEquals(listOf("failed:ENCODED_H264_UNAVAILABLE"), transportFailure.events.events)

        val sourceFailure = Fixture().also { it.source.startResult = SourceStartResult.Failed(SourceFailure.PLATFORM_FAILURE) }
        assertEquals(
            WhipPublisherStartRejection.SOURCE_REJECTED,
            assertIs<WhipPublisherStartResult.Rejected>(sourceFailure.publisher.start(config(), sourceFailure.source, sourceFailure.events)).reason,
        )
        assertEquals(WhipPublisherState.FAILED, sourceFailure.publisher.snapshot().state)
        assertEquals(1, sourceFailure.transport.closeCalls)

        val codecFailure = Fixture()
        assertIs<WhipPublisherStartResult.Accepted>(codecFailure.publisher.start(config(), codecFailure.source, codecFailure.events))
        codecFailure.source.fail(SourceFailure.UNSUPPORTED_CODEC)
        assertEquals(WhipPublisherState.FAILED, codecFailure.publisher.snapshot().state)
        assertEquals(listOf("failed:ENCODED_H264_UNAVAILABLE"), codecFailure.events.events)
        assertEquals(1, codecFailure.transport.closeCalls)
        assertEquals(1, codecFailure.source.stopCalls)
    }

    @Test
    fun stopsResourcesAndIgnoresLateCallbacksFromThePreviousGeneration() {
        val fixture = Fixture()
        val first = assertIs<WhipPublisherStartResult.Accepted>(fixture.publisher.start(config(), fixture.source, fixture.events))
        fixture.transport.connected()
        fixture.source.emit(frame(keyFrame = true, bytes = annexB(0x67, 0x68, 0x65)))
        val stop = assertIs<WhipPublisherStopResult.Accepted>(fixture.publisher.stop())
        assertEquals(first.generation, stop.generation)
        assertEquals(WhipPublisherState.IDLE, fixture.publisher.snapshot().state)
        assertEquals(listOf("publishing:${first.generation}", "stopped:${first.generation}"), fixture.events.events)

        fixture.transport.sent.clear()
        val second = assertIs<WhipPublisherStartResult.Accepted>(fixture.publisher.start(config(), fixture.source, fixture.events))
        fixture.transport.listeners[0].onFailed(WhipTransportFailure.NETWORK)
        fixture.source.listeners[0].onFrame(frame(keyFrame = true, bytes = annexB(0x67, 0x68, 0x65)))
        assertEquals(WhipPublisherState.CONNECTING, fixture.publisher.snapshot().state)
        assertEquals(second.generation, fixture.publisher.snapshot().generation)
        assertEquals(0, fixture.transport.sent.size)
    }

    @Test
    fun mapsDisconnectTransportFailureAndDuplicateTerminalSignalsOnce() {
        val fixture = Fixture()
        fixture.publisher.start(config(), fixture.source, fixture.events)
        fixture.transport.connected()
        fixture.transport.listeners.last().onDisconnected()
        fixture.transport.listeners.last().onDisconnected()
        assertEquals(WhipPublisherState.DISCONNECTED, fixture.publisher.snapshot().state)
        assertEquals(listOf("disconnected:1"), fixture.events.events)

        val failed = Fixture()
        failed.publisher.start(config(), failed.source, failed.events)
        failed.transport.connected()
        failed.transport.sendResult = WhipTransportSendResult.Failed(WhipTransportFailure.ICE)
        failed.source.emit(frame(keyFrame = false, bytes = annexB(0x61)))
        failed.transport.listeners.last().onFailed(WhipTransportFailure.ICE)
        assertEquals(WhipPublisherState.FAILED, failed.publisher.snapshot().state)
        assertEquals(listOf("failed:ICE"), failed.events.events)
    }

    @Test
    fun handlesStopRepetitionAndStopFailureWithoutThrowingPlatformDetails() {
        val fixture = Fixture()
        assertIs<WhipPublisherStopResult.AlreadyStopped>(fixture.publisher.stop())
        fixture.publisher.start(config(), fixture.source, fixture.events)
        fixture.transport.closeResult = WhipTransportCloseResult.Failed(WhipTransportFailure.NETWORK)
        assertIs<WhipPublisherStopResult.Accepted>(fixture.publisher.stop())
        assertEquals(WhipPublisherState.FAILED, fixture.publisher.snapshot().state)
        assertEquals(listOf("failed:STOP_FAILED"), fixture.events.events)
        assertIs<WhipPublisherStopResult.AlreadyStopped>(fixture.publisher.stop())
    }

    private class Fixture {
        val transport = FakeTransport()
        val source = FakeSource()
        val events = Events()
        val publisher = WhipPublisher.create(WhipPublisherDependencies(transport))
    }

    private class FakeTransport : WhipTransport {
        val listeners = CopyOnWriteArrayList<WhipTransportListener>()
        val sent = CopyOnWriteArrayList<EncodedVideoFrame>()
        var openResult: WhipTransportOpenResult = WhipTransportOpenResult.Accepted
        var sendResult: WhipTransportSendResult = WhipTransportSendResult.Accepted
        var closeResult: WhipTransportCloseResult = WhipTransportCloseResult.Closed
        var openCalls = 0
        var closeCalls = 0

        override fun open(config: ValidatedWhipStreamConfig, listener: WhipTransportListener): WhipTransportOpenResult {
            openCalls += 1
            listeners += listener
            return openResult
        }

        override fun send(frame: EncodedVideoFrame): WhipTransportSendResult {
            sent += frame
            return sendResult
        }

        override fun close(): WhipTransportCloseResult {
            closeCalls += 1
            return closeResult
        }

        fun connected() = listeners.last().onConnected()
    }

    private class FakeSource : EncodedVideoSource {
        val listeners = CopyOnWriteArrayList<EncodedVideoListener>()
        var startResult: SourceStartResult = SourceStartResult.Started
        var stopResult: SourceStopResult = SourceStopResult.Stopped
        var startCalls = 0
        var stopCalls = 0
        private var onFailure: ((SourceFailure) -> Unit)? = null

        override fun start(listener: EncodedVideoListener): SourceStartResult {
            startCalls += 1
            listeners += listener
            return startResult
        }

        override fun start(listener: EncodedVideoListener, onFailure: (SourceFailure) -> Unit): SourceStartResult {
            this.onFailure = onFailure
            return start(listener)
        }

        override fun stop(): SourceStopResult {
            stopCalls += 1
            return stopResult
        }

        fun emit(frame: EncodedVideoFrame) = listeners.last().onFrame(frame)

        fun fail(reason: SourceFailure) {
            onFailure?.invoke(reason)
        }
    }

    private class Events : WhipPublisherListener {
        val events = mutableListOf<String>()
        var metrics: WhipStreamMetrics? = null

        override fun onPublishing(generation: Long, metrics: WhipStreamMetrics?) {
            events += "publishing:$generation"
            this.metrics = metrics
        }

        override fun onStopped(generation: Long) { events += "stopped:$generation" }

        override fun onFailed(generation: Long, reason: WhipPublisherFailure) { events += "failed:$reason" }

        override fun onDisconnected(generation: Long) { events += "disconnected:$generation" }
    }

    private fun config() = ValidatedWhipStreamConfig("http://computer/live/drone/whip")

    private fun frame(keyFrame: Boolean, bytes: ByteArray) = EncodedVideoFrame(
        data = bytes,
        offset = 0,
        length = bytes.size,
        width = 1920,
        height = 1080,
        frameRate = 30,
        presentationTimeMs = 1,
        isKeyFrame = keyFrame,
    )

    private fun annexB(vararg nalTypes: Int): ByteArray = nalTypes.flatMap { type ->
        listOf(0, 0, 0, 1, type, 1)
    }.map(Int::toByte).toByteArray()

    private fun avcc(vararg nalTypes: Int): ByteArray = nalTypes.flatMap { type ->
        listOf(0, 0, 0, 2, type, 1)
    }.map(Int::toByte).toByteArray()
}
