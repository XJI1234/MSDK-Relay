package com.skycommand.relay.runtime.service.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

data class ForegroundNotificationSpec(
    val channelId: String,
    val channelNameResId: Int,
    val textResId: Int,
    val notificationId: Int,
    val smallIconResId: Int,
) {
    init {
        require(channelId.isNotBlank())
        require(notificationId > 0)
        require(channelNameResId > 0)
        require(textResId > 0)
        require(smallIconResId > 0)
    }
}

internal class AndroidForegroundServicePlatform private constructor(
    private val context: Context,
    private val spec: ForegroundNotificationSpec,
) : ForegroundServicePlatform, AutoCloseable {
    private val lock = Any()
    private var eventCallback: ((ForegroundServicePlatformEvent) -> Unit)? = null
    private var closed = false
    private var serviceRunning = false
    private val statusAction = "${context.packageName}.relay.FOREGROUND_SERVICE_STATUS"
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            dispatchStatus(intent)
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(statusAction + ACTION_STARTED_SUFFIX)
            addAction(statusAction + ACTION_STOPPED_SUFFIX)
            addAction(statusAction + ACTION_FAILED_SUFFIX)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun start(operationId: String, callback: (ForegroundServicePlatformEvent) -> Unit) {
        synchronized(lock) {
            check(!closed) { "Foreground service platform is closed" }
            eventCallback = callback
        }
        try {
            ContextCompat.startForegroundService(
                context,
                commandIntent(RelayForegroundService.ACTION_START, operationId),
            )
        } catch (failure: Exception) {
            clearCallback()
            throw failure
        }
    }

    override fun stop(operationId: String, callback: (ForegroundServicePlatformEvent) -> Unit) {
        val completeImmediately = synchronized(lock) {
            check(!closed) { "Foreground service platform is closed" }
            if (serviceRunning) {
                eventCallback = callback
                false
            } else {
                true
            }
        }
        if (completeImmediately) {
            callback(ForegroundServicePlatformEvent.Stopped(operationId))
            return
        }
        try {
            context.startService(
                commandIntent(RelayForegroundService.ACTION_STOP, operationId),
            )
        } catch (failure: Exception) {
            clearCallback()
            throw failure
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            eventCallback = null
        }
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun commandIntent(command: String, operationId: String) =
        Intent(context, RelayForegroundService::class.java).apply {
            action = command
            putExtra(RelayForegroundService.EXTRA_OPERATION_ID, operationId)
            putExtra(RelayForegroundService.EXTRA_CHANNEL_ID, spec.channelId)
            putExtra(RelayForegroundService.EXTRA_CHANNEL_NAME, spec.channelNameResId)
            putExtra(RelayForegroundService.EXTRA_TEXT, spec.textResId)
            putExtra(RelayForegroundService.EXTRA_NOTIFICATION_ID, spec.notificationId)
            putExtra(RelayForegroundService.EXTRA_ICON, spec.smallIconResId)
            putExtra(RelayForegroundService.EXTRA_STATUS_ACTION, statusAction)
        }

    private fun dispatchStatus(intent: Intent) {
        val operationId = intent.getStringExtra(RelayForegroundService.EXTRA_OPERATION_ID) ?: return
        val event = when (intent.action) {
            statusAction + ACTION_STARTED_SUFFIX -> ForegroundServicePlatformEvent.Started(operationId)
            statusAction + ACTION_STOPPED_SUFFIX -> ForegroundServicePlatformEvent.Stopped(operationId)
            statusAction + ACTION_FAILED_SUFFIX -> ForegroundServicePlatformEvent.Failed(operationId)
            else -> return
        }
        val callback = synchronized(lock) {
            if (closed) return
            when (event) {
                is ForegroundServicePlatformEvent.Started -> serviceRunning = true
                is ForegroundServicePlatformEvent.Stopped,
                is ForegroundServicePlatformEvent.Failed,
                -> serviceRunning = false
            }
            eventCallback
        }
        callback?.invoke(event)
    }

    private fun clearCallback() {
        synchronized(lock) { eventCallback = null }
    }

    companion object {
        internal fun create(
            context: Context,
            spec: ForegroundNotificationSpec,
        ): AndroidForegroundServicePlatform =
            AndroidForegroundServicePlatform(context.applicationContext, spec)

        private const val ACTION_STARTED_SUFFIX = ".STARTED"
        private const val ACTION_STOPPED_SUFFIX = ".STOPPED"
        private const val ACTION_FAILED_SUFFIX = ".FAILED"
    }
}

class RelayForegroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getStringExtra(EXTRA_OPERATION_ID) == null) return START_NOT_STICKY
        if (intent.action == ACTION_STOP) {
            pendingStop = intent
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (intent.action != ACTION_START) return START_NOT_STICKY
        try {
            startForeground(
                intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0),
                notification(intent),
            )
            status(intent, ACTION_STARTED_SUFFIX)
        } catch (_: Exception) {
            status(intent, ACTION_FAILED_SUFFIX)
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        pendingStop?.let { status(it, ACTION_STOPPED_SUFFIX) }
        pendingStop = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(intent: Intent): android.app.Notification {
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID)
            ?: error("Missing foreground notification channel")
        val channelNameResId = intent.getIntExtra(EXTRA_CHANNEL_NAME, 0)
        val textResId = intent.getIntExtra(EXTRA_TEXT, 0)
        val iconResId = intent.getIntExtra(EXTRA_ICON, 0)
        require(channelNameResId > 0 && textResId > 0 && iconResId > 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    getString(channelNameResId),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(iconResId)
            .setContentText(getString(textResId))
            .setOngoing(true)
            .build()
    }

    private fun status(intent: Intent, suffix: String) {
        val baseAction = intent.getStringExtra(EXTRA_STATUS_ACTION) ?: return
        sendBroadcast(
            Intent(baseAction + suffix)
                .setPackage(packageName)
                .putExtra(EXTRA_OPERATION_ID, intent.getStringExtra(EXTRA_OPERATION_ID)),
        )
    }

    private var pendingStop: Intent? = null

    companion object {
        const val ACTION_START = "com.skycommand.relay.START"
        const val ACTION_STOP = "com.skycommand.relay.STOP"
        const val EXTRA_OPERATION_ID = "operation"
        const val EXTRA_CHANNEL_ID = "channel"
        const val EXTRA_CHANNEL_NAME = "channelName"
        const val EXTRA_TEXT = "text"
        const val EXTRA_NOTIFICATION_ID = "notification"
        const val EXTRA_ICON = "icon"
        const val EXTRA_STATUS_ACTION = "status"

        private const val ACTION_STARTED_SUFFIX = ".STARTED"
        private const val ACTION_STOPPED_SUFFIX = ".STOPPED"
        private const val ACTION_FAILED_SUFFIX = ".FAILED"
    }
}
