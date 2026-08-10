package com.skycommand.relay.runtime.permission

import java.util.concurrent.atomic.AtomicBoolean

enum class PermissionKind {
    RUNTIME,
    USB_ACCESS,
}

enum class PermissionState {
    UNKNOWN,
    GRANTED,
    DENIED,
    PERMANENTLY_DENIED,
}

class PermissionSnapshot private constructor(
    private val states: Map<PermissionKind, PermissionState>,
) {
    fun stateOf(kind: PermissionKind): PermissionState = states[kind] ?: PermissionState.UNKNOWN

    companion object {
        fun empty(): PermissionSnapshot = PermissionSnapshot(emptyMap())

        fun of(states: Map<PermissionKind, PermissionState>): PermissionSnapshot =
            PermissionSnapshot(states.toMap())

        fun granted(kind: PermissionKind): PermissionSnapshot =
            PermissionSnapshot(mapOf(kind to PermissionState.GRANTED))

        fun denied(kind: PermissionKind): PermissionSnapshot =
            PermissionSnapshot(mapOf(kind to PermissionState.DENIED))
    }
}

fun interface PermissionCancellation {
    fun cancel()
}

fun interface PermissionPortCallback {
    fun completed(snapshot: PermissionSnapshot)
}

interface PermissionPort {
    fun snapshot(): PermissionSnapshot

    fun request(required: Set<PermissionKind>, callback: PermissionPortCallback): PermissionCancellation
}

fun interface PermissionListener {
    fun onChanged(snapshot: PermissionSnapshot)
}

fun interface PermissionRegistration {
    fun unregister()
}

sealed interface PermissionRequestResult {
    data class Started(val cancellation: PermissionCancellation) : PermissionRequestResult

    data class AlreadySatisfied(val snapshot: PermissionSnapshot) : PermissionRequestResult

    data class Rejected(val reason: PermissionRejection) : PermissionRequestResult

    sealed interface Terminal : PermissionRequestResult {
        data class Completed(val snapshot: PermissionSnapshot) : Terminal

        data class Denied(val snapshot: PermissionSnapshot) : Terminal

        data object Failed : Terminal

        data object Cancelled : Terminal
    }
}

enum class PermissionRejection {
    EMPTY_REQUEST,
    ALREADY_IN_PROGRESS,
    PORT_FAILURE,
}

class PermissionCoordinator private constructor(
    private val port: PermissionPort,
) {
    private val lock = Any()
    private var nextOperationId = 0L
    private var active: Operation? = null
    private var current = runCatching { port.snapshot() }.getOrDefault(PermissionSnapshot.empty())
    private val listeners = mutableSetOf<PermissionListener>()

    fun snapshot(): PermissionSnapshot = synchronized(lock) { current }

    fun request(
        required: Set<PermissionKind>,
        listener: (PermissionRequestResult.Terminal) -> Unit,
    ): PermissionRequestResult {
        if (required.isEmpty()) return PermissionRequestResult.Rejected(PermissionRejection.EMPTY_REQUEST)
        val operation: Operation
        synchronized(lock) {
            if (active != null) return PermissionRequestResult.Rejected(PermissionRejection.ALREADY_IN_PROGRESS)
            val snapshot = runCatching { port.snapshot() }.getOrElse {
                return PermissionRequestResult.Rejected(PermissionRejection.PORT_FAILURE)
            }
            current = snapshot
            if (required.all { snapshot.stateOf(it) == PermissionState.GRANTED }) {
                return PermissionRequestResult.AlreadySatisfied(snapshot)
            }
            operation = Operation(++nextOperationId, required, listener)
            active = operation
        }

        val cancellation = try {
            port.request(required) { snapshot -> complete(operation, snapshot) }
        } catch (_: Exception) {
            complete(operation, null)
            return PermissionRequestResult.Rejected(PermissionRejection.PORT_FAILURE)
        }
        operation.portCancellation = cancellation
        return PermissionRequestResult.Started(PermissionCancellation { cancel(operation) })
    }

    fun onChanged(listener: PermissionListener): PermissionRegistration {
        synchronized(lock) { listeners += listener }
        return PermissionRegistration { synchronized(lock) { listeners -= listener } }
    }

    private fun cancel(operation: Operation) {
        if (!operation.terminal.compareAndSet(false, true)) return
        val portCancellation = synchronized(lock) {
            if (active?.id == operation.id) active = null
            operation.portCancellation
        }
        runCatching { portCancellation?.cancel() }
        notifyTerminal(operation, PermissionRequestResult.Terminal.Cancelled)
    }

    private fun complete(operation: Operation, snapshot: PermissionSnapshot?) {
        if (!operation.terminal.compareAndSet(false, true)) return
        val terminal = synchronized(lock) {
            if (active?.id == operation.id) active = null
            if (snapshot != null) {
                current = snapshot
                if (operation.required.all { snapshot.stateOf(it) == PermissionState.GRANTED }) {
                    PermissionRequestResult.Terminal.Completed(snapshot)
                } else {
                    PermissionRequestResult.Terminal.Denied(snapshot)
                }
            } else {
                PermissionRequestResult.Terminal.Failed
            }
        }
        if (snapshot != null) notifyChanged(snapshot)
        notifyTerminal(operation, terminal)
    }

    private fun notifyChanged(snapshot: PermissionSnapshot) {
        val targets = synchronized(lock) { listeners.toList() }
        targets.forEach { listener -> runCatching { listener.onChanged(snapshot) } }
    }

    private fun notifyTerminal(operation: Operation, terminal: PermissionRequestResult.Terminal) {
        runCatching { operation.listener(terminal) }
    }

    private class Operation(
        val id: Long,
        val required: Set<PermissionKind>,
        val listener: (PermissionRequestResult.Terminal) -> Unit,
    ) {
        val terminal = AtomicBoolean(false)
        var portCancellation: PermissionCancellation? = null
    }

    companion object {
        fun create(port: PermissionPort): PermissionCoordinator = PermissionCoordinator(port)
    }
}
