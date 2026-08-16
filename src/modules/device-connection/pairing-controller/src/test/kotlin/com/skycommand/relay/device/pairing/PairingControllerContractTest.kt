package com.skycommand.relay.device.pairing

import com.skycommand.relay.device.operation.DjiOperation
import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.CancellationResult
import com.skycommand.relay.device.operation.OperationCompletion
import com.skycommand.relay.device.operation.OperationExecutor
import com.skycommand.relay.device.operation.OperationOutcome
import com.skycommand.relay.device.operation.OperationScheduler
import com.skycommand.relay.device.operation.SubmissionResult
import com.skycommand.relay.device.state.DeviceStatePatch
import com.skycommand.relay.device.state.DeviceStateStore
import com.skycommand.relay.device.state.LinkState
import com.skycommand.relay.device.state.PairingState
import com.skycommand.relay.device.state.SdkAvailability
import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PairingControllerContractTest {

    @Test
    fun acceptsStartOnlyWhenTheDeviceIsReadyAndDoesNotFakePairedState() {
        val fixture = Fixture()
        fixture.makeReady()
        val results = mutableListOf<PairingOperationResult>()

        assertIs<PairingRequestResult.Accepted>(fixture.controller.start(1_000) { results += it })
        assertEquals(PairingState.PAIRING, fixture.controller.state())
        fixture.executor.runNext()
        fixture.action.succeed()

        assertEquals(listOf(PairingOperationResult.RequestAccepted), results)
        assertEquals(PairingState.PAIRING, fixture.controller.state())
    }

    @Test
    fun rejectsStartWithoutRemoteControllerAndRoutesFailureToFailedState() {
        val fixture = Fixture()
        val results = mutableListOf<PairingOperationResult>()

        assertEquals(
            PairingRequestResult.Rejected(PairingRejection.NOT_READY),
            fixture.controller.start(1_000) { results += it },
        )
        fixture.makeReady()
        assertIs<PairingRequestResult.Accepted>(fixture.controller.start(1_000) { results += it })
        fixture.executor.runNext()
        fixture.action.fail()

        assertEquals(PairingState.FAILED, fixture.controller.state())
        assertEquals(listOf(PairingOperationResult.RequestFailed), results)
    }

    @Test
    fun rejectsStartWhenSdkOrAircraftPreconditionsAreNotSatisfied() {
        val sdkFixture = Fixture()
        sdkFixture.makeReady()
        sdkFixture.store.apply(DeviceStatePatch.sdk(2, SdkAvailability.STARTING))
        assertEquals(
            PairingRequestResult.Rejected(PairingRejection.NOT_READY),
            sdkFixture.controller.start(1_000) { },
        )

        val aircraftFixture = Fixture()
        aircraftFixture.makeReady()
        aircraftFixture.store.apply(
            DeviceStatePatch.aircraft(2, LinkState.CONNECTED, LinkState.CONNECTED, "Matrice"),
        )
        assertEquals(
            PairingRequestResult.Rejected(PairingRejection.NOT_READY),
            aircraftFixture.controller.start(1_000) { },
        )
    }

    @Test
    fun rejectsStartFromNonIdlePairingStates() {
        val fixture = Fixture()
        fixture.makeReady()
        fixture.store.apply(DeviceStatePatch.pairing(2, PairingState.PAIRED))

        assertEquals(
            PairingRequestResult.Rejected(PairingRejection.NOT_READY),
            fixture.controller.start(1_000) { },
        )
    }

    @Test
    fun acceptsStartAfterFailureWhileAircraftRemainsDisconnected() {
        val fixture = Fixture()
        fixture.makeReady()
        fixture.store.apply(DeviceStatePatch.pairing(2, PairingState.FAILED))

        assertIs<PairingRequestResult.Accepted>(fixture.controller.start(1_000) { })
        assertEquals(PairingState.PAIRING, fixture.controller.state())
    }

    @Test
    fun stopSuccessReturnsToIdleSoPairingCanRestartWhileAircraftIsDisconnected() {
        val fixture = Fixture()
        fixture.makeReady()
        assertIs<PairingRequestResult.Accepted>(fixture.controller.start(1_000) { })
        fixture.executor.runNext()
        fixture.action.succeed()
        assertIs<PairingRequestResult.Accepted>(fixture.controller.stop(1_000) { })
        assertEquals(PairingState.STOPPING, fixture.controller.state())

        fixture.executor.runNext()
        fixture.stopAction.succeed()

        assertEquals(PairingState.IDLE, fixture.controller.state())
        assertIs<PairingRequestResult.Accepted>(fixture.controller.start(1_000) { })
    }

    @Test
    fun rejectsStopWhenPairingIsNotRunningAndAllowsStopAfterStartRequest() {
        val fixture = Fixture()
        fixture.makeReady()
        assertEquals(
            PairingRequestResult.Rejected(PairingRejection.NOT_RUNNING),
            fixture.controller.stop(1_000) { },
        )
        assertIs<PairingRequestResult.Accepted>(fixture.controller.start(1_000) { })
        assertIs<PairingRequestResult.Accepted>(fixture.controller.stop(1_000) { })
        assertEquals(PairingState.STOPPING, fixture.controller.state())
    }

    @Test
    fun mapsTimeoutAndCancellationToStableNonSuccessResults() {
        val timeoutFixture = Fixture()
        timeoutFixture.makeReady()
        val timeoutResults = mutableListOf<PairingOperationResult>()
        assertIs<PairingRequestResult.Accepted>(timeoutFixture.controller.start(1_000) { timeoutResults += it })
        timeoutFixture.executor.runNext()
        timeoutFixture.scheduler.fireNext()

        assertEquals(PairingState.FAILED, timeoutFixture.controller.state())
        assertEquals(listOf(PairingOperationResult.RequestTimedOut), timeoutResults)

        val cancelFixture = Fixture()
        cancelFixture.makeReady()
        val cancelResults = mutableListOf<PairingOperationResult>()
        val accepted = assertIs<PairingRequestResult.Accepted>(
            cancelFixture.controller.start(1_000) { cancelResults += it },
        )
        assertEquals(CancellationResult.Cancelled, accepted.cancellation.cancel())

        assertEquals(PairingState.FAILED, cancelFixture.controller.state())
        assertEquals(listOf(PairingOperationResult.RequestCancelled), cancelResults)
    }

    @Test
    fun rejectsInvalidTimeoutAndDoesNotChangePairingState() {
        val fixture = Fixture()
        fixture.makeReady()

        assertEquals(
            PairingRequestResult.Rejected(PairingRejection.INVALID_TIMEOUT),
            fixture.controller.start(999) { },
        )
        assertEquals(PairingState.IDLE, fixture.controller.state())
        assertTrue(fixture.executor.isEmpty())
    }

    @Test
    fun ignoresLateCompletionAfterTimeout() {
        val fixture = Fixture()
        fixture.makeReady()
        val results = mutableListOf<PairingOperationResult>()

        assertIs<PairingRequestResult.Accepted>(fixture.controller.start(1_000) { results += it })
        fixture.executor.runNext()
        fixture.scheduler.fireNext()
        fixture.action.succeed()

        assertEquals(listOf(PairingOperationResult.RequestTimedOut), results)
        assertEquals(PairingState.FAILED, fixture.controller.state())
    }

    @Test
    fun routesStopFailureToFailedStateAfterARealStartRequest() {
        val fixture = Fixture()
        fixture.makeReady()
        assertIs<PairingRequestResult.Accepted>(fixture.controller.start(1_000) { })
        fixture.executor.runNext()
        fixture.action.succeed()

        val results = mutableListOf<PairingOperationResult>()
        assertIs<PairingRequestResult.Accepted>(fixture.controller.stop(1_000) { results += it })
        assertEquals(PairingState.STOPPING, fixture.controller.state())
        fixture.executor.runNext()
        fixture.stopAction.fail()

        assertEquals(PairingState.FAILED, fixture.controller.state())
        assertEquals(listOf(PairingOperationResult.RequestFailed), results)
    }

    private class Fixture {
        val store = DeviceStateStore.create()
        val executor = ManualExecutor()
        val startAction = RecordingAction()
        val stopAction = RecordingAction()
        val scheduler = ManualScheduler()
        val coordinator = DjiOperationCoordinator.create(executor, scheduler)
        val controller = PairingController.create(
            store,
            coordinator,
            object : PairingPort {
                override fun startPairing() = startAction
                override fun stopPairing() = stopAction
            },
        )
        val action get() = startAction

        fun makeReady() {
            store.apply(DeviceStatePatch.remoteController(1, LinkState.CONNECTED, "RC"))
            store.apply(DeviceStatePatch.aircraft(1, LinkState.DISCONNECTED, LinkState.DISCONNECTED, null))
            store.apply(DeviceStatePatch.pairing(1, PairingState.IDLE))
            store.apply(DeviceStatePatch.sdk(1, SdkAvailability.READY))
        }
    }

    private class ManualExecutor : OperationExecutor {
        val tasks = ArrayDeque<() -> Unit>()
        override fun execute(task: () -> Unit) { tasks.addLast(task) }
        fun runNext() = tasks.removeFirst().invoke()
        fun isEmpty() = tasks.isEmpty()
    }

    private class ManualScheduler : OperationScheduler {
        private val tasks = ArrayDeque<() -> Unit>()

        override fun schedule(delayMillis: Long, callback: () -> Unit) =
            com.skycommand.relay.device.operation.OperationCancellation {
                if (tasks.isNotEmpty()) tasks.removeFirst()
            }.also {
                tasks.addLast(callback)
            }

        fun fireNext() = tasks.removeFirst().invoke()
    }

    private class RecordingAction : DjiOperation {
        private var completion: OperationCompletion? = null
        override fun run(completion: OperationCompletion) { this.completion = completion }
        fun succeed() = checkNotNull(completion).succeed()
        fun fail() = checkNotNull(completion).fail()
    }
}
