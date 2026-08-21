package com.skycommand.relay.stream.whip.android

import com.skycommand.relay.stream.video.EncodedVideoFrame
import com.skycommand.relay.stream.whip.config.ValidatedWhipStreamConfig
import com.skycommand.relay.stream.whip.publisher.WhipTransportCloseResult
import com.skycommand.relay.stream.whip.publisher.WhipTransportFailure
import com.skycommand.relay.stream.whip.publisher.WhipTransportListener
import com.skycommand.relay.stream.whip.publisher.WhipTransportOpenResult
import com.skycommand.relay.stream.whip.publisher.WhipTransportRejection
import com.skycommand.relay.stream.whip.publisher.WhipTransportSendResult
import java.util.ArrayDeque
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledThreadPoolExecutor
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidWhipTransportContractTest {
    @Test
    fun rejectsInvalidWhipConfigBeforeCreatingPlatformResources() {
        val fixture = Fixture()
        try {
            val result = fixture.transport.open(
                ValidatedWhipStreamConfig("http://computer/live/device/not-whip"),
                fixture.events,
            )

            assertEquals(WhipTransportOpenResult.Rejected(WhipTransportRejection.INVALID_CONFIGURATION), result)
            assertEquals(0, fixture.factory.createCalls)
            assertTrue(fixture.http.offers.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun acceptsOpenAsynchronouslyAndConnectsOnlyAfterAnswerAndIceState() {
        val fixture = Fixture()
        try {
            assertEquals(WhipTransportOpenResult.Accepted, fixture.transport.open(config(), fixture.events))
            assertEquals(0, fixture.factory.createCalls)

            fixture.executor.runNext()
            assertEquals(1, fixture.factory.createCalls)
            val session = fixture.factory.sessions.single()
            assertNull(fixture.events.connected)

            session.offerReady("offer-with-gathered-candidate")
            assertTrue(fixture.http.offers.isEmpty())
            fixture.executor.runNext()

            assertEquals(listOf("offer-with-gathered-candidate"), fixture.http.offers)
            assertEquals(listOf("answer-sdp"), session.answers)
            assertNull(fixture.events.connected)

            session.connected()
            assertEquals(1, fixture.events.connected)
            session.connected()
            assertEquals(1, fixture.events.connected)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun copiesFrameBeforeReturningAndAppliesBoundedBackpressure() {
        val fixture = Fixture(queueCapacity = 1)
        try {
            fixture.connect()
            val bytes = byteArrayOf(9, 0, 0, 0, 1, 5, 7)
            val frame = EncodedVideoFrame(
                data = bytes,
                offset = 1,
                length = bytes.size - 1,
                width = 1920,
                height = 1080,
                frameRate = 30,
                presentationTimeMs = 33,
                isKeyFrame = true,
            )

            assertEquals(WhipTransportSendResult.Accepted, fixture.transport.send(frame))
            bytes[4] = 99
            assertEquals(WhipTransportSendResult.Backpressured, fixture.transport.send(frame))

            fixture.executor.runNext()
            val sent = fixture.factory.sessions.single().sent.single()
            assertContentEquals(byteArrayOf(0, 0, 0, 1, 5, 7), sent.data)
            assertEquals(1920, sent.width)
            assertEquals(1080, sent.height)
            assertEquals(33, sent.presentationTimeMs)
            assertTrue(sent.isKeyFrame)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun dropsQueuedDeltaFramesToAcceptAKeyframeUnderBackpressure() {
        val fixture = Fixture(queueCapacity = 1)
        try {
            fixture.connect()
            val delta = frame(keyFrame = false, bytes = byteArrayOf(0, 0, 0, 1, 1))
            val key = frame(keyFrame = true, bytes = byteArrayOf(0, 0, 0, 1, 5))

            assertEquals(WhipTransportSendResult.Accepted, fixture.transport.send(delta))
            assertEquals(WhipTransportSendResult.Accepted, fixture.transport.send(key))
            assertEquals(WhipTransportSendResult.Backpressured, fixture.transport.send(delta))

            fixture.executor.runNext()
            val sent = fixture.factory.sessions.single().sent.single()
            assertTrue(sent.isKeyFrame)
            assertContentEquals(byteArrayOf(0, 0, 0, 1, 5), sent.data)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun convertsLengthPrefixedAvccWithoutDecodingIt() {
        val fixture = Fixture()
        try {
            fixture.connect()
            val frame = frame(
                keyFrame = true,
                bytes = byteArrayOf(0, 0, 0, 2, 7, 1, 0, 0, 0, 2, 8, 1),
            )

            assertEquals(WhipTransportSendResult.Accepted, fixture.transport.send(frame))
            fixture.executor.runNext()

            assertContentEquals(
                byteArrayOf(0, 0, 0, 1, 7, 1, 0, 0, 0, 1, 8, 1),
                fixture.factory.sessions.single().sent.single().data,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun mapsPlatformFailureOnceAndReleasesSession() {
        val fixture = Fixture()
        try {
            fixture.connect()
            val session = fixture.factory.sessions.single()

            session.failed(AndroidWhipPlatformFailure.ICE)
            session.failed(AndroidWhipPlatformFailure.NETWORK)

            assertEquals(listOf(WhipTransportFailure.ICE), fixture.events.failures)
            assertEquals(1, session.closeCalls)
            assertEquals(WhipTransportSendResult.NotConnected, fixture.transport.send(frame(true, annexB(5))))
            assertEquals(WhipTransportCloseResult.AlreadyClosed, fixture.transport.close())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun oldGenerationCallbacksCannotAffectNewGeneration() {
        val fixture = Fixture()
        try {
            fixture.connect()
            val old = fixture.factory.sessions[0]
            assertEquals(WhipTransportCloseResult.Closed, fixture.transport.close())

            assertEquals(WhipTransportOpenResult.Accepted, fixture.transport.open(config(), fixture.events))
            fixture.executor.runNext()
            val current = fixture.factory.sessions[1]
            old.connected()
            old.failed(AndroidWhipPlatformFailure.ICE)
            assertEquals(1, fixture.events.connected)
            assertTrue(fixture.events.failures.isEmpty())

            current.offerReady("offer-2")
            fixture.executor.runNext()
            current.connected()
            assertEquals(2, fixture.events.connected)
            assertTrue(fixture.events.failures.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun refusesToStartWhenEncodedH264InjectionIsUnavailable() {
        val fixture = Fixture(encodedH264Available = false)
        try {
            assertEquals(
                WhipTransportOpenResult.Rejected(WhipTransportRejection.ENCODED_H264_UNAVAILABLE),
                fixture.transport.open(config(), fixture.events),
            )
            assertEquals(0, fixture.executor.size)
            assertEquals(0, fixture.factory.createCalls)
        } finally {
            fixture.close()
        }
    }

    private class Fixture(
        queueCapacity: Int = 3,
        encodedH264Available: Boolean = true,
    ) {
        val executor = QueueExecutor()
        val scheduler = ScheduledThreadPoolExecutor(1)
        val factory = FakeFactory(encodedH264Available)
        val http = FakeHttp()
        val events = Events()
        val transport = AndroidWhipTransport.createForTest(
            AndroidWhipTransportDependencies(
                webRtc = factory,
                http = http,
                executor = executor,
                scheduler = scheduler,
                options = AndroidWhipTransportOptions(queueCapacity = queueCapacity, signalingTimeoutMs = 10_000),
            ),
        )

        fun connect() {
            assertEquals(
                WhipTransportOpenResult.Accepted,
                transport.open(ValidatedWhipStreamConfig("http://computer/live/device/whip"), events),
            )
            executor.runNext()
            val session = factory.sessions.single()
            session.offerReady("offer")
            executor.runNext()
            session.connected()
        }

        fun close() {
            runCatching { transport.close() }
            scheduler.shutdownNow()
        }
    }

    private class QueueExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val size: Int get() = tasks.size

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() {
            require(tasks.isNotEmpty()) { "expected a queued adapter task" }
            tasks.removeFirst().run()
        }
    }

    private class FakeFactory(
        override val encodedH264Available: Boolean,
    ) : AndroidWhipWebRtcFactory {
        val sessions = mutableListOf<FakeSession>()
        var createCalls = 0

        override fun create(): AndroidWhipWebRtcSession {
            createCalls += 1
            return FakeSession().also { sessions += it }
        }
    }

    private class FakeSession : AndroidWhipWebRtcSession {
        private var listener: AndroidWhipWebRtcListener? = null
        val answers = mutableListOf<String>()
        val sent = mutableListOf<EncodedVideoFrame>()
        var closeCalls = 0

        override fun start(listener: AndroidWhipWebRtcListener) {
            this.listener = listener
        }

        override fun setRemoteAnswer(answerSdp: String) {
            answers += answerSdp
        }

        override fun send(frame: EncodedVideoFrame): AndroidWhipSendResult {
            sent += frame
            return AndroidWhipSendResult.Accepted
        }

        override fun close() {
            closeCalls += 1
        }

        fun offerReady(sdp: String) = requireNotNull(listener).onOfferReady(sdp)
        fun connected() = requireNotNull(listener).onConnected()
        fun failed(reason: AndroidWhipPlatformFailure) = requireNotNull(listener).onFailed(reason)
    }

    private class FakeHttp : AndroidWhipHttpClient {
        val offers = mutableListOf<String>()

        override fun postOffer(config: ValidatedWhipStreamConfig, offerSdp: String): AndroidWhipHttpResult {
            offers += offerSdp
            return AndroidWhipHttpResult.Answer("answer-sdp")
        }
    }

    private class Events : WhipTransportListener {
        var connected: Int? = null
        val failures = mutableListOf<WhipTransportFailure>()

        override fun onConnected() {
            connected = (connected ?: 0) + 1
        }

        override fun onFailed(reason: WhipTransportFailure) {
            failures += reason
        }

        override fun onDisconnected() = Unit
    }

    private fun config() = ValidatedWhipStreamConfig("http://computer/live/device/whip")

    private fun frame(keyFrame: Boolean, bytes: ByteArray) = EncodedVideoFrame(
        data = bytes,
        offset = 0,
        length = bytes.size,
        width = 1920,
        height = 1080,
        frameRate = 30,
        presentationTimeMs = 1,
        isKeyFrame = keyFrame,
    )

    private fun annexB(vararg nalTypes: Int): ByteArray = nalTypes.flatMap { type ->
        listOf(0, 0, 0, 1, type, 1)
    }.map(Int::toByte).toByteArray()
}
