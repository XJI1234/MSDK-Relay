package com.skycommand.relay.gateway.transport

import com.skycommand.relay.gateway.session.SessionGeneration
import com.skycommand.relay.gateway.session.ActiveFrameConsumer
import com.skycommand.relay.gateway.session.AttachResult
import com.skycommand.relay.gateway.session.CommandSessionCleanup
import com.skycommand.relay.gateway.session.ConfigurationRejected
import com.skycommand.relay.gateway.session.ConnectionSession
import com.skycommand.relay.gateway.session.HandshakeSendResult
import com.skycommand.relay.gateway.session.MissionSessionCleanup
import com.skycommand.relay.gateway.session.MonotonicScheduler
import com.skycommand.relay.gateway.session.OrderedStateNotifier
import com.skycommand.relay.gateway.session.ScheduledCancellation
import com.skycommand.relay.gateway.session.SessionConfig
import com.skycommand.relay.gateway.session.SessionCreated
import com.skycommand.relay.gateway.session.SessionDependencies
import com.skycommand.relay.gateway.session.SessionOutbound
import com.skycommand.relay.gateway.session.TransportConnection
import com.skycommand.relay.gateway.session.TransportConnector
import com.skycommand.relay.gateway.session.TransportListener
import com.skycommand.relay.gateway.session.TransportOpenResult
import com.skycommand.relay.gateway.session.TransportWriteResult
import com.skycommand.relay.protocol.HelloFrame
import com.skycommand.relay.protocol.RelayFrame
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OkHttpTransportConnectorIntegrationTest {

    @Test
    fun connectsToARealWebSocketAndForwardsOnlyBinaryFramesUntilItsSingleCloseTerminalEvent() {
        val server = MockWebServer()
        val serverSocket = AtomicReference<WebSocket>()
        val serverOpened = CountDownLatch(1)
        val serverReceived = CountDownLatch(1)
        val receivedByServer = AtomicReference<ByteArray>()
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                        serverSocket.set(webSocket)
                        serverOpened.countDown()
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        receivedByServer.set(bytes.toByteArray())
                        serverReceived.countDown()
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }
                },
            ),
        )
        server.start()

        val client = OkHttpClient.Builder().build()
        try {
            val listener = RecordingListener()
            val endpoint = server.url("/relay").toString().replaceFirst("http", "ws")
            val connection = assertIs<TransportOpenResult.OpenAccepted>(
                OkHttpTransportConnector(client).open(endpoint, generationForTest(), listener),
            ).connection
            connection.enableCallbacks()

            assertTrue(serverOpened.await(5, TimeUnit.SECONDS), "server did not accept the WebSocket")
            assertTrue(listener.opened.await(5, TimeUnit.SECONDS), "adapter did not report onOpened")

            serverSocket.get().send("ignored text")
            serverSocket.get().send(ByteString.of(*byteArrayOf(7, 8, 9)))
            assertTrue(listener.binaryReceived.await(5, TimeUnit.SECONDS), "adapter did not deliver binary payload")
            assertEquals(listOf(listOf(7.toByte(), 8, 9)), listener.bytes)

            assertEquals(TransportWriteResult.WriteAccepted, connection.writer.write(byteArrayOf(1, 2, 3)))
            assertTrue(serverReceived.await(5, TimeUnit.SECONDS), "server did not receive adapter binary write")
            assertEquals(listOf(1.toByte(), 2, 3), receivedByServer.get().toList())

            connection.close("caller requested close")
            assertTrue(listener.terminal.await(5, TimeUnit.SECONDS), "adapter did not report terminal close")
            assertEquals(1, listener.terminalCount.get())
            assertEquals(1, listener.closedCount.get())
        } finally {
            server.shutdown()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private class RecordingListener : TransportListener {
        val opened = CountDownLatch(1)
        val binaryReceived = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val terminalCount = AtomicInteger()
        val closedCount = AtomicInteger()
        val bytes = mutableListOf<List<Byte>>()

        override fun onOpened(connection: TransportConnection) {
            opened.countDown()
        }

        override fun onBytes(generation: SessionGeneration, bytes: ByteArray) {
            synchronized(this.bytes) {
                this.bytes += bytes.toList()
            }
            binaryReceived.countDown()
        }

        override fun onClosed(generation: SessionGeneration, reason: String) {
            closedCount.incrementAndGet()
            terminalCount.incrementAndGet()
            terminal.countDown()
        }

        override fun onFailure(generation: SessionGeneration, reason: String) {
            terminalCount.incrementAndGet()
            terminal.countDown()
        }
    }

    private fun generationForTest(): SessionGeneration {
        var captured: SessionGeneration? = null
        val created = ConnectionSession.create(
            SessionConfig("ws://desktop.example/relay", "generation-source"),
            SessionDependencies(
                connector = TransportConnector { _, generation, _ ->
                    captured = generation
                    TransportOpenResult.OpenRejected("not used")
                },
                outbound = object : SessionOutbound {
                    override fun attach(generation: SessionGeneration, writer: com.skycommand.relay.gateway.session.TransportWriter): AttachResult =
                        AttachResult.AttachRejected

                    override fun sendHandshake(generation: SessionGeneration, frame: HelloFrame): HandshakeSendResult =
                        HandshakeSendResult.SendRejected

                    override fun discard(generation: SessionGeneration) = Unit
                },
                activeFrameConsumer = ActiveFrameConsumer { _, _: RelayFrame -> },
                commandCleanup = CommandSessionCleanup { _, _ -> },
                missionCleanup = MissionSessionCleanup { _, _ -> },
                scheduler = MonotonicScheduler { _, _ -> ScheduledCancellation { } },
                stateNotifier = OrderedStateNotifier { _, _ -> },
            ),
        )
        val session = when (created) {
            is SessionCreated -> created.session
            is ConfigurationRejected -> error(created.detail)
        }
        session.start()
        return requireNotNull(captured)
    }
}
