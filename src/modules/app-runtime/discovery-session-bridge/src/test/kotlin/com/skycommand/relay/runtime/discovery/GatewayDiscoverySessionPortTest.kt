package com.skycommand.relay.runtime.discovery

import com.skycommand.relay.discovery.DiscoveryClock
import com.skycommand.relay.discovery.DiscoveryPhase
import com.skycommand.relay.gateway.RelayGateway
import com.skycommand.relay.gateway.RelayGatewayConfig
import com.skycommand.relay.gateway.command.CommandHandler
import com.skycommand.relay.gateway.mission.*
import com.skycommand.relay.gateway.session.*
import com.skycommand.relay.protocol.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class GatewayDiscoverySessionPortTest {
    @Test fun publicGatewayAdapterConnectsDispatchesAndClosesTheSameGateway() {
        var now = 0L
        val bridge = DiscoverySessionBridge(DiscoveryClock { now })
        val transports = CopyOnWriteArrayList<Pair<TransportConnection, TransportListener>>()
        val gateway = RelayGateway.create(
            RelayGatewayConfig(null, "gateway-bridge-device", object : MissionSink {
                override fun begin(metadata: MissionMetadata) = MissionSinkResult.Accepted
                override fun append(bytes: ByteArray) = MissionSinkResult.Accepted
                override fun complete() = MissionSinkCompletionResult.Rejected
                override fun abort(reason: MissionAbortReason) = Unit
            }, pairingAdmission = bridge.pairingAdmission),
            TransportConnector { _, generation, listener ->
                val connection = object : TransportConnection {
                    override val generation = generation
                    override val writer = TransportWriter { TransportWriteResult.WriteAccepted }
                    override fun close(reason: String) = TransportCloseResult.CloseRequested
                }
                transports += connection to listener
                TransportOpenResult.OpenAccepted(connection)
            },
            MonotonicScheduler { _, _ -> ScheduledCancellation { } },
        )
        val commands = CopyOnWriteArrayList<String>()
        gateway.registerCommandHandler("telemetry.read", CommandHandler { request, completion ->
            commands += request.id
            completion.succeed("read")
        })
        bridge.bind(GatewayDiscoverySessionPort(gateway))
        try {
            bridge.start(); assertEquals(SessionState.STOPPED, gateway.connectionState())
            bridge.receive(bridge.networkGeneration(), announcement(), "192.168.1.20")
            now = 3000; bridge.tick()
            val (connection, listener) = transports.single()
            listener.onOpened(connection)
            fun receive(frame: RelayFrame) {
                listener.onBytes(connection.generation, assertIs<Accepted<ByteArray>>(RelayFrameCodec.encode(frame)).value)
            }
            receive(PairedFrame("gateway-session", null))
            receive(CommandFrame("read-1", "telemetry.read", JsonObject(emptyMap())))
            assertEquals(SessionState.ACTIVE, gateway.connectionState())
            assertEquals(DiscoveryPhase.ACTIVE, bridge.snapshot().phase)
            assertEquals(listOf("read-1"), commands.toList())
            bridge.close()
            receive(CommandFrame("old", "telemetry.read", JsonObject(emptyMap())))
            assertEquals(SessionState.STOPPED, gateway.connectionState())
            assertEquals(listOf("read-1"), commands.toList())
        } finally { bridge.close() }
    }
}
