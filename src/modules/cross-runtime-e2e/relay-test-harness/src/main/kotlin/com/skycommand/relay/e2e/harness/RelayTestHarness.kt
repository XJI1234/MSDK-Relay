package com.skycommand.relay.e2e.harness

import com.skycommand.relay.device.DeviceConnection
import com.skycommand.relay.device.DeviceConnectionDependencies
import com.skycommand.relay.device.state.LinkState
import com.skycommand.relay.device.operation.OperationCancellation
import com.skycommand.relay.device.operation.OperationExecutor
import com.skycommand.relay.device.operation.OperationScheduler
import com.skycommand.relay.device.pairing.PairingOperationResult
import com.skycommand.relay.device.pairing.PairingRequestResult
import com.skycommand.relay.e2e.simulation.ManualSimulationClock
import com.skycommand.relay.e2e.simulation.SimulationDjiAdapter
import com.skycommand.relay.e2e.simulation.SimulationDjiPlan
import com.skycommand.relay.e2e.simulation.SimulationDjiSnapshot
import com.skycommand.relay.e2e.simulation.SimulationInjection
import com.skycommand.relay.e2e.simulation.SimulationMissionCommand
import com.skycommand.relay.e2e.simulation.SimulationOperation
import com.skycommand.relay.e2e.simulation.SimulationSettingsOperation
import com.skycommand.relay.e2e.simulation.SimulationStreamOperation
import com.skycommand.relay.flight.FlightControl
import com.skycommand.relay.flight.FlightControlDependencies
import com.skycommand.relay.flight.command.FlightAction
import com.skycommand.relay.gateway.RelayGateway
import com.skycommand.relay.gateway.RelayGatewayConfig
import com.skycommand.relay.gateway.command.CommandCompletion
import com.skycommand.relay.gateway.command.CommandHandler
import com.skycommand.relay.gateway.command.RegistrationResult
import com.skycommand.relay.gateway.outbound.PublishResult
import com.skycommand.relay.gateway.session.MonotonicScheduler
import com.skycommand.relay.gateway.session.ScheduledCancellation
import com.skycommand.relay.gateway.session.SessionState
import com.skycommand.relay.gateway.transport.OkHttpTransportConnector
import com.skycommand.relay.diagnostics.DiagnosticClock
import com.skycommand.relay.diagnostics.DiagnosticJournal
import com.skycommand.relay.diagnostics.DiagnosticLevel
import com.skycommand.relay.diagnostics.gateway.GatewayDiagnosticPublisher
import com.skycommand.relay.diagnostics.gateway.RelayGatewayDiagnosticPort
import com.skycommand.relay.settings.RelayConnectionSettingsResult
import com.skycommand.relay.settings.RelaySettings
import com.skycommand.relay.settings.identity.DeviceIdentityGenerator
import com.skycommand.relay.settings.store.RelaySettingsBackend
import com.skycommand.relay.settings.store.RelaySettingsRecord
import com.skycommand.relay.runtime.AppRuntime
import com.skycommand.relay.runtime.RuntimeStartResult
import com.skycommand.relay.runtime.bootstrap.AppBootstrap
import com.skycommand.relay.runtime.bootstrap.BootstrapModule
import com.skycommand.relay.runtime.permission.PermissionCancellation
import com.skycommand.relay.runtime.permission.PermissionCoordinator
import com.skycommand.relay.runtime.permission.PermissionKind
import com.skycommand.relay.runtime.permission.PermissionPort
import com.skycommand.relay.runtime.permission.PermissionPortCallback
import com.skycommand.relay.runtime.permission.PermissionSnapshot
import com.skycommand.relay.runtime.service.ForegroundServiceCallback
import com.skycommand.relay.runtime.service.ForegroundServiceController
import com.skycommand.relay.runtime.service.ForegroundServicePort
import com.skycommand.relay.protocol.JsonBoolean
import com.skycommand.relay.protocol.JsonNull
import com.skycommand.relay.protocol.JsonNumber
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.JsonString
import com.skycommand.relay.protocol.JsonValue
import com.skycommand.relay.protocol.MissionPhaseFrame
import com.skycommand.relay.protocol.TelemetryFrame
import com.skycommand.relay.settings.DeviceSettings
import com.skycommand.relay.settings.DeviceSettingsDependencies
import com.skycommand.relay.stream.LiveStream
import com.skycommand.relay.stream.LiveStreamDependencies
import com.skycommand.relay.stream.StreamStartGate
import com.skycommand.relay.telemetry.Telemetry
import com.skycommand.relay.telemetry.TelemetryRegistration
import com.skycommand.relay.telemetry.TelemetryStateSource
import com.skycommand.relay.telemetry.command.TelemetryReadResult
import com.skycommand.relay.telemetry.publish.PublishTelemetryResult
import com.skycommand.relay.telemetry.publish.TelemetrySink
import com.skycommand.relay.telemetry.snapshot.TelemetryInputs
import com.skycommand.relay.telemetry.snapshot.TelemetrySnapshot
import com.skycommand.relay.wayline.WaylineMission
import com.skycommand.relay.wayline.WaylineMissionDependencies
import com.skycommand.relay.wayline.executor.MissionStartSafetyGate
import com.skycommand.relay.wayline.phase.MissionExecutionSignal
import com.skycommand.relay.wayline.staging.MissionMetadata
import com.skycommand.relay.wayline.staging.StagingStorage
import com.skycommand.relay.wayline.uploader.StagedMissionContentReader
import java.io.ByteArrayOutputStream
import java.net.URI
import java.time.Duration
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import okhttp3.OkHttpClient

data class RelayHarnessConfig(val endpoint: String, val deviceId: String) {
    init {
        val uri = runCatching { URI(endpoint) }.getOrElse { throw IllegalArgumentException("测试 WebSocket 地址无效") }
        require(uri.scheme == "ws" && uri.host in setOf("127.0.0.1", "localhost", "::1")) {
            "测试宿主只能连接回环 WebSocket 地址"
        }
        require(deviceId.isNotBlank() && deviceId.none(Char::isISOControl)) { "测试设备标识无效" }
    }
}

class InMemoryMissionStorage : StagingStorage, StagedMissionContentReader {
    private val lock = ReentrantLock()
    private var temporary: ByteArrayOutputStream? = null
    private var temporaryMetadata: MissionMetadata? = null
    private var current: ByteArray? = null
    private var currentMetadata: MissionMetadata? = null

    override fun beginTemporary(metadata: MissionMetadata) = lock.withLock {
        temporaryMetadata = metadata
        temporary = ByteArrayOutputStream()
    }

    override fun append(bytes: ByteArray) = lock.withLock {
        checkNotNull(temporary) { "没有活动的临时航线" }.write(bytes)
    }

    override fun flush() = lock.withLock {
        checkNotNull(temporary) { "没有活动的临时航线" }
        Unit
    }

    override fun replaceCurrent() = lock.withLock {
        current = checkNotNull(temporary) { "没有活动的临时航线" }.toByteArray()
        currentMetadata = checkNotNull(temporaryMetadata)
        temporary = null
        temporaryMetadata = null
    }

    override fun deleteTemporary() = lock.withLock {
        temporary = null
        temporaryMetadata = null
    }

    override fun read(metadata: MissionMetadata): ByteArray = lock.withLock {
        check(currentMetadata == metadata) { "请求的航线不是当前已暂存航线" }
        checkNotNull(current).copyOf()
    }
}

private class HarnessRelaySettingsBackend(initial: RelaySettingsRecord) : RelaySettingsBackend {
    private val lock = ReentrantLock()
    private var record: RelaySettingsRecord? = initial

    override fun update(change: (RelaySettingsRecord?) -> RelaySettingsRecord?): RelaySettingsRecord? = lock.withLock {
        record = change(record)
        record
    }
}

private class HarnessBootstrapModule(
    override val name: String,
    private val startAction: () -> Unit,
    private val stopAction: () -> Unit,
) : BootstrapModule {
    override fun start() = startAction()
    override fun stop() = stopAction()
}

private class GrantedPermissionPort : PermissionPort {
    override fun snapshot(): PermissionSnapshot = PermissionSnapshot.granted(PermissionKind.RUNTIME)
    override fun request(required: Set<PermissionKind>, callback: PermissionPortCallback) = PermissionCancellation { }
}

private class ImmediateForegroundServicePort : ForegroundServicePort {
    override fun start(callback: ForegroundServiceCallback) = callback.started()
    override fun stop(callback: ForegroundServiceCallback) = callback.stopped()
}

data class RelayHarnessSnapshot(
    val closed: Boolean,
    val started: Boolean,
    val gateway: SessionState,
    val registeredCommands: Set<String>,
    val assembledModules: Set<String>,
    val simulation: SimulationDjiSnapshot,
)

class RelayTestHarness private constructor(
    private val device: DeviceConnection,
    private val gateway: RelayGateway,
    private val telemetry: Telemetry,
    private val flightControl: FlightControl,
    private val deviceSettings: DeviceSettings,
    private val stream: LiveStream,
    private val wayline: WaylineMission,
    private val appRuntime: AppRuntime,
    private val diagnosticJournal: DiagnosticJournal,
    private val diagnostics: GatewayDiagnosticPublisher,
    private val simulation: SimulationDjiAdapter,
    private val executor: ScheduledThreadPoolExecutor,
    private val transportClient: OkHttpClient,
    private val commands: Set<String>,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    fun start() {
        check(!closed.get()) { "测试宿主已关闭" }
        if (!started.compareAndSet(false, true)) return
        val result = appRuntime.start(setOf(PermissionKind.RUNTIME))
        check(result is RuntimeStartResult.Accepted || result is RuntimeStartResult.AlreadyRunning) {
            "测试应用运行时启动失败"
        }
        simulation.advanceUntilIdle()
    }

    fun advanceSimulation(duration: Duration) = simulation.advanceBy(duration)

    fun reconnect() {
        check(started.get() && !closed.get()) { "测试宿主尚未运行或已经关闭" }
        appRuntime.stop()
        val result = appRuntime.start(setOf(PermissionKind.RUNTIME))
        check(result is RuntimeStartResult.Accepted || result is RuntimeStartResult.AlreadyRunning) {
            "测试应用运行时重连失败"
        }
        simulation.advanceUntilIdle()
    }

    fun readTelemetry(): TelemetryReadResult = telemetry.read()

    fun injectMissionSignal(signal: MissionExecutionSignal) {
        simulation.inject(SimulationInjection.MissionSignal(signal, Duration.ZERO))
    }

    fun recordDiagnostic() {
        diagnosticJournal.record(
            DiagnosticLevel.INFO,
            "cross-runtime-e2e",
            "HARNESS_EVENT",
            null,
            "controlled diagnostic",
        )
        diagnostics.flush()
    }

    fun snapshot() = RelayHarnessSnapshot(
        closed.get(),
        started.get(),
        gateway.connectionState(),
        commands,
        ASSEMBLED_MODULES,
        simulation.snapshot(),
    )

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { appRuntime.stop() }
        runCatching { wayline.markDeviceUnavailable() }
        runCatching { flightControl.markDeviceUnavailable() }
        runCatching { deviceSettings.markDeviceUnavailable() }
        runCatching { stream.markDeviceUnavailable() }
        runCatching { device.stop() }
        runCatching { flightControl.close() }
        runCatching { deviceSettings.close() }
        runCatching { stream.close() }
        runCatching { simulation.close() }
        executor.shutdownNow()
        transportClient.dispatcher.executorService.shutdownNow()
        transportClient.connectionPool.evictAll()
    }

    companion object {
        private val ASSEMBLED_MODULES = setOf(
            "device-connection", "relay-gateway", "telemetry", "wayline-mission",
            "flight-control", "device-settings", "live-stream", "relay-settings", "runtime-diagnostics", "app-runtime",
        )

        fun create(config: RelayHarnessConfig, plan: SimulationDjiPlan): RelayTestHarness {
            val executor = ScheduledThreadPoolExecutor(2) { task ->
                Thread(task, "relay-e2e-harness").apply { isDaemon = true }
            }.apply { removeOnCancelPolicy = true }
            val operationExecutor = OperationExecutor { task -> executor.execute(task) }
            val operationScheduler = OperationScheduler { delay, callback ->
                val future = executor.schedule(callback, delay, TimeUnit.MILLISECONDS)
                OperationCancellation { future.cancel(false) }
            }
            val gatewayScheduler = MonotonicScheduler { delay, callback ->
                val future = executor.schedule(callback, delay, TimeUnit.MILLISECONDS)
                ScheduledCancellation { future.cancel(false) }
            }
            val simulation = SimulationDjiAdapter.create(plan, ManualSimulationClock())
            val ports = simulation.ports()
            val device = DeviceConnection.create(
                DeviceConnectionDependencies(
                    ports.sdk, ports.remoteController, ports.aircraft, ports.pairing, ports.pairingStatus,
                    operationExecutor, operationScheduler,
                ),
            )
            val storage = InMemoryMissionStorage()
            val wayline = WaylineMission.create(
                WaylineMissionDependencies(
                    storage, storage, ports.missionUpload, ports.missionControl, MissionStartSafetyGate { true }, ports.executionSignals,
                    device.operations(), uploadTimeoutMillis = 1_000, controlTimeoutMillis = 1_000,
                ),
            )
            val flightControl = FlightControl.create(
                FlightControlDependencies(ports.flight, device.operations(), timeoutMillis = 1_000),
            )
            val deviceSettings = DeviceSettings.create(
                DeviceSettingsDependencies(ports.settings, device.operations(), timeoutMillis = 1_000),
            )
            val stream = LiveStream.create(
                LiveStreamDependencies(
                    ports.stream,
                    device.operations(),
                    StreamStartGate { device.capabilities().canStreamVideo },
                    timeoutMillis = 1_000,
                ),
            )
            val relaySettings = RelaySettings.create(
                HarnessRelaySettingsBackend(
                    RelaySettingsRecord(schemaVersion = 1, endpoint = config.endpoint, deviceId = config.deviceId),
                ),
                DeviceIdentityGenerator { config.deviceId },
            )
            val connection = (relaySettings.connectionSettings() as? RelayConnectionSettingsResult.Available)?.settings
                ?: error("Test relay settings are unavailable")
            val endpoint = connection.endpoint?.value ?: error("Test relay endpoint is unavailable")
            val transportClient = OkHttpClient.Builder().pingInterval(15, TimeUnit.SECONDS).build()
            val gateway = RelayGateway.create(
                RelayGatewayConfig(endpoint, connection.deviceId.value, wayline.missionSink()),
                OkHttpTransportConnector(transportClient),
                gatewayScheduler,
            )
            val diagnosticJournal = DiagnosticJournal.create("e2e-run", 32, DiagnosticClock { 0L })
            val diagnostics = GatewayDiagnosticPublisher.create(
                diagnosticJournal,
                RelayGatewayDiagnosticPort(gateway),
            )
            wayline.onPhaseChanged { fact ->
                gateway.publishMissionPhase(
                    MissionPhaseFrame(
                        fact.missionRevision,
                        fact.deviceGeneration,
                        fact.sequence,
                        when (fact.phase) {
                            com.skycommand.relay.wayline.phase.MissionPhase.START_POINT_REACHED -> com.skycommand.relay.protocol.MissionPhase.START_POINT_REACHED
                            com.skycommand.relay.wayline.phase.MissionPhase.ROUTE_EXECUTION_STARTED -> com.skycommand.relay.protocol.MissionPhase.ROUTE_EXECUTION_STARTED
                        },
                        fact.fileName,
                    ),
                )
            }
            val source = HarnessTelemetrySource(device, ports.telemetry, stream, wayline)
            val telemetry = Telemetry.create(
                source,
                TelemetrySink { snapshot ->
                    when (gateway.publishTelemetry(HarnessTelemetryMapper.map(snapshot))) {
                        PublishResult.Delivered -> PublishTelemetryResult.Published
                        is PublishResult.Rejected -> PublishTelemetryResult.Rejected
                    }
                },
            )
            val appRuntime = AppRuntime.create(
                PermissionCoordinator.create(GrantedPermissionPort()),
                ForegroundServiceController.create(ImmediateForegroundServicePort()),
                AppBootstrap.create(
                    listOf(
                        HarnessBootstrapModule("device-connection", { device.start() }, { device.stop() }),
                        HarnessBootstrapModule("telemetry", { telemetry.start() }, { telemetry.stop() }),
                        HarnessBootstrapModule("relay-gateway", { gateway.start() }, { gateway.stop() }),
                        HarnessBootstrapModule("runtime-diagnostics", { diagnostics.start() }, { diagnostics.stop() }),
                    ),
                ),
            )
            val commands = registerCommands(gateway, telemetry, device, flightControl, deviceSettings, stream, wayline)
            return RelayTestHarness(
                device, gateway, telemetry, flightControl, deviceSettings, stream, wayline,
                appRuntime, diagnosticJournal, diagnostics, simulation, executor, transportClient, commands,
            )
        }

        private fun registerCommands(
            gateway: RelayGateway,
            telemetry: Telemetry,
            device: DeviceConnection,
            flightControl: FlightControl,
            deviceSettings: DeviceSettings,
            stream: LiveStream,
            wayline: WaylineMission,
        ): Set<String> {
            val handlers = linkedMapOf<String, CommandHandler>()
            handlers["telemetry.read"] = CommandHandler { command, completion ->
                if (command.fields.fields.isNotEmpty()) completion.reject("Telemetry command fields are invalid")
                else when (val read = telemetry.read()) {
                    is TelemetryReadResult.ReadSucceeded -> when (telemetry.publishCurrent()) {
                        PublishTelemetryResult.Published, PublishTelemetryResult.SkippedUnchanged ->
                            completion.succeed("Telemetry published", HarnessTelemetryMapper.commandResult(read.snapshot))
                        PublishTelemetryResult.Rejected -> completion.reject("Telemetry is unavailable")
                    }
                    TelemetryReadResult.ReadUnavailable -> completion.reject("Telemetry is unavailable")
                }
            }
            val pairing = pairingHandler(device, telemetry)
            listOf("pairing.start", "pairing.stop", "pairing.status").forEach { handlers[it] = pairing }
            listOf("live-stream.start", "live-stream.stop").forEach { handlers[it] = stream.commandHandler() }
            listOf("flight.takeoff", "flight.land", "flight.confirm-landing", "flight.return-home", "flight.stop-takeoff", "flight.stop-auto-landing").forEach { handlers[it] = flightControl.commandHandler() }
            listOf(
                "device.settings.camera.read", "device.settings.camera.write",
                "device.settings.transmission.read", "device.settings.transmission.write",
            ).forEach { handlers[it] = deviceSettings.commandHandler() }
            listOf(
                "wayline.upload", "wayline.start", "wayline.pause", "wayline.resume", "wayline.stop",
            ).forEach { handlers[it] = wayline.commandHandler() }
            handlers.forEach { (name, handler) ->
                check(gateway.registerCommandHandler(name, handler) == RegistrationResult.Registered)
            }
            return handlers.keys.toSet()
        }

        private fun pairingHandler(device: DeviceConnection, telemetry: Telemetry) = CommandHandler { command, completion ->
            if (command.fields.fields.isNotEmpty()) {
                completion.reject("Pairing command fields are invalid")
                return@CommandHandler
            }
            if (command.name == "pairing.status") {
                when (val read = telemetry.read()) {
                    is TelemetryReadResult.ReadSucceeded ->
                        completion.succeed("Pairing status", HarnessTelemetryMapper.pairingStatus(read.snapshot))
                    TelemetryReadResult.ReadUnavailable -> completion.reject("Telemetry is unavailable")
                }
                return@CommandHandler
            }
            val once = OnceCompletion(completion)
            val result = when (command.name) {
                "pairing.start" -> device.requestPairingStart(1_000) { once.complete(it) }
                "pairing.stop" -> device.requestPairingStop(1_000) { once.complete(it) }
                else -> null
            }
            if (result !is PairingRequestResult.Accepted) once.reject()
        }
    }
}

private class OnceCompletion(private val target: CommandCompletion) {
    private val done = AtomicBoolean(false)
    fun complete(result: PairingOperationResult) {
        if (!done.compareAndSet(false, true)) return
        if (result == PairingOperationResult.RequestAccepted) target.succeed("Pairing operation completed")
        else target.reject("Pairing operation failed")
    }
    fun reject() { if (done.compareAndSet(false, true)) target.reject("Pairing operation was rejected") }
}

private class HarnessTelemetrySource(
    private val device: DeviceConnection,
    private val flight: com.skycommand.relay.telemetry.flight.FlightTelemetrySource,
    private val stream: LiveStream,
    private val wayline: WaylineMission,
) : TelemetryStateSource {
    override fun snapshot() = TelemetryInputs(device.snapshot(), flight.snapshot(), stream.snapshot(), wayline.snapshot())

    override fun onChanged(listener: () -> Unit): TelemetryRegistration {
        val deviceRegistration = device.onChanged { listener() }
        val flightRegistration = flight.onChanged(listener)
        val streamRegistration = stream.onChanged { listener() }
        val waylineRegistration = wayline.onChanged { listener() }
        val active = AtomicBoolean(true)
        return TelemetryRegistration {
            if (active.compareAndSet(true, false)) {
                waylineRegistration.unregister()
                streamRegistration.unregister()
                flightRegistration.unregister()
                deviceRegistration.unregister()
            }
        }
    }
}

private object HarnessTelemetryMapper {
    fun map(snapshot: TelemetrySnapshot): TelemetryFrame = TelemetryFrame(
        payload = JsonObject(fields(snapshot)),
        capabilities = JsonObject(
            mapOf(
                "liveVideo" to JsonBoolean(snapshot.capabilities.liveVideo),
                "waypointMission" to JsonBoolean(snapshot.capabilities.waypointMission),
                "waypointMissionSupport" to JsonString(snapshot.capabilities.waypointMissionSupport.name),
                "virtualStick" to JsonBoolean(snapshot.capabilities.virtualStick),
            ),
        ),
    )

    fun commandResult(snapshot: TelemetrySnapshot) = JsonObject(fields(snapshot) + ("capabilities" to map(snapshot).capabilities))

    fun pairingStatus(snapshot: TelemetrySnapshot): JsonObject = JsonObject(
        mapOf(
            "pairingState" to JsonString(snapshot.pairing.name),
            "aircraftConnected" to JsonBoolean(snapshot.aircraft == com.skycommand.relay.device.state.LinkState.CONNECTED),
            "flightControllerConnected" to JsonBoolean(snapshot.flightController == com.skycommand.relay.device.state.LinkState.CONNECTED),
            "aircraftModel" to JsonString(snapshot.aircraftModel?.takeIf(String::isNotBlank) ?: "UNKNOWN"),
            "motorsOn" to snapshot.motorsOn.json(),
            "sdkRegistered" to JsonBoolean(snapshot.sdkAvailability == com.skycommand.relay.device.state.SdkAvailability.READY),
        ),
    )

    private fun fields(snapshot: TelemetrySnapshot): Map<String, JsonValue> = mapOf(
        "deviceRevision" to JsonNumber(snapshot.deviceRevision.toString()),
        "sdkAvailability" to JsonString(snapshot.sdkAvailability.name),
        "remoteController" to JsonString(snapshot.remoteController.name),
                "aircraft" to JsonString(snapshot.aircraft.name),
                "flightController" to JsonString(snapshot.flightController.name),
                "airLink" to JsonString(snapshot.airLink.name),
                "camera" to JsonString(snapshot.camera.name),
        "pairing" to JsonString(snapshot.pairing.name),
        "remoteControllerModel" to snapshot.remoteControllerModel.json(),
        "aircraftModel" to snapshot.aircraftModel.json(),
        "isFlying" to snapshot.isFlying.json(), "motorsOn" to snapshot.motorsOn.json(),
        "flightMode" to snapshot.flightMode.json(), "battery" to JsonString(snapshot.battery.name),
        "batteryPercent" to snapshot.batteryPercent.takeIf { snapshot.battery == LinkState.CONNECTED }.json(),
        "lowBatteryRthState" to snapshot.lowBatteryRthState?.name.json(),
        "remainingFlightTimeSeconds" to snapshot.remainingFlightTimeSeconds.json(),
        "altitudeMeters" to snapshot.altitudeMeters.json(), "latitude" to snapshot.latitude.json(),
        "longitude" to snapshot.longitude.json(), "liveStreaming" to snapshot.liveStreaming.json(),
        "liveStreamNotice" to snapshot.liveStreamNotice.json(), "liveResolution" to snapshot.liveResolution.json(),
        "liveFps" to snapshot.liveFps.json(), "liveVideoBitrateKbps" to snapshot.liveVideoBitrateKbps.json(),
        "liveRttMillis" to snapshot.liveRttMillis.json(), "missionRevision" to snapshot.missionRevision.json(),
        "missionDeviceGeneration" to snapshot.missionDeviceGeneration.json(), "missionExecution" to JsonString(snapshot.missionExecution.name),
        "missionUploadProgress" to snapshot.missionUploadProgress.json(), "missionFileName" to snapshot.missionFileName.json(),
    )

    private fun String?.json(): JsonValue = this?.let(::JsonString) ?: JsonNull
    private fun Boolean?.json(): JsonValue = this?.let(::JsonBoolean) ?: JsonNull
    private fun Number?.json(): JsonValue = this?.let { JsonNumber(it.toString()) } ?: JsonNull
}

fun main(args: Array<String>) {
    require(args.size in 2..3) { "用法：RelayTestHarness <ws://127.0.0.1:port> <device-id> [profile]" }
    val profile = args.getOrNull(2) ?: "success"
    require(profile in setOf(
        "success", "flight-timeout", "flight-reject", "flight-throw", "flight-duplicate", "flight-late",
        "mission-upload-timeout", "mission-upload-reject", "mission-upload-throw", "mission-upload-duplicate", "mission-upload-late",
        "mission-control-reject", "mission-control-throw", "mission-control-timeout", "mission-control-duplicate", "mission-control-late",
        "settings-reject", "settings-throw", "settings-timeout", "settings-duplicate", "settings-late",
        "stream-timeout", "stream-reject", "stream-throw", "stream-duplicate", "stream-late",
    )) { "测试故障配置无效" }
    val plan = SimulationDjiPlan.builder().apply {
        FlightAction.entries.forEach { action ->
            if (profile == "flight-timeout" && action == FlightAction.TAKEOFF) {
                flight(action, SimulationOperation.Silent())
            }
            if (profile == "flight-reject" && action == FlightAction.TAKEOFF) {
                flight(action, SimulationOperation.Reject)
            }
            if (profile == "flight-throw" && action == FlightAction.TAKEOFF) {
                flight(action, SimulationOperation.Throw())
            }
            if (profile == "flight-duplicate" && action == FlightAction.TAKEOFF) {
                flight(action, SimulationOperation.Duplicate())
            }
            if (profile == "flight-late" && action == FlightAction.TAKEOFF) {
                flight(action, SimulationOperation.Late(Duration.ofSeconds(2)))
            }
            flight(action, SimulationOperation.Succeed())
        }
        upload(when (profile) {
            "mission-upload-timeout" -> SimulationOperation.Silent()
            "mission-upload-reject" -> SimulationOperation.Reject
            "mission-upload-throw" -> SimulationOperation.Throw()
            "mission-upload-duplicate" -> SimulationOperation.Duplicate()
            "mission-upload-late" -> SimulationOperation.Late(Duration.ofSeconds(2))
            else -> SimulationOperation.Succeed()
        })
        repeat(3) { upload(SimulationOperation.Succeed()) }
        SimulationMissionCommand.entries.forEach { command ->
            mission(command, if (command != SimulationMissionCommand.START) SimulationOperation.Succeed() else when (profile) {
                "mission-control-reject" -> SimulationOperation.Reject
                "mission-control-throw" -> SimulationOperation.Throw()
                "mission-control-timeout" -> SimulationOperation.Silent()
                "mission-control-duplicate" -> SimulationOperation.Duplicate()
                "mission-control-late" -> SimulationOperation.Late(Duration.ofSeconds(2))
                else -> SimulationOperation.Succeed()
            })
            repeat(3) { mission(command, SimulationOperation.Succeed()) }
        }
        if (profile == "settings-reject") {
            settings(SimulationSettingsOperation.CAMERA_WRITE, SimulationOperation.Fail())
        }
        when (profile) {
            "settings-throw" -> settings(SimulationSettingsOperation.CAMERA_WRITE, SimulationOperation.Throw())
            "settings-timeout" -> settings(SimulationSettingsOperation.CAMERA_WRITE, SimulationOperation.Silent())
            "settings-duplicate" -> settings(SimulationSettingsOperation.CAMERA_WRITE, SimulationOperation.Duplicate())
            "settings-late" -> settings(SimulationSettingsOperation.CAMERA_WRITE, SimulationOperation.Late(Duration.ofSeconds(2)))
        }
        if (profile == "stream-timeout") {
            stream(SimulationStreamOperation.START, SimulationOperation.Silent())
        }
        when (profile) {
            "stream-reject" -> stream(SimulationStreamOperation.START, SimulationOperation.Reject)
            "stream-throw" -> stream(SimulationStreamOperation.START, SimulationOperation.Throw())
            "stream-duplicate" -> stream(SimulationStreamOperation.START, SimulationOperation.Duplicate())
            "stream-late" -> stream(SimulationStreamOperation.START, SimulationOperation.Late(Duration.ofSeconds(2)))
        }
    }.build()
    RelayTestHarness.create(RelayHarnessConfig(args[0], args[1]), plan).use { harness ->
        harness.start()
        println("READY")
        while (true) {
            val line = readlnOrNull() ?: break
            val parts = line.trim().split(' ').filter(String::isNotBlank)
            when (parts.firstOrNull()) {
                "ADVANCE" -> {
                    val millis = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                    require(millis >= 0) { "推进时间不能为负数" }
                    harness.advanceSimulation(Duration.ofMillis(millis))
                    println("ADVANCED")
                }
                "SIGNAL" -> {
                    val signal = MissionExecutionSignal.valueOf(checkNotNull(parts.getOrNull(1)))
                    harness.injectMissionSignal(signal)
                    harness.advanceSimulation(Duration.ZERO)
                    println("SIGNALED ${signal.name}")
                }
                "DIAGNOSTIC" -> {
                    harness.recordDiagnostic()
                    println("DIAGNOSTIC_RECORDED")
                }
                "RECONNECT" -> {
                    harness.reconnect()
                    println("RECONNECTED")
                }
                "EXIT" -> break
                else -> println("IGNORED")
            }
        }
    }
}
