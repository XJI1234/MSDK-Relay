package com.skycommand.relay.device.pairing.status.android

import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.remotecontroller.PairingState
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager

internal class MsdkV5PairingStatusApi(
    private val manager: KeyManager = KeyManager.getInstance(),
) : DjiPairingStatusApi {
    override fun observe(listener: DjiPairingStatusListener): DjiPairingStatusObservation {
        val observation = KeyManagerObservation(manager, listener)
        observation.start()
        return observation
    }
}

private class KeyManagerObservation(
    private val manager: KeyManager,
    private val listener: DjiPairingStatusListener,
) : DjiPairingStatusObservation {
    private val lock = Any()
    private val owner = Any()
    private val key = KeyTools.createKey(RemoteControllerKey.KeyPairingStatus)
    private var active = true
    private var state = PairingState.UNKNOWN
    private var pairingEventRevision = 0L

    fun start() {
        try {
            manager.listen(key, owner) { _, next -> publish(next ?: PairingState.UNKNOWN) }
            requestInitialValue(key)
        } catch (failure: Throwable) {
            synchronized(lock) { active = false }
            runCatching { manager.cancelListen(owner) }
            throw failure
        }
    }

    override fun close() {
        val shouldClose = synchronized(lock) { active.also { active = false } }
        if (shouldClose) manager.cancelListen(owner)
    }

    private fun publish(next: PairingState) {
        val fact = synchronized(lock) {
            if (!active) {
                null
            } else {
                pairingEventRevision += 1L
                state = next
                DjiPairingStatusFact(state.name)
            }
        }
        fact?.let(listener::onChanged)
    }

    private fun requestInitialValue(key: DJIKey<PairingState>) {
        val initialEventRevision = synchronized(lock) {
            if (!active) return
            pairingEventRevision
        }
        requestInitialValue(key) { value ->
            val fact = synchronized(lock) {
                if (!active || pairingEventRevision != initialEventRevision) {
                    null
                } else {
                    state = value ?: PairingState.UNKNOWN
                    DjiPairingStatusFact(state.name)
                }
            }
            fact?.let(listener::onChanged)
        }
    }

    private fun <T> requestInitialValue(key: DJIKey<T>, onSuccess: (T?) -> Unit) {
        manager.getValue(key, object : CommonCallbacks.CompletionCallbackWithParam<T> {
            override fun onSuccess(value: T) = onSuccess(value)

            override fun onFailure(error: IDJIError) = Unit
        })
    }
}
