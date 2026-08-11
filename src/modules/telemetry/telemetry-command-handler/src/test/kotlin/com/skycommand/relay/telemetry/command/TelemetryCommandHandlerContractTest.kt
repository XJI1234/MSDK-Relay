package com.skycommand.relay.telemetry.command

import com.skycommand.relay.device.state.DeviceSnapshot
import com.skycommand.relay.device.state.LinkState
import com.skycommand.relay.device.state.PairingState
import com.skycommand.relay.device.state.SdkAvailability
import com.skycommand.relay.telemetry.snapshot.TelemetrySnapshot
import com.skycommand.relay.telemetry.snapshot.FlightTelemetrySnapshot
import com.skycommand.relay.telemetry.snapshot.TelemetryInputs
import com.skycommand.relay.stream.state.StreamLifecycleState
import com.skycommand.relay.stream.state.StreamSnapshot
import com.skycommand.relay.wayline.state.ExecutionState
import com.skycommand.relay.wayline.state.MissionSnapshot
import com.skycommand.relay.wayline.state.UploadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TelemetryCommandHandlerContractTest {
    @Test
    fun readsAndAssemblesExactlyOneCurrentSnapshotPerRequest() {
        val source = RecordingSource()
        val handler = TelemetryCommandHandler.create(source)

        val first = assertIs<TelemetryReadResult.ReadSucceeded>(handler.read())
        source.current = source.current.copy(device = source.current.device.copy(revision = 2, aircraft = LinkState.CONNECTED))
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
        var current = TelemetryInputs(
            device = DeviceSnapshot(
                revision = 1,
                sdkAvailability = SdkAvailability.READY,
                remoteController = LinkState.CONNECTED,
                aircraft = LinkState.DISCONNECTED,
                flightController = LinkState.DISCONNECTED,
                pairing = PairingState.IDLE,
                remoteControllerModel = "RC",
                aircraftModel = null,
            ),
            flight = FlightTelemetrySnapshot(),
            stream = StreamSnapshot(0, StreamLifecycleState.STOPPED, false, "Stopped", null),
            mission = MissionSnapshot(0, null, 0, null, UploadState.NOT_UPLOADED, ExecutionState.NOT_STARTED),
        )

        override fun snapshot(): TelemetryInputs {
            reads += 1
            return current
        }
    }
}
