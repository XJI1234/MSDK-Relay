package com.skycommand.relay.device.pairing.status.android

import com.skycommand.relay.device.state.PairingState
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidPairingStatusPortContractTest {
    @Test
    fun publishesMappedInitialAndLaterStates() {
        val platform = FakePlatform(initial = PlatformState.UNPAIRED)
        val received = mutableListOf<String>()
        val port = AndroidPairingStatusPort(platform)

        port.start { received += "${it.sourceRevision}:${it.state}" }
        platform.publish(PlatformState.PAIRING)
        platform.publish(PlatformState.PAIRED)
        platform.publish(PlatformState.STOP_DEV_MISMATCH)
        platform.publish(PlatformState.UNKNOWN)

        assertEquals(
            listOf("1:IDLE", "2:PAIRING", "3:PAIRED", "4:FAILED", "5:UNKNOWN"),
            received,
        )
    }

    @Test
    fun observeDoesNotSeedUnknownFromMissingKeyDefaults() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/device/pairing/status/android/MsdkV5PairingStatusApi.kt"),
            Path("src/modules/device-connection/android-pairing-status-adapter/src/main/kotlin/com/skycommand/relay/device/pairing/status/android/MsdkV5PairingStatusApi.kt"),
        ).first { it.exists() }.readText()
        assertTrue(source.contains("manager.listen(key"))
        assertFalse(source.contains("manager.getValue(key, PairingState.UNKNOWN)"))
    }

    @Test
    fun retainsOneObservationAndRejectsStaleCallbacks() {
        val platform = FakePlatform()
        val port = AndroidPairingStatusPort(platform)
        val revisions = mutableListOf<Long>()

        val subscription = port.start { revisions += it.sourceRevision }
        port.start { error("replacement listener") }
        val stale = platform.listenerOrThrow()
        subscription.cancel()
        stale.onChanged(DjiPairingStatusFact("PAIRED"))
        port.start { revisions += it.sourceRevision }
        platform.publish(PlatformState.PAIRING)
        port.stop()
        platform.listenerOrThrow().onChanged(DjiPairingStatusFact("PAIRED"))

        assertEquals(2, platform.observeCalls)
        assertEquals(listOf(1L), revisions)
    }

    @Test
    fun convertsRegistrationFailureToTheStableReasonAndContainsFailures() {
        val failure = assertFailsWith<IllegalStateException> {
            AndroidPairingStatusPort(FakePlatform(throwOnObserve = true)).start { }
        }
        assertEquals("pairing status listener unavailable", failure.message)

        val platform = FakePlatform(throwOnClose = true)
        val port = AndroidPairingStatusPort(platform)
        port.start { error("listener failure") }.cancel()
        port.stop()
        port.start { }.cancel()
    }

    private enum class PlatformState {
        UNPAIRED, PAIRING, PAIRED, STOP_THEN_SWITCH, STOP_DEV_MISMATCH, UNKNOWN,
    }

    private class FakePlatform(
        private val initial: PlatformState? = null,
        private val throwOnObserve: Boolean = false,
        private val throwOnClose: Boolean = false,
    ) : DjiPairingStatusApi {
        var observeCalls = 0
        private var listener: DjiPairingStatusListener? = null

        override fun observe(listener: DjiPairingStatusListener): DjiPairingStatusObservation {
            if (throwOnObserve) error("DJI failure")
            observeCalls += 1
            this.listener = listener
            initial?.let { listener.onChanged(it.toFact()) }
            return DjiPairingStatusObservation {
                if (throwOnClose) error("close failure")
            }
        }

        fun publish(state: PlatformState) = listenerOrThrow().onChanged(state.toFact())

        fun listenerOrThrow(): DjiPairingStatusListener = checkNotNull(listener)

        private fun PlatformState.toFact() = DjiPairingStatusFact(name)
    }
}
