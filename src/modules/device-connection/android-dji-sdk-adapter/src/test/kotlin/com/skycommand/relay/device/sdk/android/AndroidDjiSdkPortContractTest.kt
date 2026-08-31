package com.skycommand.relay.device.sdk.android

import android.content.Context
import com.skycommand.relay.device.sdk.DjiSdkCallbacks
import com.skycommand.relay.device.sdk.DjiSdkPort
import com.skycommand.relay.device.sdk.PortStartResult
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidDjiSdkPortContractTest {

    @Test
    fun recordsMsdkInitializationAndRegistrationTransitionsForLinkDiagnosis() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/device/sdk/android/MsdkV5ManagerBridge.kt"),
            Path("src/modules/device-connection/android-dji-sdk-adapter/src/main/kotlin/com/skycommand/relay/device/sdk/android/MsdkV5ManagerBridge.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("[DEBUG-link-order]"))
        assertTrue(source.contains("onInitializationComplete"))
        assertTrue(source.contains("onRegistrationSuccess"))
        assertTrue(source.contains("onRegistrationFailure"))
        assertTrue(source.contains("Log.i("))
    }
    @Test
    fun factoryExposesOnlyTheDomainPortType() {
        val factory: (Context) -> DjiSdkPort = AndroidDjiSdkPort::create

        assertNotNull(factory)
    }

    @Test
    fun reportsTheFirstRegistrationSuccessOnlyOnce() {
        val bridge = FakeBridge()
        val port = AndroidDjiSdkPort(bridge)
        var ready = 0

        assertIs<PortStartResult.Accepted>(port.initialize(callback(onReady = { ready += 1 })))
        bridge.registered()
        bridge.registered()

        assertEquals(1, ready)
    }

    @Test
    fun returnsASafeRejectionWithoutCallingTheClientForBridgeRejection() {
        val bridge = FakeBridge(startResult = BridgeStartResult.Rejected)
        val port = AndroidDjiSdkPort(bridge)
        var ready = 0
        var failures = 0

        val result = port.initialize(callback(onReady = { ready += 1 }, onFailure = { failures += 1 }))

        assertEquals("DJI SDK initialization unavailable", assertIs<PortStartResult.Rejected>(result).safeReason)
        assertEquals(0, ready)
        assertEquals(0, failures)
    }

    @Test
    fun convertsABridgeThrowIntoTheSameSafeRejection() {
        val bridge = FakeBridge(throwOnInitialize = true)
        val port = AndroidDjiSdkPort(bridge)

        val result = port.initialize(callback())

        assertEquals("DJI SDK initialization unavailable", assertIs<PortStartResult.Rejected>(result).safeReason)
    }

    @Test
    fun discardsASynchronousCallbackWhenTheBridgeRejectsInitialization() {
        val bridge = FakeBridge(
            startResult = BridgeStartResult.Rejected,
            eventDuringInitialize = BridgeEvent.REGISTERED,
        )
        val port = AndroidDjiSdkPort(bridge)
        var ready = 0

        val result = port.initialize(callback(onReady = { ready += 1 }))

        assertIs<PortStartResult.Rejected>(result)
        assertEquals(0, ready)
    }

    @Test
    fun handlesASynchronousRegistrationFailureOnce() {
        val bridge = FakeBridge(eventDuringInitialize = BridgeEvent.FAILURE)
        val port = AndroidDjiSdkPort(bridge)
        var failures = 0

        assertIs<PortStartResult.Accepted>(port.initialize(callback(onFailure = { failures += 1 })))
        bridge.failed()

        assertEquals(1, failures)
    }

    @Test
    fun ignoresCallbacksFromAClosedGenerationAndAcceptsANewGeneration() {
        val bridge = FakeBridge()
        val port = AndroidDjiSdkPort(bridge)
        var oldReady = 0
        var newReady = 0

        port.initialize(callback(onReady = { oldReady += 1 }))
        val oldListener = bridge.listener!!
        port.close()
        oldListener.onRegistered()

        port.initialize(callback(onReady = { newReady += 1 }))
        oldListener.onRegistered()
        bridge.registered()

        assertEquals(0, oldReady)
        assertEquals(1, newReady)
        assertEquals(1, bridge.closeCalls)
    }

    @Test
    fun repeatedCloseAndClientCallbackFailureDoNotBlockTheNextGeneration() {
        val bridge = FakeBridge()
        val port = AndroidDjiSdkPort(bridge)

        port.initialize(callback(onReady = { error("client callback failed") }))
        bridge.registered()
        port.close()
        port.close()

        var ready = 0
        port.initialize(callback(onReady = { ready += 1 }))
        bridge.registered()

        assertEquals(1, ready)
        assertEquals(1, bridge.closeCalls)
    }

    private fun callback(
        onReady: () -> Unit = {},
        onFailure: () -> Unit = {},
    ): DjiSdkCallbacks = object : DjiSdkCallbacks {
        override fun onReady() = onReady()

        override fun onFailure() = onFailure()
    }

    private class FakeBridge(
        private val startResult: BridgeStartResult = BridgeStartResult.Accepted,
        private val throwOnInitialize: Boolean = false,
        private val eventDuringInitialize: BridgeEvent? = null,
    ) : DjiSdkManagerBridge {
        var listener: DjiSdkManagerListener? = null
        var closeCalls = 0

        override fun initialize(listener: DjiSdkManagerListener): BridgeStartResult {
            if (throwOnInitialize) error("DJI bridge failed")
            this.listener = listener
            when (eventDuringInitialize) {
                BridgeEvent.REGISTERED -> listener.onRegistered()
                BridgeEvent.FAILURE -> listener.onFailure()
                null -> Unit
            }
            return startResult
        }

        override fun close() {
            closeCalls += 1
            listener = null
        }

        fun registered() {
            listener?.onRegistered()
        }

        fun failed() {
            listener?.onFailure()
        }
    }

    private enum class BridgeEvent { REGISTERED, FAILURE }
}
