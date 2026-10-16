package com.skycommand.relay.discovery.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AndroidDiscoveryReceiver private constructor(context: Context, sink: DiscoveryReceiverSink) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val pendingPackets = AtomicInteger(0)
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "relay-discovery-control").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
        setContinueExistingPeriodicTasksAfterShutdownPolicy(false)
    }
    private val platform = AndroidReceiverPlatform(context.applicationContext, executor, ::dispatch, ::dispatchPacket)
    private val loop = DiscoveryReceiverLoop(platform, sink)

    fun start() {
        if (!closed.get() && started.compareAndSet(false, true)) dispatch(loop::start)
    }

    private fun dispatch(task: () -> Unit) {
        if (closed.get()) return
        try { executor.execute { if (!closed.get()) task() } }
        catch (_: RejectedExecutionException) { /* close won the race */ }
    }

    private fun dispatchPacket(task: () -> Unit) {
        if (closed.get()) return
        if (pendingPackets.incrementAndGet() > 64) {
            pendingPackets.decrementAndGet()
            return
        }
        try {
            executor.execute {
                try { if (!closed.get()) task() }
                finally { pendingPackets.decrementAndGet() }
            }
        } catch (_: RejectedExecutionException) { pendingPackets.decrementAndGet() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Keep the cleanup task even when close is called from a sink callback.
        executor.execute {
            try { loop.close() } finally { executor.shutdown() }
        }
    }

    companion object {
        fun create(applicationContext: Context, sink: DiscoveryReceiverSink): AndroidDiscoveryReceiver =
            AndroidDiscoveryReceiver(applicationContext, sink)
    }
}

internal class AndroidReceiverPlatform(
    context: Context,
    private val executor: ScheduledThreadPoolExecutor,
    private val dispatch: (() -> Unit) -> Unit,
    private val dispatchPacket: (() -> Unit) -> Unit,
) : ReceiverPlatform {
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

    override fun watch(changed: (Long, WifiLink?) -> Unit): ReceiverCancellation {
        val active = AtomicBoolean(true)
        val available = ConcurrentHashMap.newKeySet<Network>()
        fun publish(network: Network, properties: LinkProperties?) {
            if (!active.get() || network !in available) return
            val link = properties?.let { link(network, it) }
            dispatch { if (active.get()) changed(network.networkHandle, link) }
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                available.add(network)
                // Use the ordered link-properties callback, avoiding a racing
                // synchronous query while Android is still completing the link.
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                publish(network, linkProperties)
            }

            override fun onLost(network: Network) {
                available.remove(network)
                dispatch { if (active.get()) changed(network.networkHandle, null) }
            }
        }
        try {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().clearCapabilities().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                callback,
            )
        } catch (failure: Exception) {
            active.set(false)
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            throw failure
        }
        return ReceiverCancellation {
            if (active.compareAndSet(true, false)) {
                available.clear()
                connectivity.unregisterNetworkCallback(callback)
            }
        }
    }

    private fun link(network: Network, properties: LinkProperties): WifiLink? {
        val name = properties.interfaceName ?: return null
        return properties.linkAddresses.asSequence().filter { it.address is Inet4Address }
            .map { WifiLink(network.networkHandle, name, it.address.hostAddress!!, it.prefixLength) }
            .filter { it.subnet() != null }.sortedBy { it.ipv4 }.firstOrNull()
    }

    override fun open(link: WifiLink, packet: (ByteArray, String) -> Unit, failed: () -> Unit): AutoCloseable =
        WifiUdpReader.open(
            acquireWifiLock = {
                val lock = wifi.createMulticastLock("relay-discovery").apply { setReferenceCounted(false) }
                try { lock.acquire() }
                catch (failure: Exception) {
                    runCatching { if (lock.isHeld) lock.release() }
                    throw failure
                }
                AutoCloseable { if (lock.isHeld) lock.release() }
            },
            bindNetwork = { Network.fromNetworkHandle(link.handle).bindSocket(it) },
            packet = { bytes, source -> dispatchPacket { packet(bytes, source) } },
            failed = { dispatch(failed) },
        )

    override fun later(delayMillis: Long, task: () -> Unit): ReceiverCancellation {
        val future = executor.schedule({ dispatch(task) }, delayMillis, TimeUnit.MILLISECONDS)
        return ReceiverCancellation { future.cancel(false) }
    }
}
