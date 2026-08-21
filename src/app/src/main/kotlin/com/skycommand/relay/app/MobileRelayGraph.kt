package com.skycommand.relay.app

import androidx.activity.ComponentActivity
import com.skycommand.relay.device.DeviceConnection
import com.skycommand.relay.device.DeviceConnectionDependencies
import com.skycommand.relay.device.DeviceConnectionStartResult
import com.skycommand.relay.device.aircraft.android.AndroidAircraftPort
import com.skycommand.relay.device.operation.OperationCancellation
import com.skycommand.relay.device.operation.OperationExecutor
import com.skycommand.relay.device.operation.OperationScheduler
import com.skycommand.relay.device.pairing.PairingOperationResult
import com.skycommand.relay.device.pairing.PairingRequestResult
import com.skycommand.relay.device.state.SdkAvailability
import com.skycommand.relay.device.pairing.command.android.AndroidPairingPort
import com.skycommand.relay.device.pairing.status.android.AndroidPairingStatusPort
import com.skycommand.relay.device.remote.android.AndroidRemoteControllerPort
import com.skycommand.relay.device.sdk.android.AndroidDjiSdkPort
import com.skycommand.relay.gateway.RelayGateway
import com.skycommand.relay.gateway.RelayGatewayConfig
import com.skycommand.relay.gateway.command.CommandCompletion
import com.skycommand.relay.gateway.command.CommandHandler
import com.skycommand.relay.gateway.command.RegistrationResult
import com.skycommand.relay.gateway.outbound.PublishResult
import com.skycommand.relay.gateway.session.MonotonicScheduler
import com.skycommand.relay.gateway.session.ScheduledCancellation
import com.skycommand.relay.gateway.session.SessionEndKind
import com.skycommand.relay.gateway.session.SessionState
import com.skycommand.relay.gateway.transport.OkHttpTransportConnector
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.MissionPhaseFrame
import com.skycommand.relay.diagnostics.DiagnosticClock
import com.skycommand.relay.diagnostics.DiagnosticJournal
import com.skycommand.relay.diagnostics.DiagnosticLevel
import com.skycommand.relay.diagnostics.android.AndroidDiagnosticStore
import com.skycommand.relay.diagnostics.gateway.GatewayDiagnosticPublisher
import com.skycommand.relay.diagnostics.gateway.RelayGatewayDiagnosticPort
import com.skycommand.relay.runtime.AppRuntime
import com.skycommand.relay.runtime.RuntimeCancellation
import com.skycommand.relay.runtime.RuntimeStartResult
import com.skycommand.relay.runtime.RuntimeState
import com.skycommand.relay.runtime.RuntimeStopResult
import com.skycommand.relay.runtime.bootstrap.AppBootstrap
import com.skycommand.relay.runtime.permission.PermissionCancellation
import com.skycommand.relay.runtime.permission.PermissionCoordinator
import com.skycommand.relay.runtime.permission.PermissionKind
import com.skycommand.relay.runtime.permission.PermissionRequestResult
import com.skycommand.relay.runtime.permission.PermissionState
import com.skycommand.relay.runtime.permission.android.AndroidPermissionAdapter
import com.skycommand.relay.runtime.service.ForegroundServiceController
import com.skycommand.relay.runtime.service.android.AndroidForegroundServicePort
import com.skycommand.relay.runtime.service.android.ForegroundNotificationSpec
import com.skycommand.relay.stream.LiveStream
import com.skycommand.relay.stream.LiveStreamDependencies
import com.skycommand.relay.stream.camera.CameraStreamSource
import com.skycommand.relay.stream.camera.android.AndroidCameraStreamApi
import com.skycommand.relay.stream.dji.android.AndroidDjiStreamPort
import com.skycommand.relay.stream.whip.WhipLiveStream
import com.skycommand.relay.stream.whip.WhipLiveStreamDependencies
import com.skycommand.relay.stream.whip.android.AndroidWhipTransport
import com.skycommand.relay.flight.FlightControl
import com.skycommand.relay.flight.FlightControlDependencies
import com.skycommand.relay.flight.dji.android.AndroidDjiFlightPort
import com.skycommand.relay.settings.DeviceSettings
import com.skycommand.relay.settings.DeviceSettingsDependencies
import com.skycommand.relay.settings.dji.android.AndroidDjiSettingsPort
import com.skycommand.relay.telemetry.Telemetry
import com.skycommand.relay.telemetry.command.TelemetryReadResult
import com.skycommand.relay.telemetry.flight.android.AndroidFlightTelemetrySource
import com.skycommand.relay.telemetry.flight.FlightTelemetrySource
import com.skycommand.relay.telemetry.publish.PublishTelemetryResult
import com.skycommand.relay.telemetry.publish.TelemetrySink
import com.skycommand.relay.wayline.WaylineMission
import com.skycommand.relay.wayline.WaylineMissionDependencies
import com.skycommand.relay.wayline.android.AndroidDjiWaylineAdapter
import com.skycommand.relay.wayline.staging.android.AndroidMissionStagingStorage
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

data class MobileRelayStatus(
    val runtime: RuntimeState,
    val gateway: SessionState,
    val remoteController: String,
    val aircraft: String,
    val pairing: String,
    val stream: String,
    val mission: String,
    val canStartPairing: Boolean,
    val canStopPairing: Boolean,
)

class MobileRelayGraph private constructor(
    private val runtime: AppRuntime,
    private val permissions: PermissionCoordinator,
    private val device: DeviceConnection,
    private val gateway: RelayGateway,
    private val diagnostics: GatewayDiagnosticPublisher,
    private val telemetry: Telemetry,
    private val flight: FlightTelemetrySource,
    private val flightControl: FlightControl,
    private val deviceSettings: DeviceSettings,
    private val stream: LiveStream,
    private val whipStream: WhipLiveStream,
    private val videoTransports: VideoTransportInterlock,
    private val wayline: WaylineMission,
    private val waylineAdapter: AndroidDjiWaylineAdapter,
    private val staging: AndroidMissionStagingStorage,
    private val foregroundPort: AndroidForegroundServicePort,
    private val executor: ScheduledThreadPoolExecutor,
    private val journal: DiagnosticJournal,
    private val permissionAdapter: AndroidPermissionAdapter,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<(MobileRelayStatus) -> Unit>()
    private val registrations = mutableListOf<CloseableRegistration>()
    private var startCancellation: RuntimeCancellation? = null
    private var usbWatchStarted = false
    private var usbCancellation: PermissionCancellation? = null
    private var hardwareRefreshedForSdkReady = false

    fun start(): RuntimeStartResult {
        val result = runtime.start(setOf(PermissionKind.RUNTIME))
        if (result is RuntimeStartResult.Accepted) startCancellation = result.cancellation
        return result
    }

    fun stop(): RuntimeStopResult {
        cancelUsbWatch()
        hardwareRefreshedForSdkReady = false
        startCancellation?.cancel()
        startCancellation = null
        return runtime.stop()
    }

    fun startPairing(): PairingRequestResult = device.requestPairingStart(30_000) { notifyStatus() }
    fun stopPairing(): PairingRequestResult = device.requestPairingStop(30_000) { notifyStatus() }

    fun status(): MobileRelayStatus {
        val deviceSnapshot = device.snapshot()
        val capabilities = device.capabilities()
        val missionSnapshot = wayline.snapshot()
        val running = runtime.snapshot() == RuntimeState.RUNNING
        return MobileRelayStatus(
            runtime.snapshot(),
            gateway.connectionState(),
            deviceSnapshot.remoteController.name,
            deviceSnapshot.aircraft.name,
            deviceSnapshot.pairing.name,
            stream.snapshot().state.name,
            missionSnapshot.upload.toString() + "/" + missionSnapshot.execution.name,
            canStartPairing = running && capabilities.canStartPairing,
            canStopPairing = running && capabilities.canStopPairing,
        )
    }

    fun onStatusChanged(listener: (MobileRelayStatus) -> Unit): CloseableRegistration {
        listeners += listener
        runCatching { listener(status()) }
        return CloseableRegistration { listeners -= listener }
    }

    private fun installStatusNotifications() {
        registrations += runtime.onChanged {
            notifyStatus()
            if (runtime.snapshot() == RuntimeState.RUNNING) watchUsbAccessory()
        }.let { registration ->
            CloseableRegistration { registration.unregister() }
        }
        registrations += device.onChanged {
            notifyStatus()
            refreshHardwareIfSdkReady()
        }.let { registration ->
            CloseableRegistration { registration.unregister() }
        }
        registrations += permissionAdapter.onUsbPresenceChanged {
            onUsbPresenceChanged()
        }.let { cancellation ->
            CloseableRegistration { cancellation.cancel() }
        }
        registrations += gateway.onStateChanged { event ->
            val ended = event.endReason
            val code = if (ended != null) "SESSION_${ended.kind.name}" else "SESSION_${event.snapshot.state.name}"
            val level = when (ended?.kind) {
                SessionEndKind.HANDSHAKE_TIMEOUT,
                SessionEndKind.NOT_CONNECTED,
                SessionEndKind.INVALID_FRAME,
                SessionEndKind.UNSUPPORTED_FRAME,
                SessionEndKind.PROTOCOL_VERSION_UNSUPPORTED,
                -> DiagnosticLevel.WARN
                else -> DiagnosticLevel.INFO
            }
            val detail = when (ended?.kind) {
                SessionEndKind.HANDSHAKE_TIMEOUT -> "Phone did not receive paired within handshake timeout"
                SessionEndKind.NOT_CONNECTED -> "Phone to desktop transport closed"
                SessionEndKind.EXPLICIT_STOP -> "Phone stopped the desktop session"
                SessionEndKind.INVALID_FRAME -> "Phone discarded an invalid protocol frame"
                SessionEndKind.UNSUPPORTED_FRAME -> "Phone received an unsupported protocol frame"
                SessionEndKind.PROTOCOL_VERSION_UNSUPPORTED -> "Phone and desktop protocol versions do not match"
                null -> "Phone to desktop session is ${event.snapshot.state.name}"
            }
            journal.record(level, "relay-gateway", code, null, detail)
            notifyStatus()
        }.let { registration ->
            CloseableRegistration { registration.unregister() }
        }
        registrations += stream.onChanged { notifyStatus() }.let { registration ->
            CloseableRegistration { registration.unregister() }
        }
        registrations += whipStream.onChanged { notifyStatus() }.let { registration ->
            CloseableRegistration { registration.unregister() }
        }
        registrations += wayline.onChanged { notifyStatus() }.let { registration ->
            CloseableRegistration { registration.unregister() }
        }
    }

    private fun watchUsbAccessory() {
        if (usbWatchStarted) return
        usbWatchStarted = true
        when (
            val result = permissions.request(setOf(PermissionKind.USB_ACCESS)) { terminal ->
                usbCancellation = null
                usbWatchStarted = false
                if (terminal is PermissionRequestResult.Terminal.Completed) {
                    device.refreshHardwareLinks()
                }
                notifyStatus()
            }
        ) {
            is PermissionRequestResult.AlreadySatisfied -> {
                usbWatchStarted = false
                device.refreshHardwareLinks()
                notifyStatus()
            }
            is PermissionRequestResult.Started -> usbCancellation = result.cancellation
            is PermissionRequestResult.Rejected,
            is PermissionRequestResult.Terminal,
            -> usbWatchStarted = false
        }
    }

    private fun onUsbPresenceChanged() {
        if (runtime.snapshot() != RuntimeState.RUNNING) return
        val usb = runCatching { permissionAdapter.snapshot().stateOf(PermissionKind.USB_ACCESS) }.getOrNull()
            ?: return
        if (usb == PermissionState.GRANTED) {
            device.refreshHardwareLinks()
        } else if (!usbWatchStarted) {
            watchUsbAccessory()
        }
        notifyStatus()
    }

    private fun refreshHardwareIfSdkReady() {
        val sdk = runCatching { device.snapshot().sdkAvailability }.getOrNull() ?: return
        if (sdk == SdkAvailability.READY) {
            if (hardwareRefreshedForSdkReady) return
            hardwareRefreshedForSdkReady = true
            device.refreshHardwareLinks()
        } else {
            hardwareRefreshedForSdkReady = false
        }
    }

    private fun cancelUsbWatch() {
        usbCancellation?.cancel()
        usbCancellation = null
        usbWatchStarted = false
    }

    private fun notifyStatus() {
        val current = runCatching(::status).getOrNull() ?: return
        listeners.forEach { listener -> runCatching { listener(current) } }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { startCancellation?.cancel() }
        startCancellation = null
        runCatching { cancelUsbWatch() }
        runCatching { runtime.stop() }
        runCatching { diagnostics.stop() }
        registrations.asReversed().forEach { runCatching { it.unregister() } }
        registrations.clear()
        runCatching { wayline.markDeviceUnavailable() }
        runCatching { videoTransports.close() }
        runCatching { whipStream.close() }
        runCatching { flightControl.close() }
        runCatching { deviceSettings.close() }
        runCatching { stream.close() }
        runCatching { waylineAdapter.close() }
        runCatching { staging.close() }
        runCatching { foregroundPort.close() }
        executor.shutdownNow()
        listeners.clear()
    }

    companion object {
        fun create(
            activity: ComponentActivity,
            endpoint: String,
            deviceId: String,
            permissionAdapter: AndroidPermissionAdapter,
        ): MobileRelayGraph {
            val executor = ScheduledThreadPoolExecutor(2) { task ->
                Thread(task, "msdk-relay").apply { isDaemon = true }
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
            val diagnosticStore = AndroidDiagnosticStore.create(activity)
            val journal = DiagnosticJournal.create(
                runId = UUID.randomUUID().toString(),
                capacity = 256,
                clock = DiagnosticClock { System.currentTimeMillis() },
                persistence = diagnosticStore.persistence(),
                recoveredEvents = diagnosticStore.restore(),
            )
            val device = DeviceConnection.create(
                DeviceConnectionDependencies(
                    AndroidDjiSdkPort.create(activity),
                    AndroidRemoteControllerPort.create(),
                    AndroidAircraftPort.create(),
                    AndroidPairingPort.create(),
                    AndroidPairingStatusPort.create(),
                    operationExecutor,
                    operationScheduler,
                    sdkDiagnosticSink = { diagnostic ->
                        journal.record(
                            DiagnosticLevel.ERROR,
                            "device-connection",
                            diagnostic.kind.name,
                            null,
                            "DJI SDK lifecycle callback reported a failure",
                        )
                    },
                    deviceStateDiagnosticSink = { diagnostic ->
                        journal.record(
                            DiagnosticLevel.WARN,
                            "device-connection",
                            diagnostic.kind.name,
                            null,
                            "Device state listener failed while receiving a state update",
                        )
                    },
                    remoteControllerDiagnosticSink = { diagnostic ->
                        journal.record(
                            DiagnosticLevel.WARN,
                            "device-connection",
                            diagnostic.kind.name,
                            null,
                            "Remote controller observation could not be processed",
                        )
                    },
                    pairingStatusDiagnosticSink = { diagnostic ->
                        journal.record(
                            DiagnosticLevel.WARN,
                            "device-connection",
                            diagnostic.kind.name,
                            null,
                            "Pairing status observation could not be processed",
                        )
                    },
                    aircraftDiagnosticSink = { diagnostic ->
                        journal.record(
                            DiagnosticLevel.WARN,
                            "device-connection",
                            diagnostic.kind.name,
                            null,
                            "Aircraft observation could not be processed",
                        )
                    },
                ),
            )
            val staging = AndroidMissionStagingStorage.create(activity)
            val waylineAdapter = AndroidDjiWaylineAdapter.create(activity)
            val wayline = WaylineMission.create(
                WaylineMissionDependencies(
                    stagingStorage = staging,
                    contentReader = staging,
                    uploadPort = waylineAdapter,
                    controlPort = waylineAdapter,
                    executionSignalSource = waylineAdapter,
                    operationCoordinator = device.operations(),
                    uploadTimeoutMillis = 60_000,
                    controlTimeoutMillis = 60_000,
                    diagnosticSink = { diagnostic ->
                        journal.record(
                            DiagnosticLevel.WARN,
                            "wayline-mission",
                            diagnostic.kind.name,
                            null,
                            "Mission state listener failed while receiving a state update",
                        )
                    },
                ),
            )
            val stream = LiveStream.create(
                LiveStreamDependencies(
                    AndroidDjiStreamPort.create(),
                    device.operations(),
                    diagnosticSink = { kind ->
                        journal.record(
                            DiagnosticLevel.WARN,
                            "live-stream",
                            kind.name,
                            null,
                            "Stream state listener failed while receiving a state update",
                        )
                    },
                ),
            )
            val whipStream = WhipLiveStream.create(
                WhipLiveStreamDependencies(
                    deviceId = deviceId,
                    source = CameraStreamSource.create(
                        AndroidCameraStreamApi.create(),
                        diagnosticSink = { kind ->
                            journal.record(
                                DiagnosticLevel.WARN,
                                "live-stream-webrtc",
                                kind.name,
                                null,
                                "DJI CameraStream source reported a WHIP input issue",
                            )
                        },
                    ),
                    transport = AndroidWhipTransport.create(activity),
                    diagnosticSink = { kind ->
                        journal.record(
                            DiagnosticLevel.WARN,
                            "live-stream-webrtc",
                            kind.name,
                            null,
                            "WHIP stream state listener failed",
                        )
                    },
                ),
            )
            val flightControl = FlightControl.create(
                FlightControlDependencies(
                    AndroidDjiFlightPort.create(),
                    device.operations(),
                ),
            )
            val deviceSettings = DeviceSettings.create(
                DeviceSettingsDependencies(AndroidDjiSettingsPort.create(), device.operations()),
            )
            val gateway = RelayGateway.create(
                RelayGatewayConfig(
                    endpoint,
                    deviceId,
                    wayline.missionSink(),
                    diagnosticSink = { diagnostic ->
                        journal.record(
                            DiagnosticLevel.WARN,
                            "relay-gateway",
                            diagnostic.kind.name,
                            null,
                            diagnostic.detail,
                        )
                    },
                ),
                OkHttpTransportConnector(),
                gatewayScheduler,
            )
            val diagnostics = GatewayDiagnosticPublisher.create(journal, RelayGatewayDiagnosticPort(gateway))
            wayline.onPhaseChanged { fact ->
                val result = gateway.publishMissionPhase(
                    MissionPhaseFrame(
                        missionRevision = fact.missionRevision,
                        deviceGeneration = fact.deviceGeneration,
                        sequence = fact.sequence,
                        phase = when (fact.phase) {
                            com.skycommand.relay.wayline.phase.MissionPhase.START_POINT_REACHED ->
                                com.skycommand.relay.protocol.MissionPhase.START_POINT_REACHED
                            com.skycommand.relay.wayline.phase.MissionPhase.ROUTE_EXECUTION_STARTED ->
                                com.skycommand.relay.protocol.MissionPhase.ROUTE_EXECUTION_STARTED
                        },
                        fileName = fact.fileName,
                    ),
                )
                if (result is PublishResult.Rejected) {
                    journal.record(
                        DiagnosticLevel.WARN,
                        "wayline-mission",
                        "MISSION_PHASE_PUBLISH_REJECTED",
                        null,
                        "Gateway was unavailable when a mission phase fact was produced",
                    )
                }
            }
            val flight = AndroidFlightTelemetrySource.create()
            val source = CompositeTelemetrySource(
                feed({ device.snapshot() }) { changed ->
                    device.onChanged { changed() }.let { CloseableRegistration(it::unregister) }
                },
                feed({ flight.snapshot() }) { changed ->
                    flight.onChanged(changed).let { CloseableRegistration(it::unregister) }
                },
                feed({ stream.snapshot() }) { changed ->
                    stream.onChanged { changed() }.let { CloseableRegistration(it::unregister) }
                },
                feed({ wayline.snapshot() }) { changed ->
                    wayline.onChanged { changed() }.let { CloseableRegistration(it::unregister) }
                },
            )
            val telemetry = Telemetry.create(
                source,
                TelemetrySink { snapshot ->
                    when (gateway.publishTelemetry(TelemetryFrameMapper.map(snapshot))) {
                        PublishResult.Delivered -> PublishTelemetryResult.Published
                        is PublishResult.Rejected -> PublishTelemetryResult.Rejected
                    }
                },
            )
            val videoTransports = VideoTransportInterlock(stream.commandHandler(), whipStream.commandHandler())
            registerCommands(gateway, journal, telemetry, device, flightControl, deviceSettings, videoTransports, wayline)
            val lifecycle = RelayBootstrapModule(
                object : RelayLifecyclePorts {
                    override fun sdkAvailability() = device.snapshot().sdkAvailability
                    override fun onDeviceChanged(listener: () -> Unit) =
                        device.onChanged { listener() }.let { CloseableRegistration(it::unregister) }
                    override fun onGatewayStateChanged(listener: (SessionState) -> Unit) =
                        gateway.onStateChanged { listener(it.snapshot.state) }.let { CloseableRegistration(it::unregister) }
                    override fun startDevice() {
                        val result = device.start()
                        if (result is DeviceConnectionStartResult.StartRejected) error(result.safeReason)
                    }
                    override fun stopDevice() { device.stop() }
                    override fun startTelemetry() { telemetry.start() }
                    override fun stopTelemetry() { telemetry.stop() }
                    override fun publishTelemetry() { telemetry.publishCurrent() }
                    override fun startGateway() {
                        diagnostics.start()
                        gateway.start()
                    }
                    override fun stopGateway() {
                        diagnostics.stop()
                        gateway.stop()
                    }
                    override fun closeFlightTelemetry() { flight.close() }
                    override fun markStreamUnavailable() {
                        videoTransports.markDeviceUnavailable()
                        stream.markDeviceUnavailable()
                        whipStream.markDeviceUnavailable()
                    }
                    override fun markMissionUnavailable() { wayline.markDeviceUnavailable() }
                    override fun markFlightControlUnavailable() { flightControl.markDeviceUnavailable() }
                    override fun markDeviceSettingsUnavailable() { deviceSettings.markDeviceUnavailable() }
                    override fun reportDiagnostic(kind: RelayBootstrapDiagnosticKind) {
                        journal.record(
                            DiagnosticLevel.ERROR,
                            "app-runtime",
                            kind.name,
                            null,
                            "Relay lifecycle operation could not be completed",
                        )
                    }
                },
            )
            val foregroundPort = AndroidForegroundServicePort.create(
                activity,
                ForegroundNotificationSpec(
                    "msdk-relay", R.string.relay_channel, R.string.relay_running, 1001,
                    android.R.drawable.stat_sys_upload,
                ),
            )
            val permissions = PermissionCoordinator.create(permissionAdapter)
            val runtime = AppRuntime.create(
                permissions,
                ForegroundServiceController.create(foregroundPort),
                AppBootstrap.create(listOf(lifecycle)),
            )
            return MobileRelayGraph(
                runtime, permissions, device, gateway, diagnostics, telemetry, flight, flightControl, deviceSettings, stream, whipStream, videoTransports, wayline, waylineAdapter,
                staging, foregroundPort, executor, journal, permissionAdapter,
            ).also { it.installStatusNotifications() }
        }

        private fun <T> feed(
            snapshot: () -> T,
            subscribe: ((() -> Unit)) -> CloseableRegistration,
        ): SnapshotFeed<T> = object : SnapshotFeed<T> {
            override fun snapshot(): T = snapshot()
            override fun onChanged(listener: () -> Unit) = subscribe(listener)
        }

        private fun registerCommands(
            gateway: RelayGateway,
            journal: DiagnosticJournal,
            telemetry: Telemetry,
            device: DeviceConnection,
            flightControl: FlightControl,
            deviceSettings: DeviceSettings,
            videoTransports: VideoTransportInterlock,
            wayline: WaylineMission,
        ) {
            val telemetryHandler = CommandHandler { command, completion ->
                if (command.fields.fields.isNotEmpty()) completion.reject("Telemetry command fields are invalid")
                else when (val read = telemetry.read()) {
                    is TelemetryReadResult.ReadSucceeded -> when (telemetry.publishCurrent()) {
                        PublishTelemetryResult.Published, PublishTelemetryResult.SkippedUnchanged ->
                            completion.succeed("Telemetry published", TelemetryFrameMapper.commandResult(read.snapshot))
                        PublishTelemetryResult.Rejected -> completion.reject("Telemetry is unavailable")
                    }
                    TelemetryReadResult.ReadUnavailable -> completion.reject("Telemetry is unavailable")
                }
            }
            val pairingHandler = pairingHandler(device, telemetry)
            register(gateway, journal, "telemetry.read", telemetryHandler)
            listOf("pairing.start", "pairing.stop", "pairing.status").forEach {
                register(gateway, journal, it, pairingHandler)
            }
            listOf("live-stream.start", "live-stream.stop").forEach {
                register(gateway, journal, it, videoTransports.handlerFor(it))
            }
            listOf("live-stream-webrtc.start", "live-stream-webrtc.stop").forEach {
                register(gateway, journal, it, videoTransports.handlerFor(it))
            }
            listOf("flight.takeoff", "flight.land", "flight.return-home").forEach {
                register(gateway, journal, it, flightControl.commandHandler())
            }
            listOf("device.settings.camera.read", "device.settings.camera.write", "device.settings.transmission.read", "device.settings.transmission.write").forEach {
                register(gateway, journal, it, deviceSettings.commandHandler())
            }
            listOf(
                "wayline.upload", "wayline.start", "wayline.pause",
                "wayline.resume", "wayline.stop",
            ).forEach { register(gateway, journal, it, wayline.commandHandler()) }
        }

        private fun pairingHandler(device: DeviceConnection, telemetry: Telemetry) = CommandHandler { command, completion ->
            if (command.fields.fields.isNotEmpty()) {
                completion.reject("Pairing command fields are invalid")
                return@CommandHandler
            }
            if (command.name == "pairing.status") {
                when (val read = telemetry.read()) {
                    is TelemetryReadResult.ReadSucceeded ->
                        completion.succeed("Pairing status", TelemetryFrameMapper.pairingStatus(read.snapshot))
                    TelemetryReadResult.ReadUnavailable -> completion.reject("Telemetry is unavailable")
                }
                return@CommandHandler
            }
            val once = OnceCommandCompletion(completion)
            val listener: (PairingOperationResult) -> Unit = { outcome ->
                if (outcome == PairingOperationResult.RequestAccepted) once.succeed("Pairing operation completed")
                else once.reject("Pairing operation failed")
            }
            val result = when (command.name) {
                "pairing.start" -> device.requestPairingStart(30_000, listener)
                "pairing.stop" -> device.requestPairingStop(30_000, listener)
                else -> null
            }
            when (result) {
                is PairingRequestResult.Accepted -> Unit
                is PairingRequestResult.Rejected, null -> once.reject("Pairing operation was rejected")
            }
        }

        private fun register(gateway: RelayGateway, journal: DiagnosticJournal, name: String, handler: CommandHandler) {
            check(gateway.registerCommandHandler(name, recorded(journal, handler)) == RegistrationResult.Registered) {
                "Command registration failed"
            }
        }
    }
}

private class OnceCommandCompletion(private val completion: CommandCompletion) {
    private val finished = AtomicBoolean(false)
    fun succeed(detail: String) { if (finished.compareAndSet(false, true)) completion.succeed(detail) }
    fun reject(detail: String) { if (finished.compareAndSet(false, true)) completion.reject(detail) }
}

private fun commandModule(name: String): String = when {
    name.startsWith("wayline.") -> "wayline-mission"
    name.startsWith("live-stream-webrtc.") -> "live-stream-webrtc"
    name.startsWith("live-stream.") -> "live-stream"
    name.startsWith("flight.") -> "flight-control"
    name.startsWith("device.settings.") -> "device-settings"
    name.startsWith("pairing.") -> "device-connection"
    name.startsWith("telemetry.") -> "telemetry"
    else -> "relay-gateway"
}

private fun commandEventCode(name: String, ok: Boolean): String {
    val stem = name.uppercase().replace('.', '_').replace('-', '_')
    return if (ok) "${stem}_OK" else "${stem}_REJECTED"
}

private fun recorded(journal: DiagnosticJournal, handler: CommandHandler): CommandHandler =
    CommandHandler { command, completion ->
        handler.handle(command, object : CommandCompletion {
            private fun write(ok: Boolean, detail: String) {
                if (ok && command.name == "telemetry.read") return
                journal.record(
                    if (ok) DiagnosticLevel.INFO else DiagnosticLevel.WARN,
                    commandModule(command.name),
                    commandEventCode(command.name, ok),
                    command.id,
                    "${command.name} $detail",
                )
            }

            override fun succeed(detail: String) {
                write(true, detail)
                completion.succeed(detail)
            }

            override fun succeed(detail: String, result: JsonObject?) {
                write(true, detail)
                completion.succeed(detail, result)
            }

            override fun reject(detail: String) {
                write(false, detail)
                completion.reject(detail)
            }
        })
    }
