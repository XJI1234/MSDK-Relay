package com.skycommand.relay.app

import com.skycommand.relay.diagnostics.DiagnosticClock
import com.skycommand.relay.diagnostics.DiagnosticJournal
import com.skycommand.relay.gateway.command.CommandCompletion
import com.skycommand.relay.gateway.command.CommandHandler
import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.JsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandDiagnosticRecorderTest {
    @Test
    fun recordsRequestAndDjiRejectedTerminalResultWithItsOriginalDetails() {
        var elapsedMillis = 1_000L
        val journal = journal()
        val recorder = CommandDiagnosticRecorder(journal) { elapsedMillis }
        val completion = Completion()

        recorder.wrap(CommandHandler { _, callback ->
            elapsedMillis = 14_975L
            callback.reject(
                "Mission action was rejected",
                result(
                    "wayline",
                    "ACTION_REJECTED",
                    "REQUEST_HANDLER_NOT_FOUND",
                    "DJI did not provide an error description",
                ),
            )
        }).handle(command("wayline.start"), completion)

        val events = journal.pending(32)
        assertEquals(listOf("WAYLINE_START_REQUESTED", "WAYLINE_START_REJECTED"), events.map { it.eventCode })
        assertTrue(events[0].safeDetail.contains("phase=requested"))
        assertTrue(events[1].safeDetail.contains("phase=terminal"))
        assertTrue(events[1].safeDetail.contains("terminal=rejected"))
        assertTrue(events[1].safeDetail.contains("elapsedMs=13975"))
        assertTrue(events[1].safeDetail.contains("resultDomain=wayline"))
        assertTrue(events[1].safeDetail.contains("resultOutcome=ACTION_REJECTED"))
        assertTrue(events[1].safeDetail.contains("djiErrorCode=REQUEST_HANDLER_NOT_FOUND"))
        assertTrue(events[1].safeDetail.contains("djiErrorDescription=DJI did not provide an error description"))
        assertEquals(listOf("reject:Mission action was rejected"), completion.events)
    }

    @Test
    fun recordsUnconfirmedTerminalResultWithoutInventingADjiRejection() {
        val journal = journal()
        val completion = Completion()

        CommandDiagnosticRecorder(journal) { 1_000L }
            .wrap(CommandHandler { _, callback ->
                callback.reject(
                    "Mission operation result was not confirmed",
                    result("wayline", "RESULT_UNCONFIRMED"),
                )
            })
            .handle(command("wayline.stop"), completion)

        val terminal = journal.pending(32).last()
        assertEquals("WAYLINE_STOP_REJECTED", terminal.eventCode)
        assertTrue(terminal.safeDetail.contains("resultOutcome=RESULT_UNCONFIRMED"))
        assertFalse(terminal.safeDetail.contains("djiErrorCode="))
        assertFalse(terminal.safeDetail.contains("djiErrorDescription="))
        assertEquals(listOf("reject:Mission operation result was not confirmed"), completion.events)
    }

    @Test
    fun recordsAndIgnoresADuplicateTerminalCallback() {
        val journal = journal()
        val completion = Completion()

        CommandDiagnosticRecorder(journal) { 1_000L }
            .wrap(CommandHandler { _, callback ->
                callback.succeed("Mission started")
                callback.reject("Late failure", result("wayline", "ACTION_REJECTED", "LATE", "Late callback"))
            })
            .handle(command("wayline.start"), completion)

        val events = journal.pending(32)
        assertEquals(
            listOf("WAYLINE_START_REQUESTED", "WAYLINE_START_OK", "WAYLINE_START_DUPLICATE_RESULT"),
            events.map { it.eventCode },
        )
        assertTrue(events.last().safeDetail.contains("ignored=duplicate-terminal-callback"))
        assertEquals(listOf("ok:Mission started"), completion.events)
    }

    @Test
    fun recordsAHandlerFailureAndKeepsTheExistingSafeRejection() {
        val journal = journal()
        val completion = Completion()

        CommandDiagnosticRecorder(journal) { 1_000L }
            .wrap(CommandHandler { _, _ -> error("internal adapter failure /private/path") })
            .handle(command("flight.land"), completion)

        val events = journal.pending(32)
        assertEquals(listOf("FLIGHT_LAND_REQUESTED", "FLIGHT_LAND_HANDLER_FAILURE"), events.map { it.eventCode })
        assertTrue(events.last().safeDetail.contains("terminal=handler-failure"))
        assertFalse(events.last().safeDetail.contains("internal adapter failure"))
        assertEquals(listOf("reject:Command failed"), completion.events)
    }

    @Test
    fun keepsTheCommandAndItsReceiptWorkingWhenDiagnosticWritingFails() {
        val journal = journal()
        val completion = Completion()
        var handled = false

        CommandDiagnosticRecorder(journal) { 1_000L }
            .wrap(CommandHandler { _, callback ->
                handled = true
                callback.succeed("Command completed")
            })
            .handle(command("invalid command name"), completion)

        assertTrue(handled)
        assertEquals(listOf("ok:Command completed"), completion.events)
        assertTrue(journal.pending(32).isEmpty())
    }

    @Test
    fun redactsStreamEndpointsFromDiagnosticsWithoutChangingTheCommandReceipt() {
        val journal = journal()
        val completion = Completion()
        val detail = "Stream failed at rtmp://relay.private.example"

        CommandDiagnosticRecorder(journal) { 1_000L }
            .wrap(CommandHandler { _, callback ->
                callback.reject(
                    detail,
                    result(
                        "live-stream",
                        "ACTION_REJECTED",
                        "STREAM_FAILURE",
                        "WHIP endpoint is whip://relay.private.example",
                    ),
                )
            })
            .handle(command("live-stream.start"), completion)

        val terminal = journal.pending(32).last().safeDetail
        assertTrue(terminal.contains("djiErrorCode=STREAM_FAILURE"))
        assertFalse(terminal.contains("rtmp://"))
        assertFalse(terminal.contains("whip://"))
        assertFalse(terminal.contains("relay.private.example"))
        assertEquals(listOf("reject:$detail"), completion.events)
    }

    private fun journal(): DiagnosticJournal = DiagnosticJournal.create(
        runId = "run-1",
        capacity = 32,
        clock = DiagnosticClock { 10L },
    )

    private fun command(name: String): CommandFrame = CommandFrame(
        id = "command-1",
        name = name,
        fields = JsonObject(emptyMap()),
    )

    private fun result(
        domain: String,
        outcome: String,
        errorCode: String? = null,
        errorDescription: String? = null,
    ): JsonObject = JsonObject(
        buildMap {
            put("domain", JsonString(domain))
            put("outcome", JsonString(outcome))
            errorCode?.let { put("errorCode", JsonString(it)) }
            errorDescription?.let { put("errorDescription", JsonString(it)) }
        },
    )

    private class Completion : CommandCompletion {
        val events = mutableListOf<String>()

        override fun succeed(detail: String) {
            events += "ok:$detail"
        }

        override fun reject(detail: String) {
            events += "reject:$detail"
        }
    }
}
