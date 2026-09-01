package com.skycommand.relay.device.remote.android

import android.util.Log
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.remotecontroller.RemoteControllerType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager

internal fun groundUnitConnected(
    remoteControllerKey: Boolean?,
): Boolean? = remoteControllerKey

internal class MsdkV5RemoteControllerApi(
    private val manager: KeyManager = KeyManager.getInstance(),
) : DjiRemoteControllerApi {
    override fun observe(listener: DjiRemoteControllerListener): DjiRemoteControllerObservation {
        val observation = KeyManagerObservation(manager, listener)
        observation.start()
        return observation
    }
}

private class KeyManagerObservation(
    private val manager: KeyManager,
    private val listener: DjiRemoteControllerListener,
) : DjiRemoteControllerObservation {
    private val lock = Any()
    private val owner = Any()
    private val connectionKey = KeyTools.createKey(RemoteControllerKey.KeyConnection)
    private val typeKey = KeyTools.createKey(RemoteControllerKey.KeyRemoteControllerType)
    private var active = true
    private var remoteControllerConnected: Boolean? = null
    private var type = RemoteControllerType.UNKNOWN
    private var connectionEventRevision = 0L
    private var typeEventRevision = 0L

    fun start() {
        try {
            manager.listen(connectionKey, owner) { previous, next ->
                publishConnection(previous, next)
            }
            manager.listen(typeKey, owner) { _, next ->
                publishType(next ?: RemoteControllerType.UNKNOWN)
            }
            requestInitialConnection()
            requestInitialType()
        } catch (failure: Throwable) {
            runCatching { manager.cancelListen(owner) }
            throw failure
        }
    }

    override fun close() {
        val shouldClose = synchronized(lock) {
            active.also { active = false }
        }
        if (shouldClose) manager.cancelListen(owner)
    }

    private fun publishConnection(
        previousRemoteController: Boolean?,
        nextRemoteController: Boolean?,
    ) {
        val fact = update {
            connectionEventRevision += 1L
            remoteControllerConnected = nextRemoteController
        }
        recordLinkDiagnostic(
            "$LINK_DIAGNOSTIC_PREFIX event=key-change key=RemoteControllerKey.KeyConnection " +
                "old=$previousRemoteController new=$nextRemoteController",
        )
        fact?.let(listener::onChanged)
    }

    private fun publishType(nextType: RemoteControllerType) {
        val fact = update {
            typeEventRevision += 1L
            type = nextType
        }
        fact?.let { listener.onChanged(it) }
    }

    private fun requestInitialConnection() {
        val initialEventRevision = synchronized(lock) {
            if (!active) return
            connectionEventRevision
        }
        requestInitialValue(connectionKey, "RemoteControllerKey.KeyConnection") { initialValue ->
            val fact = synchronized(lock) {
                if (!active || connectionEventRevision != initialEventRevision) {
                    null
                } else {
                    remoteControllerConnected = initialValue
                    currentFact()
                }
            }
            fact?.let(listener::onChanged)
        }
    }

    private fun requestInitialType() {
        val initialEventRevision = synchronized(lock) {
            if (!active) return
            typeEventRevision
        }
        requestInitialValue(typeKey, "RemoteControllerKey.KeyRemoteControllerType") { initialValue ->
            val fact = synchronized(lock) {
                if (!active || typeEventRevision != initialEventRevision) {
                    null
                } else {
                    type = initialValue ?: RemoteControllerType.UNKNOWN
                    currentFact()
                }
            }
            fact?.let(listener::onChanged)
        }
    }

    private fun <T> requestInitialValue(
        key: DJIKey<T>,
        diagnosticName: String,
        onSuccess: (T?) -> Unit,
    ) {
        manager.getValue(key, object : CommonCallbacks.CompletionCallbackWithParam<T> {
            override fun onSuccess(value: T) {
                onSuccess(value)
            }

            override fun onFailure(error: IDJIError) {
                recordLinkDiagnostic("$LINK_DIAGNOSTIC_PREFIX event=hardware-read-failure key=$diagnosticName")
            }
        })
    }

    private fun currentFact(): DjiRemoteControllerFact {
        val connected = groundUnitConnected(remoteControllerConnected)
        return DjiRemoteControllerFact(connected, if (connected == true) type.toDisplayModel() else null)
    }

    private fun update(transform: KeyManagerObservation.() -> Unit): DjiRemoteControllerFact? = synchronized(lock) {
        if (!active) {
            null
        } else {
            transform(this)
            currentFact()
        }
    }

    private fun RemoteControllerType.toDisplayModel(): String? = when (this) {
        RemoteControllerType.UNKNOWN,
        RemoteControllerType.NONE,
        -> null

        else -> name
            .takeUnless { it.startsWith("NOT_SUPPORTED") }
            ?.replace('_', ' ')
    }

    private fun recordLinkDiagnostic(message: String) {
        runCatching { Log.i(LINK_DIAGNOSTIC_TAG, message) }
    }

    private companion object {
        const val LINK_DIAGNOSTIC_TAG = "SCLinkDiag"
        const val LINK_DIAGNOSTIC_PREFIX = "[DEBUG-link-order]"
    }
}
