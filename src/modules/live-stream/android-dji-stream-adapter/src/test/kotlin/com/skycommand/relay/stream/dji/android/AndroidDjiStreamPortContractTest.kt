package com.skycommand.relay.stream.dji.android

import com.skycommand.relay.stream.config.ValidatedStreamConfig
import com.skycommand.relay.stream.dji.StreamDjiCompletion
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
        val firstMetrics = mutableListOf<StreamMetrics>(); var firstFailures = 0
        port.start(ValidatedStreamConfig("rtmp://host/live/one"), { firstMetrics += it }, { firstFailures++ }, Completion())
        val stale = requireNotNull(platform.listener); requireNotNull(platform.startCompletion).succeed()
        port.start(ValidatedStreamConfig("rtmp://host/live/two"), {}, {}, Completion())
        stale.onStatus(DjiLiveStreamFact(true, 1920, 1080, 30, 4000, 40)); stale.onError()
        assertEquals(emptyList(), firstMetrics); assertEquals(0, firstFailures)

        val currentMetrics = mutableListOf<StreamMetrics>(); var failures = 0
        port.start(ValidatedStreamConfig("rtmp://host/live/three"), { currentMetrics += it }, { failures++ }, Completion())
        requireNotNull(platform.listener).onStatus(DjiLiveStreamFact(true, 1280, 720, 25, 2200, 35)); requireNotNull(platform.listener).onError()
        assertEquals(listOf(StreamMetrics("1280x720", 25.0, 2200.0, 35)), currentMetrics); assertEquals(1, failures)
    }

    @Test fun detachesAfterSuccessfulStopAndMapsStopException() {
        val platform = FakePlatform(); val port = AndroidDjiStreamPort(platform)
        port.start(ValidatedStreamConfig("rtmp://host/live/device"), {}, {}, Completion()); requireNotNull(platform.startCompletion).succeed()
        platform.throwOnStop = true; val failed = Completion(); port.stop(failed); assertEquals(listOf("failure"), failed.events)
        platform.throwOnStop = false; val stopped = Completion(); port.stop(stopped); requireNotNull(platform.stopCompletion).succeed()
        assertEquals(listOf("success"), stopped.events); assertEquals(1, platform.removeCalls)
    }

    private class Completion : StreamDjiCompletion { val events=mutableListOf<String>(); override fun succeed(){events+="success"}; override fun fail(){events+="failure"} }
    private class FakePlatform : DjiLiveStreamApi {
        var url:String?=null; var listener:DjiLiveStreamListener?=null; var startCompletion:DjiLiveStreamCompletion?=null
        var stopCompletion:DjiLiveStreamCompletion?=null; var removeCalls=0; var throwOnStop=false
        override fun start(url:String, listener:DjiLiveStreamListener, completion:DjiLiveStreamCompletion){this.url=url;this.listener=listener;startCompletion=completion}
        override fun stop(completion:DjiLiveStreamCompletion){if(throwOnStop) error("stop");stopCompletion=completion}
        override fun removeListener(listener:DjiLiveStreamListener){removeCalls++}
    }
}
