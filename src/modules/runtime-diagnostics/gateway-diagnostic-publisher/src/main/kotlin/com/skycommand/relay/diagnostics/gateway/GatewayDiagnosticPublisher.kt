package com.skycommand.relay.diagnostics.gateway

import com.skycommand.relay.diagnostics.DiagnosticEvent
import com.skycommand.relay.diagnostics.DiagnosticJournal
import com.skycommand.relay.diagnostics.AcknowledgementResult
import com.skycommand.relay.gateway.outbound.PublishResult
import com.skycommand.relay.gateway.RelayGateway
import com.skycommand.relay.gateway.session.SessionState
import com.skycommand.relay.protocol.DiagnosticAcknowledgementFrame
import com.skycommand.relay.protocol.DiagnosticEventFrame
import com.skycommand.relay.protocol.DiagnosticReportFrame
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

fun interface DiagnosticRegistration {
    fun unregister()
}

fun interface DiagnosticTimeoutScheduler {
    fun schedule(delayMillis: Long, callback: () -> Unit): DiagnosticRegistration
}

interface DiagnosticGatewayPort {
    fun currentState(): SessionState
    fun publish(report: DiagnosticReportFrame): PublishResult
    fun onStateChanged(listener: (SessionState) -> Unit): DiagnosticRegistration
    fun onAcknowledged(handler: (DiagnosticAcknowledgementFrame) -> Unit): DiagnosticRegistration
}

class RelayGatewayDiagnosticPort(
    private val gateway: RelayGateway,
) : DiagnosticGatewayPort {
    override fun currentState(): SessionState = gateway.connectionState()

    override fun publish(report: DiagnosticReportFrame): PublishResult = gateway.publishDiagnosticReport(report)

    override fun onStateChanged(listener: (SessionState) -> Unit): DiagnosticRegistration {
        val registration = gateway.onStateChanged { listener(it.snapshot.state) }
        return DiagnosticRegistration { registration.unregister() }
    }

    override fun onAcknowledged(handler: (DiagnosticAcknowledgementFrame) -> Unit): DiagnosticRegistration {
        val registration = gateway.registerDiagnosticAcknowledgementHandler { handler(it) }
        return DiagnosticRegistration { registration.unregister() }
    }
}

sealed interface FlushResult {
    data object Sent : FlushResult
    data object NothingPending : FlushResult
    data object NotActive : FlushResult
    data object Rejected : FlushResult
}

class GatewayDiagnosticPublisher private constructor(
    private val journal: DiagnosticJournal,
    private val gateway: DiagnosticGatewayPort,
    private val scheduler: DiagnosticTimeoutScheduler,
) {
    private val lock = ReentrantLock()
    private var stateRegistration: DiagnosticRegistration? = null
    private var acknowledgementRegistration: DiagnosticRegistration? = null
    private var recordRegistration: (() -> Unit)? = null
    private var timeoutRegistration: DiagnosticRegistration? = null
    private var lastSentSequence = 0L
    private val inFlightEnds = ArrayDeque<Long>()
    private var timeoutDelayMs = ACK_TIMEOUT_MS
    private var sendWindowVersion = 0L
    private var flushing = false

    fun start() {
        lock.withLock {
            if (stateRegistration != null) return
            stateRegistration = gateway.onStateChanged { state ->
                if (state == SessionState.ACTIVE) {
                    flush()
                } else {
                    lock.withLock { resetSendWindow() }
                }
            }
            acknowledgementRegistration = gateway.onAcknowledged { frame ->
                val result = journal.acknowledge(frame.runId, frame.acknowledgedSequence)
                if (result is AcknowledgementResult.Applied) {
                    lock.withLock {
                        while (inFlightEnds.isNotEmpty() && inFlightEnds.first() <= frame.acknowledgedSequence) {
                            inFlightEnds.removeFirst()
                        }
                        timeoutDelayMs = ACK_TIMEOUT_MS
                        armTimeoutLocked()
                    }
                    flush()
                }
            }
            recordRegistration = journal.onRecorded { flush() }
        }
        if (gateway.currentState() == SessionState.ACTIVE) flush()
    }

    fun stop() {
        val registrations = lock.withLock {
            val current = listOfNotNull(stateRegistration, acknowledgementRegistration, timeoutRegistration)
            val recorded = recordRegistration
            stateRegistration = null
            acknowledgementRegistration = null
            timeoutRegistration = null
            recordRegistration = null
            resetSendWindow()
            current to recorded
        }
        registrations.first.forEach { runCatching { it.unregister() } }
        registrations.second?.let { runCatching { it() } }
    }

    fun flush(): FlushResult {
        if (gateway.currentState() != SessionState.ACTIVE) return FlushResult.NotActive
        val canFlush = lock.withLock {
            if (flushing) false
            else {
                flushing = true
                true
            }
        }
        if (!canFlush) return FlushResult.NothingPending

        var sent = false
        var result: FlushResult = FlushResult.NothingPending
        var flushAgain = false
        try {
            while (gateway.currentState() == SessionState.ACTIVE) {
                val batch = lock.withLock {
                    val events = journal.pendingAfter(lastSentSequence, DiagnosticJournal.MAX_BATCH)
                    if (events.isEmpty()) null else PendingBatch(sendWindowVersion, events)
                } ?: break

                if (gateway.publish(toReport(batch.events)) != PublishResult.Delivered) {
                    result = if (sent) FlushResult.Sent else FlushResult.Rejected
                    break
                }
                val applied = lock.withLock {
                    if (batch.windowVersion != sendWindowVersion) {
                        false
                    } else {
                        lastSentSequence = batch.events.last().sequence
                        inFlightEnds.addLast(lastSentSequence)
                        true
                    }
                }
                if (!applied) {
                    result = FlushResult.NotActive
                    break
                }
                sent = true
                result = FlushResult.Sent
            }
            if (gateway.currentState() != SessionState.ACTIVE && !sent) {
                result = FlushResult.NotActive
            }
        } finally {
            flushAgain = lock.withLock {
                flushing = false
                if (sent) armTimeoutLocked()
                result == FlushResult.NothingPending &&
                    journal.pendingAfter(lastSentSequence, DiagnosticJournal.MAX_BATCH).isNotEmpty()
            }
        }
        return if (flushAgain) flush() else result
    }

    private fun resetSendWindow() {
        sendWindowVersion += 1
        lastSentSequence = 0L
        inFlightEnds.clear()
        timeoutDelayMs = ACK_TIMEOUT_MS
        timeoutRegistration?.unregister()
        timeoutRegistration = null
    }

    private fun armTimeoutLocked() {
        timeoutRegistration?.unregister()
        timeoutRegistration = null
        if (inFlightEnds.isEmpty()) {
            timeoutDelayMs = ACK_TIMEOUT_MS
            return
        }
        val delay = timeoutDelayMs
        timeoutRegistration = scheduler.schedule(delay) {
            resendOldest()
        }
    }

    private fun resendOldest() {
        val events = lock.withLock {
            timeoutRegistration = null
            if (inFlightEnds.isEmpty()) emptyList()
            else journal.pending(DiagnosticJournal.MAX_BATCH)
        }
        if (events.isEmpty()) return
        if (gateway.currentState() != SessionState.ACTIVE) return
        gateway.publish(toReport(events))
        lock.withLock {
            if (inFlightEnds.isEmpty()) return
            timeoutDelayMs = (timeoutDelayMs * 2).coerceAtMost(ACK_TIMEOUT_MAX_MS)
            armTimeoutLocked()
        }
    }

    private data class PendingBatch(
        val windowVersion: Long,
        val events: List<DiagnosticEvent>,
    )

    private fun toReport(events: List<DiagnosticEvent>): DiagnosticReportFrame =
        DiagnosticReportFrame(
            events.first().runId,
            events.map {
                DiagnosticEventFrame(
                    sequence = it.sequence,
                    timestampMillis = it.timestampMillis,
                    level = it.level.name,
                    module = it.module,
                    eventCode = it.eventCode,
                    operationId = it.operationId,
                    safeDetail = it.safeDetail,
                )
            },
        )

    companion object {
        const val ACK_TIMEOUT_MS = 3_000L
        const val ACK_TIMEOUT_MAX_MS = 15_000L
        val NO_TIMEOUT = DiagnosticTimeoutScheduler { _, _ -> DiagnosticRegistration { } }

        fun create(
            journal: DiagnosticJournal,
            gateway: DiagnosticGatewayPort,
            scheduler: DiagnosticTimeoutScheduler = NO_TIMEOUT,
        ): GatewayDiagnosticPublisher = GatewayDiagnosticPublisher(journal, gateway, scheduler)
    }
}
