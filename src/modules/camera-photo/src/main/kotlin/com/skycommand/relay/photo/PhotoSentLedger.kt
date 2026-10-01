package com.skycommand.relay.photo

import java.io.File

interface PhotoSentLedger {
    fun names(): Set<String>
    fun remember(fileName: String)

    companion object {
        fun memory(): PhotoSentLedger = MemoryLedger()

        fun file(path: File): PhotoSentLedger = FileLedger(path)
    }
}

private class MemoryLedger : PhotoSentLedger {
    private val sent = linkedSetOf<String>()

    override fun names(): Set<String> = synchronized(sent) { sent.toSet() }

    override fun remember(fileName: String) {
        if (!safePhotoName(fileName)) return
        synchronized(sent) { sent += fileName }
    }
}

private class FileLedger(private val path: File) : PhotoSentLedger {
    override fun names(): Set<String> = synchronized(path) {
        if (!path.isFile) return emptySet()
        path.readLines()
            .map { it.trim() }
            .filter(::safePhotoName)
            .toSet()
    }

    override fun remember(fileName: String) {
        if (!safePhotoName(fileName)) return
        synchronized(path) {
            val current = names()
            if (fileName in current) return
            path.parentFile?.mkdirs()
            path.appendText("$fileName\n")
        }
    }
}

private fun safePhotoName(fileName: String): Boolean {
    val lower = fileName.lowercase()
    return fileName.isNotBlank() &&
        fileName.length <= 180 &&
        !fileName.contains("..") &&
        !fileName.contains('/') &&
        !fileName.contains('\\') &&
        !fileName.contains('\n') &&
        !fileName.contains('\r') &&
        (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".dng"))
}
