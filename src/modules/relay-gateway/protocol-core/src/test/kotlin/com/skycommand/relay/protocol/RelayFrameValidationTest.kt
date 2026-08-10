package com.skycommand.relay.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertContentEquals

class RelayFrameValidationTest {

    @Test
    fun acceptsValidHello() {
        assertIs<Accepted<RelayFrame>>(validate(HelloFrame("android-device", "1")))
    }

    @Test
    fun rejectsBlankHelloDeviceId() {
        val result = validate(HelloFrame("", "1"))

        assertEquals(ProtocolErrorCode.INVALID_DEVICE_ID, assertIs<Rejected>(result).error.code)
    }

    @Test
    fun reportsUnsupportedProtocolVersionWithContractError() {
        val result = validate(HelloFrame("android-device", "2"))

        assertEquals("PROTOCOL_VERSION_UNSUPPORTED", assertIs<Rejected>(result).error.code.name)
    }

    @Test
    fun rejectsUnsafeMissionFileName() {
        val result = validate(MissionBeginFrame("id", "../mission.kmz", 1, "0".repeat(64)))

        assertEquals(ProtocolErrorCode.INVALID_FILE_NAME, assertIs<Rejected>(result).error.code)
    }

    @Test
    fun rejectsMissionSizeAboveLimit() {
        val result = validate(MissionBeginFrame("id", "mission.kmz", 104857601, "0".repeat(64)))

        assertEquals(ProtocolErrorCode.MISSION_SIZE_OUT_OF_RANGE, assertIs<Rejected>(result).error.code)
    }

    @Test
    fun reportsMissionSizeOutsideRangeWithContractError() {
        val result = validate(MissionBeginFrame("id", "mission.kmz", 0, "0".repeat(64)))

        assertEquals("MISSION_SIZE_OUT_OF_RANGE", assertIs<Rejected>(result).error.code.name)
    }

    @Test
    fun rejectsMissionChunkAboveLimit() {
        val result = validate(MissionChunkFrame("id", ByteArray(49153)))

        assertEquals(ProtocolErrorCode.CHUNK_TOO_LARGE, assertIs<Rejected>(result).error.code)
    }

    @Test
    fun copiesMissionChunkBytesOnInputAndOutput() {
        val source = byteArrayOf(1, 2, 3)
        val frame = MissionChunkFrame("id", source)
        source[0] = 9
        val returned = frame.bytes
        returned[1] = 8

        assertContentEquals(byteArrayOf(1, 2, 3), frame.bytes)
    }

    @Test
    fun rejectsControlledCommandName() {
        val frame = CommandFrame("id", "bad\ncommand", JsonObject(emptyMap()))

        val result = validate(frame)

        assertEquals(ProtocolErrorCode.INVALID_COMMAND_NAME, assertIs<Rejected>(result).error.code)
    }

    @Test
    fun rejectsReservedNameInsideCommandFields() {
        val frame = CommandFrame(
            id = "id",
            name = "telemetry.read",
            fields = JsonObject(mapOf("name" to JsonString("replacement"))),
        )

        val result = validate(frame)

        assertEquals(ProtocolErrorCode.INVALID_FIELD, assertIs<Rejected>(result).error.code)
    }

    @Test
    fun rejectsProgrammaticJsonBeyondContractDepth() {
        var nested: JsonValue = JsonBoolean(true)
        repeat(40) {
            nested = JsonArray(listOf(nested))
        }
        val frame = TelemetryFrame(
            payload = JsonObject(mapOf("value" to nested)),
            capabilities = JsonObject(emptyMap()),
        )

        val result = validate(frame)

        assertEquals(ProtocolErrorCode.INVALID_JSON, assertIs<Rejected>(result).error.code)
    }

    @Test
    fun rejectsProgrammaticNonJsonNumbers() {
        listOf("", "+1", "01", "NaN", "Infinity", "1".repeat(129)).forEach { number ->
            val frame = TelemetryFrame(
                payload = JsonObject(mapOf("value" to JsonNumber(number))),
                capabilities = JsonObject(emptyMap()),
            )

            val result = validate(frame)

            assertEquals(ProtocolErrorCode.INVALID_JSON, assertIs<Rejected>(result).error.code)
        }
    }

    @Test
    fun rejectsProgrammaticJsonTokenCountBeyondContractLimit() {
        val values = List(8_200) { JsonBoolean(true) }
        val frame = TelemetryFrame(
            payload = JsonObject(mapOf("values" to JsonArray(values))),
            capabilities = JsonObject(emptyMap()),
        )

        val result = validate(frame)

        assertEquals(ProtocolErrorCode.INVALID_JSON, assertIs<Rejected>(result).error.code)
    }
}
