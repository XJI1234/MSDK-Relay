package com.skycommand.relay.flight.command

import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonBoolean
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.JsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FlightCommandHandlerContractTest {
    @Test
    fun acceptsOnlyExplicitlyConfirmedSupportedActions() {
        val actions = RecordingActions()
        val handler = FlightCommandHandler.create(actions)

        assertIs<FlightCommandResult.Accepted>(handler.handle(command("flight.takeoff")))
        assertIs<FlightCommandResult.Accepted>(handler.handle(command("flight.land")))
        assertIs<FlightCommandResult.Accepted>(handler.handle(command("flight.confirm-landing")))
        assertIs<FlightCommandResult.Accepted>(handler.handle(command("flight.return-home")))
        assertIs<FlightCommandResult.Accepted>(handler.handle(command("flight.stop-takeoff")))
        assertIs<FlightCommandResult.Accepted>(handler.handle(command("flight.stop-auto-landing")))

        assertEquals(
            listOf(
                FlightAction.TAKEOFF,
                FlightAction.LAND,
                FlightAction.CONFIRM_LANDING,
                FlightAction.RETURN_HOME,
                FlightAction.STOP_TAKEOFF,
                FlightAction.STOP_AUTO_LANDING,
            ),
            actions.actions,
        )
    }

    @Test
    fun rejectsMissingFalseOrAdditionalConfirmationFieldsBeforeCallingActions() {
        val actions = RecordingActions()
        val handler = FlightCommandHandler.create(actions)

        assertEquals(FlightCommandRejection.CONFIRMATION_REQUIRED, rejected(handler.handle(CommandFrame("a", "flight.takeoff", JsonObject(emptyMap())))))
        assertEquals(FlightCommandRejection.CONFIRMATION_REQUIRED, rejected(handler.handle(CommandFrame("b", "flight.takeoff", JsonObject(mapOf("confirm" to JsonBoolean(false)))))))
        assertEquals(FlightCommandRejection.INVALID_FIELDS, rejected(handler.handle(CommandFrame("c", "flight.takeoff", JsonObject(mapOf("confirm" to JsonString("true")))))))
        assertEquals(FlightCommandRejection.INVALID_FIELDS, rejected(handler.handle(CommandFrame("d", "flight.takeoff", JsonObject(mapOf("confirm" to JsonBoolean(true), "extra" to JsonBoolean(true)))))))
        assertEquals(0, actions.actions.size)
    }

    @Test
    fun forwardsOnlyTheFirstTerminalCompletion() {
        val actions = RecordingActions()
        val handler = FlightCommandHandler.create(actions)
        val outcomes = mutableListOf<FlightActionTerminalOutcome>()

        assertIs<FlightCommandResult.Accepted>(handler.handle(command("flight.takeoff"), FlightActionCompletion { outcomes += it }))
        actions.complete(FlightActionTerminalOutcome.SUCCEEDED)
        actions.complete(FlightActionTerminalOutcome.FAILED)

        assertEquals(listOf(FlightActionTerminalOutcome.SUCCEEDED), outcomes)
    }

    private fun command(name: String) = CommandFrame("id-$name", name, JsonObject(mapOf("confirm" to JsonBoolean(true))))
    private fun rejected(result: FlightCommandResult) = assertIs<FlightCommandResult.Rejected>(result).reason

    private class RecordingActions : FlightCommandActions {
        val actions = mutableListOf<FlightAction>()
        private var completion: FlightActionCompletion? = null
        override fun execute(action: FlightAction, completion: FlightActionCompletion): FlightActionResult {
            actions += action
            this.completion = completion
            return FlightActionResult.Accepted
        }
        fun complete(outcome: FlightActionTerminalOutcome) = checkNotNull(completion).complete(outcome)
    }
}
