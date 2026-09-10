package com.skycommand.relay.diagnostics.gateway

import com.skycommand.relay.diagnostics.DiagnosticClock
import com.skycommand.relay.diagnostics.DiagnosticJournal
import com.skycommand.relay.diagnostics.DiagnosticLevel
import com.skycommand.relay.gateway.outbound.PublishResult
import com.skycommand.relay.gateway.session.SessionState
import com.skycommand.relay.protocol.DiagnosticAcknowledgementFrame
import com.skycommand.relay.protocol.DiagnosticReportFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GatewayDiagnosticPublisherContractTest {

    @Test
    fun sendsOldestPendingBatchOnActiveAndDeletesOnlyAfterAcknowledgement() {
        val journal = DiagnosticJournal.create("run-1", 8, FixedClock)
        journal.record(DiagnosticLevel.ERROR, "device-connection", "SDK_FAILURE", null, "safe")
        val gateway = RecordingGateway(SessionState.RECONNECT_WAIT)
        val publisher = GatewayDiagnosticPublisher.create(journal, gateway)

        publisher.start()
        assertEquals(emptyList(), gateway.reports)
        gateway.transitionTo(SessionState.ACTIVE)

        assertEquals(listOf(1L), gateway.reports.single().events.map { it.sequence })
        assertEquals(listOf(1L), journal.pending(32).map { it.sequence })
        gateway.acknowledge("run-1", 1)

        assertEquals(emptyList(), journal.pending(32))
    }

    @Test
    fun doesNotSendDuplicateBatchBeforeAcknowledgementAndResendsAfterReconnect() {
        val journal = DiagnosticJournal.create("run-1", 8, FixedClock)
        journal.record(DiagnosticLevel.WARN, "relay-gateway", "WRITE_REJECTED", null, "safe")
        val gateway = RecordingGateway(SessionState.ACTIVE)
        val publisher = GatewayDiagnosticPublisher.create(journal, gateway)

        publisher.start()
        publisher.flush()
        assertEquals(1, gateway.reports.size)
        gateway.transitionTo(SessionState.RECONNECT_WAIT)
        gateway.transitionTo(SessionState.ACTIVE)

        assertEquals(2, gateway.reports.size)
        assertEquals(listOf(1L, 1L), gateway.reports.map { it.events.single().sequence })
    }

    @Test
    fun leavesEventsPendingWhenGatewayRejectsWrite() {
        val journal = DiagnosticJournal.create("run-1", 8, FixedClock)
        journal.record(DiagnosticLevel.INFO, "runtime-diagnostics", "STARTED", null, "safe")
        val gateway = RecordingGateway(SessionState.ACTIVE, PublishResult.Rejected(com.skycommand.relay.gateway.outbound.PublishRejectionKind.WRITE_REJECTED))
        val publisher = GatewayDiagnosticPublisher.create(journal, gateway)

        publisher.start()

        assertEquals(listOf(1L), journal.pending(32).map { it.sequence })
        assertEquals(1, gateway.reports.size)
    }

    @Test
    fun flushesNewlyRecordedEventsWhileActiveWithoutWaitingForManualFlush() {
        val journal = DiagnosticJournal.create("run-1", 8, FixedClock)
        val gateway = RecordingGateway(SessionState.ACTIVE)
        val publisher = GatewayDiagnosticPublisher.create(journal, gateway)
        publisher.start()

        journal.record(DiagnosticLevel.INFO, "runtime-diagnostics", "STARTED", null, "safe")

        assertEquals(listOf(1L), gateway.reports.single().events.map { it.sequence })
        assertEquals(listOf(1L), journal.pending(32).map { it.sequence })
    }

    @Test
    fun sendsLaterBatchesBeforeTheEarlierBatchIsAcknowledged() {
        val journal = DiagnosticJournal.create("run-1", 256, FixedClock)
        repeat(33) { journal.record(DiagnosticLevel.INFO, "runtime-diagnostics", "EVENT", null, "safe") }
        val gateway = RecordingGateway(SessionState.ACTIVE)
        val publisher = GatewayDiagnosticPublisher.create(journal, gateway)

        publisher.start()

        assertEquals(2, gateway.reports.size)
        assertEquals((1L..32L).toList(), gateway.reports[0].events.map { it.sequence })
        assertEquals(listOf(33L), gateway.reports[1].events.map { it.sequence })
        assertEquals(33, journal.snapshot().pendingEvents)
    }

    @Test
    fun capsUnacknowledgedSendsAtFourBatches() {
        val journal = DiagnosticJournal.create("run-1", 256, FixedClock)
        repeat(GatewayDiagnosticPublisher.MAX_IN_FLIGHT_BATCHES * DiagnosticJournal.MAX_BATCH + 8) {
            journal.record(DiagnosticLevel.INFO, "runtime-diagnostics", "EVENT", null, "safe")
        }
        val gateway = RecordingGateway(SessionState.ACTIVE)
        val publisher = GatewayDiagnosticPublisher.create(journal, gateway)

        publisher.start()

        assertEquals(GatewayDiagnosticPublisher.MAX_IN_FLIGHT_BATCHES, gateway.reports.size)
        assertEquals(1L, gateway.reports.first().events.first().sequence)
        assertEquals(
            (GatewayDiagnosticPublisher.MAX_IN_FLIGHT_BATCHES * DiagnosticJournal.MAX_BATCH).toLong(),
            gateway.reports.last().events.last().sequence,
        )
        gateway.acknowledge("run-1", DiagnosticJournal.MAX_BATCH.toLong())

        assertEquals(GatewayDiagnosticPublisher.MAX_IN_FLIGHT_BATCHES + 1, gateway.reports.size)
        assertEquals(
            (GatewayDiagnosticPublisher.MAX_IN_FLIGHT_BATCHES * DiagnosticJournal.MAX_BATCH + 8).toLong(),
            gateway.reports.last().events.last().sequence,
        )
    }

    @Test
    fun resendsOldestUnacknowledgedBatchWhenTheAckTimerFires() {
        val journal = DiagnosticJournal.create("run-1", 8, FixedClock)
        journal.record(DiagnosticLevel.INFO, "runtime-diagnostics", "STARTED", null, "safe")
        val gateway = RecordingGateway(SessionState.ACTIVE)
        val scheduler = ManualScheduler()
        val publisher = GatewayDiagnosticPublisher.create(journal, gateway, scheduler)
        publisher.start()
        assertEquals(1, gateway.reports.size)

        scheduler.fireNext()

        assertEquals(2, gateway.reports.size)
        assertEquals(listOf(1L, 1L), gateway.reports.map { it.events.single().sequence })
        assertEquals(listOf(1L), journal.pending(32).map { it.sequence })
    }

    private object FixedClock : DiagnosticClock { override fun currentTimeMillis(): Long = 0 }

    private class ManualScheduler : DiagnosticTimeoutScheduler {
        private val pending = ArrayDeque<() -> Unit>()
        override fun schedule(delayMillis: Long, callback: () -> Unit): DiagnosticRegistration {
            pending.addLast(callback)
            return DiagnosticRegistration { pending.remove(callback) }
        }
        fun fireNext() {
            if (pending.isNotEmpty()) pending.removeFirst().invoke()
        }
    }

    private class RecordingGateway(
        initial: SessionState,
        private val result: PublishResult = PublishResult.Delivered,
    ) : DiagnosticGatewayPort {
        private var listener: ((SessionState) -> Unit)? = null
        private var acknowledgementHandler: ((DiagnosticAcknowledgementFrame) -> Unit)? = null
        private var state = initial
        val reports = mutableListOf<DiagnosticReportFrame>()

        override fun currentState(): SessionState = state
        override fun publish(report: DiagnosticReportFrame): PublishResult {
            reports += report
            return result
        }
        override fun onStateChanged(listener: (SessionState) -> Unit): DiagnosticRegistration {
            this.listener = listener
            return DiagnosticRegistration { this.listener = null }
        }
        override fun onAcknowledged(handler: (DiagnosticAcknowledgementFrame) -> Unit): DiagnosticRegistration {
            acknowledgementHandler = handler
            return DiagnosticRegistration { acknowledgementHandler = null }
        }
        fun transitionTo(next: SessionState) { state = next; listener?.invoke(next) }
        fun acknowledge(runId: String, sequence: Long) { acknowledgementHandler?.invoke(DiagnosticAcknowledgementFrame(runId, sequence)) }
    }
}
