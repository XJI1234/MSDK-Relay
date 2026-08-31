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
    fun map(snapshot: TelemetrySnapshot): TelemetryFrame = TelemetryFrame(
        payload = JsonObject(
            mapOf(
                "deviceRevision" to JsonNumber(snapshot.deviceRevision.toString()),
                "sdkAvailability" to JsonString(snapshot.sdkAvailability.name),
                "remoteController" to JsonString(snapshot.remoteController.name),
                "aircraft" to JsonString(snapshot.aircraft.name),
                "flightController" to JsonString(snapshot.flightController.name),
                "pairing" to JsonString(snapshot.pairing.name),
                "remoteControllerModel" to snapshot.remoteControllerModel.json(),
                "aircraftModel" to snapshot.aircraftModel.json(),
                "isFlying" to snapshot.isFlying.json(),
                "motorsOn" to snapshot.motorsOn.json(),
                "flightMode" to snapshot.flightMode.json(),
                "batteryPercent" to snapshot.batteryPercent.json(),
                "lowBatteryRthState" to snapshot.lowBatteryRthState?.name.json(),
                "remainingFlightTimeSeconds" to snapshot.remainingFlightTimeSeconds.json(),
                "altitudeMeters" to snapshot.altitudeMeters.json(),
                "latitude" to snapshot.latitude.json(),
                "longitude" to snapshot.longitude.json(),
                "liveStreaming" to JsonBoolean(snapshot.liveStreaming),
                "liveStreamNotice" to snapshot.liveStreamNotice.json(),
                "liveResolution" to snapshot.liveResolution.json(),
                "liveFps" to snapshot.liveFps.json(),
                "liveVideoBitrateKbps" to snapshot.liveVideoBitrateKbps.json(),
                "liveRttMillis" to snapshot.liveRttMillis.json(),
                "missionRevision" to snapshot.missionRevision.json(),
                "missionDeviceGeneration" to snapshot.missionDeviceGeneration.json(),
                "missionExecution" to JsonString(snapshot.missionExecution.name),
                "missionUploadProgress" to snapshot.missionUploadProgress.json(),
                "missionFileName" to snapshot.missionFileName.json(),
            ),
        ),
        capabilities = JsonObject(
            mapOf(
                "liveVideo" to JsonBoolean(snapshot.capabilities.liveVideo),
                "waypointMission" to JsonBoolean(snapshot.capabilities.waypointMission),
                "waypointMissionSupport" to JsonString(snapshot.capabilities.waypointMissionSupport.name),
                "virtualStick" to JsonBoolean(snapshot.capabilities.virtualStick),
            ),
        ),
    )

    fun commandResult(snapshot: TelemetrySnapshot): JsonObject {
        val frame = map(snapshot)
        return JsonObject(frame.payload.fields + ("capabilities" to frame.capabilities))
    }

    fun pairingStatus(snapshot: TelemetrySnapshot): JsonObject = JsonObject(
        mapOf(
            "pairingState" to JsonString(snapshot.pairing.name),
            "aircraftConnected" to JsonBoolean(snapshot.aircraft == LinkState.CONNECTED),
            "flightControllerConnected" to JsonBoolean(snapshot.flightController == LinkState.CONNECTED),
            "aircraftModel" to JsonString(snapshot.aircraftModel?.takeIf(String::isNotBlank) ?: "UNKNOWN"),
            "motorsOn" to snapshot.motorsOn.json(),
            "sdkRegistered" to JsonBoolean(snapshot.sdkAvailability == SdkAvailability.READY),
        ),
    )

    private fun String?.json(): JsonValue = this?.let(::JsonString) ?: JsonNull
    private fun Boolean?.json(): JsonValue = this?.let(::JsonBoolean) ?: JsonNull
    private fun Number?.json(): JsonValue = this?.let { JsonNumber(it.toString()) } ?: JsonNull
}
