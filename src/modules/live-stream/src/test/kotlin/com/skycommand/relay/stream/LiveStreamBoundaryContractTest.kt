package com.skycommand.relay.stream

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class LiveStreamBoundaryContractTest {
    @Test
    fun `production RTMP facade is the only app-facing live stream interface`() {
        val repositoryRoot = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val source = File(
            repositoryRoot,
            "src/modules/live-stream/src/main/kotlin/com/skycommand/relay/stream/LiveStream.kt",
        ).readText()

        assertTrue(source.contains("interface ProductionRtmpStream"))
        assertTrue(source.contains("class LiveStream private constructor(private val dependencies: LiveStreamDependencies) : ProductionRtmpStream"))
        assertTrue(source.contains("fun commandHandler(): CommandHandler"))
        assertTrue(source.contains("fun markDeviceUnavailable(): StreamSnapshot"))
        assertTrue(source.contains("fun close()"))
        assertTrue(!source.contains("whip"), "The RTMP facade must not depend on the sealed WHIP transport.")

        val graph = File(
            repositoryRoot,
            "src/app/src/main/kotlin/com/skycommand/relay/app/MobileRelayGraph.kt",
        ).readText()
        assertTrue(graph.contains("private val stream: ProductionRtmpStream"))
    }
}
