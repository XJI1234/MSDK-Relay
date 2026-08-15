package com.skycommand.relay.wayline.command

import com.skycommand.relay.protocol.JsonBoolean
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.CommandFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WaylineCommandHandlerContractTest {
    @Test
    fun requiresConfirmationForUploadAndControlCommands() {
        val fixture = Fixture()
        listOf("wayline.upload", "wayline.start", "wayline.pause", "wayline.resume", "wayline.stop").forEach { name ->
            val result = fixture.handler.handle(CommandFrame("1", name, JsonObject(emptyMap())))
            assertEquals(WaylineCommandRejection.CONFIRMATION_REQUIRED, assertIs<WaylineCommandResult.Rejected>(result).reason)
        }
        assertEquals(0, fixture.actions.calls)
    }

    @Test
    fun rejectsUndeclaredFieldsForConfirmedControlCommands() {
        val fixture = Fixture()
        val confirmedWithExtra = confirm("wayline.upload").copy(
            fields = JsonObject(mapOf("confirm" to JsonBoolean(true), "unexpected" to JsonBoolean(true))),
        )
        assertEquals(
            WaylineCommandRejection.INVALID_FIELDS,
            assertIs<WaylineCommandResult.Rejected>(fixture.handler.handle(confirmedWithExtra)).reason,
        )
        assertEquals(0, fixture.actions.calls)
    }

    @Test
    fun delegatesConfirmedCommandsAndMapsDelegatedRejection() {
        val fixture = Fixture()
        val accepted = fixture.handler.handle(confirm("wayline.upload"))
        assertIs<WaylineCommandResult.Accepted>(accepted)
        assertEquals(1, fixture.actions.calls)
        fixture.actions.rejected = true
        assertEquals(
            WaylineCommandRejection.CAPABILITY_REJECTED,
            assertIs<WaylineCommandResult.Rejected>(fixture.handler.handle(confirm("wayline.start"))).reason,
        )
    }

    @Test
    fun rejectsRemovedGenerationCommand() {
        assertEquals(
            WaylineCommandRejection.UNKNOWN_COMMAND,
            assertIs<WaylineCommandResult.Rejected>(Fixture().handler.handle(CommandFrame("1", "wayline.generate", JsonObject(emptyMap())))).reason,
        )
    }

    private class Fixture {
        val actions = Actions()
        val handler = WaylineCommandHandler.create(actions)
    }

    private class Actions : WaylineCommandActions {
        var calls = 0
        var rejected = false
        override fun upload(completion: WaylineActionCompletion) = invoke(completion)
        override fun start(completion: WaylineActionCompletion) = invoke(completion)
        override fun pause(completion: WaylineActionCompletion) = invoke(completion)
        override fun resume(completion: WaylineActionCompletion) = invoke(completion)
        override fun stop(completion: WaylineActionCompletion) = invoke(completion)
        private fun invoke(completion: WaylineActionCompletion): WaylineActionResult {
            calls += 1
            return if (rejected) WaylineActionResult.Rejected else WaylineActionResult.Accepted
        }
    }

    private fun confirm(name: String) = CommandFrame("1", name, JsonObject(mapOf("confirm" to JsonBoolean(true))))
}
