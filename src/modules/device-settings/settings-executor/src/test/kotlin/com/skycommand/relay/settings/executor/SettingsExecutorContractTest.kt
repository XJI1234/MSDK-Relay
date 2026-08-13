package com.skycommand.relay.settings.executor

import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellation
import com.skycommand.relay.device.operation.OperationExecutor
import com.skycommand.relay.device.operation.OperationScheduler
import com.skycommand.relay.settings.command.CameraSettings
import com.skycommand.relay.settings.command.SettingsDomain
import com.skycommand.relay.settings.command.SettingsRequest
import com.skycommand.relay.settings.command.SettingsSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SettingsExecutorContractTest {
    @Test
    fun serializesRequestsAndReturnsOnlyMatchingConfirmedSnapshots() {
        val executor = ManualExecutor()
        val port = Port()
        val settings = SettingsExecutor.create(port, DjiOperationCoordinator.create(executor, Scheduler()), 1_000)
        val outcomes = mutableListOf<SettingsExecutionOutcome>()

        assertIs<SettingsSubmissionResult.Accepted>(settings.execute(SettingsRequest.Read(SettingsDomain.CAMERA)) { outcomes += it })
        assertIs<SettingsSubmissionResult.Accepted>(settings.execute(SettingsRequest.Read(SettingsDomain.TRANSMISSION)) { outcomes += it })
        executor.runNext()
        assertEquals(1, port.requests.size)
        port.succeed(SettingsSnapshot.Camera(CameraSettings(false, "AUTO", "LEFT_OR_MAIN")))
        executor.runNext()

        assertEquals(2, port.requests.size)
        assertEquals(1, outcomes.size)
    }

    @Test
    fun turnsWrongDomainSnapshotTimeoutAndCancellationIntoStableTerminalOutcomes() {
        val executor = ManualExecutor()
        val scheduler = Scheduler()
        val port = Port()
        val settings = SettingsExecutor.create(port, DjiOperationCoordinator.create(executor, scheduler), 1_000)
        val outcomes = mutableListOf<SettingsExecutionOutcome>()

        assertIs<SettingsSubmissionResult.Accepted>(settings.execute(SettingsRequest.Read(SettingsDomain.CAMERA)) { outcomes += it })
        executor.runNext()
        port.succeed(SettingsSnapshot.Transmission(com.skycommand.relay.settings.command.TransmissionSettings("BAND_MULTI", "AUTO", "BANDWIDTH_20MHZ", null)))
        assertEquals(listOf<SettingsExecutionOutcome>(SettingsExecutionOutcome.Failed), outcomes)

        assertIs<SettingsSubmissionResult.Accepted>(settings.execute(SettingsRequest.Read(SettingsDomain.CAMERA)) { outcomes += it })
        executor.runNext()
        scheduler.fire()
        assertEquals(SettingsExecutionOutcome.TimedOut, outcomes.last())
    }

    private class Port : DjiSettingsPort {
        val requests = mutableListOf<SettingsRequest>(); private var completion: SettingsDjiCompletion? = null
        override fun execute(request: SettingsRequest, completion: SettingsDjiCompletion) { requests += request; this.completion = completion }
        fun succeed(snapshot: SettingsSnapshot) = checkNotNull(completion).succeed(snapshot)
    }
    private class ManualExecutor : OperationExecutor {
        private val tasks = ArrayDeque<() -> Unit>(); override fun execute(task: () -> Unit) { tasks += task }; fun runNext() = tasks.removeFirst()()
    }
    private class Scheduler : OperationScheduler {
        private var callback: (() -> Unit)? = null
        override fun schedule(delayMillis: Long, callback: () -> Unit): OperationCancellation { this.callback = callback; return OperationCancellation { } }
        fun fire() = checkNotNull(callback).invoke()
    }
}
