package com.skycommand.relay.e2e.harness

import com.skycommand.relay.e2e.simulation.SimulationDjiPlan
import com.skycommand.relay.wayline.staging.MissionMetadata
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertIs
import com.skycommand.relay.telemetry.command.TelemetryReadResult

class RelayTestHarnessContractTest {
    @Test
    fun `测试宿主拒绝非回环 WebSocket 地址`() {
        assertFailsWith<IllegalArgumentException> {
            RelayHarnessConfig("ws://192.168.1.2:8080", "test-relay")
        }
    }

    @Test
    fun `测试宿主接受回环 WebSocket 地址`() {
        assertEquals("ws://127.0.0.1:9181", RelayHarnessConfig("ws://127.0.0.1:9181", "test-relay").endpoint)
    }

    @Test
    fun `内存航线缓存只在替换完成后公开完整内容`() {
        val storage = InMemoryMissionStorage()
        val first = "first".encodeToByteArray()
        val second = "second".encodeToByteArray()

        storage.beginTemporary(metadata("first.kmz", first))
        storage.append(first)
        storage.flush()
        storage.replaceCurrent()
        storage.beginTemporary(metadata("second.kmz", second))
        storage.append(second)

        assertEquals(first.toList(), storage.read(metadata("first.kmz", first)).toList())
        storage.replaceCurrent()
        assertEquals(second.toList(), storage.read(metadata("second.kmz", second)).toList())
    }

    @Test
    fun `组合根注册全部正式手机命令`() {
        RelayTestHarness.create(config(), SimulationDjiPlan.empty()).use { harness ->
            assertEquals(EXPECTED_COMMANDS, harness.snapshot().registeredCommands)
        }
    }

    @Test
    fun `组合根装配所有生产可达手机业务模块`() {
        RelayTestHarness.create(config(), SimulationDjiPlan.empty()).use { harness ->
            assertEquals(
                setOf(
                    "device-connection", "relay-gateway", "telemetry", "wayline-mission",
                    "flight-control", "device-settings", "live-stream", "relay-settings", "runtime-diagnostics", "app-runtime",
                ),
                harness.snapshot().assembledModules,
            )
        }
    }

    @Test
    fun `关闭操作幂等并清理模拟适配器`() {
        val harness = RelayTestHarness.create(config(), SimulationDjiPlan.empty())

        harness.close()
        harness.close()

        assertTrue(harness.snapshot().closed)
        assertTrue(harness.snapshot().simulation.closed)
    }

    @Test
    fun `组合根启动后可以组装完整遥测快照`() {
        RelayTestHarness.create(config(), SimulationDjiPlan.empty()).use { harness ->
            harness.start()
            assertIs<TelemetryReadResult.ReadSucceeded>(harness.readTelemetry())
        }
    }

    private fun config() = RelayHarnessConfig("ws://127.0.0.1:9181", "test-relay")

    private fun metadata(fileName: String, bytes: ByteArray) = MissionMetadata(
        fileName,
        bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
    )

    private companion object {
        val EXPECTED_COMMANDS = setOf(
            "telemetry.read",
            "pairing.start", "pairing.stop", "pairing.status",
            "live-stream.start", "live-stream.stop",
            "flight.takeoff", "flight.land", "flight.confirm-landing", "flight.return-home", "flight.stop-takeoff", "flight.stop-auto-landing",
            "device.settings.camera.read", "device.settings.camera.write",
            "device.settings.transmission.read", "device.settings.transmission.write",
            "wayline.upload", "wayline.start", "wayline.pause", "wayline.resume", "wayline.stop",
        )
    }
}
