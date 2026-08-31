package com.skycommand.relay.device.aircraft.android

import com.skycommand.relay.device.aircraft.AircraftListener
import com.skycommand.relay.device.aircraft.AircraftPort
import com.skycommand.relay.device.aircraft.AircraftPortSubscription
import com.skycommand.relay.device.aircraft.AircraftSignal

internal data class DjiAircraftFact(
    val aircraftConnected: Boolean?,
    val flightControllerConnected: Boolean?,
    val displayModel: String?,
)

internal fun interface DjiAircraftListener {
    fun onChanged(fact: DjiAircraftFact)
}

internal fun interface DjiAircraftObservation {
    fun close()
}

internal interface DjiAircraftApi {
    fun observe(listener: DjiAircraftListener): DjiAircraftObservation
}

class AndroidAircraftPort internal constructor(
    private val platform: DjiAircraftApi,
) : AircraftPort {
    private val lock = Any()
    private var generation = 0L
    private var sourceRevision = 0L
    private var active: Active? = null

    override fun start(listener: AircraftListener): AircraftPortSubscription {
        val operation = synchronized(lock) {
            active?.let { return AircraftPortSubscription { } }
            Active(++generation, listener).also { active = it }
        }
        val observation = runCatching { platform.observe(listenerFor(operation)) }
            .getOrElse {
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
        return AircraftPortSubscription { cancel(operation) }
    }

    override fun stop() {
        val observation = synchronized(lock) {
            active?.also { active = null }?.observation
        }
        runCatching { observation?.close() }
    }

    private fun listenerFor(operation: Active) = DjiAircraftListener { fact ->
        val delivery = synchronized(lock) {
            if (active !== operation || generation != operation.generation) {
                null
            } else {
                val aircraftConnected = fact.aircraftConnected
                val flightControllerConnected = normalizedFlightControllerConnection(
                    fact.aircraftConnected,
                    fact.flightControllerConnected,
                )
                AircraftSignal(
                    sourceRevision = ++sourceRevision,
                    aircraftConnected = aircraftConnected,
                    flightControllerConnected = flightControllerConnected,
                    displayModel = fact.displayModel
                        ?.trim()
                        ?.takeIf { aircraftConnected == true && it.isNotEmpty() },
                ) to operation.listener
            }
        }
        delivery?.let { (signal, listener) -> runCatching { listener.onChanged(signal) } }
    }

    private fun cancel(operation: Active) {
        val observation = synchronized(lock) {
            if (active !== operation) null else {
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
        val listener: AircraftListener,
        var observation: DjiAircraftObservation? = null,
    )

    companion object {
        private const val UNAVAILABLE_REASON = "aircraft listener unavailable"

        fun create(): AircraftPort = AndroidAircraftPort(MsdkV5AircraftApi())
    }
}

internal fun normalizedFlightControllerConnection(
    productConnected: Boolean?,
    flightControllerConnected: Boolean?,
): Boolean? = when {
    productConnected == true -> flightControllerConnected
    productConnected == false -> false
    else -> null
}
