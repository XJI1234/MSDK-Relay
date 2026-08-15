package com.skycommand.relay.device.aircraft.android

import com.skycommand.relay.device.aircraft.AircraftListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidAircraftPortContractTest {
    @Test
    fun publishesAnInitialConnectedAircraftFact() {
        val platform = FakePlatform(initial = Fact(true, true, "M350 RTK"))
        val port = AndroidAircraftPort(platform)
        val received = mutableListOf<String>()

        port.start(AircraftListener { signal ->
            received += "${signal.sourceRevision}:${signal.aircraftConnected}:${signal.flightControllerConnected}:${signal.displayModel}"
        })

        assertEquals(listOf("1:true:true:M350 RTK"), received)
    }

    @Test
    fun normalizesAnInitiallyDisconnectedAircraftFact() {
        val platform = FakePlatform(initial = Fact(false, true, "M350 RTK"))
        val port = AndroidAircraftPort(platform)
        val received = mutableListOf<String>()

        port.start(AircraftListener { signal ->
            received += "${signal.aircraftConnected}:${signal.flightControllerConnected}:${signal.displayModel}"
        })
        platform.publish(true, true, "  ")

        assertEquals(listOf("false:false:null", "true:true:null"), received)
    }

    @Test
    fun productConnectionWithoutFlightControllerIsNotAircraftConnected() {
        val platform = FakePlatform(initial = Fact(true, false, "M350 RTK"))
        val port = AndroidAircraftPort(platform)
        val received = mutableListOf<String>()

        port.start(AircraftListener { signal ->
            received += "${signal.aircraftConnected}:${signal.flightControllerConnected}:${signal.displayModel}"
        })
        platform.publish(true, true, "M350 RTK")

        assertEquals(listOf("false:false:null", "true:true:M350 RTK"), received)
    }

    @Test
    fun repeatedStartKeepsTheOriginalObservation() {
        val platform = FakePlatform()
        val port = AndroidAircraftPort(platform)
        var original = 0
        var replacement = 0

        port.start(AircraftListener { original += 1 })
        port.start(AircraftListener { replacement += 1 }).cancel()
        platform.publish(true, true)

        assertEquals(1, platform.observeCalls)
        assertEquals(1, original)
        assertEquals(0, replacement)
    }

    @Test
    fun ignoresStaleCallbacksAndKeepsRevisionsIncreasingAcrossRestart() {
        val platform = FakePlatform()
        val port = AndroidAircraftPort(platform)
        val revisions = mutableListOf<Long>()

        val subscription = port.start(AircraftListener { revisions += it.sourceRevision })
        val stale = platform.listenerOrThrow()
        subscription.cancel()
        subscription.cancel()
        stale.onChanged(DjiAircraftFact(true, true, "old"))

        port.start(AircraftListener { revisions += it.sourceRevision })
        platform.publish(true, true, "current")
        port.stop()
        platform.listenerOrThrow().onChanged(DjiAircraftFact(true, true, "stopped"))
        port.start(AircraftListener { revisions += it.sourceRevision })
        platform.publish(false, false)

        assertEquals(listOf(1L, 2L), revisions)
        assertEquals(2, platform.closeCalls)
    }

    @Test
    fun convertsPlatformRegistrationFailureIntoAStableFailure() {
        val port = AndroidAircraftPort(FakePlatform(throwOnObserve = true))

        val failure = assertFailsWith<IllegalStateException> {
            port.start(AircraftListener { })
        }

        assertEquals("aircraft listener unavailable", failure.message)
    }

    @Test
    fun containsPlatformReleaseAndListenerFailures() {
        val platform = FakePlatform(throwOnClose = true)
        val port = AndroidAircraftPort(platform)

        port.start(AircraftListener { error("listener failed") })
        platform.publish(true, true)
        port.stop()
        port.stop()

        var delivered = 0
        port.start(AircraftListener { delivered += 1 })
        platform.publish(true, false)

        assertEquals(1, delivered)
    }

    private data class Fact(
        val aircraftConnected: Boolean,
        val flightControllerConnected: Boolean,
        val displayModel: String? = null,
    )

    private class FakePlatform(
        private val initial: Fact? = null,
        private val throwOnObserve: Boolean = false,
        private val throwOnClose: Boolean = false,
    ) : DjiAircraftApi {
        var observeCalls = 0
        var closeCalls = 0
        private var listener: DjiAircraftListener? = null

        override fun observe(listener: DjiAircraftListener): DjiAircraftObservation {
            if (throwOnObserve) error("DJI registration failure")
            observeCalls += 1
            this.listener = listener
            initial?.let { listener.onChanged(DjiAircraftFact(it.aircraftConnected, it.flightControllerConnected, it.displayModel)) }
            return DjiAircraftObservation {
                closeCalls += 1
                if (throwOnClose) error("DJI removal failure")
            }
        }

        fun publish(aircraftConnected: Boolean, flightControllerConnected: Boolean, displayModel: String? = null) {
            listenerOrThrow().onChanged(DjiAircraftFact(aircraftConnected, flightControllerConnected, displayModel))
        }

        fun listenerOrThrow(): DjiAircraftListener = checkNotNull(listener)
    }
}
