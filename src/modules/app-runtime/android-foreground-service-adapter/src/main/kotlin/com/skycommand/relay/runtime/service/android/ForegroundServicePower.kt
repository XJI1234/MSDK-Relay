package com.skycommand.relay.runtime.service.android

internal interface ServicePowerLock {
    fun acquire()
    fun release()
}

internal class ForegroundServicePower(
    private val cpu: ServicePowerLock,
    private val wifi: ServicePowerLock,
) {
    private var cpuHeld = false
    private var wifiHeld = false

    fun acquire() {
        if (cpuHeld && wifiHeld) return
        if (!cpuHeld) {
            cpu.acquire()
            cpuHeld = true
        }
        try {
            if (!wifiHeld) {
                wifi.acquire()
                wifiHeld = true
            }
        } catch (failure: Throwable) {
            try { release() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    fun release() {
        var failure: Throwable? = null
        if (wifiHeld) {
            try { wifi.release(); wifiHeld = false }
            catch (error: Throwable) { failure = error }
        }
        if (cpuHeld) {
            try { cpu.release(); cpuHeld = false }
            catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }
}
