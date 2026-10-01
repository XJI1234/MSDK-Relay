package com.skycommand.relay.photo.command

import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonBoolean
import com.skycommand.relay.protocol.JsonArray
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.JsonString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PhotoCommandHandlerContractTest {
    @Test
    fun acceptsEmptyCaptureAndFetchAndRejectsAnythingElseBeforeCallingActions() {
        val actions = Actions()
        val handler = PhotoCommandHandler.create(actions)

        assertIs<PhotoCommandResult.Accepted>(handler.handle(command("camera.photo.capture")))
        assertIs<PhotoCommandResult.Accepted>(handler.handle(command("camera.photo.fetch")))
        assertEquals(listOf(PhotoRequest.Capture, PhotoRequest.Fetch()), actions.requests)

        val structured = PhotoManifestEntry("DJI_0001.jpg", "a".repeat(64))
        val knownPhotos = JsonArray(
            listOf(
                JsonObject(
                    mapOf(
                        "fileName" to JsonString(structured.fileName),
                        "sha256" to JsonString(structured.sha256),
                    ),
                ),
            ),
        )
        assertIs<PhotoCommandResult.Accepted>(
            handler.handle(command("camera.photo.fetch", mapOf("knownPhotos" to knownPhotos))),
        )
        assertEquals(PhotoRequest.Fetch(setOf(structured)), actions.requests.last())

        assertEquals(PhotoCommandRejection.INVALID_FIELDS, rejected(handler.handle(command("camera.photo.capture", mapOf("x" to JsonBoolean(true))))))
        assertEquals(PhotoCommandRejection.UNKNOWN_COMMAND, rejected(handler.handle(command("camera.photo.list"))))
        assertEquals(3, actions.requests.size)
    }

    private fun command(name: String, fields: Map<String, com.skycommand.relay.protocol.JsonValue> = emptyMap()) =
        CommandFrame("id", name, JsonObject(fields))

    private fun rejected(result: PhotoCommandResult) = assertIs<PhotoCommandResult.Rejected>(result).reason

    private class Actions : PhotoCommandActions {
        val requests = mutableListOf<PhotoRequest>()
        override fun execute(request: PhotoRequest, completion: PhotoActionCompletion): PhotoActionResult {
            requests += request
            return PhotoActionResult.Accepted
        }
    }
}
