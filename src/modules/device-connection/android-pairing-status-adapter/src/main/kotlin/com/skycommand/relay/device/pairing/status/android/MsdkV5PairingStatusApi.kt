package com.skycommand.relay.device.pairing.status.android

import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.remotecontroller.PairingState
import dji.v5.manager.KeyManager

internal class MsdkV5PairingStatusApi(
    private val manager: KeyManager = KeyManager.getInstance(),
) : DjiPairingStatusApi {
    override fun observe(listener: DjiPairingStatusListener): DjiPairingStatusObservation {
        val owner = Any()
        val key = KeyTools.createKey(RemoteControllerKey.KeyPairingStatus)
        try {
            manager.listen(key, owner) { _, next -> listener.onChanged(DjiPairingStatusFact((next ?: PairingState.UNKNOWN).name)) }
        } catch (failure: Throwable) {
            runCatching { manager.cancelListen(owner) }
            throw failure
        }
        return DjiPairingStatusObservation { manager.cancelListen(owner) }
    }
}
