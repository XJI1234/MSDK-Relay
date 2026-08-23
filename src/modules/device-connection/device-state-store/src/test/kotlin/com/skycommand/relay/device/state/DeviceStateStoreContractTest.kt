package com.skycommand.relay.device.state

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class DeviceStateStoreContractTest {

    @Test
    fun exposesTheStableDisconnectedInitialSnapshot() {
        val store = DeviceStateStore.create()

        assertEquals(
            DeviceSnapshot(
                revision = 0,
                sdkAvailability = SdkAvailability.STOPPED,
                remoteController = LinkState.DISCONNECTED,
                aircraft = LinkState.DISCONNECTED,
                flightController = LinkState.DISCONNECTED,
                pairing = PairingState.UNKNOWN,
                remoteControllerModel = null,
                aircraftModel = null,
            ),
            store.snapshot(),
        )
    }

    @Test
    fun atomicallyReplacesTheWholeSnapshotAndNotifiesListenersInRevisionOrder() {
        val store = DeviceStateStore.create()
        val revisions = ConcurrentLinkedQueue<Long>()
        store.onChanged { event -> revisions += event.current.revision }

        val applied = assertIs<ApplyResult.Applied>(store.apply(readyObservation(revision = 1)))
        assertEquals(1, applied.snapshot.revision)
        assertEquals(SdkAvailability.READY, applied.snapshot.sdkAvailability)
        assertEquals(LinkState.CONNECTED, applied.snapshot.aircraft)
        assertEquals(PairingState.IDLE, applied.snapshot.pairing)
        assertEquals(listOf(1L), revisions.toList())
    }

    @Test
    fun ignoresOldOrDuplicateObservationsWithoutNotifyingListeners() {
        val store = DeviceStateStore.create()
        val revisions = mutableListOf<Long>()
        store.onChanged { event -> revisions += event.current.revision }
        store.apply(readyObservation(revision = 2))

        assertEquals(ApplyResult.IgnoredStale(2), store.apply(readyObservation(revision = 2)))
        assertEquals(ApplyResult.IgnoredStale(1), store.apply(readyObservation(revision = 1)))
        assertEquals(2, store.snapshot().revision)
        assertEquals(listOf(2L), revisions)
    }

    @Test
    fun rejectsInvalidObservationDataBeforeChangingTheCurrentSnapshot() {
        val store = DeviceStateStore.create()

        assertFailsWith<IllegalArgumentException> { store.apply(readyObservation(revision = 0)) }
        assertFailsWith<IllegalArgumentException> { store.apply(readyObservation(revision = 1, aircraftModel = "bad\u0000model")) }
        assertEquals(0, store.snapshot().revision)
    }

    @Test
    fun rejectsAnImpossibleAircraftAndFlightControllerCombination() {
        val store = DeviceStateStore.create()

        assertFailsWith<IllegalArgumentException> {
            store.apply(DeviceStatePatch.aircraft(1, LinkState.DISCONNECTED, LinkState.CONNECTED, null))
        }

        assertEquals(0, store.snapshot().revision)
    }

    @Test
    fun listenerFailureDoesNotPreventLaterListenersOrStateCommit() {
        val diagnostics = mutableListOf<DeviceStateDiagnostic>()
        val store = DeviceStateStore.create(DeviceStateDiagnosticSink { diagnostics += it })
        val observed = mutableListOf<Long>()
        store.onChanged { error("listener failure") }
        store.onChanged { event -> observed += event.current.revision }

        store.apply(readyObservation(revision = 1))

        assertEquals(listOf(1L), observed)
        assertEquals(1, store.snapshot().revision)
        assertEquals(DeviceStateDiagnosticKind.LISTENER_FAILURE, diagnostics.single().kind)
    }

    @Test
    fun mergesIndependentSourcePatchesWithoutLettingOneSourceRollbackAnother() {
        val store = DeviceStateStore.create()

        store.apply(
            DeviceStatePatch.remoteController(
                sourceRevision = 1,
                link = LinkState.CONNECTED,
                model = "DJI RC Plus",
            ),
        )
        store.apply(
            DeviceStatePatch.aircraft(
                sourceRevision = 1,
                aircraft = LinkState.CONNECTED,
                flightController = LinkState.CONNECTED,
                model = "Matrice 4",
            ),
        )

        assertEquals(LinkState.CONNECTED, store.snapshot().remoteController)
        assertEquals("DJI RC Plus", store.snapshot().remoteControllerModel)
        assertEquals(LinkState.CONNECTED, store.snapshot().aircraft)
        assertEquals(
            ApplyResult.IgnoredStale(1),
            store.apply(DeviceStatePatch.remoteController(1, LinkState.DISCONNECTED, null)),
        )
        assertEquals(LinkState.CONNECTED, store.snapshot().remoteController)
    }

    @Test
    fun tracksPairingAsAnIndependentVersionedSource() {
        val store = DeviceStateStore.create()

        store.apply(DeviceStatePatch.pairing(1, PairingState.PAIRING))
        assertEquals(PairingState.PAIRING, store.snapshot().pairing)
        assertEquals(ApplyResult.IgnoredStale(1), store.apply(DeviceStatePatch.pairing(1, PairingState.IDLE)))
        assertEquals(PairingState.PAIRING, store.snapshot().pairing)
    }

    @Test
    fun tracksSdkAvailabilityAsAnIndependentVersionedSource() {
        val store = DeviceStateStore.create()
        store.apply(DeviceStatePatch.remoteController(1, LinkState.CONNECTED, "RC"))

        val applied = assertIs<ApplyResult.Applied>(
            store.apply(DeviceStatePatch.sdk(1, SdkAvailability.READY)),
        )

        assertEquals(SdkAvailability.READY, applied.snapshot.sdkAvailability)
        assertEquals(LinkState.CONNECTED, applied.snapshot.remoteController)
        assertEquals(ApplyResult.IgnoredStale(1), store.apply(DeviceStatePatch.sdk(1, SdkAvailability.FAILED)))
        assertEquals(SdkAvailability.READY, store.snapshot().sdkAvailability)
    }

    @Test
    fun assignsMonotonicSourceVersionsToLocalPairingTransitions() {
        val store = DeviceStateStore.create()

        val first = assertIs<ApplyResult.Applied>(store.applyPairing(PairingState.PAIRING))
        val second = assertIs<ApplyResult.Applied>(store.applyPairing(PairingState.FAILED))

        assertEquals(PairingState.PAIRING, first.snapshot.pairing)
        assertEquals(PairingState.FAILED, second.snapshot.pairing)
        assertEquals(2, second.snapshot.revision)
    }

    @Test
    fun localPairingCommandsDoNotStarveLaterObservedPairingFacts() {
        val store = DeviceStateStore.create()
        store.applyPairing(PairingState.PAIRING)
        store.applyPairing(PairingState.STOPPING)

        val observed = assertIs<ApplyResult.Applied>(store.apply(DeviceStatePatch.pairing(1, PairingState.PAIRED)))

        assertEquals(PairingState.PAIRED, observed.snapshot.pairing)
        assertEquals(PairingState.PAIRED, store.snapshot().pairing)
    }

    @Test
    fun assignsMonotonicSourceVersionsToLocalSdkTransitions() {
        val store = DeviceStateStore.create()

        store.applySdk(SdkAvailability.STARTING)
        store.applySdk(SdkAvailability.READY)

        assertEquals(SdkAvailability.READY, store.snapshot().sdkAvailability)
        assertEquals(2, store.snapshot().revision)
    }

    private fun readyObservation(
        revision: Long,
        aircraftModel: String? = "Matrice 4",
    ): DeviceObservation = DeviceObservation(
        sourceRevision = revision,
        sdkAvailability = SdkAvailability.READY,
        remoteController = LinkState.CONNECTED,
        aircraft = LinkState.CONNECTED,
        flightController = LinkState.CONNECTED,
        pairing = PairingState.IDLE,
        remoteControllerModel = "DJI RC Plus",
        aircraftModel = aircraftModel,
    )
}
