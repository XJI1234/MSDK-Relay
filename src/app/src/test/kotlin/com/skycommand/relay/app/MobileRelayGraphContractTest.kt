package com.skycommand.relay.app

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
    fun missionStartSafetyGateDoesNotTreatPairingAsAFlightReadinessFact() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/app/MobileRelayGraph.kt"),
            Path("src/app/src/main/kotlin/com/skycommand/relay/app/MobileRelayGraph.kt"),
        ).first { it.exists() }.readText()

        assertFalse(source.contains("deviceSnapshot.pairing == PairingState.PAIRED"))
    }

    @Test
    fun telemetryReadRefreshesTheMsdkConnectionKeysBeforeTakingItsSnapshot() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/app/MobileRelayGraph.kt"),
            Path("src/app/src/main/kotlin/com/skycommand/relay/app/MobileRelayGraph.kt"),
        ).first { it.exists() }.readText()
        val telemetryHandler = source.substringAfter("val telemetryHandler = CommandHandler")
            .substringBefore("val pairingHandler")

        assertTrue(telemetryHandler.contains("device.refreshHardwareLinks()"))
    }

    @Test
    fun productConnectionIsNeverLabelledAsAircraftConnection() {
        val strings = listOf(
            Path("src/main/res/values/strings.xml"),
            Path("src/app/src/main/res/values/strings.xml"),
        ).first { it.exists() }.readText()

        assertTrue(strings.contains("DJI 硬件产品连接 [ProductKey.KeyConnection]"))
        assertFalse(strings.contains("飞机连接 [ProductKey.KeyConnection]"))
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

class MainActivityMsdkFactContractTest {
    @Test
    fun displaysEveryObservedMsdkFactOnItsOwnSourceNamedLine() {
        val activity = listOf(
            Path("src/main/kotlin/com/skycommand/relay/app/MainActivity.kt"),
            Path("src/app/src/main/kotlin/com/skycommand/relay/app/MainActivity.kt"),
        ).first { it.exists() }.readText()
        val strings = listOf(
            Path("src/main/res/values/strings.xml"),
            Path("src/app/src/main/res/values/strings.xml"),
        ).first { it.exists() }.readText()

        assertTrue(activity.contains("R.string.status_msdk"))
        assertTrue(activity.contains("R.string.status_flight_controller"))
        assertTrue(strings.contains("MSDK 生命周期 [SDKManager]"))
        assertTrue(strings.contains("遥控器连接 [RemoteControllerKey.KeyConnection]"))
        assertTrue(strings.contains("对频状态 [RemoteControllerKey.KeyPairingStatus]"))
        assertTrue(strings.contains("飞控连接 [FlightControllerKey.KeyConnection]"))
        assertTrue(strings.contains("DJI 硬件产品连接 [ProductKey.KeyConnection]"))
    }
}
