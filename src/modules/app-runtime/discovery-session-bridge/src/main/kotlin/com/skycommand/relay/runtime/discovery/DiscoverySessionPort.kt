package com.skycommand.relay.runtime.discovery

import com.skycommand.relay.gateway.RelayGateway
import com.skycommand.relay.gateway.session.ConnectionSelection
import com.skycommand.relay.gateway.session.Registration
import com.skycommand.relay.gateway.session.SessionSnapshot
import com.skycommand.relay.gateway.session.SessionStateListener

/** Controls must not deliver state listeners inline; snapshot must never wait. */
interface DiscoverySessionPort {
    fun start()
    fun stop()
    fun selectConnection(selection: ConnectionSelection?)
    fun snapshot(): SessionSnapshot
    fun onStateChanged(listener: SessionStateListener): Registration
}

class GatewayDiscoverySessionPort(private val gateway: RelayGateway) : DiscoverySessionPort {
    override fun start() { gateway.start() }
    override fun stop() { gateway.stop() }
    override fun selectConnection(selection: ConnectionSelection?) { gateway.selectConnection(selection) }
    override fun snapshot(): SessionSnapshot = gateway.connectionSnapshot()
    override fun onStateChanged(listener: SessionStateListener): Registration = gateway.onStateChanged(listener)
}
