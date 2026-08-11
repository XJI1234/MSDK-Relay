package com.skycommand.relay.app

import com.skycommand.relay.device.state.DeviceSnapshot
import com.skycommand.relay.device.state.LinkState
import com.skycommand.relay.device.state.PairingState
import com.skycommand.relay.device.state.SdkAvailability
import com.skycommand.relay.stream.state.StreamLifecycleState
import com.skycommand.relay.stream.state.StreamSnapshot
import com.skycommand.relay.telemetry.snapshot.FlightTelemetrySnapshot
import com.skycommand.relay.wayline.state.ExecutionState
import com.skycommand.relay.wayline.state.MissionSnapshot
import com.skycommand.relay.wayline.state.UploadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CompositeTelemetrySourceTest {
    @Test fun snapshotReadsEveryFeedIntoOneInput() {
        val source = source()

        val snapshot = source.snapshot()

        assertEquals(deviceSnapshot(), snapshot.device)
        assertEquals(FlightTelemetrySnapshot(), snapshot.flight)
        assertEquals(streamSnapshot(), snapshot.stream)
        assertEquals(missionSnapshot(), snapshot.mission)
    }

    @Test fun everyFeedChangeUsesTheSameListener() {
        val feeds = List(4) { FakeFeed<Any>() }
        val source = CompositeTelemetrySource(
            feeds[0].typed(deviceSnapshot()),
            feeds[1].typed(FlightTelemetrySnapshot()),
            feeds[2].typed(streamSnapshot()),
            feeds[3].typed(missionSnapshot()),
        )
        var changes = 0
        source.onChanged { changes++ }

        feeds.forEach { it.emit() }

        assertEquals(4, changes)
    }

    @Test fun subscriptionFailureReleasesPreviouslyRegisteredFeedsInReverseOrder() {
        val events = mutableListOf<String>()
        val device = NamedFeed("device", deviceSnapshot(), events)
        val flight = NamedFeed("flight", FlightTelemetrySnapshot(), events)
        val stream = NamedFeed("stream", streamSnapshot(), events, failRegistration = true)
        val mission = NamedFeed("mission", missionSnapshot(), events)
        val source = CompositeTelemetrySource(device, flight, stream, mission)

        assertFailsWith<IllegalStateException> { source.onChanged {} }

        assertEquals(listOf("device+", "flight+", "stream+", "flight-", "device-"), events)
    }

    @Test fun registrationReleaseIsIdempotentAndReverseOrdered() {
        val events = mutableListOf<String>()
        val source = CompositeTelemetrySource(
            NamedFeed("device", deviceSnapshot(), events),
            NamedFeed("flight", FlightTelemetrySnapshot(), events),
            NamedFeed("stream", streamSnapshot(), events),
            NamedFeed("mission", missionSnapshot(), events),
        )
        val registration = source.onChanged {}

        registration.unregister()
        registration.unregister()

        assertEquals(
            listOf("device+", "flight+", "stream+", "mission+", "mission-", "stream-", "flight-", "device-"),
            events,
        )
    }

    private fun source() = CompositeTelemetrySource(
        FakeFeed(deviceSnapshot()).feed(),
        FakeFeed(FlightTelemetrySnapshot()).feed(),
        FakeFeed(streamSnapshot()).feed(),
        FakeFeed(missionSnapshot()).feed(),
    )

    private fun deviceSnapshot() = DeviceSnapshot(
        revision = 0,
        sdkAvailability = SdkAvailability.STOPPED,
        remoteController = LinkState.DISCONNECTED,
        aircraft = LinkState.DISCONNECTED,
        flightController = LinkState.DISCONNECTED,
        pairing = PairingState.UNKNOWN,
        remoteControllerModel = null,
        aircraftModel = null,
    )

    private fun streamSnapshot() = StreamSnapshot(
        revision = 0,
        state = StreamLifecycleState.STOPPED,
        targetConfigured = false,
        notice = "Stopped",
        metrics = null,
    )

    private fun missionSnapshot() = MissionSnapshot(
        revision = 0,
        missionRevision = null,
        deviceGeneration = 0,
        file = null,
        upload = UploadState.NOT_UPLOADED,
        execution = ExecutionState.NOT_STARTED,
    )

    private class FakeFeed<T>(private val value: T? = null) {
        private var listener: (() -> Unit)? = null

        fun emit() = requireNotNull(listener).invoke()

        @Suppress("UNCHECKED_CAST")
        fun <R> typed(snapshot: R) = object : SnapshotFeed<R> {
            override fun snapshot() = snapshot
            override fun onChanged(listener: () -> Unit) = CloseableRegistration { this@FakeFeed.listener = null }
                .also { this@FakeFeed.listener = listener }
        }

        fun feed() = object : SnapshotFeed<T> {
            override fun snapshot(): T = requireNotNull(value)
            override fun onChanged(listener: () -> Unit) = CloseableRegistration { this@FakeFeed.listener = null }
                .also { this@FakeFeed.listener = listener }
        }
    }

    private class NamedFeed<T>(
        private val name: String,
        private val snapshot: T,
        private val events: MutableList<String>,
        private val failRegistration: Boolean = false,
    ) : SnapshotFeed<T> {
        override fun snapshot() = snapshot

        override fun onChanged(listener: () -> Unit): CloseableRegistration {
            events += "$name+"
            if (failRegistration) error("registration failed")
            return CloseableRegistration { events += "$name-" }
        }
    }

}
