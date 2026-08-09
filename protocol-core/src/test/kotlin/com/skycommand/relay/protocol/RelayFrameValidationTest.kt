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
    fun rejectsUnsafeMissionFileName() {
        val result = validate(MissionBeginFrame("id", "../mission.kmz", 1, "0".repeat(64)))

        assertEquals(ProtocolErrorCode.INVALID_FILE_NAME, assertIs<Rejected>(result).error.code)
    }

    @Test
    fun rejectsMissionSizeAboveLimit() {
        val result = validate(MissionBeginFrame("id", "mission.kmz", 104857601, "0".repeat(64)))

        assertEquals(ProtocolErrorCode.MISSION_TOO_LARGE, assertIs<Rejected>(result).error.code)
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
}
