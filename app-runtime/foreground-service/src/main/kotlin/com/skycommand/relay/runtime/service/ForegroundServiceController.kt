package com.skycommand.relay.runtime.service

enum class ForegroundServiceState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED,
}

enum class ForegroundRejection {
    ALREADY_RUNNING,
    ALREADY_STOPPED,
    TRANSITION_IN_PROGRESS,
    PORT_FAILURE,
}

interface ForegroundServiceCallback {
    fun started()
    fun stopped()
    fun failed()
}

interface ForegroundServicePort {
    fun start(callback: ForegroundServiceCallback)
    fun stop(callback: ForegroundServiceCallback)
}

fun interface ForegroundServiceListener {
    fun onChanged(state: ForegroundServiceState)
}

fun interface ForegroundServiceRegistration {
    fun unregister()
}

sealed interface ForegroundRequestResult {
    data object Accepted : ForegroundRequestResult

    data class Rejected(val reason: ForegroundRejection) : ForegroundRequestResult
}

class ForegroundServiceController private constructor(
    private val port: ForegroundServicePort,
) {
    private val lock = Any()
    private val listeners = mutableSetOf<ForegroundServiceListener>()
    private var state = ForegroundServiceState.STOPPED
    private var generation = 0L
    private var active: Operation? = null

    fun start(): ForegroundRequestResult {
        val operation: Operation
        synchronized(lock) {
            when (state) {
                ForegroundServiceState.RUNNING -> return rejected(ForegroundRejection.ALREADY_RUNNING)
                ForegroundServiceState.STARTING,
                ForegroundServiceState.STOPPING,
                -> return rejected(ForegroundRejection.TRANSITION_IN_PROGRESS)

                ForegroundServiceState.STOPPED,
                ForegroundServiceState.FAILED,
                -> Unit
            }
            operation = Operation(++generation, Direction.START)
            active = operation
            state = ForegroundServiceState.STARTING
        }
        notifyChanged(ForegroundServiceState.STARTING)
        try {
            port.start(callbackFor(operation))
        } catch (_: Exception) {
            fail(operation)
            return rejected(ForegroundRejection.PORT_FAILURE)
        }
        return ForegroundRequestResult.Accepted
    }

    fun stop(): ForegroundRequestResult {
        val operation: Operation
        synchronized(lock) {
            when (state) {
                ForegroundServiceState.STOPPED -> return rejected(ForegroundRejection.ALREADY_STOPPED)
                ForegroundServiceState.STARTING,
                ForegroundServiceState.STOPPING,
                -> return rejected(ForegroundRejection.TRANSITION_IN_PROGRESS)

                ForegroundServiceState.RUNNING,
                ForegroundServiceState.FAILED,
                -> Unit
            }
            operation = Operation(++generation, Direction.STOP)
            active = operation
            state = ForegroundServiceState.STOPPING
        }
        notifyChanged(ForegroundServiceState.STOPPING)
        try {
            port.stop(callbackFor(operation))
        } catch (_: Exception) {
            fail(operation)
            return rejected(ForegroundRejection.PORT_FAILURE)
        }
        return ForegroundRequestResult.Accepted
    }

    fun snapshot(): ForegroundServiceState = synchronized(lock) { state }

    fun onChanged(listener: ForegroundServiceListener): ForegroundServiceRegistration {
        synchronized(lock) { listeners += listener }
        return ForegroundServiceRegistration { synchronized(lock) { listeners -= listener } }
    }

    private fun callbackFor(operation: Operation) = object : ForegroundServiceCallback {
        override fun started() {
            complete(operation, ForegroundServiceState.RUNNING, Direction.START)
        }

        override fun stopped() {
            complete(operation, ForegroundServiceState.STOPPED, Direction.STOP)
        }

        override fun failed() {
            fail(operation)
        }
    }

    private fun complete(operation: Operation, next: ForegroundServiceState, expected: Direction) {
        synchronized(lock) {
            if (active?.matches(operation, expected) != true) return
            active = null
            state = next
        }
        notifyChanged(next)
    }

    private fun fail(operation: Operation) {
        synchronized(lock) {
            if (active?.id != operation.id) return
            active = null
            state = ForegroundServiceState.FAILED
        }
        notifyChanged(ForegroundServiceState.FAILED)
    }

    private fun notifyChanged(next: ForegroundServiceState) {
        val targets = synchronized(lock) { listeners.toList() }
        targets.forEach { runCatching { it.onChanged(next) } }
    }

    private fun rejected(reason: ForegroundRejection) = ForegroundRequestResult.Rejected(reason)

    private enum class Direction { START, STOP }

    private data class Operation(val id: Long, val direction: Direction) {
        fun matches(other: Operation, expected: Direction): Boolean =
            id == other.id && direction == expected
    }

    companion object {
        fun create(port: ForegroundServicePort): ForegroundServiceController = ForegroundServiceController(port)
    }
}
