package com.skycommand.relay.protocol

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.util.Random

class ProtocolFuzzTest {

    @Test
    fun malformedInputsAlwaysReturnAProtocolResult() {
        val inputs = listOf(
            byteArrayOf(),
            byteArrayOf(0xC3.toByte(), 0x28),
            "null".toByteArray(),
            "[]".toByteArray(),
            "{}".toByteArray(),
            "{\"type\":\"hello\"}".toByteArray(),
            "{\"type\":\"hello\",\"deviceId\":\"a\",\"protocolVersion\":\"2\"}".toByteArray(),
            "{\"type\":\"mission-begin\",\"id\":\"id\",\"fileName\":\"../x.kmz\",\"size\":1,\"sha256\":\"${"0".repeat(64)}\"}".toByteArray(),
            "{\"type\":\"mission-chunk\",\"id\":\"id\",\"data\":\"%%%\"}".toByteArray(),
            "{\"type\":\"command\",\"id\":\"id\",\"command\":{\"name\":1}}".toByteArray(),
            "{\"type\":\"hello\",\"deviceId\":\"a\",\"deviceId\":\"b\"}".toByteArray(),
            "{\"type\":\"hello\",\"deviceId\":\"a\"} trailing".toByteArray(),
        )

        inputs.forEach { input ->
            val result = runCatching { RelayFrameCodec.decode(input) }.getOrNull()
            assertNotNull(result)
            assertTrue(result is DecodeResult.Rejected || result is DecodeResult.Ignored || result is DecodeResult.Decoded)
        }
    }

    @Test
    fun seededRandomInputsNeverEscapeTheCodec() {
        val random = Random(0x5A10L)

        repeat(10_000) { index ->
            val input = ByteArray(random.nextInt(2_049))
            random.nextBytes(input)

            val result = runCatching { RelayFrameCodec.decode(input) }.getOrNull()

            assertNotNull(result, "codec threw for randomized input #$index")
            assertTrue(
                result is DecodeResult.Rejected || result is DecodeResult.Ignored || result is DecodeResult.Decoded,
                "unexpected result for randomized input #$index",
            )
        }
    }
}
