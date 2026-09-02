package com.skycommand.relay.stream.dji.android

import com.skycommand.relay.stream.config.ValidatedStreamConfig
import com.skycommand.relay.stream.dji.DjiStreamPort
import com.skycommand.relay.stream.dji.DjiStreamStatus
import com.skycommand.relay.stream.dji.StreamDjiCompletion
import com.skycommand.relay.stream.state.StreamMetrics

internal data class DjiLiveStreamFact(
    val streaming: Boolean,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val rttMillis: Int,
    val packetLoss: Int = 0,
    val packetCacheLength: Int = 0,
)

internal interface DjiLiveStreamListener {
    fun onStatus(fact: DjiLiveStreamFact)
    fun onError()
}

internal interface DjiLiveStreamCompletion {
    fun succeed()
    fun fail()
}

internal interface DjiLiveStreamApi {
    fun start(url: String, listener: DjiLiveStreamListener, completion: DjiLiveStreamCompletion)
    fun stop(completion: DjiLiveStreamCompletion)
    fun removeListener(listener: DjiLiveStreamListener)
}

class AndroidDjiStreamPort internal constructor(
    private val platform: DjiLiveStreamApi,
) : DjiStreamPort {
    private val lock = Any()
    private var generation = 0L
    private var active: Active? = null
    private var platformOperationInFlight = false
    private var closed = false

    override fun start(
        config: ValidatedStreamConfig,
        status: (DjiStreamStatus) -> Unit,
        runtimeFailure: () -> Unit,
        completion: StreamDjiCompletion,
    ) {
        val prepared = synchronized(lock) {
            if (closed || platformOperationInFlight) null else {
                platformOperationInFlight = true
                val previous = active
                val operation = Active(++generation, status, runtimeFailure, completion)
                active = operation
                operation.listener = listenerFor(operation)
                PreparedStart(previous, operation)
            }
        }
        if (prepared == null) {
            runCatching { completion.fail() }
            return
        }
        prepared.previous?.let(::detach)
        try {
            platform.start(config.rtmpUrl, prepared.operation.listener!!, completionForStart(prepared.operation))
        } catch (_: Throwable) {
            finishStart(prepared.operation, false)
        }
    }

    override fun stop(completion: StreamDjiCompletion) {
        val operation = synchronized(lock) {
            if (closed || platformOperationInFlight) null else {
                platformOperationInFlight = true
                StopOperation(active)
            }
        }
        if (operation == null) {
            runCatching { completion.fail() }
            return
        }
        val once = OnceCompletion(completion)
        try {
            platform.stop(object : DjiLiveStreamCompletion {
                override fun succeed() = finishStop(operation.active, once, true)
                override fun fail() = finishStop(operation.active, once, false)
            })
        } catch (_: Throwable) {
            finishStop(operation.active, once, false)
        }
    }

    private fun listenerFor(operation: Active) = object : DjiLiveStreamListener {
        override fun onStatus(fact: DjiLiveStreamFact) {
            if (!isActive(operation)) return
            operation.report(statusOf(fact))?.let { status -> runCatching { operation.status(status) } }
        }

        override fun onError() {
            if (isActive(operation)) {
                runCatching { operation.runtimeFailure() }
            }
        }
    }

    private fun completionForStart(operation: Active) = object : DjiLiveStreamCompletion {
        override fun succeed() = finishStart(operation, true)
        override fun fail() = finishStart(operation, false)
    }

    private fun finishStart(operation: Active, succeeded: Boolean) {
        if (!operation.startCompleted(succeeded)) return
        if (!succeeded) {
            synchronized(lock) { if (active === operation) active = null }
            detach(operation)
        }
        val deliver = synchronized(lock) { platformOperationInFlight = false; !closed }
        if (deliver) runCatching { if (succeeded) operation.completion.succeed() else operation.completion.fail() }
        if (deliver && succeeded) operation.markStartCompletionDelivered()?.let { status ->
            runCatching { operation.status(status) }
        }
    }

    override fun close() {
        val operation = synchronized(lock) {
            if (closed) return
            closed = true
            active.also { active = null }
        }
        operation?.listener?.let { runCatching { platform.removeListener(it) } }
        if (operation != null) runCatching {
            platform.stop(object : DjiLiveStreamCompletion {
                override fun succeed() = Unit
                override fun fail() = Unit
            })
        }
    }

    private fun finishStop(operation: Active?, completion: OnceCompletion, succeeded: Boolean) {
        val detach = synchronized(lock) {
            if (succeeded && operation != null && active === operation) {
                active = null
                operation
            } else null
        }
        detach?.let(::detach)
        val deliver = synchronized(lock) { platformOperationInFlight = false; !closed }
        if (deliver) {
            if (succeeded) completion.succeed() else completion.fail()
        }
    }

    private fun isActive(operation: Active): Boolean = synchronized(lock) { active === operation }

    private fun detach(operation: Active) {
        operation.listener?.let { listener -> runCatching { platform.removeListener(listener) } }
    }

    private fun statusOf(fact: DjiLiveStreamFact): DjiStreamStatus {
        if (!fact.streaming) return DjiStreamStatus(false)
        val resolution = if (fact.width > 0 && fact.height > 0) "${fact.width}x${fact.height}" else null
        return DjiStreamStatus(
            isStreaming = true,
            metrics = StreamMetrics(
                resolution = resolution,
                fps = fact.fps.takeIf { it >= 0 }?.toDouble(),
                videoBitrateKbps = fact.bitrateKbps.takeIf { it >= 0 }?.toDouble(),
                rttMillis = fact.rttMillis.takeIf { it >= 0 }?.toLong(),
                packetLoss = fact.packetLoss.takeIf { it >= 0 }?.toLong(),
                packetCacheLength = fact.packetCacheLength.takeIf { it >= 0 }?.toLong(),
            ),
        )
    }

    private class OnceCompletion(private val delegate: StreamDjiCompletion) {
        private val lock = Any(); private var done = false
        fun succeed() = complete { delegate.succeed() }
        fun fail() = complete { delegate.fail() }
        private fun complete(action: () -> Unit) { if (synchronized(lock) { if(done) false else { done=true; true } }) runCatching(action) }
    }

    private data class Active(
        val generation: Long,
        val status: (DjiStreamStatus) -> Unit,
        val runtimeFailure: () -> Unit,
        val completion: StreamDjiCompletion,
        var listener: DjiLiveStreamListener? = null,
    ) {
        private val lock = Any()
        private var completed = false
        private var startSucceeded = false
        private var startCompletionDelivered = false
        private var pendingStatus: DjiStreamStatus? = null

        fun startCompleted(succeeded: Boolean): Boolean = synchronized(lock) {
            if (completed) false else {
                completed = true
                startSucceeded = succeeded
                true
            }
        }

        fun report(status: DjiStreamStatus): DjiStreamStatus? = synchronized(lock) {
            if (!startSucceeded || !startCompletionDelivered) {
                if (status.isStreaming) pendingStatus = status
                null
            } else {
                status
            }
        }

        fun markStartCompletionDelivered(): DjiStreamStatus? = synchronized(lock) {
            if (!startSucceeded || startCompletionDelivered) null else {
                startCompletionDelivered = true
                pendingStatus.also { pendingStatus = null }
            }
        }
    }

    private data class PreparedStart(val previous: Active?, val operation: Active)
    private data class StopOperation(val active: Active?)

    companion object { fun create(): DjiStreamPort = AndroidDjiStreamPort(MsdkV5LiveStreamApi()) }
}
