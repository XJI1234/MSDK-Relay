package com.skycommand.relay.photo

import com.skycommand.relay.photo.executor.CameraMediaRecoveryPort

class CameraMediaReadiness(
    private val recovery: CameraMediaRecoveryPort = CameraMediaRecoveryPort { completion -> completion(true) },
) {
    private val lock = Any()
    private var sourceAvailable = false
    private var recoveryInFlight = false
    private var videoInputRecovered = false
    private var connectionGeneration = 0L
    private var activeMediaOperations = 0

    fun onVideoSourceChanged(available: Boolean) {
        val generation = synchronized(lock) {
            if (sourceAvailable == available) return
            sourceAvailable = available
            connectionGeneration += 1
            recoveryInFlight = available
            videoInputRecovered = false
            if (!available) return
            connectionGeneration
        }
        recovery.recover { recovered ->
            synchronized(lock) {
                if (!sourceAvailable || generation != connectionGeneration) return@recover
                recoveryInFlight = false
                videoInputRecovered = recovered
            }
        }
    }

    fun onCameraMediaBusy() = synchronized(lock) {
        activeMediaOperations += 1
    }

    fun onCameraMediaReleased() = synchronized(lock) {
        if (activeMediaOperations > 0) activeMediaOperations -= 1
    }

    fun isReady(): Boolean = synchronized(lock) {
        sourceAvailable && videoInputRecovered && !recoveryInFlight && activeMediaOperations == 0
    }
}
