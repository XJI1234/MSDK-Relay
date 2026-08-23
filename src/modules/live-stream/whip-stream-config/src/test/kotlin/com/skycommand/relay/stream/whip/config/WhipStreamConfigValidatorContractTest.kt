package com.skycommand.relay.stream.whip.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WhipStreamConfigValidatorContractTest {
    @Test
    fun acceptsIpv4Ipv6HostNamesAndKeepsTheOriginalUrl() {
        listOf(
            "http://192.168.1.20:18889/live/drone-a/whip",
            "https://computer.example/live/drone-a/whip",
            "http://[2001:db8::1]:18889/live/drone-a/whip",
            "http://computer/live/device%201/whip",
            "http://computer:1/live/device/whip",
            "http://computer:65535/live/device/whip",
        ).forEach { url ->
            val valid = assertIs<WhipConfigValidationResult.Valid>(WhipStreamConfigValidator.validate(url))
            assertEquals(url, valid.config.whipUrl)
        }
    }

    @Test
    fun rejectsEmptyWrongSchemeAndMissingWhipPath() {
        assertReason("", WhipConfigRejection.EMPTY)
        assertReason("   ", WhipConfigRejection.EMPTY)
        assertReason("rtmp://computer/live/device/whip", WhipConfigRejection.INVALID_SCHEME)
        assertReason("http:///live/device/whip", WhipConfigRejection.MISSING_HOST)
        assertReason("http://computer/live/device", WhipConfigRejection.MISSING_PATH)
        assertReason("http://computer/whipish", WhipConfigRejection.MISSING_PATH)
    }

    @Test
    fun rejectsCredentialsQueryFragmentInvalidPortAndMalformedUrl() {
        assertReason("http://user:password@computer/live/device/whip", WhipConfigRejection.USER_INFO_NOT_ALLOWED)
        assertReason("http://computer:0/live/device/whip", WhipConfigRejection.INVALID_PORT)
        assertReason("http://computer:65536/live/device/whip", WhipConfigRejection.INVALID_PORT)
        assertReason("http://computer:abc/live/device/whip", WhipConfigRejection.MALFORMED)
        assertReason("http://computer/live/device/whip?token=secret", WhipConfigRejection.QUERY_NOT_ALLOWED)
        assertReason("http://computer/live/device/whip#fragment", WhipConfigRejection.FRAGMENT_NOT_ALLOWED)
        assertReason("http://computer/live/%zz/whip", WhipConfigRejection.MALFORMED)
    }

    @Test
    fun rejectsLoopbackAndLocalhostPublishHosts() {
        assertReason("http://127.0.0.1:18889/live/device/whip", WhipConfigRejection.LOOPBACK)
        assertReason("http://127.1.2.3/live/device/whip", WhipConfigRejection.LOOPBACK)
        assertReason("http://localhost/live/device/whip", WhipConfigRejection.LOOPBACK)
        assertReason("http://LOCALHOST:18889/live/device/whip", WhipConfigRejection.LOOPBACK)
        assertReason("http://[::1]:18889/live/device/whip", WhipConfigRejection.LOOPBACK)
    }

    @Test
    fun enforcesLengthControlCharacterAndStableConcurrentValidation() {
        val maximum = "http://computer/" + "a".repeat(2048 - "http://computer/".length - 5) + "/whip"
        assertIs<WhipConfigValidationResult.Valid>(WhipStreamConfigValidator.validate(maximum))
        assertReason(maximum + "x", WhipConfigRejection.TOO_LONG)
        assertReason("http://computer/live/device\n/whip", WhipConfigRejection.CONTROL_CHARACTER)
        repeat(100) {
            assertIs<WhipConfigValidationResult.Valid>(WhipStreamConfigValidator.validate("http://computer/live/$it/whip"))
        }
    }

    private fun assertReason(url: String, expected: WhipConfigRejection) {
        assertEquals(expected, assertIs<WhipConfigValidationResult.Invalid>(WhipStreamConfigValidator.validate(url)).reason)
    }
}
