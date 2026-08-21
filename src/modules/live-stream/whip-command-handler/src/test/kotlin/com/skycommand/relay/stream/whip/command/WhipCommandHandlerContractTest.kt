package com.skycommand.relay.stream.whip.command

import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonBoolean
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.JsonString
import com.skycommand.relay.stream.whip.config.ValidatedWhipStreamConfig
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WhipCommandHandlerContractTest {
    @Test
    fun validatesStartAndDelegatesOnlyTheValidatedUrl() {
        val fixture = Fixture()

        val result = fixture.handler.handle(start("http://computer/live/drone-a/whip"))

        assertIs<WhipCommandResult.Accepted>(result)
        assertEquals("http://computer/live/drone-a/whip", fixture.actions.started?.whipUrl)
        assertEquals(1, fixture.actions.calls)
        assertEquals(emptyList(), fixture.completion.events)
    }

    @Test
    fun rejectsEveryInvalidWhipConfigurationBeforeCallingActions() {
        val invalidUrls = listOf(
            "",
            "rtmp://computer/live/drone/whip",
            "http:///live/drone/whip",
            "http://computer/live/drone",
            "http://user:password@computer/live/drone/whip",
            "http://computer/live/drone/whip?token=secret",
            "http://computer/live/drone/whip#fragment",
            "http://computer/live/%zz/whip",
        )

        invalidUrls.forEachIndexed { index, url ->
            val fixture = Fixture()
            val result = fixture.handler.handle(start(url))

            assertEquals(
                WhipCommandRejection.INVALID_CONFIGURATION,
                assertIs<WhipCommandResult.Rejected>(result).reason,
                "invalid URL at index $index",
            )
            assertEquals(0, fixture.actions.calls)
        }
    }

    @Test
    fun requiresExactFieldsAndRecognizesOnlyWhipCommands() {
        val fixture = Fixture()

        assertEquals(
            WhipCommandRejection.INVALID_FIELDS,
            assertIs<WhipCommandResult.Rejected>(fixture.handler.handle(
                CommandFrame("1", "live-stream-webrtc.start", JsonObject(mapOf("whipUrl" to JsonBoolean(true)))),
            )).reason,
        )
        assertEquals(
            WhipCommandRejection.INVALID_FIELDS,
            assertIs<WhipCommandResult.Rejected>(fixture.handler.handle(
                CommandFrame("2", "live-stream-webrtc.start", JsonObject(mapOf(
                    "whipUrl" to JsonString("http://computer/live/drone/whip"),
                    "rtmpUrl" to JsonString("rtmp://computer/live/drone"),
                ))),
            )).reason,
        )
        assertEquals(
            WhipCommandRejection.INVALID_FIELDS,
            assertIs<WhipCommandResult.Rejected>(fixture.handler.handle(
                CommandFrame("3", "live-stream-webrtc.stop", JsonObject(mapOf("extra" to JsonString("x")))),
            )).reason,
        )
        assertEquals(
            WhipCommandRejection.UNKNOWN_COMMAND,
            assertIs<WhipCommandResult.Rejected>(fixture.handler.handle(
                CommandFrame("4", "live-stream-webrtc.pause", JsonObject(emptyMap())),
            )).reason,
        )
        assertEquals(0, fixture.actions.calls)
    }

    @Test
    fun stopReturnsAcceptedUntilTheActionReportsItsTerminalOutcomeOnce() {
        val fixture = Fixture()

        val result = fixture.handler.handle(stop(), fixture.completion)

        assertIs<WhipCommandResult.Accepted>(result)
        assertEquals(emptyList(), fixture.completion.events)
        fixture.actions.complete(WhipActionTerminalOutcome.SUCCEEDED)
        fixture.actions.complete(WhipActionTerminalOutcome.FAILED)
        assertEquals(listOf(WhipActionTerminalOutcome.SUCCEEDED), fixture.completion.events)
    }

    @Test
    fun mapsRejectedAndThrowingActionsWithoutLeakingExceptions() {
        val rejected = Fixture().also { it.actions.result = WhipActionResult.Rejected }
        assertEquals(
            WhipCommandRejection.CAPABILITY_REJECTED,
            assertIs<WhipCommandResult.Rejected>(rejected.handler.handle(stop())).reason,
        )

        val throwing = Fixture().also { it.actions.throwOnCall = true }
        assertEquals(
            WhipCommandRejection.CAPABILITY_REJECTED,
            assertIs<WhipCommandResult.Rejected>(throwing.handler.handle(stop())).reason,
        )
    }

    @Test
    fun concurrentReadsDoNotShareCommandsOrCompletions() {
        val fixtures = (0 until 20).map { Fixture() }
        val results = ConcurrentLinkedQueue<WhipCommandResult>()
        val pool = Executors.newFixedThreadPool(4)
        try {
            fixtures.forEachIndexed { index, fixture ->
                pool.submit { results += fixture.handler.handle(start("http://computer/live/$index/whip")) }
            }
            pool.shutdown()
            check(pool.awaitTermination(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals(20, results.size)
        assertEquals(20, results.count { it is WhipCommandResult.Accepted })
        assertEquals(
            (0 until 20).map { "http://computer/live/$it/whip" }.toSet(),
            fixtures.mapNotNull { it.actions.started?.whipUrl }.toSet(),
        )
    }

    private class Fixture {
        val actions = Actions()
        val completion = Completion()
        val handler = WhipCommandHandler.create(actions)
    }

    private class Actions : WhipCommandActions {
        var calls = 0
        var result: WhipActionResult = WhipActionResult.Accepted
        var throwOnCall = false
        var started: ValidatedWhipStreamConfig? = null
        private var completion: WhipActionCompletion? = null

        override fun start(config: ValidatedWhipStreamConfig, completion: WhipActionCompletion): WhipActionResult {
            check(!throwOnCall) { "platform failure" }
            calls += 1
            started = config
            this.completion = completion
            return result
        }

        override fun stop(completion: WhipActionCompletion): WhipActionResult {
            check(!throwOnCall) { "platform failure" }
            calls += 1
            this.completion = completion
            return result
        }

        fun complete(outcome: WhipActionTerminalOutcome) {
            completion?.complete(outcome)
        }
    }

    private class Completion : WhipActionCompletion {
        val events = mutableListOf<WhipActionTerminalOutcome>()

        override fun complete(outcome: WhipActionTerminalOutcome) {
            events += outcome
        }
    }

    private fun start(url: String) = CommandFrame(
        "start",
        "live-stream-webrtc.start",
        JsonObject(mapOf("whipUrl" to JsonString(url))),
    )

    private fun stop() = CommandFrame("stop", "live-stream-webrtc.stop", JsonObject(emptyMap()))
}
