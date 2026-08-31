package com.skycommand.relay.wayline.phase

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class MissionExecutionSignal {
    PREPARING,
    ENTER_WAYLINE,
    EXECUTING,
    PAUSED,
    COMPLETED,
    INTERRUPTED,
    IDLE,
    DISCONNECTED,
    UNKNOWN,
}

fun interface MissionExecutionSignalListener {
    fun onSignal(signal: MissionExecutionSignal)
}

fun interface MissionExecutionSignalRegistration {
    fun unregister()
}

interface MissionExecutionSignalSource {
    fun onSignal(listener: MissionExecutionSignalListener): MissionExecutionSignalRegistration

    /**
     * Identity-free platform sources close this fence before a new start attempt so a delayed
     * status callback from a prior task cannot be attributed to the task now being prepared.
     */
    fun beginStartAttempt()

    /** Enables signals only after the current start command has an explicit DJI success receipt. */
    fun confirmStartAttempt()

    /** Closes the fence when the pending start is no longer a current task. */
    fun invalidateStartAttempt()
}

enum class MissionPhase {
    START_POINT_REACHED,
    ROUTE_EXECUTION_STARTED,
}

data class MissionPhaseFact(
    val missionRevision: Long,
    val deviceGeneration: Long,
    val sequence: Long,
    val phase: MissionPhase,
    val fileName: String,
)

fun interface MissionPhaseSink {
    fun publish(fact: MissionPhaseFact)
}

fun interface MissionPhaseDiagnosticSink {
    fun record(diagnostic: MissionPhaseDiagnostic)
}

data class MissionPhaseDiagnostic(
    val kind: MissionPhaseDiagnosticKind,
)

enum class MissionPhaseDiagnosticKind {
    ENTRY_STATE_MISSING,
    PHASE_SINK_FAILURE,
}

sealed interface MissionSignalAcceptance {
    data object Accepted : MissionSignalAcceptance
    data object Deferred : MissionSignalAcceptance
    data object IgnoredBeforeExecution : MissionSignalAcceptance
    data object IgnoredStale : MissionSignalAcceptance
}

class MissionFlightPhase private constructor(
    private val sink: MissionPhaseSink,
    private val diagnosticSink: MissionPhaseDiagnosticSink,
) {
    private val lock = ReentrantLock()
    private var active: ActiveMission? = null

    fun prepareStart(missionRevision: Long, deviceGeneration: Long, fileName: String) {
        require(missionRevision > 0) { "Mission revision must be positive" }
        require(deviceGeneration >= 0) { "Device generation must not be negative" }
        require(isSafeMissionFileName(fileName)) { "Mission file name is invalid" }
        lock.withLock {
            active = ActiveMission(missionRevision, deviceGeneration, fileName)
        }
    }

    /** Signals received during startMission are held until DJI acknowledges this exact request. */
    fun confirmStart(missionRevision: Long, deviceGeneration: Long): List<MissionExecutionSignal> {
        val confirmed = lock.withLock {
            val current = active
            if (current == null || current.missionRevision != missionRevision || current.deviceGeneration != deviceGeneration) {
                null
            } else {
                current.confirmStart()
            }
        } ?: return emptyList()
        confirmed.deliveries.forEach { delivery ->
            delivery.diagnostics.forEach(::recordDiagnostic)
            delivery.facts.forEach(::publishFact)
        }
        return confirmed.signals
    }

    fun accept(
        signal: MissionExecutionSignal,
        missionRevision: Long,
        deviceGeneration: Long,
    ): MissionSignalAcceptance {
        val delivery = lock.withLock {
            val current = active
            if (current == null || current.missionRevision != missionRevision || current.deviceGeneration != deviceGeneration) {
                return MissionSignalAcceptance.IgnoredStale
            }
            current.accept(signal)
        }
        return when (delivery) {
            SignalDelivery.Deferred -> MissionSignalAcceptance.Deferred
            SignalDelivery.IgnoredBeforeExecution -> MissionSignalAcceptance.IgnoredBeforeExecution
            is SignalDelivery.Published -> {
                delivery.delivery.diagnostics.forEach(::recordDiagnostic)
                delivery.delivery.facts.forEach(::publishFact)
                MissionSignalAcceptance.Accepted
            }
        }
    }

    fun invalidate(missionRevision: Long?, deviceGeneration: Long) {
        lock.withLock {
            val current = active ?: return
            if (current.missionRevision == missionRevision && current.deviceGeneration == deviceGeneration) {
                active = null
            }
        }
    }

    private fun publishFact(fact: MissionPhaseFact) {
        try {
            sink.publish(fact)
        } catch (_: Throwable) {
            recordDiagnostic(MissionPhaseDiagnostic(MissionPhaseDiagnosticKind.PHASE_SINK_FAILURE))
        }
    }

    private fun recordDiagnostic(diagnostic: MissionPhaseDiagnostic) {
        runCatching { diagnosticSink.record(diagnostic) }
    }

    private class ActiveMission(
        val missionRevision: Long,
        val deviceGeneration: Long,
        val fileName: String,
        private var nextSequence: Long = 1,
        private var startPointReached: Boolean = false,
        private var routeExecutionStarted: Boolean = false,
        private var startConfirmed: Boolean = false,
        private val deferredSignals: MutableList<MissionExecutionSignal> = mutableListOf(),
    ) {
        fun accept(signal: MissionExecutionSignal): SignalDelivery {
            if (!startConfirmed) {
                if (signal == MissionExecutionSignal.ENTER_WAYLINE || signal == MissionExecutionSignal.EXECUTING) {
                    deferredSignals += signal
                }
                return SignalDelivery.Deferred
            }
            if (!routeExecutionStarted && signal.requiresExecutionProof()) {
                return SignalDelivery.IgnoredBeforeExecution
            }
            return SignalDelivery.Published(acceptConfirmed(signal))
        }

        fun confirmStart(): ConfirmedStart {
            startConfirmed = true
            val signals = deferredSignals.toList()
            deferredSignals.clear()
            return ConfirmedStart(signals, signals.map(::acceptConfirmed))
        }

        private fun acceptConfirmed(signal: MissionExecutionSignal): Delivery = when (signal) {
            MissionExecutionSignal.ENTER_WAYLINE -> enterWayline()
            MissionExecutionSignal.EXECUTING -> executionObserved()
            else -> Delivery.EMPTY
        }

        private fun enterWayline(): Delivery {
            if (startPointReached) return Delivery.EMPTY
            startPointReached = true
            return Delivery(facts = listOf(next(MissionPhase.START_POINT_REACHED)))
        }

        private fun executionObserved(): Delivery {
            if (routeExecutionStarted) return Delivery.EMPTY
            routeExecutionStarted = true
            return Delivery(
                facts = listOf(next(MissionPhase.ROUTE_EXECUTION_STARTED)),
                diagnostics = if (startPointReached) {
                    emptyList()
                } else {
                    listOf(MissionPhaseDiagnostic(MissionPhaseDiagnosticKind.ENTRY_STATE_MISSING))
                },
            )
        }

        private fun next(phase: MissionPhase): MissionPhaseFact = MissionPhaseFact(
            missionRevision = missionRevision,
            deviceGeneration = deviceGeneration,
            sequence = nextSequence++,
            phase = phase,
            fileName = fileName,
        )
    }

    private sealed interface SignalDelivery {
        data object Deferred : SignalDelivery
        data object IgnoredBeforeExecution : SignalDelivery
        data class Published(val delivery: Delivery) : SignalDelivery
    }

    private data class ConfirmedStart(
        val signals: List<MissionExecutionSignal>,
        val deliveries: List<Delivery>,
    )

    private data class Delivery(
        val facts: List<MissionPhaseFact> = emptyList(),
        val diagnostics: List<MissionPhaseDiagnostic> = emptyList(),
    ) {
        companion object {
            val EMPTY = Delivery()
        }
    }

    companion object {
        private const val MAX_RELAY_FILE_NAME_CODE_POINTS = 128

        fun create(
            sink: MissionPhaseSink,
            diagnosticSink: MissionPhaseDiagnosticSink = MissionPhaseDiagnosticSink { },
        ): MissionFlightPhase = MissionFlightPhase(sink, diagnosticSink)

        private fun isSafeMissionFileName(fileName: String): Boolean =
            fileName.isNotBlank() &&
                fileName.endsWith(".kmz", ignoreCase = true) &&
                fileName.codePointCount(0, fileName.length) <= MAX_RELAY_FILE_NAME_CODE_POINTS &&
                fileName.none { it == '/' || it == '\\' || it.isISOControl() }
    }
}

private fun MissionExecutionSignal.requiresExecutionProof(): Boolean = when (this) {
    MissionExecutionSignal.PAUSED,
    MissionExecutionSignal.COMPLETED,
    MissionExecutionSignal.INTERRUPTED,
    MissionExecutionSignal.IDLE,
    MissionExecutionSignal.DISCONNECTED,
    -> true
    else -> false
}
