package com.skycommand.relay.flight.dji.android

import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager

internal class MsdkV5FlightApi(
    private val manager: KeyManager = KeyManager.getInstance(),
) : DjiFlightApi {
    override fun takeoff(completion: DjiFlightCompletion) = perform(FlightControllerKey.KeyStartTakeoff, completion)
    override fun land(completion: DjiFlightCompletion) = perform(FlightControllerKey.KeyStartAutoLanding, completion)
    override fun confirmLanding(completion: DjiFlightCompletion) = perform(FlightControllerKey.KeyConfirmLanding, completion)
    override fun returnHome(completion: DjiFlightCompletion) = perform(FlightControllerKey.KeyStartGoHome, completion)
    override fun stopTakeoff(completion: DjiFlightCompletion) = perform(FlightControllerKey.KeyStopTakeoff, completion)
    override fun stopAutoLanding(completion: DjiFlightCompletion) = perform(FlightControllerKey.KeyStopAutoLanding, completion)

    private fun perform(
        key: dji.sdk.keyvalue.key.DJIActionKeyInfo<*, EmptyMsg>,
        completion: DjiFlightCompletion,
    ) {
        manager.performAction(
            KeyTools.createKey(key),
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(result: EmptyMsg) = completion.succeed()
                override fun onFailure(error: IDJIError) = completion.fail()
            },
        )
    }
}
