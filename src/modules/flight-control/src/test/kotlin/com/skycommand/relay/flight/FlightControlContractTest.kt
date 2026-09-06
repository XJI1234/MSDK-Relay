package com.skycommand.relay.flight

import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellation
import com.skycommand.relay.device.operation.OperationExecutor
import com.skycommand.relay.device.operation.OperationScheduler
import com.skycommand.relay.flight.command.FlightAction
import com.skycommand.relay.flight.dji.FlightDjiFailure
import com.skycommand.relay.flight.dji.DjiFlightPort
import com.skycommand.relay.flight.dji.FlightDjiCompletion
import com.skycommand.relay.gateway.command.CommandCompletion
import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonBoolean
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.JsonString
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlightControlContractTest {
    @Test
    fun reportsSuccessOnlyAfterDjiTerminalSuccess() {
        val fixture = Fixture()
        val completion = Completion()

        fixture.control.commandHandler().handle(command("flight.takeoff"), completion)
        assertEquals(emptyList(), completion.events)
        fixture.port.succeed()

        assertEquals(listOf("ok:Takeoff command completed"), completion.events)
    }

    @Test
    fun rejectsADuplicateTakeoffWhileTheFirstReceiptIsStillPending() {
        val fixture = Fixture()
        val first = Completion()
        val duplicate = Completion()

        fixture.control.commandHandler().handle(command("takeoff-1", "flight.takeoff"), first)
        fixture.control.commandHandler().handle(command("takeoff-2", "flight.takeoff"), duplicate)

        assertEquals(listOf(FlightAction.TAKEOFF), fixture.port.actions)
        assertEquals(listOf("reject:Flight operation was rejected"), duplicate.events)

        fixture.port.succeed()

        assertEquals(listOf("ok:Takeoff command completed"), first.events)
    }

    @Test
    fun namesTheCompletedRecoveryActionWithoutClaimingAircraftState() {
        val fixture = Fixture()
        val completion = Completion()

        fixture.control.commandHandler().handle(command("flight.stop-auto-landing"), completion)
        fixture.port.succeed()

        assertEquals(listOf("ok:Stop automatic landing command completed"), completion.events)
        assertEquals(listOf(FlightAction.STOP_AUTO_LANDING), fixture.port.actions)
    }

    @Test
    fun rejectsUnconfirmedCommandBeforeCallingDji() {
        val fixture = Fixture()
        val completion = Completion()

        fixture.control.commandHandler().handle(CommandFrame("takeoff", "flight.takeoff", JsonObject(emptyMap())), completion)

        assertEquals(listOf("reject:Flight command requires explicit confirmation"), completion.events)
        assertEquals(emptyList(), fixture.port.actions)
    }

    @Test
    fun deviceUnavailabilityCancelsActiveCommandAndDropsLateSuccess() {
        val fixture = Fixture()
        val completion = Completion()
        fixture.control.commandHandler().handle(command("flight.land"), completion)

        fixture.control.markDeviceUnavailable()
        fixture.port.succeed()

        assertEquals(listOf("reject:Flight command result was not confirmed"), completion.events)
        assertEquals(
            JsonObject(
                mapOf(
                    "domain" to JsonString("flight"),
                    "outcome" to JsonString("RESULT_UNCONFIRMED"),
                ),
            ),
            completion.result,
        )
    }

    @Test
    fun returnsTheNormalizedDjiRejectionAsAStructuredFailureResult() {
        val fixture = Fixture()
        val completion = Completion()

        fixture.control.commandHandler().handle(command("flight.takeoff"), completion)
        fixture.port.fail(FlightDjiFailure.fromDjiError("COMMON_SYSTEM_BUSY", "The aircraft is busy"))

        assertEquals(listOf("reject:Flight action was rejected"), completion.events)
        assertEquals(
            JsonObject(
                mapOf(
                    "domain" to JsonString("flight"),
                    "outcome" to JsonString("ACTION_REJECTED"),
                    "errorCode" to JsonString("COMMON_SYSTEM_BUSY"),
                    "errorDescription" to JsonString("The aircraft is busy"),
                ),
            ),
            completion.result,
        )
    }

    @Test
    fun preservesDjiRejectionMetadataInTheStructuredCommandResult() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/flight/FlightControl.kt"),
            Path("src/modules/flight-control/src/main/kotlin/com/skycommand/relay/flight/FlightControl.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("errorCode"))
        assertTrue(source.contains("errorDescription"))
        assertTrue(source.contains("ACTION_REJECTED"))
    }

    private fun command(id: String, name: String = id) = CommandFrame(id, name, JsonObject(mapOf("confirm" to JsonBoolean(true))))

    private class Fixture {
        val port = Port()
        val control = FlightControl.create(
            FlightControlDependencies(
                port,
                DjiOperationCoordinator.create(
                    OperationExecutor { it() },
                    OperationScheduler { _, _ -> OperationCancellation { } },
                ),
            ),
        )
    }

    private class Port : DjiFlightPort {
        val actions = mutableListOf<FlightAction>()
        private var completion: FlightDjiCompletion? = null
        override fun execute(action: FlightAction, completion: FlightDjiCompletion) { actions += action; this.completion = completion }
        fun succeed() = checkNotNull(completion).succeed()
        fun fail(failure: FlightDjiFailure) = checkNotNull(completion).fail(failure)
    }

    private class Completion : CommandCompletion {
        val events = mutableListOf<String>()
        var result: JsonObject? = null
        override fun succeed(detail: String) { events += "ok:$detail" }
        override fun reject(detail: String) { events += "reject:$detail" }
        override fun reject(detail: String, result: JsonObject?) {
            events += "reject:$detail"
            this.result = result
        }
    }
}
