package com.skycommand.relay.stream

import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellation
import com.skycommand.relay.device.operation.OperationExecutor
import com.skycommand.relay.device.operation.OperationScheduler
import com.skycommand.relay.gateway.command.CommandCompletion
import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.JsonString
import com.skycommand.relay.stream.dji.DjiStreamPort
import com.skycommand.relay.stream.dji.DjiStreamStatus
import com.skycommand.relay.stream.dji.StreamDjiCompletion
import com.skycommand.relay.stream.config.ValidatedStreamConfig
import com.skycommand.relay.stream.state.StreamLifecycleState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class LiveStreamContractTest {
    @Test
    fun declaresAReadOnlyStartGateBeforeDispatchingToDji() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/stream/LiveStream.kt"),
            Path("src/modules/live-stream/src/main/kotlin/com/skycommand/relay/stream/LiveStream.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("StreamStartGate"))
    }

    @Test
    fun reportsStartAndStopAfterDjiTerminalSuccess() {
        val fixture = Fixture()
        val startCompletion = Completion()
        fixture.liveStream.commandHandler().handle(start(), startCompletion)
        assertEquals(emptyList(), startCompletion.events)
        fixture.port.startCompletion!!.succeed()
        assertEquals(listOf("ok:Stream started"), startCompletion.events)
        assertEquals(StreamLifecycleState.STREAMING, fixture.liveStream.snapshot().state)

        val stopCompletion = Completion()
        fixture.liveStream.commandHandler().handle(stop(), stopCompletion)
        fixture.port.stopCompletion!!.succeed()
        assertEquals(listOf("ok:Stream stopped"), stopCompletion.events)
        assertEquals(StreamLifecycleState.STOPPED, fixture.liveStream.snapshot().state)
    }

    @Test
    fun rejectsInvalidStartBeforeCallingDjiAndReportsFailureOnce() {
        val fixture = Fixture()
        val completion = Completion()
        fixture.liveStream.commandHandler().handle(
            CommandFrame("1", "live-stream.start", JsonObject(mapOf("rtmpUrl" to JsonString("http://bad/live")))),
            completion,
        )

        assertEquals(listOf("reject:RTMP configuration is invalid"), completion.events)
        assertEquals(0, fixture.port.startCalls)
    }

    @Test
    fun rejectsStartBeforeCallingDjiWhenTheReadOnlyDeviceGateIsClosed() {
        val fixture = Fixture(startAllowed = false)
        val completion = Completion()

        fixture.liveStream.commandHandler().handle(start(), completion)

        assertEquals(listOf("reject:Stream operation was rejected"), completion.events)
        assertEquals(0, fixture.port.startCalls)
        assertEquals(StreamLifecycleState.STOPPED, fixture.liveStream.snapshot().state)
    }

    @Test
    fun mapsDjiFailureAndDuplicateCallbackToOneSafeRelayResult() {
        val fixture = Fixture()
        val completion = Completion()
        fixture.liveStream.commandHandler().handle(start(), completion)
        fixture.port.startCompletion!!.fail()
        fixture.port.startCompletion!!.fail()

        assertEquals(listOf("reject:Stream operation failed"), completion.events)
        assertEquals(StreamLifecycleState.FAILED, fixture.liveStream.snapshot().state)
    }

    @Test
    fun deviceUnavailabilityDoesNotIssueStopBesideAnUnconfirmedStart() {
        val fixture = Fixture()
        val completion = Completion()
        fixture.liveStream.commandHandler().handle(start(), completion)

        fixture.liveStream.markDeviceUnavailable()

        assertEquals(StreamLifecycleState.FAILED, fixture.liveStream.snapshot().state)
        assertEquals(listOf("reject:Stream operation failed"), completion.events)
        assertEquals(0, fixture.port.stopCalls)
        fixture.port.startCompletion!!.succeed()
        assertEquals(StreamLifecycleState.FAILED, fixture.liveStream.snapshot().state)
        assertEquals(listOf("reject:Stream operation failed"), completion.events)
        assertEquals(1, fixture.port.stopCalls)
    }

    @Test
    fun deviceUnavailabilityStopsAnActiveDjiStream() {
        val fixture = Fixture()
        val startCompletion = Completion()
        fixture.liveStream.commandHandler().handle(start(), startCompletion)
        fixture.port.startCompletion!!.succeed()
        assertEquals(StreamLifecycleState.STREAMING, fixture.liveStream.snapshot().state)

        fixture.liveStream.markDeviceUnavailable()

        assertEquals(1, fixture.port.stopCalls)
        assertEquals(StreamLifecycleState.FAILED, fixture.liveStream.snapshot().state)
    }

    @Test
    fun sourceUnavailabilityStopsOnlyTheProductionRtmpStream() {
        val fixture = Fixture()
        val completion = Completion()
        fixture.liveStream.commandHandler().handle(start(), completion)
        fixture.port.startCompletion!!.succeed()

        fixture.liveStream.markSourceUnavailable()

        assertEquals(1, fixture.port.stopCalls)
        assertEquals(StreamLifecycleState.FAILED, fixture.liveStream.snapshot().state)
        assertEquals("Video source unavailable", fixture.liveStream.snapshot().notice)
    }

    private class Fixture(startAllowed: Boolean = true) {
        val port = Port()
        private val coordinator = DjiOperationCoordinator.create(
            executor = OperationExecutor { it() },
            scheduler = OperationScheduler { _, _ -> OperationCancellation { } },
        )
        val liveStream = LiveStream.create(LiveStreamDependencies(port, coordinator, StreamStartGate { startAllowed }))
    }

    private class Completion : CommandCompletion {
        val events = mutableListOf<String>()
        override fun succeed(detail: String) { events += "ok:$detail" }
        override fun reject(detail: String) { events += "reject:$detail" }
    }

    private class Port : DjiStreamPort {
        var startCalls = 0
        var stopCalls = 0
        var startCompletion: StreamDjiCompletion? = null
        var stopCompletion: StreamDjiCompletion? = null
        override fun start(config: ValidatedStreamConfig, status: (DjiStreamStatus) -> Unit, runtimeFailure: () -> Unit, completion: StreamDjiCompletion) {
            startCalls += 1
            startCompletion = completion
        }
        override fun stop(completion: StreamDjiCompletion) {
            stopCalls += 1
            stopCompletion = completion
        }
    }

    private fun start() = CommandFrame("start", "live-stream.start", JsonObject(mapOf("rtmpUrl" to JsonString("rtmp://computer/live/device"))))
    private fun stop() = CommandFrame("stop", "live-stream.stop", JsonObject(emptyMap()))
}
