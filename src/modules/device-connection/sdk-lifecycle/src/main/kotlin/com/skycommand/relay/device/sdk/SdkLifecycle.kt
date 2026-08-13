package com.skycommand.relay.device.sdk

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

fun interface SdkLifecycleDiagnosticSink {
    fun record(diagnostic: SdkLifecycleDiagnostic)
}

data class SdkLifecycleDiagnostic(
    val kind: SdkLifecycleDiagnosticKind,
)

enum class SdkLifecycleDiagnosticKind {
    PORT_FAILURE,
    LISTENER_FAILURE,
    STALE_CALLBACK,
}

class SdkLifecycle private constructor(
    private val port: DjiSdkPort,
    private val diagnosticSink: SdkLifecycleDiagnosticSink,
) {
    private val lock = ReentrantLock()
    private val listeners = mutableListOf<ListenerSlot>()
    private var state = SdkAvailability.STOPPED
    private var runToken = 0L
    private var closeRequired = false

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
            val failed = lock.withLock {
                if (runToken == token && state == SdkAvailability.STARTING) {
                    state = SdkAvailability.FAILED
                    true
                } else {
                    false
                }
            }
            if (failed) notifyState(SdkAvailability.FAILED)
            return StartResult.StartRejected("SDK initialization was rejected")
        }
        return StartResult.StartAccepted
    }

    fun stop(): StopResult {
        val shouldClose = lock.withLock {
            if (state == SdkAvailability.STOPPED) {
                return StopResult.AlreadyStopped
            }
            runToken += 1
            state = SdkAvailability.STOPPED
            val close = closeRequired
            closeRequired = false
            close
        }
        if (shouldClose) {
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
        val accepted = lock.withLock {
            if (token != runToken || state != SdkAvailability.STARTING) {
                false
            } else {
                state = SdkAvailability.READY
                true
            }
        }
        if (accepted) notifyState(SdkAvailability.READY) else record(SdkLifecycleDiagnosticKind.STALE_CALLBACK)
    }

    private fun handleFailure(token: Long) {
        val failed = lock.withLock {
            if (token != runToken || state != SdkAvailability.STARTING) {
                false
            } else {
                state = SdkAvailability.FAILED
                true
            }
        }
        if (failed) {
            record(SdkLifecycleDiagnosticKind.PORT_FAILURE)
            notifyState(SdkAvailability.FAILED)
        } else {
            record(SdkLifecycleDiagnosticKind.STALE_CALLBACK)
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

    companion object {
        fun create(
            port: DjiSdkPort,
            diagnosticSink: SdkLifecycleDiagnosticSink = SdkLifecycleDiagnosticSink { },
        ): SdkLifecycle = SdkLifecycle(port, diagnosticSink)
    }
}
