package com.skycommand.relay.photo

class CameraMediaReadiness {
    private val lock = Any()
    private var sourceAvailable = false
    private var activeMediaOperations = 0

    fun onVideoSourceChanged(available: Boolean) {
        synchronized(lock) { sourceAvailable = available }
    }

    fun onCameraMediaBusy() = synchronized(lock) {
        activeMediaOperations += 1
    }

    fun onCameraMediaReleased() = synchronized(lock) {
        if (activeMediaOperations > 0) activeMediaOperations -= 1
    }

    fun isReady(): Boolean = synchronized(lock) {
        sourceAvailable && activeMediaOperations == 0
    }
}
