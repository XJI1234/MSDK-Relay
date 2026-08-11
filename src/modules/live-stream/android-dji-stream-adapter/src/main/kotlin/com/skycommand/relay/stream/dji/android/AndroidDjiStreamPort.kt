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
    private val startStopLock = Any()
    private var generation = 0L
    private var active: Active? = null

    override fun start(
        config: ValidatedStreamConfig,
        metrics: (StreamMetrics) -> Unit,
        runtimeFailure: () -> Unit,
        completion: StreamDjiCompletion,
    ) {
        synchronized(startStopLock) {
            startLocked(config, metrics, runtimeFailure, completion)
        }
    }

    private fun startLocked(
        config: ValidatedStreamConfig,
        metrics: (StreamMetrics) -> Unit,
        runtimeFailure: () -> Unit,
        completion: StreamDjiCompletion,
    ) {
        val previous = synchronized(lock) { active?.also { active = null } }
        previous?.let(::detach)
        val operation = Active(++generation, metrics, runtimeFailure, completion)
        synchronized(lock) { active = operation }
        operation.listener = listenerFor(operation)
        try {
            platform.start(config.rtmpUrl, operation.listener!!, completionForStart(operation))
        } catch (_: Throwable) {
            finishStart(operation, false)
        }
    }

    override fun stop(completion: StreamDjiCompletion) {
        synchronized(startStopLock) { stopLocked(completion) }
    }

    private fun stopLocked(completion: StreamDjiCompletion) {
        val operation = synchronized(lock) { active }
        val once = OnceCompletion(completion)
        try {
            platform.stop(object : DjiLiveStreamCompletion {
                override fun succeed() {
                    if (operation != null && synchronized(lock) { active === operation }) {
                        synchronized(lock) { if (active === operation) active = null }
                        detach(operation)
                    }
                    once.succeed()
                }
                override fun fail() = once.fail()
            })
        } catch (_: Throwable) {
            once.fail()
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
            if (isActive(operation)) runCatching { operation.runtimeFailure() }
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
        runCatching { if (succeeded) operation.completion.succeed() else operation.completion.fail() }
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

    companion object { fun create(): DjiStreamPort = AndroidDjiStreamPort(MsdkV5LiveStreamApi()) }
}
