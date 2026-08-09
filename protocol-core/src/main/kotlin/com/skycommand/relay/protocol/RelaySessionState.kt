package com.skycommand.relay.protocol

enum class SessionState {
    DISCONNECTED,
    HELLO_SENT,
    ACTIVE,
}

data class SessionTransition(
    val state: SessionState,
    val frame: RelayFrame? = null,
)

class RelaySessionStateMachine {
    var state: SessionState = SessionState.DISCONNECTED
        private set

    fun onConnected(): ProtocolResult<SessionTransition> {
        return if (state == SessionState.DISCONNECTED) {
            state = SessionState.HELLO_SENT
            Accepted(SessionTransition(state))
        } else {
            Rejected(ProtocolError(ProtocolErrorCode.INVALID_SESSION_STATE, "Session is already connected"))
        }
    }

    fun onFrame(frame: RelayFrame): ProtocolResult<SessionTransition> {
        val validation = validate(frame)
        if (validation is Rejected) {
            return validation
        }
        return when (state) {
            SessionState.DISCONNECTED -> Rejected(
                ProtocolError(ProtocolErrorCode.INVALID_SESSION_STATE, "Frame arrived before connection")
            )

            SessionState.HELLO_SENT -> {
                if (frame is PairedFrame) {
                    state = SessionState.ACTIVE
                    Accepted(SessionTransition(state, frame))
                } else {
                    Rejected(ProtocolError(ProtocolErrorCode.HANDSHAKE_REQUIRED, "Paired frame is required"))
                }
            }

            SessionState.ACTIVE -> {
                if (frame is PairedFrame || frame is HelloFrame) {
                    Rejected(ProtocolError(ProtocolErrorCode.DUPLICATE_HANDSHAKE, "Handshake was already completed"))
                } else {
                    Accepted(SessionTransition(state, frame))
                }
            }
        }
    }

    fun onDisconnected(): SessionTransition {
        state = SessionState.DISCONNECTED
        return SessionTransition(state)
    }
}
