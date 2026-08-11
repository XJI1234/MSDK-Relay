package com.skycommand.relay.device.sdk.android

import android.content.Context
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback

internal interface DjiSdkManagerCallback {
    fun onInitializationComplete()

    fun onRegistrationSuccess()

    fun onRegistrationFailure()
}

internal interface DjiSdkManagerApi {
    fun init(context: Context, callback: DjiSdkManagerCallback)

    fun registerApp()
}

internal class MsdkV5ManagerBridge(
    private val context: Context,
    private val manager: DjiSdkManagerApi = AndroidDjiSdkManagerApi(),
) : DjiSdkManagerBridge {
    private val lock = Any()
    private var state = State.NEW
    private var listener: DjiSdkManagerListener? = null
    private var activeGeneration = 0L

    override fun initialize(listener: DjiSdkManagerListener): BridgeStartResult {
        val action = synchronized(lock) {
            when (state) {
                State.REGISTERED -> Action.ReportRegistered(listener)
                State.INITIALIZING,
                State.REGISTERING,
                -> {
                    this.listener = listener
                    Action.Wait
                }

                State.NEW -> {
                    this.listener = listener
                    activeGeneration += 1
                    state = State.INITIALIZING
                    Action.Initialize(activeGeneration)
                }

                State.FAILED -> {
                    Action.Reject
                }
            }
        }
        return when (action) {
            is Action.ReportRegistered -> {
                runCatching { action.listener.onRegistered() }
                BridgeStartResult.Accepted
            }

            Action.Wait -> BridgeStartResult.Accepted
            Action.Reject -> BridgeStartResult.Rejected
            is Action.Initialize -> initializeManager(action.generation)
        }
    }

    override fun close() {
        synchronized(lock) {
            activeGeneration += 1
            listener = null
            if (state != State.REGISTERED) state = State.NEW
        }
    }

    private fun initializeManager(generation: Long): BridgeStartResult = runCatching {
        manager.init(context, callbackFor(generation))
    }.fold(
        onSuccess = { BridgeStartResult.Accepted },
        onFailure = {
            rejectInitialization(generation)
            BridgeStartResult.Rejected
        },
    )

    private fun callbackFor(generation: Long) = object : DjiSdkManagerCallback {
        override fun onInitializationComplete() {
            val shouldRegister = synchronized(lock) {
                if (generation != activeGeneration || state != State.INITIALIZING) false else {
                    state = State.REGISTERING
                    true
                }
            }
            if (!shouldRegister) return
            runCatching { manager.registerApp() }
                .onFailure { reportFailure(generation) }
        }

        override fun onRegistrationSuccess() {
            reportRegistered(generation)
        }

        override fun onRegistrationFailure() {
            reportFailure(generation)
        }
    }

    private fun reportRegistered(generation: Long) {
        val target = synchronized(lock) {
            if (generation != activeGeneration || state != State.REGISTERING) {
                null
            } else {
                state = State.REGISTERED
                listener.also { listener = null }
            }
        }
        runCatching { target?.onRegistered() }
    }

    private fun reportFailure(generation: Long) {
        val target = synchronized(lock) {
            if (generation != activeGeneration || state != State.REGISTERING) {
                null
            } else {
                state = State.FAILED
                listener.also { listener = null }
            }
        }
        runCatching { target?.onFailure() }
    }

    private fun rejectInitialization(generation: Long) {
        synchronized(lock) {
            if (generation == activeGeneration && state == State.INITIALIZING) {
                state = State.FAILED
                listener = null
            }
        }
    }

    private enum class State {
        NEW,
        INITIALIZING,
        REGISTERING,
        REGISTERED,
        FAILED,
    }

    private sealed interface Action {
        data class Initialize(val generation: Long) : Action

        data class ReportRegistered(val listener: DjiSdkManagerListener) : Action

        data object Wait : Action

        data object Reject : Action
    }
}

private class AndroidDjiSdkManagerApi : DjiSdkManagerApi {
    private val manager = SDKManager.getInstance()

    override fun init(context: Context, callback: DjiSdkManagerCallback) {
        manager.init(context, object : SDKManagerCallback {
            override fun onInitProcess(event: DJISDKInitEvent?, totalProcess: Int) {
                if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) callback.onInitializationComplete()
            }

            override fun onRegisterSuccess() = callback.onRegistrationSuccess()

            override fun onRegisterFailure(error: dji.v5.common.error.IDJIError?) =
                callback.onRegistrationFailure()

            override fun onProductDisconnect(productId: Int) = Unit

            override fun onProductConnect(productId: Int) = Unit

            override fun onProductChanged(productId: Int) = Unit

            override fun onDatabaseDownloadProgress(current: Long, total: Long) = Unit
        })
    }

    override fun registerApp() {
        manager.registerApp()
    }
}
