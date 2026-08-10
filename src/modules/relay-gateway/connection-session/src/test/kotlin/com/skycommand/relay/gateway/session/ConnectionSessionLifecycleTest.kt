package com.skycommand.relay.gateway.session

import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.HelloFrame
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.PairedFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConnectionSessionLifecycleTest {

    @Test
    fun startCreatesExactlyOneConnectingAttemptAndDuplicateStartIsIdempotent() {
        val fixture = SessionFixture.create()

        assertEquals(StartResult.StartAccepted, fixture.session.start())
        val connecting = fixture.session.snapshot()

        assertEquals(SessionState.CONNECTING, connecting.state)
        assertNotNull(connecting.generation)
        assertNull(connecting.sessionId)
        assertEquals(1, fixture.connector.openCount)
        assertEquals(StartResult.AlreadyRunning(connecting), fixture.session.start())
        assertEquals(1, fixture.connector.openCount)
    }

    @Test
    fun completesHelloPairedHandshakeAndForwardsOnlyActiveFrames() {
        val fixture = SessionFixture.create()
        fixture.session.start()
        val connection = fixture.connector.current

        connection.open()

        assertEquals(SessionState.AWAITING_PAIRING, fixture.session.snapshot().state)
        assertEquals(1, fixture.outbound.attachments.size)
        val hello = fixture.outbound.handshakes.single().second
        assertEquals(HelloFrame("android-device", "1"), hello)
        assertEquals(10_000, fixture.scheduler.tasks.single().delayMillis)

        connection.receive(encoded(PairedFrame("desktop-session", null)))

        val active = fixture.session.snapshot()
        assertEquals(SessionState.ACTIVE, active.state)
        assertEquals("desktop-session", active.sessionId)
        assertEquals(true, fixture.scheduler.tasks.single().cancelled)

        val command = CommandFrame("command-1", "telemetry.read", JsonObject(emptyMap()))
        connection.receive(encoded(command))

        val accepted = fixture.consumer.accepted.single()
        assertEquals(active.generation, accepted.first.generation)
        assertEquals("desktop-session", accepted.first.sessionId)
        assertEquals(command, accepted.second)
    }

    @Test
    fun explicitStopUsesContractCleanupOrderExactlyOnce() {
        val fixture = SessionFixture.create()
        fixture.becomeActive()
        val generation = assertNotNull(fixture.session.snapshot().generation)
        fixture.order.clear()

        assertEquals(StopResult.Stopped, fixture.session.stop())

        assertEquals(SessionSnapshot(SessionState.STOPPED, null, null), fixture.session.snapshot())
        assertEquals(listOf("close", "commands", "mission", "outbound", "notify"), fixture.order)
        assertEquals(1, fixture.connector.current.closeCount)
        assertEquals(generation, fixture.commandCleanup.calls.single().first)
        assertEquals(SessionEndKind.EXPLICIT_STOP, fixture.commandCleanup.calls.single().second.kind)
        assertEquals(generation, fixture.missionCleanup.calls.single().first)
        assertEquals(listOf(generation), fixture.outbound.discarded)
        assertEquals(SessionEndKind.EXPLICIT_STOP, fixture.notifier.events.last().endReason?.kind)

        assertEquals(StopResult.AlreadyStopped, fixture.session.stop())
        assertEquals(listOf("close", "commands", "mission", "outbound", "notify"), fixture.order)
    }

    @Test
    fun stopDuringHandshakeCancelsTimeoutAndNeverForwardsLaterPairing() {
        val fixture = SessionFixture.create()
        fixture.session.start()
        val connection = fixture.connector.current
        connection.open()
        val timeout = fixture.scheduler.tasks.single()

        fixture.session.stop()
        connection.receive(encoded(PairedFrame("too-late", null)))

        assertEquals(true, timeout.cancelled)
        assertEquals(SessionState.STOPPED, fixture.session.snapshot().state)
        assertEquals(0, fixture.consumer.accepted.size)
        assertIs<SessionDiagnostic>(fixture.diagnostics.diagnostics.last())
    }
}
