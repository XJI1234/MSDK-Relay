package com.skycommand.relay.device

import com.skycommand.relay.device.aircraft.AircraftListener
import com.skycommand.relay.device.aircraft.AircraftPort
import com.skycommand.relay.device.aircraft.AircraftPortSubscription
import com.skycommand.relay.device.aircraft.AircraftSignal
import com.skycommand.relay.device.aircraft.AircraftDiagnosticKind
import com.skycommand.relay.device.operation.DjiOperation
import com.skycommand.relay.device.operation.OperationExecutor
import com.skycommand.relay.device.operation.OperationScheduler
import com.skycommand.relay.device.operation.OperationCancellation
import com.skycommand.relay.device.pairing.PairingPort
import com.skycommand.relay.device.pairing.status.PairingStatusListener
import com.skycommand.relay.device.pairing.status.PairingStatusPort
import com.skycommand.relay.device.pairing.status.PairingStatusSignal
import com.skycommand.relay.device.pairing.status.PairingStatusSubscription
import com.skycommand.relay.device.remote.PortSubscription
import com.skycommand.relay.device.remote.RemoteControllerListener
import com.skycommand.relay.device.remote.RemoteControllerPort
import com.skycommand.relay.device.remote.RemoteControllerSignal
import com.skycommand.relay.device.sdk.DjiSdkCallbacks
import com.skycommand.relay.device.sdk.DjiSdkPort
import com.skycommand.relay.device.sdk.PortStartResult
import com.skycommand.relay.device.remote.RemoteControllerDiagnosticKind
import com.skycommand.relay.device.state.DeviceStateDiagnosticKind
import com.skycommand.relay.device.state.LinkState
import com.skycommand.relay.device.state.PairingState
import com.skycommand.relay.device.state.SdkAvailability
import com.skycommand.relay.device.pairing.status.PairingStatusDiagnosticKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeviceConnectionContractTest {
    @Test
    fun forwardsDeviceStateListenerFailuresToConfiguredDiagnosticSink() {
        val events = mutableListOf<String>()
        val diagnostics = mutableListOf<DeviceStateDiagnosticKind>()
        val sdk = FakeSdk(events)
        val connection = DeviceConnection.create(
            DeviceConnectionDependencies(
                sdkPort = sdk,
                remoteControllerPort = FakeRemote(events),
                aircraftPort = FakeAircraft(events),
                pairingPort = successfulPairingPort(),
                pairingStatusPort = FakePairingStatus(events),
                executor = OperationExecutor { it() },
                scheduler = OperationScheduler { _, _ -> OperationCancellation { } },
                deviceStateDiagnosticSink = { diagnostics += it.kind },
            ),
        )

        connection.onChanged { error("listener failure") }
        connection.start()

        assertEquals(listOf(DeviceStateDiagnosticKind.LISTENER_FAILURE), diagnostics)
    }

    @Test
    fun forwardsRemoteControllerObservationFailuresToConfiguredDiagnosticSink() {
        val events = mutableListOf<String>()
        val diagnostics = mutableListOf<RemoteControllerDiagnosticKind>()
        val sdk = FakeSdk(events)
        val connection = DeviceConnection.create(
            DeviceConnectionDependencies(
                sdkPort = sdk,
                remoteControllerPort = FakeRemote(events, failStart = true),
                aircraftPort = FakeAircraft(events),
                pairingPort = successfulPairingPort(),
                pairingStatusPort = FakePairingStatus(events),
                executor = OperationExecutor { it() },
                scheduler = OperationScheduler { _, _ -> OperationCancellation { } },
                remoteControllerDiagnosticSink = { diagnostics += it.kind },
            ),
        )

        connection.start()
        sdk.ready()

        assertEquals(listOf(RemoteControllerDiagnosticKind.PORT_FAILURE), diagnostics)
    }

    @Test
    fun forwardsPairingObservationFailuresToConfiguredDiagnosticSink() {
        val events = mutableListOf<String>()
        val diagnostics = mutableListOf<PairingStatusDiagnosticKind>()
        val sdk = FakeSdk(events)
        val connection = DeviceConnection.create(
            DeviceConnectionDependencies(
                sdkPort = sdk,
                remoteControllerPort = FakeRemote(events),
                aircraftPort = FakeAircraft(events),
                pairingPort = successfulPairingPort(),
                pairingStatusPort = FakePairingStatus(events, failStart = true),
                executor = OperationExecutor { it() },
                scheduler = OperationScheduler { _, _ -> OperationCancellation { } },
                pairingStatusDiagnosticSink = { diagnostics += it.kind },
            ),
        )

        connection.start()
        sdk.ready()

        assertEquals(listOf(PairingStatusDiagnosticKind.PORT_FAILURE), diagnostics)
    }

    @Test
    fun forwardsAircraftObservationFailuresToConfiguredDiagnosticSink() {
        val events = mutableListOf<String>()
        val diagnostics = mutableListOf<AircraftDiagnosticKind>()
        val sdk = FakeSdk(events)
        val connection = DeviceConnection.create(
            DeviceConnectionDependencies(
                sdkPort = sdk,
                remoteControllerPort = FakeRemote(events),
                aircraftPort = FakeAircraft(events, failStart = true),
                pairingPort = successfulPairingPort(),
                pairingStatusPort = FakePairingStatus(events),
                executor = OperationExecutor { it() },
                scheduler = OperationScheduler { _, _ -> OperationCancellation { } },
                aircraftDiagnosticSink = { diagnostics += it.kind },
            ),
        )

        connection.start()
        sdk.ready()

        assertEquals(listOf(AircraftDiagnosticKind.PORT_FAILURE), diagnostics)
    }

    @Test
    fun refreshHardwareLinksRestartsObserversWithoutStoppingSdk() {
        val events = mutableListOf<String>()
        val sdk = FakeSdk(events)
        val connection = DeviceConnection.create(
            DeviceConnectionDependencies(
                sdkPort = sdk,
                remoteControllerPort = FakeRemote(events),
                aircraftPort = FakeAircraft(events),
                pairingPort = successfulPairingPort(),
                pairingStatusPort = FakePairingStatus(events),
                executor = OperationExecutor { it() },
                scheduler = OperationScheduler { _, _ -> OperationCancellation { } },
            ),
        )

        connection.start()
        sdk.ready()
        connection.refreshHardwareLinks()

        assertEquals(1, events.count { it == "sdk.start" })
        assertEquals(0, events.count { it == "sdk.stop" })
        assertEquals(2, events.count { it == "remote.start" })
        assertEquals(1, events.count { it == "remote.stop" })
        assertEquals(2, events.count { it == "aircraft.start" })
        assertEquals(1, events.count { it == "aircraft.stop" })
        assertEquals(2, events.count { it == "pairing-status.start" })
        assertEquals(1, events.count { it == "pairing-status.stop" })
    }

    @Test
    fun refreshHardwareLinksWhileStoppedDoesNotStartObservers() {
        val events = mutableListOf<String>()
        val connection = DeviceConnection.create(
            DeviceConnectionDependencies(
                sdkPort = FakeSdk(events),
                remoteControllerPort = FakeRemote(events),
                aircraftPort = FakeAircraft(events),
                pairingPort = successfulPairingPort(),
                pairingStatusPort = FakePairingStatus(events),
                executor = OperationExecutor { it() },
                scheduler = OperationScheduler { _, _ -> OperationCancellation { } },
            ),
        )

        connection.refreshHardwareLinks()

        assertEquals(emptyList(), events)
    }

    @Test
    fun startsOnlySdkUntilItReportsReady() {
        val events = mutableListOf<String>()
        val connection = DeviceConnection.create(
            DeviceConnectionDependencies(
                sdkPort = FakeSdk(events),
                remoteControllerPort = FakeRemote(events),
                aircraftPort = FakeAircraft(events),
                pairingPort = successfulPairingPort(),
                pairingStatusPort = FakePairingStatus(events),
                executor = OperationExecutor { it() },
                scheduler = OperationScheduler { _, _ -> OperationCancellation { } },
            ),
        )

        assertIs<DeviceConnectionStartResult.StartAccepted>(connection.start())

        assertEquals(listOf("sdk.start"), events)
        assertEquals(SdkAvailability.STARTING, connection.snapshot().sdkAvailability)
        assertEquals(LinkState.UNKNOWN, connection.snapshot().remoteController)
        assertEquals(LinkState.UNKNOWN, connection.snapshot().aircraft)
        assertEquals(LinkState.UNKNOWN, connection.snapshot().flightController)
        assertEquals(PairingState.UNKNOWN, connection.snapshot().pairing)
    }

    @Test
    fun startsEveryHardwareObservationAfterSdkReportsReady() {
        val events = mutableListOf<String>()
        val sdk = FakeSdk(events)
        val connection = DeviceConnection.create(
            DeviceConnectionDependencies(
                sdkPort = sdk,
                remoteControllerPort = FakeRemote(events),
                aircraftPort = FakeAircraft(events),
                pairingPort = successfulPairingPort(),
                pairingStatusPort = FakePairingStatus(events),
                executor = OperationExecutor { it() },
                scheduler = OperationScheduler { _, _ -> OperationCancellation { } },
            ),
        )

        connection.start()
        sdk.ready()

        assertEquals(
            listOf("sdk.start", "remote.start", "aircraft.start", "pairing-status.start"),
            events,
        )
        assertEquals(SdkAvailability.READY, connection.snapshot().sdkAvailability)
    }

    @Test
    fun refreshHardwareLinksBeforeSdkIsReadyDoesNotTouchHardwareObservers() {
        val events = mutableListOf<String>()
        val connection = DeviceConnection.create(
            DeviceConnectionDependencies(
                sdkPort = FakeSdk(events),
                remoteControllerPort = FakeRemote(events),
                aircraftPort = FakeAircraft(events),
                pairingPort = successfulPairingPort(),
                pairingStatusPort = FakePairingStatus(events),
                executor = OperationExecutor { it() },
                scheduler = OperationScheduler { _, _ -> OperationCancellation { } },
            ),
        )

        connection.start()
        connection.refreshHardwareLinks()

        assertEquals(listOf("sdk.start"), events)
    }

    @Test
    fun keepsSdkAndOtherHardwareObservationsRunningWhenOneObservationFailsAfterReady() {
        val events = mutableListOf<String>()
        val sdk = FakeSdk(events)
        val connection = DeviceConnection.create(
            DeviceConnectionDependencies(
                sdkPort = sdk,
                remoteControllerPort = FakeRemote(events, failStart = true),
                aircraftPort = FakeAircraft(events),
                pairingPort = successfulPairingPort(),
                pairingStatusPort = FakePairingStatus(events),
                executor = OperationExecutor { it() },
                scheduler = OperationScheduler { _, _ -> OperationCancellation { } },
            ),
        )

        assertIs<DeviceConnectionStartResult.StartAccepted>(connection.start())
        sdk.ready()

        assertEquals(
            listOf("sdk.start", "remote.start", "aircraft.start", "pairing-status.start"),
            events,
        )
        assertEquals(SdkAvailability.READY, connection.snapshot().sdkAvailability)
        assertEquals(LinkState.UNKNOWN, connection.snapshot().remoteController)
    }

    @Test
    fun startsPairingStatusObservationAndDropsItsLateCallbackAfterStop() {
        val events = mutableListOf<String>()
        val sdk = FakeSdk(events)
        val remote = FakeRemote(events)
        val aircraft = FakeAircraft(events)
        val pairingStatus = FakePairingStatus(events)
        val connection = DeviceConnection.create(
            DeviceConnectionDependencies(
                sdkPort = sdk,
                remoteControllerPort = remote,
                aircraftPort = aircraft,
                pairingPort = object : PairingPort {
                    override fun startPairing(): DjiOperation = DjiOperation { it.succeed() }
                    override fun stopPairing(): DjiOperation = DjiOperation { it.succeed() }
                },
                pairingStatusPort = pairingStatus,
                executor = OperationExecutor { it() },
                scheduler = OperationScheduler { _, _ -> OperationCancellation { } },
            ),
        )

        assertIs<DeviceConnectionStartResult.StartAccepted>(connection.start())
        assertEquals(
            listOf("sdk.start"),
            events,
        )
        assertEquals(SdkAvailability.STARTING, connection.snapshot().sdkAvailability)
        sdk.ready()
        assertEquals(
            listOf("sdk.start", "remote.start", "aircraft.start", "pairing-status.start"),
            events,
        )
        remote.emit(RemoteControllerSignal(1, true, "RC"))
        aircraft.emit(AircraftSignal(1, true, true, "Matrice"))
        pairingStatus.emit(PairingStatusSignal(1, PairingState.PAIRED))

        assertEquals(SdkAvailability.READY, connection.snapshot().sdkAvailability)
        assertEquals(LinkState.CONNECTED, connection.snapshot().remoteController)
        assertEquals(PairingState.PAIRED, connection.snapshot().pairing)
        assertEquals(true, connection.capabilities().canReadTelemetry)
        assertIs<DeviceConnectionStopResult.Stopped>(connection.stop())
        assertEquals(
            listOf(
                "sdk.start",
                "remote.start",
                "aircraft.start",
                "pairing-status.start",
                "pairing-status.stop",
                "aircraft.stop",
                "remote.stop",
                "sdk.stop",
            ),
            events,
        )
        pairingStatus.emitLate(PairingStatusSignal(2, PairingState.PAIRED))
        assertEquals(SdkAvailability.STOPPED, connection.snapshot().sdkAvailability)
        assertEquals(LinkState.UNKNOWN, connection.snapshot().remoteController)
        assertEquals(LinkState.UNKNOWN, connection.snapshot().aircraft)
        assertEquals(LinkState.UNKNOWN, connection.snapshot().flightController)
        assertEquals(PairingState.UNKNOWN, connection.snapshot().pairing)
        assertIs<DeviceConnectionStopResult.AlreadyStopped>(connection.stop())
        assertEquals(
            listOf(
                "sdk.start",
                "remote.start",
                "aircraft.start",
                "pairing-status.start",
                "pairing-status.stop",
                "aircraft.stop",
                "remote.stop",
                "sdk.stop",
            ),
            events,
        )
    }

    @Test
    fun serializesStopAgainstAnInProgressStart() {
        val events = mutableListOf<String>()
        val sdkStopped = CountDownLatch(1)
        val sdk = FakeSdk(events, sdkStopped)
        val remote = BlockingRemote(events)
        val connection = DeviceConnection.create(
            DeviceConnectionDependencies(
                sdkPort = sdk,
                remoteControllerPort = remote,
                aircraftPort = FakeAircraft(events),
                pairingPort = successfulPairingPort(),
                pairingStatusPort = FakePairingStatus(events),
                executor = OperationExecutor { it() },
                scheduler = OperationScheduler { _, _ -> OperationCancellation { } },
            ),
        )
        val workers = Executors.newFixedThreadPool(2)

        try {
            assertIs<DeviceConnectionStartResult.StartAccepted>(connection.start())
            val ready = workers.submit { sdk.ready() }
            assertTrue(remote.awaitStart())
            val stop = workers.submit<DeviceConnectionStopResult> { connection.stop() }

            assertFalse(sdkStopped.await(250, TimeUnit.MILLISECONDS))
            remote.releaseStart()

            ready.get(5, TimeUnit.SECONDS)
            assertIs<DeviceConnectionStopResult.Stopped>(stop.get(5, TimeUnit.SECONDS))
            assertEquals(SdkAvailability.STOPPED, connection.snapshot().sdkAvailability)
            assertEquals(
                listOf(
                    "sdk.start",
                    "remote.start",
                    "aircraft.start",
                    "pairing-status.start",
                    "pairing-status.stop",
                    "aircraft.stop",
                    "remote.stop",
                    "sdk.stop",
                ),
                events,
            )
        } finally {
            workers.shutdownNow()
        }
    }

    private class FakeSdk(
        private val events: MutableList<String>,
        private val stopped: CountDownLatch? = null,
    ) : DjiSdkPort {
        private var callbacks: DjiSdkCallbacks? = null
        override fun initialize(callbacks: DjiSdkCallbacks): PortStartResult {
            events += "sdk.start"
            this.callbacks = callbacks
            return PortStartResult.Accepted
        }
        override fun close() {
            events += "sdk.stop"
            stopped?.countDown()
        }
        fun ready() = checkNotNull(callbacks).onReady()
    }

    private fun successfulPairingPort(): PairingPort = object : PairingPort {
        override fun startPairing(): DjiOperation = DjiOperation { it.succeed() }

        override fun stopPairing(): DjiOperation = DjiOperation { it.succeed() }
    }

    private class FakeRemote(
        private val events: MutableList<String>,
        private val failStart: Boolean = false,
    ) : RemoteControllerPort {
        private var listener: RemoteControllerListener? = null
        override fun start(listener: RemoteControllerListener): PortSubscription {
            events += "remote.start"
            if (failStart) error("unavailable")
            this.listener = listener
            return PortSubscription { this.listener = null }
        }
        override fun stop() {
            events += "remote.stop"
            listener = null
        }
        fun emit(signal: RemoteControllerSignal) = listener?.onChanged(signal)
    }

    private class FakeAircraft(
        private val events: MutableList<String>,
        private val failStart: Boolean = false,
    ) : AircraftPort {
        private var listener: AircraftListener? = null
        override fun start(listener: AircraftListener): AircraftPortSubscription {
            events += "aircraft.start"
            if (failStart) error("unavailable")
            this.listener = listener
            return AircraftPortSubscription { this.listener = null }
        }
        override fun stop() {
            events += "aircraft.stop"
            listener = null
        }
        fun emit(signal: AircraftSignal) = listener?.onChanged(signal)
    }

    private class BlockingRemote(private val events: MutableList<String>) : RemoteControllerPort {
        private val startEntered = CountDownLatch(1)
        private val allowStartToFinish = CountDownLatch(1)
        private var listener: RemoteControllerListener? = null

        override fun start(listener: RemoteControllerListener): PortSubscription {
            events += "remote.start"
            startEntered.countDown()
            check(allowStartToFinish.await(5, TimeUnit.SECONDS))
            this.listener = listener
            return PortSubscription { this.listener = null }
        }

        override fun stop() {
            events += "remote.stop"
            listener = null
        }

        fun awaitStart(): Boolean = startEntered.await(5, TimeUnit.SECONDS)

        fun releaseStart() {
            allowStartToFinish.countDown()
        }
    }

    private class FakePairingStatus(
        private val events: MutableList<String>,
        private val failStart: Boolean = false,
    ) : PairingStatusPort {
        private var listener: PairingStatusListener? = null
        private var latestListener: PairingStatusListener? = null

        override fun start(listener: PairingStatusListener): PairingStatusSubscription {
            events += "pairing-status.start"
            if (failStart) error("unavailable")
            this.listener = listener
            latestListener = listener
            return PairingStatusSubscription { this.listener = null }
        }

        override fun stop() {
            events += "pairing-status.stop"
            listener = null
        }

        fun emit(signal: PairingStatusSignal) = listener?.onChanged(signal)

        fun emitLate(signal: PairingStatusSignal) = latestListener?.onChanged(signal)
    }
}
