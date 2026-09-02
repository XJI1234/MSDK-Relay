package com.skycommand.relay.telemetry.flight.android

import com.skycommand.relay.telemetry.snapshot.FlightTelemetrySnapshot
import com.skycommand.relay.telemetry.snapshot.LowBatteryRthState
import com.skycommand.relay.device.state.LinkState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidFlightTelemetrySourceContractTest {
    @Test
    fun exposesSeparateRawGpsVisionAndTakeoffDiagnosticFacts() {
        val names = FlightTelemetrySnapshot::class.java.declaredFields.map { it.name }.toSet()

        assertEquals(
            setOf(
                "gpsSignalLevel",
                "gpsSatelliteCount",
                "visionSensorUsed",
                "visionSystemWarning",
                "visionPositioningEnabled",
                "landingProtectionState",
                "landingConfirmationNeeded",
                "takeoffFailureError",
                "motorStartFailureError",
            ),
            setOf(
                "gpsSignalLevel",
                "gpsSatelliteCount",
                "visionSensorUsed",
                "visionSystemWarning",
                "visionPositioningEnabled",
                "landingProtectionState",
                "landingConfirmationNeeded",
                "takeoffFailureError",
                "motorStartFailureError",
            ).intersect(names),
        )
    }

    @Test
    fun publishesTheNormalizedInitialPlatformSnapshot() {
        val platform = FakePlatform(
            FlightTelemetryFact(true, true, "WAYPOINT", 82, 420, 73.5, 30.1, 120.2, LowBatteryRthState.IDLE, LinkState.CONNECTED),
        )
        val source = AndroidFlightTelemetrySource(platform)
        var changes = 0

        source.onChanged { changes += 1 }

        assertEquals(1, changes)
        assertEquals(
            FlightTelemetrySnapshot(true, true, "WAYPOINT", 82, 420, 73.5, 30.1, 120.2, LowBatteryRthState.IDLE, LinkState.CONNECTED),
            source.snapshot(),
        )
    }

    @Test
    fun normalizesEveryInvalidOptionalPlatformValueToUnknown() {
        val platform = FakePlatform(FlightTelemetryFact(null, null, " \n", 101, -1, Double.NaN, 91.0, 181.0))
        val source = AndroidFlightTelemetrySource(platform)

        source.onChanged { }

        assertEquals(FlightTelemetrySnapshot(), source.snapshot())
    }

    @Test
    fun preservesAnExplicitUnknownFlightModeFromMsdk() {
        val source = AndroidFlightTelemetrySource(FakePlatform(FlightTelemetryFact(flightMode = "UNKNOWN")))

        source.onChanged { }

        assertEquals("UNKNOWN", source.snapshot().flightMode)
    }

    @Test
    fun doesNotTreatAnUnknownLowBatteryRthDefaultZeroAsAnEstimate() {
        val source = AndroidFlightTelemetrySource(
            FakePlatform(FlightTelemetryFact(remainingFlightTimeSeconds = 0)),
        )

        source.onChanged { }

        assertEquals(null, source.snapshot().remainingFlightTimeSeconds)
    }

    @Test
    fun keepsOneObservationAndIgnoresCallbacksAfterCancellation() {
        val platform = FakePlatform(FlightTelemetryFact())
        val source = AndroidFlightTelemetrySource(platform)
        var first = 0
        var replacement = 0
        val registration = source.onChanged { first += 1 }
        val stale = platform.listenerOrThrow()

        source.onChanged { replacement += 1 }.unregister()
        platform.publish(FlightTelemetryFact(isFlying = true))
        registration.unregister()
        registration.unregister()
        stale.onChanged(FlightTelemetryFact(isFlying = false))

        assertEquals(2, first)
        assertEquals(0, replacement)
        assertEquals(true, source.snapshot().isFlying)
        assertEquals(1, platform.observeCalls)
        assertEquals(1, platform.closeCalls)
    }

    @Test
    fun preservesBatteryFactsWhileInvalidatingAndRefreshingFlightControllerFacts() {
        val platform = FakePlatform()
        val source = AndroidFlightTelemetrySource(platform)
        source.onChanged { }

        platform.publish(FlightTelemetryFact(batteryPercent = 80, altitudeMeters = 12.0, battery = LinkState.CONNECTED))
        assertEquals(80, source.snapshot().batteryPercent)

        source.invalidateFlightControllerFacts()
        assertEquals(LinkState.CONNECTED, source.snapshot().battery)
        assertEquals(80, source.snapshot().batteryPercent)
        assertEquals(null, source.snapshot().altitudeMeters)
        assertEquals(1, platform.flightControllerInvalidations)

        source.refreshFlightControllerFacts()
        assertEquals(1, platform.flightControllerRefreshes)
        platform.publish(FlightTelemetryFact(batteryPercent = 79, altitudeMeters = 13.0, battery = LinkState.CONNECTED))
        assertEquals(79, source.snapshot().batteryPercent)
        assertEquals(13.0, source.snapshot().altitudeMeters)
        assertEquals(1, platform.observeCalls)
    }

    @Test
    fun startsANewGenerationAfterCloseAndContainsListenerAndReleaseFailures() {
        val platform = FakePlatform(FlightTelemetryFact(), throwOnClose = true)
        val source = AndroidFlightTelemetrySource(platform)
        source.onChanged { error("listener failed") }
        source.close()
        source.close()

        var delivered = 0
        source.onChanged { delivered += 1 }
        platform.publish(FlightTelemetryFact(motorsOn = true))

        assertEquals(2, delivered)
        assertEquals(true, source.snapshot().motorsOn)
        assertEquals(2, platform.observeCalls)
    }

    @Test
    fun mapsPlatformRegistrationFailureToAStableReason() {
        val source = AndroidFlightTelemetrySource(FakePlatform(throwOnObserve = true))

        val failure = assertFailsWith<IllegalStateException> { source.onChanged { } }

        assertEquals("flight telemetry listener unavailable", failure.message)
    }

    private class FakePlatform(
        private val initial: FlightTelemetryFact? = null,
        private val throwOnObserve: Boolean = false,
        private val throwOnClose: Boolean = false,
    ) : DjiFlightTelemetryApi {
        var observeCalls = 0
        var closeCalls = 0
        var flightControllerInvalidations = 0
        var flightControllerRefreshes = 0
        private var listener: DjiFlightTelemetryListener? = null

        override fun observe(listener: DjiFlightTelemetryListener): DjiFlightTelemetryObservation {
            if (throwOnObserve) error("DJI registration failed")
            observeCalls += 1
            this.listener = listener
            initial?.let(listener::onChanged)
            return object : DjiFlightTelemetryObservation {
                override fun invalidateFlightControllerFacts() {
                    flightControllerInvalidations += 1
                }

                override fun refreshFlightControllerFacts() {
                    flightControllerRefreshes += 1
                }

                override fun close() {
                    closeCalls += 1
                    if (throwOnClose) error("DJI close failed")
                }
            }
        }

        fun publish(fact: FlightTelemetryFact) = listenerOrThrow().onChanged(fact)

        fun listenerOrThrow(): DjiFlightTelemetryListener = checkNotNull(listener)
    }
}
