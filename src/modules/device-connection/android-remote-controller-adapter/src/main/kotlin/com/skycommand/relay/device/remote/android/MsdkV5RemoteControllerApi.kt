package com.skycommand.relay.device.remote.android

import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.remotecontroller.RemoteControllerType
import dji.v5.manager.KeyManager

internal fun groundUnitConnected(
    remoteControllerKey: Boolean,
    productConnectionKey: Boolean,
): Boolean = remoteControllerKey || productConnectionKey

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
    private val productKey = KeyTools.createKey(ProductKey.KeyConnection)
    private val typeKey = KeyTools.createKey(RemoteControllerKey.KeyRemoteControllerType)
    private var active = true
    private var remoteControllerConnected = false
    private var productConnected = false
    private var type = RemoteControllerType.UNKNOWN

    fun start() {
        try {
            manager.listen(connectionKey, owner) { _, next ->
                publishCurrent(nextRemoteController = next == true, nextProduct = null, nextType = null)
            }
            manager.listen(productKey, owner) { _, next ->
                publishCurrent(nextRemoteController = null, nextProduct = next == true, nextType = null)
            }
            manager.listen(typeKey, owner) { _, next ->
                publishCurrent(nextRemoteController = null, nextProduct = null, nextType = next ?: RemoteControllerType.UNKNOWN)
            }
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

    private fun publishCurrent(
        nextRemoteController: Boolean?,
        nextProduct: Boolean?,
        nextType: RemoteControllerType?,
    ) {
        val fact = synchronized(lock) {
            if (!active) {
                null
            } else {
                nextRemoteController?.let { remoteControllerConnected = it }
                nextProduct?.let { productConnected = it }
                nextType?.let { type = it }
                val connected = groundUnitConnected(remoteControllerConnected, productConnected)
                DjiRemoteControllerFact(connected, if (connected) type.toDisplayModel() else null)
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
