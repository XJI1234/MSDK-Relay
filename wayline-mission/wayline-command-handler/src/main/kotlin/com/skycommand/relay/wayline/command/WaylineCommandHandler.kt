package com.skycommand.relay.wayline.command

import com.skycommand.relay.protocol.CommandFrame
import com.skycommand.relay.protocol.JsonArray
import com.skycommand.relay.protocol.JsonBoolean
import com.skycommand.relay.protocol.JsonNumber
import com.skycommand.relay.protocol.JsonObject
import com.skycommand.relay.protocol.JsonString
import com.skycommand.relay.wayline.generate.GenerationResult
import com.skycommand.relay.wayline.generate.WaylinePlan
import com.skycommand.relay.wayline.generate.Waypoint
import com.skycommand.relay.wayline.generate.WpmzGenerator
import com.skycommand.relay.wayline.staging.MissionMetadata
import com.skycommand.relay.wayline.staging.MissionStaging
import com.skycommand.relay.wayline.staging.StagingCompleteResult
import com.skycommand.relay.wayline.staging.StagingRequestResult

interface WaylineCommandActions {
    fun upload(completion: WaylineActionCompletion): WaylineActionResult
    fun start(completion: WaylineActionCompletion): WaylineActionResult
    fun pause(completion: WaylineActionCompletion): WaylineActionResult
    fun resume(completion: WaylineActionCompletion): WaylineActionResult
    fun stop(completion: WaylineActionCompletion): WaylineActionResult
}

fun interface WaylineActionCompletion {
    fun complete(outcome: WaylineActionTerminalOutcome)
}

enum class WaylineActionTerminalOutcome {
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
}

sealed interface WaylineActionResult {
    data object Accepted : WaylineActionResult
    data object Rejected : WaylineActionResult
}

sealed interface WaylineCommandResult {
    data class Succeeded(val detail: String) : WaylineCommandResult
    data class Accepted(val detail: String) : WaylineCommandResult
    data class Rejected(val reason: WaylineCommandRejection) : WaylineCommandResult
}

enum class WaylineCommandRejection {
    UNKNOWN_COMMAND,
    INVALID_FIELDS,
    CONFIRMATION_REQUIRED,
    GENERATION_FAILED,
    STAGING_FAILED,
    CAPABILITY_REJECTED,
}

class WaylineCommandHandler private constructor(
    private val staging: MissionStaging,
    private val actions: WaylineCommandActions,
    private val generator: WpmzGenerator,
) {
    fun handle(command: CommandFrame): WaylineCommandResult = handle(command, WaylineActionCompletion { })

    fun handle(command: CommandFrame, completion: WaylineActionCompletion): WaylineCommandResult = when (command.name) {
        "wayline.generate" -> generate(command.fields)
        "wayline.upload" -> delegate(command.fields) { actions.upload(completion) }
        "wayline.start" -> delegate(command.fields) { actions.start(completion) }
        "wayline.pause" -> delegate(command.fields) { actions.pause(completion) }
        "wayline.resume" -> delegate(command.fields) { actions.resume(completion) }
        "wayline.stop" -> delegate(command.fields) { actions.stop(completion) }
        else -> WaylineCommandResult.Rejected(WaylineCommandRejection.UNKNOWN_COMMAND)
    }

    private fun generate(fields: JsonObject): WaylineCommandResult {
        val plan = parsePlan(fields) ?: return WaylineCommandResult.Rejected(WaylineCommandRejection.INVALID_FIELDS)
        val generated = generator.generate(plan)
        val artifact = (generated as? GenerationResult.Generated)?.artifact
            ?: return WaylineCommandResult.Rejected(WaylineCommandRejection.GENERATION_FAILED)
        val metadata = MissionMetadata(artifact.fileName, artifact.sizeBytes, artifact.sha256)
        if (staging.begin(metadata) !is StagingRequestResult.Accepted) {
            return WaylineCommandResult.Rejected(WaylineCommandRejection.STAGING_FAILED)
        }
        if (staging.write(artifact.bytes) !is StagingRequestResult.Accepted) {
            return WaylineCommandResult.Rejected(WaylineCommandRejection.STAGING_FAILED)
        }
        if (staging.complete() !is StagingCompleteResult.Staged) {
            return WaylineCommandResult.Rejected(WaylineCommandRejection.STAGING_FAILED)
        }
        return WaylineCommandResult.Succeeded(
            "fileName=" + artifact.fileName + ";size=" + artifact.sizeBytes + ";sha256=" + artifact.sha256,
        )
    }

    private fun delegate(fields: JsonObject, action: () -> WaylineActionResult): WaylineCommandResult {
        if (fields["confirm"] != JsonBoolean(true)) {
            return WaylineCommandResult.Rejected(WaylineCommandRejection.CONFIRMATION_REQUIRED)
        }
        return when (action()) {
            WaylineActionResult.Accepted -> WaylineCommandResult.Accepted("Operation accepted")
            WaylineActionResult.Rejected -> WaylineCommandResult.Rejected(WaylineCommandRejection.CAPABILITY_REJECTED)
        }
    }

    private fun parsePlan(fields: JsonObject): WaylinePlan? = runCatching {
        val fileName = (fields["fileName"] as? JsonString)?.value ?: return null
        val speed = (fields["speedMetersPerSecond"] as? JsonNumber)?.value?.toDoubleOrNull() ?: return null
        val points = fields["waypoints"] as? JsonArray ?: return null
        if (points.values.size !in 2..99) return null
        val waypoints = points.values.map { point ->
            val objectPoint = point as? JsonObject ?: return null
            val longitude = number(objectPoint, "longitude") ?: return null
            val latitude = number(objectPoint, "latitude") ?: return null
            val altitude = number(objectPoint, "altitude") ?: return null
            if (!longitude.isFinite() || longitude !in -180.0..180.0) return null
            if (!latitude.isFinite() || latitude !in -90.0..90.0) return null
            if (!altitude.isFinite() || altitude !in 1.0..500.0) return null
            Waypoint(longitude, latitude, altitude)
        }
        if (!speed.isFinite() || speed !in 0.1..15.0) return null
        WaylinePlan(fileName, waypoints, speed)
    }.getOrNull()

    private fun number(fields: JsonObject, name: String): Double? =
        (fields[name] as? JsonNumber)?.value?.toDoubleOrNull()

    companion object {
        fun create(
            staging: MissionStaging,
            actions: WaylineCommandActions,
            generator: WpmzGenerator = WpmzGenerator.create(),
        ): WaylineCommandHandler = WaylineCommandHandler(staging, actions, generator)
    }
}
