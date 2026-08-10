package com.skycommand.relay.telemetry.command

import com.skycommand.relay.device.state.DeviceSnapshot
import com.skycommand.relay.device.state.LinkState
import com.skycommand.relay.device.state.PairingState
import com.skycommand.relay.device.state.SdkAvailability
import com.skycommand.relay.telemetry.snapshot.TelemetrySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TelemetryCommandHandlerContractTest {
    @Test
    fun readsAndAssemblesExactlyOneCurrentSnapshotPerRequest() {
        val source = RecordingSource()
        val handler = TelemetryCommandHandler.create(source)

        val first = assertIs<TelemetryReadResult.ReadSucceeded>(handler.read())
        source.current = source.current.copy(revision = 2, aircraft = LinkState.CONNECTED)
        val second = assertIs<TelemetryReadResult.ReadSucceeded>(handler.read())

        assertEquals(1, first.snapshot.deviceRevision)
        assertEquals(2, second.snapshot.deviceRevision)
        assertEquals(2, source.reads)
    }

    @Test
    fun containsSourceFailuresAndReturnsNoInternalErrorDetails() {
        val handler = TelemetryCommandHandler.create(SnapshotSource { error("private device failure") })

        assertEquals(TelemetryReadResult.ReadUnavailable, handler.read())
    }

    private class RecordingSource : SnapshotSource {
        var reads = 0
        var current = DeviceSnapshot(
            revision = 1,
            sdkAvailability = SdkAvailability.READY,
            remoteController = LinkState.CONNECTED,
            aircraft = LinkState.DISCONNECTED,
            flightController = LinkState.DISCONNECTED,
            pairing = PairingState.IDLE,
            remoteControllerModel = "RC",
            aircraftModel = null,
        )

        override fun snapshot(): DeviceSnapshot {
            reads += 1
            return current
        }
    }
}
