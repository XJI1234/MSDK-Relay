package com.skycommand.relay.telemetry.command

import com.skycommand.relay.telemetry.snapshot.SnapshotAssembler
import com.skycommand.relay.telemetry.snapshot.TelemetryInputs
import com.skycommand.relay.telemetry.snapshot.TelemetrySnapshot

fun interface SnapshotSource {
    fun snapshot(): TelemetryInputs
}

sealed interface TelemetryReadResult {
    data class ReadSucceeded(val snapshot: TelemetrySnapshot) : TelemetryReadResult

    data object ReadUnavailable : TelemetryReadResult
}

class TelemetryCommandHandler private constructor(
    private val snapshotSource: SnapshotSource,
) {
    fun read(): TelemetryReadResult = runCatching {
        TelemetryReadResult.ReadSucceeded(SnapshotAssembler.assemble(snapshotSource.snapshot()))
    }.getOrElse {
        TelemetryReadResult.ReadUnavailable
    }

    companion object {
        fun create(snapshotSource: SnapshotSource): TelemetryCommandHandler = TelemetryCommandHandler(snapshotSource)
    }
}
