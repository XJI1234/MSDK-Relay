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
    fun telemetryReadPublishesTheCurrentSnapshotWithoutRestartingMsdkKeyObservers() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/app/MobileRelayGraph.kt"),
            Path("src/app/src/main/kotlin/com/skycommand/relay/app/MobileRelayGraph.kt"),
        ).first { it.exists() }.readText()
        val telemetryHandler = source.substringAfter("val telemetryHandler = CommandHandler")
            .substringBefore("val pairingHandler")

        assertFalse(telemetryHandler.contains("device.refreshHardwareLinks()"))
        assertTrue(telemetryHandler.contains("TelemetryFrameMapper.commandResult(read.snapshot)"))
    }

    @Test
    fun registersEveryDesktopFlightCommandWithTheFlightControlHandler() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/app/MobileRelayGraph.kt"),
            Path("src/app/src/main/kotlin/com/skycommand/relay/app/MobileRelayGraph.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("\"flight.stop-takeoff\", \"flight.stop-auto-landing\""))
        assertTrue(source.contains("register(gateway, journal, it, flightControl.commandHandler())"))
    }

    @Test
    fun sdkStartupTimeoutIsLoggedAsAMissingTerminalCallbackRatherThanAnSdkFailureCallback() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/app/MobileRelayGraph.kt"),
            Path("src/app/src/main/kotlin/com/skycommand/relay/app/MobileRelayGraph.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("DJI SDK lifecycle did not reach a terminal callback before the startup timeout"))
        assertFalse(source.contains("DJI SDK lifecycle callback reported a failure"))
    }

    @Test
    fun flightControllerConnectionTransitionsReobserveFlightFactsAfterUnknownOrDisconnect() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/app/MobileRelayGraph.kt"),
            Path("src/app/src/main/kotlin/com/skycommand/relay/app/MobileRelayGraph.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("flight.invalidateFlightControllerFacts()"))
        assertTrue(source.contains("flight.refreshFlightControllerFacts()"))
        val synchronization = source.substringAfter("private fun synchronizeFlightTelemetryWithFlightController()")
            .substringBefore("private fun cancelUsbWatch()")
        assertTrue(synchronization.contains("lastFlightControllerLink"))
        assertTrue(synchronization.contains("previous != LinkState.CONNECTED"))
        assertTrue(synchronization.contains("FlightTelemetryLinkAction.REFRESH"))
    }

    @Test
    fun videoSourceTransitionsStopOnlyTheProductionRtmpStream() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/app/MobileRelayGraph.kt"),
            Path("src/app/src/main/kotlin/com/skycommand/relay/app/MobileRelayGraph.kt"),
        ).first { it.exists() }.readText()
        val synchronization = source.substringAfter("private fun synchronizeRtmpStreamWithVideoSource()")
            .substringBefore("private fun synchronizeFlightTelemetryWithFlightController()")

        assertTrue(synchronization.contains("device.capabilities().canStreamVideo"))
        assertTrue(synchronization.contains("stream.markSourceUnavailable()"))
        assertFalse(synchronization.contains("whipStream.markSourceUnavailable()"))
    }

    @Test
    fun productConnectionIsNotShownAsAnAircraftConnectionFact() {
        val strings = listOf(
            Path("src/main/res/values/strings.xml"),
            Path("src/app/src/main/res/values/strings.xml"),
        ).first { it.exists() }.readText()

        assertFalse(strings.contains("DJI 硬件产品连接 [ProductKey.KeyConnection]"))
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
        assertTrue(strings.contains("主电池连接 [BatteryKey.KeyConnection, LEFT_OR_MAIN]"))
        assertFalse(strings.contains("DJI 硬件产品连接 [ProductKey.KeyConnection]"))
    }
}
