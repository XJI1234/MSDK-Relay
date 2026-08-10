package com.skycommand.relay.device

import com.skycommand.relay.device.aircraft.AircraftListener
import com.skycommand.relay.device.aircraft.AircraftPort
import com.skycommand.relay.device.aircraft.AircraftPortSubscription
import com.skycommand.relay.device.aircraft.AircraftSignal
import com.skycommand.relay.device.operation.DjiOperation
import com.skycommand.relay.device.operation.OperationExecutor
import com.skycommand.relay.device.operation.OperationScheduler
import com.skycommand.relay.device.operation.OperationCancellation
import com.skycommand.relay.device.pairing.PairingPort
import com.skycommand.relay.device.remote.PortSubscription
import com.skycommand.relay.device.remote.RemoteControllerListener
import com.skycommand.relay.device.remote.RemoteControllerPort
import com.skycommand.relay.device.remote.RemoteControllerSignal
import com.skycommand.relay.device.sdk.DjiSdkCallbacks
import com.skycommand.relay.device.sdk.DjiSdkPort
import com.skycommand.relay.device.sdk.PortStartResult
import com.skycommand.relay.device.state.LinkState
import com.skycommand.relay.device.state.SdkAvailability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DeviceConnectionContractTest {
    @Test
    fun composesLifecycleLinksStateAndCapabilitiesWithoutLeakingPorts() {
        val sdk = FakeSdk()
        val remote = FakeRemote()
        val aircraft = FakeAircraft()
        val connection = DeviceConnection.create(
            DeviceConnectionDependencies(
                sdkPort = sdk,
                remoteControllerPort = remote,
                aircraftPort = aircraft,
                pairingPort = object : PairingPort {
                    override fun startPairing(): DjiOperation = DjiOperation { it.succeed() }
                    override fun stopPairing(): DjiOperation = DjiOperation { it.succeed() }
                },
                executor = OperationExecutor { it() },
                scheduler = OperationScheduler { _, _ -> OperationCancellation { } },
            ),
        )

        assertIs<DeviceConnectionStartResult.StartAccepted>(connection.start())
        assertEquals(SdkAvailability.STARTING, connection.snapshot().sdkAvailability)
        sdk.ready()
        remote.emit(RemoteControllerSignal(1, true, "RC"))
        aircraft.emit(AircraftSignal(1, true, true, "Matrice"))

        assertEquals(SdkAvailability.READY, connection.snapshot().sdkAvailability)
        assertEquals(LinkState.CONNECTED, connection.snapshot().remoteController)
        assertEquals(true, connection.capabilities().canReadTelemetry)
        assertIs<DeviceConnectionStopResult.Stopped>(connection.stop())
        assertEquals(SdkAvailability.STOPPED, connection.snapshot().sdkAvailability)
        assertEquals(LinkState.DISCONNECTED, connection.snapshot().remoteController)
        assertEquals(LinkState.DISCONNECTED, connection.snapshot().aircraft)
        assertEquals(LinkState.DISCONNECTED, connection.snapshot().flightController)
    }

    private class FakeSdk : DjiSdkPort {
        private var callbacks: DjiSdkCallbacks? = null
        override fun initialize(callbacks: DjiSdkCallbacks): PortStartResult {
            this.callbacks = callbacks
            return PortStartResult.Accepted
        }
        override fun close() = Unit
        fun ready() = checkNotNull(callbacks).onReady()
    }

    private class FakeRemote : RemoteControllerPort {
        private var listener: RemoteControllerListener? = null
        override fun start(listener: RemoteControllerListener): PortSubscription {
            this.listener = listener
            return PortSubscription { this.listener = null }
        }
        override fun stop() { listener = null }
        fun emit(signal: RemoteControllerSignal) = listener?.onChanged(signal)
    }

    private class FakeAircraft : AircraftPort {
        private var listener: AircraftListener? = null
        override fun start(listener: AircraftListener): AircraftPortSubscription {
            this.listener = listener
            return AircraftPortSubscription { this.listener = null }
        }
        override fun stop() { listener = null }
        fun emit(signal: AircraftSignal) = listener?.onChanged(signal)
    }
}
