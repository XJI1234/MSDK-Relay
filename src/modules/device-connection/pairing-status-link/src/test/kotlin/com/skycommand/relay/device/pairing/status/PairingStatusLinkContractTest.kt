package com.skycommand.relay.device.pairing.status

import com.skycommand.relay.device.state.DeviceStateStore
import com.skycommand.relay.device.state.PairingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PairingStatusLinkContractTest {
    @Test
    fun appliesEveryDomainPairingStateFromAnInitialSynchronousSignal() {
        PairingState.entries.forEach { state ->
            val platform = FakePlatform(initial = Signal(1, state))
            val store = DeviceStateStore.create()
            val link = PairingStatusLink.create(store, platform)

            assertEquals(PairingStatusStartResult.Started, link.start())

            assertEquals(state, store.snapshot().pairing)
        }
    }

    @Test
    fun appliesLaterSignalsWithIncreasingSourceRevisions() {
        val fixture = fixture()
        fixture.start()

        fixture.platform.emit(Signal(1, PairingState.IDLE))
        fixture.platform.emit(Signal(2, PairingState.PAIRING))
        fixture.platform.emit(Signal(3, PairingState.PAIRED))

        assertEquals(PairingState.PAIRED, fixture.store.snapshot().pairing)
    }

    @Test
    fun ignoresAStaleSignalWithoutStoppingObservation() {
        val fixture = fixture()
        fixture.start()
        fixture.platform.emit(Signal(2, PairingState.PAIRING))
        fixture.platform.emit(Signal(1, PairingState.IDLE))
        fixture.platform.emit(Signal(3, PairingState.PAIRED))

        assertEquals(PairingState.PAIRED, fixture.store.snapshot().pairing)
        assertEquals(emptyList(), fixture.diagnostics)
    }

    @Test
    fun startsOnlyOnePlatformObservation() {
        val fixture = fixture()

        assertEquals(PairingStatusStartResult.Started, fixture.link.start())
        assertEquals(PairingStatusStartResult.AlreadyStarted, fixture.link.start())

        assertEquals(1, fixture.platform.startCalls)
    }

    @Test
    fun stopsAndDoesNotAcceptLateSignals() {
        val fixture = fixture()
        fixture.start()
        val lateListener = fixture.platform.listener

        assertEquals(PairingStatusStopResult.Stopped, fixture.link.stop())
        lateListener?.onChanged(PairingStatusSignal(1, PairingState.PAIRED))

        assertEquals(PairingState.UNKNOWN, fixture.store.snapshot().pairing)
        assertEquals(1, fixture.platform.cancelCalls)
        assertEquals(1, fixture.platform.stopCalls)
        assertEquals(PairingStatusStopResult.AlreadyStopped, fixture.link.stop())
    }

    @Test
    fun ignoresCallbacksFromThePreviousRunAfterRestart() {
        val fixture = fixture()
        fixture.start()
        val previousListener = fixture.platform.listener
        fixture.link.stop()

        fixture.start()
        previousListener?.onChanged(PairingStatusSignal(1, PairingState.PAIRED))
        fixture.platform.emit(Signal(2, PairingState.IDLE))

        assertEquals(PairingState.IDLE, fixture.store.snapshot().pairing)
    }

    @Test
    fun buffersSynchronousSignalsUntilTheSubscriptionIsInstalled() {
        val fixture = fixture(initial = Signal(1, PairingState.PAIRING))

        fixture.start()

        assertEquals(PairingState.PAIRING, fixture.store.snapshot().pairing)
    }

    @Test
    fun rejectsAPlatformStartFailureWithoutLeavingTheLinkActive() {
        val fixture = fixture()
        fixture.platform.throwOnStart = true

        assertEquals(
            PairingStatusStartResult.Rejected("pairing status listener unavailable"),
            fixture.link.start(),
        )
        assertEquals(PairingStatusStopResult.AlreadyStopped, fixture.link.stop())
        assertEquals(listOf(PairingStatusDiagnosticKind.PORT_FAILURE), fixture.diagnostics)
    }

    @Test
    fun containsReleaseFailuresAndRejectsLateSignals() {
        val fixture = fixture()
        fixture.start()
        val lateListener = fixture.platform.listener
        fixture.platform.throwOnCancel = true
        fixture.platform.throwOnStop = true

        assertEquals(PairingStatusStopResult.Stopped, fixture.link.stop())
        lateListener?.onChanged(PairingStatusSignal(1, PairingState.PAIRED))

        assertEquals(PairingState.UNKNOWN, fixture.store.snapshot().pairing)
        assertEquals(
            listOf(PairingStatusDiagnosticKind.PORT_FAILURE, PairingStatusDiagnosticKind.PORT_FAILURE),
            fixture.diagnostics,
        )
    }

    @Test
    fun containsInvalidSignalsAndAcceptsTheNextValidSignal() {
        val fixture = fixture()
        fixture.start()

        fixture.platform.emit(Signal(0, PairingState.PAIRING))
        fixture.platform.emit(Signal(1, PairingState.IDLE))

        assertEquals(PairingState.IDLE, fixture.store.snapshot().pairing)
        assertEquals(listOf(PairingStatusDiagnosticKind.INVALID_SIGNAL), fixture.diagnostics)
    }

    @Test
    fun containsDiagnosticSinkFailures() {
        val platform = FakePlatform()
        val link = PairingStatusLink.create(
            store = DeviceStateStore.create(),
            port = platform,
            diagnosticSink = PairingStatusDiagnosticSink { throw IllegalStateException("diagnostic failure") },
        )
        platform.throwOnStart = true

        assertIs<PairingStatusStartResult.Rejected>(link.start())
    }

    private fun fixture(initial: Signal? = null): Fixture {
        val diagnostics = mutableListOf<PairingStatusDiagnosticKind>()
        val platform = FakePlatform(initial)
        val store = DeviceStateStore.create()
        return Fixture(
            store = store,
            platform = platform,
            diagnostics = diagnostics,
            link = PairingStatusLink.create(
                store = store,
                port = platform,
                diagnosticSink = PairingStatusDiagnosticSink { diagnostics += it.kind },
            ),
        )
    }

    private data class Fixture(
        val store: DeviceStateStore,
        val platform: FakePlatform,
        val diagnostics: MutableList<PairingStatusDiagnosticKind>,
        val link: PairingStatusLink,
    ) {
        fun start() = assertEquals(PairingStatusStartResult.Started, link.start())
    }

    private data class Signal(
        val revision: Long,
        val state: PairingState,
    )

    private class FakePlatform(
        private val initial: Signal? = null,
    ) : PairingStatusPort {
        var startCalls = 0
        var stopCalls = 0
        var cancelCalls = 0
        var throwOnStart = false
        var throwOnCancel = false
        var throwOnStop = false
        var listener: PairingStatusListener? = null

        override fun start(listener: PairingStatusListener): PairingStatusSubscription {
            startCalls += 1
            if (throwOnStart) throw IllegalStateException("platform unavailable")
            this.listener = listener
            initial?.let { listener.onChanged(PairingStatusSignal(it.revision, it.state)) }
            return PairingStatusSubscription {
                cancelCalls += 1
                if (throwOnCancel) throw IllegalStateException("cannot cancel")
            }
        }

        override fun stop() {
            stopCalls += 1
            if (throwOnStop) throw IllegalStateException("cannot stop")
        }

        fun emit(signal: Signal) {
            listener?.onChanged(PairingStatusSignal(signal.revision, signal.state))
        }
    }
}
