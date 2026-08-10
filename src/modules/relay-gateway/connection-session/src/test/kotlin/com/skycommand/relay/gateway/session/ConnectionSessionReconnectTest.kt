package com.skycommand.relay.gateway.session

import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.PairedFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConnectionSessionReconnectTest {

    @Test
    fun handshakeTimeoutStartsOnlyAfterHelloAndSchedulesFirstRetry() {
        val fixture = SessionFixture.create()
        fixture.session.start()

        assertEquals(0, fixture.scheduler.tasks.size)

        fixture.connector.current.open()
        val handshakeTimeout = fixture.scheduler.tasks.single()
        assertEquals(10_000, handshakeTimeout.delayMillis)

        fixture.scheduler.fire(handshakeTimeout)

        assertEquals(SessionState.RECONNECT_WAIT, fixture.session.snapshot().state)
        assertEquals(SessionEndKind.HANDSHAKE_TIMEOUT, fixture.notifier.events.last().endReason?.kind)
        assertEquals(1_000, fixture.scheduler.tasks.last().delayMillis)
    }

    @Test
    fun repeatedFailuresUseExactCappedExponentialBackoff() {
        val fixture = SessionFixture.create()
        fixture.connector.rejectNextReason = "offline"
        fixture.session.start()
        val observed = mutableListOf<Long>()

        repeat(7) { index ->
            val retry = fixture.scheduler.tasks.last()
            observed += retry.delayMillis
            if (index < 6) {
                fixture.connector.rejectNextReason = "offline"
                fixture.scheduler.fire(retry)
            }
        }

        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), observed)
        assertEquals(7, fixture.connector.openCalls.size)
    }

    @Test
    fun enteringActiveResetsFailureCountBeforeNextDisconnect() {
        val fixture = SessionFixture.create()
        fixture.connector.rejectNextReason = "offline"
        fixture.session.start()
        fixture.scheduler.fireNextActive()
        fixture.connector.current.open()
        fixture.connector.current.receive(encoded(PairedFrame("active", null)))

        fixture.connector.current.fail()

        assertEquals(1_000, fixture.scheduler.tasks.last().delayMillis)
    }

    @Test
    fun manualStartDuringReconnectWaitCancelsTimerAndPreservesFailureCount() {
        val fixture = SessionFixture.create()
        fixture.connector.rejectNextReason = "offline"
        fixture.session.start()
        val firstRetry = fixture.scheduler.tasks.last()
        fixture.connector.rejectNextReason = "still-offline"

        assertEquals(StartResult.StartAccepted, fixture.session.start())

        assertEquals(true, firstRetry.cancelled)
        assertEquals(2_000, fixture.scheduler.tasks.last().delayMillis)
        assertEquals(2, fixture.connector.openCalls.size)

        fixture.scheduler.fire(firstRetry)
        assertEquals(2, fixture.connector.openCalls.size)
    }

    @Test
    fun stopDuringReconnectWaitDoesNotRepeatPreviousGenerationCleanup() {
        val fixture = SessionFixture.create()
        fixture.connector.rejectNextReason = "offline"
        fixture.session.start()
        val retry = fixture.scheduler.tasks.last()
        val commandCleanupCount = fixture.commandCleanup.calls.size
        val missionCleanupCount = fixture.missionCleanup.calls.size
        val outboundCleanupCount = fixture.outbound.discarded.size
        fixture.order.clear()

        assertEquals(StopResult.Stopped, fixture.session.stop())

        assertEquals(true, retry.cancelled)
        assertEquals(SessionSnapshot(SessionState.STOPPED, null, null), fixture.session.snapshot())
        assertEquals(commandCleanupCount, fixture.commandCleanup.calls.size)
        assertEquals(missionCleanupCount, fixture.missionCleanup.calls.size)
        assertEquals(outboundCleanupCount, fixture.outbound.discarded.size)
        assertEquals(listOf("notify"), fixture.order)
    }

    @Test
    fun everyOldTransportCallbackIsIsolatedFromNewGeneration() {
        val fixture = SessionFixture.create()
        fixture.becomeActive("old-session")
        val oldConnection = fixture.connector.current
        val oldTimeout = fixture.scheduler.tasks.first()
        oldConnection.fail()
        val retry = fixture.scheduler.tasks.last()
        fixture.scheduler.fire(retry)
        val newConnection = fixture.connector.current
        val newGeneration = assertNotNull(fixture.session.snapshot().generation)
        assertNotEquals(oldConnection.generation, newGeneration)
        val cleanupCount = fixture.commandCleanup.calls.size

        oldConnection.open()
        oldConnection.receive(
            encoded(CommandFrame("old-command", "telemetry.read", JsonObject(emptyMap())))
        )
        oldConnection.closed()
        oldConnection.fail()
        fixture.scheduler.fire(oldTimeout)

        assertEquals(SessionState.CONNECTING, fixture.session.snapshot().state)
        assertEquals(newGeneration, fixture.session.snapshot().generation)
        assertEquals(0, newConnection.closeCount)
        assertEquals(cleanupCount, fixture.commandCleanup.calls.size)
        assertEquals(0, fixture.consumer.accepted.size)
    }

    @Test
    fun pairedAndTimeoutRaceHasDeterministicFirstEventWinsBehavior() {
        val pairedFirst = SessionFixture.create()
        pairedFirst.session.start()
        pairedFirst.connector.current.open()
        val pairedFirstTimeout = pairedFirst.scheduler.tasks.single()
        pairedFirst.connector.current.receive(encoded(PairedFrame("paired-first", null)))
        pairedFirst.scheduler.fire(pairedFirstTimeout)

        val timeoutFirst = SessionFixture.create()
        timeoutFirst.session.start()
        timeoutFirst.connector.current.open()
        val timeoutFirstTask = timeoutFirst.scheduler.tasks.single()
        timeoutFirst.scheduler.fire(timeoutFirstTask)
        timeoutFirst.connector.current.receive(encoded(PairedFrame("too-late", null)))

        assertEquals(SessionState.ACTIVE, pairedFirst.session.snapshot().state)
        assertEquals(SessionState.RECONNECT_WAIT, timeoutFirst.session.snapshot().state)
    }

    @Test
    fun cleanupContinuesAfterTransportCommandMissionAndOutboundFailures() {
        val fixture = SessionFixture.create()
        fixture.becomeActive()
        fixture.connector.current.closeFailure = IllegalStateException("close secret")
        fixture.commandCleanup.failure = IllegalStateException("command secret")
        fixture.missionCleanup.failure = IllegalStateException("mission secret")
        fixture.outbound.discardFailure = IllegalStateException("outbound secret")
        fixture.order.clear()

        fixture.session.stop()

        assertEquals(SessionState.STOPPED, fixture.session.snapshot().state)
        assertEquals(listOf("commands", "mission", "outbound", "notify"), fixture.order)
        assertTrue(fixture.diagnostics.diagnostics.count { it.kind == SessionDiagnosticKind.DEPENDENCY_FAILURE } >= 4)
        assertTrue(fixture.diagnostics.diagnostics.none { it.detail.contains("secret") })
    }

    @Test
    fun stopInvalidatesHandshakeTimeoutWhenCancellationThrows() {
        val fixture = SessionFixture.create()
        fixture.session.start()
        fixture.connector.current.open()
        val timeout = fixture.scheduler.tasks.single()
        timeout.cancellationFailure = IllegalStateException("timer secret")

        assertEquals(StopResult.Stopped, fixture.session.stop())
        fixture.scheduler.fire(timeout)

        assertEquals(1, timeout.cancelAttempts)
        assertEquals(SessionSnapshot(SessionState.STOPPED, null, null), fixture.session.snapshot())
        assertEquals(1, fixture.connector.openCalls.size)
        assertTrue(fixture.diagnostics.diagnostics.any { it.kind == SessionDiagnosticKind.DEPENDENCY_FAILURE })
        assertTrue(fixture.diagnostics.diagnostics.none { it.detail.contains("secret") })
    }

    @Test
    fun stopInvalidatesReconnectTimerWhenCancellationThrows() {
        val fixture = SessionFixture.create()
        fixture.connector.rejectNextReason = "offline"
        fixture.session.start()
        val retry = fixture.scheduler.tasks.single()
        retry.cancellationFailure = IllegalStateException("timer secret")

        assertEquals(StopResult.Stopped, fixture.session.stop())
        fixture.scheduler.fire(retry)

        assertEquals(1, retry.cancelAttempts)
        assertEquals(SessionSnapshot(SessionState.STOPPED, null, null), fixture.session.snapshot())
        assertEquals(1, fixture.connector.openCalls.size)
        assertTrue(fixture.diagnostics.diagnostics.any { it.kind == SessionDiagnosticKind.DEPENDENCY_FAILURE })
        assertTrue(fixture.diagnostics.diagnostics.none { it.detail.contains("secret") })
    }
}
