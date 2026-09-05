package com.skycommand.relay.wayline.android

import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.waypoint3.WaypointMissionManager
import dji.v5.manager.aircraft.waypoint3.WaypointMissionExecuteStateListener
import dji.v5.manager.aircraft.waypoint3.model.WaypointMissionExecuteState
import com.skycommand.relay.wayline.executor.MissionControlFailure
import com.skycommand.relay.wayline.uploader.MissionUploadFailure

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
            override fun onFailure(error: IDJIError) = completion.fail(error.toMissionUploadFailure())
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
    override fun close() = manager.destroy()

    private fun DjiControlCompletion.sdk() = object : CommonCallbacks.CompletionCallback {
        override fun onSuccess() = succeed()
        override fun onFailure(error: IDJIError) = fail(error.toMissionControlFailure())
    }

    private fun IDJIError.toMissionControlFailure(): MissionControlFailure =
        MissionControlFailure.fromDjiError(
            runCatching { errorCode() }.getOrNull(),
            runCatching { description() }.getOrNull(),
        )

    private fun IDJIError.toMissionUploadFailure(): MissionUploadFailure =
        MissionUploadFailure.fromDjiError(
            runCatching { errorCode() }.getOrNull(),
            runCatching { description() }.getOrNull(),
        )

    private fun ensureInitialized() = synchronized(lock) {
        if (!initialized) { manager.init(); initialized = true }
    }

    private fun WaypointMissionExecuteState.toDjiExecutionState(): DjiMissionExecutionState =
        mapWaypointMissionStateName(name)
}

/** DJI enum names are converted here so the terminal-state policy is unit-testable without a flight stack. */
internal fun mapWaypointMissionStateName(name: String): DjiMissionExecutionState = when (name) {
    "IDLE" -> DjiMissionExecutionState.IDLE
    "READY" -> DjiMissionExecutionState.READY
    "UPLOADING" -> DjiMissionExecutionState.UPLOADING
    "PREPARING" -> DjiMissionExecutionState.PREPARING
    "RECOVERING" -> DjiMissionExecutionState.RECOVERING
    "ENTER_WAYLINE" -> DjiMissionExecutionState.ENTER_WAYLINE
    "EXECUTING" -> DjiMissionExecutionState.EXECUTING
    "PAUSED" -> DjiMissionExecutionState.PAUSED
    "INTERRUPTED" -> DjiMissionExecutionState.INTERRUPTED
    "FINISHED" -> DjiMissionExecutionState.FINISHED
    "RETURN_TO_START_POINT" -> DjiMissionExecutionState.RETURN_TO_START_POINT
    "DISCONNECTED" -> DjiMissionExecutionState.DISCONNECTED
    "NOT_SUPPORTED" -> DjiMissionExecutionState.NOT_SUPPORTED
    "UNKNOWN" -> DjiMissionExecutionState.UNKNOWN
    else -> DjiMissionExecutionState.UNKNOWN
}
