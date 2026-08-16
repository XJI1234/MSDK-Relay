package com.skycommand.relay.device

import com.skycommand.relay.device.aircraft.AircraftLink
import com.skycommand.relay.device.aircraft.AircraftDiagnosticSink
import com.skycommand.relay.device.aircraft.AircraftPort
import com.skycommand.relay.device.capability.DeviceCapabilityReader
import com.skycommand.relay.device.capability.DeviceCapabilities
import com.skycommand.relay.device.operation.DjiOperationCoordinator
import com.skycommand.relay.device.operation.OperationExecutor
import com.skycommand.relay.device.operation.OperationScheduler
import com.skycommand.relay.device.pairing.PairingController
import com.skycommand.relay.device.pairing.PairingOperationResult
import com.skycommand.relay.device.pairing.PairingPort
import com.skycommand.relay.device.pairing.PairingRequestResult
import com.skycommand.relay.device.pairing.status.PairingStatusLink
import com.skycommand.relay.device.pairing.status.PairingStatusDiagnosticSink
import com.skycommand.relay.device.pairing.status.PairingStatusPort
import com.skycommand.relay.device.remote.RemoteControllerLink
import com.skycommand.relay.device.remote.RemoteControllerDiagnosticSink
import com.skycommand.relay.device.remote.RemoteControllerPort
import com.skycommand.relay.device.sdk.DjiSdkPort
import com.skycommand.relay.device.sdk.SdkLifecycle
import com.skycommand.relay.device.sdk.SdkLifecycleDiagnosticSink
import com.skycommand.relay.device.sdk.StartResult
import com.skycommand.relay.device.sdk.StopResult
import com.skycommand.relay.device.state.DeviceSnapshot
import com.skycommand.relay.device.state.DeviceStateDiagnosticSink
import com.skycommand.relay.device.state.DeviceStateListener
import com.skycommand.relay.device.state.DeviceStateStore
import com.skycommand.relay.device.state.Registration
import com.skycommand.relay.device.state.SdkAvailability
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class DeviceConnectionDependencies(
    val sdkPort: DjiSdkPort,
    val remoteControllerPort: RemoteControllerPort,
    val aircraftPort: AircraftPort,
    val pairingPort: PairingPort,
    val pairingStatusPort: PairingStatusPort,
    val executor: OperationExecutor,
    val scheduler: OperationScheduler,
    val sdkDiagnosticSink: SdkLifecycleDiagnosticSink = SdkLifecycleDiagnosticSink { },
    val deviceStateDiagnosticSink: DeviceStateDiagnosticSink = DeviceStateDiagnosticSink { },
    val remoteControllerDiagnosticSink: RemoteControllerDiagnosticSink = RemoteControllerDiagnosticSink { },
    val pairingStatusDiagnosticSink: PairingStatusDiagnosticSink = PairingStatusDiagnosticSink { },
    val aircraftDiagnosticSink: AircraftDiagnosticSink = AircraftDiagnosticSink { },
)

sealed interface DeviceConnectionStartResult {
    data object StartAccepted : DeviceConnectionStartResult

    data object AlreadyRunning : DeviceConnectionStartResult

    data class StartRejected(val safeReason: String) : DeviceConnectionStartResult
}

sealed interface DeviceConnectionStopResult {
    data object Stopped : DeviceConnectionStopResult

    data object AlreadyStopped : DeviceConnectionStopResult
}

class DeviceConnection private constructor(dependencies: DeviceConnectionDependencies) {
    private val lifecycleLock = ReentrantLock()
    private val store = DeviceStateStore.create(dependencies.deviceStateDiagnosticSink)
    private val lifecycle = SdkLifecycle.create(dependencies.sdkPort, dependencies.sdkDiagnosticSink)
    private val remoteControllerLink = RemoteControllerLink.create(
        store,
        dependencies.remoteControllerPort,
        dependencies.remoteControllerDiagnosticSink,
    )
    private val aircraftLink = AircraftLink.create(
        store,
        dependencies.aircraftPort,
        dependencies.aircraftDiagnosticSink,
    )
    private val operations = DjiOperationCoordinator.create(dependencies.executor, dependencies.scheduler)
    private val pairing = PairingController.create(store, operations, dependencies.pairingPort)
    private val pairingStatusLink = PairingStatusLink.create(
        store,
        dependencies.pairingStatusPort,
        dependencies.pairingStatusDiagnosticSink,
    )

    init {
        lifecycle.onChanged { store.applySdk(it) }
    }

    fun start(): DeviceConnectionStartResult = lifecycleLock.withLock {
        when (val result = lifecycle.start()) {
            StartResult.StartAccepted -> {
                val remote = remoteControllerLink.start()
                if (remote is com.skycommand.relay.device.remote.RemoteControllerStartResult.Rejected) {
                    rejectFailedStart()
                } else {
                    val aircraft = aircraftLink.start()
                    if (aircraft is com.skycommand.relay.device.aircraft.AircraftStartResult.Rejected) {
                        rejectFailedStart()
                    } else {
                        when (pairingStatusLink.start()) {
                            is com.skycommand.relay.device.pairing.status.PairingStatusStartResult.Rejected -> rejectFailedStart()
                            else -> DeviceConnectionStartResult.StartAccepted
                        }
                    }
                }
            }

            is StartResult.AlreadyRunning -> DeviceConnectionStartResult.AlreadyRunning
            is StartResult.StartRejected -> DeviceConnectionStartResult.StartRejected(result.safeReason)
        }
    }

    fun stop(): DeviceConnectionStopResult = lifecycleLock.withLock {
        pairingStatusLink.stop()
        aircraftLink.stop()
        remoteControllerLink.stop()
        val lifecycleResult = lifecycle.stop()
        store.markRuntimeUnavailable()
        return if (lifecycleResult is StopResult.AlreadyStopped) {
            DeviceConnectionStopResult.AlreadyStopped
        } else {
            DeviceConnectionStopResult.Stopped
        }
    }

    fun snapshot(): DeviceSnapshot = store.snapshot()

    fun capabilities(): DeviceCapabilities = DeviceCapabilityReader.read(snapshot())

    fun onChanged(listener: DeviceStateListener): Registration = store.onChanged(listener)

    fun refreshHardwareLinks() {
        lifecycleLock.withLock {
            if (store.snapshot().sdkAvailability == SdkAvailability.STOPPED) return@withLock
            pairingStatusLink.stop()
            aircraftLink.stop()
            remoteControllerLink.stop()
            remoteControllerLink.start()
            aircraftLink.start()
            pairingStatusLink.start()
        }
    }

    fun operations(): DjiOperationCoordinator = operations

    fun requestPairingStart(
        timeoutMillis: Long,
        listener: (PairingOperationResult) -> Unit,
    ): PairingRequestResult = pairing.start(timeoutMillis, listener)

    fun requestPairingStop(
        timeoutMillis: Long,
        listener: (PairingOperationResult) -> Unit,
    ): PairingRequestResult = pairing.stop(timeoutMillis, listener)

    private fun rejectFailedStart(): DeviceConnectionStartResult {
        pairingStatusLink.stop()
        aircraftLink.stop()
        remoteControllerLink.stop()
        lifecycle.stop()
        store.markRuntimeUnavailable()
        return DeviceConnectionStartResult.StartRejected("device listener unavailable")
    }

    companion object {
        fun create(dependencies: DeviceConnectionDependencies): DeviceConnection = DeviceConnection(dependencies)
    }
}
