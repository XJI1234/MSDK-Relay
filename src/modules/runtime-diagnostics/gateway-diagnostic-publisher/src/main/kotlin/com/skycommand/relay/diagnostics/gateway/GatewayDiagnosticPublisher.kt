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
    data object WindowFull : FlushResult
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
                        flushLocked()
                        armTimeoutLocked()
                    }
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

    fun flush(): FlushResult = lock.withLock { flushLocked() }

    private fun flushLocked(): FlushResult {
        if (gateway.currentState() != SessionState.ACTIVE) return FlushResult.NotActive
        var sent = false
        while (inFlightEnds.size < MAX_IN_FLIGHT_BATCHES) {
            val events = journal.pendingAfter(lastSentSequence, DiagnosticJournal.MAX_BATCH)
            if (events.isEmpty()) break
            if (gateway.publish(toReport(events)) != PublishResult.Delivered) {
                if (!sent) return FlushResult.Rejected
                armTimeoutLocked()
                return FlushResult.Sent
            }
            lastSentSequence = events.last().sequence
            inFlightEnds.addLast(lastSentSequence)
            sent = true
        }
        armTimeoutLocked()
        return when {
            sent -> FlushResult.Sent
            inFlightEnds.size >= MAX_IN_FLIGHT_BATCHES -> FlushResult.WindowFull
            else -> FlushResult.NothingPending
        }
    }

    private fun resetSendWindow() {
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
            lock.withLock {
                timeoutRegistration = null
                if (gateway.currentState() != SessionState.ACTIVE) return@withLock
                resendOldestLocked()
                timeoutDelayMs = (timeoutDelayMs * 2).coerceAtMost(ACK_TIMEOUT_MAX_MS)
                armTimeoutLocked()
            }
        }
    }

    private fun resendOldestLocked() {
        val events = journal.pending(DiagnosticJournal.MAX_BATCH)
        if (events.isEmpty()) return
        gateway.publish(toReport(events))
    }

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
        const val MAX_IN_FLIGHT_BATCHES = 4
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
