package com.skycommand.relay.telemetry

import com.skycommand.relay.device.state.DeviceStatePatch
import com.skycommand.relay.device.state.DeviceStateStore
import com.skycommand.relay.device.state.LinkState
import com.skycommand.relay.telemetry.publish.PublishTelemetryResult
import com.skycommand.relay.telemetry.publish.TelemetrySink
import com.skycommand.relay.telemetry.command.TelemetryReadResult
import com.skycommand.relay.telemetry.snapshot.TelemetrySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TelemetryContractTest {
    @Test
    fun publishesStateChangesOnlyWhileStartedAndResetsForTheNextRun() {
        val store = DeviceStateStore.create()
        val sink = RecordingSink()
        val telemetry = Telemetry.create(store, sink)

        assertIs<TelemetryStartResult.Started>(telemetry.start())
        store.apply(DeviceStatePatch.remoteController(1, LinkState.CONNECTED, "RC"))
        assertEquals(1, sink.values.size)
        assertIs<TelemetryStopResult.Stopped>(telemetry.stop())
        store.apply(DeviceStatePatch.remoteController(2, LinkState.DISCONNECTED, null))
        assertEquals(1, sink.values.size)

        telemetry.start()
        store.apply(DeviceStatePatch.remoteController(3, LinkState.CONNECTED, "RC"))
        assertEquals(2, sink.values.size)
    }

    @Test
    fun exposesTheSameCurrentSnapshotThroughImmediateRead() {
        val store = DeviceStateStore.create()
        val telemetry = Telemetry.create(store, RecordingSink())
        store.apply(DeviceStatePatch.remoteController(1, LinkState.CONNECTED, "RC"))

        val read = assertIs<TelemetryReadResult.ReadSucceeded>(telemetry.read())

        assertEquals(LinkState.CONNECTED, read.snapshot.remoteController)
    }

    @Test
    fun keepsOneSubscriptionAndRecoversAfterARejectedPublication() {
        val store = DeviceStateStore.create()
        val sink = RecordingSink()
        val telemetry = Telemetry.create(store, sink)

        assertIs<TelemetryStartResult.Started>(telemetry.start())
        assertIs<TelemetryStartResult.AlreadyStarted>(telemetry.start())
        sink.next = PublishTelemetryResult.Rejected
        store.apply(DeviceStatePatch.remoteController(1, LinkState.CONNECTED, "RC"))
        assertEquals(0, sink.values.size)

        sink.next = PublishTelemetryResult.Published
        store.apply(DeviceStatePatch.aircraft(1, LinkState.CONNECTED, LinkState.CONNECTED, "Aircraft"))
        assertEquals(1, sink.values.size)
        assertIs<TelemetryStopResult.Stopped>(telemetry.stop())
        assertIs<TelemetryStopResult.AlreadyStopped>(telemetry.stop())
    }

    private class RecordingSink : TelemetrySink {
        val values = mutableListOf<TelemetrySnapshot>()
        var next: PublishTelemetryResult = PublishTelemetryResult.Published
        override fun publish(snapshot: TelemetrySnapshot): PublishTelemetryResult {
            if (next == PublishTelemetryResult.Published) values += snapshot
            return next
        }
    }
}
