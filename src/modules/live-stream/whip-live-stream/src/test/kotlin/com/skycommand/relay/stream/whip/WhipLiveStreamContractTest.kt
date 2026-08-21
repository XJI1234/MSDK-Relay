package com.skycommand.relay.stream.whip

import com.skycommand.relay.gateway.command.CommandCompletion
import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.JsonString
import com.skycommand.relay.stream.video.EncodedVideoFrame
import com.skycommand.relay.stream.video.EncodedVideoListener
import com.skycommand.relay.stream.video.EncodedVideoSource
import com.skycommand.relay.stream.video.SourceStartResult
import com.skycommand.relay.stream.video.SourceStopResult
import com.skycommand.relay.stream.whip.config.ValidatedWhipStreamConfig
import com.skycommand.relay.stream.whip.publisher.WhipPublisherFailure
import com.skycommand.relay.stream.whip.publisher.WhipTransport
import com.skycommand.relay.stream.whip.publisher.WhipTransportCloseResult
import com.skycommand.relay.stream.whip.publisher.WhipTransportFailure
import com.skycommand.relay.stream.whip.publisher.WhipTransportListener
import com.skycommand.relay.stream.whip.publisher.WhipTransportOpenResult
import com.skycommand.relay.stream.whip.publisher.WhipTransportRejection
import com.skycommand.relay.stream.whip.publisher.WhipTransportSendResult
import com.skycommand.relay.stream.whip.state.WhipStreamFailure
import com.skycommand.relay.stream.whip.state.WhipStreamLifecycle
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WhipLiveStreamContractTest {
    @Test
    fun acceptsStartWithoutCompletingUntilH264PublishingAndStopsExactlyOnce() {
        val fixture = Fixture()
        val startCompletion = Completion()

        assertEquals(
            Unit,
            fixture.handler.handle(start(), startCompletion),
        )
        assertEquals(WhipStreamLifecycle.CONNECTING, fixture.stream.snapshot().state)
        assertEquals(emptyList(), startCompletion.events)

        fixture.transport.connected()
        fixture.source.emit(frame(keyFrame = true, bytes = annexB(0x67, 0x68, 0x65)))
        assertEquals(WhipStreamLifecycle.PUBLISHING, fixture.stream.snapshot().state)
        assertEquals(listOf("succeed:WHIP stream started"), startCompletion.events)

        val stopCompletion = Completion()
        fixture.handler.handle(stop(), stopCompletion)
        assertEquals(WhipStreamLifecycle.IDLE, fixture.stream.snapshot().state)
        assertEquals(listOf("succeed:WHIP stream stopped"), stopCompletion.events)
        assertEquals(1, fixture.source.stopCalls)
        assertEquals(1, fixture.transport.closeCalls)

        fixture.transport.notifyDisconnected()
        assertEquals(listOf("succeed:WHIP stream stopped"), stopCompletion.events)
    }

    @Test
    fun mapsTransportRejectionAndFailureToStableStateWithoutLeakingPlatformDetails() {
        val rejected = Fixture().also {
            it.transport.openResult = WhipTransportOpenResult.Rejected(
                WhipTransportRejection.ENCODED_H264_UNAVAILABLE,
            )
        }
        assertIs<Unit>(rejected.handler.handle(start(), Completion()))
        assertEquals(WhipStreamLifecycle.FAILED, rejected.stream.snapshot().state)
        assertEquals(WhipStreamFailure.UNSUPPORTED_CODEC, rejected.stream.snapshot().failure)

        val failed = Fixture()
        val completion = Completion()
        failed.handler.handle(start(), completion)
        failed.transport.connected()
        failed.transport.notifyFailed(WhipTransportFailure.ICE)

        assertEquals(WhipStreamLifecycle.FAILED, failed.stream.snapshot().state)
        assertEquals(WhipStreamFailure.ICE, failed.stream.snapshot().failure)
        assertEquals(listOf("reject:WHIP stream failed"), completion.events)
        assertTrue(failed.transport.closeCalls <= 1)
    }

    @Test
    fun stopAfterDisconnectSucceedsAndAllowsANewStart() {
        val fixture = Fixture()
        val startCompletion = Completion()
        fixture.handler.handle(start(), startCompletion)
        fixture.transport.connected()
        fixture.source.emit(frame(keyFrame = true, bytes = annexB(0x67, 0x68, 0x65)))
        assertEquals(WhipStreamLifecycle.PUBLISHING, fixture.stream.snapshot().state)
        assertEquals(listOf("succeed:WHIP stream started"), startCompletion.events)

        fixture.transport.notifyDisconnected()
        assertEquals(WhipStreamLifecycle.DISCONNECTED, fixture.stream.snapshot().state)

        val stopCompletion = Completion()
        fixture.handler.handle(stop(), stopCompletion)
        assertEquals(listOf("succeed:WHIP stream stopped"), stopCompletion.events)

        val restart = Completion()
        fixture.handler.handle(start(), restart)
        fixture.transport.connected()
        fixture.source.emit(frame(keyFrame = true, bytes = annexB(0x67, 0x68, 0x65)))
        assertEquals(WhipStreamLifecycle.PUBLISHING, fixture.stream.snapshot().state)
        assertEquals(listOf("succeed:WHIP stream started"), restart.events)
    }

    @Test
    fun deviceUnavailableInvalidatesActiveCommandAndLatePublisherSignals() {
        val fixture = Fixture()
        val completion = Completion()
        fixture.handler.handle(start(), completion)
        fixture.transport.connected()

        fixture.stream.markDeviceUnavailable()
        assertEquals(WhipStreamLifecycle.DISCONNECTED, fixture.stream.snapshot().state)
        assertEquals(listOf("reject:WHIP stream failed"), completion.events)

        fixture.source.emit(frame(keyFrame = true, bytes = annexB(0x67, 0x68, 0x65)))
        fixture.transport.notifyFailed(WhipTransportFailure.NETWORK)
        assertEquals(listOf("reject:WHIP stream failed"), completion.events)
        assertEquals(WhipStreamLifecycle.DISCONNECTED, fixture.stream.snapshot().state)
    }

    @Test
    fun instancesAndDeviceIdsAreIndependentAndCloseIsIdempotent() {
        val first = Fixture(deviceId = "drone-a")
        val second = Fixture(deviceId = "drone-b")

        first.handler.handle(start(), Completion())
        second.handler.handle(start(), Completion())
        first.transport.connected()
        first.source.emit(frame(keyFrame = true, bytes = annexB(0x67, 0x68, 0x65)))

        assertEquals(WhipStreamLifecycle.PUBLISHING, first.stream.snapshot().state)
        assertEquals(WhipStreamLifecycle.CONNECTING, second.stream.snapshot().state)

        first.stream.close()
        first.stream.close()
        assertEquals(1, first.transport.closeCalls)
        assertEquals(WhipStreamLifecycle.DISCONNECTED, first.stream.snapshot().state)
        assertEquals(WhipStreamLifecycle.CONNECTING, second.stream.snapshot().state)
    }

    private class Fixture(
        deviceId: String = "drone-a",
    ) {
        val source = FakeSource()
        val transport = FakeTransport()
        val stream = WhipLiveStream.create(
            WhipLiveStreamDependencies(
                deviceId = deviceId,
                source = source,
                transport = transport,
            ),
        )
        val handler = stream.commandHandler()
    }

    private class Completion : CommandCompletion {
        val events = mutableListOf<String>()

        override fun succeed(detail: String) {
            events += "succeed:$detail"
        }

        override fun reject(detail: String) {
            events += "reject:$detail"
        }
    }

    private class FakeSource : EncodedVideoSource {
        private val listeners = CopyOnWriteArrayList<EncodedVideoListener>()
        var stopCalls = 0

        override fun start(listener: EncodedVideoListener): SourceStartResult {
            listeners += listener
            return SourceStartResult.Started
        }

        override fun stop(): SourceStopResult {
            stopCalls += 1
            return SourceStopResult.Stopped
        }

        fun emit(frame: EncodedVideoFrame) = listeners.last().onFrame(frame)
    }

    private class FakeTransport : WhipTransport {
        private val listeners = CopyOnWriteArrayList<WhipTransportListener>()
        var openResult: WhipTransportOpenResult = WhipTransportOpenResult.Accepted
        var closeCalls = 0

        override fun open(
            config: ValidatedWhipStreamConfig,
            listener: WhipTransportListener,
        ): WhipTransportOpenResult {
            listeners += listener
            return openResult
        }

        override fun send(frame: EncodedVideoFrame): WhipTransportSendResult = WhipTransportSendResult.Accepted

        override fun close(): WhipTransportCloseResult {
            closeCalls += 1
            return WhipTransportCloseResult.Closed
        }

        fun connected() = listeners.last().onConnected()

        fun notifyFailed(reason: WhipTransportFailure) = listeners.last().onFailed(reason)

        fun notifyDisconnected() = listeners.last().onDisconnected()
    }

    private fun start() = CommandFrame(
        "start",
        "live-stream-webrtc.start",
        JsonObject(mapOf("whipUrl" to JsonString("http://computer/live/drone-a/whip"))),
    )

    private fun stop() = CommandFrame("stop", "live-stream-webrtc.stop", JsonObject(emptyMap()))

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
}
