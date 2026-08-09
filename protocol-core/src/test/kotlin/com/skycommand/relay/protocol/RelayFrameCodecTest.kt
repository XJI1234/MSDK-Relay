package com.skycommand.relay.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RelayFrameCodecTest {

    @Test
    fun roundTripsHello() {
        val frame = HelloFrame("android-device", "1")

        val encoded = assertIs<Accepted<ByteArray>>(RelayFrameCodec.encode(frame)).value
        val decoded = assertIs<DecodeResult.Decoded>(RelayFrameCodec.decode(encoded)).frame

        assertEquals(frame, decoded)
    }

    @Test
    fun roundTripsPairedWithoutProtocolVersionForCurrentDesktopCompatibility() {
        val frame = PairedFrame("desktop-session", null)

        val encoded = assertIs<Accepted<ByteArray>>(RelayFrameCodec.encode(frame)).value
        val decoded = assertIs<DecodeResult.Decoded>(RelayFrameCodec.decode(encoded)).frame

        assertEquals(frame, decoded)
    }

    @Test
    fun roundTripsTelemetryAndCommandFrames() {
        val telemetry = TelemetryFrame(
            payload = JsonObject(mapOf("connected" to JsonBoolean(true))),
            capabilities = JsonObject(mapOf("liveVideo" to JsonBoolean(true))),
        )
        val command = CommandFrame(
            id = "command-1",
            name = "telemetry.read",
            fields = JsonObject(mapOf("confirm" to JsonBoolean(false))),
        )

        assertEquals(telemetry, decode(encode(telemetry)))
        assertEquals(command, decode(encode(command)))
    }

    @Test
    fun roundTripsMissionFramesAndCopiesBytes() {
        val begin = MissionBeginFrame("mission-1", "route.kmz", 3, "0".repeat(64))
        val chunk = MissionChunkFrame("mission-1", byteArrayOf(1, 2, 3))
        val complete = MissionCompleteFrame("mission-1")
        val result = MissionResultFrame("mission-1", true, "staged")

        assertEquals(begin, decode(encode(begin)))
        assertContentEquals(chunk.bytes, assertIs<MissionChunkFrame>(decode(encode(chunk))).bytes)
        assertEquals(complete, decode(encode(complete)))
        assertEquals(result, decode(encode(result)))
    }

    @Test
    fun rejectsInvalidUtf8() {
        val result = RelayFrameCodec.decode(byteArrayOf(0xC3.toByte(), 0x28))

        assertEquals(ProtocolErrorCode.INVALID_UTF8, assertIs<DecodeResult.Rejected>(result).error.code)
    }

    @Test
    fun rejectsDuplicateFields() {
        val json = "{\"type\":\"hello\",\"deviceId\":\"a\",\"deviceId\":\"b\"}"

        val result = RelayFrameCodec.decode(json.toByteArray())

        assertEquals(ProtocolErrorCode.INVALID_JSON, assertIs<DecodeResult.Rejected>(result).error.code)
    }

    @Test
    fun ignoresUnknownFrameTypes() {
        val json = "{\"type\":\"future-event\",\"value\":true}"

        val result = RelayFrameCodec.decode(json.toByteArray())

        assertEquals("future-event", assertIs<DecodeResult.Ignored>(result).type)
    }

    @Test
    fun acceptsCurrentDesktopPairedFrameWithoutVersion() {
        val json = "{\"type\":\"paired\",\"sessionId\":\"desktop-session\"}"

        val result = RelayFrameCodec.decode(json.toByteArray())

        assertEquals(PairedFrame("desktop-session", null), assertIs<DecodeResult.Decoded>(result).frame)
    }

    @Test
    fun rejectsKnownFrameWithWrongFieldType() {
        val json = "{\"type\":\"command\",\"id\":1,\"command\":{\"name\":\"telemetry.read\"}}"

        val result = RelayFrameCodec.decode(json.toByteArray())

        assertEquals(ProtocolErrorCode.INVALID_FIELD, assertIs<DecodeResult.Rejected>(result).error.code)
    }

    private fun encode(frame: RelayFrame): ByteArray =
        assertIs<Accepted<ByteArray>>(RelayFrameCodec.encode(frame)).value

    private fun decode(bytes: ByteArray): RelayFrame =
        assertIs<DecodeResult.Decoded>(RelayFrameCodec.decode(bytes)).frame
}
