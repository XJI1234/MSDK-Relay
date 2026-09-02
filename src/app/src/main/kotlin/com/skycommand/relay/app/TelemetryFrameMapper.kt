package com.skycommand.relay.app

import com.skycommand.relay.device.state.LinkState
import com.skycommand.relay.device.state.SdkAvailability
import com.skycommand.relay.protocol.JsonBoolean
import com.skycommand.relay.protocol.JsonNull
import com.skycommand.relay.protocol.JsonNumber
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.JsonString
import com.skycommand.relay.protocol.JsonValue
import com.skycommand.relay.protocol.TelemetryFrame
import com.skycommand.relay.telemetry.snapshot.TelemetrySnapshot

object TelemetryFrameMapper {
    fun map(snapshot: TelemetrySnapshot, telemetrySequence: Long): TelemetryFrame {
        require(telemetrySequence > 0) { "Telemetry sequence must be positive" }
        return TelemetryFrame(
        payload = JsonObject(
            payloadFields(snapshot) + mapOf(
                "telemetrySequence" to JsonNumber(telemetrySequence.toString()),
                "deviceRevision" to JsonNumber(snapshot.deviceRevision.toString()),
                "sdkAvailability" to JsonString(snapshot.sdkAvailability.name),
                "remoteController" to JsonString(snapshot.remoteController.name),
                "aircraft" to JsonString(snapshot.aircraft.name),
                "flightController" to JsonString(snapshot.flightController.name),
                "airLink" to JsonString(snapshot.airLink.name),
                "camera" to JsonString(snapshot.camera.name),
                "battery" to JsonString(snapshot.battery.name),
                "pairing" to JsonString(snapshot.pairing.name),
            ),
        ),
        capabilities = capabilities(snapshot),
        )
    }

    fun commandResult(snapshot: TelemetrySnapshot): JsonObject =
        JsonObject(payloadFields(snapshot) + mapOf(
            "deviceRevision" to JsonNumber(snapshot.deviceRevision.toString()),
            "sdkAvailability" to JsonString(snapshot.sdkAvailability.name),
            "remoteController" to JsonString(snapshot.remoteController.name),
            "aircraft" to JsonString(snapshot.aircraft.name),
            "flightController" to JsonString(snapshot.flightController.name),
            "airLink" to JsonString(snapshot.airLink.name),
            "camera" to JsonString(snapshot.camera.name),
            "battery" to JsonString(snapshot.battery.name),
            "pairing" to JsonString(snapshot.pairing.name),
            "capabilities" to capabilities(snapshot),
        ))

    fun pairingStatus(snapshot: TelemetrySnapshot): JsonObject = JsonObject(
        mapOf(
            "pairingState" to JsonString(snapshot.pairing.name),
            "flightControllerConnected" to JsonBoolean(snapshot.flightController == LinkState.CONNECTED),
            "aircraftModel" to JsonString(snapshot.aircraftModel?.takeIf(String::isNotBlank) ?: "UNKNOWN"),
            "motorsOn" to snapshot.motorsOn.json(),
            "sdkRegistered" to JsonBoolean(snapshot.sdkAvailability == SdkAvailability.READY),
        ),
    )

    private fun payloadFields(snapshot: TelemetrySnapshot): Map<String, JsonValue> = mapOf(
        "remoteControllerModel" to snapshot.remoteControllerModel.json(),
        "aircraftModel" to snapshot.aircraftModel.json(),
        "isFlying" to snapshot.isFlying.json(),
        "motorsOn" to snapshot.motorsOn.json(),
        "flightMode" to snapshot.flightMode.json(),
        "battery" to JsonString(snapshot.battery.name),
        "batteryPercent" to snapshot.batteryPercent.takeIf { snapshot.battery == LinkState.CONNECTED }.json(),
        "lowBatteryRthState" to snapshot.lowBatteryRthState?.name.json(),
        "remainingFlightTimeSeconds" to snapshot.remainingFlightTimeSeconds.json(),
        "altitudeMeters" to snapshot.altitudeMeters.json(),
        "latitude" to snapshot.latitude.json(),
        "longitude" to snapshot.longitude.json(),
        "gpsSignalLevel" to snapshot.gpsSignalLevel.json(),
        "gpsSatelliteCount" to snapshot.gpsSatelliteCount.json(),
        "visionSensorUsed" to snapshot.visionSensorUsed.json(),
        "visionSystemWarning" to snapshot.visionSystemWarning.json(),
        "visionPositioningEnabled" to snapshot.visionPositioningEnabled.json(),
        "landingProtectionState" to snapshot.landingProtectionState.json(),
        "landingConfirmationNeeded" to snapshot.landingConfirmationNeeded.json(),
        "takeoffFailureError" to snapshot.takeoffFailureError.json(),
        "motorStartFailureError" to snapshot.motorStartFailureError.json(),
        "liveStreaming" to snapshot.liveStreaming.json(),
        "liveStreamNotice" to snapshot.liveStreamNotice.json(),
        "liveResolution" to snapshot.liveResolution.json(),
        "liveFps" to snapshot.liveFps.json(),
        "liveVideoBitrateKbps" to snapshot.liveVideoBitrateKbps.json(),
        "liveRttMillis" to snapshot.liveRttMillis.json(),
        "livePacketLoss" to snapshot.livePacketLoss.json(),
        "livePacketCacheLength" to snapshot.livePacketCacheLength.json(),
        "missionRevision" to snapshot.missionRevision.json(),
        "missionDeviceGeneration" to snapshot.missionDeviceGeneration.json(),
        "missionExecution" to JsonString(snapshot.missionExecution.name),
        "missionUploadProgress" to snapshot.missionUploadProgress.json(),
        "missionFileName" to snapshot.missionFileName.json(),
    )

    private fun capabilities(snapshot: TelemetrySnapshot): JsonObject = JsonObject(
        mapOf(
            "liveVideo" to JsonBoolean(snapshot.capabilities.liveVideo),
            "waypointMission" to JsonBoolean(snapshot.capabilities.waypointMission),
            "waypointMissionSupport" to JsonString(snapshot.capabilities.waypointMissionSupport.name),
            "virtualStick" to JsonBoolean(snapshot.capabilities.virtualStick),
        ),
    )

    private fun String?.json(): JsonValue = this?.let(::JsonString) ?: JsonNull
    private fun Boolean?.json(): JsonValue = this?.let(::JsonBoolean) ?: JsonNull
    private fun Number?.json(): JsonValue = this?.let { JsonNumber(it.toString()) } ?: JsonNull
}
