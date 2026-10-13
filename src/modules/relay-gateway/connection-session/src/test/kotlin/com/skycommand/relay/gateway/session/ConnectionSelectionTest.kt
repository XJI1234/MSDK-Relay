package com.skycommand.relay.gateway.session

import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.PairedFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun selection(endpoint: String = "ws://192.168.1.20:8080/relay"): ConnectionSelection =
    assertIs<ConnectionSelectionResult.Valid>(ConnectionSelection.create(endpoint)).selection

class ConnectionSelectionTest {
    @Test fun validatesAndNormalizesSelectionsWithoutLeakingEndpointInResults() {
        for (invalid in listOf("", " ", "http://desktop/relay", "ws://user:secret@desktop/relay", "ws://desktop:0/relay", "ws://desktop/other", "ws://desktop/relay#secret", "ws://desktop/\u0000")) {
            assertEquals(ConnectionSelectionResult.Invalid, ConnectionSelection.create(invalid))
            assertIs<ConfigurationRejected>(SessionFixture.createResult(endpoint = invalid))
        }
        val normalized = selection("ws://desktop:8080")
        assertEquals("ws://desktop:8080/relay", normalized.endpoint)
        val privateSelection = assertIs<ConnectionSelectionResult.Valid>(ConnectionSelection.create("wss://desktop/relay?token=secret"))
        assertFalse(privateSelection.toString().contains("secret"))
        assertFalse(privateSelection.toString().contains("desktop"))
        assertNotSame(normalized, selection("ws://desktop:8080"))
    }

    @Test fun nullEndpointStartsWithoutOpeningTransportOrSchedulingWork() {
        val f = SessionFixture.create(endpoint = null)
        repeat(3) { assertEquals(StartResult.StartAccepted, f.session.start()) }
        assertEquals(SessionSnapshot(SessionState.STOPPED, null, null), f.session.snapshot())
        assertTrue(f.connector.openCalls.isEmpty()); assertTrue(f.scheduler.tasks.isEmpty()); assertTrue(f.notifier.events.isEmpty())
        val selected = selection()
        assertEquals(SelectionResult.Changed, f.session.selectConnection(selected))
        assertEquals(SessionState.CONNECTING, f.session.snapshot().state)
        assertEquals(listOf(selected.endpoint), f.connector.openCalls.map { it.first })
        assertSame(selected, f.notifier.events.single().connectionSelection)
    }

    @Test fun stopDuringAddressWaitRevokesRunningIntent() {
        val f = SessionFixture.create(endpoint = null)
        f.session.start()
        assertEquals(StopResult.AlreadyStopped, f.session.stop())
        f.session.selectConnection(selection())
        assertTrue(f.connector.openCalls.isEmpty()); assertTrue(f.notifier.events.isEmpty())
        f.session.start()
        assertEquals(1, f.connector.openCalls.size)
    }

    @Test fun selectionBeforeStartOnlyUpdatesConfiguration() {
        val f = SessionFixture.create(endpoint = null)
        val selected = selection()
        assertEquals(SelectionResult.Changed, f.session.selectConnection(selected))
        assertEquals(SelectionResult.Unchanged, f.session.selectConnection(selected))
        assertEquals(SessionState.STOPPED, f.session.snapshot().state)
        assertTrue(f.connector.openCalls.isEmpty()); f.session.start()
        assertEquals(selected.endpoint, f.connector.openCalls.single().first)
    }

    @Test fun duplicateSelectionDoesNotInterruptHealthyActiveConnection() {
        val f = SessionFixture.create(endpoint = null); val selected = selection()
        f.session.selectConnection(selected); f.becomeActive()
        val before = f.session.snapshot(); val events = f.notifier.events.size
        repeat(10) { assertEquals(SelectionResult.Unchanged, f.session.selectConnection(selected)) }
        assertEquals(before, f.session.snapshot()); assertEquals(events, f.notifier.events.size)
        assertEquals(1, f.connector.openCalls.size); assertEquals(0, f.connector.current.closeCount)
    }

    @Test fun repeatedSelectionPreservesExactBackoffAndItsCurrentTimer() {
        val f = SessionFixture.create(endpoint = null); val selected = selection()
        f.session.selectConnection(selected)
        f.connector.rejectNextReason = "offline"; f.session.start()
        val observed = mutableListOf<Long>()
        repeat(7) { index ->
            val retry = f.scheduler.tasks.last(); observed += retry.delayMillis
            repeat(10) { assertEquals(SelectionResult.Unchanged, f.session.selectConnection(selected)) }
            assertSame(retry, f.scheduler.tasks.last()); assertFalse(retry.cancelled)
            if (index < 6) { f.connector.rejectNextReason = "offline"; f.scheduler.fire(retry) }
        }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L), observed)
        assertEquals(7, f.connector.openCalls.size)
    }

    @Test fun pausesEveryLiveStateAndDiscardsLateCallbacksAndHandshakeTimers() {
        for (state in listOf(SessionState.CONNECTING, SessionState.AWAITING_PAIRING, SessionState.ACTIVE)) {
            val f = SessionFixture.create(); f.session.start(); val old = f.connector.current
            if (state != SessionState.CONNECTING) old.open()
            if (state == SessionState.ACTIVE) old.receive(encoded(PairedFrame("old", null)))
            val oldTasks = f.scheduler.tasks.toList()
            f.order.clear()
            assertEquals(SelectionResult.Changed, f.session.selectConnection(null))
            assertEquals(SessionSnapshot(SessionState.STOPPED, null, null), f.session.snapshot())
            assertEquals(listOf("close", "commands", "mission", "outbound", "notify"), f.order)
            assertEquals(SessionEndKind.CONNECTION_PAUSED, f.commandCleanup.calls.single().second.kind)
            val notified = f.notifier.events.size
            old.open(); old.receive(encoded(PairedFrame("late", null))); old.closed(); old.fail()
            oldTasks.forEach(f.scheduler::fire)
            assertEquals(notified, f.notifier.events.size); assertEquals(1, f.connector.openCalls.size)
            assertEquals(SelectionResult.Unchanged, f.session.selectConnection(null))
            assertEquals(1, f.commandCleanup.calls.size); assertEquals(1, f.missionCleanup.calls.size)
            f.session.selectConnection(selection())
            assertEquals(2, f.connector.openCalls.size)
        }
    }

    @Test fun pausingReconnectWaitCancelsRetryWithoutRepeatingCleanup() {
        val f = SessionFixture.create(); f.session.start(); f.connector.current.fail()
        val retry = f.scheduler.tasks.last()
        assertEquals(1, f.commandCleanup.calls.size)
        f.session.selectConnection(null)
        assertTrue(retry.cancelled); f.scheduler.fire(retry)
        assertEquals(1, f.connector.openCalls.size); assertEquals(1, f.commandCleanup.calls.size)
        assertEquals(SessionEndKind.CONNECTION_PAUSED, f.notifier.events.last().endReason?.kind)
    }

    @Test fun newSelectionAfterExplicitStopDoesNotAutoStartAndManualRestartIsPreserved() {
        val f = SessionFixture.create(); f.becomeActive(); f.session.stop()
        val next = selection(); f.session.selectConnection(next)
        assertEquals(1, f.connector.openCalls.size); assertEquals(SessionState.STOPPED, f.session.snapshot().state)
        f.session.start(); assertEquals(next.endpoint, f.connector.openCalls.last().first)
        f.session.stop(); f.session.start()
        assertEquals(next.endpoint, f.connector.openCalls.last().first)
    }

    @Test fun changingEveryLiveStateCleansOldGenerationBeforeOpeningNewConnection() {
        for (state in listOf(SessionState.CONNECTING, SessionState.AWAITING_PAIRING, SessionState.ACTIVE)) {
            val f = SessionFixture.create(endpoint = null); val oldSelection = selection("ws://old/relay")
            f.session.selectConnection(oldSelection); f.session.start(); val old = f.connector.current
            if (state != SessionState.CONNECTING) old.open()
            if (state == SessionState.ACTIVE) old.receive(encoded(PairedFrame("same-session-id", null)))
            f.order.clear(); val next = selection("ws://new/relay")
            f.session.selectConnection(next)
            assertEquals(listOf("close", "commands", "mission", "outbound", "notify", "notify"), f.order)
            assertNotEquals(old.generation, f.connector.current.generation)
            assertEquals(next.endpoint, f.connector.current.endpoint)
            assertEquals(SessionEndKind.ENDPOINT_CHANGED, f.notifier.events.takeLast(2).first().endReason?.kind)
            assertSame(oldSelection, f.notifier.events.takeLast(2).first().connectionSelection)
            assertSame(next, f.notifier.events.last().connectionSelection)
            old.receive(encoded(PairedFrame("late", null))); old.closed(); old.fail()
            assertEquals(SessionState.CONNECTING, f.session.snapshot().state)
            f.connector.current.open(); f.connector.current.receive(encoded(PairedFrame("same-session-id", null)))
            assertEquals(SessionState.ACTIVE, f.session.snapshot().state)
            assertEquals(2, f.connector.openCalls.size)
        }
    }

    @Test fun sameUrlNewSelectionStillInvalidatesOldAttempt() {
        val f = SessionFixture.create(endpoint = null); val first = selection()
        f.session.selectConnection(first); f.session.start(); val old = f.connector.current
        val next = selection(); f.session.selectConnection(next)
        assertEquals(first.endpoint, next.endpoint); assertNotSame(first, next)
        assertEquals(2, f.connector.openCalls.size); assertEquals(1, old.closeCount)
        old.open(); old.receive(encoded(PairedFrame("late", null)))
        assertEquals(SessionState.CONNECTING, f.session.snapshot().state)
    }

    @Test fun replacingDuringBackoffCancelsOldRetryAndResetsNewTargetFailureCount() {
        val f = SessionFixture.create(); f.connector.rejectNextReason = "offline"; f.session.start()
        repeat(3) { f.connector.rejectNextReason = "offline"; f.scheduler.fireNextActive() }
        val oldRetry = f.scheduler.tasks.last(); assertEquals(8000L, oldRetry.delayMillis)
        f.connector.rejectNextReason = "new target offline"
        assertEquals(SelectionResult.Changed, f.session.selectConnection(selection()))
        assertTrue(oldRetry.cancelled); assertEquals(1000L, f.scheduler.tasks.last().delayMillis)
        val count = f.connector.openCalls.size; f.scheduler.fire(oldRetry)
        assertEquals(count, f.connector.openCalls.size); assertEquals(SessionState.RECONNECT_WAIT, f.session.snapshot().state)
    }

    @Test fun cleanupAndCancellationFailuresDoNotPreventNewSelectionOrReviveOldRetry() {
        val f = SessionFixture.create(); f.becomeActive()
        f.connector.current.closeFailure = IllegalStateException("close")
        f.commandCleanup.failure = IllegalStateException("command")
        f.missionCleanup.failure = IllegalStateException("mission")
        f.outbound.discardFailure = IllegalStateException("outbound")
        f.session.selectConnection(selection())
        assertEquals(2, f.connector.openCalls.size); assertEquals(1, f.outbound.discarded.size)
        f.connector.current.fail(); val retry = f.scheduler.tasks.last()
        retry.cancellationFailure = IllegalStateException("cancel")
        f.session.selectConnection(null); f.scheduler.fire(retry)
        assertEquals(SessionState.STOPPED, f.session.snapshot().state); assertEquals(2, f.connector.openCalls.size)
    }

    @Test fun oldBusinessFramesAreNeverDeliveredAfterSelectionChange() {
        val f = SessionFixture.create(); f.becomeActive(); val old = f.connector.current
        f.session.selectConnection(selection()); f.connector.current.open()
        f.connector.current.receive(encoded(PairedFrame("new", null)))
        old.receive(encoded(CommandFrame("old", "telemetry.read", JsonObject(emptyMap()))))
        assertTrue(f.consumer.accepted.isEmpty())
        f.connector.current.receive(encoded(CommandFrame("new", "telemetry.read", JsonObject(emptyMap()))))
        assertEquals(1, f.consumer.accepted.size)
        assertEquals("new", f.consumer.accepted.single().first.sessionId)
    }

    @Test fun concurrentSelectionAndStopNeverLeaveRunningTransportIntentAfterStop() {
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            repeat(100) {
                val f = SessionFixture.create(); f.session.start()
                val ready = java.util.concurrent.CountDownLatch(1)
                val change = executor.submit { ready.await(); f.session.selectConnection(selection()) }
                val stop = executor.submit { ready.await(); f.session.stop() }
                ready.countDown(); change.get(5, java.util.concurrent.TimeUnit.SECONDS); stop.get(5, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(SessionState.STOPPED, f.session.snapshot().state)
                f.session.selectConnection(selection("ws://later/relay"))
                assertEquals(SessionState.STOPPED, f.session.snapshot().state)
                assertTrue(f.connector.attempts.all { it.closeCount == 1 })
            }
        } finally { executor.shutdownNow() }
    }

    @Test fun pairingAdmissionRunsBeforeActiveAndReceivesTheCurrentSelection() {
        val calls = mutableListOf<ConnectionSelection>()
        val f = SessionFixture.create(endpoint = null, pairingAdmission = SessionPairingAdmission { selected, _ -> calls += selected; true })
        val selected = selection(); f.session.selectConnection(selected); f.session.start()
        assertTrue(calls.isEmpty()); f.connector.current.open(); assertTrue(calls.isEmpty())
        f.connector.current.receive(encoded(PairedFrame("admitted", null)))
        assertSame(selected, calls.single()); assertEquals(SessionState.ACTIVE, f.session.snapshot().state)
        f.connector.current.receive(encoded(PairedFrame("duplicate", null)))
        assertEquals(1, calls.size)
    }

    @Test fun delayedEndOfSameSelectionCannotClearTheNewAdmittedGeneration() {
        val selected = selection()
        val admissions = mutableListOf<SessionGeneration>()
        var activated: SessionGeneration? = null
        val f = SessionFixture.create(endpoint = null, pairingAdmission = SessionPairingAdmission { current, generation ->
            assertSame(selected, current)
            admissions += generation
            activated = generation
            true
        })
        val delivered = mutableListOf<SessionStateEvent>()
        f.session.onStateChanged { event ->
            delivered += event
            if (event.endedGeneration != null && event.endedGeneration == activated) activated = null
        }
        f.session.selectConnection(selected); f.becomeActive()
        val old = f.connector.current.generation
        f.connector.current.fail()
        f.scheduler.fireNextActive()
        f.connector.current.open(); f.connector.current.receive(encoded(PairedFrame("reconnected", null)))
        val next = f.connector.current.generation
        assertNotEquals(old, next)
        assertEquals(listOf(old, next), admissions)
        assertTrue(delivered.isEmpty())
        val end = f.notifier.events.single { it.endReason != null }
        assertSame(selected, end.connectionSelection)
        assertEquals(old, end.endedGeneration)
        assertNull(end.snapshot.generation)
        assertTrue(f.notifier.events.filter { it.endReason == null }.all { it.endedGeneration == null })
        f.notifier.drain()
        assertEquals(next, activated)
        f.session.selectConnection(null); f.notifier.drain()
        assertNull(activated)
        assertEquals(next, delivered.last().endedGeneration)
    }

    @Test fun stopOrPauseDuringBackoffDoesNotReportAnAlreadyEndedGeneration() {
        for (stop in listOf(true, false)) {
            val f = SessionFixture.create(); f.session.start()
            val generation = f.connector.current.generation
            f.connector.current.fail()
            assertEquals(generation, f.notifier.events.last().endedGeneration)
            if (stop) f.session.stop() else f.session.selectConnection(null)
            assertNull(f.notifier.events.last().endedGeneration)
            assertEquals(SessionState.STOPPED, f.notifier.events.last().snapshot.state)
        }
    }

    @Test fun rejectedPairingNeverBecomesActiveOrDeliversBusinessBeforeStateNotification() {
        val f = SessionFixture.create(pairingAdmission = SessionPairingAdmission { _, _ -> false })
        f.session.start(); val rejected = f.connector.current; rejected.open()
        val timeout = f.scheduler.tasks.single()
        rejected.receive(encoded(PairedFrame("rejected", null)))
        // The test notifier deliberately has not delivered any queued state events.
        rejected.receive(encoded(CommandFrame("must-not-run", "telemetry.read", JsonObject(emptyMap()))))
        assertTrue(f.consumer.accepted.isEmpty()); assertNull(f.session.activeSession())
        assertTrue(f.notifier.events.none { it.snapshot.state == SessionState.ACTIVE })
        assertEquals(SessionState.STOPPED, f.session.snapshot().state)
        assertEquals(SessionEndKind.CONNECTION_PAUSED, f.commandCleanup.calls.single().second.kind)
        assertTrue(timeout.cancelled); f.scheduler.fire(timeout)
        f.session.start(); assertEquals(1, f.connector.openCalls.size)
    }

    @Test fun throwingAdmissionPausesAndNewAdmittedSelectionCanRecover() {
        var permit = false
        val f = SessionFixture.create(pairingAdmission = SessionPairingAdmission { _, _ ->
            if (!permit) throw IllegalStateException("private token=secret")
            true
        })
        f.becomeActive()
        assertEquals(SessionState.STOPPED, f.session.snapshot().state)
        assertTrue(f.diagnostics.diagnostics.none { it.detail.contains("secret") })
        permit = true
        f.session.selectConnection(selection()); f.connector.current.open()
        f.connector.current.receive(encoded(PairedFrame("recovered", null)))
        assertEquals(SessionState.ACTIVE, f.session.snapshot().state)
    }

    @Test fun selectionChangeRunsBeforeQueuedBusinessFramesWithoutInterruptingTheOneAlreadyStarted() {
        val f = SessionFixture.create(); f.becomeActive(); val old = f.connector.current
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        f.consumer.beforeAccept = {
            entered.countDown()
            assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        val first = kotlin.concurrent.thread {
            old.receive(encoded(CommandFrame("started", "telemetry.read", JsonObject(emptyMap()))))
        }
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
        repeat(10) { old.receive(encoded(CommandFrame("queued-$it", "telemetry.read", JsonObject(emptyMap())))) }
        val changed = java.util.concurrent.CountDownLatch(1)
        val change = kotlin.concurrent.thread {
            try { f.session.selectConnection(selection()) } finally { changed.countDown() }
        }
        try {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (change.state != Thread.State.WAITING && change.isAlive && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.WAITING, change.state)
        } finally { release.countDown() }
        assertTrue(changed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        first.join(5000); change.join(5000)
        assertFalse(first.isAlive); assertFalse(change.isAlive)
        assertEquals(1, f.consumer.accepted.size)
        assertEquals(SessionState.CONNECTING, f.session.snapshot().state)
        assertEquals(2, f.connector.openCalls.size)
    }
}
