package com.skycommand.relay.device.aircraft

import com.skycommand.relay.device.state.DeviceStatePatch
import com.skycommand.relay.device.state.DeviceStateStore
import com.skycommand.relay.device.state.LinkState
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class AircraftSignal(
    val sourceRevision: Long,
    val aircraftConnected: Boolean?,
    val flightControllerConnected: Boolean?,
    val displayModel: String?,
)

fun interface AircraftListener {
    fun onChanged(signal: AircraftSignal)
}

fun interface AircraftPortSubscription {
    fun cancel()
}

interface AircraftPort {
    fun start(listener: AircraftListener): AircraftPortSubscription

    fun stop()
}

sealed interface AircraftStartResult {
    data object Started : AircraftStartResult

    data object AlreadyStarted : AircraftStartResult

    data class Rejected(val safeReason: String) : AircraftStartResult
}

sealed interface AircraftStopResult {
    data object Stopped : AircraftStopResult

    data object AlreadyStopped : AircraftStopResult
}

fun interface AircraftDiagnosticSink {
    fun record(diagnostic: AircraftDiagnostic)
}

data class AircraftDiagnostic(
    val kind: AircraftDiagnosticKind,
)

enum class AircraftDiagnosticKind {
    PORT_FAILURE,
    INVALID_SIGNAL,
}

class AircraftLink private constructor(
    private val store: DeviceStateStore,
    private val port: AircraftPort,
    private val diagnosticSink: AircraftDiagnosticSink,
) {
    private val lock = ReentrantLock()
    private var runToken = 0L
    private var started = false
    private var acceptingSignals = false
    private var subscription: AircraftPortSubscription? = null
    private val pendingSignals = mutableListOf<AircraftSignal>()

    fun start(): AircraftStartResult {
        val token = lock.withLock {
            if (started) return AircraftStartResult.AlreadyStarted
            started = true
            acceptingSignals = false
            pendingSignals.clear()
            runToken += 1
            runToken
        }
        val registered = try {
            port.start(AircraftListener { signal -> handleSignal(token, signal) })
        } catch (_: Throwable) {
            lock.withLock {
                if (runToken == token) {
                    started = false
                    acceptingSignals = false
                    pendingSignals.clear()
                    runToken += 1
                }
            }
            record(AircraftDiagnosticKind.PORT_FAILURE)
            return AircraftStartResult.Rejected("aircraft listener unavailable")
        }
        val pending = lock.withLock {
            if (!started || runToken != token) {
                null
            } else {
                subscription = registered
                acceptingSignals = true
                pendingSignals.toList().also { pendingSignals.clear() }
            }
        }
        if (pending == null) {
            runCatching { registered.cancel() }
                .onFailure { record(AircraftDiagnosticKind.PORT_FAILURE) }
        } else {
            pending.forEach(::applySignal)
        }
        return AircraftStartResult.Started
    }

    fun stop(): AircraftStopResult {
        val currentSubscription = lock.withLock {
            if (!started) return AircraftStopResult.AlreadyStopped
            started = false
            acceptingSignals = false
            pendingSignals.clear()
            runToken += 1
            subscription.also { subscription = null }
        }
        runCatching { currentSubscription?.cancel() }
            .onFailure { record(AircraftDiagnosticKind.PORT_FAILURE) }
        runCatching { port.stop() }
            .onFailure { record(AircraftDiagnosticKind.PORT_FAILURE) }
        return AircraftStopResult.Stopped
    }

    private fun handleSignal(token: Long, signal: AircraftSignal) {
        val shouldApply = lock.withLock {
            if (!started || runToken != token) {
                false
            } else if (!acceptingSignals) {
                pendingSignals += signal
                false
            } else {
                true
            }
        }
        if (shouldApply) applySignal(signal)
    }

    private fun applySignal(signal: AircraftSignal) {
        runCatching {
            val aircraft = signal.aircraftConnected.toLinkState()
            store.apply(
                DeviceStatePatch.aircraft(
                    sourceRevision = signal.sourceRevision,
                    aircraft = aircraft,
                    flightController = when (aircraft) {
                        LinkState.CONNECTED -> signal.flightControllerConnected.toLinkState()
                        LinkState.DISCONNECTED -> LinkState.DISCONNECTED
                        LinkState.UNKNOWN -> LinkState.UNKNOWN
                    },
                    model = signal.displayModel?.takeIf { aircraft == LinkState.CONNECTED },
                ),
            )
        }.onFailure { record(AircraftDiagnosticKind.INVALID_SIGNAL) }
    }

    private fun record(kind: AircraftDiagnosticKind) {
        runCatching { diagnosticSink.record(AircraftDiagnostic(kind)) }
    }

    private fun Boolean?.toLinkState(): LinkState = when (this) {
        true -> LinkState.CONNECTED
        false -> LinkState.DISCONNECTED
        null -> LinkState.UNKNOWN
    }

    companion object {
        fun create(
            store: DeviceStateStore,
            port: AircraftPort,
            diagnosticSink: AircraftDiagnosticSink = AircraftDiagnosticSink { },
        ): AircraftLink = AircraftLink(store, port, diagnosticSink)
    }
}
