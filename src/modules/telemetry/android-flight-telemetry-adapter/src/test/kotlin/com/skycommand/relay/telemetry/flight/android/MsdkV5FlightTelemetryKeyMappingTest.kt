package com.skycommand.relay.telemetry.flight.android

import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MsdkV5FlightTelemetryKeyMappingTest {
    @Test
    fun chargePercentUsesThePrimaryBatteryKeyInsteadOfTheAggregateIndex() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/telemetry/flight/android/MsdkV5FlightTelemetryApi.kt"),
            Path("src/modules/telemetry/android-flight-telemetry-adapter/src/main/kotlin/com/skycommand/relay/telemetry/flight/android/MsdkV5FlightTelemetryApi.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("BatteryKey.KeyChargeRemainingInPercent,\n        ComponentIndexType.LEFT_OR_MAIN"))
        assertFalse(source.contains("BatteryKey.KeyChargeRemainingInPercent,\n        ComponentIndexType.AGGREGATION"))
    }

    @Test
    fun lowBatteryRthObservationReadsTheStatusAlongsideTheTime() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/telemetry/flight/android/MsdkV5FlightTelemetryApi.kt"),
            Path("src/modules/telemetry/android-flight-telemetry-adapter/src/main/kotlin/com/skycommand/relay/telemetry/flight/android/MsdkV5FlightTelemetryApi.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("LowBatteryRTHState"))
        assertTrue(source.contains("lowBatteryRTHStatus"))
    }
}
