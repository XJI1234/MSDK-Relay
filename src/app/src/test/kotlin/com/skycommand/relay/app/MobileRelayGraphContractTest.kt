package com.skycommand.relay.app

import java.io.File
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
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

    @Test
    fun productionCommandWiringStaysInTheCompositionRoot() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/app/MobileRelayGraph.kt"),
            Path("src/app/src/main/kotlin/com/skycommand/relay/app/MobileRelayGraph.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("private fun registerProductionCommandHandlers("))
        assertTrue(source.contains("\"flight.takeoff\", \"flight.land\", \"flight.return-home\""))
        assertTrue(source.contains("\"wayline.upload\", \"wayline.start\", \"wayline.pause\""))
        assertTrue(source.contains("\"device.settings.camera.read\", \"device.settings.camera.write\""))
    }

    @Test
    fun productionAppDoesNotInstantiateRegisterOrPackageTheArchivedWhipTransport() {
        val graph = listOf(
            Path("src/main/kotlin/com/skycommand/relay/app/MobileRelayGraph.kt"),
            Path("src/app/src/main/kotlin/com/skycommand/relay/app/MobileRelayGraph.kt"),
        ).first { it.exists() }.readText()
        val build = listOf(
            Path("build.gradle.kts"),
            Path("src/app/build.gradle.kts"),
        ).first { it.exists() }.readText()

        assertFalse(graph.contains("WhipLiveStream"))
        assertFalse(graph.contains("AndroidWhipTransport"))
        assertFalse(graph.contains("VideoTransportInterlock"))
        assertFalse(graph.contains("live-stream-webrtc."))
        assertTrue(graph.contains("\"live-stream.start\", \"live-stream.stop\""))
        assertFalse(build.contains(":live-stream:android-whip-publisher-adapter"))
        assertFalse(build.contains(":live-stream:whip-live-stream"))
    }

    @Test
    fun productionContractNamesRtmpInputAndHttpFlvDesktopPlaybackOnly() {
        val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val contract = File(repositoryRoot, "CONTRACT.md").readText()

        assertTrue(contract.contains("手机 DJI -> RTMP -> 电脑 Node Media Server -> 本机 HTTP-FLV -> flv.js"))
        assertFalse(contract.contains("HLS 播放"))
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
