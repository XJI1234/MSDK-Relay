package com.skycommand.relay.device.remote

import com.skycommand.relay.device.state.DeviceStateStore
import com.skycommand.relay.device.state.LinkState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RemoteControllerLinkContractTest {

    @Test
    fun normalizesSignalsIntoOnlyRemoteControllerFacts() {
        val fixture = Fixture()
        fixture.link.start()
        fixture.port.emit(RemoteControllerSignal(1, connected = true, displayModel = "RC Plus"))

        val snapshot = fixture.store.snapshot()
        assertEquals(LinkState.CONNECTED, snapshot.remoteController)
        assertEquals("RC Plus", snapshot.remoteControllerModel)
        assertEquals(LinkState.DISCONNECTED, snapshot.aircraft)
        assertEquals(LinkState.DISCONNECTED, snapshot.flightController)
    }

    @Test
    fun startAndStopAreIdempotentAndDoNotDuplicatePortSubscriptions() {
        val fixture = Fixture()

        assertIs<RemoteControllerStartResult.Started>(fixture.link.start())
        assertIs<RemoteControllerStartResult.AlreadyStarted>(fixture.link.start())
        assertEquals(1, fixture.port.startCalls)

        assertIs<RemoteControllerStopResult.Stopped>(fixture.link.stop())
        assertIs<RemoteControllerStopResult.AlreadyStopped>(fixture.link.stop())
        assertEquals(1, fixture.port.stopCalls)
    }

    @Test
    fun ignoresOldCallbackAfterStopAndOldSourceVersions() {
        val fixture = Fixture()
        fixture.link.start()
        fixture.port.emit(RemoteControllerSignal(2, true, "RC"))
        fixture.port.emit(RemoteControllerSignal(1, false, null))
        fixture.link.stop()
        fixture.port.emit(RemoteControllerSignal(3, false, null))

        assertEquals(LinkState.CONNECTED, fixture.store.snapshot().remoteController)
        assertEquals("RC", fixture.store.snapshot().remoteControllerModel)
    }

    @Test
    fun containsPortAndSignalFailuresWithoutLeakingDetails() {
        val diagnostics = mutableListOf<RemoteControllerDiagnostic>()
        val fixture = Fixture(
            diagnostics = diagnostics,
            port = RecordingPort(startFailure = IllegalStateException("secret sdk detail")),
        )

        val result = fixture.link.start()

        assertEquals(RemoteControllerStartResult.Rejected("remote controller listener unavailable"), result)
        assertEquals(1, diagnostics.size)
    }

    @Test
    fun discardsSignalsEmittedByARegistrationThatUltimatelyFails() {
        val store = DeviceStateStore.create()
        val link = RemoteControllerLink.create(store, EmitsThenThrowsPort())

        assertEquals(
            RemoteControllerStartResult.Rejected("remote controller listener unavailable"),
            link.start(),
        )

        assertEquals(LinkState.DISCONNECTED, store.snapshot().remoteController)
    }

    private class Fixture(
        val diagnostics: MutableList<RemoteControllerDiagnostic> = mutableListOf(),
        val port: RecordingPort = RecordingPort(),
    ) {
        val store = DeviceStateStore.create()
        val link = RemoteControllerLink.create(store, port) { diagnostics += it }
    }

    private class RecordingPort(
        private val startFailure: Throwable? = null,
    ) : RemoteControllerPort {
        var startCalls = 0
        var stopCalls = 0
        private var listener: RemoteControllerListener? = null

        override fun start(listener: RemoteControllerListener): PortSubscription {
            startCalls += 1
            startFailure?.let { throw it }
            this.listener = listener
            return PortSubscription { this.listener = null }
        }

        override fun stop() {
            stopCalls += 1
            listener = null
        }

        fun emit(signal: RemoteControllerSignal) {
            listener?.onChanged(signal)
        }
    }

    private class EmitsThenThrowsPort : RemoteControllerPort {
        override fun start(listener: RemoteControllerListener): PortSubscription {
            listener.onChanged(RemoteControllerSignal(1, true, "RC"))
            error("registration failed after synchronous callback")
        }

        override fun stop() = Unit
    }
}
