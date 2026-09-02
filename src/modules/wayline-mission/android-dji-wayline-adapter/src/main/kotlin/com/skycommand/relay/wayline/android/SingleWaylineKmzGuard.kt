package com.skycommand.relay.wayline.android

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.SAXException

/** Rejects KMZ input unless it defines one unambiguous DJI WPML wayline. */
internal object SingleWaylineKmzGuard {
    fun allows(content: ByteArray): Boolean = runCatching {
        val wpml = readWaylinesWpml(content) ?: return@runCatching false
        countDjiWaylines(wpml) == 1
    }.getOrDefault(false)

    private fun readWaylinesWpml(content: ByteArray): ByteArray? =
        BoundedZipInputStream(ByteArrayInputStream(content)).use { archive ->
            var wpml: ByteArray? = null
            while (true) {
                val entry = archive.nextEntry ?: break
                if (!entry.isDirectory && entry.name == WAYLINES_WPML) {
                    if (wpml != null) return@use null
                    wpml = archive.readAllBytes()
                }
                archive.closeEntry()
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
            .parse(ByteArrayInputStream(wpml))
            .getElementsByTagNameNS("*", "waylineId")
        var count = 0
        for (index in 0 until nodes.length) {
            if (nodes.item(index).namespaceURI?.startsWith(DJI_WPML_NAMESPACE_PREFIX) == true) count++
        }
        return count
    }

    private fun containsDoctypeDeclaration(content: ByteArray): Boolean =
        DOCTYPE_PATTERNS.any { pattern -> content.containsSequence(pattern) }

    private class BoundedZipInputStream(input: InputStream) : ZipInputStream(input) {
        private var uncompressedBytes = 0L

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = super.read(buffer, offset, length)
            if (read > 0) {
                uncompressedBytes += read
                if (uncompressedBytes > MAX_UNCOMPRESSED_KMZ_BYTES) {
                    throw IOException("KMZ expanded content is too large")
                }
            }
            return read
        }
    }

    private const val WAYLINES_WPML = "wpmz/waylines.wpml"
    private const val DJI_WPML_NAMESPACE_PREFIX = "http://www.dji.com/wpmz/"
    private const val MAX_UNCOMPRESSED_KMZ_BYTES = 16L * 1024L * 1024L
    private val DOCTYPE_PATTERNS = arrayOf(
        "<!DOCTYPE".encodeToByteArray(),
        byteArrayOf(0x00, 0x3C, 0x00, 0x21, 0x00, 0x44, 0x00, 0x4F, 0x00, 0x43, 0x00, 0x54, 0x00, 0x59, 0x00, 0x50, 0x00, 0x45),
        byteArrayOf(0x3C, 0x00, 0x21, 0x00, 0x44, 0x00, 0x4F, 0x00, 0x43, 0x00, 0x54, 0x00, 0x59, 0x00, 0x50, 0x00, 0x45, 0x00),
    )
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
