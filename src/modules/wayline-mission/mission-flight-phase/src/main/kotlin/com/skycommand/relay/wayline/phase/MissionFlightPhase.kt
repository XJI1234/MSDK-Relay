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
    data object IgnoredStale : MissionSignalAcceptance
}

class MissionFlightPhase private constructor(
    private val sink: MissionPhaseSink,
    private val diagnosticSink: MissionPhaseDiagnosticSink,
) {
    private val lock = ReentrantLock()
    private var active: ActiveMission? = null

    fun arm(missionRevision: Long, deviceGeneration: Long, fileName: String) {
        require(missionRevision > 0) { "Mission revision must be positive" }
        require(deviceGeneration >= 0) { "Device generation must not be negative" }
        require(isSafeMissionFileName(fileName)) { "Mission file name is invalid" }
        lock.withLock {
            active = ActiveMission(missionRevision, deviceGeneration, fileName)
        }
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
        delivery.diagnostics.forEach(::recordDiagnostic)
        delivery.facts.forEach(::publishFact)
        return MissionSignalAcceptance.Accepted
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
    ) {
        fun accept(signal: MissionExecutionSignal): Delivery = when (signal) {
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
