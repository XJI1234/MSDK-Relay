package com.skycommand.relay.device.remote.android

import android.util.Log
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.remotecontroller.RemoteControllerType
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
    private var initializing = true
    private var remoteControllerConnected: Boolean? = null
    private var type = RemoteControllerType.UNKNOWN
    private val pendingInitialUpdates = mutableListOf<KeyManagerObservation.() -> Unit>()

    fun start() {
        try {
            manager.listen(connectionKey, owner) { previous, next ->
                publishConnection(previous, next)
            }
            manager.listen(typeKey, owner) { _, next ->
                publishType(next ?: RemoteControllerType.UNKNOWN)
            }
            publishInitialFact()
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
        val fact = update { remoteControllerConnected = nextRemoteController }
        recordLinkDiagnostic(
            "$LINK_DIAGNOSTIC_PREFIX event=key-change key=RemoteControllerKey.KeyConnection " +
                "old=$previousRemoteController new=$nextRemoteController",
        )
        fact?.let(listener::onChanged)
    }

    private fun publishType(nextType: RemoteControllerType) {
        val fact = update { type = nextType }
        fact?.let { listener.onChanged(it) }
    }

    private fun update(transform: KeyManagerObservation.() -> Unit): DjiRemoteControllerFact? = synchronized(lock) {
        when {
            !active -> null
            initializing -> {
                pendingInitialUpdates += transform
                null
            }
            else -> {
                transform(this)
                val connected = groundUnitConnected(remoteControllerConnected)
                DjiRemoteControllerFact(connected, if (connected == true) type.toDisplayModel() else null)
            }
        }
    }

    private fun publishInitialFact() {
        val connection = runCatching { manager.getValue<Boolean>(connectionKey) }.getOrNull()
        val initialType = runCatching { manager.getValue<RemoteControllerType>(typeKey) }.getOrNull()
            ?: RemoteControllerType.UNKNOWN
        val fact = synchronized(lock) {
            if (!active) null else {
                remoteControllerConnected = connection
                type = initialType
                pendingInitialUpdates.forEach { update -> update(this) }
                pendingInitialUpdates.clear()
                initializing = false
                val connected = groundUnitConnected(remoteControllerConnected)
                DjiRemoteControllerFact(connected, if (connected == true) type.toDisplayModel() else null)
            }
        }
        recordLinkDiagnostic(
            "$LINK_DIAGNOSTIC_PREFIX event=initial-read remoteController=$connection",
        )
        fact?.let(listener::onChanged)
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
