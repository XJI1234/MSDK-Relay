package com.skycommand.relay.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RelaySessionStateTest {

    @Test
    fun connectionMustCompleteHandshakeBeforeCommandsAreAccepted() {
        val session = RelaySessionStateMachine()

        assertEquals(SessionState.HELLO_SENT, assertIs<Accepted<SessionTransition>>(session.onConnected()).value.state)
        assertEquals(
            SessionState.ACTIVE,
            assertIs<Accepted<SessionTransition>>(
                session.onFrame(PairedFrame("session", null))
            ).value.state,
        )
        assertIs<Accepted<SessionTransition>>(
            session.onFrame(CommandFrame("id", "telemetry.read", JsonObject(emptyMap())))
        )
    }

    @Test
    fun rejectsFramesBeforeConnection() {
        val result = RelaySessionStateMachine().onFrame(PairedFrame("session", null))

        assertEquals(ProtocolErrorCode.INVALID_SESSION_STATE, assertIs<Rejected>(result).error.code)
    }

    @Test
    fun rejectsNonPairedFramesDuringHandshake() {
        val session = RelaySessionStateMachine()
        session.onConnected()

        val result = session.onFrame(CommandFrame("id", "telemetry.read", JsonObject(emptyMap())))

        assertEquals(ProtocolErrorCode.HANDSHAKE_REQUIRED, assertIs<Rejected>(result).error.code)
    }

    @Test
    fun rejectsDuplicateHandshake() {
        val session = RelaySessionStateMachine()
        session.onConnected()
        session.onFrame(PairedFrame("session", null))

        val result = session.onFrame(PairedFrame("new-session", null))

        assertEquals(ProtocolErrorCode.DUPLICATE_HANDSHAKE, assertIs<Rejected>(result).error.code)
    }

    @Test
    fun disconnectResetsState() {
        val session = RelaySessionStateMachine()
        session.onConnected()
        session.onFrame(PairedFrame("session", null))

        assertEquals(SessionState.DISCONNECTED, session.onDisconnected().state)
    }
}
