package com.skycommand.relay.photo.executor

fun interface CameraMediaRecoveryPort {
    fun recover(completion: (Boolean) -> Unit)
}
