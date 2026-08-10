package com.skycommand.relay.telemetry.snapshot

import com.skycommand.relay.device.capability.DeviceCapabilities
import com.skycommand.relay.device.capability.DeviceCapabilityReader
import com.skycommand.relay.device.state.DeviceSnapshot
import com.skycommand.relay.device.state.LinkState
import com.skycommand.relay.device.state.PairingState
import com.skycommand.relay.device.state.SdkAvailability

data class TelemetrySnapshot(
    val deviceRevision: Long,
    val sdkAvailability: SdkAvailability,
    val remoteController: LinkState,
    val aircraft: LinkState,
    val flightController: LinkState,
    val pairing: PairingState,
    val remoteControllerModel: String?,
    val aircraftModel: String?,
    val capabilities: DeviceCapabilities,
)

object SnapshotAssembler {
    fun assemble(device: DeviceSnapshot): TelemetrySnapshot = TelemetrySnapshot(
        deviceRevision = device.revision,
        sdkAvailability = device.sdkAvailability,
        remoteController = device.remoteController,
        aircraft = device.aircraft,
        flightController = device.flightController,
        pairing = device.pairing,
        remoteControllerModel = device.remoteControllerModel,
        aircraftModel = device.aircraftModel,
        capabilities = DeviceCapabilityReader.read(device),
    )
}
