package com.skycommand.relay.telemetry.flight.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlightDiagnosticProbeTest {
    @Test
    fun initialValueIsRecordedWithoutAnEarlierEvent() {
        val events = mutableListOf<String>()
        val probe = probe(events)
        probe.initial("false", probe.revision())
        assertTrue(events.single().contains("source=initial;value=false"))
    }

    @Test
    fun aNewEventWinsOverASlowInitialReadAndDuplicateValuesAreSilent() {
        val events = mutableListOf<String>()
        val probe = probe(events)
        val revision = probe.revision()
        probe.event("true")
        probe.initial("false", revision)
        probe.unavailable(revision)
        probe.event("true")
        assertEquals(1, events.size)
        assertTrue(events.single().contains("source=event;value=true"))
    }

    @Test
    fun callbacksFromAnInvalidatedGenerationAreIgnored() {
        val events = mutableListOf<String>()
        var current = true
        val probe = FlightDiagnosticProbe("key", 3, { current }, { _, detail -> events += detail })
        val revision = probe.revision()
        current = false
        probe.event("true")
        probe.initial("false", revision)
        probe.unavailable()
        assertTrue(events.isEmpty())
    }

    @Test
    fun unsupportedKeyAndFailingDiagnosticSinkDoNotThrow() {
        val events = mutableListOf<String>()
        probe(events).unavailable()
        assertTrue(events.single().contains("source=unavailable;value=null"))
        FlightDiagnosticProbe("key", 3, { true }, { _, _ -> error("sink failed") }).event("true")
    }

    @Test
    fun stickJitterDoesNotHideALargeMovementOrAllowAStaleInitialRead() {
        val events = mutableListOf<String>()
        val probe = FlightDiagnosticProbe("stick", 3, { true }, { _, detail -> events += detail }, minimumDelta = 100)
        probe.initial("0", probe.revision())
        val revision = probe.revision()
        probe.event("5")
        probe.initial("-660", revision)
        probe.event("-300")
        probe.event("0")
        assertEquals(3, events.size)
        assertTrue(events[1].contains("value=-300"))
        assertTrue(events[2].contains("value=0"))
    }

    private fun probe(events: MutableList<String>) = FlightDiagnosticProbe(
        "key", 3, { true }, { _, detail -> events += detail },
    )
}
