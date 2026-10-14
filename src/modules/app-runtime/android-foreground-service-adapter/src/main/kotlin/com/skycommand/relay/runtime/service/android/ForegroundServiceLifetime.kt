package com.skycommand.relay.runtime.service.android

/** Main-thread service ownership; terminal events consume the matching payload. */
internal class ForegroundServiceLifetime<T : Any> {
    enum class End { STOPPED, FAILED }
    data class Terminal<T>(val payload: T, val end: End)
    var activeStart: T? = null
        private set
    private var pendingStop: T? = null

    fun starting(payload: T) { activeStart = payload; pendingStop = null }
    fun stopping(payload: T) { pendingStop = payload; activeStart = null }
    fun startupFailed(): Terminal<T>? = activeStart?.let {
        activeStart = null
        Terminal(it, End.FAILED)
    }
    fun destroyed(): Terminal<T>? {
        val result = pendingStop?.let { Terminal(it, End.STOPPED) }
            ?: activeStart?.let { Terminal(it, End.FAILED) }
        pendingStop = null
        activeStart = null
        return result
    }
}
