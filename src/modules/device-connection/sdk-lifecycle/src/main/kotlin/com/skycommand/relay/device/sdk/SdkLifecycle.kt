package com.skycommand.relay.device.sdk

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

typealias SdkAvailability = com.skycommand.relay.device.state.SdkAvailability

fun interface DjiSdkCallbacks {
    fun onReady()

    fun onFailure() {
        // The default keeps simple adapters focused on the successful path.
    }
}

interface DjiSdkPort {
    fun initialize(callbacks: DjiSdkCallbacks): PortStartResult

    fun close()
}

sealed interface PortStartResult {
    data object Accepted : PortStartResult

    data class Rejected(val safeReason: String) : PortStartResult
}

sealed interface StartResult {
    data object StartAccepted : StartResult

    data class AlreadyRunning(val state: SdkAvailability) : StartResult

    data class StartRejected(val safeReason: String) : StartResult
}

sealed interface StopResult {
    data object Stopped : StopResult

    data object AlreadyStopped : StopResult
}

fun interface SdkStateListener {
    fun onChanged(state: SdkAvailability)
}

fun interface Registration {
    fun unregister()
}

fun interface SdkStartupTimeout {
    fun cancel()
}

fun interface SdkStartupTimeoutScheduler {
    fun schedule(delayMillis: Long, callback: () -> Unit): SdkStartupTimeout
}

fun interface SdkLifecycleDiagnosticSink {
    fun record(diagnostic: SdkLifecycleDiagnostic)
}

data class SdkLifecycleDiagnostic(
    val kind: SdkLifecycleDiagnosticKind,
)

enum class SdkLifecycleDiagnosticKind {
    PORT_FAILURE,
    START_TIMEOUT,
    LISTENER_FAILURE,
    STALE_CALLBACK,
}

class SdkLifecycle private constructor(
    private val port: DjiSdkPort,
    private val diagnosticSink: SdkLifecycleDiagnosticSink,
    private val startupTimeoutScheduler: SdkStartupTimeoutScheduler,
) {
    private val lock = ReentrantLock()
    private val listeners = mutableListOf<ListenerSlot>()
    private var state = SdkAvailability.STOPPED
    private var runToken = 0L
    private var closeRequired = false
    private var startupTimeout: PendingStartupTimeout? = null

    fun start(): StartResult {
            val token = lock.withLock {
            if (state != SdkAvailability.STOPPED) {
                return StartResult.AlreadyRunning(state)
            }
            runToken += 1
            state = SdkAvailability.STARTING
            closeRequired = true
            runToken
        }
        notifyState(SdkAvailability.STARTING)

        if (!armStartupTimeout(token)) {
            if (transitionToFailed(token) != null) notifyState(SdkAvailability.FAILED)
            return StartResult.StartRejected("SDK initialization unavailable")
        }

        val result = runCatching {
            port.initialize(object : DjiSdkCallbacks {
                override fun onReady() {
                    handleReady(token)
                }

                override fun onFailure() {
                    handleFailure(token)
                }
            })
        }.getOrElse {
            record(SdkLifecycleDiagnosticKind.PORT_FAILURE)
            PortStartResult.Rejected("SDK initialization failed")
        }

        if (result is PortStartResult.Rejected) {
            transitionToFailed(token)?.let { transition ->
                transition.timeout?.cancel()
                notifyState(SdkAvailability.FAILED)
            }
            return StartResult.StartRejected("SDK initialization was rejected")
        }
        return StartResult.StartAccepted
    }

    fun stop(): StopResult {
        val stopped = lock.withLock {
            if (state == SdkAvailability.STOPPED) {
                return StopResult.AlreadyStopped
            }
            runToken += 1
            state = SdkAvailability.STOPPED
            val close = closeRequired
            closeRequired = false
            StoppedRun(close, startupTimeout?.handle.also { startupTimeout = null })
        }
        stopped.timeout?.cancel()
        if (stopped.closeRequired) {
            runCatching { port.close() }
                .onFailure { record(SdkLifecycleDiagnosticKind.PORT_FAILURE) }
        }
        notifyState(SdkAvailability.STOPPED)
        return StopResult.Stopped
    }

    fun state(): SdkAvailability = lock.withLock { state }

    fun onChanged(listener: SdkStateListener): Registration {
        val slot = ListenerSlot(listener)
        lock.withLock { listeners += slot }
        return Registration {
            if (slot.deactivate()) {
                lock.withLock { listeners.remove(slot) }
            }
        }
    }

    private fun handleReady(token: Long) {
        val transition = transitionToReady(token)
        if (transition !== null) {
            transition.timeout?.cancel()
            notifyState(SdkAvailability.READY)
        } else {
            record(SdkLifecycleDiagnosticKind.STALE_CALLBACK)
        }
    }

    private fun handleFailure(token: Long) {
        val transition = transitionToFailed(token)
        if (transition !== null) {
            transition.timeout?.cancel()
            record(SdkLifecycleDiagnosticKind.PORT_FAILURE)
            notifyState(SdkAvailability.FAILED)
        } else {
            record(SdkLifecycleDiagnosticKind.STALE_CALLBACK)
        }
    }

    private fun armStartupTimeout(token: Long): Boolean {
        val timeout = runCatching {
            startupTimeoutScheduler.schedule(STARTUP_TIMEOUT_MILLIS) { handleStartupTimeout(token) }
        }.getOrElse {
            record(SdkLifecycleDiagnosticKind.PORT_FAILURE)
            return false
        }
        val installed = lock.withLock {
            if (runToken != token || state != SdkAvailability.STARTING) {
                false
            } else {
                startupTimeout = PendingStartupTimeout(token, timeout)
                true
            }
        }
        if (!installed) timeout.cancel()
        return true
    }

    private fun handleStartupTimeout(token: Long) {
        val transition = transitionToFailed(token) ?: return
        transition.timeout?.cancel()
        record(SdkLifecycleDiagnosticKind.START_TIMEOUT)
        notifyState(SdkAvailability.FAILED)
    }

    private fun transitionToReady(token: Long): TerminalTransition? = lock.withLock {
        if (token != runToken || state != SdkAvailability.STARTING) {
            null
        } else {
            state = SdkAvailability.READY
            TerminalTransition(takeStartupTimeout(token))
        }
    }

    private fun transitionToFailed(token: Long): TerminalTransition? = lock.withLock {
        if (token != runToken || state != SdkAvailability.STARTING) {
            null
        } else {
            state = SdkAvailability.FAILED
            TerminalTransition(takeStartupTimeout(token))
        }
    }

    private fun takeStartupTimeout(token: Long): SdkStartupTimeout? {
        val pending = startupTimeout
        return if (pending !== null && pending.token == token) {
            startupTimeout = null
            pending.handle
        } else {
            null
        }
    }

    private fun notifyState(next: SdkAvailability) {
        val current = lock.withLock { listeners.toList() }
        current.forEach { listener ->
            runCatching { listener.onChanged(next) }
                .onFailure { record(SdkLifecycleDiagnosticKind.LISTENER_FAILURE) }
        }
    }

    private fun record(kind: SdkLifecycleDiagnosticKind) {
        runCatching { diagnosticSink.record(SdkLifecycleDiagnostic(kind)) }
    }

    private class ListenerSlot(
        private val delegate: SdkStateListener,
    ) : SdkStateListener {
        private val lock = ReentrantLock()
        private var active = true

        fun deactivate(): Boolean = lock.withLock {
            val changed = active
            active = false
            changed
        }

        override fun onChanged(state: SdkAvailability) {
            if (lock.withLock { active }) delegate.onChanged(state)
        }
    }

    private data class PendingStartupTimeout(
        val token: Long,
        val handle: SdkStartupTimeout,
    )

    private data class StoppedRun(
        val closeRequired: Boolean,
        val timeout: SdkStartupTimeout?,
    )

    private data class TerminalTransition(
        val timeout: SdkStartupTimeout?,
    )

    companion object {
        private const val STARTUP_TIMEOUT_MILLIS = 30_000L

        fun create(
            port: DjiSdkPort,
            diagnosticSink: SdkLifecycleDiagnosticSink = SdkLifecycleDiagnosticSink { },
            startupTimeoutScheduler: SdkStartupTimeoutScheduler = ProcessStartupTimeoutScheduler,
        ): SdkLifecycle = SdkLifecycle(port, diagnosticSink, startupTimeoutScheduler)
    }
}

private object ProcessStartupTimeoutScheduler : SdkStartupTimeoutScheduler {
    private val executor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "skycommand-sdk-start-timeout").apply { isDaemon = true }
    }

    override fun schedule(delayMillis: Long, callback: () -> Unit): SdkStartupTimeout {
        val future = executor.schedule(callback, delayMillis, TimeUnit.MILLISECONDS)
        return SdkStartupTimeout { future.cancel(false) }
    }
}
