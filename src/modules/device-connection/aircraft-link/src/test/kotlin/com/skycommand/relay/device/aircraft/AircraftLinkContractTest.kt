package com.skycommand.relay.device.aircraft

import com.skycommand.relay.device.state.DeviceStateStore
import com.skycommand.relay.device.state.LinkState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AircraftLinkContractTest {

    @Test
    fun normalizesAircraftAndFlightControllerFactsWithoutTouchingRemoteController() {
        val fixture = Fixture()
        fixture.link.start()
        fixture.port.emit(AircraftSignal(1, true, true, "Matrice 4"))

        assertEquals(LinkState.CONNECTED, fixture.store.snapshot().aircraft)
        assertEquals(LinkState.CONNECTED, fixture.store.snapshot().flightController)
        assertEquals("Matrice 4", fixture.store.snapshot().aircraftModel)
        assertEquals(LinkState.UNKNOWN, fixture.store.snapshot().remoteController)
    }

    @Test
    fun preservesAnIndependentlyObservedFlightControllerFactWhenProductIsDisconnected() {
        val fixture = Fixture()
        fixture.link.start()
        fixture.port.emit(AircraftSignal(1, false, true, "Matrice 4"))

        assertEquals(LinkState.DISCONNECTED, fixture.store.snapshot().aircraft)
        assertEquals(LinkState.CONNECTED, fixture.store.snapshot().flightController)
    }

    @Test
    fun preservesConnectedAircraftWhenFlightControllerIsUnavailable() {
        val fixture = Fixture()
        fixture.link.start()

        fixture.port.emit(AircraftSignal(1, true, false, "Matrice 4"))

        assertEquals(LinkState.CONNECTED, fixture.store.snapshot().aircraft)
        assertEquals(LinkState.DISCONNECTED, fixture.store.snapshot().flightController)
        assertEquals("Matrice 4", fixture.store.snapshot().aircraftModel)
    }

    @Test
    fun preservesRawAirLinkAndPrimaryCameraFactsIndependentlyOfFlightController() {
        val fixture = Fixture()
        fixture.link.start()

        fixture.port.emit(
            AircraftSignal(
                sourceRevision = 1,
                aircraftConnected = true,
                flightControllerConnected = false,
                displayModel = "Matrice 4",
                airLinkConnected = true,
                cameraConnected = true,
            ),
        )

        assertEquals(LinkState.CONNECTED, fixture.store.snapshot().airLink)
        assertEquals(LinkState.CONNECTED, fixture.store.snapshot().camera)
        assertEquals(LinkState.DISCONNECTED, fixture.store.snapshot().flightController)
    }

    @Test
    fun ignoresStaleSignalsAndOldCallbacksAfterStop() {
        val fixture = Fixture()
        fixture.link.start()
        fixture.port.emit(AircraftSignal(2, true, true, "Matrice"))
        fixture.port.emit(AircraftSignal(1, false, false, null))
        fixture.link.stop()
        fixture.port.emitOld(AircraftSignal(3, false, false, null))

        assertEquals(LinkState.CONNECTED, fixture.store.snapshot().aircraft)
        assertEquals(LinkState.CONNECTED, fixture.store.snapshot().flightController)
    }

    @Test
    fun supportsSynchronousRegistrationSignalsButDiscardsThemWhenRegistrationFails() {
        val successfulPort = SynchronousPort(fails = false)
        val successfulStore = DeviceStateStore.create()
        AircraftLink.create(successfulStore, successfulPort).start()
        assertEquals(LinkState.CONNECTED, successfulStore.snapshot().aircraft)

        val failingStore = DeviceStateStore.create()
        val result = AircraftLink.create(failingStore, SynchronousPort(fails = true)).start()
        assertIs<AircraftStartResult.Rejected>(result)
        assertEquals(LinkState.UNKNOWN, failingStore.snapshot().aircraft)
    }

    @Test
    fun preservesAnIndependentlyObservedFlightControllerFactWhenProductIsUnknown() {
        val fixture = Fixture()
        fixture.link.start()

        fixture.port.emit(AircraftSignal(1, aircraftConnected = null, flightControllerConnected = true, displayModel = "Matrice"))

        assertEquals(LinkState.UNKNOWN, fixture.store.snapshot().aircraft)
        assertEquals(LinkState.CONNECTED, fixture.store.snapshot().flightController)
        assertEquals(null, fixture.store.snapshot().aircraftModel)
    }

    @Test
    fun recordsPortFailureWhenObservationCannotStart() {
        val diagnostics = mutableListOf<AircraftDiagnosticKind>()
        val link = AircraftLink.create(
            DeviceStateStore.create(),
            object : AircraftPort {
                override fun start(listener: AircraftListener): AircraftPortSubscription = error("unavailable")
                override fun stop() = Unit
            },
            AircraftDiagnosticSink { diagnostics += it.kind },
        )

        assertIs<AircraftStartResult.Rejected>(link.start())

        assertEquals(listOf(AircraftDiagnosticKind.PORT_FAILURE), diagnostics)
    }

    private class Fixture {
        val store = DeviceStateStore.create()
        val port = RecordingPort()
        val link = AircraftLink.create(store, port)
    }

    private class RecordingPort : AircraftPort {
        private var listener: AircraftListener? = null
        private var oldListener: AircraftListener? = null

        override fun start(listener: AircraftListener): AircraftPortSubscription {
            this.listener = listener
            this.oldListener = listener
            return AircraftPortSubscription { this.listener = null }
        }

        override fun stop() {
            listener = null
        }

        fun emit(signal: AircraftSignal) = listener?.onChanged(signal)

        fun emitOld(signal: AircraftSignal) = oldListener?.onChanged(signal)
    }

    private class SynchronousPort(private val fails: Boolean) : AircraftPort {
        override fun start(listener: AircraftListener): AircraftPortSubscription {
            listener.onChanged(AircraftSignal(1, true, true, "Matrice"))
            if (fails) error("registration failed")
            return AircraftPortSubscription { }
        }

        override fun stop() = Unit
    }
}
