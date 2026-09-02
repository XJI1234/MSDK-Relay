package com.skycommand.relay.device.remote.android

import com.skycommand.relay.device.remote.RemoteControllerListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

class AndroidRemoteControllerPortContractTest {
    @Test
    fun publishesConnectedFactWithTheFirstPositiveRevision() {
        val platform = FakePlatform()
        val port = AndroidRemoteControllerPort(platform)
        val received = mutableListOf<String>()

        port.start(RemoteControllerListener { signal ->
            received += "${signal.sourceRevision}:${signal.connected}:${signal.displayModel}"
        })
        platform.publish(connected = true, displayModel = "RC Plus")

        assertEquals(listOf("1:true:RC Plus"), received)
    }

    @Test
    fun normalizesDisconnectedAndBlankModelsToNull() {
        val platform = FakePlatform()
        val port = AndroidRemoteControllerPort(platform)
        val received = mutableListOf<String?>()

        port.start(RemoteControllerListener { received += it.displayModel })
        platform.publish(connected = true, displayModel = "  ")
        platform.publish(connected = false, displayModel = "RC Plus")

        assertEquals(listOf<String?>(null, null), received)
    }

    @Test
    fun publishesAnInitiallyDisconnectedFactWithoutAModel() {
        val platform = FakePlatform(initialFact = Fact(false, "RC Plus"))
        val port = AndroidRemoteControllerPort(platform)
        val received = mutableListOf<String>()

        port.start(RemoteControllerListener { signal ->
            received += "${signal.sourceRevision}:${signal.connected}:${signal.displayModel}"
        })

        assertEquals(listOf("1:false:null"), received)
    }

    @Test
    fun repeatedStartKeepsTheOriginalObservationAndListener() {
        val platform = FakePlatform()
        val port = AndroidRemoteControllerPort(platform)
        var first = 0
        var second = 0

        port.start(RemoteControllerListener { first += 1 })
        port.start(RemoteControllerListener { second += 1 }).cancel()
        platform.publish(connected = true)

        assertEquals(1, platform.observeCalls)
        assertEquals(1, first)
        assertEquals(0, second)
    }

    @Test
    fun acceptsASynchronousInitialFactDuringObservationRegistration() {
        val platform = FakePlatform(initialFact = Fact(true, "RC Plus"))
        val port = AndroidRemoteControllerPort(platform)
        val received = mutableListOf<String>()

        port.start(RemoteControllerListener { signal ->
            received += "${signal.sourceRevision}:${signal.connected}:${signal.displayModel}"
        })

        assertEquals(listOf("1:true:RC Plus"), received)
    }

    @Test
    fun discardsCallbacksAfterCancelStopAndANewGeneration() {
        val platform = FakePlatform()
        val port = AndroidRemoteControllerPort(platform)
        var old = 0
        var current = 0

        val firstSubscription = port.start(RemoteControllerListener { old += 1 })
        val staleListener = platform.listenerOrThrow()
        firstSubscription.cancel()
        firstSubscription.cancel()
        staleListener.onChanged(DjiRemoteControllerFact(true, "old"))

        port.start(RemoteControllerListener { current += 1 })
        staleListener.onChanged(DjiRemoteControllerFact(true, "old"))
        port.stop()
        platform.listenerOrThrow().onChanged(DjiRemoteControllerFact(true, "stopped"))

        port.start(RemoteControllerListener { current += 1 })
        platform.publish(connected = true, displayModel = "current")

        assertEquals(0, old)
        assertEquals(1, current)
        assertEquals(2, platform.closeCalls)
    }

    @Test
    fun keepsRevisionsIncreasingAcrossStopAndRestart() {
        val platform = FakePlatform()
        val port = AndroidRemoteControllerPort(platform)
        val revisions = mutableListOf<Long>()

        port.start(RemoteControllerListener { revisions += it.sourceRevision })
        platform.publish(connected = true)
        port.stop()
        port.start(RemoteControllerListener { revisions += it.sourceRevision })
        platform.publish(connected = false)

        assertEquals(listOf(1L, 2L), revisions)
    }

    @Test
    fun convertsPlatformRegistrationFailureIntoAStableFailure() {
        val port = AndroidRemoteControllerPort(FakePlatform(throwOnObserve = true))

        val failure = assertFailsWith<IllegalStateException> {
            port.start(RemoteControllerListener { })
        }

        assertEquals("remote controller listener unavailable", failure.message)
    }

    @Test
    fun containsPlatformReleaseAndUserListenerFailures() {
        val platform = FakePlatform(throwOnClose = true)
        val port = AndroidRemoteControllerPort(platform)

        port.start(RemoteControllerListener { error("listener failed") })
        platform.publish(connected = true)
        port.stop()
        port.stop()

        var delivered = 0
        port.start(RemoteControllerListener { delivered += 1 })
        platform.publish(connected = true)

        assertEquals(1, delivered)
    }

    @Test
    fun keepsUnknownUntilTheOneTimeHardwareReadReportsItsFact() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/device/remote/android/MsdkV5RemoteControllerApi.kt"),
            Path("src/modules/device-connection/android-remote-controller-adapter/src/main/kotlin/com/skycommand/relay/device/remote/android/MsdkV5RemoteControllerApi.kt"),
        ).first { it.exists() }.readText()

        assertFalse(source.contains("publishInitialFact()"))
        assertFalse(source.contains("manager.getValue<"))
    }

    @Test
    fun requestsAnInitialHardwareValueForTheRemoteControllerWhileListening() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/device/remote/android/MsdkV5RemoteControllerApi.kt"),
            Path("src/modules/device-connection/android-remote-controller-adapter/src/main/kotlin/com/skycommand/relay/device/remote/android/MsdkV5RemoteControllerApi.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("manager.listen(connectionKey, owner)"))
        assertTrue(source.contains("requestInitialValue(connectionKey"))
        assertTrue(source.contains("requestInitialValue(typeKey"))
        assertTrue(source.contains("manager.getValue(key, object : CommonCallbacks.CompletionCallbackWithParam<T>"))
        assertTrue(source.contains("connectionEventRevision != initialEventRevision"))
        assertFalse(source.contains("manager.getValue<Boolean>(connectionKey)"))
        assertFalse(source.contains("manager.listen(connectionKey, owner, true)"))
    }

    @Test
    fun rereadsRemoteControllerTypeWhenTheControllerFirstBecomesAvailable() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/device/remote/android/MsdkV5RemoteControllerApi.kt"),
            Path("src/modules/device-connection/android-remote-controller-adapter/src/main/kotlin/com/skycommand/relay/device/remote/android/MsdkV5RemoteControllerApi.kt"),
        ).first { it.exists() }.readText()

        val connectionUpdate = source.substringAfter("private fun publishConnection(")
            .substringBefore("private fun publishType")
        assertTrue(connectionUpdate.contains("requestInitialType()"))
        assertTrue(source.contains("private var typeReadGeneration = 0L"))
        assertTrue(source.contains("typeReadGeneration != request.initialReadGeneration"))
    }

    private data class Fact(
        val connected: Boolean,
        val displayModel: String? = null,
    )

    private class FakePlatform(
        private val initialFact: Fact? = null,
        private val throwOnObserve: Boolean = false,
        private val throwOnClose: Boolean = false,
    ) : DjiRemoteControllerApi {
        var observeCalls = 0
        var closeCalls = 0
        private var listener: DjiRemoteControllerListener? = null

        override fun observe(listener: DjiRemoteControllerListener): DjiRemoteControllerObservation {
            if (throwOnObserve) error("DJI registration failure")
            observeCalls += 1
            this.listener = listener
            initialFact?.let { listener.onChanged(DjiRemoteControllerFact(it.connected, it.displayModel)) }
            return DjiRemoteControllerObservation {
                closeCalls += 1
                if (throwOnClose) error("DJI removal failure")
            }
        }

        fun publish(connected: Boolean, displayModel: String? = null) {
            listenerOrThrow().onChanged(DjiRemoteControllerFact(connected, displayModel))
        }

        fun listenerOrThrow(): DjiRemoteControllerListener = checkNotNull(listener)
    }
}
