package com.skycommand.relay.gateway.command

import com.skycommand.relay.gateway.outbound.PublishResult
import com.skycommand.relay.gateway.session.ActiveSession
import com.skycommand.relay.gateway.session.CommandSessionCleanup
import com.skycommand.relay.gateway.session.SessionEndReason
import com.skycommand.relay.gateway.session.SessionGeneration
import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.CommandResultFrame
import com.skycommand.relay.protocol.ProtocolLimits
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

fun interface CommandHandler {
    fun handle(command: CommandFrame, completion: CommandCompletion)
}

interface CommandCompletion {
    fun succeed(detail: String = "")

    fun reject(detail: String = "")
}

fun interface CommandResultPublisher {
    fun publish(activeSession: ActiveSession, frame: CommandResultFrame): PublishResult
}

sealed interface RegistrationResult {
    data object Registered : RegistrationResult

    data object RegistrationRejected : RegistrationResult
}

sealed interface UnregistrationResult {
    data object Removed : UnregistrationResult

    data object NotRegistered : UnregistrationResult
}

sealed interface DispatchResult {
    data object DispatchAccepted : DispatchResult

    data class DispatchRejected(val kind: DispatchRejectionKind) : DispatchResult

    data object DuplicateInFlight : DispatchResult
}

enum class DispatchRejectionKind {
    UNKNOWN_COMMAND,
    CAPACITY_EXCEEDED,
}

class CommandDispatcher(
    private val resultPublisher: CommandResultPublisher,
) : CommandSessionCleanup {
    private val lock = ReentrantLock()
    private val handlers = mutableMapOf<String, CommandHandler>()
    private val pending = mutableMapOf<PendingKey, PendingCommand>()

    fun register(commandName: String, handler: CommandHandler): RegistrationResult = lock.withLock {
        if (commandName !in allowedCommandNames || commandName in handlers) {
            RegistrationResult.RegistrationRejected
        } else {
            handlers[commandName] = handler
            RegistrationResult.Registered
        }
    }

    fun unregister(commandName: String): UnregistrationResult = lock.withLock {
        if (handlers.remove(commandName) == null) {
            UnregistrationResult.NotRegistered
        } else {
            UnregistrationResult.Removed
        }
    }

    fun dispatch(activeSession: ActiveSession, command: CommandFrame): DispatchResult {
        val key = PendingKey(activeSession.generation, command.id)
        val pendingCommand = lock.withLock {
            if (key in pending) {
                return DispatchResult.DuplicateInFlight
            }
            val handler = handlers[command.name]
            if (handler == null) {
                return rejectImmediately(activeSession, command.id, DispatchRejectionKind.UNKNOWN_COMMAND)
            }
            if (pending.keys.count { it.generation == activeSession.generation } >= maxPendingCommandsPerGeneration) {
                return rejectImmediately(activeSession, command.id, DispatchRejectionKind.CAPACITY_EXCEEDED)
            }
            PendingCommand(activeSession, command.id, handler).also { pending[key] = it }
        }

        val completion = Completion(key)
        try {
            pendingCommand.handler.handle(command, completion)
        } catch (_: Throwable) {
            completion.reject("Command failed")
        }
        return DispatchResult.DispatchAccepted
    }

    override fun cancel(generation: SessionGeneration, reason: SessionEndReason) {
        lock.withLock {
            pending.keys.removeAll { it.generation == generation }
        }
    }

    private fun rejectImmediately(
        activeSession: ActiveSession,
        id: String,
        kind: DispatchRejectionKind,
    ): DispatchResult {
        val detail = when (kind) {
            DispatchRejectionKind.UNKNOWN_COMMAND -> "Command is not available"
            DispatchRejectionKind.CAPACITY_EXCEEDED -> "Too many commands are pending"
        }
        publish(activeSession, CommandResultFrame(id, false, detail))
        return DispatchResult.DispatchRejected(kind)
    }

    private fun complete(key: PendingKey, ok: Boolean, detail: String) {
        val command = lock.withLock { pending.remove(key) } ?: return
        val detailIsValid = isValidDetail(detail)
        val safeDetail = if (detailIsValid) detail else "Command result is invalid"
        publish(command.activeSession, CommandResultFrame(command.id, ok && detailIsValid, safeDetail))
    }

    private fun publish(activeSession: ActiveSession, frame: CommandResultFrame) {
        runCatching { resultPublisher.publish(activeSession, frame) }
    }

    private fun isValidDetail(detail: String): Boolean =
        detail.codePointCount(0, detail.length) <= ProtocolLimits.maxResultDetailCodePoints &&
            detail.none(Char::isISOControl)

    private inner class Completion(
        private val key: PendingKey,
    ) : CommandCompletion {
        override fun succeed(detail: String) = complete(key, ok = true, detail)

        override fun reject(detail: String) = complete(key, ok = false, detail)
    }

    private data class PendingKey(
        val generation: SessionGeneration,
        val id: String,
    )

    private data class PendingCommand(
        val activeSession: ActiveSession,
        val id: String,
        val handler: CommandHandler,
    )

    private companion object {
        const val maxPendingCommandsPerGeneration = 64

        val allowedCommandNames = setOf(
            "telemetry.read",
            "pairing.start",
            "pairing.stop",
            "pairing.status",
            "wayline.generate",
            "wayline.upload",
            "wayline.start",
            "wayline.pause",
            "wayline.resume",
            "wayline.stop",
            "live-stream.start",
            "live-stream.stop",
        )
    }
}
