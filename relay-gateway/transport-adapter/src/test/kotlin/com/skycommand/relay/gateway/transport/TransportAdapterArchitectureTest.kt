package com.skycommand.relay.gateway.transport

import com.skycommand.relay.gateway.session.TransportConnector
import kotlin.test.Test
import kotlin.test.assertTrue

class TransportAdapterArchitectureTest {

    @Test
    fun exposesOnlyTheSessionTransportSeamToTheRestOfTheGateway() {
        assertTrue(TransportConnector::class.java.isAssignableFrom(OkHttpTransportConnector::class.java))
        val forbidden = OkHttpTransportConnector::class.java.declaredFields
            .map { it.type.simpleName }
            .filter {
                it.contains("protocol", ignoreCase = true) ||
                    it.contains("dji", ignoreCase = true) ||
                    it.contains("mission", ignoreCase = true) ||
                    it.contains("command", ignoreCase = true)
            }
        assertTrue(forbidden.isEmpty(), forbidden.joinToString())
    }
}
