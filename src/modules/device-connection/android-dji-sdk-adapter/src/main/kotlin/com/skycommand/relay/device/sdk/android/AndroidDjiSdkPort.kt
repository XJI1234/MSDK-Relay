package com.skycommand.relay.device.sdk.android

import android.content.Context
import com.skycommand.relay.device.sdk.DjiSdkCallbacks
import com.skycommand.relay.device.sdk.DjiSdkPort
import com.skycommand.relay.device.sdk.PortStartResult

internal interface DjiSdkManagerBridge {
    fun initialize(listener: DjiSdkManagerListener): BridgeStartResult

    fun close()
}

internal interface DjiSdkManagerListener {
    fun onRegistered()

    fun onFailure()
}

internal sealed interface BridgeStartResult {
    data object Accepted : BridgeStartResult

    data object Rejected : BridgeStartResult
}

class AndroidDjiSdkPort internal constructor(
    private val bridge: DjiSdkManagerBridge,
) : DjiSdkPort {
    private val lock = Any()
    private var generation = 0L
    private var active: Active? = null
    private var bridgeClosed = false

    override fun initialize(callbacks: DjiSdkCallbacks): PortStartResult {
        val operation = synchronized(lock) {
            check(active == null) { "DJI SDK initialization is already active" }
            bridgeClosed = false
            Active(++generation, callbacks).also { active = it }
        }

        val result = runCatching {
            bridge.initialize(listenerFor(operation))
        }.getOrElse {
            clear(operation)
            return rejected()
        }
        if (result is BridgeStartResult.Rejected) {
            clear(operation)
            return rejected()
        }
        val pending = synchronized(lock) {
            if (!isCurrent(operation)) {
                null
            } else {
                operation.accepted = true
                operation.pending?.also { active = null }
            }
        }
        pending?.let { dispatch(operation.callbacks, it) }
        return PortStartResult.Accepted
    }

    override fun close() {
        val shouldClose = synchronized(lock) {
            if (bridgeClosed) {
                false
            } else {
                generation += 1
                active = null
                bridgeClosed = true
                true
            }
        }
        if (shouldClose) runCatching { bridge.close() }
    }

    private fun listenerFor(operation: Active) = object : DjiSdkManagerListener {
        override fun onRegistered() {
            receive(operation, Terminal.REGISTERED)
        }

        override fun onFailure() {
            receive(operation, Terminal.FAILED)
        }
    }

    private fun receive(operation: Active, terminal: Terminal) {
        val delivery = synchronized(lock) {
            if (!isCurrent(operation)) {
                null
            } else if (!operation.accepted) {
                if (operation.pending == null) operation.pending = terminal
                null
            } else {
                active = null
                terminal
            }
        }
        delivery?.let { dispatch(operation.callbacks, it) }
    }

    private fun dispatch(callbacks: DjiSdkCallbacks, terminal: Terminal) {
        runCatching {
            if (terminal == Terminal.REGISTERED) callbacks.onReady() else callbacks.onFailure()
        }
    }

    private fun clear(operation: Active) {
        synchronized(lock) {
            if (isCurrent(operation)) active = null
        }
    }

    private fun isCurrent(operation: Active): Boolean =
        active === operation && generation == operation.generation

    private fun rejected(): PortStartResult =
        PortStartResult.Rejected("DJI SDK initialization unavailable")

    private data class Active(
        val generation: Long,
        val callbacks: DjiSdkCallbacks,
    ) {
        var accepted = false
        var pending: Terminal? = null
    }

    private enum class Terminal {
        REGISTERED,
        FAILED,
    }

    companion object {
        fun create(context: Context): DjiSdkPort =
            AndroidDjiSdkPort(MsdkV5ManagerBridge(context.applicationContext))
    }
}
