package com.skycommand.relay.settings

import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationCancellation
import com.skycommand.relay.device.operation.OperationExecutor
import com.skycommand.relay.device.operation.OperationScheduler
import com.skycommand.relay.gateway.command.CommandCompletion
import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.JsonString
import com.skycommand.relay.settings.command.SettingsDjiFailure
import com.skycommand.relay.settings.command.SettingsDomain
import com.skycommand.relay.settings.command.SettingsRequest
import com.skycommand.relay.settings.command.SettingsSnapshot
import com.skycommand.relay.settings.executor.DjiSettingsPort
import com.skycommand.relay.settings.executor.SettingsDjiCompletion
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceSettingsContractTest {
    @Test
    fun exposesTheRealDjiSettingsRejectionAsAStructuredCommandResult() {
        val port = Port()
        val settings = settings(port)
        val completion = Completion()
        val failure = SettingsDjiFailure.fromDjiError("COMMON_SYSTEM_BUSY", "The camera is busy")

        settings.commandHandler().handle(readCamera(), completion)
        port.fail(failure)

        assertEquals(listOf("reject:Settings action was rejected"), completion.events)
        assertEquals(
            JsonObject(
                mapOf(
                    "domain" to JsonString("settings"),
                    "outcome" to JsonString("ACTION_REJECTED"),
                    "errorCode" to JsonString("COMMON_SYSTEM_BUSY"),
                    "errorDescription" to JsonString("The camera is busy"),
                ),
            ),
            completion.result,
        )
    }

    @Test
    fun doesNotPresentASynchronousInvocationFailureAsADjiRejection() {
        val settings = settings(ThrowingPort())
        val completion = Completion()

        settings.commandHandler().handle(readCamera(), completion)

        assertEquals(listOf("reject:Settings operation failed before DJI reported a result"), completion.events)
        assertEquals(
            JsonObject(
                mapOf(
                    "domain" to JsonString("settings"),
                    "outcome" to JsonString("INVOCATION_FAILED"),
                ),
            ),
            completion.result,
        )
    }

    @Test
    fun rejectsAnotherSettingsRequestUntilTheFirstDjiRequestHasATerminalResult() {
        val port = Port()
        val settings = settings(port)
        val duplicate = Completion()

        settings.commandHandler().handle(readCamera(), Completion())
        settings.commandHandler().handle(readTransmission(), duplicate)

        assertEquals(listOf<SettingsRequest>(SettingsRequest.Read(SettingsDomain.CAMERA)), port.requests)
        assertEquals(listOf("reject:Settings operation was rejected"), duplicate.events)

        port.succeed(SettingsSnapshot.Camera(com.skycommand.relay.settings.command.CameraSettings(false, "AUTO", "LEFT_OR_MAIN")))
        settings.commandHandler().handle(readTransmission(), Completion())

        assertEquals(
            listOf<SettingsRequest>(
                SettingsRequest.Read(SettingsDomain.CAMERA),
                SettingsRequest.Read(SettingsDomain.TRANSMISSION),
            ),
            port.requests,
        )
    }

    private fun settings(port: DjiSettingsPort): DeviceSettings = DeviceSettings.create(
        DeviceSettingsDependencies(
            djiPort = port,
            operationCoordinator = DjiOperationCoordinator.create(
                executor = OperationExecutor { it() },
                scheduler = OperationScheduler { _, _ -> OperationCancellation { } },
            ),
        ),
    )

    private fun readCamera() = CommandFrame("settings-1", "device.settings.camera.read", JsonObject(emptyMap()))

    private fun readTransmission() = CommandFrame("settings-2", "device.settings.transmission.read", JsonObject(emptyMap()))

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

    private class Port : DjiSettingsPort {
        val requests = mutableListOf<SettingsRequest>()
        private var completion: SettingsDjiCompletion? = null

        override fun execute(request: SettingsRequest, completion: SettingsDjiCompletion) {
            requests += request
            this.completion = completion
        }

        fun fail(failure: SettingsDjiFailure) = checkNotNull(completion).fail(failure)

        fun succeed(snapshot: SettingsSnapshot) = checkNotNull(completion).succeed(snapshot)
    }

    private class ThrowingPort : DjiSettingsPort {
        override fun execute(request: SettingsRequest, completion: SettingsDjiCompletion) {
            error("platform failure")
        }
    }
}
