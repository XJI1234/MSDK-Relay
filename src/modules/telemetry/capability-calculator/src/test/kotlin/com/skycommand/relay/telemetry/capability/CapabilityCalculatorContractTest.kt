package com.skycommand.relay.telemetry.capability

import com.skycommand.relay.device.capability.DeviceCapabilities
import kotlin.test.Test
import kotlin.test.assertEquals

class CapabilityCalculatorContractTest {
    @Test
    fun mapsOnlyPublishedCapabilitiesAndKeepsVirtualStickDisabled() {
        val result = CapabilityCalculator.calculate(
            DeviceCapabilities(
                canStartPairing = true,
                canStopPairing = false,
                canReadTelemetry = true,
                canStreamVideo = true,
                canRunWayline = false,
            ),
        )

        assertEquals(true, result.liveVideo)
        assertEquals(false, result.waypointMission)
        assertEquals(WaypointMissionSupport.UNSUPPORTED, result.waypointMissionSupport)
        assertEquals(false, result.virtualStick)
    }
}
