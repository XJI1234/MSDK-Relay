package com.skycommand.relay.device.capability

import com.skycommand.relay.device.state.DeviceSnapshot
import com.skycommand.relay.device.state.LinkState
import com.skycommand.relay.device.state.PairingState
import com.skycommand.relay.device.state.SdkAvailability
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceCapabilityReaderContractTest {

    @Test
    fun returnsNoCapabilitiesForTheInitialSnapshot() {
        val capabilities = DeviceCapabilityReader.read(initialSnapshot())

        assertEquals(DeviceCapabilities(false, false, false, false, false), capabilities)
    }

    @Test
    fun exposesOnlyTheCapabilitiesWhoseOwnFactsAreReady() {
        val capabilities = DeviceCapabilityReader.read(
            initialSnapshot().copy(
                sdkAvailability = SdkAvailability.READY,
                remoteController = LinkState.CONNECTED,
                aircraft = LinkState.CONNECTED,
                airLink = LinkState.CONNECTED,
                camera = LinkState.CONNECTED,
                flightController = LinkState.CONNECTED,
                pairing = PairingState.PAIRED,
            ),
        )

        assertEquals(
            DeviceCapabilities(
                canStartPairing = false,
                canStopPairing = true,
                canReadTelemetry = true,
                canStreamVideo = true,
                canRunWayline = true,
            ),
            capabilities,
        )
    }

    @Test
    fun pairingCapabilitiesDoNotDependOnTelemetryOrWaylineReadiness() {
        val snapshot = initialSnapshot().copy(
            sdkAvailability = SdkAvailability.READY,
            remoteController = LinkState.CONNECTED,
            pairing = PairingState.IDLE,
        )

        assertEquals(
            DeviceCapabilities(true, false, false, false, false),
            DeviceCapabilityReader.read(snapshot),
        )
    }

    @Test
    fun allowsPairingRetryAfterFailureWhenAircraftIsDisconnected() {
        val snapshot = initialSnapshot().copy(
            sdkAvailability = SdkAvailability.READY,
            remoteController = LinkState.CONNECTED,
            pairing = PairingState.FAILED,
        )

        assertEquals(
            DeviceCapabilities(true, false, false, false, false),
            DeviceCapabilityReader.read(snapshot),
        )
    }

    @Test
    fun allowsPairingStartAfterStoppingWhenAircraftIsDisconnected() {
        val snapshot = initialSnapshot().copy(
            sdkAvailability = SdkAvailability.READY,
            remoteController = LinkState.CONNECTED,
            pairing = PairingState.STOPPING,
        )

        assertEquals(
            DeviceCapabilities(true, true, false, false, false),
            DeviceCapabilityReader.read(snapshot),
        )
    }

    @Test
    fun allowsReplacingAnAlreadyPairedAircraftWhenTheFlightControllerIsDisconnected() {
        val snapshot = initialSnapshot().copy(
            sdkAvailability = SdkAvailability.READY,
            remoteController = LinkState.CONNECTED,
            aircraft = LinkState.CONNECTED,
            flightController = LinkState.DISCONNECTED,
            pairing = PairingState.PAIRED,
        )

        assertEquals(true, DeviceCapabilityReader.read(snapshot).canStartPairing)
    }

    @Test
    fun refusesPairingWhenFlightControllerIsAlreadyConnected() {
        val snapshot = initialSnapshot().copy(
            sdkAvailability = SdkAvailability.READY,
            remoteController = LinkState.CONNECTED,
            aircraft = LinkState.DISCONNECTED,
            flightController = LinkState.CONNECTED,
            pairing = PairingState.IDLE,
        )

        assertEquals(
            DeviceCapabilities(false, false, true, false, true),
            DeviceCapabilityReader.read(snapshot),
        )
    }

    @Test
    fun waylineCapabilityFollowsFlightReadyFactsNotPairingState() {
        val snapshot = initialSnapshot().copy(
            sdkAvailability = SdkAvailability.READY,
            remoteController = LinkState.CONNECTED,
            aircraft = LinkState.CONNECTED,
            airLink = LinkState.CONNECTED,
            camera = LinkState.CONNECTED,
            flightController = LinkState.CONNECTED,
            pairing = PairingState.IDLE,
        )

        assertEquals(
            DeviceCapabilities(
                canStartPairing = false,
                canStopPairing = false,
                canReadTelemetry = true,
                canStreamVideo = true,
                canRunWayline = true,
            ),
            DeviceCapabilityReader.read(snapshot),
        )
    }

    @Test
    fun videoCapabilityRequiresAirLinkAndPrimaryCameraButNotFlightController() {
        val streamingFacts = initialSnapshot().copy(
            sdkAvailability = SdkAvailability.READY,
            aircraft = LinkState.DISCONNECTED,
            airLink = LinkState.CONNECTED,
            camera = LinkState.CONNECTED,
            flightController = LinkState.DISCONNECTED,
        )

        assertEquals(true, DeviceCapabilityReader.read(streamingFacts).canStreamVideo)
        assertEquals(false, DeviceCapabilityReader.read(streamingFacts.copy(airLink = LinkState.UNKNOWN)).canStreamVideo)
        assertEquals(false, DeviceCapabilityReader.read(streamingFacts.copy(camera = LinkState.DISCONNECTED)).canStreamVideo)
    }

    @Test
    fun ignoresProductKeyConnectionForEveryCapability() {
        val ready = initialSnapshot().copy(
            sdkAvailability = SdkAvailability.READY,
            remoteController = LinkState.CONNECTED,
            aircraft = LinkState.DISCONNECTED,
            airLink = LinkState.CONNECTED,
            camera = LinkState.CONNECTED,
            flightController = LinkState.CONNECTED,
            pairing = PairingState.IDLE,
        )

        assertEquals(
            DeviceCapabilities(
                canStartPairing = false,
                canStopPairing = false,
                canReadTelemetry = true,
                canStreamVideo = true,
                canRunWayline = true,
            ),
            DeviceCapabilityReader.read(ready),
        )
    }

    @Test
    fun neverAllowsFlightCapabilitiesWhenTheSdkIsNotReady() {
        val snapshot = initialSnapshot().copy(
            sdkAvailability = SdkAvailability.FAILED,
            remoteController = LinkState.CONNECTED,
            aircraft = LinkState.CONNECTED,
            flightController = LinkState.CONNECTED,
            pairing = PairingState.PAIRED,
        )

        assertEquals(
            DeviceCapabilities(false, true, false, false, false),
            DeviceCapabilityReader.read(snapshot),
        )
    }

    private fun initialSnapshot() = DeviceSnapshot(
        revision = 0,
        sdkAvailability = SdkAvailability.STOPPED,
        remoteController = LinkState.DISCONNECTED,
        aircraft = LinkState.DISCONNECTED,
        flightController = LinkState.DISCONNECTED,
        pairing = PairingState.UNKNOWN,
        remoteControllerModel = null,
        aircraftModel = null,
    )
}
