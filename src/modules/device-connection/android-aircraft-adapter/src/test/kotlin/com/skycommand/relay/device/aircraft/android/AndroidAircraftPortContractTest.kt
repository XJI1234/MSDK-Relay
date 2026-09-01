package com.skycommand.relay.device.aircraft.android

import com.skycommand.relay.device.aircraft.AircraftListener
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidAircraftPortContractTest {

    @Test
    fun preservesUnobservedPlatformLinksInsteadOfSeedingDisconnectedDefaults() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/device/aircraft/android/MsdkV5AircraftApi.kt"),
            Path("src/modules/device-connection/android-aircraft-adapter/src/main/kotlin/com/skycommand/relay/device/aircraft/android/MsdkV5AircraftApi.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("private var aircraftConnected: Boolean? = null"))
        assertTrue(source.contains("private var flightControllerConnected: Boolean? = null"))
        assertFalse(source.contains("next == true"))
    }
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
    fun preservesAnIndependentlyObservedFlightControllerFactWhenProductIsDisconnected() {
        val platform = FakePlatform(initial = Fact(false, true, "M350 RTK"))
        val port = AndroidAircraftPort(platform)
        val received = mutableListOf<String>()

        port.start(AircraftListener { signal ->
            received += "${signal.aircraftConnected}:${signal.flightControllerConnected}:${signal.displayModel}"
        })
        platform.publish(true, true, "  ")

        assertEquals(listOf("false:true:null", "true:true:null"), received)
    }

    @Test
    fun preservesProductConnectionWhenFlightControllerIsDisconnected() {
        val platform = FakePlatform(initial = Fact(true, false, "M350 RTK"))
        val port = AndroidAircraftPort(platform)
        val received = mutableListOf<String>()

        port.start(AircraftListener { signal ->
            received += "${signal.aircraftConnected}:${signal.flightControllerConnected}:${signal.displayModel}"
        })
        platform.publish(true, true, "M350 RTK")

        assertEquals(listOf("true:false:M350 RTK", "true:true:M350 RTK"), received)
    }

    @Test
    fun preservesProductConnectionWhenFlightControllerBecomesUnknown() {
        val platform = FakePlatform(initial = Fact(true, true, "M350 RTK"))
        val port = AndroidAircraftPort(platform)
        val received = mutableListOf<String>()

        port.start(AircraftListener { signal ->
            received += "${signal.aircraftConnected}:${signal.flightControllerConnected}:${signal.displayModel}"
        })
        platform.publish(true, null, "M350 RTK")

        assertEquals(listOf("true:true:M350 RTK", "true:null:M350 RTK"), received)
    }

    @Test
    fun forwardsAirLinkAndPrimaryCameraFactsWithoutUsingFlightControllerAsTheirProxy() {
        val port = AndroidAircraftPort(
            FakePlatform(initial = Fact(true, false, "M350 RTK", airLinkConnected = true, cameraConnected = true)),
        )
        val received = mutableListOf<String>()

        port.start(AircraftListener { signal ->
            received += "${signal.airLinkConnected}:${signal.cameraConnected}:${signal.flightControllerConnected}"
        })

        assertEquals(listOf("true:true:false"), received)
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

    @Test
    fun observeDoesNotSeedDisconnectedFromMissingKeyDefaults() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/device/aircraft/android/MsdkV5AircraftApi.kt"),
            Path("src/modules/device-connection/android-aircraft-adapter/src/main/kotlin/com/skycommand/relay/device/aircraft/android/MsdkV5AircraftApi.kt"),
        ).first { it.exists() }.readText()
        assertTrue(source.contains("manager.listen(aircraftKey"))
        assertFalse(source.contains("manager.getValue(aircraftKey, false)"))
        assertFalse(source.contains("manager.getValue(flightControllerKey, false)"))
    }

    @Test
    fun recordsRawAircraftAndFlightControllerKeyTransitionsForLinkDiagnosis() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/device/aircraft/android/MsdkV5AircraftApi.kt"),
            Path("src/modules/device-connection/android-aircraft-adapter/src/main/kotlin/com/skycommand/relay/device/aircraft/android/MsdkV5AircraftApi.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("[DEBUG-link-order]"))
        assertTrue(source.contains("ProductKey.KeyConnection"))
        assertTrue(source.contains("FlightControllerKey.KeyConnection"))
        assertTrue(source.contains("Log.i("))
    }

    @Test
    fun observesRawAirLinkAndPrimaryCameraConnectionKeys() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/device/aircraft/android/MsdkV5AircraftApi.kt"),
            Path("src/modules/device-connection/android-aircraft-adapter/src/main/kotlin/com/skycommand/relay/device/aircraft/android/MsdkV5AircraftApi.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("AirLinkKey.KeyConnection"))
        assertTrue(source.contains("CameraKey.KeyConnection"))
        assertTrue(source.contains("ComponentIndexType.LEFT_OR_MAIN"))
    }

    @Test
    fun requestsAnInitialHardwareValueForEveryConnectionKeyWhileListening() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/device/aircraft/android/MsdkV5AircraftApi.kt"),
            Path("src/modules/device-connection/android-aircraft-adapter/src/main/kotlin/com/skycommand/relay/device/aircraft/android/MsdkV5AircraftApi.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("manager.listen(aircraftKey, owner)"))
        assertTrue(source.contains("manager.listen(airLinkKey, owner)"))
        assertTrue(source.contains("manager.listen(cameraKey, owner)"))
        assertTrue(source.contains("manager.listen(flightControllerKey, owner)"))
        assertTrue(source.contains("requestInitialConnection(aircraftKey, ConnectionKey.PRODUCT)"))
        assertTrue(source.contains("requestInitialConnection(airLinkKey, ConnectionKey.AIR_LINK)"))
        assertTrue(source.contains("requestInitialConnection(cameraKey, ConnectionKey.CAMERA)"))
        assertTrue(source.contains("requestInitialConnection(flightControllerKey, ConnectionKey.FLIGHT_CONTROLLER)"))
        assertTrue(source.contains("requestInitialValue(productTypeKey"))
        assertTrue(source.contains("manager.getValue(key, object : CommonCallbacks.CompletionCallbackWithParam<T>"))
        assertTrue(source.contains("connectionEventRevisions[key] != initialEventRevision"))
        assertFalse(source.contains("manager.getValue<Boolean>(aircraftKey)"))
        assertFalse(source.contains("manager.getValue<Boolean>(airLinkKey)"))
        assertFalse(source.contains("manager.getValue<Boolean>(cameraKey)"))
        assertFalse(source.contains("manager.getValue<Boolean>(flightControllerKey)"))
        assertFalse(source.contains("manager.listen(aircraftKey, owner, true)"))
        assertFalse(source.contains("manager.listen(airLinkKey, owner, true)"))
        assertFalse(source.contains("manager.listen(cameraKey, owner, true)"))
        assertFalse(source.contains("manager.listen(flightControllerKey, owner, true)"))
    }

    @Test
    fun keepsUnknownUntilTheOneTimeHardwareReadsReportTheirFacts() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/device/aircraft/android/MsdkV5AircraftApi.kt"),
            Path("src/modules/device-connection/android-aircraft-adapter/src/main/kotlin/com/skycommand/relay/device/aircraft/android/MsdkV5AircraftApi.kt"),
        ).first { it.exists() }.readText()

        assertFalse(source.contains("publishInitialFact()"))
        assertFalse(source.contains("manager.getValue<"))
    }

    private data class Fact(
        val aircraftConnected: Boolean?,
        val flightControllerConnected: Boolean?,
        val displayModel: String? = null,
        val airLinkConnected: Boolean? = null,
        val cameraConnected: Boolean? = null,
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
            initial?.let {
                listener.onChanged(
                    DjiAircraftFact(
                        aircraftConnected = it.aircraftConnected,
                        flightControllerConnected = it.flightControllerConnected,
                        displayModel = it.displayModel,
                        airLinkConnected = it.airLinkConnected,
                        cameraConnected = it.cameraConnected,
                    ),
                )
            }
            return DjiAircraftObservation {
                closeCalls += 1
                if (throwOnClose) error("DJI removal failure")
            }
        }

        fun publish(aircraftConnected: Boolean?, flightControllerConnected: Boolean?, displayModel: String? = null) {
            listenerOrThrow().onChanged(DjiAircraftFact(aircraftConnected, flightControllerConnected, displayModel))
        }

        fun listenerOrThrow(): DjiAircraftListener = checkNotNull(listener)
    }
}
