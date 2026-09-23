package com.skycommand.relay.stream.dji.android

import java.util.concurrent.atomic.AtomicBoolean

enum class LiveStreamDiagnosticKind(val isFailure: Boolean = false) {
    SETTINGS_APPLIED,
    SETTINGS_FAILED(true),
    START_INVOKED,
    START_INVOCATION_FAILED(true),
    START_CALLBACK_SUCCEEDED,
    START_CALLBACK_FAILED(true),
    FIRST_STATUS,
    FIRST_VIDEO_OUTPUT,
    RUNTIME_ERROR(true),
}

data class LiveStreamDiagnosticEvent(
    val kind: LiveStreamDiagnosticKind,
    val attempt: Long,
    val fps: Int? = null,
    val bitrateKbps: Int? = null,
)

fun interface LiveStreamDiagnosticSink {
    fun record(event: LiveStreamDiagnosticEvent)
}

internal class LiveStreamStatusMilestones(
    private val attempt: Long,
    private val sink: LiveStreamDiagnosticSink,
) {
    private val firstStatus = AtomicBoolean()
    private val firstVideoOutput = AtomicBoolean()
    private val firstError = AtomicBoolean()
    private val active = AtomicBoolean(true)

    fun deactivate() {
        active.set(false)
    }

    fun onStatus(streaming: Boolean, fps: Int, bitrateKbps: Int) {
        if (!active.get()) return
        if (firstStatus.compareAndSet(false, true)) record(LiveStreamDiagnosticKind.FIRST_STATUS, fps, bitrateKbps)
        if (streaming && fps > 0 && firstVideoOutput.compareAndSet(false, true)) {
            record(LiveStreamDiagnosticKind.FIRST_VIDEO_OUTPUT, fps, bitrateKbps)
        }
    }

    fun onError() {
        if (!active.get()) return
        if (firstError.compareAndSet(false, true)) record(LiveStreamDiagnosticKind.RUNTIME_ERROR)
    }

    private fun record(kind: LiveStreamDiagnosticKind, fps: Int? = null, bitrateKbps: Int? = null) {
        runCatching { sink.record(LiveStreamDiagnosticEvent(kind, attempt, fps, bitrateKbps)) }
    }
}
