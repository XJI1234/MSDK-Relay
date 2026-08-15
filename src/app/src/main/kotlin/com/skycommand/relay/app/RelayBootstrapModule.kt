package com.skycommand.relay.app

import com.skycommand.relay.device.state.SdkAvailability
import com.skycommand.relay.gateway.session.SessionState
import com.skycommand.relay.runtime.bootstrap.BootstrapModule

fun interface CloseableRegistration {
    fun unregister()
}

enum class RelayBootstrapDiagnosticKind {
    DEVICE_START_FAILURE,
    RELAY_START_FAILURE,
    REGISTRATION_RELEASE_FAILURE,
    GATEWAY_STOP_FAILURE,
    TELEMETRY_STOP_FAILURE,
    FLIGHT_TELEMETRY_CLOSE_FAILURE,
    DEVICE_STOP_FAILURE,
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
    fun markFlightControlUnavailable()
    fun markDeviceSettingsUnavailable()
    fun reportDiagnostic(kind: RelayBootstrapDiagnosticKind)
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
                report(RelayBootstrapDiagnosticKind.DEVICE_START_FAILURE)
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
                    report(RelayBootstrapDiagnosticKind.RELAY_START_FAILURE)
                    relayStarted = false
                    stopGateway()
                    stopTelemetry()
                    if (propagateFailure) throw failure
                }
            } else if (relayStarted) {
                ports.markStreamUnavailable()
                ports.markMissionUnavailable()
                ports.markFlightControlUnavailable()
                ports.markDeviceSettingsUnavailable()
                relayStarted = false
                stopGateway()
                stopTelemetry()
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
        runCatching { gatewayRegistration?.unregister() }
            .onFailure { report(RelayBootstrapDiagnosticKind.REGISTRATION_RELEASE_FAILURE) }
        gatewayRegistration = null
        runCatching { deviceRegistration?.unregister() }
            .onFailure { report(RelayBootstrapDiagnosticKind.REGISTRATION_RELEASE_FAILURE) }
        deviceRegistration = null
        val stopRelay = relayStarted.also { relayStarted = false }
        if (stopRelay) {
            stopGateway()
            stopTelemetry()
        }
        runCatching { ports.closeFlightTelemetry() }
            .onFailure { report(RelayBootstrapDiagnosticKind.FLIGHT_TELEMETRY_CLOSE_FAILURE) }
        runCatching { ports.stopDevice() }
            .onFailure { report(RelayBootstrapDiagnosticKind.DEVICE_STOP_FAILURE) }
    }

    private fun stopGateway() {
        runCatching { ports.stopGateway() }
            .onFailure { report(RelayBootstrapDiagnosticKind.GATEWAY_STOP_FAILURE) }
    }

    private fun stopTelemetry() {
        runCatching { ports.stopTelemetry() }
            .onFailure { report(RelayBootstrapDiagnosticKind.TELEMETRY_STOP_FAILURE) }
    }

    private fun report(kind: RelayBootstrapDiagnosticKind) {
        runCatching { ports.reportDiagnostic(kind) }
    }
}
