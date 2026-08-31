package com.skycommand.relay.device.sdk.android

import android.content.Context
import android.util.Log
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
                recordLinkDiagnostic("event=sdk-already-registered")
                runCatching { action.listener.onRegistered() }
                BridgeStartResult.Accepted
            }

            Action.Wait -> {
                recordLinkDiagnostic("event=sdk-initialization-wait")
                BridgeStartResult.Accepted
            }

            Action.Reject -> {
                recordLinkDiagnostic("event=sdk-initialization-rejected")
                BridgeStartResult.Rejected
            }

            is Action.Initialize -> {
                recordLinkDiagnostic("event=sdk-initialization-start generation=${action.generation}")
                initializeManager(action.generation)
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            activeGeneration += 1
            listener = null
            if (state != State.REGISTERED) state = State.NEW
        }
        recordLinkDiagnostic("event=sdk-bridge-close")
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
            recordLinkDiagnostic("event=sdk-initialization-complete generation=$generation")
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
            recordLinkDiagnostic("event=sdk-registration-success-callback generation=$generation")
            reportRegistered(generation)
        }

        override fun onRegistrationFailure() {
            recordLinkDiagnostic("event=sdk-registration-failure-callback generation=$generation")
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
        recordLinkDiagnostic("event=sdk-registration-success-applied generation=$generation accepted=${target != null}")
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
        recordLinkDiagnostic("event=sdk-registration-failure-applied generation=$generation accepted=${target != null}")
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

            override fun onProductDisconnect(productId: Int) {
                recordLinkDiagnostic("event=msdk-product-disconnect")
            }

            override fun onProductConnect(productId: Int) {
                recordLinkDiagnostic("event=msdk-product-connect")
            }

            override fun onProductChanged(productId: Int) {
                recordLinkDiagnostic("event=msdk-product-changed")
            }

            override fun onDatabaseDownloadProgress(current: Long, total: Long) = Unit
        })
    }

    override fun registerApp() {
        manager.registerApp()
    }
}

private const val LINK_DIAGNOSTIC_TAG = "SCLinkDiag"
private const val LINK_DIAGNOSTIC_PREFIX = "[DEBUG-link-order]"

private fun recordLinkDiagnostic(message: String) {
    runCatching { Log.i(LINK_DIAGNOSTIC_TAG, "$LINK_DIAGNOSTIC_PREFIX $message") }
}
