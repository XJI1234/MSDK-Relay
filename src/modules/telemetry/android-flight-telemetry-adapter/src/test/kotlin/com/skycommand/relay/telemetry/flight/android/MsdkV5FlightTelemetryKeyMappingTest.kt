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
    fun observesPrimaryBatteryConnectionIndependentlyOfFlightControllerKeys() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/telemetry/flight/android/MsdkV5FlightTelemetryApi.kt"),
            Path("src/modules/telemetry/android-flight-telemetry-adapter/src/main/kotlin/com/skycommand/relay/telemetry/flight/android/MsdkV5FlightTelemetryApi.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("BatteryKey.KeyConnection,\n        ComponentIndexType.LEFT_OR_MAIN"))
        assertTrue(source.contains("private val batteryOwner = Any()"))
        assertTrue(source.contains("manager.listen(batteryConnectionKey, batteryOwner)"))
        assertTrue(source.contains("manager.listen(batteryKey, batteryOwner)"))
        assertTrue(source.contains("requestInitialValue(\n            batteryConnectionKey,"))
        val invalidation = source.substringAfter("override fun invalidateFlightControllerFacts()")
            .substringBefore("override fun refreshFlightControllerFacts()")
        val refresh = source.substringAfter("override fun refreshFlightControllerFacts()")
            .substringBefore("private fun beginFlightControllerObservationGeneration()")
        assertTrue(invalidation.contains("manager.cancelListen(flightControllerOwner)"))
        assertTrue(refresh.contains("manager.cancelListen(flightControllerOwner)"))
        assertFalse(invalidation.contains("batteryOwner"))
        assertFalse(refresh.contains("batteryOwner"))
    }

    @Test
    fun refreshesTheBatteryKeyFromHardwareWhenFlightControllerFirstBecomesReady() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/telemetry/flight/android/MsdkV5FlightTelemetryApi.kt"),
            Path("src/modules/telemetry/android-flight-telemetry-adapter/src/main/kotlin/com/skycommand/relay/telemetry/flight/android/MsdkV5FlightTelemetryApi.kt"),
        ).first { it.exists() }.readText()

        val refresh = source.substringAfter("override fun refreshFlightControllerFacts()")
            .substringBefore("private fun beginFlightControllerObservationGeneration()")
        assertTrue(refresh.contains("requestBatteryFacts()"))
        val batteryRead = source.substringAfter("private fun requestBatteryFacts()")
            .substringBefore("private fun requestInitialBatteryPercent()")
        assertTrue(batteryRead.contains("batteryConnectionKey"))
        assertTrue(batteryRead.contains("requestInitialBatteryPercent()"))
        assertFalse(batteryRead.contains("flightControllerOwner"))
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
    fun preservesExplicitUnknownMsdkStatesWithoutTreatingTheDefaultZeroAsAnEstimate() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/telemetry/flight/android/MsdkV5FlightTelemetryApi.kt"),
            Path("src/modules/telemetry/android-flight-telemetry-adapter/src/main/kotlin/com/skycommand/relay/telemetry/flight/android/MsdkV5FlightTelemetryApi.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("private fun FCFlightMode?.toStableName(): String? = this?.name"))
        assertTrue(source.contains("LowBatteryRTHState.UNKNOWN -> LowBatteryRthState.UNKNOWN"))
        assertTrue(source.contains("state != LowBatteryRthState.UNKNOWN && it in 1..86_400"))
    }

    @Test
    fun requestsEveryInitialTelemetryValueFromHardwareWhileContinuingToListen() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/telemetry/flight/android/MsdkV5FlightTelemetryApi.kt"),
            Path("src/modules/telemetry/android-flight-telemetry-adapter/src/main/kotlin/com/skycommand/relay/telemetry/flight/android/MsdkV5FlightTelemetryApi.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("manager.listen(isFlyingKey, flightControllerOwner)"))
        assertTrue(source.contains("manager.listen(motorsOnKey, flightControllerOwner)"))
        assertTrue(source.contains("manager.listen(flightModeKey, flightControllerOwner)"))
        assertTrue(source.contains("manager.listen(batteryKey, batteryOwner)"))
        assertTrue(source.contains("manager.listen(remainingFlightTimeKey, flightControllerOwner)"))
        assertTrue(source.contains("manager.listen(altitudeKey, flightControllerOwner)"))
        assertTrue(source.contains("manager.listen(locationKey, flightControllerOwner)"))
        assertTrue(source.contains("requestInitialValue(isFlyingKey, ObservedKey.IS_FLYING,"))
        assertTrue(source.contains("requestInitialValue(motorsOnKey, ObservedKey.MOTORS_ON,"))
        assertTrue(source.contains("requestInitialValue(flightModeKey, ObservedKey.FLIGHT_MODE,"))
        assertTrue(source.contains("requestInitialValue(\n            batteryKey,"))
        assertTrue(source.contains("requestInitialValue(remainingFlightTimeKey, ObservedKey.LOW_BATTERY_RTH,"))
        assertTrue(source.contains("requestInitialValue(altitudeKey, ObservedKey.ALTITUDE,"))
        assertTrue(source.contains("requestInitialValue(locationKey, ObservedKey.LOCATION,"))
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

    @Test
    fun observesRawGpsVisionAndTakeoffDiagnosticKeysWithoutInventingSafetyStates() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/telemetry/flight/android/MsdkV5FlightTelemetryApi.kt"),
            Path("src/modules/telemetry/android-flight-telemetry-adapter/src/main/kotlin/com/skycommand/relay/telemetry/flight/android/MsdkV5FlightTelemetryApi.kt"),
        ).first { it.exists() }.readText()

        listOf(
            "FlightControllerKey.KeyGPSSignalLevel",
            "FlightControllerKey.KeyGPSSatelliteCount",
            "FlightControllerKey.KeyIsVisionSensorUsed",
            "FlightAssistantKey.KeyVisionSystemWarning",
            "FlightAssistantKey.KeyVisionPositioningEnabled",
            "FlightAssistantKey.KeyLandingProtectionState",
            "FlightControllerKey.KeyIsLandingConfirmationNeeded",
            "FlightControllerKey.KeyTakeoffFailureError",
            "FlightControllerKey.KeyMotorStartFailureError",
        ).forEach { key -> assertTrue(source.contains(key), "Missing observation for $key") }
        listOf(
            "GPS_SIGNAL_LEVEL",
            "GPS_SATELLITE_COUNT",
            "VISION_SENSOR_USED",
            "VISION_SYSTEM_WARNING",
            "VISION_POSITIONING_ENABLED",
            "LANDING_PROTECTION_STATE",
            "LANDING_CONFIRMATION_NEEDED",
            "TAKEOFF_FAILURE_ERROR",
            "MOTOR_START_FAILURE_ERROR",
        ).forEach { fact -> assertTrue(source.contains("ObservedKey.$fact"), "Missing raw fact mapping for $fact") }
        assertTrue(source.contains("manager.listen(gpsSignalLevelKey, flightControllerOwner)"))
        assertTrue(source.contains("manager.listen(visionSystemWarningKey, flightControllerOwner)"))
        assertTrue(source.contains("requestInitialValue(gpsSignalLevelKey, ObservedKey.GPS_SIGNAL_LEVEL,"))
        assertTrue(source.contains("requestInitialValue(visionSystemWarningKey, ObservedKey.VISION_SYSTEM_WARNING,"))
        assertTrue(source.contains("next?.name"))
    }
}
