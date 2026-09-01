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
            current = FlightTelemetrySnapshot()
            Active(++generation, listener).also { active = it }
        }
        val observationGeneration = nextObservationGeneration(operation)
        if (observationGeneration == null || !startObservation(operation, observationGeneration)) {
            clear(operation)
            throw IllegalStateException(UNAVAILABLE_REASON)
        }
        return FlightTelemetryRegistration { cancel(operation) }
    }

    override fun invalidate() {
        val listener = synchronized(lock) {
            val operation = active
            if (operation != null) operation.observationGeneration += 1L
            current = FlightTelemetrySnapshot()
            operation?.listener
        }
        listener?.let { callback -> runCatching { callback() } }
    }

    override fun refresh() {
        val refresh = synchronized(lock) {
            val operation = active
            if (operation == null) {
                current = FlightTelemetrySnapshot()
                null
            } else {
                current = FlightTelemetrySnapshot()
                operation.observationGeneration += 1L
                Reobservation(operation, operation.observationGeneration)
            }
        } ?: return
        runCatching { refresh.operation.listener() }
        startObservation(refresh.operation, refresh.observationGeneration)
    }

    override fun close() {
        val observation = synchronized(lock) {
            current = FlightTelemetrySnapshot()
            active?.also { active = null }?.observation
        }
        runCatching { observation?.close() }
    }

    private fun listenerFor(operation: Active, observationGeneration: Long) = DjiFlightTelemetryListener { fact ->
        val delivery = synchronized(lock) {
            if (
                active !== operation ||
                generation != operation.generation ||
                operation.observationGeneration != observationGeneration
            ) {
                null
            } else {
                current = fact.toSnapshot()
                operation.listener
            }
        }
        delivery?.let { callback -> runCatching { callback() } }
    }

    private fun nextObservationGeneration(operation: Active): Long? = synchronized(lock) {
        if (active !== operation || generation != operation.generation) {
            null
        } else {
            operation.observationGeneration += 1L
            operation.observationGeneration
        }
    }

    private fun startObservation(operation: Active, observationGeneration: Long): Boolean {
        val observation = runCatching {
            platform.observe(listenerFor(operation, observationGeneration))
        }.getOrNull() ?: return false
        val previous = synchronized(lock) {
            if (
                active !== operation ||
                generation != operation.generation ||
                operation.observationGeneration != observationGeneration
            ) {
                observation
            } else {
                operation.observation.also { operation.observation = observation }
            }
        }
        if (previous === observation) {
            runCatching { observation.close() }
            return false
        }
        runCatching { previous?.close() }
        return true
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
        var observationGeneration: Long = 0L,
    )

    private data class Reobservation(
        val operation: Active,
        val observationGeneration: Long,
    )

    companion object {
        private const val UNAVAILABLE_REASON = "flight telemetry listener unavailable"

        fun create(): FlightTelemetrySource = AndroidFlightTelemetrySource(MsdkV5FlightTelemetryApi())
    }
}
