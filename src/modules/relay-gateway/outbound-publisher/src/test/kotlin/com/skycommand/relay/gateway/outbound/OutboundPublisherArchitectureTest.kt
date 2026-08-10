package com.skycommand.relay.gateway.outbound

import com.skycommand.relay.gateway.session.SessionOutbound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OutboundPublisherArchitectureTest {

    @Test
    fun exposesOnlyTheContractualPublishFailureClasses() {
        assertEquals(
            setOf(
                PublishRejectionKind.STALE_SESSION,
                PublishRejectionKind.DIRECTION_NOT_ALLOWED,
                PublishRejectionKind.ENCODING_REJECTED,
                PublishRejectionKind.WRITE_REJECTED,
            ),
            PublishRejectionKind.entries.toSet(),
        )
    }

    @Test
    fun providesTheSessionOutboundBoundaryWithoutOwningAConnection() {
        assertTrue(SessionOutbound::class.java.isAssignableFrom(OutboundPublisher::class.java))
        assertEquals(
            emptySet(),
            OutboundPublisher::class.java.declaredFields
                .map { it.type.name }
                .filter { it.contains("TransportConnection") || it.contains("WebSocket") }
                .toSet(),
        )
    }
}
