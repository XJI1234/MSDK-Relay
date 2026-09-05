package com.skycommand.relay.wayline.android

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.SAXException

internal enum class SingleWaylineKmzRejection {
    MISSING_OR_DUPLICATE_WAYLINES_WPML,
    UNREADABLE_OR_OVERSIZED_KMZ,
    INVALID_OR_UNSAFE_WPML,
    WAYLINE_COUNT_NOT_ONE,
}

internal data class SingleWaylineKmzInspection(
    val rejection: SingleWaylineKmzRejection?,
) {
    val accepted: Boolean get() = rejection == null
}

/** Rejects KMZ input unless it defines one unambiguous DJI WPML wayline. */
internal object SingleWaylineKmzGuard {
    fun inspect(file: File): SingleWaylineKmzInspection {
        val wpml = try {
            readWaylinesWpml(file)
        } catch (_: Throwable) {
            return SingleWaylineKmzInspection(SingleWaylineKmzRejection.UNREADABLE_OR_OVERSIZED_KMZ)
        } ?: return SingleWaylineKmzInspection(SingleWaylineKmzRejection.MISSING_OR_DUPLICATE_WAYLINES_WPML)

        val count = try {
            countDjiWaylines(wpml)
        } catch (_: Throwable) {
            return SingleWaylineKmzInspection(SingleWaylineKmzRejection.INVALID_OR_UNSAFE_WPML)
        }
        return SingleWaylineKmzInspection(
            if (count == 1) null else SingleWaylineKmzRejection.WAYLINE_COUNT_NOT_ONE,
        )
    }

    private fun readWaylinesWpml(file: File): ByteArray? =
        ZipFile(file).use { archive ->
            var wpml: ByteArray? = null
            var declaredUncompressedBytes = 0L
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val declaredSize = entry.size
                if (declaredSize < 0 || declaredUncompressedBytes > MAX_UNCOMPRESSED_KMZ_BYTES - declaredSize) {
                    throw IOException("KMZ expanded content is too large")
                }
                declaredUncompressedBytes += declaredSize
                if (!entry.isDirectory && entry.name == WAYLINES_WPML) {
                    if (wpml != null) return@use null
                    wpml = archive.getInputStream(entry).use { input -> input.readBoundedEntryBytes() }
                }
            }
            wpml
        }

    private fun countDjiWaylines(wpml: ByteArray): Int {
        require(!containsDoctypeDeclaration(wpml)) { "WPML must not contain a DOCTYPE declaration" }
        val parser = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("WPML external entities are not allowed") }
        }
        val nodes = parser
            .parse(wpml.inputStream())
            .getElementsByTagNameNS("*", "waylineId")
        var count = 0
        for (index in 0 until nodes.length) {
            if (nodes.item(index).namespaceURI?.startsWith(DJI_WPML_NAMESPACE_PREFIX) == true) count++
        }
        return count
    }

    private fun containsDoctypeDeclaration(content: ByteArray): Boolean =
        DOCTYPE_PATTERNS.any { pattern -> content.containsSequence(pattern) }

    private const val WAYLINES_WPML = "wpmz/waylines.wpml"
    private const val DJI_WPML_NAMESPACE_PREFIX = "http://www.dji.com/wpmz/"
    private const val MAX_UNCOMPRESSED_KMZ_BYTES = 16L * 1024L * 1024L
    private val DOCTYPE_PATTERNS = arrayOf(
        "<!DOCTYPE".encodeToByteArray(),
        byteArrayOf(0x00, 0x3C, 0x00, 0x21, 0x00, 0x44, 0x00, 0x4F, 0x00, 0x43, 0x00, 0x54, 0x00, 0x59, 0x00, 0x50, 0x00, 0x45),
        byteArrayOf(0x3C, 0x00, 0x21, 0x00, 0x44, 0x00, 0x4F, 0x00, 0x43, 0x00, 0x54, 0x00, 0x59, 0x00, 0x50, 0x00, 0x45, 0x00),
    )

    private fun InputStream.readBoundedEntryBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var uncompressedBytes = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) return output.toByteArray()
            uncompressedBytes += count
            if (uncompressedBytes > MAX_UNCOMPRESSED_KMZ_BYTES) {
                throw IOException("KMZ expanded content is too large")
            }
            if (count > 0) output.write(buffer, 0, count)
        }
    }
}

private fun ByteArray.containsSequence(sequence: ByteArray): Boolean {
    if (sequence.size > size) return false
    for (start in 0..size - sequence.size) {
        var offset = 0
        while (offset < sequence.size && this[start + offset] == sequence[offset]) offset++
        if (offset == sequence.size) return true
    }
    return false
}
