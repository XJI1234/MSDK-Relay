package com.skycommand.relay.app

import com.skycommand.relay.device.state.SdkAvailability
import com.skycommand.relay.gateway.session.SessionState
import com.skycommand.relay.runtime.bootstrap.BootstrapModule

fun interface CloseableRegistration {
    fun unregister()
}

interface RelayLifecyclePorts {
    fun sdkAvailability(): SdkAvailability
    fun onDeviceChanged(listener: () -> Unit): CloseableRegistration
    fun onGatewayStateChanged(listener: (SessionState) -> Unit): CloseableRegistration
    fun startDevice()
    fun stopDevice()
    fun startTelemetry()
    fun stopTelemetry()
    fun publishTelemetry()
    fun startGateway()
    fun stopGateway()
    fun closeFlightTelemetry()
    fun markStreamUnavailable()
    fun markMissionUnavailable()
}

class RelayBootstrapModule(
    private val ports: RelayLifecyclePorts,
) : BootstrapModule {
    override val name: String = "mobile-relay"
    private val lock = Any()
    private var active = false
    private var relayStarted = false
    private var deviceRegistration: CloseableRegistration? = null
    private var gatewayRegistration: CloseableRegistration? = null

    override fun start() {
        synchronized(lock) {
            check(!active) { "Relay bootstrap is already active" }
            active = true
            try {
                deviceRegistration = ports.onDeviceChanged { onDeviceChanged(false) }
                gatewayRegistration = ports.onGatewayStateChanged(::onGatewayStateChanged)
                ports.startDevice()
                onDeviceChanged(true)
            } catch (failure: Exception) {
                active = false
                stopInternal()
                throw failure
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            if (!active) return
            active = false
            stopInternal()
        }
    }

    private fun onDeviceChanged(propagateFailure: Boolean) {
        synchronized(lock) {
            if (!active) return
            if (ports.sdkAvailability() == SdkAvailability.READY) {
                if (relayStarted) return
                relayStarted = true
                try {
                    ports.startTelemetry()
                    if (!active || !relayStarted) {
                        runCatching { ports.stopTelemetry() }
                        return
                    }
                    ports.startGateway()
                } catch (failure: Exception) {
                    relayStarted = false
                    runCatching { ports.stopGateway() }
                    runCatching { ports.stopTelemetry() }
                    if (propagateFailure) throw failure
                }
            } else if (relayStarted) {
                ports.markStreamUnavailable()
                ports.markMissionUnavailable()
            }
        }
    }

    private fun onGatewayStateChanged(state: SessionState) {
        synchronized(lock) {
            if (state == SessionState.ACTIVE && active && relayStarted) {
                ports.publishTelemetry()
            }
        }
    }

    private fun stopInternal() {
        gatewayRegistration?.unregister()
        gatewayRegistration = null
        deviceRegistration?.unregister()
        deviceRegistration = null
        val stopRelay = relayStarted.also { relayStarted = false }
        if (stopRelay) {
            runCatching { ports.stopGateway() }
            runCatching { ports.stopTelemetry() }
        }
        runCatching { ports.closeFlightTelemetry() }
        runCatching { ports.stopDevice() }
    }
}
