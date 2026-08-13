package com.skycommand.relay.diagnostics.android

import com.skycommand.relay.diagnostics.DiagnosticEvent
import com.skycommand.relay.diagnostics.DiagnosticLevel
import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticEventFileCodecTest {
    @Test
    fun roundTripsEventsAndSkipsDamagedLines() {
        val events = listOf(DiagnosticEvent(1, DiagnosticLevel.ERROR, "device-connection", "SDK_FAILURE", "run-1", 2, "op-1", "safe"))

        val decoded = DiagnosticEventFileCodec.decode(DiagnosticEventFileCodec.encode(events) + "damaged\n")

        assertEquals(events, decoded)
    }
}
