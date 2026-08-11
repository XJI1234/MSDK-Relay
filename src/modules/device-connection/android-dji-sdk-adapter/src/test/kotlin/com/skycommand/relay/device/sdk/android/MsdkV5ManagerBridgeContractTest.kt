package com.skycommand.relay.device.sdk.android

import android.content.ContextWrapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MsdkV5ManagerBridgeContractTest {
    @Test
    fun registersOnlyAfterInitializationCompletes() {
        val manager = FakeManager()
        val bridge = MsdkV5ManagerBridge(ContextWrapper(null), manager)
        var registered = 0
        val listener = listener(onRegistered = { registered += 1 })

        assertIs<BridgeStartResult.Accepted>(bridge.initialize(listener))
        assertEquals(1, manager.initCalls)
        assertEquals(0, manager.registerCalls)

        manager.callbackOrThrow().onInitializationComplete()
        manager.callbackOrThrow().onInitializationComplete()
        assertEquals(1, manager.registerCalls)

        manager.callbackOrThrow().onRegistrationSuccess()
        assertEquals(1, registered)
    }

    @Test
    fun reportsRegistrationFailureAndSuppressesDuplicateTerminalEvents() {
        val manager = FakeManager()
        val bridge = MsdkV5ManagerBridge(ContextWrapper(null), manager)
        var failures = 0

        bridge.initialize(listener(onFailure = { failures += 1 }))
        manager.callbackOrThrow().onInitializationComplete()
        manager.callbackOrThrow().onRegistrationFailure()
        manager.callbackOrThrow().onRegistrationFailure()

        assertEquals(1, failures)
    }

    @Test
    fun doesNotRegisterBeforeInitializationCompletes() {
        val manager = FakeManager()
        val bridge = MsdkV5ManagerBridge(ContextWrapper(null), manager)
        var registered = 0

        bridge.initialize(listener(onRegistered = { registered += 1 }))
        manager.callbackOrThrow().onRegistrationSuccess()

        assertEquals(0, registered)
        assertEquals(0, manager.registerCalls)
    }

    @Test
    fun reportsARegistrationCallbackImmediatelyWhenSdkIsAlreadyRegistered() {
        val manager = FakeManager()
        val bridge = MsdkV5ManagerBridge(ContextWrapper(null), manager)
        var first = 0
        var second = 0

        bridge.initialize(listener(onRegistered = { first += 1 }))
        manager.callbackOrThrow().onInitializationComplete()
        manager.callbackOrThrow().onRegistrationSuccess()
        bridge.initialize(listener(onRegistered = { second += 1 }))

        assertEquals(1, first)
        assertEquals(1, second)
        assertEquals(1, manager.initCalls)
        assertEquals(1, manager.registerCalls)
    }

    @Test
    fun turnsAnSdkInitThrowIntoARejectedStart() {
        val manager = FakeManager(throwOnInit = true)
        val bridge = MsdkV5ManagerBridge(ContextWrapper(null), manager)
        var failures = 0

        val result = bridge.initialize(listener(onFailure = { failures += 1 }))

        assertIs<BridgeStartResult.Rejected>(result)
        assertEquals(0, failures)
    }

    @Test
    fun turnsARegisterAppThrowIntoOneFailure() {
        val manager = FakeManager(throwOnRegister = true)
        val bridge = MsdkV5ManagerBridge(ContextWrapper(null), manager)
        var failures = 0

        bridge.initialize(listener(onFailure = { failures += 1 }))
        manager.callbackOrThrow().onInitializationComplete()
        manager.callbackOrThrow().onRegistrationFailure()

        assertEquals(1, manager.registerCalls)
        assertEquals(1, failures)
    }

    @Test
    fun closeSuppressesARegistrationCallbackFromTheOldBridgeGeneration() {
        val manager = FakeManager()
        val bridge = MsdkV5ManagerBridge(ContextWrapper(null), manager)
        var registered = 0

        bridge.initialize(listener(onRegistered = { registered += 1 }))
        bridge.close()
        manager.callbackOrThrow().onRegistrationSuccess()

        assertEquals(0, registered)
    }

    @Test
    fun closeInvalidatesOldCallbacksBeforeANewInitialization() {
        val manager = FakeManager()
        val bridge = MsdkV5ManagerBridge(ContextWrapper(null), manager)
        var newRegistration = 0

        bridge.initialize(listener())
        val staleCallback = manager.callbackOrThrow()
        bridge.close()
        staleCallback.onInitializationComplete()

        assertEquals(0, manager.registerCalls)
        assertIs<BridgeStartResult.Accepted>(bridge.initialize(listener(onRegistered = { newRegistration += 1 })))
        assertEquals(2, manager.initCalls)

        staleCallback.onInitializationComplete()
        assertEquals(0, manager.registerCalls)

        val currentCallback = manager.callbackOrThrow()
        currentCallback.onInitializationComplete()
        staleCallback.onRegistrationSuccess()
        assertEquals(0, newRegistration)

        currentCallback.onRegistrationSuccess()
        assertEquals(1, newRegistration)
    }

    private fun listener(
        onRegistered: () -> Unit = {},
        onFailure: () -> Unit = {},
    ) = object : DjiSdkManagerListener {
        override fun onRegistered() = onRegistered()

        override fun onFailure() = onFailure()
    }

    private class FakeManager(
        private val throwOnInit: Boolean = false,
        private val throwOnRegister: Boolean = false,
    ) : DjiSdkManagerApi {
        var initCalls = 0
        var registerCalls = 0
        var callback: DjiSdkManagerCallback? = null

        override fun init(context: android.content.Context, callback: DjiSdkManagerCallback) {
            if (throwOnInit) error("SDK init failed")
            initCalls += 1
            this.callback = callback
        }

        override fun registerApp() {
            registerCalls += 1
            if (throwOnRegister) error("SDK registration failed")
        }

        fun callbackOrThrow(): DjiSdkManagerCallback = checkNotNull(callback)
    }
}
