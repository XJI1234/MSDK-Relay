package com.skycommand.relay.gateway.command

import com.skycommand.relay.gateway.session.CommandSessionCleanup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandDispatcherArchitectureTest {

    @Test
    fun exposesOnlyTheContractualDispatchRejections() {
        assertEquals(
            setOf(
                DispatchRejectionKind.UNKNOWN_COMMAND,
                DispatchRejectionKind.CAPACITY_EXCEEDED,
            ),
            DispatchRejectionKind.entries.toSet(),
        )
    }

    @Test
    fun providesTheSessionCleanupSeamWithoutOwningAConnection() {
        assertTrue(CommandSessionCleanup::class.java.isAssignableFrom(CommandDispatcher::class.java))
        assertEquals(
            emptySet(),
            CommandDispatcher::class.java.declaredFields
                .map { it.type.name }
                .filter { it.contains("Transport") || it.contains("WebSocket") || it.contains("SessionState") }
                .toSet(),
        )
    }
}
