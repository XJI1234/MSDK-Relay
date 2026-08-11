package com.skycommand.relay.runtime

import com.skycommand.relay.runtime.bootstrap.AppBootstrap
import com.skycommand.relay.runtime.bootstrap.BootstrapResult
import com.skycommand.relay.runtime.permission.PermissionCancellation
import com.skycommand.relay.runtime.permission.PermissionCoordinator
import com.skycommand.relay.runtime.permission.PermissionKind
import com.skycommand.relay.runtime.permission.PermissionRequestResult
import com.skycommand.relay.runtime.service.ForegroundRequestResult
import com.skycommand.relay.runtime.service.ForegroundServiceController
import com.skycommand.relay.runtime.service.ForegroundServiceState

enum class RuntimeState {
    STOPPED,
    WAITING_PERMISSIONS,
    STARTING_SERVICE,
    STARTING_MODULES,
    RUNNING,
    STOPPING,
    FAILED,
}

enum class RuntimeStartFailure {
    PERMISSION_REQUEST,
    FOREGROUND_SERVICE,
    MODULES,
}

sealed interface RuntimeStartResult {
    data class Accepted(val cancellation: RuntimeCancellation) : RuntimeStartResult
    data class Rejected(val reason: RuntimeStartFailure) : RuntimeStartResult
    data object AlreadyRunning : RuntimeStartResult
    data object TransitionInProgress : RuntimeStartResult
}

sealed interface RuntimeStopResult {
    data object Accepted : RuntimeStopResult
    data object AlreadyStopped : RuntimeStopResult
    data object TransitionInProgress : RuntimeStopResult
    data class Rejected(val reason: RuntimeStopFailure) : RuntimeStopResult
}

enum class RuntimeStopFailure {
    STOP_FAILURE,
}

fun interface RuntimeCancellation {
    fun cancel()
}

fun interface RuntimeListener {
    fun onChanged(state: RuntimeState)
}

fun interface RuntimeRegistration {
    fun unregister()
}

class AppRuntime private constructor(
    private val permissions: PermissionCoordinator,
    private val foregroundService: ForegroundServiceController,
    private val bootstrap: AppBootstrap,
) {
    private val lock = Any()
    private val listeners = mutableSetOf<RuntimeListener>()
    private var state = RuntimeState.STOPPED
    private var generation = 0L
    private var permissionCancellation: PermissionCancellation? = null
    private var startFailure: RuntimeStartFailure? = null

    init {
        foregroundService.onChanged { serviceState -> onForegroundChanged(serviceState) }
    }

    fun start(requiredPermissions: Set<PermissionKind>): RuntimeStartResult {
        val operation: Long
        synchronized(lock) {
            when (state) {
                RuntimeState.RUNNING -> return RuntimeStartResult.AlreadyRunning
                RuntimeState.WAITING_PERMISSIONS,
                RuntimeState.STARTING_SERVICE,
                RuntimeState.STARTING_MODULES,
                RuntimeState.STOPPING,
                -> return RuntimeStartResult.TransitionInProgress

                RuntimeState.STOPPED,
                RuntimeState.FAILED,
                -> Unit
            }
            operation = ++generation
            permissionCancellation = null
            startFailure = null
            state = RuntimeState.WAITING_PERMISSIONS
        }
        notifyChanged(RuntimeState.WAITING_PERMISSIONS)
        val request = permissions.request(requiredPermissions) { terminal -> onPermissionTerminal(operation, terminal) }
        when (request) {
            is PermissionRequestResult.AlreadySatisfied -> beginForegroundService(operation)
            is PermissionRequestResult.Started -> synchronized(lock) {
                if (generation == operation && state == RuntimeState.WAITING_PERMISSIONS) {
                    permissionCancellation = request.cancellation
                }
            }

            is PermissionRequestResult.Rejected -> fail(
                operation,
                RuntimeState.WAITING_PERMISSIONS,
                RuntimeStartFailure.PERMISSION_REQUEST,
            )
            else -> Unit
        }
        val immediateFailure = synchronized(lock) {
            if (generation == operation) startFailure else null
        }
        return when (request) {
            is PermissionRequestResult.Rejected -> RuntimeStartResult.Rejected(RuntimeStartFailure.PERMISSION_REQUEST)
            else -> immediateFailure?.let { RuntimeStartResult.Rejected(it) }
                ?: RuntimeStartResult.Accepted(RuntimeCancellation { cancelStartup(operation) })
        }
    }

    fun stop(): RuntimeStopResult {
        synchronized(lock) {
            when (state) {
                RuntimeState.STOPPED -> return RuntimeStopResult.AlreadyStopped
                RuntimeState.WAITING_PERMISSIONS,
                RuntimeState.STARTING_SERVICE,
                RuntimeState.STARTING_MODULES,
                RuntimeState.STOPPING,
                -> return RuntimeStopResult.TransitionInProgress

                RuntimeState.RUNNING,
                RuntimeState.FAILED,
                -> state = RuntimeState.STOPPING
            }
        }
        notifyChanged(RuntimeState.STOPPING)
        val modules = bootstrap.stop()
        val service = foregroundService.stop()
        if (modules is BootstrapResult.Rejected || service is ForegroundRequestResult.Rejected) {
            transitionTo(RuntimeState.FAILED)
            return RuntimeStopResult.Rejected(RuntimeStopFailure.STOP_FAILURE)
        }
        if (foregroundService.snapshot() == ForegroundServiceState.STOPPED && snapshot() == RuntimeState.STOPPING) {
            transitionTo(RuntimeState.STOPPED)
        }
        return RuntimeStopResult.Accepted
    }

    fun snapshot(): RuntimeState = synchronized(lock) { state }

    fun onChanged(listener: RuntimeListener): RuntimeRegistration {
        synchronized(lock) { listeners += listener }
        return RuntimeRegistration { synchronized(lock) { listeners -= listener } }
    }

    private fun onPermissionTerminal(operation: Long, terminal: PermissionRequestResult.Terminal) {
        if (!isCurrent(operation, RuntimeState.WAITING_PERMISSIONS)) return
        when (terminal) {
            is PermissionRequestResult.Terminal.Completed -> beginForegroundService(operation)
            PermissionRequestResult.Terminal.Cancelled -> transitionTo(RuntimeState.STOPPED)
            is PermissionRequestResult.Terminal.Denied,
            PermissionRequestResult.Terminal.Failed,
            -> fail(operation, RuntimeState.WAITING_PERMISSIONS, RuntimeStartFailure.PERMISSION_REQUEST)
        }
    }

    private fun beginForegroundService(operation: Long) {
        if (!isCurrent(operation, RuntimeState.WAITING_PERMISSIONS)) return
        transitionTo(RuntimeState.STARTING_SERVICE)
        when (foregroundService.start()) {
            ForegroundRequestResult.Accepted -> {
                if (foregroundService.snapshot() == ForegroundServiceState.RUNNING) beginModules(operation)
            }

            is ForegroundRequestResult.Rejected -> {
                if (foregroundService.snapshot() == ForegroundServiceState.RUNNING) {
                    beginModules(operation)
                } else {
                    fail(operation, RuntimeState.STARTING_SERVICE, RuntimeStartFailure.FOREGROUND_SERVICE)
                }
            }
        }
    }

    private fun onForegroundChanged(serviceState: ForegroundServiceState) {
        val operation = synchronized(lock) { generation }
        when (serviceState) {
            ForegroundServiceState.RUNNING -> beginModules(operation)
            ForegroundServiceState.STOPPED -> if (snapshot() == RuntimeState.STOPPING) transitionTo(RuntimeState.STOPPED)
            ForegroundServiceState.FAILED -> if (snapshot() == RuntimeState.STARTING_SERVICE) {
                fail(operation, RuntimeState.STARTING_SERVICE, RuntimeStartFailure.FOREGROUND_SERVICE)
            }
            else -> Unit
        }
    }

    private fun beginModules(operation: Long) {
        if (!isCurrent(operation, RuntimeState.STARTING_SERVICE)) return
        transitionTo(RuntimeState.STARTING_MODULES)
        when (bootstrap.start()) {
            BootstrapResult.Started,
            BootstrapResult.AlreadyRunning,
            -> transitionTo(RuntimeState.RUNNING)

            else -> {
                foregroundService.stop()
                fail(operation, RuntimeState.STARTING_MODULES, RuntimeStartFailure.MODULES)
            }
        }
    }

    private fun cancelStartup(operation: Long) {
        val cancellation = synchronized(lock) {
            if (generation != operation || state != RuntimeState.WAITING_PERMISSIONS) return
            permissionCancellation.also { permissionCancellation = null }
        }
        runCatching { cancellation?.cancel() }
        if (isCurrent(operation, RuntimeState.WAITING_PERMISSIONS)) transitionTo(RuntimeState.STOPPED)
    }

    private fun fail(
        operation: Long,
        expected: RuntimeState,
        reason: RuntimeStartFailure,
    ) {
        if (!isCurrent(operation, expected)) return
        synchronized(lock) { startFailure = reason }
        transitionTo(RuntimeState.FAILED)
    }

    private fun isCurrent(operation: Long, expected: RuntimeState? = null): Boolean = synchronized(lock) {
        generation == operation && (expected == null || state == expected)
    }

    private fun transitionTo(next: RuntimeState) {
        synchronized(lock) {
            state = next
            if (next != RuntimeState.WAITING_PERMISSIONS) permissionCancellation = null
        }
        notifyChanged(next)
    }

    private fun notifyChanged(next: RuntimeState) {
        val targets = synchronized(lock) { listeners.toList() }
        targets.forEach { runCatching { it.onChanged(next) } }
    }

    companion object {
        fun create(
            permissions: PermissionCoordinator,
            foregroundService: ForegroundServiceController,
            bootstrap: AppBootstrap,
        ): AppRuntime = AppRuntime(permissions, foregroundService, bootstrap)
    }
}
