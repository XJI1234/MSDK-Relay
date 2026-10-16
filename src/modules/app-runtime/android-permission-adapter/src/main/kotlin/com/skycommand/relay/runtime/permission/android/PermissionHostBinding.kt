package com.skycommand.relay.runtime.permission.android

/** Separates short-lived UI ownership from application-owned USB observation. */
internal class PermissionHostBinding<H : AutoCloseable> {
    internal class Token<H>(internal var identity: Any?, internal var host: H?)
    internal class Request<H>(val token: Token<H>, val completed: () -> Unit, val failed: () -> Unit)
    private val lock = Any()
    private var current: Token<H>? = null
    private var request: Request<H>? = null

    fun currentHost(): H? = synchronized(lock) { current?.host }
    fun currentToken(): Token<H>? = synchronized(lock) { current }

    fun bind(identity: Any, host: H): Token<H> {
        val next = Token(identity, host)
        val old: Token<H>?
        val pending: Request<H>?
        synchronized(lock) {
            old = current
            pending = request
            request = null
            current = next
        }
        release(old)
        pending?.let { runCatching { it.failed() } }
        return next
    }

    fun detach(identity: Any): Boolean {
        val old: Token<H>
        val pending: Request<H>?
        synchronized(lock) {
            old = current?.takeIf { it.identity === identity } ?: return false
            current = null
            pending = request
            request = null
        }
        release(old)
        pending?.let { runCatching { it.failed() } }
        return true
    }

    fun begin(token: Token<H>, completed: () -> Unit, failed: () -> Unit): Request<H> {
        val next = Request(token, completed, failed)
        synchronized(lock) {
            check(current === token && token.host != null) { "No current permission host" }
            check(request == null) { "Runtime permission request is already active" }
            request = next
        }
        return next
    }

    fun cancel(operation: Request<H>) {
        synchronized(lock) { if (request === operation) request = null }
    }

    fun complete(operation: Request<H>) {
        val callback = synchronized(lock) {
            if (current !== operation.token || request !== operation) return
            request?.completed.also { request = null }
        }
        callback?.invoke()
    }

    fun close() {
        val old = synchronized(lock) {
            request = null
            current.also { current = null }
        }
        release(old)
    }

    private fun release(token: Token<H>?) {
        val host = synchronized(lock) {
            token?.host.also {
                token?.identity = null
                token?.host = null
            }
        }
        host?.let { runCatching { it.close() } }
    }
}
