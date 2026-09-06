package com.skycommand.relay.telemetry

import com.skycommand.relay.device.state.DeviceSnapshot
import com.skycommand.relay.device.state.DeviceStatePatch
import com.skycommand.relay.device.state.DeviceStateStore
import com.skycommand.relay.device.state.LinkState
import com.skycommand.relay.device.state.PairingState
import com.skycommand.relay.device.state.SdkAvailability
import com.skycommand.relay.stream.state.StreamLifecycleState
import com.skycommand.relay.stream.state.StreamSnapshot
import com.skycommand.relay.telemetry.command.TelemetryReadResult
import com.skycommand.relay.telemetry.publish.PublishTelemetryResult
import com.skycommand.relay.telemetry.publish.TelemetrySink
import com.skycommand.relay.telemetry.snapshot.FlightTelemetrySnapshot
import com.skycommand.relay.telemetry.snapshot.TelemetryInputs
import com.skycommand.relay.telemetry.snapshot.TelemetrySnapshot
import com.skycommand.relay.wayline.state.ExecutionState
import com.skycommand.relay.wayline.state.MissionSnapshot
import com.skycommand.relay.wayline.state.UploadState
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TelemetryContractTest {
    @Test
    fun publishesStateChangesOnlyWhileStartedAndResetsForTheNextRun() {
        val store = DeviceStateStore.create()
        val sink = RecordingSink()
        val executor = QueuedPublicationExecutor()
        val telemetry = Telemetry.create(store, sink, executor)

        assertIs<TelemetryStartResult.Started>(telemetry.start())
        store.apply(DeviceStatePatch.remoteController(1, LinkState.CONNECTED, "RC"))
        executor.runAll()
        assertEquals(1, sink.values.size)
        assertIs<TelemetryStopResult.Stopped>(telemetry.stop())
        store.apply(DeviceStatePatch.remoteController(2, LinkState.DISCONNECTED, null))
        assertEquals(1, sink.values.size)

        telemetry.start()
        store.apply(DeviceStatePatch.remoteController(3, LinkState.CONNECTED, "RC"))
        executor.runAll()
        assertEquals(2, sink.values.size)
    }

    @Test
    fun exposesTheSameCurrentSnapshotThroughImmediateRead() {
        val store = DeviceStateStore.create()
        val telemetry = Telemetry.create(store, RecordingSink(), QueuedPublicationExecutor())
        store.apply(DeviceStatePatch.remoteController(1, LinkState.CONNECTED, "RC"))

        val read = assertIs<TelemetryReadResult.ReadSucceeded>(telemetry.read())

        assertEquals(LinkState.CONNECTED, read.snapshot.remoteController)
    }

    @Test
    fun keepsOneSubscriptionAndRecoversAfterARejectedPublication() {
        val store = DeviceStateStore.create()
        val sink = RecordingSink()
        val executor = QueuedPublicationExecutor()
        val telemetry = Telemetry.create(store, sink, executor)

        assertIs<TelemetryStartResult.Started>(telemetry.start())
        assertIs<TelemetryStartResult.AlreadyStarted>(telemetry.start())
        sink.next = PublishTelemetryResult.Rejected
        store.apply(DeviceStatePatch.remoteController(1, LinkState.CONNECTED, "RC"))
        executor.runAll()
        assertEquals(0, sink.values.size)

        sink.next = PublishTelemetryResult.Published
        store.apply(
            DeviceStatePatch.aircraft(
                1,
                LinkState.CONNECTED,
                LinkState.CONNECTED,
                "Aircraft",
                LinkState.UNKNOWN,
                LinkState.UNKNOWN,
            ),
        )
        executor.runAll()
        assertEquals(1, sink.values.size)
        assertIs<TelemetryStopResult.Stopped>(telemetry.stop())
        assertIs<TelemetryStopResult.AlreadyStopped>(telemetry.stop())
    }

    @Test
    fun publishesACompleteSnapshotWhenAnyUnifiedSourceChanges() {
        val source = UnifiedSource()
        val sink = RecordingSink()
        val executor = QueuedPublicationExecutor()
        val telemetry = Telemetry.create(source, sink, executor)
        telemetry.start()

        source.inputs = source.inputs.copy(
            device = connectedFlightDevice(),
            flight = FlightTelemetrySnapshot(isFlying = true, batteryPercent = 77, battery = LinkState.CONNECTED),
        )
        source.emit()
        executor.runAll()

        assertEquals(true, sink.values.single().isFlying)
        assertEquals(77, sink.values.single().batteryPercent)
        assertEquals(source.inputs, source.lastSampled)
    }

    @Test
    fun rejectsSamplingFailuresWithoutBreakingLaterPublications() {
        val source = UnifiedSource()
        val sink = RecordingSink()
        val executor = QueuedPublicationExecutor()
        val telemetry = Telemetry.create(source, sink, executor)
        telemetry.start()

        source.failure = IllegalStateException("DJI unavailable")
        source.emit()
        executor.runAll()
        assertEquals(emptyList(), sink.values)

        source.failure = null
        source.inputs = source.inputs.copy(
            device = connectedFlightDevice(),
            flight = FlightTelemetrySnapshot(isFlying = true),
        )
        source.emit()
        executor.runAll()
        assertEquals(true, sink.values.single().isFlying)
    }

    @Test
    fun ignoresAListenerCapturedBeforeStopAndUsesANewListenerAfterRestart() {
        val source = UnifiedSource()
        val sink = RecordingSink()
        val executor = QueuedPublicationExecutor()
        val telemetry = Telemetry.create(source, sink, executor)
        telemetry.start()
        val stale = source.captureListener()

        telemetry.stop()
        stale()
        assertEquals(emptyList(), sink.values)

        telemetry.start()
        source.inputs = source.inputs.copy(
            device = connectedFlightDevice(),
            flight = FlightTelemetrySnapshot(motorsOn = true),
        )
        source.emit()
        executor.runAll()
        assertEquals(true, sink.values.single().motorsOn)
    }

    @Test
    fun suppressesDuplicateSnapshotsFromRepeatedUnifiedNotifications() {
        val source = UnifiedSource()
        val sink = RecordingSink()
        val executor = QueuedPublicationExecutor()
        val telemetry = Telemetry.create(source, sink, executor)
        telemetry.start()

        source.emit()
        source.emit()
        executor.runAll()

        assertEquals(1, sink.values.size)
    }

    @Test
    fun queuesCurrentSnapshotOnlyWhileStarted() {
        val source = UnifiedSource()
        val sink = RecordingSink()
        val executor = QueuedPublicationExecutor()
        val telemetry = Telemetry.create(source, sink, executor)

        assertEquals(TelemetryPublicationRequestResult.Rejected, telemetry.publishCurrent())
        telemetry.start()
        assertEquals(TelemetryPublicationRequestResult.Queued, telemetry.publishCurrent())
        executor.runAll()
        assertEquals(1, sink.values.size)
        assertEquals(TelemetryPublicationRequestResult.Queued, telemetry.publishCurrent())
        executor.runAll()
        assertEquals(1, sink.values.size)
        telemetry.stop()
        assertEquals(TelemetryPublicationRequestResult.Rejected, telemetry.publishCurrent())
    }

    @Test
    fun returnsFromAStateCallbackWhileTheTelemetrySinkIsBlocked() {
        val source = UnifiedSource()
        val sink = BlockingSink()
        val telemetry = Telemetry.create(source, sink)
        var producer: Thread? = null
        try {
            telemetry.start()
            producer = thread(name = "telemetry-source-callback") {
                source.emit()
                sink.callbackReturned.countDown()
            }

            assertTrue(sink.publishEntered.await(1, TimeUnit.SECONDS), "The state change must reach the telemetry publisher")
            assertTrue(
                sink.callbackReturned.await(250, TimeUnit.MILLISECONDS),
                "A state callback must not wait for a blocked desktop publication",
            )
        } finally {
            sink.releasePublish.countDown()
            producer?.join(1_000)
            telemetry.stop()
        }
    }

    @Test
    fun publishesOnlyTheNewestSnapshotThatArrivesWhileTheSinkIsBlocked() {
        val source = UnifiedSource()
        val sink = BlockingFirstSink()
        val telemetry = Telemetry.create(source, sink)
        try {
            telemetry.start()
            source.inputs = source.inputs.copy(flight = FlightTelemetrySnapshot(battery = LinkState.CONNECTED, batteryPercent = 40))
            source.emit()
            assertTrue(sink.firstPublishEntered.await(1, TimeUnit.SECONDS))

            source.inputs = source.inputs.copy(flight = FlightTelemetrySnapshot(battery = LinkState.CONNECTED, batteryPercent = 41))
            source.emit()
            source.inputs = source.inputs.copy(flight = FlightTelemetrySnapshot(battery = LinkState.CONNECTED, batteryPercent = 42))
            source.emit()
            sink.releaseFirstPublish.countDown()

            assertTrue(sink.secondPublishEntered.await(1, TimeUnit.SECONDS))
            assertEquals(listOf(40, 42), sink.values.map(TelemetrySnapshot::batteryPercent))
        } finally {
            sink.releaseFirstPublish.countDown()
            telemetry.stop()
        }
    }

    private class RecordingSink : TelemetrySink {
        val values = mutableListOf<TelemetrySnapshot>()
        var next: PublishTelemetryResult = PublishTelemetryResult.Published

        override fun publish(snapshot: TelemetrySnapshot): PublishTelemetryResult {
            if (next == PublishTelemetryResult.Published) values += snapshot
            return next
        }
    }

    private class BlockingSink : TelemetrySink {
        val publishEntered = CountDownLatch(1)
        val releasePublish = CountDownLatch(1)
        val callbackReturned = CountDownLatch(1)

        override fun publish(snapshot: TelemetrySnapshot): PublishTelemetryResult {
            publishEntered.countDown()
            releasePublish.await()
            return PublishTelemetryResult.Published
        }
    }

    private class BlockingFirstSink : TelemetrySink {
        val firstPublishEntered = CountDownLatch(1)
        val releaseFirstPublish = CountDownLatch(1)
        val secondPublishEntered = CountDownLatch(1)
        val values = mutableListOf<TelemetrySnapshot>()

        override fun publish(snapshot: TelemetrySnapshot): PublishTelemetryResult = synchronized(this) {
            if (values.isEmpty()) {
                values += snapshot
                firstPublishEntered.countDown()
                releaseFirstPublish.await()
            } else {
                values += snapshot
                secondPublishEntered.countDown()
            }
            PublishTelemetryResult.Published
        }
    }

    private class QueuedPublicationExecutor : TelemetryPublicationExecutor {
        private val work = ArrayDeque<() -> Unit>()

        override fun execute(task: () -> Unit) {
            work.addLast(task)
        }

        fun runAll() {
            while (work.isNotEmpty()) {
                work.removeFirst().invoke()
            }
        }
    }

    private class UnifiedSource : TelemetryStateSource {
        var inputs = TelemetryInputs(
            device = DeviceSnapshot(0, SdkAvailability.STOPPED, LinkState.DISCONNECTED, LinkState.DISCONNECTED, LinkState.DISCONNECTED, PairingState.UNKNOWN, null, null),
            flight = FlightTelemetrySnapshot(),
            stream = StreamSnapshot(0, StreamLifecycleState.STOPPED, false, "Stopped", null),
            mission = MissionSnapshot(0, null, 0, null, UploadState.NOT_UPLOADED, ExecutionState.NOT_STARTED),
        )
        var lastSampled: TelemetryInputs? = null
        var failure: RuntimeException? = null
        private var listener: (() -> Unit)? = null

        override fun snapshot(): TelemetryInputs {
            failure?.let { throw it }
            return inputs.also { lastSampled = it }
        }

        override fun onChanged(listener: () -> Unit): TelemetryRegistration {
            this.listener = listener
            return TelemetryRegistration { this.listener = null }
        }

        fun emit() = requireNotNull(listener).invoke()

        fun captureListener(): () -> Unit = requireNotNull(listener)
    }

    private fun connectedFlightDevice() = DeviceSnapshot(
        revision = 1,
        sdkAvailability = SdkAvailability.READY,
        remoteController = LinkState.CONNECTED,
        aircraft = LinkState.CONNECTED,
        flightController = LinkState.CONNECTED,
        pairing = PairingState.PAIRED,
        remoteControllerModel = "RC Plus",
        aircraftModel = "Matrice 4",
    )
}
