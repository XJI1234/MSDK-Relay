package com.skycommand.relay.photo.executor

data class CameraMediaStateSnapshot(
    val cameraMode: String?,
    val playingBack: Boolean?,
)

fun interface CameraMediaRecoveryPort {
    fun recover(completion: (Boolean) -> Unit)

    fun recoverAfterZeroFrameStart(completion: (Boolean) -> Unit) = recover(completion)

    fun inspectBeforeZeroFrameRecovery(completion: (CameraMediaStateSnapshot) -> Unit) =
        completion(CameraMediaStateSnapshot(cameraMode = null, playingBack = null))
}
