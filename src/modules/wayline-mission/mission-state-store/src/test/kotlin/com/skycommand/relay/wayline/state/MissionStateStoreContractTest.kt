package com.skycommand.relay.wayline.state

import com.skycommand.relay.wayline.staging.MissionMetadata
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class MissionStateStoreContractTest {

    @Test
    fun exposesAnEmptyInitialSnapshot() {
        val store = MissionStateStore.create()

        assertEquals(
            MissionSnapshot(
                revision = 0,
                missionRevision = null,
                deviceGeneration = 0,
                file = null,
                upload = UploadState.NOT_UPLOADED,
                execution = ExecutionState.NOT_STARTED,
            ),
            store.snapshot(),
        )
    }

    @Test
    fun stagesMetadataAndResetsUploadAndExecutionForANewMissionRevision() {
        val store = MissionStateStore.create()
        val first = staged(store, 1, "first.kmz")
        store.apply(uploadChanged(1, first.missionRevision!!, UploadState.UPLOADED))
        store.apply(executionChanged(1, first.missionRevision, ExecutionState.EXECUTING))

        val replacement = assertIs<ApplyResult.Applied>(
            store.apply(MissionStateEvent.FileStaged(2, metadata("replacement.kmz"))),
        ).snapshot

        assertEquals(2, replacement.missionRevision)
        assertEquals("replacement.kmz", replacement.file?.fileName)
        assertEquals(UploadState.NOT_UPLOADED, replacement.upload)
        assertEquals(ExecutionState.NOT_STARTED, replacement.execution)
    }

    @Test
    fun ignoresOldAndDuplicateEventsIndependentlyForEachSource() {
        val store = MissionStateStore.create()
        val mission = staged(store, 1).missionRevision!!
        store.apply(uploadChanged(1, mission, UploadState.Uploading(10)))
        store.apply(executionChanged(1, mission, ExecutionState.FAILED))

        assertEquals(
            ApplyResult.IgnoredStale(1),
            store.apply(uploadChanged(1, mission, UploadState.Uploading(20))),
        )
        assertEquals(
            ApplyResult.IgnoredStale(1),
            store.apply(executionChanged(1, mission, ExecutionState.FAILED)),
        )
        assertEquals(UploadState.Uploading(10), store.snapshot().upload)
        assertEquals(ExecutionState.FAILED, store.snapshot().execution)
    }

    @Test
    fun ignoresLateCallbacksForAReplacedMissionEvenWhenTheirSourceRevisionIsNewer() {
        val store = MissionStateStore.create()
        val oldMission = staged(store, 1, "old.kmz").missionRevision!!
        val currentMission = staged(store, 2, "current.kmz").missionRevision!!

        assertEquals(
            ApplyResult.IgnoredStale(9),
            store.apply(uploadChanged(9, oldMission, UploadState.UPLOADED)),
        )
        assertEquals(currentMission, store.snapshot().missionRevision)
        assertEquals(UploadState.NOT_UPLOADED, store.snapshot().upload)
    }

    @Test
    fun rejectsUploadAndExecutionUpdatesWithoutACurrentMission() {
        val store = MissionStateStore.create()

        assertEquals(
            ApplyResult.IgnoredStale(1),
            store.apply(uploadChanged(1, 1, UploadState.UPLOADED)),
        )
        assertEquals(
            ApplyResult.IgnoredStale(1),
            store.apply(executionChanged(1, 1, ExecutionState.EXECUTING)),
        )
        assertEquals(0, store.snapshot().revision)
    }

    @Test
    fun onlyAllowsExecutionSuccessStatesForAnUploadedCurrentMission() {
        val store = MissionStateStore.create()
        val mission = staged(store, 1).missionRevision!!

        assertEquals(
            ApplyResult.IgnoredStale(1),
            store.apply(executionChanged(1, mission, ExecutionState.STARTING)),
        )
        store.apply(uploadChanged(1, mission, UploadState.UPLOADED))

        val result = assertIs<ApplyResult.Applied>(
            store.apply(executionChanged(2, mission, ExecutionState.STARTING)),
        )
        assertEquals(ExecutionState.STARTING, result.snapshot.execution)
    }

    @Test
    fun acceptsUploadAndExecutionFailuresForTheCurrentMission() {
        val store = MissionStateStore.create()
        val mission = staged(store, 1).missionRevision!!

        store.apply(uploadChanged(1, mission, UploadState.FAILED))
        store.apply(executionChanged(1, mission, ExecutionState.FAILED))

        assertEquals(UploadState.FAILED, store.snapshot().upload)
        assertEquals(ExecutionState.FAILED, store.snapshot().execution)
    }

    @Test
    fun deviceUnavailabilityPreservesTheMissionAndRejectsCallbacksFromThePreviousDeviceGeneration() {
        val store = MissionStateStore.create()
        val staged = staged(store, 1)
        val mission = staged.missionRevision!!
        val previousDeviceGeneration = staged.deviceGeneration
        store.apply(MissionStateEvent.UploadChanged(1, mission, previousDeviceGeneration, UploadState.UPLOADED))
        store.apply(MissionStateEvent.ExecutionChanged(1, mission, previousDeviceGeneration, ExecutionState.EXECUTING))

        val unavailable = assertIs<ApplyResult.Applied>(store.markDeviceUnavailable()).snapshot

        assertEquals(staged.file, unavailable.file)
        assertEquals(previousDeviceGeneration + 1, unavailable.deviceGeneration)
        assertEquals(UploadState.FAILED, unavailable.upload)
        assertEquals(ExecutionState.FAILED, unavailable.execution)
        assertEquals(
            ApplyResult.IgnoredStale(2),
            store.apply(MissionStateEvent.UploadChanged(2, mission, previousDeviceGeneration, UploadState.UPLOADED)),
        )
        assertEquals(UploadState.FAILED, store.snapshot().upload)
    }

    @Test
    fun repeatedDeviceUnavailabilityKeepsTheSafeFailureStateAndAdvancesTheGeneration() {
        val store = MissionStateStore.create()
        val staged = staged(store, 1)

        val first = store.markDeviceUnavailable().snapshot
        val second = store.markDeviceUnavailable().snapshot

        assertEquals(staged.file, second.file)
        assertEquals(staged.deviceGeneration + 2, second.deviceGeneration)
        assertEquals(UploadState.FAILED, second.upload)
        assertEquals(ExecutionState.FAILED, second.execution)
        assertEquals(first.revision + 1, second.revision)
    }

    @Test
    fun acceptsBothUploadProgressBoundaries() {
        val store = MissionStateStore.create()
        val mission = staged(store, 1).missionRevision!!

        store.apply(uploadChanged(1, mission, UploadState.Uploading(0)))
        store.apply(uploadChanged(2, mission, UploadState.Uploading(100)))

        assertEquals(UploadState.Uploading(100), store.snapshot().upload)
    }

    @Test
    fun acceptsEveryExecutionStateAfterUpload() {
        val store = MissionStateStore.create()
        val mission = staged(store, 1).missionRevision!!
        store.apply(uploadChanged(1, mission, UploadState.UPLOADED))

        listOf(
            ExecutionState.STARTING,
            ExecutionState.EXECUTING,
            ExecutionState.PAUSED,
            ExecutionState.STOPPING,
            ExecutionState.FINISHED,
        ).forEachIndexed { index, state ->
            store.apply(executionChanged(index + 1L, mission, state))
            assertEquals(state, store.snapshot().execution)
        }
    }

    @Test
    fun validatesSourceRevisionsMetadataAndUploadProgressBeforeChangingState() {
        val store = MissionStateStore.create()

        assertFailsWith<IllegalArgumentException> {
            store.apply(MissionStateEvent.FileStaged(0, metadata()))
        }
        assertFailsWith<IllegalArgumentException> {
            store.apply(MissionStateEvent.FileStaged(1, MissionMetadata("", 1, "0".repeat(64))))
        }
        val mission = staged(store, 2).missionRevision!!
        assertFailsWith<IllegalArgumentException> {
            store.apply(uploadChanged(1, mission, UploadState.Uploading(101)))
        }
        assertEquals(1, store.snapshot().revision)
    }

    @Test
    fun clearingTheFileResetsAllStateAndMakesExistingMissionCallbacksStale() {
        val store = MissionStateStore.create()
        val mission = staged(store, 1).missionRevision!!
        store.apply(uploadChanged(1, mission, UploadState.UPLOADED))

        store.apply(MissionStateEvent.FileCleared(2))

        assertNull(store.snapshot().file)
        assertNull(store.snapshot().missionRevision)
        assertEquals(UploadState.NOT_UPLOADED, store.snapshot().upload)
        assertEquals(
            ApplyResult.IgnoredStale(2),
            store.apply(uploadChanged(2, mission, UploadState.UPLOADED)),
        )
    }

    @Test
    fun notifiesListenersInCommittedSnapshotOrderAndIsolatesListenerFailures() {
        val diagnostics = mutableListOf<MissionStateDiagnostic>()
        val store = MissionStateStore.create(MissionStateDiagnosticSink { diagnostics += it })
        val observed = ConcurrentLinkedQueue<Long>()
        store.onChanged { error("listener failure") }
        store.onChanged { event -> observed += event.current.revision }

        staged(store, 1)
        val mission = store.snapshot().missionRevision!!
        store.apply(uploadChanged(1, mission, UploadState.Uploading(10)))

        assertEquals(listOf(1L, 2L), observed.toList())
        assertEquals(listOf(MissionStateDiagnosticKind.LISTENER_FAILURE, MissionStateDiagnosticKind.LISTENER_FAILURE), diagnostics.map { it.kind })
    }

    @Test
    fun unregisterIsIdempotentAndPreventsFutureNotifications() {
        val store = MissionStateStore.create()
        val observed = mutableListOf<Long>()
        val registration = store.onChanged { event -> observed += event.current.revision }

        staged(store, 1)
        registration.unregister()
        registration.unregister()
        val mission = store.snapshot().missionRevision!!
        store.apply(uploadChanged(1, mission, UploadState.Uploading(10)))

        assertEquals(listOf(1L), observed)
    }

    @Test
    fun unregisterWaitsForAnInFlightCallbackAndPreventsQueuedCallbacks() {
        val store = MissionStateStore.create()
        val callbackStarted = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val callbackCount = ConcurrentLinkedQueue<Long>()
        val registration = store.onChanged {
            callbackCount += it.current.revision
            callbackStarted.countDown()
            releaseCallback.await(2, TimeUnit.SECONDS)
        }

        val applyThread = thread(start = true) { staged(store, 1) }
        check(callbackStarted.await(2, TimeUnit.SECONDS))
        val unregisterFinished = CountDownLatch(1)
        val unregisterThread = thread(start = true) {
            registration.unregister()
            unregisterFinished.countDown()
        }

        check(!unregisterFinished.await(100, TimeUnit.MILLISECONDS))
        releaseCallback.countDown()
        applyThread.join(2_000)
        unregisterThread.join(2_000)
        check(unregisterFinished.await(2, TimeUnit.SECONDS))

        val mission = store.snapshot().missionRevision!!
        store.apply(uploadChanged(1, mission, UploadState.Uploading(10)))
        assertEquals(listOf(1L), callbackCount.toList())
    }

    @Test
    fun unregisterFromInsideACallbackDoesNotDeadlockOrReceiveLaterEvents() {
        val store = MissionStateStore.create()
        var registration: Registration? = null
        var callbackCount = 0
        registration = store.onChanged {
            callbackCount += 1
            registration!!.unregister()
        }

        staged(store, 1)
        val mission = store.snapshot().missionRevision!!
        store.apply(uploadChanged(1, mission, UploadState.Uploading(10)))

        assertEquals(1, callbackCount)
    }

    private fun uploadChanged(sourceRevision: Long, missionRevision: Long, state: UploadState): MissionStateEvent.UploadChanged =
        MissionStateEvent.UploadChanged(sourceRevision, missionRevision, 0, state)

    private fun executionChanged(sourceRevision: Long, missionRevision: Long, state: ExecutionState): MissionStateEvent.ExecutionChanged =
        MissionStateEvent.ExecutionChanged(sourceRevision, missionRevision, 0, state)
    private fun staged(store: MissionStateStore, revision: Long, name: String = "survey.kmz"): MissionSnapshot =
        assertIs<ApplyResult.Applied>(store.apply(MissionStateEvent.FileStaged(revision, metadata(name)))).snapshot

    private fun metadata(name: String = "survey.kmz") = MissionMetadata(
        fileName = name,
        expectedSize = 42,
        sha256 = "a".repeat(64),
    )
}
