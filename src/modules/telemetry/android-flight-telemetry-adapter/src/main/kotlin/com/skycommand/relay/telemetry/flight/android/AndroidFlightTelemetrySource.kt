package com.skycommand.relay.telemetry.flight.android

import com.skycommand.relay.telemetry.snapshot.FlightTelemetrySnapshot
import com.skycommand.relay.telemetry.snapshot.LowBatteryRthState
import com.skycommand.relay.telemetry.flight.FlightTelemetryRegistration
import com.skycommand.relay.telemetry.flight.FlightTelemetrySource

internal data class FlightTelemetryFact(
    val isFlying: Boolean? = null,
    val motorsOn: Boolean? = null,
    val flightMode: String? = null,
    val batteryPercent: Int? = null,
    val remainingFlightTimeSeconds: Int? = null,
    val altitudeMeters: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val lowBatteryRthState: LowBatteryRthState? = null,
)

internal fun interface DjiFlightTelemetryListener {
    fun onChanged(fact: FlightTelemetryFact)
}

internal fun interface DjiFlightTelemetryObservation {
    fun close()
}

internal interface DjiFlightTelemetryApi {
    fun observe(listener: DjiFlightTelemetryListener): DjiFlightTelemetryObservation
}

class AndroidFlightTelemetrySource internal constructor(
    private val platform: DjiFlightTelemetryApi,
) : FlightTelemetrySource {
    private val lock = Any()
    private var generation = 0L
    private var current = FlightTelemetrySnapshot()
    private var active: Active? = null

    override fun snapshot(): FlightTelemetrySnapshot = synchronized(lock) { current }

    override fun onChanged(listener: () -> Unit): FlightTelemetryRegistration {
        val operation = synchronized(lock) {
            active?.let { return FlightTelemetryRegistration { } }
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
        return FlightTelemetryRegistration { cancel(operation) }
    }

    override fun close() {
        val observation = synchronized(lock) {
            active?.also { active = null }?.observation
        }
        runCatching { observation?.close() }
    }

    private fun listenerFor(operation: Active) = DjiFlightTelemetryListener { fact ->
        val delivery = synchronized(lock) {
            if (active !== operation || generation != operation.generation) {
                null
            } else {
                current = fact.toSnapshot()
                operation.listener
            }
        }
        delivery?.let { callback -> runCatching { callback() } }
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

    private fun FlightTelemetryFact.toSnapshot(): FlightTelemetrySnapshot {
        val validCoordinates = latitude?.isFinite() == true && longitude?.isFinite() == true &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
        val normalizedMode = flightMode
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it.none(Char::isISOControl) && it.codePointCount(0, it.length) <= 128 }
            ?.takeUnless { it.equals("UNKNOWN", ignoreCase = true) || it.equals("UNRECOGNIZED", ignoreCase = true) }
        val rthState = lowBatteryRthState
        return FlightTelemetrySnapshot(
            isFlying = isFlying,
            motorsOn = motorsOn,
            flightMode = normalizedMode,
            batteryPercent = batteryPercent?.takeIf { it in 0..100 },
            remainingFlightTimeSeconds = remainingFlightTimeSeconds?.takeIf { rthState != null && it in 1..86_400 },
            altitudeMeters = altitudeMeters?.takeIf(Double::isFinite),
            latitude = latitude?.takeIf { validCoordinates },
            longitude = longitude?.takeIf { validCoordinates },
            lowBatteryRthState = rthState,
        )
    }

    private data class Active(
        val generation: Long,
        val listener: () -> Unit,
        var observation: DjiFlightTelemetryObservation? = null,
    )

    companion object {
        private const val UNAVAILABLE_REASON = "flight telemetry listener unavailable"

        fun create(): FlightTelemetrySource = AndroidFlightTelemetrySource(MsdkV5FlightTelemetryApi())
    }
}
