package com.skycommand.relay.telemetry.snapshot

import com.skycommand.relay.device.state.DeviceSnapshot
import com.skycommand.relay.device.state.LinkState
import com.skycommand.relay.device.state.PairingState
import com.skycommand.relay.device.state.SdkAvailability
import com.skycommand.relay.telemetry.capability.TelemetryCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SnapshotAssemblerContractTest {
    @Test
    fun assemblesAStableSafeSnapshotFromOneDeviceSnapshot() {
        val input = DeviceSnapshot(
            revision = 7,
            sdkAvailability = SdkAvailability.READY,
            remoteController = LinkState.CONNECTED,
            aircraft = LinkState.CONNECTED,
            flightController = LinkState.CONNECTED,
            pairing = PairingState.PAIRED,
            remoteControllerModel = "RC Plus",
            aircraftModel = "Matrice 4",
        )

        val result = SnapshotAssembler.assemble(input)

        assertEquals(7, result.deviceRevision)
        assertEquals(SdkAvailability.READY, result.sdkAvailability)
        assertEquals("Matrice 4", result.aircraftModel)
        assertIs<TelemetryCapabilities>(result.capabilities)
        assertEquals(result, SnapshotAssembler.assemble(input))
    }

    @Test
    fun preservesExplicitUnknownAndOptionalValues() {
        val input = DeviceSnapshot(
            revision = 0,
            sdkAvailability = SdkAvailability.STOPPED,
            remoteController = LinkState.DISCONNECTED,
            aircraft = LinkState.DISCONNECTED,
            flightController = LinkState.DISCONNECTED,
            pairing = PairingState.UNKNOWN,
            remoteControllerModel = null,
            aircraftModel = null,
        )

        val result = SnapshotAssembler.assemble(input)

        assertEquals(null, result.aircraftModel)
        assertEquals(PairingState.UNKNOWN, result.pairing)
        assertEquals(false, result.capabilities.waypointMission)
    }
}
