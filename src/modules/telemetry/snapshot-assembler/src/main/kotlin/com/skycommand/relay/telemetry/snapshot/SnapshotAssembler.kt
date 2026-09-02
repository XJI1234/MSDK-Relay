package com.skycommand.relay.telemetry.snapshot

import com.skycommand.relay.device.capability.DeviceCapabilityReader
import com.skycommand.relay.device.state.DeviceSnapshot
import com.skycommand.relay.device.state.LinkState
import com.skycommand.relay.device.state.PairingState
import com.skycommand.relay.device.state.SdkAvailability
import com.skycommand.relay.telemetry.capability.CapabilityCalculator
import com.skycommand.relay.telemetry.capability.TelemetryCapabilities
import com.skycommand.relay.stream.state.StreamSnapshot
import com.skycommand.relay.wayline.state.ExecutionState
import com.skycommand.relay.wayline.state.MissionSnapshot
import com.skycommand.relay.wayline.state.UploadState

enum class LowBatteryRthState {
    IDLE,
    COUNTING_DOWN,
    EXECUTED,
    CANCELLED,
    UNKNOWN,
}

data class FlightTelemetrySnapshot(
    val isFlying: Boolean? = null,
    val motorsOn: Boolean? = null,
    val flightMode: String? = null,
    val batteryPercent: Int? = null,
    val remainingFlightTimeSeconds: Int? = null,
    val altitudeMeters: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val lowBatteryRthState: LowBatteryRthState? = null,
    val battery: LinkState = LinkState.UNKNOWN,
    val gpsSignalLevel: String? = null,
    val gpsSatelliteCount: Int? = null,
    val visionSensorUsed: Boolean? = null,
    val visionSystemWarning: String? = null,
    val visionPositioningEnabled: Boolean? = null,
    val landingProtectionState: String? = null,
    val landingConfirmationNeeded: Boolean? = null,
    val takeoffFailureError: String? = null,
    val motorStartFailureError: String? = null,
) {
    init {
        flightMode?.let { require(it.isNotBlank() && it.codePointCount(0, it.length) <= 128 && it.none(Char::isISOControl)) }
        batteryPercent?.let { require(it in 0..100) }
        remainingFlightTimeSeconds?.let { require(it in 1..86_400) }
        if (remainingFlightTimeSeconds != null) require(lowBatteryRthState != null)
        altitudeMeters?.let { require(it.isFinite()) }
        require((latitude == null) == (longitude == null)) { "Latitude and longitude must be provided together" }
        latitude?.let { require(it.isFinite() && it in -90.0..90.0) }
        longitude?.let { require(it.isFinite() && it in -180.0..180.0) }
        gpsSignalLevel?.let { require(it.isNotBlank() && it.codePointCount(0, it.length) <= 128 && it.none(Char::isISOControl)) }
        gpsSatelliteCount?.let { require(it >= 0) }
        visionSystemWarning?.let { require(it.isNotBlank() && it.codePointCount(0, it.length) <= 128 && it.none(Char::isISOControl)) }
        landingProtectionState?.let { require(it.isNotBlank() && it.codePointCount(0, it.length) <= 128 && it.none(Char::isISOControl)) }
        takeoffFailureError?.let { require(it.isNotBlank() && it.codePointCount(0, it.length) <= 128 && it.none(Char::isISOControl)) }
        motorStartFailureError?.let { require(it.isNotBlank() && it.codePointCount(0, it.length) <= 128 && it.none(Char::isISOControl)) }
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
    val liveStreaming: Boolean? = null,
    val liveStreamNotice: String? = null,
    val liveResolution: String? = null,
    val liveFps: Double? = null,
    val liveVideoBitrateKbps: Double? = null,
    val liveRttMillis: Long? = null,
    val livePacketLoss: Long? = null,
    val livePacketCacheLength: Long? = null,
    val missionRevision: Long? = null,
    val missionDeviceGeneration: Long? = null,
    val missionExecution: ExecutionState = ExecutionState.NOT_STARTED,
    val missionUploadProgress: Int? = null,
    val missionFileName: String? = null,
    val lowBatteryRthState: LowBatteryRthState? = null,
    val airLink: LinkState = LinkState.UNKNOWN,
    val camera: LinkState = LinkState.UNKNOWN,
    val battery: LinkState = LinkState.UNKNOWN,
    val gpsSignalLevel: String? = null,
    val gpsSatelliteCount: Int? = null,
    val visionSensorUsed: Boolean? = null,
    val visionSystemWarning: String? = null,
    val visionPositioningEnabled: Boolean? = null,
    val landingProtectionState: String? = null,
    val landingConfirmationNeeded: Boolean? = null,
    val takeoffFailureError: String? = null,
    val motorStartFailureError: String? = null,
) {
    init {
        remainingFlightTimeSeconds?.let { require(it in 1..86_400) }
        if (remainingFlightTimeSeconds != null) require(lowBatteryRthState != null)
    }
}

object SnapshotAssembler {
    fun assemble(inputs: TelemetryInputs): TelemetrySnapshot {
        val flightControllerFacts = if (inputs.device.flightController == LinkState.CONNECTED) {
            inputs.flight
        } else {
            FlightTelemetrySnapshot()
        }
        val batteryPercent = if (inputs.flight.battery == LinkState.CONNECTED) inputs.flight.batteryPercent else null
        val liveMetrics = inputs.stream.metrics.takeIf { inputs.stream.djiStreaming == true }
        return TelemetrySnapshot(
        deviceRevision = inputs.device.revision,
        sdkAvailability = inputs.device.sdkAvailability,
        remoteController = inputs.device.remoteController,
        aircraft = inputs.device.aircraft,
        flightController = inputs.device.flightController,
        pairing = inputs.device.pairing,
        remoteControllerModel = inputs.device.remoteControllerModel,
        aircraftModel = inputs.device.aircraftModel,
        airLink = inputs.device.airLink,
        camera = inputs.device.camera,
        capabilities = CapabilityCalculator.calculate(DeviceCapabilityReader.read(inputs.device)),
        isFlying = flightControllerFacts.isFlying,
        motorsOn = flightControllerFacts.motorsOn,
        flightMode = flightControllerFacts.flightMode,
        battery = inputs.flight.battery,
        batteryPercent = batteryPercent,
        remainingFlightTimeSeconds = flightControllerFacts.remainingFlightTimeSeconds,
        lowBatteryRthState = flightControllerFacts.lowBatteryRthState,
        altitudeMeters = flightControllerFacts.altitudeMeters,
        latitude = flightControllerFacts.latitude,
        longitude = flightControllerFacts.longitude,
        gpsSignalLevel = flightControllerFacts.gpsSignalLevel,
        gpsSatelliteCount = flightControllerFacts.gpsSatelliteCount,
        visionSensorUsed = flightControllerFacts.visionSensorUsed,
        visionSystemWarning = flightControllerFacts.visionSystemWarning,
        visionPositioningEnabled = flightControllerFacts.visionPositioningEnabled,
        landingProtectionState = flightControllerFacts.landingProtectionState,
        landingConfirmationNeeded = flightControllerFacts.landingConfirmationNeeded,
        takeoffFailureError = flightControllerFacts.takeoffFailureError,
        motorStartFailureError = flightControllerFacts.motorStartFailureError,
        liveStreaming = inputs.stream.djiStreaming,
        liveStreamNotice = inputs.stream.notice,
        liveResolution = liveMetrics?.resolution,
        liveFps = liveMetrics?.fps,
        liveVideoBitrateKbps = liveMetrics?.videoBitrateKbps,
        liveRttMillis = liveMetrics?.rttMillis,
        livePacketLoss = liveMetrics?.packetLoss,
        livePacketCacheLength = liveMetrics?.packetCacheLength,
        missionRevision = inputs.mission.missionRevision,
        missionDeviceGeneration = inputs.mission.missionRevision?.let { inputs.mission.deviceGeneration },
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
    }

    fun assemble(device: DeviceSnapshot): TelemetrySnapshot = TelemetrySnapshot(
        deviceRevision = device.revision,
        sdkAvailability = device.sdkAvailability,
        remoteController = device.remoteController,
        aircraft = device.aircraft,
        flightController = device.flightController,
        pairing = device.pairing,
        remoteControllerModel = device.remoteControllerModel,
        aircraftModel = device.aircraftModel,
        airLink = device.airLink,
        camera = device.camera,
        capabilities = CapabilityCalculator.calculate(DeviceCapabilityReader.read(device)),
    )
}
