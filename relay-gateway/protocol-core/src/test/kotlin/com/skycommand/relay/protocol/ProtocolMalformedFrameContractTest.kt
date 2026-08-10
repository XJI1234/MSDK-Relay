package com.skycommand.relay.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProtocolMalformedFrameContractTest {

    @Test
    fun rejectsMissingRequiredFieldsForEveryKnownFrame() {
        val hash = "0".repeat(64)
        val malformed = listOf(
            """{"type":"hello","protocolVersion":"1"}""",
            """{"type":"hello","deviceId":"device"}""",
            """{"type":"paired","protocolVersion":"1"}""",
            """{"type":"telemetry","capabilities":{}}""",
            """{"type":"telemetry","payload":{}}""",
            """{"type":"command","command":{"name":"telemetry.read"}}""",
            """{"type":"command","id":"id"}""",
            """{"type":"command","id":"id","command":{}}""",
            """{"type":"command-result","ok":true}""",
            """{"type":"command-result","id":"id"}""",
            """{"type":"mission-begin","fileName":"route.kmz","size":1,"sha256":"$hash"}""",
            """{"type":"mission-begin","id":"id","size":1,"sha256":"$hash"}""",
            """{"type":"mission-begin","id":"id","fileName":"route.kmz","sha256":"$hash"}""",
            """{"type":"mission-begin","id":"id","fileName":"route.kmz","size":1}""",
            """{"type":"mission-chunk","data":"AQ=="}""",
            """{"type":"mission-chunk","id":"id"}""",
            """{"type":"mission-complete"}""",
            """{"type":"mission-result","ok":true}""",
            """{"type":"mission-result","id":"id"}""",
        )

        malformed.forEach(::assertInvalidField)
    }

    @Test
    fun rejectsWrongRequiredFieldTypesForEveryKnownFrame() {
        val hash = "0".repeat(64)
        val malformed = listOf(
            """{"type":"hello","deviceId":1,"protocolVersion":"1"}""",
            """{"type":"hello","deviceId":"device","protocolVersion":1}""",
            """{"type":"paired","sessionId":1}""",
            """{"type":"paired","sessionId":"session","protocolVersion":1}""",
            """{"type":"telemetry","payload":[],"capabilities":{}}""",
            """{"type":"telemetry","payload":{},"capabilities":false}""",
            """{"type":"command","id":1,"command":{"name":"telemetry.read"}}""",
            """{"type":"command","id":"id","command":[]}""",
            """{"type":"command","id":"id","command":{"name":1}}""",
            """{"type":"command-result","id":1,"ok":true}""",
            """{"type":"command-result","id":"id","ok":"true"}""",
            """{"type":"command-result","id":"id","ok":true,"detail":{}}""",
            """{"type":"mission-begin","id":1,"fileName":"route.kmz","size":1,"sha256":"$hash"}""",
            """{"type":"mission-begin","id":"id","fileName":1,"size":1,"sha256":"$hash"}""",
            """{"type":"mission-begin","id":"id","fileName":"route.kmz","size":1.0,"sha256":"$hash"}""",
            """{"type":"mission-begin","id":"id","fileName":"route.kmz","size":1,"sha256":true}""",
            """{"type":"mission-chunk","id":1,"data":"AQ=="}""",
            """{"type":"mission-chunk","id":"id","data":[]}""",
            """{"type":"mission-complete","id":1}""",
            """{"type":"mission-result","id":1,"ok":true}""",
            """{"type":"mission-result","id":"id","ok":1}""",
            """{"type":"mission-result","id":"id","ok":true,"detail":[]}""",
        )

        malformed.forEach(::assertInvalidField)
    }

    @Test
    fun rejectsMalformedJsonDocumentClasses() {
        val malformed = listOf(
            "",
            "   ",
            "null",
            "[]",
            "true",
            "1",
            "{}",
            "{",
            "{\"type\":\"hello\"} trailing",
            "{\"type\":\"hello\",\"deviceId\":\"a\",\"deviceId\":\"b\",\"protocolVersion\":\"1\"}",
        )

        malformed.forEach { json ->
            val result = RelayFrameCodec.decode(json.encodeToByteArray())

            assertIs<DecodeResult.Rejected>(result, "json=$json")
        }
    }

    @Test
    fun separatesUnsupportedVersionFromWrongVersionType() {
        val unsupported = RelayFrameCodec.decode(
            """{"type":"paired","sessionId":"session","protocolVersion":"2"}""".encodeToByteArray()
        )
        val wrongType = RelayFrameCodec.decode(
            """{"type":"paired","sessionId":"session","protocolVersion":2}""".encodeToByteArray()
        )

        assertEquals(
            ProtocolErrorCode.PROTOCOL_VERSION_UNSUPPORTED,
            assertIs<DecodeResult.Rejected>(unsupported).error.code,
        )
        assertEquals(
            ProtocolErrorCode.INVALID_FIELD,
            assertIs<DecodeResult.Rejected>(wrongType).error.code,
        )
    }

    private fun assertInvalidField(json: String) {
        val result = RelayFrameCodec.decode(json.encodeToByteArray())

        assertEquals(
            ProtocolErrorCode.INVALID_FIELD,
            assertIs<DecodeResult.Rejected>(result, "json=$json").error.code,
        )
    }
}
