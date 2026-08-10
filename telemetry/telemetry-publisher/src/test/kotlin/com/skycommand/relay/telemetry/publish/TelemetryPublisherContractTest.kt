package com.skycommand.relay.telemetry.publish

import com.skycommand.relay.device.capability.DeviceCapabilities
import com.skycommand.relay.device.state.LinkState
import com.skycommand.relay.device.state.PairingState
import com.skycommand.relay.device.state.SdkAvailability
import com.skycommand.relay.telemetry.snapshot.TelemetrySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TelemetryPublisherContractTest {
    @Test
    fun publishesFirstSnapshotAndSkipsAnUnchangedSnapshot() {
        val sink = RecordingSink()
        val publisher = TelemetryPublisher.create(sink)
        val snapshot = sample(1)

        assertIs<PublishTelemetryResult.Published>(publisher.publish(snapshot))
        assertEquals(PublishTelemetryResult.SkippedUnchanged, publisher.publish(snapshot))
        assertEquals(listOf(snapshot), sink.values)
    }

    @Test
    fun doesNotAdvanceDeduplicationAfterSinkFailureAndPublishesAgainAfterReset() {
        val sink = RecordingSink()
        val publisher = TelemetryPublisher.create(sink)
        val snapshot = sample(1)
        sink.next = PublishTelemetryResult.Rejected

        assertEquals(PublishTelemetryResult.Rejected, publisher.publish(snapshot))
        sink.next = PublishTelemetryResult.Published
        assertEquals(PublishTelemetryResult.Published, publisher.publish(snapshot))
        publisher.reset()
        assertEquals(PublishTelemetryResult.Published, publisher.publish(snapshot))
        assertEquals(2, sink.values.size)
    }

    private fun sample(revision: Long) = TelemetrySnapshot(
        deviceRevision = revision,
        sdkAvailability = SdkAvailability.READY,
        remoteController = LinkState.CONNECTED,
        aircraft = LinkState.CONNECTED,
        flightController = LinkState.CONNECTED,
        pairing = PairingState.PAIRED,
        remoteControllerModel = "RC",
        aircraftModel = "Aircraft",
        capabilities = DeviceCapabilities(false, true, true, true, true),
    )

    private class RecordingSink : TelemetrySink {
        val values = mutableListOf<TelemetrySnapshot>()
        var next: PublishTelemetryResult = PublishTelemetryResult.Published
        override fun publish(snapshot: TelemetrySnapshot): PublishTelemetryResult {
            val result = next
            if (result == PublishTelemetryResult.Published) values += snapshot
            return result
        }
    }
}
