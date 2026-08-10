package com.skycommand.relay.telemetry.publish

import com.skycommand.relay.telemetry.snapshot.TelemetrySnapshot
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

fun interface TelemetrySink {
    fun publish(snapshot: TelemetrySnapshot): PublishTelemetryResult
}

sealed interface PublishTelemetryResult {
    data object Published : PublishTelemetryResult

    data object SkippedUnchanged : PublishTelemetryResult

    data object Rejected : PublishTelemetryResult
}

class TelemetryPublisher private constructor(
    private val sink: TelemetrySink,
) {
    private val lock = ReentrantLock()
    private var lastPublished: TelemetrySnapshot? = null

    fun publish(snapshot: TelemetrySnapshot): PublishTelemetryResult = lock.withLock {
        if (snapshot == lastPublished) return PublishTelemetryResult.SkippedUnchanged

        val result = runCatching { sink.publish(snapshot) }.getOrElse { PublishTelemetryResult.Rejected }
        if (result == PublishTelemetryResult.Published) {
            lastPublished = snapshot
        }
        result
    }

    fun reset() = lock.withLock {
        lastPublished = null
    }

    companion object {
        fun create(sink: TelemetrySink): TelemetryPublisher = TelemetryPublisher(sink)
    }
}
