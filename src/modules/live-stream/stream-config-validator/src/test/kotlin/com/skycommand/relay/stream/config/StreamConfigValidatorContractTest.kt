package com.skycommand.relay.stream.config

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StreamConfigValidatorContractTest {
    @Test
    fun acceptsCommonRtmpDestinationsWithoutChangingTheOriginalValue() {
        listOf(
            "rtmp://computer.example/live/device-1",
            "rtmp://computer:1/live/device-1",
            "rtmp://computer:65535/live/device-1",
            "rtmp://192.168.1.20:1935/live/device-1?token=abc",
            "rtmp://[2001:db8::1]/live/device-1",
        ).forEach { value ->
            val result = StreamConfigValidator.validate(value)
            assertEquals(value, assertIs<StreamValidationResult.Valid>(result).config.rtmpUrl)
        }
    }

    @Test
    fun rejectsEmptyWrongSchemeAndMissingEndpointParts() {
        assertReason("", StreamConfigRejection.EMPTY)
        assertReason("   ", StreamConfigRejection.EMPTY)
        assertReason("rtmps://computer/live/device", StreamConfigRejection.INVALID_SCHEME)
        assertReason("http://computer/live/device", StreamConfigRejection.INVALID_SCHEME)
        assertReason("rtmp:///live/device", StreamConfigRejection.MISSING_HOST)
        assertReason("rtmp://computer", StreamConfigRejection.MISSING_PATH)
    }

    @Test
    fun rejectsUnsafeAuthorityPortAndSyntax() {
        assertReason("rtmp://user:password@computer/live/device", StreamConfigRejection.USER_INFO_NOT_ALLOWED)
        assertReason("rtmp://computer:0/live/device", StreamConfigRejection.INVALID_PORT)
        assertReason("rtmp://computer:65536/live/device", StreamConfigRejection.INVALID_PORT)
        assertReason("rtmp://computer:abc/live/device", StreamConfigRejection.MALFORMED)
        assertReason("rtmp://computer/live/device#fragment", StreamConfigRejection.FRAGMENT_NOT_ALLOWED)
        assertReason("rtmp://computer/live/%zz", StreamConfigRejection.MALFORMED)
        assertReason("rtmp://127.0.0.1/live/device", StreamConfigRejection.LOOPBACK)
        assertReason("rtmp://localhost/live/device", StreamConfigRejection.LOOPBACK)
        assertReason("rtmp://[::1]/live/device", StreamConfigRejection.LOOPBACK)
    }

    @Test
    fun enforcesLengthAndControlCharacterLimits() {
        val maximum = "rtmp://computer/live/" + "a".repeat(2048 - "rtmp://computer/live/".length)
        assertIs<StreamValidationResult.Valid>(StreamConfigValidator.validate(maximum))
        val path = "/live/" + "a".repeat(2048 - "rtmp://computer".length - 5)
        assertReason("rtmp://computer" + path, StreamConfigRejection.TOO_LONG)
        assertReason("rtmp://computer/live/device\n1", StreamConfigRejection.CONTROL_CHARACTER)
    }

    @Test
    fun validationIsStatelessAndThreadSafe() {
        val values = ConcurrentLinkedQueue<StreamValidationResult>()
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(40) {
                pool.submit { values += StreamConfigValidator.validate("rtmp://computer/live/$it") }
            }
            pool.shutdown()
            check(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
        assertEquals(40, values.size)
        assertEquals(40, values.count { it is StreamValidationResult.Valid })
    }

    private fun assertReason(value: String, expected: StreamConfigRejection) {
        assertEquals(expected, assertIs<StreamValidationResult.Invalid>(StreamConfigValidator.validate(value)).reason)
    }
}
