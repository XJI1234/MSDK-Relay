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

    @Test
    fun requestsEveryInitialTelemetryValueFromHardwareWhileContinuingToListen() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/telemetry/flight/android/MsdkV5FlightTelemetryApi.kt"),
            Path("src/modules/telemetry/android-flight-telemetry-adapter/src/main/kotlin/com/skycommand/relay/telemetry/flight/android/MsdkV5FlightTelemetryApi.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("manager.listen(isFlyingKey, owner)"))
        assertTrue(source.contains("manager.listen(motorsOnKey, owner)"))
        assertTrue(source.contains("manager.listen(flightModeKey, owner)"))
        assertTrue(source.contains("manager.listen(batteryKey, owner)"))
        assertTrue(source.contains("manager.listen(remainingFlightTimeKey, owner)"))
        assertTrue(source.contains("manager.listen(altitudeKey, owner)"))
        assertTrue(source.contains("manager.listen(locationKey, owner)"))
        assertTrue(source.contains("requestInitialValue(isFlyingKey, ObservedKey.IS_FLYING)"))
        assertTrue(source.contains("requestInitialValue(motorsOnKey, ObservedKey.MOTORS_ON)"))
        assertTrue(source.contains("requestInitialValue(flightModeKey, ObservedKey.FLIGHT_MODE)"))
        assertTrue(source.contains("requestInitialValue(batteryKey, ObservedKey.BATTERY)"))
        assertTrue(source.contains("requestInitialValue(remainingFlightTimeKey, ObservedKey.LOW_BATTERY_RTH)"))
        assertTrue(source.contains("requestInitialValue(altitudeKey, ObservedKey.ALTITUDE)"))
        assertTrue(source.contains("requestInitialValue(locationKey, ObservedKey.LOCATION)"))
        assertTrue(source.contains("manager.getValue(key, object : CommonCallbacks.CompletionCallbackWithParam<T>"))
        assertTrue(source.contains("eventRevisions[observedKey] != initialEventRevision"))
        assertFalse(source.contains("publishInitial()"))
        assertFalse(source.contains("manager.getValue(isFlyingKey)"))
        assertFalse(source.contains("manager.getValue(motorsOnKey)"))
        assertFalse(source.contains("manager.getValue<FCFlightMode>(flightModeKey)"))
        assertFalse(source.contains("manager.getValue(batteryKey)"))
        assertFalse(source.contains("manager.getValue<LowBatteryRTHInfo>(remainingFlightTimeKey)"))
        assertFalse(source.contains("manager.getValue(altitudeKey)"))
        assertFalse(source.contains("manager.getValue(locationKey)"))
        assertFalse(source.contains("manager.listen(isFlyingKey, owner, false)"))
    }
}
