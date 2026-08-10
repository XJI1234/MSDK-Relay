package com.skycommand.relay.settings.endpoint

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EndpointSettingsContractTest {
    @Test fun acceptsWebSocketEndpointFormsWithoutNormalizing() {
        listOf("ws://computer", "wss://computer/relay?token=x", "ws://192.168.1.2:1/relay", "ws://[2001:db8::1]:65535/relay").forEach { value ->
            assertEquals(value, assertIs<EndpointValidationResult.Valid>(EndpointSettings.validate(value)).endpoint.value)
        }
    }

    @Test fun rejectsInvalidEndpointParts() {
        assertReason("", EndpointRejection.EMPTY)
        assertReason("http://computer", EndpointRejection.INVALID_SCHEME)
        assertReason("ws:///relay", EndpointRejection.MISSING_HOST)
        assertReason("ws://computer:0/relay", EndpointRejection.INVALID_PORT)
        assertReason("ws://computer:65536/relay", EndpointRejection.INVALID_PORT)
        assertReason("ws://computer:abc/relay", EndpointRejection.MALFORMED)
        assertReason("ws://user:password@computer/relay", EndpointRejection.USER_INFO_NOT_ALLOWED)
        assertReason("ws://computer/relay#fragment", EndpointRejection.FRAGMENT_NOT_ALLOWED)
        assertReason("ws://computer/%zz", EndpointRejection.MALFORMED)
        assertReason("ws://computer/relay\n", EndpointRejection.CONTROL_CHARACTER)
    }

    @Test fun enforcesLengthAndConcurrentCalls() {
        val maximum = "ws://computer/" + "a".repeat(2048 - "ws://computer/".length)
        assertIs<EndpointValidationResult.Valid>(EndpointSettings.validate(maximum))
        assertReason(maximum + "a", EndpointRejection.TOO_LONG)
        val results = ConcurrentLinkedQueue<EndpointValidationResult>()
        val pool = Executors.newFixedThreadPool(4)
        try {
            repeat(40) { pool.submit { results += EndpointSettings.validate("ws://computer/$it") } }
            pool.shutdown(); check(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally { pool.shutdownNow() }
        assertEquals(40, results.count { it is EndpointValidationResult.Valid })
    }

    private fun assertReason(value: String, reason: EndpointRejection) {
        assertEquals(reason, assertIs<EndpointValidationResult.Invalid>(EndpointSettings.validate(value)).reason)
    }
}
