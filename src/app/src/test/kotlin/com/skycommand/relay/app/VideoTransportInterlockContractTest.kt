package com.skycommand.relay.app

import com.skycommand.relay.gateway.command.CommandCompletion
import com.skycommand.relay.gateway.command.CommandHandler
import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.JsonString
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class VideoTransportInterlockContractTest {
    @Test fun legacyStartRejectsWhipWithoutCallingWhipHandler() {
        val fixture = Fixture()

        fixture.legacyStart()
        val whipCompletion = fixture.whipStart()

        assertEquals(1, fixture.legacy.calls.get())
        assertEquals(0, fixture.whip.calls.get())
        assertEquals(listOf("reject:Another video transport is active"), whipCompletion.events)
    }

    @Test fun whipStartRejectsLegacyWithoutCallingLegacyHandler() {
        val fixture = Fixture()

        fixture.whipStart()
        val legacyCompletion = fixture.legacyStart()

        assertEquals(0, fixture.legacy.calls.get())
        assertEquals(1, fixture.whip.calls.get())
        assertEquals(listOf("reject:Another video transport is active"), legacyCompletion.events)
    }

    @Test fun failedStartReleasesTransportForTheOtherMode() {
        val fixture = Fixture()

        fixture.legacyStart()
        fixture.legacy.complete(0).reject("Stream operation failed")
        fixture.whipStart()

        assertEquals(1, fixture.whip.calls.get())
    }

    @Test fun successfulStopReleasesTransportForTheOtherMode() {
        val fixture = Fixture()

        fixture.legacyStart()
        fixture.legacy.complete(0).succeed("Stream started")
        fixture.legacyStop()
        fixture.legacy.complete(1).succeed("Stream stopped")
        fixture.whipStart()

        assertEquals(1, fixture.whip.calls.get())
    }

    @Test fun failedStopKeepsTransportOwned() {
        val fixture = Fixture()

        fixture.legacyStart()
        fixture.legacy.complete(0).succeed("Stream started")
        fixture.legacyStop()
        fixture.legacy.complete(1).reject("Stream operation failed")
        val whipCompletion = fixture.whipStart()

        assertEquals(0, fixture.whip.calls.get())
        assertEquals(listOf("reject:Another video transport is active"), whipCompletion.events)
    }

    @Test fun concurrentStartsCallOnlyOneUnderlyingHandler() {
        val fixture = Fixture()
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val legacy = executor.submit { gate.await(); fixture.legacyStart() }
            val whip = executor.submit { gate.await(); fixture.whipStart() }

            gate.countDown()
            legacy.get(1, TimeUnit.SECONDS)
            whip.get(1, TimeUnit.SECONDS)

            assertEquals(1, fixture.legacy.calls.get() + fixture.whip.calls.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun deviceUnavailableLetsAnotherModeStartAndIgnoresOldCompletion() {
        val fixture = Fixture()

        fixture.legacyStart()
        fixture.interlock.markDeviceUnavailable()
        fixture.whipStart()
        fixture.legacy.complete(0).succeed("Stream started")
        val legacyCompletion = fixture.legacyStart()

        assertEquals(1, fixture.whip.calls.get())
        assertEquals(1, fixture.legacy.calls.get())
        assertEquals(listOf("reject:Another video transport is active"), legacyCompletion.events)
    }

    @Test fun synchronousStartExceptionReleasesTransportAndStaysSafe() {
        val fixture = Fixture().apply { legacy.throwOnCall = true }

        val legacyCompletion = fixture.legacyStart()
        fixture.whipStart()

        assertEquals(listOf("reject:Video transport operation failed"), legacyCompletion.events)
        assertEquals(1, fixture.whip.calls.get())
    }

    @Test fun repeatedSameModeStartKeepsTheOriginalOwnership() {
        val fixture = Fixture().apply { legacy.rejectSecondCall = true }

        fixture.legacyStart()
        val repeated = fixture.legacyStart()
        val whipCompletion = fixture.whipStart()

        assertEquals(2, fixture.legacy.calls.get())
        assertEquals(listOf("reject:Existing legacy stream request"), repeated.events)
        assertEquals(0, fixture.whip.calls.get())
        assertEquals(listOf("reject:Another video transport is active"), whipCompletion.events)
    }

    private class Fixture {
        val legacy = Handler()
        val whip = Handler()
        val interlock = VideoTransportInterlock(legacy, whip)

        fun legacyStart() = handle("live-stream.start", start("live-stream.start", "rtmpUrl", "rtmp://computer/live/drone"))
        fun legacyStop() = handle("live-stream.stop", stop("live-stream.stop"))
        fun whipStart() = handle("live-stream-webrtc.start", start("live-stream-webrtc.start", "whipUrl", "http://computer/live/drone/whip"))

        private fun handle(name: String, command: CommandFrame): Completion = Completion().also { completion ->
            interlock.handlerFor(name).handle(command, completion)
        }
    }

    private class Handler : CommandHandler {
        val calls = AtomicInteger()
        private val completions = CopyOnWriteArrayList<CommandCompletion>()
        var throwOnCall = false
        var rejectSecondCall = false

        override fun handle(command: CommandFrame, completion: CommandCompletion) {
            val call = calls.incrementAndGet()
            if (throwOnCall) error("internal adapter failure")
            if (rejectSecondCall && call == 2) {
                completion.reject("Existing legacy stream request")
                return
            }
            completions += completion
        }

        fun complete(index: Int): CommandCompletion = completions[index]
    }

    private class Completion : CommandCompletion {
        val events = CopyOnWriteArrayList<String>()

        override fun succeed(detail: String) {
            events += "ok:$detail"
        }

        override fun reject(detail: String) {
            events += "reject:$detail"
        }
    }

    private companion object {
        fun start(name: String, field: String, value: String) = CommandFrame(
            id = "command-$name",
            name = name,
            fields = JsonObject(mapOf(field to JsonString(value))),
        )

        fun stop(name: String) = CommandFrame(
            id = "command-$name",
            name = name,
            fields = JsonObject(emptyMap()),
        )
    }
}
