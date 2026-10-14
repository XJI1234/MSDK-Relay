package com.skycommand.relay.app

import android.content.Context
import com.skycommand.relay.discovery.DiscoveryNetworkGeneration
import com.skycommand.relay.discovery.Ipv4Subnet
import com.skycommand.relay.discovery.android.AndroidDiscoveryReceiver
import com.skycommand.relay.discovery.android.DiscoveryReceiverSink
import com.skycommand.relay.discovery.android.DiscoveryReceiverState
import com.skycommand.relay.runtime.discovery.DiscoverySource
import com.skycommand.relay.runtime.discovery.DiscoverySourceFactory
import com.skycommand.relay.runtime.discovery.DiscoverySourceSink

internal interface ConnectionDiscoveryReceiver : AutoCloseable {
    fun start()
}

internal fun androidConnectionDiscoveryFactory(context: Context): DiscoverySourceFactory {
    val applicationContext = context.applicationContext
    return DiscoverySourceFactory { sink ->
        AndroidConnectionDiscoverySource(sink) { receiverSink ->
            val receiver = AndroidDiscoveryReceiver.create(applicationContext, receiverSink)
            object : ConnectionDiscoveryReceiver {
                override fun start() = receiver.start()
                override fun close() = receiver.close()
            }
        }
    }
}

/** Receiver creation is deferred until start; close never waits for sink callbacks. */
internal class AndroidConnectionDiscoverySource(
    private val sink: DiscoverySourceSink,
    private val createReceiver: (DiscoveryReceiverSink) -> ConnectionDiscoveryReceiver,
) : DiscoverySource {
    private val lock = Any()
    private var receiver: ConnectionDiscoveryReceiver? = null
    @Volatile private var closed = false
    private var started = false

    private val receiverSink = object : DiscoveryReceiverSink {
        // The controller returns an inert token for a retired source.
        override fun advanceNetwork(): DiscoveryNetworkGeneration = sink.advanceNetwork()
        override fun receive(generation: DiscoveryNetworkGeneration, bytes: ByteArray, sourceIpv4: String, subnet: Ipv4Subnet) {
            if (!closed) sink.receive(generation, bytes, sourceIpv4, subnet)
        }
        override fun tick() { if (!closed) sink.tick() }
        override fun onStateChanged(state: DiscoveryReceiverState) {
            if (!closed && state == DiscoveryReceiverState.FAILED) sink.failed()
        }
    }

    override fun start() = synchronized(lock) {
        if (closed || started) return@synchronized
        started = true
        val created = createReceiver(receiverSink)
        receiver = created
        created.start()
    }

    override fun close() {
        val owned = synchronized(lock) {
            closed = true
            receiver
        }
        owned?.close()
        synchronized(lock) { if (receiver === owned) receiver = null }
    }
}
