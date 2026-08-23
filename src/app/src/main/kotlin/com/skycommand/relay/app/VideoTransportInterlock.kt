package com.skycommand.relay.app

import com.skycommand.relay.gateway.command.CommandCompletion
import com.skycommand.relay.gateway.command.CommandHandler
import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonObject
import java.util.concurrent.atomic.AtomicBoolean

internal class VideoTransportInterlock(
    private val legacy: CommandHandler,
    private val whip: CommandHandler,
) {
    private val lock = Any()
    private var closed = false
    private var nextOperationId = 0L
    private var ownership: Ownership? = null

    fun handlerFor(commandName: String): CommandHandler {
        val route = routes[commandName]
            ?: return CommandHandler { _, completion -> completion.reject("Video transport command is not available") }
        return CommandHandler { command, completion ->
            if (command.name != commandName) {
                completion.reject("Video transport command is not available")
            } else {
                handle(route, command, completion)
            }
        }
    }

    fun markDeviceUnavailable() {
        synchronized(lock) { ownership = null }
    }

    fun releaseStreamingOwnership() {
        synchronized(lock) {
            if (ownership?.phase == OwnershipPhase.STREAMING) ownership = null
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            ownership = null
        }
    }

    private fun handle(route: Route, command: CommandFrame, completion: CommandCompletion) {
        when (val decision = decide(route)) {
            Decision.RejectedForActiveTransport -> completion.reject("Another video transport is active")
            Decision.RejectedForClosedGraph -> completion.reject("Video transport is unavailable")
            is Decision.Direct -> decision.handler.handle(command, completion)
            is Decision.Owned -> invokeOwned(decision, command, completion)
        }
    }

    private fun decide(route: Route): Decision = synchronized(lock) {
        if (closed) return Decision.RejectedForClosedGraph
        val current = ownership
        when (route.action) {
            VideoAction.START -> when {
                current == null -> acquire(route, OwnershipPhase.STARTING)
                current.transport != route.transport -> Decision.RejectedForActiveTransport
                else -> Decision.Direct(handlerFor(route.transport))
            }

            VideoAction.STOP -> when {
                current == null -> Decision.Direct(handlerFor(route.transport))
                current.transport != route.transport -> Decision.RejectedForActiveTransport
                else -> acquire(route, OwnershipPhase.STOPPING)
            }
        }
    }

    private fun acquire(route: Route, phase: OwnershipPhase): Decision.Owned {
        val owned = Ownership(route.transport, phase, ++nextOperationId)
        ownership = owned
        return Decision.Owned(handlerFor(route.transport), owned)
    }

    private fun handlerFor(transport: VideoTransport): CommandHandler = when (transport) {
        VideoTransport.LEGACY -> legacy
        VideoTransport.WHIP -> whip
    }

    private fun invokeOwned(decision: Decision.Owned, command: CommandFrame, completion: CommandCompletion) {
        val wrapped = OwnedCompletion(decision.ownership, completion)
        try {
            decision.handler.handle(command, wrapped)
        } catch (_: Throwable) {
            wrapped.reject("Video transport operation failed")
        }
    }

    private fun settle(owned: Ownership, succeeded: Boolean) {
        synchronized(lock) {
            if (ownership != owned) return
            ownership = when (owned.phase) {
                OwnershipPhase.STARTING -> if (succeeded) owned.copy(phase = OwnershipPhase.STREAMING) else null
                OwnershipPhase.STOPPING -> if (succeeded) null else owned
                OwnershipPhase.STREAMING -> owned
            }
        }
    }

    private inner class OwnedCompletion(
        private val owned: Ownership,
        private val delegate: CommandCompletion,
    ) : CommandCompletion {
        private val completed = AtomicBoolean(false)

        override fun succeed(detail: String) = complete(succeeded = true) {
            delegate.succeed(detail)
        }

        override fun succeed(detail: String, result: JsonObject?) = complete(succeeded = true) {
            delegate.succeed(detail, result)
        }

        override fun reject(detail: String) = complete(succeeded = false) {
            delegate.reject(detail)
        }

        private fun complete(succeeded: Boolean, forward: () -> Unit) {
            if (!completed.compareAndSet(false, true)) return
            settle(owned, succeeded)
            forward()
        }
    }

    private data class Ownership(
        val transport: VideoTransport,
        val phase: OwnershipPhase,
        val operationId: Long,
    )

    private data class Route(
        val transport: VideoTransport,
        val action: VideoAction,
    )

    private sealed interface Decision {
        data object RejectedForActiveTransport : Decision
        data object RejectedForClosedGraph : Decision
        data class Direct(val handler: CommandHandler) : Decision
        data class Owned(val handler: CommandHandler, val ownership: Ownership) : Decision
    }

    private enum class VideoTransport { LEGACY, WHIP }

    private enum class VideoAction { START, STOP }

    private enum class OwnershipPhase { STARTING, STREAMING, STOPPING }

    private companion object {
        val routes = mapOf(
            "live-stream.start" to Route(VideoTransport.LEGACY, VideoAction.START),
            "live-stream.stop" to Route(VideoTransport.LEGACY, VideoAction.STOP),
            "live-stream-webrtc.start" to Route(VideoTransport.WHIP, VideoAction.START),
            "live-stream-webrtc.stop" to Route(VideoTransport.WHIP, VideoAction.STOP),
        )
    }
}
