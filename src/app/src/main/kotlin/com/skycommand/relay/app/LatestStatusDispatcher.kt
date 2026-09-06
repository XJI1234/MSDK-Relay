package com.skycommand.relay.app

/**
 * Delivers only the newest replaceable UI status and bounds queued main-thread work.
 * It owns presentation scheduling only; status production remains lossless upstream.
 */
internal class LatestStatusDispatcher<T : Any>(
    private val schedule: ((() -> Unit) -> Unit),
    private val render: (T) -> Unit,
) {
    private val lock = Any()
    private var latest: T? = null
    private var scheduled = false
    private var closed = false

    fun offer(value: T) {
        val shouldSchedule = synchronized(lock) {
            if (closed) {
                false
            } else {
                latest = value
                if (scheduled) false else {
                    scheduled = true
                    true
                }
            }
        }
        if (!shouldSchedule) return

        runCatching { schedule(::drain) }
            .onFailure {
                synchronized(lock) { scheduled = false }
            }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            latest = null
        }
    }

    private fun drain() {
        val value = synchronized(lock) {
            scheduled = false
            if (closed) {
                latest = null
                null
            } else {
                latest.also { latest = null }
            }
        } ?: return
        render(value)
    }
}
