package com.skycommand.relay.app

import com.skycommand.relay.runtime.RuntimeState
import com.skycommand.relay.runtime.service.ForegroundServiceState

/** Keeps ports/executors alive until calls have returned and both lifecycles settle. */
internal class GraphReleaseCoordinator(
    private val runtime: () -> RuntimeState,
    private val foreground: () -> ForegroundServiceState,
    private val schedule: (() -> Unit) -> Unit,
    private val stop: () -> Unit,
    private val release: () -> Unit,
) {
    private val lock = Any()
    private var operations = 0
    private var closing = false
    private var scheduled = false
    private var stopStarted = false
    private var stopReturned = false
    private var released = false

    fun enter(): Boolean = synchronized(lock) {
        if (closing) false else { operations++; true }
    }

    fun leave() {
        synchronized(lock) { check(operations > 0); operations-- }
        changed()
    }

    fun close() {
        synchronized(lock) { closing = true }
        changed()
    }

    fun changed() {
        val enqueue = synchronized(lock) {
            if (!closing || released || scheduled || operations != 0) false
            else { scheduled = true; true }
        }
        if (enqueue) {
            try { schedule(::drain) }
            catch (failure: Exception) {
                synchronized(lock) { scheduled = false }
                throw failure
            }
        }
    }

    private fun drain() {
        val shouldStop = synchronized(lock) {
            scheduled = false
            if (released || operations != 0) return
            if (!stopStarted) { stopStarted = true; true } else false
        }
        if (shouldStop) {
            try { stop() }
            finally {
                synchronized(lock) { stopReturned = true }
                changed()
            }
            return
        }
        synchronized(lock) { if (!stopReturned || released) return }
        val runtimeTerminal = runtime() in setOf(RuntimeState.STOPPED, RuntimeState.FAILED)
        val foregroundTerminal = foreground() in setOf(ForegroundServiceState.STOPPED, ForegroundServiceState.FAILED)
        if (!runtimeTerminal || !foregroundTerminal) return
        val shouldRelease = synchronized(lock) {
            if (released) false else { released = true; true }
        }
        if (shouldRelease) release()
    }
}
