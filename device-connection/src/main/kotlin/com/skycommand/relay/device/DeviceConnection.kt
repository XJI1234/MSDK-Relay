package com.skycommand.relay.device

import com.skycommand.relay.device.aircraft.AircraftLink
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
import com.skycommand.relay.device.remote.RemoteControllerLink
import com.skycommand.relay.device.remote.RemoteControllerPort
import com.skycommand.relay.device.sdk.DjiSdkPort
import com.skycommand.relay.device.sdk.SdkLifecycle
import com.skycommand.relay.device.sdk.StartResult
import com.skycommand.relay.device.sdk.StopResult
import com.skycommand.relay.device.state.DeviceSnapshot
import com.skycommand.relay.device.state.DeviceStateListener
import com.skycommand.relay.device.state.DeviceStateStore
import com.skycommand.relay.device.state.Registration

data class DeviceConnectionDependencies(
    val sdkPort: DjiSdkPort,
    val remoteControllerPort: RemoteControllerPort,
    val aircraftPort: AircraftPort,
    val pairingPort: PairingPort,
    val executor: OperationExecutor,
    val scheduler: OperationScheduler,
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
    private val store = DeviceStateStore.create()
    private val lifecycle = SdkLifecycle.create(dependencies.sdkPort)
    private val remoteControllerLink = RemoteControllerLink.create(store, dependencies.remoteControllerPort)
    private val aircraftLink = AircraftLink.create(store, dependencies.aircraftPort)
    private val operations = DjiOperationCoordinator.create(dependencies.executor, dependencies.scheduler)
    private val pairing = PairingController.create(store, operations, dependencies.pairingPort)

    init {
        lifecycle.onChanged { store.applySdk(it) }
    }

    fun start(): DeviceConnectionStartResult = when (val result = lifecycle.start()) {
        StartResult.StartAccepted -> {
            val remote = remoteControllerLink.start()
            val aircraft = aircraftLink.start()
            if (remote is com.skycommand.relay.device.remote.RemoteControllerStartResult.Rejected ||
                aircraft is com.skycommand.relay.device.aircraft.AircraftStartResult.Rejected
            ) {
                aircraftLink.stop()
                remoteControllerLink.stop()
                lifecycle.stop()
                DeviceConnectionStartResult.StartRejected("device listener unavailable")
            } else {
                DeviceConnectionStartResult.StartAccepted
            }
        }

        is StartResult.AlreadyRunning -> DeviceConnectionStartResult.AlreadyRunning
        is StartResult.StartRejected -> DeviceConnectionStartResult.StartRejected(result.safeReason)
    }

    fun stop(): DeviceConnectionStopResult {
        val lifecycleResult = lifecycle.stop()
        aircraftLink.stop()
        remoteControllerLink.stop()
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

    fun operations(): DjiOperationCoordinator = operations

    fun requestPairingStart(
        timeoutMillis: Long,
        listener: (PairingOperationResult) -> Unit,
    ): PairingRequestResult = pairing.start(timeoutMillis, listener)

    fun requestPairingStop(
        timeoutMillis: Long,
        listener: (PairingOperationResult) -> Unit,
    ): PairingRequestResult = pairing.stop(timeoutMillis, listener)

    companion object {
        fun create(dependencies: DeviceConnectionDependencies): DeviceConnection = DeviceConnection(dependencies)
    }
}
