package com.skycommand.relay.diagnostics

import java.util.Collections
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class DiagnosticLevel { DEBUG, INFO, WARN, ERROR }

data class DiagnosticEvent(
    val timestampMillis: Long,
    val level: DiagnosticLevel,
    val module: String,
    val eventCode: String,
    val runId: String,
    val sequence: Long,
    val operationId: String?,
    val safeDetail: String,
)

data class DiagnosticJournalSnapshot(
    val pendingEvents: Int,
    val droppedEvents: Long,
    val persistenceFailures: Long,
    val lastAcknowledgedSequence: Long,
)

sealed interface AcknowledgementResult {
    data class Applied(val removedEvents: Int, val sequence: Long) : AcknowledgementResult
    data object IgnoredRun : AcknowledgementResult
    data object IgnoredStale : AcknowledgementResult
}

fun interface DiagnosticClock {
    fun currentTimeMillis(): Long
}

fun interface DiagnosticPersistence {
    fun persist(events: List<DiagnosticEvent>)
}

class DiagnosticJournal private constructor(
    private val runId: String,
    private val capacity: Int,
    private val clock: DiagnosticClock,
    private val persistence: DiagnosticPersistence,
    recoveredEvents: List<DiagnosticEvent>,
) {
    private val lock = ReentrantLock()
    private val events = ArrayDeque<DiagnosticEvent>()
    private var nextSequence = 1L
    private var droppedEvents = 0L
    private var persistenceFailures = 0L
    private val lastAcknowledgedSequences = mutableMapOf<String, Long>()

    init {
        val retained = recoveredEvents.takeLast(capacity)
        events.addAll(retained)
        droppedEvents = (recoveredEvents.size - retained.size).toLong()
        nextSequence = (retained.filter { it.runId == runId }.maxOfOrNull { it.sequence } ?: 0L) + 1L
    }

    fun record(
        level: DiagnosticLevel,
        module: String,
        eventCode: String,
        operationId: String?,
        detail: String,
    ): DiagnosticEvent = lock.withLock {
        require(validIdentifier(module)) { "Diagnostic module is invalid" }
        require(validIdentifier(eventCode)) { "Diagnostic event code is invalid" }
        require(operationId == null || validId(operationId)) { "Diagnostic operation ID is invalid" }

        val dropped = if (events.size >= capacity) {
            events.removeFirst()
            droppedEvents += 1
            " dropped=$droppedEvents"
        } else {
            ""
        }
        val event = DiagnosticEvent(
            timestampMillis = clock.currentTimeMillis().coerceAtLeast(0),
            level = level,
            module = module,
            eventCode = eventCode,
            runId = runId,
            sequence = nextSequence++,
            operationId = operationId,
            safeDetail = sanitize(detail).take((MAX_DETAIL - dropped.length).coerceAtLeast(0)) + dropped,
        )
        events.addLast(event)
        persistSafely()
        event
    }

    fun pending(maxEvents: Int): List<DiagnosticEvent> = lock.withLock {
        require(maxEvents in 1..MAX_BATCH)
        val oldestRun = events.firstOrNull()?.runId ?: return emptyList()
        events.takeWhile { it.runId == oldestRun }.take(maxEvents)
    }

    fun acknowledge(ackRunId: String, acknowledgedSequence: Long): AcknowledgementResult = lock.withLock {
        if (events.none { it.runId == ackRunId } && ackRunId != runId) return AcknowledgementResult.IgnoredRun
        val lastAcknowledged = lastAcknowledgedSequences[ackRunId] ?: 0L
        if (acknowledgedSequence <= lastAcknowledged) return AcknowledgementResult.IgnoredStale
        if (acknowledgedSequence < 0) return AcknowledgementResult.IgnoredStale
        lastAcknowledgedSequences[ackRunId] = acknowledgedSequence
        val before = events.size
        val retained = events.filterNot { it.runId == ackRunId && it.sequence <= acknowledgedSequence }
        events.clear()
        events.addAll(retained)
        val removed = before - events.size
        persistSafely()
        AcknowledgementResult.Applied(removed, acknowledgedSequence)
    }

    fun snapshot(): DiagnosticJournalSnapshot = lock.withLock {
        DiagnosticJournalSnapshot(events.size, droppedEvents, persistenceFailures, lastAcknowledgedSequences[runId] ?: 0L)
    }

    private fun sanitize(value: String): String {
        var result = value.replace(Regex("(?i)(authorization|api[-_]?key|token|secret|password)\\s*[:=]\\s*[^\\s,;]+"), "[REDACTED]")
        result = result.replace(Regex("(?i)https?://[^\\s]+"), "[REDACTED_URL]")
        result = result.replace(Regex("(?:[A-Za-z]:\\\\|/)(?:[^\\s/\\\\]+[\\\\/])+(?:[^\\s/\\\\]+)?"), "[REDACTED_PATH]")
        return result.filterNot { it.isISOControl() }
    }

    private fun persistSafely() {
        runCatching { persistence.persist(Collections.unmodifiableList(events.toList())) }
            .onFailure { persistenceFailures += 1 }
    }

    companion object {
        const val MAX_BATCH = 32
        const val MAX_DETAIL = 512

        fun create(
            runId: String,
            capacity: Int,
            clock: DiagnosticClock,
            persistence: DiagnosticPersistence = DiagnosticPersistence { },
            recoveredEvents: List<DiagnosticEvent> = emptyList(),
        ): DiagnosticJournal {
            require(validId(runId)) { "Diagnostic run ID is invalid" }
            require(capacity > 0) { "Diagnostic capacity must be positive" }
            require(recoveredEvents.all { validId(it.runId) && it.sequence > 0 && it.timestampMillis >= 0 }) {
                "Recovered diagnostic events are invalid"
            }
            return DiagnosticJournal(runId, capacity, clock, persistence, recoveredEvents)
        }

        private fun validId(value: String): Boolean =
            value.isNotBlank() && value.codePointCount(0, value.length) <= 128 && value.none(Char::isISOControl)

        private fun validIdentifier(value: String): Boolean =
            value.length in 1..64 && value.firstOrNull()?.let { it in 'A'..'Z' || it in 'a'..'z' } == true &&
                value.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    }
}
