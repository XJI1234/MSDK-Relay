package com.skycommand.relay.device.remote

import com.skycommand.relay.device.state.DeviceStatePatch
import com.skycommand.relay.device.state.DeviceStateStore
import com.skycommand.relay.device.state.LinkState
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class RemoteControllerSignal(
    val sourceRevision: Long,
    val connected: Boolean,
    val displayModel: String?,
)

fun interface RemoteControllerListener {
    fun onChanged(signal: RemoteControllerSignal)
}

fun interface PortSubscription {
    fun cancel()
}

interface RemoteControllerPort {
    fun start(listener: RemoteControllerListener): PortSubscription

    fun stop()
}

sealed interface RemoteControllerStartResult {
    data object Started : RemoteControllerStartResult

    data object AlreadyStarted : RemoteControllerStartResult

    data class Rejected(val safeReason: String) : RemoteControllerStartResult
}

sealed interface RemoteControllerStopResult {
    data object Stopped : RemoteControllerStopResult

    data object AlreadyStopped : RemoteControllerStopResult
}

fun interface RemoteControllerDiagnosticSink {
    fun record(diagnostic: RemoteControllerDiagnostic)
}

data class RemoteControllerDiagnostic(
    val kind: RemoteControllerDiagnosticKind,
)

enum class RemoteControllerDiagnosticKind {
    PORT_FAILURE,
    INVALID_SIGNAL,
}

class RemoteControllerLink private constructor(
    private val store: DeviceStateStore,
    private val port: RemoteControllerPort,
    private val diagnosticSink: RemoteControllerDiagnosticSink,
) {
    private val lock = ReentrantLock()
    private var runToken = 0L
    private var started = false
    private var acceptingSignals = false
    private var subscription: PortSubscription? = null
    private val pendingSignals = mutableListOf<RemoteControllerSignal>()

    fun start(): RemoteControllerStartResult {
        val token = lock.withLock {
            if (started) return RemoteControllerStartResult.AlreadyStarted
            runToken += 1
            started = true
            acceptingSignals = false
            pendingSignals.clear()
            runToken
        }
        val registered = try {
            port.start(RemoteControllerListener { signal -> handleSignal(token, signal) })
        } catch (_: Throwable) {
            lock.withLock {
                if (runToken == token) {
                    started = false
                    acceptingSignals = false
                    pendingSignals.clear()
                    runToken += 1
                }
            }
            record(RemoteControllerDiagnosticKind.PORT_FAILURE)
            return RemoteControllerStartResult.Rejected("remote controller listener unavailable")
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
        } else {
            pending.forEach(::applySignal)
        }
        return RemoteControllerStartResult.Started
    }

    fun stop(): RemoteControllerStopResult {
        val currentSubscription = lock.withLock {
            if (!started) return RemoteControllerStopResult.AlreadyStopped
            started = false
            acceptingSignals = false
            runToken += 1
            pendingSignals.clear()
            subscription.also { subscription = null }
        }
        runCatching { currentSubscription?.cancel() }
            .onFailure { record(RemoteControllerDiagnosticKind.PORT_FAILURE) }
        runCatching { port.stop() }
            .onFailure { record(RemoteControllerDiagnosticKind.PORT_FAILURE) }
        return RemoteControllerStopResult.Stopped
    }

    private fun handleSignal(token: Long, signal: RemoteControllerSignal) {
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

    private fun applySignal(signal: RemoteControllerSignal) {
        runCatching {
            store.apply(
                DeviceStatePatch.remoteController(
                    sourceRevision = signal.sourceRevision,
                    link = if (signal.connected) LinkState.CONNECTED else LinkState.DISCONNECTED,
                    model = signal.displayModel,
                ),
            )
        }.onFailure {
            record(RemoteControllerDiagnosticKind.INVALID_SIGNAL)
        }
    }

    private fun record(kind: RemoteControllerDiagnosticKind) {
        runCatching { diagnosticSink.record(RemoteControllerDiagnostic(kind)) }
    }

    companion object {
        fun create(
            store: DeviceStateStore,
            port: RemoteControllerPort,
            diagnosticSink: RemoteControllerDiagnosticSink = RemoteControllerDiagnosticSink { },
        ): RemoteControllerLink = RemoteControllerLink(store, port, diagnosticSink)
    }
}
