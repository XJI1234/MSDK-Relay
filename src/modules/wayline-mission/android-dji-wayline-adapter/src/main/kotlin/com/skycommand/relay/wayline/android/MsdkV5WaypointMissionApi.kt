package com.skycommand.relay.wayline.android

import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.waypoint3.WaypointMissionManager
import dji.v5.manager.aircraft.waypoint3.WaypointMissionExecuteStateListener
import dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState

internal class MsdkV5WaypointMissionApi(
    private val manager: WaypointMissionManager = WaypointMissionManager.getInstance(),
) : DjiWaypointMissionApi {
    private val lock = Any()
    private var initialized = false

    override fun upload(path: String, completion: DjiUploadCompletion) {
        ensureInitialized()
        manager.pushKMZFileToAircraft(path, object : CommonCallbacks.CompletionCallbackWithProgress<Double> {
            override fun onProgressUpdate(progress: Double) = completion.progress(progress)
            override fun onSuccess() = completion.succeed()
            override fun onFailure(error: IDJIError) = completion.fail()
        })
    }

    override fun start(name: String, completion: DjiControlCompletion) { ensureInitialized(); manager.startMission(name, completion.sdk()) }
    override fun pause(completion: DjiControlCompletion) { ensureInitialized(); manager.pauseMission(completion.sdk()) }
    override fun resume(completion: DjiControlCompletion) { ensureInitialized(); manager.resumeMission(completion.sdk()) }
    override fun stop(name: String, completion: DjiControlCompletion) { ensureInitialized(); manager.stopMission(name, completion.sdk()) }
    override fun onExecutionState(listener: (DjiMissionExecutionState) -> Unit): DjiExecutionStateRegistration {
        ensureInitialized()
        val sdkListener = WaypointMissionExecuteStateListener { state -> listener(state.toDjiExecutionState()) }
        manager.addWaypointMissionExecuteStateListener(sdkListener)
        return DjiExecutionStateRegistration { manager.removeWaypointMissionExecuteStateListener(sdkListener) }
    }
    override fun close() = Unit

    private fun DjiControlCompletion.sdk() = object : CommonCallbacks.CompletionCallback {
        override fun onSuccess() = succeed()
        override fun onFailure(error: IDJIError) = fail()
    }

    private fun ensureInitialized() = synchronized(lock) {
        if (!initialized) { manager.init(); initialized = true }
    }

    private fun WaypointMissionExecuteState.toDjiExecutionState(): DjiMissionExecutionState = when (this) {
        WaypointMissionExecuteState.PREPARING,
        WaypointMissionExecuteState.UPLOADING,
        WaypointMissionExecuteState.RECOVERING -> DjiMissionExecutionState.PREPARING
        WaypointMissionExecuteState.ENTER_WAYLINE -> DjiMissionExecutionState.ENTER_WAYLINE
        WaypointMissionExecuteState.EXECUTING -> DjiMissionExecutionState.EXECUTING
        WaypointMissionExecuteState.INTERRUPTED -> DjiMissionExecutionState.INTERRUPTED
        WaypointMissionExecuteState.FINISHED,
        WaypointMissionExecuteState.RETURN_TO_START_POINT -> DjiMissionExecutionState.COMPLETED
        WaypointMissionExecuteState.DISCONNECTED -> DjiMissionExecutionState.DISCONNECTED
        WaypointMissionExecuteState.IDLE,
        WaypointMissionExecuteState.READY -> DjiMissionExecutionState.IDLE
        WaypointMissionExecuteState.NOT_SUPPORTED,
        WaypointMissionExecuteState.UNKNOWN -> DjiMissionExecutionState.UNKNOWN
    }
}
