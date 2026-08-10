package com.skycommand.relay.protocol

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProtocolBoundaryContractTest {

    @Test
    fun roundTripsCommandResultAndExplicitVersionPairedFrames() {
        val commandResult = CommandResultFrame("command-1", false, "rejected")
        val paired = PairedFrame("session-1", "1")

        assertEquals(commandResult, roundTrip(commandResult))
        assertEquals(paired, roundTrip(paired))
    }

    @Test
    fun missingResultDetailDecodesAsEmptyForV1Compatibility() {
        val command = decode("""{"type":"command-result","id":"command-1","ok":true}""")
        val mission = decode("""{"type":"mission-result","id":"mission-1","ok":false}""")

        assertEquals(CommandResultFrame("command-1", true, ""), command)
        assertEquals(MissionResultFrame("mission-1", false, ""), mission)
    }

    @Test
    fun roundTripsEveryGenericJsonValueKind() {
        val frame = TelemetryFrame(
            payload = JsonObject(
                mapOf(
                    "null" to JsonNull,
                    "text" to JsonString("ready"),
                    "number" to JsonNumber("42"),
                    "boolean" to JsonBoolean(true),
                    "array" to JsonArray(listOf(JsonString("a"), JsonNumber("2"))),
                    "object" to JsonObject(mapOf("nested" to JsonBoolean(false))),
                )
            ),
            capabilities = JsonObject(emptyMap()),
        )

        assertEquals(frame, roundTrip(frame))
    }

    @Test
    fun knownFramesIgnoreCompatibleExtraTopLevelFields() {
        val frame = decode("""{"type":"hello","deviceId":"device","protocolVersion":"1","future":true}""")

        assertEquals(HelloFrame("device", "1"), frame)
    }

    @Test
    fun acceptsEveryDocumentedMaximumFieldBoundary() {
        val unicodeId = "\uD83D\uDE80".repeat(128)
        val commandName = "a".repeat(64)
        val fileName = "a".repeat(124) + ".KMZ"
        val detail = "d".repeat(1_024)

        assertIs<Accepted<RelayFrame>>(validate(HelloFrame(unicodeId)))
        assertIs<Accepted<RelayFrame>>(validate(CommandFrame("id", commandName, JsonObject(emptyMap()))))
        assertIs<Accepted<RelayFrame>>(
            validate(MissionBeginFrame("id", fileName, 104_857_600, "0".repeat(64)))
        )
        assertIs<Accepted<RelayFrame>>(validate(CommandResultFrame("id", true, detail)))
        assertIs<Accepted<RelayFrame>>(validate(MissionChunkFrame("id", ByteArray(49_152))))
    }

    @Test
    fun appliesIdBoundaryIndependentlyToEveryIdRole() {
        val maximumId = "\uD83D\uDE80".repeat(128)
        val overlongId = "\uD83D\uDE80".repeat(129)

        assertIs<Accepted<RelayFrame>>(validate(HelloFrame(maximumId)))
        assertIs<Accepted<RelayFrame>>(validate(PairedFrame(maximumId, "1")))
        assertIs<Accepted<RelayFrame>>(validate(CommandFrame(maximumId, "read", JsonObject(emptyMap()))))
        assertIs<Accepted<RelayFrame>>(validate(MissionCompleteFrame(maximumId)))

        assertRejected(ProtocolErrorCode.INVALID_DEVICE_ID, validate(HelloFrame(overlongId)))
        assertRejected(ProtocolErrorCode.INVALID_SESSION_ID, validate(PairedFrame(overlongId, "1")))
        assertRejected(
            ProtocolErrorCode.INVALID_MESSAGE_ID,
            validate(CommandFrame(overlongId, "read", JsonObject(emptyMap()))),
        )
        assertRejected(ProtocolErrorCode.INVALID_MESSAGE_ID, validate(MissionCompleteFrame(overlongId)))
    }

    @Test
    fun rejectsBlankAndControlledValuesForEveryIdRole() {
        listOf("", "   ", "bad\nvalue").forEach { invalidId ->
            assertRejected(ProtocolErrorCode.INVALID_DEVICE_ID, validate(HelloFrame(invalidId)))
            assertRejected(ProtocolErrorCode.INVALID_SESSION_ID, validate(PairedFrame(invalidId, "1")))
            assertRejected(
                ProtocolErrorCode.INVALID_MESSAGE_ID,
                validate(CommandFrame(invalidId, "read", JsonObject(emptyMap()))),
            )
            assertRejected(ProtocolErrorCode.INVALID_MESSAGE_ID, validate(MissionCompleteFrame(invalidId)))
        }
    }

    @Test
    fun rejectsEveryJustOverMaximumFieldBoundary() {
        val unicodeId = "\uD83D\uDE80".repeat(129)
        val commandName = "a".repeat(65)
        val fileName = "a".repeat(125) + ".kmz"
        val detail = "d".repeat(1_025)

        assertRejected(ProtocolErrorCode.INVALID_DEVICE_ID, validate(HelloFrame(unicodeId)))
        assertRejected(
            ProtocolErrorCode.INVALID_COMMAND_NAME,
            validate(CommandFrame("id", commandName, JsonObject(emptyMap()))),
        )
        assertRejected(
            ProtocolErrorCode.INVALID_FILE_NAME,
            validate(MissionBeginFrame("id", fileName, 1, "0".repeat(64))),
        )
        assertRejected(
            ProtocolErrorCode.INVALID_RESULT_DETAIL,
            validate(CommandResultFrame("id", true, detail)),
        )
        assertRejected(
            ProtocolErrorCode.CHUNK_TOO_LARGE,
            validate(MissionChunkFrame("id", ByteArray(49_153))),
        )
    }

    @Test
    fun rejectsEveryUnsafeMissionFileNameClass() {
        val invalidNames = listOf(
            "",
            "   ",
            "../route.kmz",
            "folder/route.kmz",
            "folder\\route.kmz",
            "route..backup.kmz",
            "route\u0000.kmz",
            "route.txt",
        )

        invalidNames.forEach { fileName ->
            assertRejected(
                ProtocolErrorCode.INVALID_FILE_NAME,
                validate(MissionBeginFrame("id", fileName, 1, "0".repeat(64))),
            )
        }
    }

    @Test
    fun rejectsInvalidSha256Forms() {
        listOf("0".repeat(63), "0".repeat(65), "A".repeat(64), "g".repeat(64)).forEach { hash ->
            assertRejected(
                ProtocolErrorCode.INVALID_SHA256,
                validate(MissionBeginFrame("id", "route.kmz", 1, hash)),
            )
        }
    }

    @Test
    fun acceptsMissionSizeEndpointsAndRejectsValuesOutsideThem() {
        val hash = "0".repeat(64)

        assertIs<Accepted<RelayFrame>>(validate(MissionBeginFrame("id", "route.kmz", 1, hash)))
        assertIs<Accepted<RelayFrame>>(
            validate(MissionBeginFrame("id", "route.kmz", 104_857_600, hash))
        )
        listOf(-1L, 0L, 104_857_601L).forEach { size ->
            assertRejected(
                ProtocolErrorCode.MISSION_SIZE_OUT_OF_RANGE,
                validate(MissionBeginFrame("id", "route.kmz", size, hash)),
            )
        }
    }

    @Test
    fun rejectsControlCharactersInEveryResultDetail() {
        assertRejected(
            ProtocolErrorCode.INVALID_RESULT_DETAIL,
            validate(CommandResultFrame("command", true, "line\nbreak")),
        )
        assertRejected(
            ProtocolErrorCode.INVALID_RESULT_DETAIL,
            validate(MissionResultFrame("mission", false, "line\u0000break")),
        )
    }

    @Test
    fun enforcesMessageTypeCodePointBoundary() {
        val acceptedType = "t".repeat(64)
        val rejectedType = "t".repeat(65)

        assertEquals(acceptedType, assertIs<DecodeResult.Ignored>(decodeResult("""{"type":"$acceptedType"}""")).type)
        assertEquals(
            ProtocolErrorCode.INVALID_MESSAGE_TYPE,
            assertIs<DecodeResult.Rejected>(decodeResult("""{"type":"$rejectedType"}""")).error.code,
        )
    }

    @Test
    fun enforcesGenericStringCodePointBoundary() {
        val acceptedValue = "a".repeat(65_536)
        val rejectedValue = "a".repeat(65_537)

        assertIs<DecodeResult.Decoded>(
            decodeResult("""{"type":"telemetry","payload":{"value":"$acceptedValue"},"capabilities":{}}""")
        )
        assertEquals(
            ProtocolErrorCode.INVALID_JSON,
            assertIs<DecodeResult.Rejected>(
                decodeResult("""{"type":"telemetry","payload":{"value":"$rejectedValue"},"capabilities":{}}""")
            ).error.code,
        )
    }

    @Test
    fun acceptsFrameAtExactByteLimitAndRejectsOneByteMore() {
        val atLimit = unknownFrameWithSize(98_304)
        val overLimit = unknownFrameWithSize(98_305)

        assertEquals(98_304, atLimit.size)
        assertIs<DecodeResult.Ignored>(RelayFrameCodec.decode(atLimit))
        assertEquals(
            ProtocolErrorCode.FRAME_TOO_LARGE,
            assertIs<DecodeResult.Rejected>(RelayFrameCodec.decode(overLimit)).error.code,
        )
    }

    @Test
    fun acceptsJsonAtDepth32AndRejectsDepth33() {
        assertIs<DecodeResult.Decoded>(decodeResult(telemetryWithNestedArray(arrayDepth = 30)))
        assertEquals(
            ProtocolErrorCode.INVALID_JSON,
            assertIs<DecodeResult.Rejected>(decodeResult(telemetryWithNestedArray(arrayDepth = 31))).error.code,
        )
    }

    @Test
    fun accepts8192JsonTokensAndRejects8193() {
        assertIs<DecodeResult.Ignored>(decodeResult(unknownFrameWithArrayValues(8_185)))
        assertEquals(
            ProtocolErrorCode.INVALID_JSON,
            assertIs<DecodeResult.Rejected>(decodeResult(unknownFrameWithArrayValues(8_186))).error.code,
        )
    }

    @Test
    fun acceptsCanonicalMaximumMissionChunk() {
        val bytes = ByteArray(49_152) { index -> (index % 251).toByte() }
        val data = Base64.getEncoder().encodeToString(bytes)
        val json = """{"type":"mission-chunk","id":"id","data":"$data"}"""

        val frame = assertIs<MissionChunkFrame>(decode(json))

        assertEquals(65_536, data.length)
        assertTrue(bytes.contentEquals(frame.bytes))
    }

    private fun roundTrip(frame: RelayFrame): RelayFrame {
        val encoded = assertIs<Accepted<ByteArray>>(RelayFrameCodec.encode(frame)).value
        return assertIs<DecodeResult.Decoded>(RelayFrameCodec.decode(encoded)).frame
    }

    private fun decode(json: String): RelayFrame =
        assertIs<DecodeResult.Decoded>(decodeResult(json)).frame

    private fun decodeResult(json: String): DecodeResult = RelayFrameCodec.decode(json.encodeToByteArray())

    private fun assertRejected(code: ProtocolErrorCode, result: ProtocolResult<*>) {
        assertEquals(code, assertIs<Rejected>(result).error.code)
    }

    private fun telemetryWithNestedArray(arrayDepth: Int): String {
        val nested = "[".repeat(arrayDepth) + "true" + "]".repeat(arrayDepth)
        return """{"type":"telemetry","payload":{"value":$nested},"capabilities":{}}"""
    }

    private fun unknownFrameWithArrayValues(valueCount: Int): String {
        val values = List(valueCount) { "0" }.joinToString(",")
        return """{"type":"future-event","values":[$values]}"""
    }

    private fun unknownFrameWithSize(size: Int): ByteArray {
        val empty = """{"type":"future-event","a":"","b":""}"""
        val payloadLength = size - empty.encodeToByteArray().size
        val firstLength = minOf(ProtocolLimits.maxJsonStringCodePoints, payloadLength)
        val secondLength = payloadLength - firstLength
        val json = """{"type":"future-event","a":"${"a".repeat(firstLength)}","b":"${"b".repeat(secondLength)}"}"""
        return json.encodeToByteArray()
    }
}
