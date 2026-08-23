package com.skycommand.relay.app

import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class MobileRelayGraphContractTest {
    @Test
    fun handshakeTimeoutMatchesDesktopFifteenSeconds() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/app/MobileRelayGraph.kt"),
            Path("src/app/src/main/kotlin/com/skycommand/relay/app/MobileRelayGraph.kt"),
        ).first { it.exists() }.readText()
        assertTrue(source.contains("handshakeTimeoutMillis = 15_000"))
        assertTrue(source.contains("publishLinkSnapshot"))
        assertTrue(source.contains("SnapshotAssembler.assemble(device.snapshot())"))
    }
}

class MainActivityRetentionContractTest {
    @Test
    fun destroyingTheScreenDoesNotCloseARetainedRelay() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/app/MainActivity.kt"),
            Path("src/app/src/main/kotlin/com/skycommand/relay/app/MainActivity.kt"),
        ).first { it.exists() }.readText()
        assertTrue(source.contains("RelaySurfaceRetention.shouldRetain"))
        assertTrue(source.contains("RelayRuntimeHolder"))
        assertTrue(source.contains("permissionAdapter.rebind"))
        assertTrue(!source.contains("graph?.close()\n        graph = null\n        permissionAdapter.close()"))
    }
}
