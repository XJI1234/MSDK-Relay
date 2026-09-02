package com.skycommand.relay.telemetry.snapshot

import com.skycommand.relay.device.state.DeviceSnapshot
import com.skycommand.relay.device.state.LinkState
import com.skycommand.relay.device.state.PairingState
import com.skycommand.relay.device.state.SdkAvailability
import com.skycommand.relay.telemetry.capability.TelemetryCapabilities
import com.skycommand.relay.stream.state.StreamLifecycleState
import com.skycommand.relay.stream.state.StreamMetrics
import com.skycommand.relay.stream.state.StreamSnapshot
import com.skycommand.relay.wayline.staging.MissionMetadata
import com.skycommand.relay.wayline.state.ExecutionState
import com.skycommand.relay.wayline.state.MissionSnapshot
import com.skycommand.relay.wayline.state.UploadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

        val result = SnapshotAssembler.assemble(inputs(device = input))

        assertEquals(7, result.deviceRevision)
        assertEquals(SdkAvailability.READY, result.sdkAvailability)
        assertEquals("Matrice 4", result.aircraftModel)
        assertIs<TelemetryCapabilities>(result.capabilities)
        assertEquals(result, SnapshotAssembler.assemble(inputs(device = input)))
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

        val result = SnapshotAssembler.assemble(inputs(device = input))

        assertEquals(null, result.aircraftModel)
        assertEquals(PairingState.UNKNOWN, result.pairing)
        assertEquals(false, result.capabilities.waypointMission)
    }

    @Test
    fun combinesFlightStreamAndMissionFactsWithoutInventingMissingValues() {
        val result = SnapshotAssembler.assemble(
            inputs(
                device = DeviceSnapshot(
                    revision = 1,
                    sdkAvailability = SdkAvailability.READY,
                    remoteController = LinkState.CONNECTED,
                    aircraft = LinkState.CONNECTED,
                    flightController = LinkState.CONNECTED,
                    pairing = PairingState.PAIRED,
                    remoteControllerModel = "RC Plus",
                    aircraftModel = "Matrice 4",
                ),
                flight = FlightTelemetrySnapshot(
                    isFlying = true,
                    motorsOn = true,
                    flightMode = "WAYPOINT",
                    batteryPercent = 86,
                    remainingFlightTimeSeconds = 420,
                    lowBatteryRthState = LowBatteryRthState.IDLE,
                    altitudeMeters = 80.5,
                    latitude = 30.123,
                    longitude = 120.456,
                    battery = LinkState.CONNECTED,
                ),
                stream = StreamSnapshot(
                    revision = 3,
                    state = StreamLifecycleState.STREAMING,
                    targetConfigured = true,
                    notice = "Streaming",
                    metrics = StreamMetrics(
                        resolution = "1920x1080",
                        fps = 30.0,
                        videoBitrateKbps = 4_000.0,
                        rttMillis = 42,
                        packetLoss = 7,
                        packetCacheLength = 96,
                    ),
                ),
                mission = MissionSnapshot(
                    revision = 5,
                    missionRevision = 1,
                    deviceGeneration = 0,
                    file = MissionMetadata("survey.kmz", 10, "a".repeat(64)),
                    upload = UploadState.Uploading(65),
                    execution = ExecutionState.EXECUTING,
                ),
            ),
        )

        assertEquals(true, result.isFlying)
        assertEquals(86, result.batteryPercent)
        assertEquals(LowBatteryRthState.IDLE, result.lowBatteryRthState)
        assertEquals(30.123, result.latitude)
        assertEquals<Boolean?>(null, result.liveStreaming)
        assertEquals(null, result.liveResolution)
        assertEquals(null, result.livePacketLoss)
        assertEquals(null, result.livePacketCacheLength)
        assertEquals(1, result.missionRevision)
        assertEquals(0, result.missionDeviceGeneration)
        assertEquals(ExecutionState.EXECUTING, result.missionExecution)
        assertEquals(65, result.missionUploadProgress)
        assertEquals("survey.kmz", result.missionFileName)
    }

    @Test
    fun publishesLiveMetricsOnlyWhenDjiReportsStreaming() {
        val result = SnapshotAssembler.assemble(
            inputs(
                stream = StreamSnapshot(
                    revision = 1,
                    state = StreamLifecycleState.STREAMING,
                    targetConfigured = true,
                    notice = "Streaming",
                    metrics = StreamMetrics("1920x1080", 30.0, 4_000.0, 42, 7, 96),
                    djiStreaming = true,
                ),
            ),
        )

        assertEquals(true, result.liveStreaming)
        assertEquals("1920x1080", result.liveResolution)
        assertEquals(7, result.livePacketLoss)
        assertEquals(96, result.livePacketCacheLength)
    }

    @Test
    fun rejectsInvalidFlightTelemetryAtItsConstructionBoundary() {
        listOf(-1, 101).forEach { battery ->
            assertFailsWith<IllegalArgumentException> { FlightTelemetrySnapshot(batteryPercent = battery) }
        }
        assertFailsWith<IllegalArgumentException> { FlightTelemetrySnapshot(remainingFlightTimeSeconds = -1) }
        assertFailsWith<IllegalArgumentException> { FlightTelemetrySnapshot(altitudeMeters = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { FlightTelemetrySnapshot(altitudeMeters = Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { FlightTelemetrySnapshot(flightMode = " ") }
        assertFailsWith<IllegalArgumentException> { FlightTelemetrySnapshot(flightMode = "AUTO\nLAND") }
        assertFailsWith<IllegalArgumentException> { FlightTelemetrySnapshot(flightMode = "x".repeat(129)) }
    }

    @Test
    fun acceptsCoordinateBoundariesAndRejectsPartialOrOutOfRangeCoordinates() {
        FlightTelemetrySnapshot(latitude = -90.0, longitude = -180.0)
        FlightTelemetrySnapshot(latitude = 90.0, longitude = 180.0)

        assertFailsWith<IllegalArgumentException> { FlightTelemetrySnapshot(latitude = 0.0) }
        assertFailsWith<IllegalArgumentException> { FlightTelemetrySnapshot(longitude = 0.0) }
        assertFailsWith<IllegalArgumentException> { FlightTelemetrySnapshot(latitude = 90.000001, longitude = 0.0) }
        assertFailsWith<IllegalArgumentException> { FlightTelemetrySnapshot(latitude = 0.0, longitude = 180.000001) }
        assertFailsWith<IllegalArgumentException> { FlightTelemetrySnapshot(latitude = Double.NaN, longitude = 0.0) }
        assertFailsWith<IllegalArgumentException> { FlightTelemetrySnapshot(latitude = 0.0, longitude = Double.NEGATIVE_INFINITY) }
    }

    @Test
    fun keepsEveryUnknownFlightValueExplicitlyNull() {
        val result = SnapshotAssembler.assemble(inputs())

        assertEquals(null, result.isFlying)
        assertEquals(null, result.motorsOn)
        assertEquals(null, result.flightMode)
        assertEquals(null, result.batteryPercent)
        assertEquals(null, result.lowBatteryRthState)
        assertEquals(null, result.remainingFlightTimeSeconds)
        assertEquals(null, result.altitudeMeters)
        assertEquals(null, result.latitude)
        assertEquals(null, result.longitude)
    }

    @Test
    fun clearsOnlyFlightControllerFactsWhenTheFlightControllerIsDisconnected() {
        val result = SnapshotAssembler.assemble(
            inputs(
                device = DeviceSnapshot(
                    revision = 8,
                    sdkAvailability = SdkAvailability.READY,
                    remoteController = LinkState.CONNECTED,
                    aircraft = LinkState.DISCONNECTED,
                    flightController = LinkState.DISCONNECTED,
                    pairing = PairingState.UNKNOWN,
                    remoteControllerModel = "RC Plus",
                    aircraftModel = null,
                ),
                flight = FlightTelemetrySnapshot(
                    isFlying = false,
                    motorsOn = false,
                    flightMode = "WAYPOINT",
                    batteryPercent = 86,
                    remainingFlightTimeSeconds = 420,
                    lowBatteryRthState = LowBatteryRthState.COUNTING_DOWN,
                    altitudeMeters = 80.5,
                    latitude = 30.123,
                    longitude = 120.456,
                    battery = LinkState.CONNECTED,
                ),
            ),
        )

        assertEquals(null, result.isFlying)
        assertEquals(null, result.motorsOn)
        assertEquals(null, result.flightMode)
        assertEquals(86, result.batteryPercent)
        assertEquals(null, result.lowBatteryRthState)
        assertEquals(null, result.remainingFlightTimeSeconds)
        assertEquals(null, result.altitudeMeters)
        assertEquals(null, result.latitude)
        assertEquals(null, result.longitude)
    }

    private fun inputs(
        device: DeviceSnapshot = DeviceSnapshot(0, SdkAvailability.STOPPED, LinkState.DISCONNECTED, LinkState.DISCONNECTED, LinkState.DISCONNECTED, PairingState.UNKNOWN, null, null),
        flight: FlightTelemetrySnapshot = FlightTelemetrySnapshot(),
        stream: StreamSnapshot = StreamSnapshot(0, StreamLifecycleState.STOPPED, false, "Stopped", null),
        mission: MissionSnapshot = MissionSnapshot(0, null, 0, null, UploadState.NOT_UPLOADED, ExecutionState.NOT_STARTED),
    ) = TelemetryInputs(device, flight, stream, mission)
}
