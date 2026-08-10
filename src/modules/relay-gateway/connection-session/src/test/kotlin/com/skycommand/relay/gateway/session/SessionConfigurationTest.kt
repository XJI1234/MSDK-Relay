package com.skycommand.relay.gateway.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SessionConfigurationTest {

    @Test
    fun validConfigurationCreatesStoppedSessionWithoutOpeningTransport() {
        val fixture = SessionFixture.create()

        assertEquals(SessionSnapshot(SessionState.STOPPED, null, null), fixture.session.snapshot())
        assertEquals(0, fixture.connector.openCount)
    }

    @Test
    fun acceptsContractTimingBoundaries() {
        assertIs<SessionCreated>(
            SessionFixture.createResult(
                handshakeTimeoutMillis = 1_000,
                reconnectInitialDelayMillis = 250,
                reconnectMaxDelayMillis = 250,
            )
        )
        assertIs<SessionCreated>(
            SessionFixture.createResult(
                handshakeTimeoutMillis = 60_000,
                reconnectInitialDelayMillis = 30_000,
                reconnectMaxDelayMillis = 300_000,
            )
        )
    }

    @Test
    fun rejectsHandshakeTimeoutOutsideContractRange() {
        assertIs<ConfigurationRejected>(SessionFixture.createResult(handshakeTimeoutMillis = 999))
        assertIs<ConfigurationRejected>(SessionFixture.createResult(handshakeTimeoutMillis = 60_001))
    }

    @Test
    fun rejectsReconnectInitialDelayOutsideContractRange() {
        assertIs<ConfigurationRejected>(SessionFixture.createResult(reconnectInitialDelayMillis = 249))
        assertIs<ConfigurationRejected>(SessionFixture.createResult(reconnectInitialDelayMillis = 30_001))
    }

    @Test
    fun rejectsReconnectMaximumBelowInitialOrAboveContractLimit() {
        assertIs<ConfigurationRejected>(
            SessionFixture.createResult(
                reconnectInitialDelayMillis = 1_000,
                reconnectMaxDelayMillis = 999,
            )
        )
        assertIs<ConfigurationRejected>(SessionFixture.createResult(reconnectMaxDelayMillis = 300_001))
    }

    @Test
    fun rejectsInvalidEndpointAndDeviceIdentityWithoutOpeningTransport() {
        val blankEndpoint = SessionFixture.createResult(endpoint = "   ")
        val blankDeviceId = SessionFixture.createResult(deviceId = "")
        val controlCharacterDeviceId = SessionFixture.createResult(deviceId = "phone\u0000id")

        assertIs<ConfigurationRejected>(blankEndpoint)
        assertIs<ConfigurationRejected>(blankDeviceId)
        assertIs<ConfigurationRejected>(controlCharacterDeviceId)
    }
}
