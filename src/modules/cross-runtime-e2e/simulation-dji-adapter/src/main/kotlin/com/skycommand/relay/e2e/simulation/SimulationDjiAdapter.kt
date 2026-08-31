package com.skycommand.relay.e2e.simulation

import com.skycommand.relay.flight.command.FlightAction
import com.skycommand.relay.flight.dji.DjiFlightPort
import com.skycommand.relay.flight.dji.FlightDjiCompletion
import com.skycommand.relay.wayline.executor.ControlCompletion
import com.skycommand.relay.wayline.executor.MissionControlPort
import com.skycommand.relay.wayline.phase.MissionExecutionSignal
import com.skycommand.relay.wayline.phase.MissionExecutionSignalListener
import com.skycommand.relay.wayline.phase.MissionExecutionSignalRegistration
import com.skycommand.relay.wayline.phase.MissionExecutionSignalSource
import com.skycommand.relay.wayline.staging.MissionMetadata
import com.skycommand.relay.wayline.uploader.MissionUploadPort
import com.skycommand.relay.wayline.uploader.UploadCompletion
import com.skycommand.relay.settings.command.CameraSettings
import com.skycommand.relay.settings.command.CameraSettingsPatch
import com.skycommand.relay.settings.command.SettingsRequest
import com.skycommand.relay.settings.command.SettingsSnapshot
import com.skycommand.relay.settings.command.TransmissionSettings
import com.skycommand.relay.settings.command.TransmissionSettingsPatch
import com.skycommand.relay.settings.executor.DjiSettingsPort
import com.skycommand.relay.settings.executor.SettingsDjiCompletion
import com.skycommand.relay.stream.config.ValidatedStreamConfig
import com.skycommand.relay.stream.dji.DjiStreamPort
import com.skycommand.relay.stream.dji.StreamDjiCompletion
import com.skycommand.relay.stream.state.StreamMetrics
import com.skycommand.relay.device.sdk.DjiSdkCallbacks
import com.skycommand.relay.device.sdk.DjiSdkPort
import com.skycommand.relay.device.sdk.PortStartResult
import com.skycommand.relay.device.remote.PortSubscription
import com.skycommand.relay.device.remote.RemoteControllerListener
import com.skycommand.relay.device.remote.RemoteControllerPort
import com.skycommand.relay.device.remote.RemoteControllerSignal
import com.skycommand.relay.device.aircraft.AircraftListener
import com.skycommand.relay.device.aircraft.AircraftPort
import com.skycommand.relay.device.aircraft.AircraftPortSubscription
import com.skycommand.relay.device.aircraft.AircraftSignal
import com.skycommand.relay.device.pairing.PairingPort
import com.skycommand.relay.device.pairing.status.PairingStatusListener
import com.skycommand.relay.device.pairing.status.PairingStatusPort
import com.skycommand.relay.device.pairing.status.PairingStatusSignal
import com.skycommand.relay.device.pairing.status.PairingStatusSubscription
import com.skycommand.relay.device.state.PairingState
import com.skycommand.relay.device.operation.DjiOperation
import com.skycommand.relay.telemetry.flight.FlightTelemetryRegistration
import com.skycommand.relay.telemetry.flight.FlightTelemetrySource
import com.skycommand.relay.telemetry.snapshot.FlightTelemetrySnapshot
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 场景的不可变起点；后续版本在此集中增加设备事实和操作计划。 */
class SimulationDjiPlan private constructor(
    private val flightOperations: Map<FlightAction, List<SimulationOperation>>,
    private val uploadOperations: List<SimulationOperation>,
    private val missionOperations: Map<SimulationMissionCommand, List<SimulationOperation>>,
    private val settingsOperations: Map<SimulationSettingsOperation, List<SimulationOperation>>,
    private val streamOperations: Map<SimulationStreamOperation, List<SimulationOperation>>,
) {
    internal fun nextFlight(action: FlightAction): SimulationOperation =
        flightOperations[action]?.firstOrNull() ?: SimulationOperation.Fail(Duration.ZERO)

    internal fun remainingFlight(action: FlightAction): List<SimulationOperation> =
        flightOperations[action].orEmpty()
    internal fun remainingUpload(): List<SimulationOperation> = uploadOperations
    internal fun remainingMission(command: SimulationMissionCommand): List<SimulationOperation> = missionOperations[command].orEmpty()
    internal fun remainingSettings(operation: SimulationSettingsOperation): List<SimulationOperation> =
        settingsOperations[operation].orEmpty()
    internal fun remainingStream(operation: SimulationStreamOperation): List<SimulationOperation> =
        streamOperations[operation].orEmpty()

    companion object {
        fun empty(): SimulationDjiPlan = SimulationDjiPlan(emptyMap(), emptyList(), emptyMap(), emptyMap(), emptyMap())
        fun builder(): Builder = Builder()
    }

    class Builder {
        private val flight = mutableMapOf<FlightAction, MutableList<SimulationOperation>>()
        private val upload = mutableListOf<SimulationOperation>()
        private val mission = mutableMapOf<SimulationMissionCommand, MutableList<SimulationOperation>>()
        private val settings = mutableMapOf<SimulationSettingsOperation, MutableList<SimulationOperation>>()
        private val stream = mutableMapOf<SimulationStreamOperation, MutableList<SimulationOperation>>()

        fun flight(action: FlightAction, operation: SimulationOperation): Builder = apply {
            flight.getOrPut(action) { mutableListOf() } += operation
        }

        fun upload(operation: SimulationOperation): Builder = apply { upload += operation }

        fun mission(command: SimulationMissionCommand, operation: SimulationOperation): Builder = apply {
            mission.getOrPut(command) { mutableListOf() } += operation
        }

        fun settings(operation: SimulationSettingsOperation, result: SimulationOperation): Builder = apply {
            settings.getOrPut(operation) { mutableListOf() } += result
        }

        fun stream(operation: SimulationStreamOperation, result: SimulationOperation): Builder = apply {
            stream.getOrPut(operation) { mutableListOf() } += result
        }

        fun build(): SimulationDjiPlan = SimulationDjiPlan(
            flight.mapValues { (_, values) -> values.toList() },
            upload.toList(),
            mission.mapValues { (_, values) -> values.toList() },
            settings.mapValues { (_, values) -> values.toList() },
            stream.mapValues { (_, values) -> values.toList() },
        )
    }
}

sealed interface SimulationOperation {
    val delay: Duration

    data class Succeed(override val delay: Duration = Duration.ZERO) : SimulationOperation
    data class Fail(override val delay: Duration = Duration.ZERO) : SimulationOperation
    data object Reject : SimulationOperation { override val delay: Duration = Duration.ZERO }
    data class Throw(val message: String = "simulated failure") : SimulationOperation { override val delay: Duration = Duration.ZERO }
    data class Silent(override val delay: Duration = Duration.ZERO) : SimulationOperation
    data class Duplicate(
        val firstDelay: Duration = Duration.ZERO,
        val secondDelay: Duration = Duration.ZERO,
    ) : SimulationOperation { override val delay: Duration = firstDelay }
    data class Late(override val delay: Duration) : SimulationOperation
}

enum class SimulationMissionCommand { START, PAUSE, RESUME, STOP }

enum class SimulationSettingsOperation { CAMERA_READ, CAMERA_WRITE, TRANSMISSION_READ, TRANSMISSION_WRITE }

enum class SimulationStreamOperation { START, STOP }

/** 端到端验证使用的单调、手动推进时钟。 */
class ManualSimulationClock {
    private val lock = ReentrantLock()
    private var elapsed = Duration.ZERO

    fun now(): Duration = lock.withLock { elapsed }

    fun advanceBy(duration: Duration) {
        require(!duration.isNegative) { "duration must not be negative" }
        lock.withLock { elapsed += duration }
    }
}

data class SimulationDjiSnapshot(
    val closed: Boolean,
    val pendingEventCount: Int,
    val deliveredMarkers: List<String>,
    val flight: SimulationFlightFact,
    val missionFileName: String?,
    val streamMediaReady: Boolean,
)

data class SimulationFlightFact(val isFlying: Boolean? = null)

class SimulationDjiPorts internal constructor(
    val flight: DjiFlightPort,
    val missionUpload: MissionUploadPort,
    val missionControl: MissionControlPort,
    val executionSignals: MissionExecutionSignalSource,
    val settings: DjiSettingsPort,
    val stream: DjiStreamPort,
    val sdk: DjiSdkPort,
    val remoteController: RemoteControllerPort,
    val aircraft: AircraftPort,
    val pairing: PairingPort,
    val pairingStatus: PairingStatusPort,
    val telemetry: FlightTelemetrySource,
)

sealed interface SimulationInjection {
    data class Marker(val name: String, val after: Duration) : SimulationInjection {
        init {
            require(name.isNotBlank() && name.none(Char::isISOControl)) { "marker name is invalid" }
            require(!after.isNegative) { "marker delay must not be negative" }
        }
    }

    data class MissionSignal(val signal: MissionExecutionSignal, val after: Duration) : SimulationInjection {
        init {
            require(!after.isNegative) { "mission signal delay must not be negative" }
        }
    }
}

enum class SimulationInjectionResult { Applied, Rejected, Ignored }

class SimulationDjiAdapter private constructor(
    @Suppress("unused") private val plan: SimulationDjiPlan,
    private val clock: ManualSimulationClock,
) {
    private val lock = ReentrantLock()
    private var closed = false
    private var sequence = 0L
    private val scheduled = java.util.PriorityQueue<Scheduled>()
    private val deliveredMarkers = mutableListOf<String>()
    private val remainingFlight = plan.run {
        FlightAction.entries.associateWith { ArrayDeque(remainingFlight(it)) }.toMutableMap()
    }
    private val remainingUpload = ArrayDeque(plan.remainingUpload())
    private val remainingMission = SimulationMissionCommand.entries.associateWith {
        ArrayDeque(plan.remainingMission(it))
    }.toMutableMap()
    private val remainingSettings = SimulationSettingsOperation.entries.associateWith {
        ArrayDeque(plan.remainingSettings(it))
    }.toMutableMap()
    private val remainingStream = SimulationStreamOperation.entries.associateWith {
        ArrayDeque(plan.remainingStream(it))
    }.toMutableMap()
    private var missionFileName: String? = null
    private var cameraSettings = CameraSettings(false, "AUTO", "DEFAULT")
    private var transmissionSettings = TransmissionSettings("2_4_GHZ", "AUTO", "20_MHZ", null)
    private val signalListeners = mutableSetOf<MissionExecutionSignalListener>()
    private val remoteListeners = mutableSetOf<RemoteControllerListener>()
    private val aircraftListeners = mutableSetOf<AircraftListener>()
    private val pairingListeners = mutableSetOf<PairingStatusListener>()
    private val telemetryListeners = mutableSetOf<() -> Unit>()
    private val ports = SimulationDjiPorts(
        flight = SimulatedFlightPort(),
        missionUpload = SimulatedMissionUploadPort(),
        missionControl = SimulatedMissionControlPort(),
        executionSignals = SimulatedExecutionSignals(),
        settings = SimulatedSettingsPort(),
        stream = SimulatedStreamPort(),
        sdk = SimulatedSdkPort(),
        remoteController = SimulatedRemoteControllerPort(),
        aircraft = SimulatedAircraftPort(),
        pairing = SimulatedPairingPort(),
        pairingStatus = SimulatedPairingStatusPort(),
        telemetry = SimulatedTelemetrySource(),
    )

    fun ports(): SimulationDjiPorts = ports

    fun snapshot(): SimulationDjiSnapshot = lock.withLock {
        SimulationDjiSnapshot(
            closed = closed,
            pendingEventCount = scheduled.size,
            deliveredMarkers = deliveredMarkers.toList(),
            flight = SimulationFlightFact(),
            missionFileName = missionFileName,
            streamMediaReady = false,
        )
    }

    fun advanceBy(duration: Duration) {
        if (lock.withLock { closed }) return
        clock.advanceBy(duration)
        val due = lock.withLock {
            buildList {
                while (scheduled.peek()?.dueAt?.let { it <= clock.now() } == true) add(scheduled.remove())
            }
        }
        due.forEach { event ->
            when (event) {
                is Scheduled.Marker -> lock.withLock {
                    if (!closed) deliveredMarkers += event.name
                }
                is Scheduled.Callback -> if (!lock.withLock { closed }) event.callback()
                is Scheduled.Signal -> {
                    val listeners = lock.withLock {
                        if (closed) emptyList() else signalListeners.toList()
                    }
                    listeners.forEach { listener -> runCatching { listener.onSignal(event.signal) } }
                }
            }
        }
    }

    fun advanceUntilIdle() {
        while (true) {
            val next = lock.withLock { if (closed) null else scheduled.peek() } ?: return
            advanceBy(next.dueAt.minus(clock.now()))
        }
    }

    fun inject(injection: SimulationInjection): SimulationInjectionResult = lock.withLock {
        if (closed) return SimulationInjectionResult.Ignored
        when (injection) {
            is SimulationInjection.Marker -> {
                scheduled += Scheduled.Marker(clock.now().plus(injection.after), sequence++, injection.name)
                SimulationInjectionResult.Applied
            }
            is SimulationInjection.MissionSignal -> {
                scheduled += Scheduled.Signal(clock.now().plus(injection.after), sequence++, injection.signal)
                SimulationInjectionResult.Applied
            }
        }
    }

    fun close() {
        lock.withLock {
            closed = true
            scheduled.clear()
        }
    }

    private fun submitFlight(action: FlightAction, completion: FlightDjiCompletion) {
        val operation = lock.withLock {
            check(!closed) { "simulation adapter is closed" }
            remainingFlight.getOrPut(action) { ArrayDeque() }.let { queue ->
                if (queue.isEmpty()) null else queue.removeFirst()
            }
                ?: SimulationOperation.Fail(Duration.ZERO)
        }
        when (operation) {
            SimulationOperation.Reject -> completion.fail()
            is SimulationOperation.Throw -> throw IllegalStateException(operation.message)
            is SimulationOperation.Silent -> Unit
            is SimulationOperation.Succeed -> schedule(operation.delay) { completion.succeed() }
            is SimulationOperation.Fail -> schedule(operation.delay) { completion.fail() }
            is SimulationOperation.Duplicate -> {
                schedule(operation.firstDelay) { completion.succeed() }
                schedule(operation.secondDelay) { completion.succeed() }
            }
            is SimulationOperation.Late -> schedule(operation.delay) { completion.succeed() }
        }
    }

    private fun submitUpload(metadata: MissionMetadata, progress: (Int) -> Unit, completion: UploadCompletion) {
        val operation = lock.withLock {
            check(!closed) { "simulation adapter is closed" }
            if (remainingUpload.isEmpty()) SimulationOperation.Fail(Duration.ZERO) else remainingUpload.removeFirst()
        }
        when (operation) {
            SimulationOperation.Reject -> completion.fail()
            is SimulationOperation.Throw -> throw IllegalStateException(operation.message)
            is SimulationOperation.Silent -> Unit
            is SimulationOperation.Succeed -> schedule(operation.delay) {
                progress(100)
                lock.withLock { if (!closed) missionFileName = metadata.fileName }
                completion.succeed()
            }
            is SimulationOperation.Fail -> schedule(operation.delay) { completion.fail() }
            is SimulationOperation.Duplicate -> {
                repeat(2) { index -> schedule(if (index == 0) operation.firstDelay else operation.secondDelay) {
                    progress(100)
                    lock.withLock { if (!closed) missionFileName = metadata.fileName }
                    completion.succeed()
                } }
            }
            is SimulationOperation.Late -> schedule(operation.delay) {
                progress(100)
                lock.withLock { if (!closed) missionFileName = metadata.fileName }
                completion.succeed()
            }
        }
    }

    private fun submitMission(command: SimulationMissionCommand, completion: ControlCompletion) {
        val operation = lock.withLock {
            check(!closed) { "simulation adapter is closed" }
            remainingMission.getOrPut(command) { ArrayDeque() }.let { queue ->
                if (queue.isEmpty()) SimulationOperation.Fail(Duration.ZERO) else queue.removeFirst()
            }
        }
        when (operation) {
            SimulationOperation.Reject -> completion.fail()
            is SimulationOperation.Throw -> throw IllegalStateException(operation.message)
            is SimulationOperation.Silent -> Unit
            is SimulationOperation.Succeed -> schedule(operation.delay) { completion.succeed() }
            is SimulationOperation.Fail -> schedule(operation.delay) { completion.fail() }
            is SimulationOperation.Duplicate -> {
                schedule(operation.firstDelay) { completion.succeed() }
                schedule(operation.secondDelay) { completion.succeed() }
            }
            is SimulationOperation.Late -> schedule(operation.delay) { completion.succeed() }
        }
    }

    private fun submitSettings(request: SettingsRequest, completion: SettingsDjiCompletion) {
        val category = when (request) {
            is SettingsRequest.Read -> when (request.domain) {
                com.skycommand.relay.settings.command.SettingsDomain.CAMERA -> SimulationSettingsOperation.CAMERA_READ
                com.skycommand.relay.settings.command.SettingsDomain.TRANSMISSION -> SimulationSettingsOperation.TRANSMISSION_READ
            }
            is SettingsRequest.WriteCamera -> SimulationSettingsOperation.CAMERA_WRITE
            is SettingsRequest.WriteTransmission -> SimulationSettingsOperation.TRANSMISSION_WRITE
        }
        val operation = lock.withLock {
            check(!closed) { "simulation adapter is closed" }
            remainingSettings.getOrPut(category) { ArrayDeque() }.let { queue ->
                if (queue.isEmpty()) SimulationOperation.Succeed() else queue.removeFirst()
            }
        }
        val succeed = {
            val snapshot = lock.withLock {
                when (request) {
                    is SettingsRequest.Read -> when (request.domain) {
                        com.skycommand.relay.settings.command.SettingsDomain.CAMERA -> SettingsSnapshot.Camera(cameraSettings)
                        com.skycommand.relay.settings.command.SettingsDomain.TRANSMISSION -> SettingsSnapshot.Transmission(transmissionSettings)
                    }
                    is SettingsRequest.WriteCamera -> {
                        cameraSettings = cameraSettings.copy(
                            autoExposureLockEnabled = request.patch.autoExposureLockEnabled ?: cameraSettings.autoExposureLockEnabled,
                            focusMode = request.patch.focusMode ?: cameraSettings.focusMode,
                        )
                        SettingsSnapshot.Camera(cameraSettings)
                    }
                    is SettingsRequest.WriteTransmission -> {
                        transmissionSettings = transmissionSettings.copy(
                            frequencyBand = request.patch.frequencyBand ?: transmissionSettings.frequencyBand,
                            channelSelectionMode = request.patch.channelSelectionMode ?: transmissionSettings.channelSelectionMode,
                            bandwidth = request.patch.bandwidth ?: transmissionSettings.bandwidth,
                        )
                        SettingsSnapshot.Transmission(transmissionSettings)
                    }
                }
            }
            completion.succeed(snapshot)
        }
        when (operation) {
            SimulationOperation.Reject -> completion.fail()
            is SimulationOperation.Throw -> throw IllegalStateException(operation.message)
            is SimulationOperation.Silent -> Unit
            is SimulationOperation.Fail -> schedule(operation.delay) { completion.fail() }
            is SimulationOperation.Succeed -> schedule(operation.delay, succeed)
            is SimulationOperation.Duplicate -> {
                schedule(operation.firstDelay, succeed)
                schedule(operation.secondDelay, succeed)
            }
            is SimulationOperation.Late -> schedule(operation.delay, succeed)
        }
    }

    private fun submitStream(operationType: SimulationStreamOperation, completion: StreamDjiCompletion) {
        val operation = lock.withLock {
            check(!closed) { "simulation adapter is closed" }
            remainingStream.getOrPut(operationType) { ArrayDeque() }.let { queue ->
                if (queue.isEmpty()) SimulationOperation.Succeed() else queue.removeFirst()
            }
        }
        when (operation) {
            SimulationOperation.Reject -> completion.fail()
            is SimulationOperation.Throw -> throw IllegalStateException(operation.message)
            is SimulationOperation.Silent -> Unit
            is SimulationOperation.Fail -> schedule(operation.delay) { completion.fail() }
            is SimulationOperation.Succeed -> schedule(operation.delay) { completion.succeed() }
            is SimulationOperation.Duplicate -> {
                schedule(operation.firstDelay) { completion.succeed() }
                schedule(operation.secondDelay) { completion.succeed() }
            }
            is SimulationOperation.Late -> schedule(operation.delay) { completion.succeed() }
        }
    }

    private fun schedule(delay: Duration, callback: () -> Unit) {
        require(!delay.isNegative) { "operation delay must not be negative" }
        lock.withLock {
            if (!closed) scheduled += Scheduled.Callback(clock.now().plus(delay), sequence++, callback)
        }
    }

    private inner class SimulatedFlightPort : DjiFlightPort {
        override fun execute(action: FlightAction, completion: FlightDjiCompletion) = submitFlight(action, completion)
    }

    private inner class SimulatedMissionUploadPort : MissionUploadPort {
        override fun upload(metadata: MissionMetadata, bytes: ByteArray, progress: (Int) -> Unit, completion: UploadCompletion) {
            submitUpload(metadata, progress, completion)
        }
    }

    private inner class SimulatedMissionControlPort : MissionControlPort {
        override fun start(completion: ControlCompletion) = submitMission(SimulationMissionCommand.START, completion)
        override fun pause(completion: ControlCompletion) = submitMission(SimulationMissionCommand.PAUSE, completion)
        override fun resume(completion: ControlCompletion) = submitMission(SimulationMissionCommand.RESUME, completion)
        override fun stop(completion: ControlCompletion) = submitMission(SimulationMissionCommand.STOP, completion)
    }

    private inner class SimulatedExecutionSignals : MissionExecutionSignalSource {
        override fun onSignal(listener: MissionExecutionSignalListener): MissionExecutionSignalRegistration {
            lock.withLock { if (!closed) signalListeners += listener }
            return MissionExecutionSignalRegistration { lock.withLock { signalListeners.remove(listener) } }
        }

        override fun beginStartAttempt() = Unit

        override fun confirmStartAttempt() = Unit

        override fun invalidateStartAttempt() = Unit
    }

    private inner class SimulatedSettingsPort : DjiSettingsPort {
        override fun execute(request: SettingsRequest, completion: SettingsDjiCompletion) {
            submitSettings(request, completion)
        }
    }

    private inner class SimulatedStreamPort : DjiStreamPort {
        override fun start(
            config: ValidatedStreamConfig,
            metrics: (StreamMetrics) -> Unit,
            runtimeFailure: () -> Unit,
            completion: StreamDjiCompletion,
        ) {
            check(config.rtmpUrl.isNotBlank()) { "stream URL is required" }
            submitStream(SimulationStreamOperation.START, completion)
        }

        override fun stop(completion: StreamDjiCompletion) {
            submitStream(SimulationStreamOperation.STOP, completion)
        }
    }

    private inner class SimulatedSdkPort : DjiSdkPort {
        override fun initialize(callbacks: DjiSdkCallbacks): PortStartResult {
            schedule(Duration.ZERO) { callbacks.onReady() }
            return PortStartResult.Accepted
        }
        override fun close() = Unit
    }

    private inner class SimulatedRemoteControllerPort : RemoteControllerPort {
        override fun start(listener: RemoteControllerListener): PortSubscription {
            lock.withLock { if (!closed) remoteListeners += listener }
            schedule(Duration.ZERO) { listener.onChanged(RemoteControllerSignal(1, true, "RC-N2")) }
            return PortSubscription { lock.withLock { remoteListeners.remove(listener) } }
        }
        override fun stop() = Unit
    }

    private inner class SimulatedAircraftPort : AircraftPort {
        override fun start(listener: AircraftListener): AircraftPortSubscription {
            lock.withLock { if (!closed) aircraftListeners += listener }
            schedule(Duration.ZERO) { listener.onChanged(AircraftSignal(1, true, true, "SIMULATED")) }
            return AircraftPortSubscription { lock.withLock { aircraftListeners.remove(listener) } }
        }
        override fun stop() = Unit
    }

    private inner class SimulatedPairingPort : PairingPort {
        override fun startPairing(): DjiOperation = DjiOperation { completion -> schedule(Duration.ZERO) { completion.succeed() } }
        override fun stopPairing(): DjiOperation = DjiOperation { completion -> schedule(Duration.ZERO) { completion.succeed() } }
    }

    private inner class SimulatedPairingStatusPort : PairingStatusPort {
        override fun start(listener: PairingStatusListener): PairingStatusSubscription {
            lock.withLock { if (!closed) pairingListeners += listener }
            schedule(Duration.ZERO) { listener.onChanged(PairingStatusSignal(1, PairingState.PAIRED)) }
            return PairingStatusSubscription { lock.withLock { pairingListeners.remove(listener) } }
        }
        override fun stop() = Unit
    }

    private inner class SimulatedTelemetrySource : FlightTelemetrySource {
        override fun snapshot(): FlightTelemetrySnapshot = FlightTelemetrySnapshot(
            isFlying = false,
            motorsOn = false,
            flightMode = "GPS_NORMAL",
            batteryPercent = 80,
        )
        override fun onChanged(listener: () -> Unit): FlightTelemetryRegistration {
            lock.withLock { if (!closed) telemetryListeners += listener }
            return FlightTelemetryRegistration { lock.withLock { telemetryListeners.remove(listener) } }
        }
        override fun close() = Unit
    }

    companion object {
        fun create(plan: SimulationDjiPlan, clock: ManualSimulationClock): SimulationDjiAdapter =
            SimulationDjiAdapter(plan, clock)
    }

    private sealed interface Scheduled : Comparable<Scheduled> {
        val dueAt: Duration
        val sequence: Long

        override fun compareTo(other: Scheduled): Int = compareValuesBy(this, other, Scheduled::dueAt, Scheduled::sequence)

        data class Marker(
            override val dueAt: Duration,
            override val sequence: Long,
            val name: String,
        ) : Scheduled

        data class Signal(
            override val dueAt: Duration,
            override val sequence: Long,
            val signal: MissionExecutionSignal,
        ) : Scheduled

        data class Callback(
            override val dueAt: Duration,
            override val sequence: Long,
            val callback: () -> Unit,
        ) : Scheduled
    }
}
