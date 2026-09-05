package com.skycommand.relay.flight.dji

import com.skycommand.relay.device.operation.DjiOperation
import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellationHandle
import com.skycommand.relay.device.operation.OperationCompletion
import com.skycommand.relay.device.operation.OperationOutcome
import com.skycommand.relay.device.operation.OperationResultListener
import com.skycommand.relay.device.operation.SubmissionResult
import com.skycommand.relay.device.operation.UnconfirmedOutcomeAdmission
import com.skycommand.relay.flight.command.FlightAction
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

interface FlightDjiCompletion {
    fun succeed()
    fun fail(failure: FlightDjiFailure? = null)
}

data class FlightDjiFailure private constructor(
    val errorCode: String,
    val errorDescription: String,
) {
    companion object {
        fun fromDjiError(errorCode: String?, errorDescription: String?): FlightDjiFailure = FlightDjiFailure(
            normalize(errorCode, maxCodePoints = 128, fallback = "UNKNOWN_DJI_ERROR"),
            normalize(errorDescription, maxCodePoints = 512, fallback = "DJI did not provide an error description"),
        )

        private fun normalize(value: String?, maxCodePoints: Int, fallback: String): String {
            if (value == null) return fallback
            val result = StringBuilder()
            var offset = 0
            var count = 0
            while (offset < value.length && count < maxCodePoints) {
                val codePoint = value.codePointAt(offset)
                if (!Character.isISOControl(codePoint)) {
                    result.appendCodePoint(codePoint)
                    count += 1
                }
                offset += Character.charCount(codePoint)
            }
            return result.toString().trim().ifBlank { fallback }
        }
    }
}

interface DjiFlightPort {
    fun execute(action: FlightAction, completion: FlightDjiCompletion)
    fun close() = Unit
}

fun interface FlightDjiTerminalListener {
    fun onCompleted(result: FlightDjiTerminalResult)
}

enum class FlightDjiTerminalOutcome {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
}

sealed interface FlightSubmissionResult {
    data class Accepted(val cancellation: OperationCancellationHandle) : FlightSubmissionResult
    data object Rejected : FlightSubmissionResult
}

data class FlightDjiTerminalResult(
    val outcome: FlightDjiTerminalOutcome,
    val failure: FlightDjiFailure? = null,
) {
    init {
        require(outcome == FlightDjiTerminalOutcome.FAILED || failure == null) {
            "Only DJI failure callbacks may include a DJI failure summary"
        }
    }
}

/**
 * A normalized fact from the active MSDK flight-controller observation.  The two revisions are
 * local correlation data, not DJI Key values and not desktop protocol fields.
 */
data class FlightActionState(
    val sourceGeneration: Long = 0,
    val sourceRevision: Long = 0,
    val isFlying: Boolean? = null,
    val motorsOn: Boolean? = null,
    val flightMode: String? = null,
    val landingConfirmationNeeded: Boolean? = null,
) {
    init {
        require(sourceGeneration >= 0) { "Flight observation generation must not be negative" }
        require(sourceRevision >= 0) { "Flight observation revision must not be negative" }
    }
}

class DjiFlightAdapter private constructor(
    private val port: DjiFlightPort,
    private val coordinator: DjiOperationCoordinator,
    private val timeoutMillis: Long,
    private val beforeInvocation: () -> Unit,
) {
    private val stateLock = ReentrantLock()
    private var latestState = FlightActionState()
    private var unresolvedRecovery: FlightActionRecovery? = null

    fun execute(
        action: FlightAction,
        listener: FlightDjiTerminalListener = FlightDjiTerminalListener { },
    ): FlightSubmissionResult {
        val terminal = OnceTerminal(listener)
        val recovery = FlightActionRecovery(action, ::clearRecovery)
        val djiFailure = AtomicReference<FlightDjiFailure?>(null)
        val submission = coordinator.submit(
            object : DjiOperation {
                override fun unconfirmedOutcomeAdmission(): UnconfirmedOutcomeAdmission = when (action) {
                    FlightAction.TAKEOFF -> UnconfirmedOutcomeAdmission.STANDARD
                    FlightAction.LAND,
                    FlightAction.CONFIRM_LANDING,
                    FlightAction.RETURN_HOME,
                    FlightAction.STOP_TAKEOFF,
                    FlightAction.STOP_AUTO_LANDING -> UnconfirmedOutcomeAdmission.CONTAINMENT
                }

                override fun run(completion: OperationCompletion) {
                    beginRecovery(recovery, completion)
                    beforeInvocation()
                    markInvocationBoundary(recovery)
                    port.execute(action, completion.asFlightCompletion(djiFailure))
                }

                override fun onHardwareOutcomeUnconfirmed(outcome: OperationOutcome) {
                    recovery.onHardwareOutcomeUnconfirmed()
                }

                override fun onLateDjiCompletion(outcome: OperationOutcome) {
                    clearRecovery(recovery)
                }
            },
            timeoutMillis,
            OperationResultListener { outcome ->
                if (outcome == OperationOutcome.SUCCEEDED || outcome == OperationOutcome.FAILED) {
                    clearRecovery(recovery)
                }
                terminal.complete(
                    FlightDjiTerminalResult(
                        outcome.toTerminalOutcome(),
                        if (outcome == OperationOutcome.FAILED) djiFailure.getAndSet(null) else null,
                    ),
                )
            },
        )
        return when (submission) {
            is SubmissionResult.Accepted -> FlightSubmissionResult.Accepted(submission.cancellation)
            SubmissionResult.Rejected -> FlightSubmissionResult.Rejected
        }
    }

    /** Receives only normalized state facts produced by the active MSDK flight-key listener. */
    fun observeState(state: FlightActionState) {
        val recovery = stateLock.withLock {
            if (!state.isNewerThan(latestState)) return
            latestState = state
            unresolvedRecovery
        }
        recovery?.observe(state)
    }

    private fun beginRecovery(recovery: FlightActionRecovery, completion: OperationCompletion) {
        val initial = stateLock.withLock {
            unresolvedRecovery = recovery
            latestState
        }
        recovery.begin(initial, completion)
    }

    /**
     * Marks the exact point at which the adapter is about to enter the DJI port.  The
     * state lock serializes this marker with incoming observations, so an observation
     * that arrived before the invocation boundary can never be used as recovery proof.
     */
    private fun markInvocationBoundary(recovery: FlightActionRecovery) {
        stateLock.withLock {
            recovery.markInvocationStarted(latestState)
        }
    }

    private fun clearRecovery(recovery: FlightActionRecovery) {
        recovery.close()
        stateLock.withLock {
            if (unresolvedRecovery === recovery) unresolvedRecovery = null
        }
    }

    private fun OperationCompletion.asFlightCompletion(djiFailure: AtomicReference<FlightDjiFailure?>): FlightDjiCompletion = object : FlightDjiCompletion {
        override fun succeed() = this@asFlightCompletion.succeed()
        override fun fail(failure: FlightDjiFailure?) {
            djiFailure.set(failure)
            this@asFlightCompletion.fail()
        }
    }

    private fun OperationOutcome.toTerminalOutcome(): FlightDjiTerminalOutcome = when (this) {
        OperationOutcome.SUCCEEDED -> FlightDjiTerminalOutcome.SUCCEEDED
        OperationOutcome.FAILED -> FlightDjiTerminalOutcome.FAILED
        OperationOutcome.TIMED_OUT -> FlightDjiTerminalOutcome.TIMED_OUT
        OperationOutcome.CANCELLED -> FlightDjiTerminalOutcome.CANCELLED
    }

    private fun FlightActionState.isNewerThan(other: FlightActionState): Boolean =
        sourceGeneration > other.sourceGeneration ||
            (sourceGeneration == other.sourceGeneration && sourceRevision > other.sourceRevision)

    private class FlightActionRecovery(
        private val action: FlightAction,
        private val onSettled: (FlightActionRecovery) -> Unit,
    ) {
        private val lock = ReentrantLock()
        private var baseline: FlightActionState? = null
        private var completion: OperationCompletion? = null
        private var matchedState: FlightActionState? = null
        private var invocationBoundary: FlightActionState? = null
        private var hardwareOutcomeUnconfirmed = false
        private var closed = false

        fun begin(initial: FlightActionState, operationCompletion: OperationCompletion) {
            lock.withLock {
                if (closed) return
                baseline = initial
                completion = operationCompletion
            }
        }

        fun markInvocationStarted(boundary: FlightActionState) {
            lock.withLock {
                if (closed) return
                invocationBoundary = boundary
                matchedState = null
            }
        }

        fun observe(state: FlightActionState) {
            val candidate = lock.withLock {
                if (closed || !matches(state)) return
                matchedState = state
                completion.takeIf { hardwareOutcomeUnconfirmed }
            }
            candidate?.let(::tryConfirm)
        }

        fun onHardwareOutcomeUnconfirmed() {
            val candidate = lock.withLock {
                if (closed) return
                hardwareOutcomeUnconfirmed = true
                completion.takeIf { matchedState != null }
            }
            candidate?.let(::tryConfirm)
        }

        fun close() = lock.withLock {
            closed = true
            completion = null
            matchedState = null
        }

        private fun tryConfirm(operationCompletion: OperationCompletion) {
            if (!operationCompletion.confirmHardwareSettled()) return
            lock.withLock {
                if (closed || completion !== operationCompletion) return
                closed = true
                completion = null
                matchedState = null
            }
            onSettled(this)
        }

        private fun matches(state: FlightActionState): Boolean {
            val initial = baseline ?: return false
            val boundary = invocationBoundary ?: return false
            if (
                state.sourceGeneration != initial.sourceGeneration ||
                state.sourceRevision <= initial.sourceRevision ||
                state.sourceGeneration != boundary.sourceGeneration ||
                state.sourceRevision <= boundary.sourceRevision
            ) return false
            return when (action) {
                FlightAction.TAKEOFF -> state.isFlying == true || state.flightMode == "AUTO_TAKE_OFF"
                FlightAction.LAND ->
                    state.flightMode in setOf("AUTO_LANDING", "CONFIRM_LANDING") ||
                        (initial.isFlying == true && state.isFlying == false && state.motorsOn == false)
                FlightAction.CONFIRM_LANDING ->
                    initial.landingConfirmationNeeded == true &&
                        (state.flightMode == "AUTO_LANDING" || (state.isFlying == false && state.motorsOn == false))
                FlightAction.RETURN_HOME -> state.flightMode == "GO_HOME"
                FlightAction.STOP_TAKEOFF ->
                    initial.flightMode == "AUTO_TAKE_OFF" && state.flightMode != null && state.flightMode != "AUTO_TAKE_OFF"
                FlightAction.STOP_AUTO_LANDING ->
                    initial.flightMode in setOf("AUTO_LANDING", "CONFIRM_LANDING") &&
                        state.flightMode != null && state.flightMode !in setOf("AUTO_LANDING", "CONFIRM_LANDING")
            }
        }
    }

    private class OnceTerminal(private val delegate: FlightDjiTerminalListener) {
        private val completed = AtomicBoolean(false)

        fun complete(result: FlightDjiTerminalResult) {
            if (completed.compareAndSet(false, true)) runCatching { delegate.onCompleted(result) }
        }
    }

    companion object {
        fun create(
            port: DjiFlightPort,
            coordinator: DjiOperationCoordinator,
            timeoutMillis: Long = 30_000,
        ): DjiFlightAdapter = DjiFlightAdapter(port, coordinator, timeoutMillis) { }

        internal fun createForTest(
            port: DjiFlightPort,
            coordinator: DjiOperationCoordinator,
            timeoutMillis: Long = 30_000,
            beforeInvocation: () -> Unit,
        ): DjiFlightAdapter = DjiFlightAdapter(port, coordinator, timeoutMillis, beforeInvocation)
    }
}
