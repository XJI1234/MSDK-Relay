package com.skycommand.relay.flight

import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellation
import com.skycommand.relay.device.operation.OperationExecutor
import com.skycommand.relay.device.operation.OperationScheduler
import com.skycommand.relay.flight.command.FlightAction
import com.skycommand.relay.flight.dji.DjiFlightPort
import com.skycommand.relay.flight.dji.FlightDjiCompletion
import com.skycommand.relay.gateway.command.CommandCompletion
import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonBoolean
import com.skycommand.relay.protocol.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

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

        assertEquals(listOf("reject:Flight command failed"), completion.events)
    }

    private fun command(name: String) = CommandFrame(name, name, JsonObject(mapOf("confirm" to JsonBoolean(true))))

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
    }

    private class Completion : CommandCompletion {
        val events = mutableListOf<String>()
        override fun succeed(detail: String) { events += "ok:$detail" }
        override fun reject(detail: String) { events += "reject:$detail" }
    }
}
