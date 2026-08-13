package com.skycommand.relay.diagnostics.gateway

import com.skycommand.relay.diagnostics.DiagnosticJournal
import com.skycommand.relay.diagnostics.AcknowledgementResult
import com.skycommand.relay.gateway.outbound.PublishResult
import com.skycommand.relay.gateway.RelayGateway
import com.skycommand.relay.gateway.session.SessionState
import com.skycommand.relay.protocol.DiagnosticAcknowledgementFrame
import com.skycommand.relay.protocol.DiagnosticEventFrame
import com.skycommand.relay.protocol.DiagnosticReportFrame
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

fun interface DiagnosticRegistration {
    fun unregister()
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
    data object AwaitingAcknowledgement : FlushResult
    data object Rejected : FlushResult
}

class GatewayDiagnosticPublisher private constructor(
    private val journal: DiagnosticJournal,
    private val gateway: DiagnosticGatewayPort,
) {
    private val lock = ReentrantLock()
    private var stateRegistration: DiagnosticRegistration? = null
    private var acknowledgementRegistration: DiagnosticRegistration? = null
    private var inFlightFirstSequence: Long? = null

    fun start() {
        lock.withLock {
            if (stateRegistration != null) return
            stateRegistration = gateway.onStateChanged { state ->
                if (state == SessionState.ACTIVE) {
                    flush()
                } else {
                    lock.withLock { inFlightFirstSequence = null }
                }
            }
            acknowledgementRegistration = gateway.onAcknowledged { frame ->
                val result = journal.acknowledge(frame.runId, frame.acknowledgedSequence)
                val confirmed = lock.withLock {
                    val first = inFlightFirstSequence
                    if (result is AcknowledgementResult.Applied && first != null && frame.acknowledgedSequence >= first) {
                        inFlightFirstSequence = null
                        true
                    } else {
                        false
                    }
                }
                if (confirmed) flush()
            }
        }
        if (gateway.currentState() == SessionState.ACTIVE) flush()
    }

    fun stop() {
        val registrations = lock.withLock {
            val current = listOfNotNull(stateRegistration, acknowledgementRegistration)
            stateRegistration = null
            acknowledgementRegistration = null
            inFlightFirstSequence = null
            current
        }
        registrations.forEach { runCatching { it.unregister() } }
    }

    fun flush(): FlushResult = lock.withLock {
        if (gateway.currentState() != SessionState.ACTIVE) return FlushResult.NotActive
        if (inFlightFirstSequence != null) return FlushResult.AwaitingAcknowledgement
        val events = journal.pending(DiagnosticJournal.MAX_BATCH)
        if (events.isEmpty()) return FlushResult.NothingPending
        val report = DiagnosticReportFrame(
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
        return if (gateway.publish(report) == PublishResult.Delivered) {
            inFlightFirstSequence = events.first().sequence
            FlushResult.Sent
        } else {
            FlushResult.Rejected
        }
    }

    companion object {
        fun create(journal: DiagnosticJournal, gateway: DiagnosticGatewayPort): GatewayDiagnosticPublisher =
            GatewayDiagnosticPublisher(journal, gateway)
    }
}
