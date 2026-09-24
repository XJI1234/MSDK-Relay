package com.skycommand.relay.photo

import com.skycommand.relay.photo.executor.CameraMediaRecoveryPort

class CameraMediaReadiness(
    private val recovery: CameraMediaRecoveryPort,
) {
    private val lock = Any()
    private var sourceAvailable = false
    private var videoInputRecovered = false
    private var recoveryInFlight = false
    private var connectionGeneration = 0L
    private var activeMediaOperations = 0

    fun onVideoSourceChanged(available: Boolean) {
        val generation = synchronized(lock) {
            if (sourceAvailable == available) return
            sourceAvailable = available
            videoInputRecovered = false
            recoveryInFlight = false
            connectionGeneration += 1
            if (!available) return
            recoveryInFlight = true
            connectionGeneration
        }
        recovery.recover { recovered ->
            synchronized(lock) {
                if (generation != connectionGeneration || !sourceAvailable) return@recover
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
