package com.skycommand.relay.device.pairing.status.android

import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.remotecontroller.PairingState
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
    private var initializing = true
    private var state = PairingState.UNKNOWN
    private val pendingInitialStates = mutableListOf<PairingState>()

    fun start() {
        try {
            manager.listen(key, owner) { _, next -> publish(next ?: PairingState.UNKNOWN) }
            publishInitialFact()
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
            when {
                !active -> null
                initializing -> {
                    pendingInitialStates += next
                    null
                }
                else -> {
                    state = next
                    DjiPairingStatusFact(state.name)
                }
            }
        }
        fact?.let(listener::onChanged)
    }

    private fun publishInitialFact() {
        val initial = runCatching { manager.getValue<PairingState>(key) }.getOrNull() ?: PairingState.UNKNOWN
        val fact = synchronized(lock) {
            if (!active) null else {
                state = initial
                pendingInitialStates.forEach { state = it }
                pendingInitialStates.clear()
                initializing = false
                DjiPairingStatusFact(state.name)
            }
        }
        fact?.let(listener::onChanged)
    }
}
