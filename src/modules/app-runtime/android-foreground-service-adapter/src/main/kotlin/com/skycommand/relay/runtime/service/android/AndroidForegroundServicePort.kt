package com.skycommand.relay.runtime.service.android

import android.content.Context
import com.skycommand.relay.runtime.service.ForegroundServiceCallback
import com.skycommand.relay.runtime.service.ForegroundServicePort
import java.util.UUID

internal interface ForegroundServicePlatform : AutoCloseable {
    override fun close() = Unit

    fun start(operationId: String, callback: (ForegroundServicePlatformEvent) -> Unit)
    fun stop(operationId: String, callback: (ForegroundServicePlatformEvent) -> Unit)
}

internal sealed interface ForegroundServicePlatformEvent {
    val operationId: String
    data class Started(override val operationId: String) : ForegroundServicePlatformEvent
    data class Stopped(override val operationId: String) : ForegroundServicePlatformEvent
    data class Failed(override val operationId: String) : ForegroundServicePlatformEvent
}

class AndroidForegroundServicePort internal constructor(
    private val platform: ForegroundServicePlatform,
) : ForegroundServicePort, AutoCloseable {
    private val lock = Any()
    private var active: Active? = null
    private var closed = false

    override fun start(callback: ForegroundServiceCallback) = begin(callback, true)

    override fun stop(callback: ForegroundServiceCallback) = begin(callback, false)

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            active = null
        }
        platform.close()
    }

    private fun begin(callback: ForegroundServiceCallback, starting: Boolean) {
        val operation = synchronized(lock) {
            check(!closed) { "Foreground service port is closed" }
            check(active == null) { "A foreground service operation is already active" }
            Active(UUID.randomUUID().toString(), starting, callback).also { active = it }
        }
        try {
            val receive: (ForegroundServicePlatformEvent) -> Unit = { event -> complete(operation, event) }
            if (starting) platform.start(operation.id, receive) else platform.stop(operation.id, receive)
        } catch (failure: Exception) {
            complete(operation, ForegroundServicePlatformEvent.Failed(operation.id))
            throw failure
        }
    }

    private fun complete(operation: Active, event: ForegroundServicePlatformEvent) {
        if (event.operationId != operation.id) return
        if (event is ForegroundServicePlatformEvent.Started && !operation.starting) return
        if (event is ForegroundServicePlatformEvent.Stopped && operation.starting) return
        val accepted = synchronized(lock) {
            if (closed || active !== operation) false else { active = null; true }
        }
        if (!accepted) return
        runCatching {
            when (event) {
                is ForegroundServicePlatformEvent.Failed -> operation.callback.failed()
                is ForegroundServicePlatformEvent.Started -> operation.callback.started()
                is ForegroundServicePlatformEvent.Stopped -> operation.callback.stopped()
            }
        }
    }

    private data class Active(
        val id: String,
        val starting: Boolean,
        val callback: ForegroundServiceCallback,
    )

    companion object {
        fun create(
            context: Context,
            notificationSpec: ForegroundNotificationSpec,
        ): AndroidForegroundServicePort =
            AndroidForegroundServicePort(
                AndroidForegroundServicePlatform.create(context, notificationSpec),
            )
    }
}
