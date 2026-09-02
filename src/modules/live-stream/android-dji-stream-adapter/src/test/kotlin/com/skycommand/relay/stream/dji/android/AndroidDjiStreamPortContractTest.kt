package com.skycommand.relay.stream.dji.android

import com.skycommand.relay.stream.config.ValidatedStreamConfig
import com.skycommand.relay.stream.dji.StreamDjiCompletion
import com.skycommand.relay.stream.dji.DjiStreamStatus
import com.skycommand.relay.stream.state.StreamMetrics
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidDjiStreamPortContractTest {
    @Test fun configuresRtmpAndCompletesStartOnce() {
        val platform = FakePlatform(); val port = AndroidDjiStreamPort(platform); val completion = Completion()
        port.start(ValidatedStreamConfig("rtmp://host/live/device"), {}, {}, completion)
        requireNotNull(platform.startCompletion).succeed(); requireNotNull(platform.startCompletion).succeed()
        assertEquals("rtmp://host/live/device", platform.url); assertEquals(listOf("success"), completion.events)
    }

    @Test fun reportsMetricsAndRuntimeFailureOnlyForCurrentGeneration() {
        val platform = FakePlatform(); val port = AndroidDjiStreamPort(platform)
        val firstStatuses = mutableListOf<DjiStreamStatus>(); var firstFailures = 0
        port.start(ValidatedStreamConfig("rtmp://host/live/one"), { firstStatuses += it }, { firstFailures++ }, Completion())
        val stale = requireNotNull(platform.listener); requireNotNull(platform.startCompletion).succeed()
        port.start(ValidatedStreamConfig("rtmp://host/live/two"), {}, {}, Completion())
        requireNotNull(platform.startCompletion).succeed()
        stale.onStatus(DjiLiveStreamFact(true, 1920, 1080, 30, 4000, 40)); stale.onError()
        assertEquals(emptyList(), firstStatuses); assertEquals(0, firstFailures)
        assertEquals(0, platform.stopCalls)

        val currentStatuses = mutableListOf<DjiStreamStatus>(); var failures = 0
        port.start(ValidatedStreamConfig("rtmp://host/live/three"), { currentStatuses += it }, { failures++ }, Completion())
        requireNotNull(platform.startCompletion).succeed()
        requireNotNull(platform.listener).onStatus(DjiLiveStreamFact(true, 1280, 720, 25, 2200, 35, 12, 24)); requireNotNull(platform.listener).onError()
        assertEquals(listOf(DjiStreamStatus(true, StreamMetrics("1280x720", 25.0, 2200.0, 35, 12, 24))), currentStatuses); assertEquals(1, failures)
        assertEquals(0, platform.stopCalls)
    }

    @Test fun buffersPreStartStreamingAndDoesNotTreatPreStartStoppedAsFailure() {
        val platform = FakePlatform(); val port = AndroidDjiStreamPort(platform)
        val statuses = mutableListOf<DjiStreamStatus>(); var failures = 0

        port.start(ValidatedStreamConfig("rtmp://host/live/device"), { statuses += it }, { failures++ }, Completion())
        requireNotNull(platform.listener).onStatus(DjiLiveStreamFact(false, 0, 0, 0, 0, 0))
        requireNotNull(platform.listener).onStatus(DjiLiveStreamFact(true, 1920, 1080, 30, 4000, 40))

        assertEquals(emptyList(), statuses)
        assertEquals(0, failures)
        requireNotNull(platform.startCompletion).succeed()

        assertEquals(listOf(DjiStreamStatus(true, StreamMetrics("1920x1080", 30.0, 4000.0, 40, 0, 0))), statuses)
        assertEquals(0, failures)
    }

    @Test fun detachesAfterSuccessfulStopAndMapsStopException() {
        val platform = FakePlatform(); val port = AndroidDjiStreamPort(platform)
        port.start(ValidatedStreamConfig("rtmp://host/live/device"), {}, {}, Completion()); requireNotNull(platform.startCompletion).succeed()
        platform.throwOnStop = true; val failed = Completion(); port.stop(failed); assertEquals(listOf("failure"), failed.events)
        platform.throwOnStop = false; val stopped = Completion(); port.stop(stopped); requireNotNull(platform.stopCompletion).succeed()
        assertEquals(listOf("success"), stopped.events); assertEquals(1, platform.removeCalls)
    }

    @Test fun rejectsStartWhilePlatformStopIsStillInFlight() {
        val platform = FakePlatform(); val port = AndroidDjiStreamPort(platform)
        port.start(ValidatedStreamConfig("rtmp://host/live/first"), {}, {}, Completion())
        requireNotNull(platform.startCompletion).succeed()
        port.stop(Completion())
        val pendingStop = requireNotNull(platform.stopCompletion)

        val rejected = Completion()
        port.start(ValidatedStreamConfig("rtmp://host/live/second"), {}, {}, rejected)
        assertEquals(listOf("failure"), rejected.events)
        assertEquals(1, platform.startCalls)

        pendingStop.succeed()
        val retry = Completion()
        port.start(ValidatedStreamConfig("rtmp://host/live/second"), {}, {}, retry)
        assertEquals(2, platform.startCalls)
    }

    @Test fun runtimeFailureDoesNotIssueASecondPlatformOperation() {
        val platform = FakePlatform(); val port = AndroidDjiStreamPort(platform)
        var runtimeFailures = 0

        port.start(ValidatedStreamConfig("rtmp://host/live/device"), {}, { runtimeFailures++ }, Completion())
        requireNotNull(platform.startCompletion).succeed()
        requireNotNull(platform.listener).onError()

        assertEquals(1, runtimeFailures)
        assertEquals(0, platform.stopCalls)
    }

    @Test fun reportsDjiStoppedStatusWithoutIssuingASecondPlatformOperation() {
        val platform = FakePlatform(); val port = AndroidDjiStreamPort(platform)
        val statuses = mutableListOf<DjiStreamStatus>(); var runtimeFailures = 0

        port.start(ValidatedStreamConfig("rtmp://host/live/device"), { statuses += it }, { runtimeFailures++ }, Completion())
        requireNotNull(platform.startCompletion).succeed()
        requireNotNull(platform.listener).onStatus(DjiLiveStreamFact(false, 0, 0, 0, 0, 0))

        assertEquals(listOf(DjiStreamStatus(false)), statuses)
        assertEquals(0, runtimeFailures)
        assertEquals(0, platform.stopCalls)
    }

    @Test fun closeStopsActiveStreamAndInvalidatesCallbacks() {
        val platform = FakePlatform(); val port = AndroidDjiStreamPort(platform)
        val statuses = mutableListOf<DjiStreamStatus>(); var runtimeFailures = 0
        port.start(ValidatedStreamConfig("rtmp://host/live/device"), { statuses += it }, { runtimeFailures++ }, Completion())
        val listener = requireNotNull(platform.listener)
        requireNotNull(platform.startCompletion).succeed()

        port.close()
        listener.onStatus(DjiLiveStreamFact(true, 1920, 1080, 30, 4000, 40))
        listener.onError()
        val rejected = Completion()
        port.start(ValidatedStreamConfig("rtmp://host/live/next"), {}, {}, rejected)

        assertEquals(1, platform.removeCalls)
        assertEquals(1, platform.stopCalls)
        assertEquals(emptyList(), statuses)
        assertEquals(0, runtimeFailures)
        assertEquals(listOf("failure"), rejected.events)
    }

    private class Completion : StreamDjiCompletion { val events=mutableListOf<String>(); override fun succeed(){events+="success"}; override fun fail(){events+="failure"} }
    private class FakePlatform : DjiLiveStreamApi {
        var url:String?=null; var listener:DjiLiveStreamListener?=null; var startCompletion:DjiLiveStreamCompletion?=null
        var stopCompletion:DjiLiveStreamCompletion?=null; var removeCalls=0; var throwOnStop=false; var startCalls=0; var stopCalls=0
        override fun start(url:String, listener:DjiLiveStreamListener, completion:DjiLiveStreamCompletion){startCalls++;this.url=url;this.listener=listener;startCompletion=completion}
        override fun stop(completion:DjiLiveStreamCompletion){stopCalls++;if(throwOnStop) error("stop");stopCompletion=completion}
        override fun removeListener(listener:DjiLiveStreamListener){removeCalls++}
    }
}
