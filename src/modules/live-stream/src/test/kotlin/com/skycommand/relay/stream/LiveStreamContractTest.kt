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
import com.skycommand.relay.stream.dji.StreamDjiCompletion
import com.skycommand.relay.stream.config.ValidatedStreamConfig
import com.skycommand.relay.stream.state.StreamLifecycleState
import com.skycommand.relay.stream.state.StreamMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LiveStreamContractTest {
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
    fun deviceUnavailabilityCancelsAnActiveStartAndDropsItsLateSuccess() {
        val fixture = Fixture()
        val completion = Completion()
        fixture.liveStream.commandHandler().handle(start(), completion)

        fixture.liveStream.markDeviceUnavailable()

        assertEquals(StreamLifecycleState.FAILED, fixture.liveStream.snapshot().state)
        assertEquals(listOf("reject:Stream operation failed"), completion.events)
        fixture.port.startCompletion!!.succeed()
        assertEquals(StreamLifecycleState.FAILED, fixture.liveStream.snapshot().state)
        assertEquals(listOf("reject:Stream operation failed"), completion.events)
    }

    private class Fixture {
        val port = Port()
        private val coordinator = DjiOperationCoordinator.create(
            executor = OperationExecutor { it() },
            scheduler = OperationScheduler { _, _ -> OperationCancellation { } },
        )
        val liveStream = LiveStream.create(LiveStreamDependencies(port, coordinator))
    }

    private class Completion : CommandCompletion {
        val events = mutableListOf<String>()
        override fun succeed(detail: String) { events += "ok:$detail" }
        override fun reject(detail: String) { events += "reject:$detail" }
    }

    private class Port : DjiStreamPort {
        var startCalls = 0
        var startCompletion: StreamDjiCompletion? = null
        var stopCompletion: StreamDjiCompletion? = null
        override fun start(config: ValidatedStreamConfig, metrics: (StreamMetrics) -> Unit, completion: StreamDjiCompletion) {
            startCalls += 1
            startCompletion = completion
        }
        override fun stop(completion: StreamDjiCompletion) { stopCompletion = completion }
    }

    private fun start() = CommandFrame("start", "live-stream.start", JsonObject(mapOf("rtmpUrl" to JsonString("rtmp://computer/live/device"))))
    private fun stop() = CommandFrame("stop", "live-stream.stop", JsonObject(emptyMap()))
}
