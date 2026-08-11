package com.skycommand.relay.device.pairing.command.android

import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.manager.KeyManager

internal class MsdkV5PairingCommandApi(private val manager: KeyManager = KeyManager.getInstance()) : DjiPairingCommandApi {
    override fun perform(action: PairingCommand, completion: DjiCommandCompletion) {
        val key = KeyTools.createKey(
            if (action == PairingCommand.START) RemoteControllerKey.KeyRequestPairing else RemoteControllerKey.KeyStopPairing,
        )
        manager.performAction(key, object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
            override fun onSuccess(result: EmptyMsg) = completion.succeed()
            override fun onFailure(error: dji.v5.common.error.IDJIError) = completion.fail()
        })
    }
}
