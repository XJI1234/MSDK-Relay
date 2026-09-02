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
import com.skycommand.relay.telemetry.snapshot.LowBatteryRthState
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
                battery = LinkState.CONNECTED,
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
                battery = LinkState.CONNECTED,
                remainingFlightTimeSeconds = 840,
                lowBatteryRthState = LowBatteryRthState.IDLE,
                altitudeMeters = 52.25,
                latitude = 31.2,
                longitude = 121.5,
                liveStreaming = true,
                liveStreamNotice = "Streaming",
                liveResolution = "1920x1080",
                liveFps = 30.0,
                liveVideoBitrateKbps = 4000.0,
                liveRttMillis = 42,
                livePacketLoss = 7,
                livePacketCacheLength = 96,
                missionRevision = 7,
                missionDeviceGeneration = 3,
                missionExecution = ExecutionState.EXECUTING,
                missionUploadProgress = 100,
                missionFileName = "survey.kmz",
            ),
            telemetrySequence = 12,
        )

        assertEquals(JsonNumber("12"), frame.payload["telemetrySequence"])
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
        assertEquals(JsonString("IDLE"), frame.payload["lowBatteryRthState"])
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
        assertEquals(JsonNumber("7"), frame.payload["livePacketLoss"])
        assertEquals(JsonNumber("96"), frame.payload["livePacketCacheLength"])
        assertEquals(JsonNumber("7"), frame.payload["missionRevision"])
        assertEquals(JsonNumber("3"), frame.payload["missionDeviceGeneration"])
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
        assertEquals(null, result["aircraftConnected"])
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
        assertEquals(null, result["aircraftConnected"])
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
        assertEquals(null, result["aircraftConnected"])
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
            telemetrySequence = 1,
        )
        listOf(
            "remoteControllerModel", "aircraftModel", "isFlying", "motorsOn", "flightMode",
            "batteryPercent", "lowBatteryRthState", "remainingFlightTimeSeconds", "altitudeMeters", "latitude", "longitude",
            "liveStreaming", "liveStreamNotice", "liveResolution", "liveFps", "liveVideoBitrateKbps", "liveRttMillis",
            "missionRevision", "missionDeviceGeneration", "missionUploadProgress", "missionFileName",
        ).forEach { assertEquals(JsonNull, frame.payload[it], it) }
    }

    @Test fun emitsExplicitUnknownStatesForAirLinkAndPrimaryCamera() {
        val frame = TelemetryFrameMapper.map(
            TelemetrySnapshot(
                0, SdkAvailability.STOPPED, LinkState.DISCONNECTED, LinkState.DISCONNECTED,
                LinkState.DISCONNECTED, PairingState.UNKNOWN, null, null,
                TelemetryCapabilities(false, false, WaypointMissionSupport.UNSUPPORTED, false),
            ),
            telemetrySequence = 1,
        )

        assertEquals(JsonString("UNKNOWN"), frame.payload["airLink"])
        assertEquals(JsonString("UNKNOWN"), frame.payload["camera"])
        assertEquals(JsonString("UNKNOWN"), frame.payload["battery"])
    }

    @Test fun omitsBatteryPercentFromTelemetryFramesUntilTheBatteryKeyReportsConnected() {
        val frame = TelemetryFrameMapper.map(
            TelemetrySnapshot(
                1, SdkAvailability.READY, LinkState.CONNECTED, LinkState.CONNECTED,
                LinkState.DISCONNECTED, PairingState.UNKNOWN, null, null,
                TelemetryCapabilities(false, false, WaypointMissionSupport.UNSUPPORTED, false),
                batteryPercent = 73,
                battery = LinkState.UNKNOWN,
            ),
            telemetrySequence = 1,
        )

        assertEquals(JsonString("UNKNOWN"), frame.payload["battery"])
        assertEquals(JsonNull, frame.payload["batteryPercent"])
    }
}
