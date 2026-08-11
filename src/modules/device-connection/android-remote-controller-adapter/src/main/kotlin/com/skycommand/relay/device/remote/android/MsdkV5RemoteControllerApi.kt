package com.skycommand.relay.device.remote.android

import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.remotecontroller.RemoteControllerType
import dji.v5.manager.KeyManager

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
    private var connected = false
    private var type = RemoteControllerType.UNKNOWN

    fun start() {
        try {
            manager.listen(connectionKey, owner) { _, next ->
                updateConnection(next == true)
            }
            manager.listen(typeKey, owner) { _, next ->
                updateType(next ?: RemoteControllerType.UNKNOWN)
            }
            publishCurrent(
                manager.getValue(connectionKey, false),
                manager.getValue(typeKey, RemoteControllerType.UNKNOWN),
            )
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

    private fun updateConnection(next: Boolean) {
        publishCurrent(next, null)
    }

    private fun updateType(next: RemoteControllerType) {
        publishCurrent(null, next)
    }

    private fun publishCurrent(
        nextConnection: Boolean?,
        nextType: RemoteControllerType?,
    ) {
        val fact = synchronized(lock) {
            if (!active) {
                null
            } else {
                nextConnection?.let { connected = it }
                nextType?.let { type = it }
                DjiRemoteControllerFact(connected, type.toDisplayModel())
            }
        }
        fact?.let { listener.onChanged(it) }
    }

    private fun RemoteControllerType.toDisplayModel(): String? = when (this) {
        RemoteControllerType.UNKNOWN,
        RemoteControllerType.NONE,
        -> null

        else -> name
            .takeUnless { it.startsWith("NOT_SUPPORTED") }
            ?.replace('_', ' ')
    }
}
