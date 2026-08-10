package com.skycommand.relay.wayline.command

import com.skycommand.relay.protocol.JsonArray
import com.skycommand.relay.protocol.JsonBoolean
import com.skycommand.relay.protocol.JsonNumber
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.JsonString
import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.wayline.staging.MissionMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WaylineCommandHandlerContractTest {
    @Test
    fun generatesAndStagesAValidatedWayline() {
        val fixture = Fixture()
        val result = fixture.handler.handle(generate())
        val detail = assertIs<WaylineCommandResult.Succeeded>(result).detail
        assertEquals("survey.kmz", fixture.staging.current()?.fileName)
        assertEquals(true, detail.contains("survey.kmz"))
        assertEquals(0, fixture.actions.calls)
    }

    @Test
    fun rejectsInvalidGenerationFieldsBeforeGenerationOrStaging() {
        val fixture = Fixture()
        val invalid = generate().copy(fields = JsonObject(mapOf(
            "fileName" to JsonString("../bad.kmz"),
            "speedMetersPerSecond" to JsonNumber("5"),
            "waypoints" to JsonArray(listOf(point(120.0, 30.0, 80.0))),
        )))
        assertEquals(
            WaylineCommandRejection.INVALID_FIELDS,
            assertIs<WaylineCommandResult.Rejected>(fixture.handler.handle(invalid)).reason,
        )
        assertEquals(null, fixture.staging.current())
    }

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
    fun rejectsUnknownCommand() {
        assertEquals(
            WaylineCommandRejection.UNKNOWN_COMMAND,
            assertIs<WaylineCommandResult.Rejected>(Fixture().handler.handle(CommandFrame("1", "wayline.unknown", JsonObject(emptyMap())))).reason,
        )
    }

    private class Fixture {
        val storage = Storage()
        val staging = com.skycommand.relay.wayline.staging.MissionStaging.create(storage)
        val actions = Actions()
        val handler = WaylineCommandHandler.create(staging, actions)
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

    private class Storage : com.skycommand.relay.wayline.staging.StagingStorage {
        var currentBytes = ByteArray(0)
        var currentMetadata: MissionMetadata? = null
        override fun beginTemporary(metadata: MissionMetadata) { currentMetadata = metadata }
        override fun append(bytes: ByteArray) { currentBytes += bytes }
        override fun flush() = Unit
        override fun replaceCurrent() = Unit
        override fun deleteTemporary() { currentBytes = ByteArray(0); currentMetadata = null }
    }

    private fun generate() = CommandFrame("1", "wayline.generate", JsonObject(mapOf(
        "fileName" to JsonString("survey.kmz"),
        "speedMetersPerSecond" to JsonNumber("5"),
        "waypoints" to JsonArray(listOf(point(120.0, 30.0, 80.0), point(120.1, 30.1, 80.0))),
    )))

    private fun confirm(name: String) = CommandFrame("1", name, JsonObject(mapOf("confirm" to JsonBoolean(true))))
    private fun point(longitude: Double, latitude: Double, altitude: Double) = JsonObject(mapOf(
        "longitude" to JsonNumber(longitude.toString()),
        "latitude" to JsonNumber(latitude.toString()),
        "altitude" to JsonNumber(altitude.toString()),
    ))
}
