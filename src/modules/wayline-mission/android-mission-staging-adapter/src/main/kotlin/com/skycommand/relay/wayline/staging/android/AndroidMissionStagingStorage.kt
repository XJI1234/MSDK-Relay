package com.skycommand.relay.wayline.staging.android

import android.content.Context
import com.skycommand.relay.wayline.staging.MissionMetadata
import com.skycommand.relay.wayline.staging.StagingStorage
import com.skycommand.relay.wayline.uploader.StagedMissionContentReader
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class AndroidMissionStagingStorage internal constructor(
    private val directory: File,
) : StagingStorage, StagedMissionContentReader, AutoCloseable {
    private val lock = Any()
    private val temporary = File(directory, "incoming.tmp")
    private val current = File(directory, "current.kmz")
    private var output: FileOutputStream? = null
    private var temporaryMetadata: MissionMetadata? = null
    private var currentMetadata: MissionMetadata? = null

    override fun beginTemporary(metadata: MissionMetadata) = synchronized(lock) {
        closeOutput()
        temporary.delete()
        check(directory.exists() || directory.mkdirs())
        output = FileOutputStream(temporary, false)
        temporaryMetadata = metadata
    }

    override fun append(bytes: ByteArray) = synchronized(lock) {
        checkNotNull(output) { "No active mission transfer" }.write(bytes)
    }

    override fun flush() = synchronized(lock) {
        val stream = checkNotNull(output) { "No active mission transfer" }
        stream.flush()
        stream.fd.sync()
    }

    override fun replaceCurrent() = synchronized(lock) {
        val metadata = checkNotNull(temporaryMetadata) { "No active mission transfer" }
        closeOutput()
        check(temporary.isFile)
        runCatching {
            Files.move(
                temporary.toPath(),
                current.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }.getOrElse {
            Files.move(temporary.toPath(), current.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        temporaryMetadata = null
        currentMetadata = metadata
    }

    override fun deleteTemporary() = synchronized(lock) {
        closeOutput()
        temporaryMetadata = null
        temporary.delete()
        Unit
    }

    override fun read(metadata: MissionMetadata): ByteArray = synchronized(lock) {
        check(currentMetadata == metadata && current.isFile) { "Staged mission is not current" }
        current.readBytes()
    }

    override fun close() = synchronized(lock) {
        closeOutput()
        temporaryMetadata = null
        temporary.delete()
        Unit
    }

    private fun closeOutput() {
        runCatching { output?.close() }
        output = null
    }

    companion object {
        fun create(context: Context): AndroidMissionStagingStorage = AndroidMissionStagingStorage(
            File(context.applicationContext.filesDir, "relay-missions"),
        )
    }
}
