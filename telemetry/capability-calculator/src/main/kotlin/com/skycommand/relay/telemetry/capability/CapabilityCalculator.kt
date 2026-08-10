package com.skycommand.relay.telemetry.capability

import com.skycommand.relay.device.capability.DeviceCapabilities

enum class WaypointMissionSupport {
    SUPPORTED,
    UNSUPPORTED,
}

data class TelemetryCapabilities(
    val liveVideo: Boolean,
    val waypointMission: Boolean,
    val waypointMissionSupport: WaypointMissionSupport,
    val virtualStick: Boolean,
)

object CapabilityCalculator {
    fun calculate(capabilities: DeviceCapabilities): TelemetryCapabilities = TelemetryCapabilities(
        liveVideo = capabilities.canStreamVideo,
        waypointMission = capabilities.canRunWayline,
        waypointMissionSupport = if (capabilities.canRunWayline) {
            WaypointMissionSupport.SUPPORTED
        } else {
            WaypointMissionSupport.UNSUPPORTED
        },
        virtualStick = false,
    )
}
