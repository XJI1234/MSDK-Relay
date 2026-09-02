package com.skycommand.relay.device.capability

import com.skycommand.relay.device.state.DeviceSnapshot
import com.skycommand.relay.device.state.LinkState
import com.skycommand.relay.device.state.PairingState
import com.skycommand.relay.device.state.SdkAvailability

data class DeviceCapabilities(
    val canStartPairing: Boolean,
    val canStopPairing: Boolean,
    val canReadTelemetry: Boolean,
    val canStreamVideo: Boolean,
    val canRunWayline: Boolean,
)

object DeviceCapabilityReader {
    fun read(snapshot: DeviceSnapshot): DeviceCapabilities {
        val sdkReady = snapshot.sdkAvailability == SdkAvailability.READY
        val remoteConnected = snapshot.remoteController == LinkState.CONNECTED
        val airLinkConnected = snapshot.airLink == LinkState.CONNECTED
        val cameraConnected = snapshot.camera == LinkState.CONNECTED
        val flightControllerConnected = snapshot.flightController == LinkState.CONNECTED
        val pairingCanStart = snapshot.pairing in setOf(
            PairingState.UNKNOWN,
            PairingState.IDLE,
            PairingState.PAIRED,
            PairingState.FAILED,
            PairingState.STOPPING,
        )
        val pairingActive = snapshot.pairing in setOf(
            PairingState.PAIRING,
            PairingState.PAIRED,
            PairingState.STOPPING,
        )
        val flightReady = sdkReady && flightControllerConnected

        return DeviceCapabilities(
            canStartPairing = sdkReady && remoteConnected && snapshot.flightController == LinkState.DISCONNECTED && pairingCanStart,
            canStopPairing = pairingActive,
            canReadTelemetry = flightReady,
            canStreamVideo = sdkReady && airLinkConnected && cameraConnected,
            canRunWayline = flightReady && remoteConnected,
        )
    }
}
