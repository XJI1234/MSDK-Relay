package com.skycommand.relay.protocol

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProtocolBase64ContractTest {

    @Test
    fun acceptsCanonicalOneByteChunk() {
        val result = decodeChunk("AQ==")

        assertContentEquals(byteArrayOf(1), assertIs<DecodeResult.Decoded>(result).frame.let {
            assertIs<MissionChunkFrame>(it).bytes
        })
    }

    @Test
    fun encoderAlwaysProducesCanonicalPaddedBase64() {
        val encoded = assertIs<Accepted<ByteArray>>(
            RelayFrameCodec.encode(MissionChunkFrame("id", byteArrayOf(1)))
        ).value.decodeToString()

        assertEquals(true, encoded.contains("\"data\":\"AQ==\""))
    }

    @Test
    fun rejectsEmptyChunkAfterSuccessfulBase64Decode() {
        val result = decodeChunk("")

        assertEquals(
            ProtocolErrorCode.EMPTY_CHUNK,
            assertIs<DecodeResult.Rejected>(result).error.code,
        )
    }

    @Test
    fun rejectsEveryMalformedBase64Class() {
        val malformed = listOf(
            "A===",
            "=AAA",
            "AA=A",
            "AA==AA==",
            "A A=",
            "AA\\n=",
            "-_==",
            "%%%%",
            "====",
        )

        malformed.forEach { data ->
            val result = decodeChunk(data)

            assertEquals(
                ProtocolErrorCode.INVALID_BASE64,
                assertIs<DecodeResult.Rejected>(result).error.code,
                "data=$data",
            )
        }
    }

    @Test
    fun rejectsDecodedChunkOneByteBeyondLimit() {
        val data = Base64.getEncoder().encodeToString(ByteArray(49_153))

        val result = decodeChunk(data)

        assertEquals(65_540, data.length)
        assertEquals(
            ProtocolErrorCode.CHUNK_TOO_LARGE,
            assertIs<DecodeResult.Rejected>(result).error.code,
        )
    }

    private fun decodeChunk(data: String): DecodeResult = RelayFrameCodec.decode(
        """{"type":"mission-chunk","id":"id","data":"$data"}""".encodeToByteArray()
    )
}
