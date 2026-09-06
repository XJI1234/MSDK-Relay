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
    fun resetTelemetryPublicationBaseline() = Unit
    fun publishTelemetry()
    fun publishLinkSnapshot()
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
    private var deviceBoundFeaturesInvalidated = false
    private var deviceRegistration: CloseableRegistration? = null
    private var gatewayRegistration: CloseableRegistration? = null
    private var startInProgress = false
    private var stopRequested = false
    private var deviceStarted = false

    override fun start() {
        synchronized(lock) {
            check(!active && !startInProgress) { "Relay bootstrap is already active or starting" }
            active = true
            startInProgress = true
            stopRequested = false
            deviceBoundFeaturesInvalidated = false
            deviceStarted = false
        }

        try {
            val deviceRegistration = ports.onDeviceChanged { onDeviceChanged(false) }
            installDeviceRegistration(deviceRegistration)
            val gatewayRegistration = ports.onGatewayStateChanged(::onGatewayStateChanged)
            installGatewayRegistration(gatewayRegistration)
            if (!isActive()) return

            synchronized(lock) { if (active) deviceStarted = true }
            ports.startDevice()
            if (!isActive()) return

            synchronized(lock) {
                if (active) gatewayStarted = true
            }
            if (!isActive()) return
            ports.startGateway()
            if (isActive()) syncTelemetry(true)
        } catch (failure: Exception) {
            val diagnostic = synchronized(lock) {
                val gatewayWasStarted = gatewayStarted
                active = false
                startInProgress = false
                stopRequested = false
                if (gatewayWasStarted) RelayBootstrapDiagnosticKind.RELAY_START_FAILURE
                else RelayBootstrapDiagnosticKind.DEVICE_START_FAILURE
            }
            report(diagnostic)
            stopInternal()
            throw failure
        } finally {
            val shouldStop = synchronized(lock) {
                startInProgress = false
                stopRequested && !active
            }
            if (shouldStop) stopInternal()
        }
    }

    override fun stop() {
        val shouldStop = synchronized(lock) {
            if (!active) {
                false
            } else if (startInProgress) {
                active = false
                stopRequested = true
                false
            } else {
                active = false
                true
            }
        }
        if (shouldStop) stopInternal()
    }

    private fun onDeviceChanged(propagateFailure: Boolean) {
        if (!isActive()) return
        syncTelemetry(propagateFailure)
    }

    private fun syncTelemetry(propagateFailure: Boolean) {
        val sdkReady = runCatching { ports.sdkAvailability() == SdkAvailability.READY }.getOrDefault(false)
        if (sdkReady) {
            val shouldStart = synchronized(lock) {
                if (!active || telemetryStarted) {
                    false
                } else {
                    deviceBoundFeaturesInvalidated = false
                    telemetryStarted = true
                    true
                }
            }
            if (!shouldStart) return
            try {
                ports.startTelemetry()
                val shouldContinue = synchronized(lock) { active && telemetryStarted }
                if (!shouldContinue) {
                    runCatching { ports.stopTelemetry() }
                    synchronized(lock) { telemetryStarted = false }
                    return
                }
                val shouldPublish = synchronized(lock) { gatewayActive }
                if (shouldPublish) ports.publishTelemetry()
            } catch (failure: Exception) {
                report(RelayBootstrapDiagnosticKind.RELAY_START_FAILURE)
                synchronized(lock) { telemetryStarted = false }
                stopTelemetry()
                if (propagateFailure) throw failure
            }
        } else {
            val shouldInvalidate = synchronized(lock) {
                if (!active) return
                val wasStarted = telemetryStarted
                telemetryStarted = false
                wasStarted
            }
            if (shouldInvalidate) {
                invalidateDeviceBoundFeatures()
                stopTelemetry()
            }
            publishAvailableSnapshot()
        }
    }

    private fun onGatewayStateChanged(state: SessionState) {
        val action = synchronized(lock) {
            if (!active) return
            val wasActive = gatewayActive
            gatewayActive = state == SessionState.ACTIVE
            when {
                gatewayActive && !wasActive -> GatewayStateAction.ACTIVATED
                !gatewayActive && wasActive -> GatewayStateAction.DEACTIVATED
                gatewayActive -> GatewayStateAction.PUBLISH
                else -> GatewayStateAction.NONE
            }
        }
        when (action) {
            GatewayStateAction.ACTIVATED -> {
                ports.resetTelemetryPublicationBaseline()
                publishAvailableSnapshot()
            }
            GatewayStateAction.DEACTIVATED -> ports.markStreamUnavailable()
            GatewayStateAction.PUBLISH -> publishAvailableSnapshot()
            GatewayStateAction.NONE -> Unit
        }
    }

    private fun publishAvailableSnapshot() {
        val publication = synchronized(lock) {
            if (!active || !gatewayActive) null else if (telemetryStarted) "telemetry" else "links"
        }
        when (publication) {
            "telemetry" -> ports.publishTelemetry()
            "links" -> ports.publishLinkSnapshot()
            null -> Unit
        }
    }

    private fun stopInternal() {
        val work = synchronized(lock) {
            StopWork(
                invalidateDeviceBoundFeatures = !deviceBoundFeaturesInvalidated,
                gatewayRegistration = gatewayRegistration.also { gatewayRegistration = null },
                deviceRegistration = deviceRegistration.also { deviceRegistration = null },
                stopGateway = gatewayStarted.also { gatewayStarted = false },
                stopTelemetry = telemetryStarted.also { telemetryStarted = false },
                stopDevice = deviceStarted.also { deviceStarted = false },
            ).also {
                deviceBoundFeaturesInvalidated = true
                gatewayActive = false
            }
        }
        if (work.invalidateDeviceBoundFeatures) {
            ports.markStreamUnavailable()
            ports.markMissionUnavailable()
            ports.markFlightControlUnavailable()
            ports.markDeviceSettingsUnavailable()
        }
        runCatching { work.gatewayRegistration?.unregister() }
            .onFailure { report(RelayBootstrapDiagnosticKind.REGISTRATION_RELEASE_FAILURE) }
        runCatching { work.deviceRegistration?.unregister() }
            .onFailure { report(RelayBootstrapDiagnosticKind.REGISTRATION_RELEASE_FAILURE) }
        if (work.stopGateway) stopGateway()
        if (work.stopTelemetry) stopTelemetry()
        runCatching { ports.closeFlightTelemetry() }
            .onFailure { report(RelayBootstrapDiagnosticKind.FLIGHT_TELEMETRY_CLOSE_FAILURE) }
        if (work.stopDevice) {
            runCatching { ports.stopDevice() }
                .onFailure { report(RelayBootstrapDiagnosticKind.DEVICE_STOP_FAILURE) }
        }
    }

    private data class StopWork(
        val invalidateDeviceBoundFeatures: Boolean,
        val gatewayRegistration: CloseableRegistration?,
        val deviceRegistration: CloseableRegistration?,
        val stopGateway: Boolean,
        val stopTelemetry: Boolean,
        val stopDevice: Boolean,
    )

    private fun invalidateDeviceBoundFeatures() {
        val shouldInvalidate = synchronized(lock) {
            if (deviceBoundFeaturesInvalidated) false else {
                deviceBoundFeaturesInvalidated = true
                true
            }
        }
        if (!shouldInvalidate) return
        ports.markStreamUnavailable()
        ports.markMissionUnavailable()
        ports.markFlightControlUnavailable()
        ports.markDeviceSettingsUnavailable()
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

    private fun isActive(): Boolean = synchronized(lock) { active }

    private fun installDeviceRegistration(registration: CloseableRegistration) {
        val keep = synchronized(lock) {
            if (active) {
                deviceRegistration = registration
                true
            } else false
        }
        if (!keep) runCatching { registration.unregister() }
    }

    private fun installGatewayRegistration(registration: CloseableRegistration) {
        val keep = synchronized(lock) {
            if (active) {
                gatewayRegistration = registration
                true
            } else false
        }
        if (!keep) runCatching { registration.unregister() }
    }

    private enum class GatewayStateAction { ACTIVATED, DEACTIVATED, PUBLISH, NONE }
}
