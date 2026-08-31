package com.skycommand.relay.runtime.permission.android

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.skycommand.relay.runtime.permission.PermissionCancellation
import com.skycommand.relay.runtime.permission.PermissionKind
import com.skycommand.relay.runtime.permission.PermissionSnapshot
import com.skycommand.relay.runtime.permission.PermissionState

internal class AndroidPermissionPlatform private constructor(
    private var activity: Activity,
    private var activityResultRegistry: ActivityResultRegistry,
    private var lifecycleOwner: LifecycleOwner,
) : PermissionAdapterPlatform, DefaultLifecycleObserver {
    private val lock = Any()
    private val usbManager = activity.getSystemService(Context.USB_SERVICE) as UsbManager
    private val history: SharedPreferences = activity.getSharedPreferences(HISTORY_NAME, Context.MODE_PRIVATE)
    private val usbPermissionAction = "${activity.packageName}.permission.USB_ACCESS"
    private val declaredPermissions = loadDeclaredPermissions()
    private var runtimeLauncher: ActivityResultLauncher<Array<String>> = registerLauncher()
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                    val accessory = accessoryFrom(intent) ?: currentAccessory()
                    synchronized(lock) { attachedAccessory = accessory }
                    recordLinkDiagnostic("event=usb-accessory-attached present=${accessory != null}")
                    requestPendingUsbPermission()
                    notifyUsbPresenceChanged()
                }

                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    synchronized(lock) {
                        attachedAccessory = null
                        usbRequestInFlight = false
                    }
                    recordLinkDiagnostic("event=usb-accessory-detached")
                    notifyUsbPresenceChanged()
                }

                usbPermissionAction -> {
                    val outcome = usbPermissionOutcome(
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false),
                    )
                    recordLinkDiagnostic("event=usb-permission outcome=$outcome")
                    val callback = synchronized(lock) {
                        usbRequestInFlight = false
                        when (outcome) {
                            UsbPermissionOutcome.GRANTED -> {
                                usbFailure = null
                                usbCallback.also { usbCallback = null }
                            }

                            UsbPermissionOutcome.DENIED -> {
                                usbCallback = null
                                usbFailure.also { usbFailure = null }
                            }
                        }
                    }
                    callback?.invoke()
                }
            }
        }
    }

    private var runtimeCallback: (() -> Unit)? = null
    private var runtimeFailure: (() -> Unit)? = null
    private var usbCallback: (() -> Unit)? = null
    private var usbFailure: (() -> Unit)? = null
    private var attachedAccessory: UsbAccessory? = null
    private var usbRequestInFlight = false
    private var receiverRegistered = false
    private var closed = false
    private val usbPresenceListeners = mutableListOf<() -> Unit>()

    init {
        lifecycleOwner.lifecycle.addObserver(this)
        synchronized(lock) { attachedAccessory = currentAccessory() }
    }

    override fun onStart(owner: LifecycleOwner) {
        synchronized(lock) {
            if (closed || receiverRegistered) return
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
                addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
                addAction(usbPermissionAction)
            }
            ContextCompat.registerReceiver(
                activity.applicationContext,
                usbReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
            attachedAccessory = currentAccessory()
        }
        requestPendingUsbPermission()
    }

    override fun onStop(owner: LifecycleOwner) {
        // Keep the USB receiver registered while the Activity exists. Unregistering
        // here drops accessory attach and permission-result broadcasts whenever the
        // operator backgrounds the screen, which leaves the RC stuck disconnected.
    }

    override fun snapshot(): PermissionSnapshot = synchronized(lock) {
        check(!closed) { "Permission platform is closed" }
        PermissionSnapshot.of(
            mapOf(
                PermissionKind.RUNTIME to runtimeState(),
                PermissionKind.USB_ACCESS to usbState(),
            ),
        )
    }

    override fun requestRuntimePermissions(
        callback: () -> Unit,
        failure: () -> Unit,
    ): PermissionCancellation {
        val missing = runtimePermissions().filterNot(::isGranted)
        if (missing.isEmpty()) {
            callback()
            return PermissionCancellation { }
        }
        synchronized(lock) {
            check(!closed) { "Permission platform is closed" }
            check(runtimeCallback == null) { "Runtime permission request is already active" }
            missing.forEach { permission -> history.edit().putBoolean(historyKey(permission), true).apply() }
            runtimeCallback = callback
            runtimeFailure = failure
        }
        try {
            runtimeLauncher.launch(missing.toTypedArray())
        } catch (failure: Exception) {
            synchronized(lock) {
                runtimeCallback = null
                runtimeFailure = null
            }
            throw failure
        }
        return PermissionCancellation {
            synchronized(lock) {
                runtimeCallback = null
                runtimeFailure = null
            }
        }
    }

    override fun rebind(activity: Any, activityResultRegistry: Any, lifecycleOwner: Any) {
        rebind(
            activity as Activity,
            activityResultRegistry as ActivityResultRegistry,
            lifecycleOwner as LifecycleOwner,
        )
    }

    fun rebind(
        activity: Activity,
        activityResultRegistry: ActivityResultRegistry,
        lifecycleOwner: LifecycleOwner,
    ) {
        synchronized(lock) {
            check(!closed) { "Permission platform is closed" }
        }
        this.lifecycleOwner.lifecycle.removeObserver(this)
        runCatching { runtimeLauncher.unregister() }
        this.activity = activity
        this.activityResultRegistry = activityResultRegistry
        this.lifecycleOwner = lifecycleOwner
        runtimeLauncher = registerLauncher()
        lifecycleOwner.lifecycle.addObserver(this)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            onStart(lifecycleOwner)
        }
    }

    override fun onUsbPresenceChanged(listener: () -> Unit): PermissionCancellation {
        synchronized(lock) {
            check(!closed) { "Permission platform is closed" }
            usbPresenceListeners += listener
        }
        return PermissionCancellation {
            synchronized(lock) { usbPresenceListeners -= listener }
        }
    }

    override fun requestUsbPermission(
        callback: () -> Unit,
        failure: () -> Unit,
    ): PermissionCancellation {
        synchronized(lock) {
            check(!closed) { "Permission platform is closed" }
            check(usbCallback == null) { "USB permission request is already active" }
            usbCallback = callback
            usbFailure = failure
            attachedAccessory = currentAccessory()
        }
        requestPendingUsbPermission()
        return PermissionCancellation {
            synchronized(lock) {
                usbCallback = null
                usbFailure = null
                usbRequestInFlight = false
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runtimeCallback = null
            runtimeFailure = null
            usbCallback = null
            usbFailure = null
            usbRequestInFlight = false
            usbPresenceListeners.clear()
            if (receiverRegistered) {
                runCatching { activity.applicationContext.unregisterReceiver(usbReceiver) }
                receiverRegistered = false
            }
        }
        lifecycleOwner.lifecycle.removeObserver(this)
        runCatching { runtimeLauncher.unregister() }
    }

    private fun notifyUsbPresenceChanged() {
        val targets = synchronized(lock) { usbPresenceListeners.toList() }
        targets.forEach { listener -> runCatching { listener() } }
    }

    private fun requestPendingUsbPermission() {
        val accessory: UsbAccessory
        synchronized(lock) {
            if (closed || usbCallback == null || usbRequestInFlight) return
            accessory = attachedAccessory ?: return
            if (usbManager.hasPermission(accessory)) {
                usbFailure = null
                val callback = usbCallback.also { usbCallback = null }
                callback?.invoke()
                return
            }
            usbRequestInFlight = true
        }
        try {
            val intent = Intent(usbPermissionAction).setPackage(activity.packageName)
            val pendingIntent = PendingIntent.getBroadcast(
                activity.applicationContext,
                USB_PERMISSION_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            usbManager.requestPermission(accessory, pendingIntent)
        } catch (_: Exception) {
            val failure = synchronized(lock) {
                usbRequestInFlight = false
                usbCallback = null
                usbFailure.also { usbFailure = null }
            }
            failure?.invoke()
        }
    }

    private fun runtimeState(): PermissionState {
        return RuntimePermissionStateResolver.resolve(
            permissions = runtimePermissions(),
            isGranted = ::isGranted,
            wasRequested = { permission -> history.getBoolean(historyKey(permission), false) },
            shouldShowRationale = activity::shouldShowRequestPermissionRationale,
        )
    }

    private fun usbState(): PermissionState {
        val accessory = attachedAccessory ?: currentAccessory().also { attachedAccessory = it }
            ?: return PermissionState.UNKNOWN
        return if (usbManager.hasPermission(accessory)) PermissionState.GRANTED else PermissionState.DENIED
    }

    private fun runtimePermissions(): List<String> = AndroidRuntimePermissionPolicy
        .permissionsFor(Build.VERSION.SDK_INT)
        .filter(declaredPermissions::contains)

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private fun currentAccessory(): UsbAccessory? = usbManager.accessoryList?.firstOrNull()

    private fun loadDeclaredPermissions(): Set<String> = runCatching {
        @Suppress("DEPRECATION")
        activity.packageManager.getPackageInfo(
            activity.packageName,
            PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.orEmpty().toSet()
    }.getOrDefault(emptySet())

    private fun registerLauncher(): ActivityResultLauncher<Array<String>> =
        activityResultRegistry.register(
            RUNTIME_LAUNCHER_KEY,
            lifecycleOwner,
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            val callback = synchronized(lock) {
                runtimeCallback.also { runtimeCallback = null }
            }
            callback?.invoke()
        }

    private fun historyKey(permission: String): String = "requested.$permission"

    @Suppress("DEPRECATION")
    private fun accessoryFrom(intent: Intent): UsbAccessory? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
    } else {
        intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
    }

    companion object {
        private const val HISTORY_NAME = "android-permission-history"
        private const val RUNTIME_LAUNCHER_KEY = "skycommand.relay.runtime.permissions"
        private const val USB_PERMISSION_REQUEST_CODE = 17024

        fun attach(
            activity: Activity,
            activityResultRegistry: ActivityResultRegistry,
            lifecycleOwner: LifecycleOwner,
        ): AndroidPermissionPlatform = AndroidPermissionPlatform(activity, activityResultRegistry, lifecycleOwner)
    }
}

internal enum class UsbPermissionOutcome {
    GRANTED,
    DENIED,
}

internal fun usbPermissionOutcome(permissionGranted: Boolean): UsbPermissionOutcome =
    if (permissionGranted) UsbPermissionOutcome.GRANTED else UsbPermissionOutcome.DENIED

private const val LINK_DIAGNOSTIC_TAG = "SCLinkDiag"
private const val LINK_DIAGNOSTIC_PREFIX = "[DEBUG-link-order]"

private fun recordLinkDiagnostic(message: String) {
    runCatching { Log.i(LINK_DIAGNOSTIC_TAG, "$LINK_DIAGNOSTIC_PREFIX $message") }
}
