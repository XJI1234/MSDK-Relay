package com.skycommand.relay.app

import com.skycommand.relay.device.state.LinkState
import com.skycommand.relay.device.state.PairingState
import com.skycommand.relay.device.state.SdkAvailability
import com.skycommand.relay.protocol.JsonBoolean
import com.skycommand.relay.protocol.JsonNull
import com.skycommand.relay.protocol.JsonNumber
import com.skycommand.relay.protocol.JsonString
import com.skycommand.relay.protocol.validate
import com.skycommand.relay.protocol.Accepted
import com.skycommand.relay.telemetry.capability.TelemetryCapabilities
import com.skycommand.relay.telemetry.capability.WaypointMissionSupport
import com.skycommand.relay.telemetry.snapshot.TelemetrySnapshot
import com.skycommand.relay.wayline.state.ExecutionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TelemetryFrameMapperTest {
    @Test fun mapsTelemetryReadResultToTheDesktopPayloadShape() {
        val result = TelemetryFrameMapper.commandResult(
            TelemetrySnapshot(
                4, SdkAvailability.READY, LinkState.CONNECTED, LinkState.CONNECTED,
                LinkState.CONNECTED, PairingState.PAIRED, null, "DJI Mini 4 Pro",
                TelemetryCapabilities(true, true, WaypointMissionSupport.SUPPORTED, false),
                batteryPercent = 73,
            ),
        )

        assertEquals(JsonNumber("4"), result["deviceRevision"])
        assertEquals(JsonString("DJI Mini 4 Pro"), result["aircraftModel"])
        assertEquals(JsonNumber("73"), result["batteryPercent"])
        val capabilities = result["capabilities"]
        assertIs<com.skycommand.relay.protocol.JsonObject>(capabilities)
        assertEquals(JsonBoolean(true), capabilities["waypointMission"])
    }

    @Test fun mapsEveryBusinessFieldAndCapability() {
        val frame = TelemetryFrameMapper.map(
            TelemetrySnapshot(
                deviceRevision = 9,
                sdkAvailability = SdkAvailability.READY,
                remoteController = LinkState.CONNECTED,
                aircraft = LinkState.CONNECTED,
                flightController = LinkState.CONNECTED,
                pairing = PairingState.PAIRED,
                remoteControllerModel = "RC Plus",
                aircraftModel = "M350 RTK",
                capabilities = TelemetryCapabilities(true, true, WaypointMissionSupport.SUPPORTED, false),
                isFlying = true,
                motorsOn = true,
                flightMode = "GPS_NORMAL",
                batteryPercent = 73,
                remainingFlightTimeSeconds = 840,
                altitudeMeters = 52.25,
                latitude = 31.2,
                longitude = 121.5,
                liveStreaming = true,
                liveStreamNotice = "Streaming",
                liveResolution = "1920x1080",
                liveFps = 30.0,
                liveVideoBitrateKbps = 4000.0,
                liveRttMillis = 42,
                missionExecution = ExecutionState.EXECUTING,
                missionUploadProgress = 100,
                missionFileName = "survey.kmz",
            ),
        )

        assertEquals(JsonNumber("9"), frame.payload["deviceRevision"])
        assertEquals(JsonString("READY"), frame.payload["sdkAvailability"])
        assertEquals(JsonString("CONNECTED"), frame.payload["remoteController"])
        assertEquals(JsonString("CONNECTED"), frame.payload["aircraft"])
        assertEquals(JsonString("CONNECTED"), frame.payload["flightController"])
        assertEquals(JsonString("PAIRED"), frame.payload["pairing"])
        assertEquals(JsonString("RC Plus"), frame.payload["remoteControllerModel"])
        assertEquals(JsonString("M350 RTK"), frame.payload["aircraftModel"])
        assertEquals(JsonBoolean(true), frame.payload["isFlying"])
        assertEquals(JsonBoolean(true), frame.payload["motorsOn"])
        assertEquals(JsonString("GPS_NORMAL"), frame.payload["flightMode"])
        assertEquals(JsonNumber("73"), frame.payload["batteryPercent"])
        assertEquals(JsonNumber("840"), frame.payload["remainingFlightTimeSeconds"])
        assertEquals(JsonNumber("52.25"), frame.payload["altitudeMeters"])
        assertEquals(JsonNumber("31.2"), frame.payload["latitude"])
        assertEquals(JsonNumber("121.5"), frame.payload["longitude"])
        assertEquals(JsonBoolean(true), frame.payload["liveStreaming"])
        assertEquals(JsonString("Streaming"), frame.payload["liveStreamNotice"])
        assertEquals(JsonString("1920x1080"), frame.payload["liveResolution"])
        assertEquals(JsonNumber("30.0"), frame.payload["liveFps"])
        assertEquals(JsonNumber("4000.0"), frame.payload["liveVideoBitrateKbps"])
        assertEquals(JsonNumber("42"), frame.payload["liveRttMillis"])
        assertEquals(JsonString("EXECUTING"), frame.payload["missionExecution"])
        assertEquals(JsonNumber("100"), frame.payload["missionUploadProgress"])
        assertEquals(JsonString("survey.kmz"), frame.payload["missionFileName"])
        assertEquals(JsonBoolean(true), frame.capabilities["liveVideo"])
        assertEquals(JsonBoolean(true), frame.capabilities["waypointMission"])
        assertEquals(JsonString("SUPPORTED"), frame.capabilities["waypointMissionSupport"])
        assertEquals(JsonBoolean(false), frame.capabilities["virtualStick"])
        assertIs<Accepted<*>>(validate(frame))
    }

    @Test fun mapsPairingStatusToTheRootContractResult() {
        val result = TelemetryFrameMapper.pairingStatus(
            TelemetrySnapshot(
                4, SdkAvailability.READY, LinkState.CONNECTED, LinkState.CONNECTED,
                LinkState.CONNECTED, PairingState.PAIRED, null, "DJI Mini 4 Pro",
                TelemetryCapabilities(true, true, WaypointMissionSupport.SUPPORTED, false),
                motorsOn = false,
            ),
        )

        assertEquals(JsonString("PAIRED"), result["pairingState"])
        assertEquals(JsonBoolean(true), result["aircraftConnected"])
        assertEquals(JsonBoolean(true), result["flightControllerConnected"])
        assertEquals(JsonString("DJI Mini 4 Pro"), result["aircraftModel"])
        assertEquals(JsonBoolean(false), result["motorsOn"])
        assertEquals(JsonBoolean(true), result["sdkRegistered"])
    }

    @Test fun pairingStatusUsesUnknownModelAndNullMotorsWhenMissing() {
        val result = TelemetryFrameMapper.pairingStatus(
            TelemetrySnapshot(
                0, SdkAvailability.STOPPED, LinkState.DISCONNECTED, LinkState.DISCONNECTED,
                LinkState.DISCONNECTED, PairingState.IDLE, null, null,
                TelemetryCapabilities(false, false, WaypointMissionSupport.UNSUPPORTED, false),
            ),
        )

        assertEquals(JsonString("IDLE"), result["pairingState"])
        assertEquals(JsonBoolean(false), result["aircraftConnected"])
        assertEquals(JsonBoolean(false), result["flightControllerConnected"])
        assertEquals(JsonString("UNKNOWN"), result["aircraftModel"])
        assertEquals(JsonNull, result["motorsOn"])
        assertEquals(JsonBoolean(false), result["sdkRegistered"])
    }

    @Test fun pairingStatusUsesUnknownModelWhenBlankAndDoesNotTreatStartingSdkAsRegistered() {
        val result = TelemetryFrameMapper.pairingStatus(
            TelemetrySnapshot(
                1, SdkAvailability.STARTING, LinkState.CONNECTED, LinkState.DISCONNECTED,
                LinkState.DISCONNECTED, PairingState.PAIRING, null, "  ",
                TelemetryCapabilities(false, false, WaypointMissionSupport.UNSUPPORTED, false),
            ),
        )

        assertEquals(JsonString("PAIRING"), result["pairingState"])
        assertEquals(JsonBoolean(false), result["aircraftConnected"])
        assertEquals(JsonString("UNKNOWN"), result["aircraftModel"])
        assertEquals(JsonBoolean(false), result["sdkRegistered"])
    }

    @Test fun preservesMissingOptionalValuesAsJsonNull() {
        val frame = TelemetryFrameMapper.map(
            TelemetrySnapshot(
                0, SdkAvailability.STOPPED, LinkState.DISCONNECTED, LinkState.DISCONNECTED,
                LinkState.DISCONNECTED, PairingState.UNKNOWN, null, null,
                TelemetryCapabilities(false, false, WaypointMissionSupport.UNSUPPORTED, false),
            ),
        )
        listOf(
            "remoteControllerModel", "aircraftModel", "isFlying", "motorsOn", "flightMode",
            "batteryPercent", "remainingFlightTimeSeconds", "altitudeMeters", "latitude", "longitude",
            "liveStreamNotice", "liveResolution", "liveFps", "liveVideoBitrateKbps", "liveRttMillis",
            "missionUploadProgress", "missionFileName",
        ).forEach { assertEquals(JsonNull, frame.payload[it], it) }
    }
}
