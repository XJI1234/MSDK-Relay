package com.skycommand.relay.gateway.session

import java.util.UUID

enum class SessionState {
    STOPPED,
    CONNECTING,
    AWAITING_PAIRING,
    ACTIVE,
    RECONNECT_WAIT,
}

class SessionGeneration private constructor(private val id: UUID) {
    internal companion object {
        fun create(): SessionGeneration = SessionGeneration(UUID.randomUUID())
    }

    override fun equals(other: Any?): Boolean = other is SessionGeneration && id == other.id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "SessionGeneration(opaque)"
}

class ActiveSession internal constructor(
    val generation: SessionGeneration,
    val sessionId: String,
) {
    override fun equals(other: Any?): Boolean =
        other is ActiveSession && generation == other.generation && sessionId == other.sessionId

    override fun hashCode(): Int = 31 * generation.hashCode() + sessionId.hashCode()

    override fun toString(): String = "ActiveSession(generation=$generation, sessionId=<redacted>)"
}

data class SessionSnapshot(
    val state: SessionState,
    val generation: SessionGeneration?,
    val sessionId: String?,
)

enum class SessionEndKind {
    EXPLICIT_STOP,
    NOT_CONNECTED,
    HANDSHAKE_TIMEOUT,
    INVALID_FRAME,
    UNSUPPORTED_FRAME,
    PROTOCOL_VERSION_UNSUPPORTED,
}

class SessionEndReason private constructor(
    val kind: SessionEndKind,
    val detail: String,
) {
    internal companion object {
        fun create(kind: SessionEndKind, detail: String): SessionEndReason {
            require(detail.isNotBlank()) { "Session end detail must not be blank" }
            require(detail.codePointCount(0, detail.length) <= 256) { "Session end detail is too long" }
            require(detail.none(Char::isISOControl)) { "Session end detail contains a control character" }
            return SessionEndReason(kind, detail)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is SessionEndReason && kind == other.kind && detail == other.detail

    override fun hashCode(): Int = 31 * kind.hashCode() + detail.hashCode()

    override fun toString(): String = "SessionEndReason(kind=$kind, detail=<redacted>)"
}

data class SessionStateEvent(
    val previousState: SessionState,
    val snapshot: SessionSnapshot,
    val endReason: SessionEndReason?,
)

sealed interface StartResult {
    data object StartAccepted : StartResult

    data class AlreadyRunning(val snapshot: SessionSnapshot) : StartResult
}

sealed interface StopResult {
    data object Stopped : StopResult

    data object AlreadyStopped : StopResult
}

data class SessionConfig(
    val endpoint: String,
    val deviceId: String,
    val handshakeTimeoutMillis: Long = 10_000,
    val reconnectInitialDelayMillis: Long = 1_000,
    val reconnectMaxDelayMillis: Long = 30_000,
)

sealed interface SessionCreationResult

data class SessionCreated(val session: ConnectionSession) : SessionCreationResult

data class ConfigurationRejected(val detail: String) : SessionCreationResult

fun interface Registration {
    fun unregister()
}
