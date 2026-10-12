package com.skycommand.relay.photo.dji.android

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class CaptureStorageEntry(val type: String?, val state: String?, val photos: Int?)

internal data class CaptureStorageSnapshot(
    val current: String?,
    val entries: List<CaptureStorageEntry>,
    val summary: String,
) {
    val selected: CaptureStorageEntry? get() = entries.firstOrNull { current != null && it.type == current }
    val ready: Boolean get() = selected?.let { it.state == "INSERTED" && (it.photos ?: 0) > 0 } == true
}

/** Re-read storage after photo settings change; never retry the shutter itself. */
internal class CaptureStorageCheck(
    private val read: ((CaptureStorageSnapshot?) -> Unit) -> Unit,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val active: () -> Boolean,
    private val ready: (CaptureStorageSnapshot) -> Unit,
    private val failed: (CaptureStorageSnapshot?) -> Unit,
) {
    private val finished = AtomicBoolean(false)
    private val latest = AtomicReference<CaptureStorageSnapshot?>(null)

    fun start() = attempt(0)

    private fun attempt(index: Int) {
        if (finished.get() || !active()) return
        val accepted = AtomicBoolean(false)
        val cancelTimeout = AtomicReference<(() -> Unit)?>(null)
        val complete: (CaptureStorageSnapshot?) -> Unit = { snapshot ->
            if (accepted.compareAndSet(false, true) && !finished.get() && active()) {
                cancelTimeout.get()?.invoke()
                if (snapshot != null) latest.set(snapshot)
                when {
                    snapshot?.ready == true -> if (finished.compareAndSet(false, true)) ready(snapshot)
                    snapshot?.selected?.state == "NOT_INSERTED" || index >= 5 ->
                        if (finished.compareAndSet(false, true)) failed(latest.get())
                    else -> schedule(350) { attempt(index + 1) }
                }
            }
        }
        cancelTimeout.set(schedule(600) { complete(null) })
        try { read(complete) } catch (_: Exception) { complete(null) }
    }
}
