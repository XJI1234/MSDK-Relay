package com.skycommand.relay.settings.command

import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonBoolean
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.JsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SettingsCommandHandlerContractTest {
    @Test
    fun parsesStrictReadAndWriteRequestsForBothDomains() {
        val actions = Actions()
        val handler = SettingsCommandHandler.create(actions)

        assertIs<SettingsCommandResult.Accepted>(handler.handle(command("device.settings.camera.read", emptyMap())))
        assertIs<SettingsCommandResult.Accepted>(handler.handle(command("device.settings.camera.write", mapOf("focusMode" to JsonString("AUTO")))))
        assertIs<SettingsCommandResult.Accepted>(handler.handle(command("device.settings.transmission.read", emptyMap())))
        assertIs<SettingsCommandResult.Accepted>(handler.handle(command("device.settings.transmission.write", mapOf("bandwidth" to JsonString("BANDWIDTH_20MHZ")))))

        assertEquals(
            listOf(
                SettingsRequest.Read(SettingsDomain.CAMERA),
                SettingsRequest.WriteCamera(CameraSettingsPatch(focusMode = "AUTO")),
                SettingsRequest.Read(SettingsDomain.TRANSMISSION),
                SettingsRequest.WriteTransmission(TransmissionSettingsPatch(bandwidth = "BANDWIDTH_20MHZ")),
            ),
            actions.requests,
        )
    }

    @Test
    fun rejectsInvalidOrReadonlyWriteFieldsBeforeCallingActions() {
        val actions = Actions()
        val handler = SettingsCommandHandler.create(actions)

        assertEquals(SettingsCommandRejection.INVALID_FIELDS, rejected(handler.handle(command("device.settings.camera.read", mapOf("x" to JsonBoolean(true))))))
        assertEquals(SettingsCommandRejection.INVALID_FIELDS, rejected(handler.handle(command("device.settings.camera.write", emptyMap()))))
        assertEquals(SettingsCommandRejection.INVALID_VALUE, rejected(handler.handle(command("device.settings.camera.write", mapOf("focusMode" to JsonString("lowercase"))))))
        assertEquals(SettingsCommandRejection.INVALID_FIELDS, rejected(handler.handle(command("device.settings.transmission.write", mapOf("dynamicDataRateMbps" to JsonString("12"))))))
        assertEquals(0, actions.requests.size)
    }

    private fun command(name: String, fields: Map<String, com.skycommand.relay.protocol.JsonValue>) = CommandFrame("id", name, JsonObject(fields))
    private fun rejected(result: SettingsCommandResult) = assertIs<SettingsCommandResult.Rejected>(result).reason
    private class Actions : SettingsCommandActions {
        val requests = mutableListOf<SettingsRequest>()
        override fun execute(request: SettingsRequest, completion: SettingsActionCompletion): SettingsActionResult { requests += request; return SettingsActionResult.Accepted }
    }
}
