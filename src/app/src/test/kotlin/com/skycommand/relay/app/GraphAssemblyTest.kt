package com.skycommand.relay.app

import kotlin.test.*

class GraphAssemblyTest {
    @Test fun failedAssemblyReleasesOnlyCreatedResourcesInReverseOrder() {
        val events = mutableListOf<String>(); val assembly = GraphAssembly()
        assembly.add { events += "executor" }; assembly.add { events += "adapter" }; assembly.add { events += "timer" }
        assembly.rollback(IllegalStateException("assembly")); assembly.rollback(IllegalStateException("repeat"))
        assertEquals(listOf("timer", "adapter", "executor"), events)
    }
    @Test fun cleanupFailureKeepsOriginalErrorAndDoesNotSkipOtherResources() {
        val error = IllegalStateException("assembly"); val cleanupError = IllegalArgumentException("cleanup")
        val assembly = GraphAssembly(); var released = false
        assembly.add { released = true }; assembly.add { throw cleanupError }; assembly.rollback(error)
        assertTrue(released); assertSame(cleanupError, error.suppressed.single())
    }
    @Test fun successfulAssemblyTransfersOwnershipWithoutCleanup() {
        val assembly = GraphAssembly(); var released = false
        assembly.add { released = true }; assembly.commit(); assembly.rollback(IllegalStateException("late"))
        assertFalse(released)
    }
    @Test fun originalErrorIsNeverAddedAsItsOwnSuppressedError() {
        val error = IllegalStateException("assembly"); val assembly = GraphAssembly()
        assembly.add { throw error }; assembly.rollback(error); assertTrue(error.suppressed.isEmpty())
    }
}
