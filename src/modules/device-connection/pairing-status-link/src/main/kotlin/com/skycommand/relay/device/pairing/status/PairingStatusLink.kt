package com.skycommand.relay.device.pairing.status

import com.skycommand.relay.device.state.DeviceStatePatch
import com.skycommand.relay.device.state.DeviceStateStore
import com.skycommand.relay.device.state.PairingState
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class PairingStatusSignal(
    val sourceRevision: Long,
    val state: PairingState,
)

fun interface PairingStatusListener {
    fun onChanged(signal: PairingStatusSignal)
}

fun interface PairingStatusSubscription {
    fun cancel()
}

interface PairingStatusPort {
    fun start(listener: PairingStatusListener): PairingStatusSubscription

    fun stop()
}

sealed interface PairingStatusStartResult {
    data object Started : PairingStatusStartResult

    data object AlreadyStarted : PairingStatusStartResult

    data class Rejected(val safeReason: String) : PairingStatusStartResult
}

sealed interface PairingStatusStopResult {
    data object Stopped : PairingStatusStopResult

    data object AlreadyStopped : PairingStatusStopResult
}

fun interface PairingStatusDiagnosticSink {
    fun record(diagnostic: PairingStatusDiagnostic)
}

data class PairingStatusDiagnostic(
    val kind: PairingStatusDiagnosticKind,
)

enum class PairingStatusDiagnosticKind {
    PORT_FAILURE,
    INVALID_SIGNAL,
}

class PairingStatusLink private constructor(
    private val store: DeviceStateStore,
    private val port: PairingStatusPort,
    private val diagnosticSink: PairingStatusDiagnosticSink,
) {
    private val lock = ReentrantLock()
    private var runToken = 0L
    private var started = false
    private var acceptingSignals = false
    private var subscription: PairingStatusSubscription? = null
    private val pendingSignals = mutableListOf<PairingStatusSignal>()

    fun start(): PairingStatusStartResult {
        val token = lock.withLock {
            if (started) return PairingStatusStartResult.AlreadyStarted
            runToken += 1
            started = true
            acceptingSignals = false
            pendingSignals.clear()
            runToken
        }
        val registered = try {
            port.start(PairingStatusListener { signal -> handleSignal(token, signal) })
        } catch (_: Throwable) {
            lock.withLock {
                if (runToken == token) {
                    started = false
                    acceptingSignals = false
                    pendingSignals.clear()
                    runToken += 1
                }
            }
            record(PairingStatusDiagnosticKind.PORT_FAILURE)
            return PairingStatusStartResult.Rejected("pairing status listener unavailable")
        }
        val pending = lock.withLock {
            if (started && runToken == token) {
                subscription = registered
                acceptingSignals = true
                pendingSignals.toList().also { pendingSignals.clear() }
            } else {
                null
            }
        }
        if (pending == null) {
            runCatching { registered.cancel() }
                .onFailure { record(PairingStatusDiagnosticKind.PORT_FAILURE) }
        } else {
            pending.forEach(::applySignal)
        }
        return PairingStatusStartResult.Started
    }

    fun stop(): PairingStatusStopResult {
        val currentSubscription = lock.withLock {
            if (!started) return PairingStatusStopResult.AlreadyStopped
            started = false
            acceptingSignals = false
            runToken += 1
            pendingSignals.clear()
            subscription.also { subscription = null }
        }
        runCatching { currentSubscription?.cancel() }
            .onFailure { record(PairingStatusDiagnosticKind.PORT_FAILURE) }
        runCatching { port.stop() }
            .onFailure { record(PairingStatusDiagnosticKind.PORT_FAILURE) }
        return PairingStatusStopResult.Stopped
    }

    private fun handleSignal(token: Long, signal: PairingStatusSignal) {
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

    private fun applySignal(signal: PairingStatusSignal) {
        runCatching {
            store.apply(DeviceStatePatch.pairing(signal.sourceRevision, signal.state))
        }.onFailure {
            record(PairingStatusDiagnosticKind.INVALID_SIGNAL)
        }
    }

    private fun record(kind: PairingStatusDiagnosticKind) {
        runCatching { diagnosticSink.record(PairingStatusDiagnostic(kind)) }
    }

    companion object {
        fun create(
            store: DeviceStateStore,
            port: PairingStatusPort,
            diagnosticSink: PairingStatusDiagnosticSink = PairingStatusDiagnosticSink { },
        ): PairingStatusLink = PairingStatusLink(store, port, diagnosticSink)
    }
}
