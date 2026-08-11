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
import com.skycommand.relay.gateway.session.SessionState
import com.skycommand.relay.gateway.transport.OkHttpTransportConnector
import com.skycommand.relay.runtime.AppRuntime
import com.skycommand.relay.runtime.RuntimeState
import com.skycommand.relay.runtime.bootstrap.AppBootstrap
import com.skycommand.relay.runtime.permission.PermissionCoordinator
import com.skycommand.relay.runtime.permission.PermissionKind
import com.skycommand.relay.runtime.permission.android.AndroidPermissionAdapter
import com.skycommand.relay.runtime.service.ForegroundServiceController
import com.skycommand.relay.runtime.service.android.AndroidForegroundServicePort
import com.skycommand.relay.runtime.service.android.ForegroundNotificationSpec
import com.skycommand.relay.stream.LiveStream
import com.skycommand.relay.stream.LiveStreamDependencies
import com.skycommand.relay.stream.dji.android.AndroidDjiStreamPort
import com.skycommand.relay.telemetry.Telemetry
import com.skycommand.relay.telemetry.flight.android.AndroidFlightTelemetrySource
import com.skycommand.relay.telemetry.flight.android.FlightTelemetrySource
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

data class MobileRelayStatus(
    val runtime: RuntimeState,
    val gateway: SessionState,
    val sdk: String,
    val aircraft: String,
    val stream: String,
    val mission: String,
)

class MobileRelayGraph private constructor(
    private val runtime: AppRuntime,
    private val device: DeviceConnection,
    private val gateway: RelayGateway,
    private val telemetry: Telemetry,
    private val flight: FlightTelemetrySource,
    private val stream: LiveStream,
    private val wayline: WaylineMission,
    private val waylineAdapter: AndroidDjiWaylineAdapter,
    private val staging: AndroidMissionStagingStorage,
    private val permissionAdapter: AndroidPermissionAdapter,
    private val foregroundPort: AndroidForegroundServicePort,
    private val executor: ScheduledThreadPoolExecutor,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<(MobileRelayStatus) -> Unit>()
    private val registrations = mutableListOf<CloseableRegistration>()

    fun start() = runtime.start(setOf(PermissionKind.RUNTIME, PermissionKind.USB_ACCESS))
    fun stop() = runtime.stop()

    fun status(): MobileRelayStatus {
        val deviceSnapshot = device.snapshot()
        val missionSnapshot = wayline.snapshot()
        return MobileRelayStatus(
            runtime.snapshot(),
            gateway.connectionState(),
            deviceSnapshot.sdkAvailability.name,
            deviceSnapshot.aircraft.name,
            stream.snapshot().state.name,
            missionSnapshot.upload.toString() + "/" + missionSnapshot.execution.name,
        )
    }

    fun onStatusChanged(listener: (MobileRelayStatus) -> Unit): CloseableRegistration {
        listeners += listener
        runCatching { listener(status()) }
        return CloseableRegistration { listeners -= listener }
    }

    private fun installStatusNotifications() {
        registrations += runtime.onChanged { notifyStatus() }.let { registration ->
            CloseableRegistration { registration.unregister() }
        }
        registrations += device.onChanged { notifyStatus() }.let { registration ->
            CloseableRegistration { registration.unregister() }
        }
        registrations += gateway.onStateChanged { notifyStatus() }.let { registration ->
            CloseableRegistration { registration.unregister() }
        }
        registrations += stream.onChanged { notifyStatus() }.let { registration ->
            CloseableRegistration { registration.unregister() }
        }
        registrations += wayline.onChanged { notifyStatus() }.let { registration ->
            CloseableRegistration { registration.unregister() }
        }
    }

    private fun notifyStatus() {
        val current = runCatching(::status).getOrNull() ?: return
        listeners.forEach { listener -> runCatching { listener(current) } }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { runtime.stop() }
        registrations.asReversed().forEach { runCatching { it.unregister() } }
        registrations.clear()
        runCatching { wayline.markDeviceUnavailable() }
        runCatching { stream.close() }
        runCatching { waylineAdapter.close() }
        runCatching { staging.close() }
        runCatching { permissionAdapter.close() }
        runCatching { foregroundPort.close() }
        executor.shutdownNow()
        listeners.clear()
    }

    companion object {
        fun create(activity: ComponentActivity, endpoint: String, deviceId: String): MobileRelayGraph {
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
            val device = DeviceConnection.create(
                DeviceConnectionDependencies(
                    AndroidDjiSdkPort.create(activity),
                    AndroidRemoteControllerPort.create(),
                    AndroidAircraftPort.create(),
                    AndroidPairingPort.create(),
                    AndroidPairingStatusPort.create(),
                    operationExecutor,
                    operationScheduler,
                ),
            )
            val staging = AndroidMissionStagingStorage.create(activity)
            val waylineAdapter = AndroidDjiWaylineAdapter.create(activity)
            val wayline = WaylineMission.create(
                WaylineMissionDependencies(
                    staging, staging, waylineAdapter, waylineAdapter, device.operations(),
                    uploadTimeoutMillis = 60_000,
                    controlTimeoutMillis = 30_000,
                ),
            )
            val stream = LiveStream.create(
                LiveStreamDependencies(AndroidDjiStreamPort.create(), device.operations()),
            )
            val gateway = RelayGateway.create(
                RelayGatewayConfig(endpoint, deviceId, wayline.missionSink()),
                OkHttpTransportConnector(),
                gatewayScheduler,
            )
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
            registerCommands(gateway, telemetry, device, stream, wayline)
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
                    override fun startGateway() { gateway.start() }
                    override fun stopGateway() { gateway.stop() }
                    override fun closeFlightTelemetry() { flight.close() }
                    override fun markStreamUnavailable() { stream.markDeviceUnavailable() }
                    override fun markMissionUnavailable() { wayline.markDeviceUnavailable() }
                },
            )
            val permissionAdapter = AndroidPermissionAdapter.attach(activity, activity.activityResultRegistry, activity)
            val foregroundPort = AndroidForegroundServicePort.create(
                activity,
                ForegroundNotificationSpec(
                    "msdk-relay", R.string.relay_channel, R.string.relay_running, 1001,
                    android.R.drawable.stat_sys_upload,
                ),
            )
            val runtime = AppRuntime.create(
                PermissionCoordinator.create(permissionAdapter),
                ForegroundServiceController.create(foregroundPort),
                AppBootstrap.create(listOf(lifecycle)),
            )
            return MobileRelayGraph(
                runtime, device, gateway, telemetry, flight, stream, wayline, waylineAdapter,
                staging, permissionAdapter, foregroundPort, executor,
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
            telemetry: Telemetry,
            device: DeviceConnection,
            stream: LiveStream,
            wayline: WaylineMission,
        ) {
            val telemetryHandler = CommandHandler { command, completion ->
                if (command.fields.fields.isNotEmpty()) completion.reject("Telemetry command fields are invalid")
                else when (telemetry.publishCurrent()) {
                    PublishTelemetryResult.Published, PublishTelemetryResult.SkippedUnchanged ->
                        completion.succeed("Telemetry published")
                    PublishTelemetryResult.Rejected -> completion.reject("Telemetry is unavailable")
                }
            }
            val pairingHandler = pairingHandler(device)
            register(gateway, "telemetry.read", telemetryHandler)
            listOf("pairing.start", "pairing.stop", "pairing.status").forEach {
                register(gateway, it, pairingHandler)
            }
            listOf("live-stream.start", "live-stream.stop").forEach {
                register(gateway, it, stream.commandHandler())
            }
            listOf(
                "wayline.generate", "wayline.upload", "wayline.start", "wayline.pause",
                "wayline.resume", "wayline.stop",
            ).forEach { register(gateway, it, wayline.commandHandler()) }
        }

        private fun pairingHandler(device: DeviceConnection) = CommandHandler { command, completion ->
            if (command.fields.fields.isNotEmpty()) {
                completion.reject("Pairing command fields are invalid")
                return@CommandHandler
            }
            if (command.name == "pairing.status") {
                completion.succeed("Pairing state: " + device.snapshot().pairing.name)
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

        private fun register(gateway: RelayGateway, name: String, handler: CommandHandler) {
            check(gateway.registerCommandHandler(name, handler) == RegistrationResult.Registered) {
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
