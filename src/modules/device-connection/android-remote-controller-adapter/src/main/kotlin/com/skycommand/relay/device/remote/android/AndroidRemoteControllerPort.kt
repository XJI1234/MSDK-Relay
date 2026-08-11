package com.skycommand.relay.device.remote.android

import com.skycommand.relay.device.remote.PortSubscription
import com.skycommand.relay.device.remote.RemoteControllerListener
import com.skycommand.relay.device.remote.RemoteControllerPort
import com.skycommand.relay.device.remote.RemoteControllerSignal

internal data class DjiRemoteControllerFact(
    val connected: Boolean,
    val displayModel: String?,
)

internal fun interface DjiRemoteControllerListener {
    fun onChanged(fact: DjiRemoteControllerFact)
}

internal fun interface DjiRemoteControllerObservation {
    fun close()
}

internal interface DjiRemoteControllerApi {
    fun observe(listener: DjiRemoteControllerListener): DjiRemoteControllerObservation
}

class AndroidRemoteControllerPort internal constructor(
    private val platform: DjiRemoteControllerApi,
) : RemoteControllerPort {
    private val lock = Any()
    private var generation = 0L
    private var sourceRevision = 0L
    private var active: Active? = null

    override fun start(listener: RemoteControllerListener): PortSubscription {
        val operation = synchronized(lock) {
            active?.let { return PortSubscription { } }
            Active(++generation, listener).also { active = it }
        }

        val observation = runCatching {
            platform.observe(listenerFor(operation))
        }.getOrElse {
            clear(operation)
            throw IllegalStateException(UNAVAILABLE_REASON)
        }
        val mustClose = synchronized(lock) {
            if (active === operation) {
                operation.observation = observation
                false
            } else {
                true
            }
        }
        if (mustClose) runCatching { observation.close() }
        return PortSubscription { cancel(operation) }
    }

    override fun stop() {
        val observation = synchronized(lock) {
            active?.also { active = null }?.observation
        }
        runCatching { observation?.close() }
    }

    private fun listenerFor(operation: Active) = DjiRemoteControllerListener { fact ->
        val delivery = synchronized(lock) {
            if (active !== operation || generation != operation.generation) {
                null
            } else {
                RemoteControllerSignal(
                    sourceRevision = ++sourceRevision,
                    connected = fact.connected,
                    displayModel = fact.displayModel
                        ?.trim()
                        ?.takeIf { fact.connected && it.isNotEmpty() },
                ) to operation.listener
            }
        }
        delivery?.let { (signal, listener) -> runCatching { listener.onChanged(signal) } }
    }

    private fun cancel(operation: Active) {
        val observation = synchronized(lock) {
            if (active !== operation) {
                null
            } else {
                active = null
                operation.observation
            }
        }
        runCatching { observation?.close() }
    }

    private fun clear(operation: Active) {
        synchronized(lock) {
            if (active === operation) active = null
        }
    }

    private data class Active(
        val generation: Long,
        val listener: RemoteControllerListener,
        var observation: DjiRemoteControllerObservation? = null,
    )

    companion object {
        private const val UNAVAILABLE_REASON = "remote controller listener unavailable"

        fun create(): RemoteControllerPort = AndroidRemoteControllerPort(MsdkV5RemoteControllerApi())
    }
}
