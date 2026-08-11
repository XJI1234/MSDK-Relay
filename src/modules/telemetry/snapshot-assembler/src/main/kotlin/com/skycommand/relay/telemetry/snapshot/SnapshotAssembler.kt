package com.skycommand.relay.telemetry.snapshot

import com.skycommand.relay.device.capability.DeviceCapabilityReader
import com.skycommand.relay.device.state.DeviceSnapshot
import com.skycommand.relay.device.state.LinkState
import com.skycommand.relay.device.state.PairingState
import com.skycommand.relay.device.state.SdkAvailability
import com.skycommand.relay.telemetry.capability.CapabilityCalculator
import com.skycommand.relay.telemetry.capability.TelemetryCapabilities
import com.skycommand.relay.stream.state.StreamLifecycleState
import com.skycommand.relay.stream.state.StreamSnapshot
import com.skycommand.relay.wayline.state.ExecutionState
import com.skycommand.relay.wayline.state.MissionSnapshot
import com.skycommand.relay.wayline.state.UploadState

data class FlightTelemetrySnapshot(
    val isFlying: Boolean? = null,
    val motorsOn: Boolean? = null,
    val flightMode: String? = null,
    val batteryPercent: Int? = null,
    val remainingFlightTimeSeconds: Int? = null,
    val altitudeMeters: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    init {
        flightMode?.let { require(it.isNotBlank() && it.codePointCount(0, it.length) <= 128 && it.none(Char::isISOControl)) }
        batteryPercent?.let { require(it in 0..100) }
        remainingFlightTimeSeconds?.let { require(it >= 0) }
        altitudeMeters?.let { require(it.isFinite()) }
        require((latitude == null) == (longitude == null)) { "Latitude and longitude must be provided together" }
        latitude?.let { require(it.isFinite() && it in -90.0..90.0) }
        longitude?.let { require(it.isFinite() && it in -180.0..180.0) }
    }
}

data class TelemetryInputs(
    val device: DeviceSnapshot,
    val flight: FlightTelemetrySnapshot,
    val stream: StreamSnapshot,
    val mission: MissionSnapshot,
)

data class TelemetrySnapshot(
    val deviceRevision: Long,
    val sdkAvailability: SdkAvailability,
    val remoteController: LinkState,
    val aircraft: LinkState,
    val flightController: LinkState,
    val pairing: PairingState,
    val remoteControllerModel: String?,
    val aircraftModel: String?,
    val capabilities: TelemetryCapabilities,
    val isFlying: Boolean? = null,
    val motorsOn: Boolean? = null,
    val flightMode: String? = null,
    val batteryPercent: Int? = null,
    val remainingFlightTimeSeconds: Int? = null,
    val altitudeMeters: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val liveStreaming: Boolean = false,
    val liveStreamNotice: String? = null,
    val liveResolution: String? = null,
    val liveFps: Double? = null,
    val liveVideoBitrateKbps: Double? = null,
    val liveRttMillis: Long? = null,
    val missionExecution: ExecutionState = ExecutionState.NOT_STARTED,
    val missionUploadProgress: Int? = null,
    val missionFileName: String? = null,
)

object SnapshotAssembler {
    fun assemble(inputs: TelemetryInputs): TelemetrySnapshot = TelemetrySnapshot(
        deviceRevision = inputs.device.revision,
        sdkAvailability = inputs.device.sdkAvailability,
        remoteController = inputs.device.remoteController,
        aircraft = inputs.device.aircraft,
        flightController = inputs.device.flightController,
        pairing = inputs.device.pairing,
        remoteControllerModel = inputs.device.remoteControllerModel,
        aircraftModel = inputs.device.aircraftModel,
        capabilities = CapabilityCalculator.calculate(DeviceCapabilityReader.read(inputs.device)),
        isFlying = inputs.flight.isFlying,
        motorsOn = inputs.flight.motorsOn,
        flightMode = inputs.flight.flightMode,
        batteryPercent = inputs.flight.batteryPercent,
        remainingFlightTimeSeconds = inputs.flight.remainingFlightTimeSeconds,
        altitudeMeters = inputs.flight.altitudeMeters,
        latitude = inputs.flight.latitude,
        longitude = inputs.flight.longitude,
        liveStreaming = inputs.stream.state == StreamLifecycleState.STREAMING,
        liveStreamNotice = inputs.stream.notice,
        liveResolution = inputs.stream.metrics?.resolution,
        liveFps = inputs.stream.metrics?.fps,
        liveVideoBitrateKbps = inputs.stream.metrics?.videoBitrateKbps,
        liveRttMillis = inputs.stream.metrics?.rttMillis,
        missionExecution = inputs.mission.execution,
        missionUploadProgress = when (val upload = inputs.mission.upload) {
            is UploadState.Uploading -> upload.progress
            UploadState.UPLOADED -> 100
            UploadState.NOT_UPLOADED,
            UploadState.FAILED,
            -> null
        },
        missionFileName = inputs.mission.file?.fileName,
    )

    fun assemble(device: DeviceSnapshot): TelemetrySnapshot = TelemetrySnapshot(
        deviceRevision = device.revision,
        sdkAvailability = device.sdkAvailability,
        remoteController = device.remoteController,
        aircraft = device.aircraft,
        flightController = device.flightController,
        pairing = device.pairing,
        remoteControllerModel = device.remoteControllerModel,
        aircraftModel = device.aircraftModel,
        capabilities = CapabilityCalculator.calculate(DeviceCapabilityReader.read(device)),
    )
}
