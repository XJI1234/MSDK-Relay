package com.skycommand.relay.stream.dji.android

import com.skycommand.relay.stream.config.ValidatedStreamConfig
import com.skycommand.relay.stream.dji.DjiStreamPort
import com.skycommand.relay.stream.dji.StreamDjiCompletion
import com.skycommand.relay.stream.state.StreamMetrics

internal data class DjiLiveStreamFact(
    val streaming: Boolean,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val rttMillis: Int,
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
        metrics: (StreamMetrics) -> Unit,
        runtimeFailure: () -> Unit,
        completion: StreamDjiCompletion,
    ) {
        val prepared = synchronized(lock) {
            if (closed || platformOperationInFlight) null else {
                platformOperationInFlight = true
                val previous = active
                val operation = Active(++generation, metrics, runtimeFailure, completion)
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

    override fun abort() {
        val operation = synchronized(lock) {
            platformOperationInFlight = true
            active.also { active = null }
        }
        operation?.let {
            it.startCompleted()
            detach(it)
        }
        val finish = {
            synchronized(lock) { platformOperationInFlight = false }
        }
        try {
            platform.stop(object : DjiLiveStreamCompletion {
                override fun succeed() = finish()
                override fun fail() = finish()
            })
        } catch (_: Throwable) {
            finish()
        }
    }

    private fun listenerFor(operation: Active) = object : DjiLiveStreamListener {
        override fun onStatus(fact: DjiLiveStreamFact) {
            if (!fact.streaming || !isActive(operation)) return
            val resolution = if (fact.width > 0 && fact.height > 0) "${fact.width}x${fact.height}" else null
            runCatching {
                operation.metrics(
                    StreamMetrics(
                        resolution,
                        fact.fps.takeIf { it >= 0 }?.toDouble(),
                        fact.bitrateKbps.takeIf { it >= 0 }?.toDouble(),
                        fact.rttMillis.takeIf { it >= 0 }?.toLong(),
                    ),
                )
            }
        }

        override fun onError() {
            if (isActive(operation)) {
                runCatching {
                    stop(object : StreamDjiCompletion {
                        override fun succeed() = Unit
                        override fun fail() = Unit
                    })
                }
                runCatching { operation.runtimeFailure() }
            }
        }
    }

    private fun completionForStart(operation: Active) = object : DjiLiveStreamCompletion {
        override fun succeed() = finishStart(operation, true)
        override fun fail() = finishStart(operation, false)
    }

    private fun finishStart(operation: Active, succeeded: Boolean) {
        if (!operation.startCompleted()) return
        if (!succeeded) {
            synchronized(lock) { if (active === operation) active = null }
            detach(operation)
        }
        val deliver = synchronized(lock) { platformOperationInFlight = false; !closed }
        if (deliver) runCatching { if (succeeded) operation.completion.succeed() else operation.completion.fail() }
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

    private class OnceCompletion(private val delegate: StreamDjiCompletion) {
        private val lock = Any(); private var done = false
        fun succeed() = complete { delegate.succeed() }
        fun fail() = complete { delegate.fail() }
        private fun complete(action: () -> Unit) { if (synchronized(lock) { if(done) false else { done=true; true } }) runCatching(action) }
    }

    private data class Active(
        val generation: Long,
        val metrics: (StreamMetrics) -> Unit,
        val runtimeFailure: () -> Unit,
        val completion: StreamDjiCompletion,
        var listener: DjiLiveStreamListener? = null,
    ) {
        private val lock = Any(); private var completed = false
        fun startCompleted(): Boolean = synchronized(lock) { if(completed) false else { completed=true; true } }
    }

    private data class PreparedStart(val previous: Active?, val operation: Active)
    private data class StopOperation(val active: Active?)

    companion object { fun create(): DjiStreamPort = AndroidDjiStreamPort(MsdkV5LiveStreamApi()) }
}
