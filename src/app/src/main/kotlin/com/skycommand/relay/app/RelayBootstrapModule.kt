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
    private var gatewayStarted = false
    private var telemetryStarted = false
    private var gatewayActive = false
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
            } catch (failure: Exception) {
                report(RelayBootstrapDiagnosticKind.DEVICE_START_FAILURE)
                active = false
                stopInternal()
                throw failure
            }
            try {
                ports.startGateway()
                gatewayStarted = true
                syncTelemetry(true)
            } catch (failure: Exception) {
                report(RelayBootstrapDiagnosticKind.RELAY_START_FAILURE)
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
            syncTelemetry(propagateFailure)
        }
    }

    private fun syncTelemetry(propagateFailure: Boolean) {
        if (ports.sdkAvailability() == SdkAvailability.READY) {
            if (telemetryStarted) return
            telemetryStarted = true
            try {
                ports.startTelemetry()
                if (!active || !telemetryStarted) {
                    runCatching { ports.stopTelemetry() }
                    return
                }
                if (gatewayActive) ports.publishTelemetry()
            } catch (failure: Exception) {
                report(RelayBootstrapDiagnosticKind.RELAY_START_FAILURE)
                telemetryStarted = false
                stopTelemetry()
                if (propagateFailure) throw failure
            }
        } else if (telemetryStarted) {
            ports.markStreamUnavailable()
            ports.markMissionUnavailable()
            ports.markFlightControlUnavailable()
            ports.markDeviceSettingsUnavailable()
            telemetryStarted = false
            stopTelemetry()
        }
    }

    private fun onGatewayStateChanged(state: SessionState) {
        synchronized(lock) {
            gatewayActive = state == SessionState.ACTIVE
            if (gatewayActive && active && telemetryStarted) {
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
        val stopGateway = gatewayStarted.also { gatewayStarted = false }
        val stopTelemetry = telemetryStarted.also { telemetryStarted = false }
        gatewayActive = false
        if (stopGateway) stopGateway()
        if (stopTelemetry) stopTelemetry()
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
