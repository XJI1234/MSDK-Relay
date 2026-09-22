package com.skycommand.relay.photo.dji.android

/** Serializes ownership of the adapter's mutable capture state. */
internal class CaptureAttemptGuard {
    internal class Attempt internal constructor(internal val generation: Long)

    private val lock = Any()
    private var current: Attempt? = null
    private var nextGeneration = 0L

    fun begin(): Attempt = synchronized(lock) {
        Attempt(++nextGeneration).also { current = it }
    }

    fun isCurrent(attempt: Attempt): Boolean = synchronized(lock) {
        current === attempt
    }

    fun finish(attempt: Attempt): Boolean = synchronized(lock) {
        if (current !== attempt) return@synchronized false
        current = null
        true
    }

    fun invalidate(attempt: Attempt): Boolean = finish(attempt)

    fun invalidateCurrent(): Attempt? = synchronized(lock) {
        current?.also { current = null }
    }
}
