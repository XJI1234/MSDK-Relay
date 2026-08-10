package com.skycommand.relay.gateway

import com.skycommand.relay.gateway.command.CommandDispatcher
import com.skycommand.relay.gateway.command.CommandHandler
import com.skycommand.relay.gateway.command.RegistrationResult
import com.skycommand.relay.gateway.command.UnregistrationResult
import com.skycommand.relay.gateway.mission.MissionResultPublisher
import com.skycommand.relay.gateway.mission.MissionSink
import com.skycommand.relay.gateway.mission.MissionTransfer
import com.skycommand.relay.gateway.outbound.OutboundPublisher
import com.skycommand.relay.gateway.outbound.PublishRejectionKind
import com.skycommand.relay.gateway.outbound.PublishResult
import com.skycommand.relay.gateway.session.ActiveFrameConsumer
import com.skycommand.relay.gateway.session.ConfigurationRejected
import com.skycommand.relay.gateway.session.ConnectionSession
import com.skycommand.relay.gateway.session.ExecutorOrderedStateNotifier
import com.skycommand.relay.gateway.session.MonotonicScheduler
import com.skycommand.relay.gateway.session.Registration
import com.skycommand.relay.gateway.session.SessionConfig
import com.skycommand.relay.gateway.session.SessionCreated
import com.skycommand.relay.gateway.session.SessionDependencies
import com.skycommand.relay.gateway.session.SessionDiagnosticSink
import com.skycommand.relay.gateway.session.SessionState
import com.skycommand.relay.gateway.session.SessionStateListener
import com.skycommand.relay.gateway.session.StartResult
import com.skycommand.relay.gateway.session.StopResult
import com.skycommand.relay.gateway.session.TransportConnector
import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.CommandResultFrame
import com.skycommand.relay.protocol.MissionBeginFrame
import com.skycommand.relay.protocol.MissionChunkFrame
import com.skycommand.relay.protocol.MissionCompleteFrame
import com.skycommand.relay.protocol.MissionResultFrame
import com.skycommand.relay.protocol.RelayFrame
import com.skycommand.relay.protocol.TelemetryFrame
import java.util.concurrent.ForkJoinPool

/** Immutable dependencies and connection policy for one relay gateway instance. */
data class RelayGatewayConfig(
    val endpoint: String,
    val deviceId: String,
    val missionSink: MissionSink,
    val handshakeTimeoutMillis: Long = 10_000,
    val reconnectInitialDelayMillis: Long = 1_000,
    val reconnectMaxDelayMillis: Long = 30_000,
    val diagnosticSink: SessionDiagnosticSink = SessionDiagnosticSink { },
)

/**
 * The sole composition root for the relay transport, session, command, mission,
 * and outbound modules. It does not contain DJI-specific business behavior.
 */
class RelayGateway private constructor(
    private val session: ConnectionSession,
    private val outbound: OutboundPublisher,
    private val commands: CommandDispatcher,
) {
    fun start(): StartResult = session.start()

    fun stop(): StopResult = session.stop()

    fun connectionState(): SessionState = session.snapshot().state

    fun registerCommandHandler(name: String, handler: CommandHandler): RegistrationResult =
        commands.register(name, handler)

    fun unregisterCommandHandler(name: String): UnregistrationResult = commands.unregister(name)

    fun publishTelemetry(frame: TelemetryFrame): PublishResult = publish(frame)

    fun publishCommandResult(frame: CommandResultFrame): PublishResult = publish(frame)

    fun publishMissionResult(frame: MissionResultFrame): PublishResult = publish(frame)

    fun onStateChanged(listener: SessionStateListener): Registration = session.onStateChanged(listener)

    private fun publish(frame: RelayFrame): PublishResult {
        val activeSession = session.activeSession()
            ?: return PublishResult.Rejected(PublishRejectionKind.STALE_SESSION)
        return outbound.publish(activeSession, frame)
    }

    companion object {
        fun create(
            config: RelayGatewayConfig,
            transport: TransportConnector,
            clock: MonotonicScheduler,
        ): RelayGateway {
            val outbound = OutboundPublisher()
            val commands = CommandDispatcher { activeSession, frame -> outbound.publish(activeSession, frame) }
            val missions = MissionTransfer(
                config.missionSink,
                MissionResultPublisher { activeSession, frame -> outbound.publish(activeSession, frame) },
            )
            val activeFrameConsumer = ActiveFrameConsumer { activeSession, frame ->
                when (frame) {
                    is CommandFrame -> commands.dispatch(activeSession, frame)
                    is MissionBeginFrame,
                    is MissionChunkFrame,
                    is MissionCompleteFrame,
                    -> missions.accept(activeSession, frame)

                    else -> Unit
                }
            }
            val creation = ConnectionSession.create(
                SessionConfig(
                    endpoint = config.endpoint,
                    deviceId = config.deviceId,
                    handshakeTimeoutMillis = config.handshakeTimeoutMillis,
                    reconnectInitialDelayMillis = config.reconnectInitialDelayMillis,
                    reconnectMaxDelayMillis = config.reconnectMaxDelayMillis,
                ),
                SessionDependencies(
                    connector = transport,
                    outbound = outbound,
                    activeFrameConsumer = activeFrameConsumer,
                    commandCleanup = commands,
                    missionCleanup = missions,
                    scheduler = clock,
                    stateNotifier = ExecutorOrderedStateNotifier(ForkJoinPool.commonPool(), config.diagnosticSink),
                    diagnosticSink = config.diagnosticSink,
                ),
            )
            return when (creation) {
                is SessionCreated -> RelayGateway(creation.session, outbound, commands)
                is ConfigurationRejected -> throw IllegalArgumentException(creation.detail)
            }
        }
    }
}
