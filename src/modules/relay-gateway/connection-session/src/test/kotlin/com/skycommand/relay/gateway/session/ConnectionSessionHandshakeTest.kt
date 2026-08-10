package com.skycommand.relay.gateway.session

import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.HelloFrame
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.PairedFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ConnectionSessionHandshakeTest {

    @Test
    fun ignoresUnknownExtensionDuringHandshakeWithoutCancellingTimeout() {
        val fixture = SessionFixture.create()
        fixture.session.start()
        fixture.connector.current.open()
        val timeout = fixture.scheduler.tasks.single()

        fixture.connector.current.receive("""{"type":"future-extension","value":1}""".encodeToByteArray())

        assertEquals(SessionState.AWAITING_PAIRING, fixture.session.snapshot().state)
        assertEquals(false, timeout.cancelled)
        assertEquals(0, fixture.consumer.accepted.size)
    }

    @Test
    fun rejectsKnownBusinessFrameBeforePairingAndDoesNotCacheIt() {
        val fixture = SessionFixture.create()
        fixture.session.start()
        fixture.connector.current.open()
        val command = CommandFrame("command-1", "telemetry.read", JsonObject(emptyMap()))

        fixture.connector.current.receive(encoded(command))

        assertEquals(SessionState.RECONNECT_WAIT, fixture.session.snapshot().state)
        assertEquals(SessionEndKind.UNSUPPORTED_FRAME, fixture.notifier.events.last().endReason?.kind)
        assertEquals(0, fixture.consumer.accepted.size)
    }

    @Test
    fun classifiesMalformedHandshakeAndUnsupportedProtocolVersionSeparately() {
        val malformed = SessionFixture.create()
        malformed.session.start()
        malformed.connector.current.open()
        malformed.connector.current.receive("{".encodeToByteArray())

        val unsupported = SessionFixture.create()
        unsupported.session.start()
        unsupported.connector.current.open()
        unsupported.connector.current.receive(
            """{"type":"paired","sessionId":"desktop","protocolVersion":"2"}""".encodeToByteArray()
        )

        assertEquals(SessionEndKind.INVALID_FRAME, malformed.notifier.events.last().endReason?.kind)
        assertEquals(
            SessionEndKind.PROTOCOL_VERSION_UNSUPPORTED,
            unsupported.notifier.events.last().endReason?.kind,
        )
    }

    @Test
    fun duplicateOpenedCallbackDoesNotSendAnotherHello() {
        val fixture = SessionFixture.create()
        fixture.session.start()

        fixture.connector.current.open()
        fixture.connector.current.open()

        assertEquals(1, fixture.outbound.attachments.size)
        assertEquals(1, fixture.outbound.handshakes.size)
        assertEquals(SessionState.AWAITING_PAIRING, fixture.session.snapshot().state)
        assertTrue(fixture.diagnostics.diagnostics.any { it.kind == SessionDiagnosticKind.ADAPTER_VIOLATION })
    }

    @Test
    fun attachAndHandshakeSendRejectionNeverEnterAwaitingPairing() {
        val attachRejected = SessionFixture.create()
        attachRejected.outbound.attachResult = AttachResult.AttachRejected
        attachRejected.session.start()
        attachRejected.connector.current.open()

        val sendRejected = SessionFixture.create()
        sendRejected.outbound.sendResult = HandshakeSendResult.SendRejected
        sendRejected.session.start()
        sendRejected.connector.current.open()

        assertEquals(SessionState.RECONNECT_WAIT, attachRejected.session.snapshot().state)
        assertEquals(0, attachRejected.scheduler.tasks.count { it.delayMillis == 10_000L })
        assertEquals(SessionState.RECONNECT_WAIT, sendRejected.session.snapshot().state)
        assertEquals(0, sendRejected.scheduler.tasks.count { it.delayMillis == 10_000L })
    }

    @Test
    fun duplicateHandshakeEndsActiveSessionInsteadOfReplacingSessionId() {
        val fixture = SessionFixture.create()
        fixture.becomeActive("first-session")

        fixture.connector.current.receive(encoded(PairedFrame("replacement-session", null)))

        assertEquals(SessionState.RECONNECT_WAIT, fixture.session.snapshot().state)
        assertEquals(SessionEndKind.UNSUPPORTED_FRAME, fixture.notifier.events.last().endReason?.kind)
    }

    @Test
    fun incomingHelloEndsActiveSessionAsDuplicateHandshake() {
        val fixture = SessionFixture.create()
        fixture.becomeActive()

        fixture.connector.current.receive(encoded(HelloFrame("desktop-device", "1")))

        assertEquals(SessionState.RECONNECT_WAIT, fixture.session.snapshot().state)
        assertEquals(SessionEndKind.UNSUPPORTED_FRAME, fixture.notifier.events.last().endReason?.kind)
    }

    @Test
    fun malformedActiveFrameIsDiscardedWithoutClosingHealthySession() {
        val fixture = SessionFixture.create()
        fixture.becomeActive()

        fixture.connector.current.receive("{".encodeToByteArray())

        assertEquals(SessionState.ACTIVE, fixture.session.snapshot().state)
        assertEquals(0, fixture.consumer.accepted.size)
        assertTrue(fixture.diagnostics.diagnostics.any { it.kind == SessionDiagnosticKind.INVALID_FRAME })
    }

    @Test
    fun consumerFailureDoesNotEscapeOrRedeliverFrame() {
        val fixture = SessionFixture.create()
        fixture.becomeActive()
        fixture.consumer.failure = IllegalStateException("private failure")
        val command = CommandFrame("command-1", "telemetry.read", JsonObject(emptyMap()))

        fixture.connector.current.receive(encoded(command))

        assertEquals(SessionState.ACTIVE, fixture.session.snapshot().state)
        assertEquals(0, fixture.consumer.accepted.size)
        assertTrue(fixture.diagnostics.diagnostics.any { it.kind == SessionDiagnosticKind.DEPENDENCY_FAILURE })
    }

    @Test
    fun bytesBeforeOpenedAreIgnoredWithoutStartingHandshake() {
        val fixture = SessionFixture.create()
        fixture.session.start()

        fixture.connector.current.receive(encoded(PairedFrame("too-early", null)))

        assertEquals(SessionState.CONNECTING, fixture.session.snapshot().state)
        assertEquals(0, fixture.outbound.handshakes.size)
        assertIs<SessionDiagnostic>(fixture.diagnostics.diagnostics.last())
    }
}
