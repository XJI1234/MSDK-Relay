package com.skycommand.relay.telemetry.flight.android

/** A read-only, generation-scoped probe. No value here is used to control flight. */
internal class FlightDiagnosticProbe(
    private val key: String,
    private val generation: Long,
    private val isCurrent: () -> Boolean,
    private val sink: (String, String) -> Unit,
    private val minimumDelta: Int = 0,
) {
    private val lock = Any()
    private var eventRevision = 0L
    private var previous: String? = null

    fun revision(): Long = synchronized(lock) { eventRevision }

    fun event(value: String) = accept(value, "event", event = true)

    fun initial(value: String, revision: Long) = accept(value, "initial", expectedRevision = revision)

    fun unavailable(revision: Long? = null) = accept("null", "unavailable", expectedRevision = revision)

    private fun accept(value: String, source: String, event: Boolean = false, expectedRevision: Long? = null) {
        val detail = synchronized(lock) {
            if (!isCurrent() || (expectedRevision != null && eventRevision != expectedRevision)) return
            if (event) eventRevision += 1L
            if (value == previous) return
            val oldNumber = previous?.toLongOrNull()
            val nextNumber = value.toLongOrNull()
            if (minimumDelta > 0 && oldNumber != null && nextNumber != null &&
                kotlin.math.abs(nextNumber - oldNumber) < minimumDelta
            ) return
            previous = value
            "flightGeneration=$generation;key=$key;source=$source;value=$value"
        }
        runCatching { sink("FLIGHT_LANDING_DIAGNOSTIC", detail) }
    }
}
