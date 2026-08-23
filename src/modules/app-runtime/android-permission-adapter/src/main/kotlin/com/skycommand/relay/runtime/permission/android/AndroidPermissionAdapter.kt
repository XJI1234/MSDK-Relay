package com.skycommand.relay.runtime.permission.android

import android.app.Activity
import androidx.activity.result.ActivityResultRegistry
import androidx.lifecycle.LifecycleOwner
import com.skycommand.relay.runtime.permission.PermissionCancellation
import com.skycommand.relay.runtime.permission.PermissionKind
import com.skycommand.relay.runtime.permission.PermissionPort
import com.skycommand.relay.runtime.permission.PermissionPortCallback
import com.skycommand.relay.runtime.permission.PermissionSnapshot
import com.skycommand.relay.runtime.permission.PermissionState
import java.util.concurrent.atomic.AtomicBoolean

internal interface PermissionAdapterPlatform {
    fun snapshot(): PermissionSnapshot

    fun requestRuntimePermissions(callback: () -> Unit, failure: () -> Unit): PermissionCancellation

    fun requestUsbPermission(callback: () -> Unit, failure: () -> Unit): PermissionCancellation

    fun onUsbPresenceChanged(listener: () -> Unit): PermissionCancellation = PermissionCancellation { }

    fun rebind(activity: Any, activityResultRegistry: Any, lifecycleOwner: Any) = Unit

    fun close() = Unit
}

class AndroidPermissionAdapter internal constructor(
    private val platform: PermissionAdapterPlatform,
) : PermissionPort, AutoCloseable {
    private val lock = Any()
    private var active: Operation? = null
    private var closed = false

    override fun snapshot(): PermissionSnapshot = synchronized(lock) {
        check(!closed) { "Permission adapter is closed" }
        platform.snapshot()
    }

    override fun request(
        required: Set<PermissionKind>,
        callback: PermissionPortCallback,
    ): PermissionCancellation {
        require(required.isNotEmpty()) { "Permission request must not be empty" }

        val initial = snapshot()
        if (required.all { initial.stateOf(it) == PermissionState.GRANTED }) {
            notifyCompleted(callback, initial)
            return PermissionCancellation { }
        }

        val operation = Operation(required, callback)
        synchronized(lock) {
            check(!closed) { "Permission adapter is closed" }
            check(active == null) { "A permission request is already active" }
            active = operation
        }

        try {
            if (required.contains(PermissionKind.RUNTIME) &&
                initial.stateOf(PermissionKind.RUNTIME) != PermissionState.GRANTED
            ) {
                operation.addCancellation(
                    platform.requestRuntimePermissions(
                        callback = { onPlatformResult(operation) },
                        failure = { fail(operation) },
                    ),
                )
            }
            if (isActive(operation) &&
                required.contains(PermissionKind.USB_ACCESS) &&
                initial.stateOf(PermissionKind.USB_ACCESS) != PermissionState.GRANTED
            ) {
                operation.addCancellation(
                    platform.requestUsbPermission(
                        callback = { onPlatformResult(operation) },
                        failure = { fail(operation) },
                    ),
                )
            }
        } catch (failure: Exception) {
            cancel(operation)
            throw failure
        }

        return PermissionCancellation { cancel(operation) }
    }

    fun onUsbPresenceChanged(listener: () -> Unit): PermissionCancellation =
        platform.onUsbPresenceChanged(listener)

    fun rebind(
        activity: Any,
        activityResultRegistry: Any,
        lifecycleOwner: Any,
    ) {
        synchronized(lock) {
            check(!closed) { "Permission adapter is closed" }
        }
        platform.rebind(activity, activityResultRegistry, lifecycleOwner)
    }

    override fun close() {
        val operation = synchronized(lock) {
            if (closed) return
            closed = true
            active.also { active = null }
        }
        operation?.cancelPlatformRequests()
        runCatching { platform.close() }
    }

    private fun onPlatformResult(operation: Operation) {
        if (!isActive(operation)) return
        val snapshot = runCatching { platform.snapshot() }.getOrNull()
        if (snapshot == null) {
            fail(operation)
            return
        }

        val shouldComplete = operation.required.all { snapshot.stateOf(it) == PermissionState.GRANTED }
        val shouldDeny = operation.required.any {
            snapshot.stateOf(it) == PermissionState.DENIED ||
                snapshot.stateOf(it) == PermissionState.PERMANENTLY_DENIED
        }
        if (shouldComplete || shouldDeny) complete(operation, snapshot)
    }

    private fun complete(operation: Operation, snapshot: PermissionSnapshot) {
        val accepted = synchronized(lock) {
            if (active !== operation || !operation.terminal.compareAndSet(false, true)) {
                false
            } else {
                active = null
                true
            }
        }
        if (!accepted) return
        operation.cancelPlatformRequests()
        notifyCompleted(operation.callback, snapshot)
    }

    private fun fail(operation: Operation) {
        val accepted = synchronized(lock) {
            if (active !== operation || !operation.terminal.compareAndSet(false, true)) {
                false
            } else {
                active = null
                true
            }
        }
        if (!accepted) return
        operation.cancelPlatformRequests()
        runCatching { operation.callback.failed() }
    }

    private fun cancel(operation: Operation) {
        val accepted = synchronized(lock) {
            if (active !== operation || !operation.terminal.compareAndSet(false, true)) {
                false
            } else {
                active = null
                true
            }
        }
        if (accepted) operation.cancelPlatformRequests()
    }

    private fun isActive(operation: Operation): Boolean = synchronized(lock) {
        active === operation && !operation.terminal.get() && !closed
    }

    private fun notifyCompleted(callback: PermissionPortCallback, snapshot: PermissionSnapshot) {
        runCatching { callback.completed(snapshot) }
    }

    private class Operation(
        val required: Set<PermissionKind>,
        val callback: PermissionPortCallback,
    ) {
        val terminal = AtomicBoolean(false)
        private val lock = Any()
        private val cancellations = mutableListOf<PermissionCancellation>()

        fun addCancellation(cancellation: PermissionCancellation) {
            val cancelImmediately = synchronized(lock) {
                if (terminal.get()) true else {
                    cancellations += cancellation
                    false
                }
            }
            if (cancelImmediately) runCatching { cancellation.cancel() }
        }

        fun cancelPlatformRequests() {
            val current = synchronized(lock) { cancellations.toList() }
            current.forEach { runCatching { it.cancel() } }
        }
    }

    companion object {
        fun attach(
            activity: Activity,
            activityResultRegistry: ActivityResultRegistry,
            lifecycleOwner: LifecycleOwner,
        ): AndroidPermissionAdapter = AndroidPermissionAdapter(
            AndroidPermissionPlatform.attach(activity, activityResultRegistry, lifecycleOwner),
        )
    }
}
