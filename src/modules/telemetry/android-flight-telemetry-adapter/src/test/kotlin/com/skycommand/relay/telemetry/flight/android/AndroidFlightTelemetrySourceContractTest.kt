package com.skycommand.relay.telemetry.flight.android

import com.skycommand.relay.telemetry.snapshot.FlightTelemetrySnapshot
import com.skycommand.relay.telemetry.snapshot.LowBatteryRthState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidFlightTelemetrySourceContractTest {
    @Test
    fun publishesTheNormalizedInitialPlatformSnapshot() {
        val platform = FakePlatform(
            FlightTelemetryFact(true, true, "WAYPOINT", 82, 420, 73.5, 30.1, 120.2, LowBatteryRthState.IDLE),
        )
        val source = AndroidFlightTelemetrySource(platform)
        var changes = 0

        source.onChanged { changes += 1 }

        assertEquals(1, changes)
        assertEquals(
            FlightTelemetrySnapshot(true, true, "WAYPOINT", 82, 420, 73.5, 30.1, 120.2, LowBatteryRthState.IDLE),
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
        private var listener: DjiFlightTelemetryListener? = null

        override fun observe(listener: DjiFlightTelemetryListener): DjiFlightTelemetryObservation {
            if (throwOnObserve) error("DJI registration failed")
            observeCalls += 1
            this.listener = listener
            initial?.let(listener::onChanged)
            return DjiFlightTelemetryObservation {
                closeCalls += 1
                if (throwOnClose) error("DJI close failed")
            }
        }

        fun publish(fact: FlightTelemetryFact) = listenerOrThrow().onChanged(fact)

        fun listenerOrThrow(): DjiFlightTelemetryListener = checkNotNull(listener)
    }
}
