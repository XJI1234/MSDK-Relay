package com.skycommand.relay.gateway.transport

import com.skycommand.relay.gateway.session.SessionGeneration
import com.skycommand.relay.gateway.session.TransportCloseResult
import com.skycommand.relay.gateway.session.TransportConnection
import com.skycommand.relay.gateway.session.TransportConnector
import com.skycommand.relay.gateway.session.TransportListener
import com.skycommand.relay.gateway.session.TransportOpenResult
import com.skycommand.relay.gateway.session.TransportWriter
import com.skycommand.relay.gateway.session.TransportWriteResult
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class OkHttpTransportConnector(
    client: OkHttpClient = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build(),
) : TransportConnector {
    private val delegate = EngineTransportConnector(OkHttpSocketEngine(client))

    override fun open(
        endpoint: String,
        generation: SessionGeneration,
        listener: TransportListener,
    ): TransportOpenResult = delegate.open(endpoint, generation, listener)
}

internal interface SocketEngine {
    fun open(endpoint: String, callbacks: SocketCallbacks): SocketOpenResult
}

internal interface SocketCallbacks {
    fun onOpened()

    fun onBinary(bytes: ByteArray)

    fun onText()

    fun onClosing()

    fun onClosed()

    fun onFailure()
}

internal sealed interface SocketOpenResult {
    data class Accepted(val socket: SocketHandle) : SocketOpenResult

    data object Rejected : SocketOpenResult
}

internal interface SocketHandle {
    fun send(bytes: ByteArray): Boolean

    fun close(): Boolean
}

internal class EngineTransportConnector(
    private val engine: SocketEngine,
) : TransportConnector {
    override fun open(
        endpoint: String,
        generation: SessionGeneration,
        listener: TransportListener,
    ): TransportOpenResult {
        if (!isWebSocketEndpoint(endpoint)) {
            return TransportOpenResult.OpenRejected("Transport endpoint is invalid")
        }

        val connection = AdapterConnection(generation, listener)
        val result = runCatching { engine.open(endpoint, connection) }.getOrNull()
        val socket = (result as? SocketOpenResult.Accepted)?.socket
            ?: return TransportOpenResult.OpenRejected("Transport connection could not be opened")
        connection.attach(socket)
        return TransportOpenResult.OpenAccepted(connection)
    }

    private fun isWebSocketEndpoint(endpoint: String): Boolean = runCatching {
        val uri = URI(endpoint)
        (uri.scheme == "ws" || uri.scheme == "wss") && uri.host != null
    }.getOrDefault(false)
}

private class AdapterConnection(
    override val generation: SessionGeneration,
    private val listener: TransportListener,
) : TransportConnection, TransportWriter, SocketCallbacks {
    private val lock = ReentrantLock()
    private var socket: SocketHandle? = null
    private var opened = false
    private var closeRequested = false
    private var terminalDelivered = false

    override val writer: TransportWriter
        get() = this

    fun attach(socket: SocketHandle) {
        lock.withLock {
            this.socket = socket
        }
    }

    override fun write(bytes: ByteArray): TransportWriteResult = lock.withLock {
        val currentSocket = socket
        if (!opened || closeRequested || terminalDelivered || currentSocket == null) {
            return TransportWriteResult.WriteRejected
        }
        val accepted = runCatching { currentSocket.send(bytes.copyOf()) }.getOrDefault(false)
        if (accepted) TransportWriteResult.WriteAccepted else TransportWriteResult.WriteRejected
    }

    override fun close(reason: String): TransportCloseResult {
        val currentSocket = lock.withLock {
            if (closeRequested || terminalDelivered) {
                return TransportCloseResult.AlreadyClosed
            }
            closeRequested = true
            socket
        }
        runCatching { currentSocket?.close() }
        return TransportCloseResult.CloseRequested
    }

    override fun onOpened() {
        val shouldDeliver = lock.withLock {
            if (opened || closeRequested || terminalDelivered) {
                false
            } else {
                opened = true
                true
            }
        }
        if (shouldDeliver) deliver { listener.onOpened(this) }
    }

    override fun onBinary(bytes: ByteArray) {
        val shouldDeliver = lock.withLock { opened && !closeRequested && !terminalDelivered }
        if (shouldDeliver) {
            val copied = bytes.copyOf()
            deliver { listener.onBytes(generation, copied) }
        }
    }

    override fun onText() = Unit

    override fun onClosing() {
        close("network closing")
    }

    override fun onClosed() {
        if (markTerminal()) deliver { listener.onClosed(generation, "Transport closed") }
    }

    override fun onFailure() {
        if (markTerminal()) deliver { listener.onFailure(generation, "Transport failed") }
    }

    private fun markTerminal(): Boolean = lock.withLock {
        if (terminalDelivered) {
            false
        } else {
            terminalDelivered = true
            true
        }
    }

    private fun deliver(callback: () -> Unit) {
        runCatching(callback)
    }
}

private class OkHttpSocketEngine(
    private val client: OkHttpClient,
) : SocketEngine {
    override fun open(endpoint: String, callbacks: SocketCallbacks): SocketOpenResult = runCatching {
        val request = Request.Builder().url(endpoint).build()
        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    callbacks.onOpened()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    callbacks.onBinary(bytes.toByteArray())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    callbacks.onText()
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    callbacks.onClosing()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    callbacks.onClosed()
                }

                override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: okhttp3.Response?) {
                    callbacks.onFailure()
                }
            },
        )
        SocketOpenResult.Accepted(OkHttpSocketHandle(socket))
    }.getOrDefault(SocketOpenResult.Rejected)
}

private class OkHttpSocketHandle(
    private val socket: WebSocket,
) : SocketHandle {
    override fun send(bytes: ByteArray): Boolean = socket.send(ByteString.of(*bytes))

    override fun close(): Boolean = socket.close(1000, "Relay transport closing")
}
