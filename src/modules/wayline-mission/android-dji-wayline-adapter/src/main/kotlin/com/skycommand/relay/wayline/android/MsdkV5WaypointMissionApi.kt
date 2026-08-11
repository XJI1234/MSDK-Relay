package com.skycommand.relay.wayline.android

import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.waypoint3.WaypointMissionManager

internal class MsdkV5WaypointMissionApi(
    private val manager: WaypointMissionManager = WaypointMissionManager.getInstance(),
) : DjiWaypointMissionApi {
    init { manager.init() }

    override fun upload(path: String, completion: DjiUploadCompletion) {
        manager.pushKMZFileToAircraft(path, object : CommonCallbacks.CompletionCallbackWithProgress<Double> {
            override fun onProgressUpdate(progress: Double) = completion.progress(progress)
            override fun onSuccess() = completion.succeed()
            override fun onFailure(error: IDJIError) = completion.fail()
        })
    }

    override fun start(name: String, completion: DjiControlCompletion) = manager.startMission(name, completion.sdk())
    override fun pause(completion: DjiControlCompletion) = manager.pauseMission(completion.sdk())
    override fun resume(completion: DjiControlCompletion) = manager.resumeMission(completion.sdk())
    override fun stop(name: String, completion: DjiControlCompletion) = manager.stopMission(name, completion.sdk())
    override fun close() = Unit

    private fun DjiControlCompletion.sdk() = object : CommonCallbacks.CompletionCallback {
        override fun onSuccess() = succeed()
        override fun onFailure(error: IDJIError) = fail()
    }
}
