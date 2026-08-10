package com.skycommand.relay.wayline.generate

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class Waypoint(
    val longitude: Double,
    val latitude: Double,
    val altitudeMeters: Double,
)

data class WaylinePlan(
    val fileName: String,
    val waypoints: List<Waypoint>,
    val speedMetersPerSecond: Double,
)

class GeneratedArtifact internal constructor(
    val fileName: String,
    payload: ByteArray,
    val sha256: String,
) {
    private val payload = payload.copyOf()

    val sizeBytes: Long
        get() = payload.size.toLong()

    val bytes: ByteArray
        get() = payload.copyOf()
}

sealed interface GenerationResult {
    data class Generated(val artifact: GeneratedArtifact) : GenerationResult
    data class Rejected(val reason: GenerationRejection) : GenerationResult
}

enum class GenerationRejection {
    INVALID_PLAN,
    GENERATION_FAILED,
}

class WpmzGenerator private constructor() {

    fun generate(plan: WaylinePlan): GenerationResult {
        if (!valid(plan)) return GenerationResult.Rejected(GenerationRejection.INVALID_PLAN)
        return try {
            val bytes = buildArchive(plan)
            GenerationResult.Generated(
                GeneratedArtifact(
                    fileName = plan.fileName,
                    payload = bytes,
                    sha256 = bytes.sha256(),
                ),
            )
        } catch (_: Exception) {
            GenerationResult.Rejected(GenerationRejection.GENERATION_FAILED)
        }
    }

    private fun buildArchive(plan: WaylinePlan): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            writeEntry(zip, TEMPLATE_ENTRY, templateKml(plan.fileName))
            writeEntry(zip, WPML_ENTRY, waylinesWpml(plan))
        }
        return output.toByteArray()
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        val entry = ZipEntry(name).apply { time = 0L }
        zip.putNextEntry(entry)
        zip.write(content.encodeToByteArray())
        zip.closeEntry()
    }

    private fun templateKml(fileName: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<kml xmlns="http://www.opengis.net/kml/2.2">""" +
            "<Document><name>" + fileName.xmlEscape() + "</name></Document></kml>"

    private fun waylinesWpml(plan: WaylinePlan): String {
        val points = plan.waypoints.mapIndexed { index, waypoint ->
            "<Placemark>" +
                "<Point><coordinates>" +
                waypoint.longitude + "," + waypoint.latitude +
                "</coordinates></Point>" +
                "<wpml:index>" + index + "</wpml:index>" +
                "<wpml:executeHeight>" + waypoint.altitudeMeters + "</wpml:executeHeight>" +
                "<wpml:waypointSpeed>" + plan.speedMetersPerSecond + "</wpml:waypointSpeed>" +
                "</Placemark>"
        }.joinToString("")
        return """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<kml xmlns="http://www.opengis.net/kml/2.2" """ +
            """xmlns:wpml="http://www.dji.com/wpmz/1.0.6">""" +
            "<Document><Folder><wpml:waylineId>0</wpml:waylineId>" +
            "<wpml:executeHeightMode>WGS84</wpml:executeHeightMode>" +
            "<wpml:autoFlightSpeed>" + plan.speedMetersPerSecond + "</wpml:autoFlightSpeed>" +
            points +
            "</Folder></Document></kml>"
    }

    companion object {
        private const val TEMPLATE_ENTRY = "wpmz/template.kml"
        private const val WPML_ENTRY = "wpmz/waylines.wpml"
        private const val MAX_WAYPOINTS = 10_000
        private const val MIN_SPEED = 0.1
        private const val MAX_SPEED = 15.0
        private const val MAX_ALTITUDE = 10_000.0

        fun create(): WpmzGenerator = WpmzGenerator()

        private fun valid(plan: WaylinePlan): Boolean =
            plan.fileName.isNotBlank() &&
                plan.fileName.endsWith(".kmz", ignoreCase = true) &&
                plan.fileName.codePointCount(0, plan.fileName.length) <= 255 &&
                plan.fileName.none { it == '/' || it == '\\' || it.isISOControl() } &&
                plan.waypoints.size in 1..MAX_WAYPOINTS &&
                plan.speedMetersPerSecond.isFinite() &&
                plan.speedMetersPerSecond in MIN_SPEED..MAX_SPEED &&
                plan.waypoints.all(::valid)

        private fun valid(waypoint: Waypoint): Boolean =
            waypoint.longitude.isFinite() &&
                waypoint.longitude in -180.0..180.0 &&
                waypoint.latitude.isFinite() &&
                waypoint.latitude in -90.0..90.0 &&
                waypoint.altitudeMeters.isFinite() &&
                waypoint.altitudeMeters in 0.0..MAX_ALTITUDE

        private fun String.xmlEscape(): String =
            replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")

        private fun ByteArray.sha256(): String =
            MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
    }
}
