package com.skycommand.relay.e2e.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.Duration
import com.skycommand.relay.flight.command.FlightAction
import com.skycommand.relay.wayline.executor.ControlCompletion
import com.skycommand.relay.wayline.phase.MissionExecutionSignal
import com.skycommand.relay.wayline.staging.MissionMetadata
import com.skycommand.relay.wayline.uploader.UploadCompletion
import com.skycommand.relay.settings.command.SettingsDomain
import com.skycommand.relay.settings.command.SettingsRequest
import com.skycommand.relay.settings.command.SettingsSnapshot
import com.skycommand.relay.settings.executor.SettingsDjiCompletion
import com.skycommand.relay.stream.config.ValidatedStreamConfig
import com.skycommand.relay.stream.dji.StreamDjiCompletion
import com.skycommand.relay.device.remote.RemoteControllerSignal
import com.skycommand.relay.device.DeviceConnection
import com.skycommand.relay.device.DeviceConnectionDependencies
import com.skycommand.relay.device.operation.OperationCancellation
import com.skycommand.relay.device.operation.OperationExecutor
import com.skycommand.relay.device.operation.OperationScheduler
import com.skycommand.relay.device.state.LinkState
import com.skycommand.relay.device.state.PairingState
import com.skycommand.relay.device.state.SdkAvailability

class SimulationDjiAdapterContractTest {
    @Test
    fun `重复和迟到故障类型按计划交付回调`() {
        val adapter = SimulationDjiAdapter.create(
            SimulationDjiPlan.builder()
                .flight(FlightAction.TAKEOFF, SimulationOperation.Duplicate())
                .flight(FlightAction.LAND, SimulationOperation.Late(Duration.ofSeconds(2)))
                .build(),
            ManualSimulationClock(),
        )
        var takeoffCallbacks = 0
        var landCallbacks = 0
        fun completion(increment: () -> Unit) = object : com.skycommand.relay.flight.dji.FlightDjiCompletion {
            override fun succeed() = increment()
            override fun fail() = error("unexpected failure")
        }

        adapter.ports().flight.execute(FlightAction.TAKEOFF, completion { takeoffCallbacks += 1 })
        adapter.ports().flight.execute(FlightAction.LAND, completion { landCallbacks += 1 })
        adapter.advanceBy(Duration.ZERO)
        assertEquals(2, takeoffCallbacks)
        assertEquals(0, landCallbacks)
        adapter.advanceBy(Duration.ofSeconds(2))
        assertEquals(1, landCallbacks)
    }

    @Test
    fun `在线静止飞机提供可供正式起飞前检查使用的完整安全遥测`() {
        val adapter = SimulationDjiAdapter.create(SimulationDjiPlan.empty(), ManualSimulationClock())

        val telemetry = adapter.ports().telemetry.snapshot()

        assertEquals(false, telemetry.isFlying)
        assertEquals(false, telemetry.motorsOn)
        assertEquals(80, telemetry.batteryPercent)
        assertEquals("GPS_NORMAL", telemetry.flightMode)
    }

    @Test
    fun `新建模拟器公开未关闭且无待交付事件的快照`() {
        val adapter = SimulationDjiAdapter.create(SimulationDjiPlan.empty(), ManualSimulationClock())

        val snapshot = adapter.snapshot()

        assertFalse(snapshot.closed)
        assertTrue(snapshot.pendingEventCount == 0)
    }

    @Test
    fun `时钟只交付到期事件并保持注入顺序`() {
        val adapter = SimulationDjiAdapter.create(SimulationDjiPlan.empty(), ManualSimulationClock())
        adapter.inject(SimulationInjection.Marker("later", Duration.ofSeconds(2)))
        adapter.inject(SimulationInjection.Marker("first", Duration.ofSeconds(1)))
        adapter.inject(SimulationInjection.Marker("second", Duration.ofSeconds(1)))

        adapter.advanceBy(Duration.ofSeconds(1))

        assertEquals(listOf("first", "second"), adapter.snapshot().deliveredMarkers)
        assertEquals(1, adapter.snapshot().pendingEventCount)
    }

    @Test
    fun `关闭后拒绝新注入且不交付尚未到期的事件`() {
        val adapter = SimulationDjiAdapter.create(SimulationDjiPlan.empty(), ManualSimulationClock())
        adapter.inject(SimulationInjection.Marker("late", Duration.ofSeconds(1)))
        adapter.close()

        adapter.advanceBy(Duration.ofSeconds(2))

        assertEquals(SimulationInjectionResult.Ignored, adapter.inject(SimulationInjection.Marker("new", Duration.ZERO)))
        assertTrue(adapter.snapshot().deliveredMarkers.isEmpty())
        assertEquals(0, adapter.snapshot().pendingEventCount)
    }

    @Test
    fun `飞控端口只在计划时间完成调用且不伪造飞行事实`() {
        val plan = SimulationDjiPlan.builder()
            .flight(FlightAction.TAKEOFF, SimulationOperation.Succeed(Duration.ofSeconds(1)))
            .build()
        val adapter = SimulationDjiAdapter.create(plan, ManualSimulationClock())
        var succeeded = 0
        var failed = 0

        adapter.ports().flight.execute(FlightAction.TAKEOFF, object : com.skycommand.relay.flight.dji.FlightDjiCompletion {
            override fun succeed() { succeeded += 1 }
            override fun fail() { failed += 1 }
        })
        adapter.advanceBy(Duration.ofMillis(999))
        assertEquals(0, succeeded)
        adapter.advanceBy(Duration.ofMillis(1))

        assertEquals(1, succeeded)
        assertEquals(0, failed)
        assertEquals(null, adapter.snapshot().flight.isFlying)
    }

    @Test
    fun `航线上传和启动只按计划完成且完成信号必须单独注入`() {
        val plan = SimulationDjiPlan.builder()
            .upload(SimulationOperation.Succeed(Duration.ofSeconds(1)))
            .mission(SimulationMissionCommand.START, SimulationOperation.Succeed(Duration.ZERO))
            .build()
        val adapter = SimulationDjiAdapter.create(plan, ManualSimulationClock())
        var uploaded = 0
        var started = 0
        val receivedSignals = mutableListOf<MissionExecutionSignal>()
        adapter.ports().executionSignals.onSignal { receivedSignals += it }

        adapter.ports().missionUpload.upload(
            MissionMetadata("route.kmz", 3, "abc"),
            byteArrayOf(1, 2, 3),
            progress = {},
            completion = object : UploadCompletion {
                override fun succeed() { uploaded += 1 }
                override fun fail() = error("unexpected upload failure")
            },
        )
        adapter.advanceBy(Duration.ofSeconds(1))
        adapter.ports().missionControl.start(object : ControlCompletion {
            override fun succeed() { started += 1 }
            override fun fail() = error("unexpected start failure")
        })
        adapter.advanceUntilIdle()

        assertEquals(1, uploaded)
        assertEquals(1, started)
        assertTrue(receivedSignals.isEmpty())
        assertEquals("route.kmz", adapter.snapshot().missionFileName)
        adapter.inject(SimulationInjection.MissionSignal(MissionExecutionSignal.COMPLETED, Duration.ZERO))
        adapter.advanceUntilIdle()
        assertEquals(listOf(MissionExecutionSignal.COMPLETED), receivedSignals)
    }

    @Test
    fun `设置读取只通过完整同域快照完成`() {
        val adapter = SimulationDjiAdapter.create(SimulationDjiPlan.empty(), ManualSimulationClock())
        var result: SettingsSnapshot? = null

        adapter.ports().settings.execute(SettingsRequest.Read(SettingsDomain.CAMERA), object : SettingsDjiCompletion {
            override fun succeed(snapshot: SettingsSnapshot) { result = snapshot }
            override fun fail() = error("unexpected settings failure")
        })
        adapter.advanceUntilIdle()

        assertEquals(
            SettingsSnapshot.Camera(
                com.skycommand.relay.settings.command.CameraSettings(false, "AUTO", "DEFAULT"),
            ),
            result,
        )
    }

    @Test
    fun `设置端口按读写类别消费失败计划且不改变设置事实`() {
        val adapter = SimulationDjiAdapter.create(
            SimulationDjiPlan.builder()
                .settings(SimulationSettingsOperation.CAMERA_WRITE, SimulationOperation.Fail())
                .build(),
            ManualSimulationClock(),
        )
        var succeeded = 0
        var failed = 0

        adapter.ports().settings.execute(
            SettingsRequest.WriteCamera(
                com.skycommand.relay.settings.command.CameraSettingsPatch(focusMode = "MANUAL"),
            ),
            object : SettingsDjiCompletion {
                override fun succeed(snapshot: SettingsSnapshot) { succeeded += 1 }
                override fun fail() { failed += 1 }
            },
        )
        adapter.advanceUntilIdle()
        var readBack: SettingsSnapshot? = null
        adapter.ports().settings.execute(SettingsRequest.Read(SettingsDomain.CAMERA), object : SettingsDjiCompletion {
            override fun succeed(snapshot: SettingsSnapshot) { readBack = snapshot }
            override fun fail() = error("unexpected settings read failure")
        })
        adapter.advanceUntilIdle()

        assertEquals(0, succeeded)
        assertEquals(1, failed)
        assertEquals(
            SettingsSnapshot.Camera(
                com.skycommand.relay.settings.command.CameraSettings(false, "AUTO", "DEFAULT"),
            ),
            readBack,
        )
    }

    @Test
    fun `图传控制完成不产生视频就绪事实`() {
        val adapter = SimulationDjiAdapter.create(SimulationDjiPlan.empty(), ManualSimulationClock())
        var completed = 0

        adapter.ports().stream.start(
            ValidatedStreamConfig("rtmp://127.0.0.1/live/relay"),
            status = {},
            runtimeFailure = {},
            completion = object : StreamDjiCompletion {
                override fun succeed() { completed += 1 }
                override fun fail() = error("unexpected stream failure")
            },
        )
        adapter.advanceUntilIdle()

        assertEquals(1, completed)
        assertEquals(false, adapter.snapshot().streamMediaReady)
    }

    @Test
    fun `图传端口按开始停止类别消费静默计划而不伪造完成`() {
        val adapter = SimulationDjiAdapter.create(
            SimulationDjiPlan.builder()
                .stream(SimulationStreamOperation.START, SimulationOperation.Silent())
                .build(),
            ManualSimulationClock(),
        )
        var completed = 0

        adapter.ports().stream.start(
            ValidatedStreamConfig("rtmp://127.0.0.1/live/relay"),
            status = {},
            runtimeFailure = {},
            completion = object : StreamDjiCompletion {
                override fun succeed() { completed += 1 }
                override fun fail() { completed += 1 }
            },
        )
        adapter.advanceUntilIdle()

        assertEquals(0, completed)
        assertEquals(false, adapter.snapshot().streamMediaReady)
    }

    @Test
    fun `设备端口从同一模拟事实发布遥控器和飞机连接`() {
        val adapter = SimulationDjiAdapter.create(SimulationDjiPlan.empty(), ManualSimulationClock())
        val remote = mutableListOf<RemoteControllerSignal>()
        var aircraftConnected: Boolean? = null

        adapter.ports().remoteController.start { remote += it }
        adapter.ports().aircraft.start { aircraftConnected = it.aircraftConnected }
        adapter.advanceUntilIdle()

        assertEquals(true, remote.single().connected)
        assertEquals(true, aircraftConnected)
    }

    @Test
    fun `真实设备连接模块从模拟端口形成一致的在线快照`() {
        val adapter = SimulationDjiAdapter.create(SimulationDjiPlan.empty(), ManualSimulationClock())
        val device = DeviceConnection.create(
            DeviceConnectionDependencies(
                adapter.ports().sdk,
                adapter.ports().remoteController,
                adapter.ports().aircraft,
                adapter.ports().pairing,
                adapter.ports().pairingStatus,
                OperationExecutor { task -> task() },
                OperationScheduler { _, _ -> OperationCancellation { } },
            ),
        )

        device.start()
        adapter.advanceUntilIdle()
        val snapshot = device.snapshot()

        assertEquals(SdkAvailability.READY, snapshot.sdkAvailability)
        assertEquals(LinkState.CONNECTED, snapshot.remoteController)
        assertEquals(LinkState.CONNECTED, snapshot.aircraft)
        assertEquals(LinkState.CONNECTED, snapshot.flightController)
        assertEquals(PairingState.PAIRED, snapshot.pairing)
        assertEquals(true, device.capabilities().canRunWayline)
        assertEquals(true, device.capabilities().canStreamVideo)
    }
}
