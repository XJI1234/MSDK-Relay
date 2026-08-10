package com.skycommand.relay.stream.command

import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.JsonString
import com.skycommand.relay.stream.config.ValidatedStreamConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StreamCommandHandlerContractTest {
    @Test
    fun validatesStartAndDelegatesTheImmutableConfiguration() {
        val fixture = Fixture()
        val result = fixture.handler.handle(start("rtmp://computer/live/device"))

        assertIs<StreamCommandResult.Accepted>(result)
        assertEquals("rtmp://computer/live/device", fixture.actions.started?.rtmpUrl)
    }

    @Test
    fun doesNotCallActionsForInvalidStartOrStopFields() {
        val fixture = Fixture()
        assertEquals(
            StreamCommandRejection.INVALID_CONFIGURATION,
            assertIs<StreamCommandResult.Rejected>(fixture.handler.handle(start("http://computer/live/device"))).reason,
        )
        assertEquals(
            StreamCommandRejection.INVALID_FIELDS,
            assertIs<StreamCommandResult.Rejected>(fixture.handler.handle(CommandFrame("1", "live-stream.start", JsonObject(emptyMap())))).reason,
        )
        assertEquals(
            StreamCommandRejection.INVALID_FIELDS,
            assertIs<StreamCommandResult.Rejected>(fixture.handler.handle(CommandFrame("2", "live-stream.stop", JsonObject(mapOf("extra" to JsonString("x")))))).reason,
        )
        assertEquals(0, fixture.actions.calls)
    }

    @Test
    fun stopDelegatesWithoutPretendingThatSubmissionIsSuccess() {
        val fixture = Fixture()
        val completion = Completion()
        val result = fixture.handler.handle(stop(), completion)

        assertIs<StreamCommandResult.Accepted>(result)
        assertEquals(emptyList(), completion.events)
        fixture.actions.complete(StreamActionTerminalOutcome.SUCCEEDED)
        assertEquals(listOf("success:Stream operation completed"), completion.events)
    }

    @Test
    fun mapsActionRejectionAndUnknownCommandToSafeResults() {
        val fixture = Fixture()
        fixture.actions.rejected = true
        assertEquals(
            StreamCommandRejection.CAPABILITY_REJECTED,
            assertIs<StreamCommandResult.Rejected>(fixture.handler.handle(stop())).reason,
        )
        assertEquals(
            StreamCommandRejection.UNKNOWN_COMMAND,
            assertIs<StreamCommandResult.Rejected>(fixture.handler.handle(CommandFrame("3", "live-stream.pause", JsonObject(emptyMap())))).reason,
        )
    }

    private class Fixture {
        val actions = Actions()
        val handler = StreamCommandHandler.create(actions)
    }

    private class Actions : StreamCommandActions {
        var calls = 0
        var rejected = false
        var started: ValidatedStreamConfig? = null
        private var completion: StreamActionCompletion? = null
        override fun start(config: ValidatedStreamConfig, completion: StreamActionCompletion): StreamActionResult {
            calls += 1
            started = config
            this.completion = completion
            return if (rejected) StreamActionResult.Rejected else StreamActionResult.Accepted
        }
        override fun stop(completion: StreamActionCompletion): StreamActionResult {
            calls += 1
            this.completion = completion
            return if (rejected) StreamActionResult.Rejected else StreamActionResult.Accepted
        }
        fun complete(outcome: StreamActionTerminalOutcome) { requireNotNull(completion).complete(outcome) }
    }

    private class Completion : StreamActionCompletion {
        val events = mutableListOf<String>()
        override fun complete(outcome: StreamActionTerminalOutcome) {
            events += if (outcome == StreamActionTerminalOutcome.SUCCEEDED) "success:Stream operation completed" else "reject:Stream operation failed"
        }
    }

    private fun start(url: String) = CommandFrame("start", "live-stream.start", JsonObject(mapOf("rtmpUrl" to JsonString(url))))
    private fun stop() = CommandFrame("stop", "live-stream.stop", JsonObject(emptyMap()))
}
