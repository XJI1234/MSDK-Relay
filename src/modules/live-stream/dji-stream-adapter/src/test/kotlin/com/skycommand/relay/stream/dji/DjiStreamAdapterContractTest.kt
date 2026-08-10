package com.skycommand.relay.stream.dji

import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellation
import com.skycommand.relay.device.operation.OperationExecutor
import com.skycommand.relay.device.operation.OperationScheduler
import com.skycommand.relay.stream.config.ValidatedStreamConfig
import com.skycommand.relay.stream.state.StreamLifecycleState
import com.skycommand.relay.stream.state.StreamMetrics
import com.skycommand.relay.stream.state.StreamStateStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DjiStreamAdapterContractTest {
    @Test
    fun reportsOneSafeTerminalOutcomeAfterAnAcceptedOperation() {
        val fixture = Fixture()
        val outcomes = mutableListOf<StreamDjiTerminalOutcome>()

        fixture.adapter.start(config(), StreamDjiTerminalListener { outcomes += it })
        fixture.port.startCompletion!!.succeed()
        fixture.port.startCompletion!!.succeed()

        assertEquals(listOf(StreamDjiTerminalOutcome.SUCCEEDED), outcomes)
    }

    @Test
    fun reportsStartAndStopOnlyAfterDjiTerminalSuccess() {
        val fixture = Fixture()
        val start = assertIs<DjiStreamStartResult.Accepted>(fixture.adapter.start(config()))
        assertEquals(StreamLifecycleState.STARTING, fixture.store.snapshot().state)
        fixture.port.startCompletion!!.succeed()
        assertEquals(StreamLifecycleState.STREAMING, fixture.store.snapshot().state)

        assertIs<DjiStreamStopResult.Accepted>(fixture.adapter.stop())
        assertEquals(StreamLifecycleState.STOPPING, fixture.store.snapshot().state)
        fixture.port.stopCompletion!!.succeed()
        assertEquals(StreamLifecycleState.STOPPED, fixture.store.snapshot().state)
    }

    @Test
    fun forwardsMetricsAndIgnoresDuplicateCompletion() {
        val fixture = Fixture()
        fixture.adapter.start(config())
        fixture.port.metrics!!.invoke(StreamMetrics("720p", 30.0, 800.0, 20))
        assertEquals(null, fixture.store.snapshot().metrics)
        fixture.port.startCompletion!!.succeed()
        fixture.port.metrics!!.invoke(StreamMetrics("720p", 30.0, 800.0, 20))
        fixture.port.startCompletion!!.succeed()
        assertEquals(StreamMetrics("720p", 30.0, 800.0, 20), fixture.store.snapshot().metrics)
    }

    @Test
    fun mapsPreconditionsAdapterFailureTimeoutAndCancellationToSafeResults() {
        val empty = Fixture()
        assertEquals(DjiStreamRejection.NO_ACTIVE_STREAM, assertIs<DjiStreamStopResult.Rejected>(empty.adapter.stop()).reason)

        val failure = Fixture()
        failure.adapter.start(config())
        failure.port.startCompletion!!.fail()
        assertEquals(StreamLifecycleState.FAILED, failure.store.snapshot().state)

        val timeout = Fixture()
        timeout.adapter.start(config())
        timeout.scheduler.fire()
        assertEquals(StreamLifecycleState.FAILED, timeout.store.snapshot().state)

        val cancelled = Fixture()
        val accepted = assertIs<DjiStreamStartResult.Accepted>(cancelled.adapter.start(config()))
        accepted.cancellation.cancel()
        assertEquals(StreamLifecycleState.FAILED, cancelled.store.snapshot().state)
        cancelled.port.startCompletion!!.succeed()
        assertEquals(StreamLifecycleState.FAILED, cancelled.store.snapshot().state)

        val rejected = Fixture(timeoutMillis = 999)
        assertEquals(DjiStreamRejection.OPERATION_REJECTED, assertIs<DjiStreamStartResult.Rejected>(rejected.adapter.start(config())).reason)
        assertEquals(StreamLifecycleState.FAILED, rejected.store.snapshot().state)
    }

    @Test
    fun convertsDjiAdapterExceptionsToFailedState() {
        val fixture = Fixture()
        fixture.port.throwOnStart = true
        assertIs<DjiStreamStartResult.Accepted>(fixture.adapter.start(config()))
        assertEquals(StreamLifecycleState.FAILED, fixture.store.snapshot().state)
    }

    private class Fixture(val timeoutMillis: Long = 30_000) {
        val store = StreamStateStore.create()
        val port = Port()
        val scheduler = Scheduler()
        private val coordinator = DjiOperationCoordinator.create(
            executor = OperationExecutor { it() },
            scheduler = scheduler,
        )
        val adapter = DjiStreamAdapter.create(store, port, coordinator, timeoutMillis)
    }

    private class Port : DjiStreamPort {
        var metrics: ((StreamMetrics) -> Unit)? = null
        var startCompletion: StreamDjiCompletion? = null
        var stopCompletion: StreamDjiCompletion? = null
        var throwOnStart = false
        override fun start(config: ValidatedStreamConfig, metrics: (StreamMetrics) -> Unit, completion: StreamDjiCompletion) {
            if (throwOnStart) error("dji failure")
            this.metrics = metrics
            this.startCompletion = completion
        }
        override fun stop(completion: StreamDjiCompletion) { stopCompletion = completion }
    }

    private class Scheduler : OperationScheduler {
        var callback: (() -> Unit)? = null
        override fun schedule(delayMillis: Long, callback: () -> Unit): OperationCancellation {
            this.callback = callback
            return OperationCancellation { }
        }
        fun fire() { callback?.invoke() }
    }

    private fun config() = ValidatedStreamConfig("rtmp://computer/live/device")
}
