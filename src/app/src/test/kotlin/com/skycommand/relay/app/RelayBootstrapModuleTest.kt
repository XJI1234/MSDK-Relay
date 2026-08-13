package com.skycommand.relay.app

import com.skycommand.relay.device.state.SdkAvailability
import com.skycommand.relay.gateway.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RelayBootstrapModuleTest {
    @Test fun startsDeviceFirstAndDefersRelayUntilSdkReady() {
        val ports = FakePorts()
        val module = RelayBootstrapModule(ports)
        module.start()
        assertEquals(listOf("device-listen", "gateway-listen", "device-start"), ports.events)

        ports.sdk = SdkAvailability.READY
        ports.deviceChanged()
        ports.gatewayStateChanged(SessionState.ACTIVE)
        assertEquals(
            listOf("device-listen", "gateway-listen", "device-start", "telemetry-start", "gateway-start", "telemetry-publish"),
            ports.events,
        )

        ports.deviceChanged()
        assertEquals(1, ports.events.count { it == "gateway-start" })
    }

    @Test fun invalidatesDeviceBoundFeaturesAndStopsInReverseOrder() {
        val ports = FakePorts().apply { sdk = SdkAvailability.READY }
        val module = RelayBootstrapModule(ports)
        module.start()
        ports.sdk = SdkAvailability.FAILED
        ports.deviceChanged()
        module.stop()

        assertEquals(
            listOf(
                "device-listen", "gateway-listen", "device-start", "telemetry-start", "gateway-start",
                "stream-unavailable", "mission-unavailable", "flight-control-unavailable", "device-settings-unavailable", "gateway-unlisten", "device-unlisten",
                "gateway-stop", "telemetry-stop", "flight-close", "device-stop",
            ),
            ports.events,
        )
    }

    @Test fun failedStartCleansRegistrationsAndAllowsACompleteRetry() {
        val ports = FakePorts().apply { failNextStart = true }
        val module = RelayBootstrapModule(ports)
        runCatching { module.start() }
        module.start()
        assertEquals(2, ports.events.count { it == "device-start" })
        assertEquals(1, ports.events.count { it == "device-stop" })
    }

    @Test fun asynchronousRelayFailureRollsBackWithoutEscapingAndCanRetry() {
        val ports = FakePorts()
        val module = RelayBootstrapModule(ports)
        module.start()
        ports.sdk = SdkAvailability.READY
        ports.failNextGatewayStart = true

        val result = runCatching { ports.deviceChanged() }

        assertFalse(result.isFailure)
        assertEquals(1, ports.events.count { it == "gateway-stop" })
        assertEquals(1, ports.events.count { it == "telemetry-stop" })
        assertEquals(1, ports.events.count { it == "diagnostic:RELAY_START_FAILURE" })
        ports.deviceChanged()
        assertEquals(2, ports.events.count { it == "gateway-start" })
    }

    @Test fun reentrantStopCannotStartGatewayAfterTelemetryReturns() {
        val ports = FakePorts()
        lateinit var module: RelayBootstrapModule
        ports.afterTelemetryStart = { module.stop() }
        module = RelayBootstrapModule(ports)
        module.start()
        ports.sdk = SdkAvailability.READY

        ports.deviceChanged()

        assertEquals(0, ports.events.count { it == "gateway-start" })
        assertEquals(2, ports.events.count { it == "telemetry-stop" })
        assertEquals(1, ports.events.count { it == "device-stop" })
    }

    @Test fun callbacksAfterStopCannotPublishOrRestartRelay() {
        val ports = FakePorts().apply { sdk = SdkAvailability.READY }
        val module = RelayBootstrapModule(ports)
        module.start()
        val staleDeviceCallback = ports.requireDeviceListener()
        val staleGatewayCallback = ports.requireGatewayListener()
        module.stop()

        staleDeviceCallback()
        staleGatewayCallback(SessionState.ACTIVE)

        assertEquals(1, ports.events.count { it == "gateway-start" })
        assertEquals(0, ports.events.count { it == "telemetry-publish" })
    }

    private class FakePorts : RelayLifecyclePorts {
        val events = mutableListOf<String>()
        var sdk = SdkAvailability.STARTING
        var failNextStart = false
        var failNextGatewayStart = false
        var afterTelemetryStart: (() -> Unit)? = null
        private var deviceListener: (() -> Unit)? = null
        private var gatewayListener: ((SessionState) -> Unit)? = null

        fun deviceChanged() = requireNotNull(deviceListener).invoke()
        fun gatewayStateChanged(state: SessionState) = requireNotNull(gatewayListener).invoke(state)
        fun requireDeviceListener() = requireNotNull(deviceListener)
        fun requireGatewayListener() = requireNotNull(gatewayListener)
        override fun sdkAvailability() = sdk
        override fun onDeviceChanged(listener: () -> Unit) = CloseableRegistration {
            events += "device-unlisten"; deviceListener = null
        }.also { events += "device-listen"; deviceListener = listener }
        override fun onGatewayStateChanged(listener: (SessionState) -> Unit) = CloseableRegistration {
            events += "gateway-unlisten"; gatewayListener = null
        }.also { events += "gateway-listen"; gatewayListener = listener }
        override fun startDevice() {
            events += "device-start"
            if (failNextStart) {
                failNextStart = false
                error("start failed")
            }
        }
        override fun stopDevice() { events += "device-stop" }
        override fun startTelemetry() { events += "telemetry-start"; afterTelemetryStart?.invoke() }
        override fun stopTelemetry() { events += "telemetry-stop" }
        override fun publishTelemetry() { events += "telemetry-publish" }
        override fun startGateway() {
            events += "gateway-start"
            if (failNextGatewayStart) {
                failNextGatewayStart = false
                error("gateway start failed")
            }
        }
        override fun reportDiagnostic(kind: RelayBootstrapDiagnosticKind) {
            events += "diagnostic:${kind.name}"
        }
        override fun stopGateway() { events += "gateway-stop" }
        override fun closeFlightTelemetry() { events += "flight-close" }
        override fun markStreamUnavailable() { events += "stream-unavailable" }
        override fun markMissionUnavailable() { events += "mission-unavailable" }
        override fun markFlightControlUnavailable() { events += "flight-control-unavailable" }
        override fun markDeviceSettingsUnavailable() { events += "device-settings-unavailable" }
    }
}
