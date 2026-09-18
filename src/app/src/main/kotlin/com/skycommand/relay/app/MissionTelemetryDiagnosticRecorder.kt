package com.skycommand.relay.app

import com.skycommand.relay.diagnostics.DiagnosticJournal
import com.skycommand.relay.diagnostics.DiagnosticLevel
import com.skycommand.relay.wayline.state.MissionSnapshot

/**
 * Records the wayline facts needed to explain an accepted start/stop after disconnect.
 * It observes snapshots only; it never reads DJI, changes mission state, or controls a vehicle.
 */
internal class MissionTelemetryDiagnosticRecorder(
    private val journal: DiagnosticJournal,
) {
    private val lock = Any()
    private var previous: MissionSnapshot? = null

    fun record(next: MissionSnapshot) {
        val observation = synchronized(lock) {
            val before = previous
            previous = next
            MissionObservation(before, next)
        }
        val changed = observation.changedFacts()
        if (changed.isNotEmpty()) {
            journal.record(
                DiagnosticLevel.INFO,
                "wayline-mission",
                "WAYLINE_STATE_CHANGED",
                null,
                "missionRevision=${value(next.missionRevision)};deviceGeneration=${value(next.deviceGeneration)};changed=${changed.joinToString(",")}",
            )
        }
        val interrupt = observation.interruptFacts()
        if (interrupt != null) {
            journal.record(
                DiagnosticLevel.WARN,
                "wayline-mission",
                "WAYLINE_INTERRUPT",
                null,
                interrupt,
            )
        }
    }

    fun reset() {
        synchronized(lock) { previous = null }
    }

    private data class MissionObservation(
        val before: MissionSnapshot?,
        val after: MissionSnapshot,
    ) {
        fun changedFacts(): List<String> = buildList {
            addChange("upload", before?.upload, after.upload)
            addChange("execution", before?.execution, after.execution)
            addChange("missionDjiExecutionState", before?.missionDjiExecutionState, after.missionDjiExecutionState)
            addChange("waylineId", before?.waylineId, after.waylineId)
            addChange("currentWaypointIndex", before?.currentWaypointIndex, after.currentWaypointIndex)
            addChange("waypointActionGroup", before?.waypointActionGroup, after.waypointActionGroup)
            addChange("waypointActionId", before?.waypointActionId, after.waypointActionId)
            addChange("waypointActionPhase", before?.waypointActionPhase, after.waypointActionPhase)
            addChange("waypointActionErrorCode", before?.waypointActionErrorCode, after.waypointActionErrorCode)
            addChange("waypointActionErrorDescription", before?.waypointActionErrorDescription, after.waypointActionErrorDescription)
            addChange("waylineInterruptErrorCode", before?.waylineInterruptErrorCode, after.waylineInterruptErrorCode)
            addChange("waylineInterruptErrorDescription", before?.waylineInterruptErrorDescription, after.waylineInterruptErrorDescription)
        }

        fun interruptFacts(): String? {
            val codeChanged = before?.waylineInterruptErrorCode != after.waylineInterruptErrorCode
            val descriptionChanged = before?.waylineInterruptErrorDescription != after.waylineInterruptErrorDescription
            if (!codeChanged && !descriptionChanged) return null
            if (after.waylineInterruptErrorCode == null && after.waylineInterruptErrorDescription == null) return null
            return listOfNotNull(
                after.waylineInterruptErrorCode?.let { "djiErrorCode=$it" },
                after.waylineInterruptErrorDescription?.let { "djiErrorDescription=$it" },
            ).joinToString(";")
        }

        private fun MutableList<String>.addChange(key: String, old: Any?, next: Any?) {
            if (old != next) add("$key=${value(old)}->${value(next)}")
        }
    }

    private companion object {
        fun value(value: Any?): String = value?.toString() ?: "null"
    }
}
