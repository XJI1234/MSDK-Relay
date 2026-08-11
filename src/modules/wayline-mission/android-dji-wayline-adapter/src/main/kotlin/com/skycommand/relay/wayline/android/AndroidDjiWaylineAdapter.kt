package com.skycommand.relay.wayline.android

import android.content.Context
import com.skycommand.relay.wayline.executor.ControlCompletion
import com.skycommand.relay.wayline.executor.MissionControlPort
import com.skycommand.relay.wayline.staging.MissionMetadata
import com.skycommand.relay.wayline.uploader.MissionUploadPort
import com.skycommand.relay.wayline.uploader.UploadCompletion
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

internal class StoredMissionFile(val path: String, val fileName: String, private val deleteAction: () -> Unit) {
    fun delete() = runCatching(deleteAction)
}

internal fun interface MissionFileStore { fun write(fileName: String, content: ByteArray): StoredMissionFile }
internal interface DjiUploadCompletion { fun progress(value: Double); fun succeed(); fun fail() }
internal interface DjiControlCompletion { fun succeed(); fun fail() }
internal interface DjiWaypointMissionApi {
    fun upload(path: String, completion: DjiUploadCompletion)
    fun start(name: String, completion: DjiControlCompletion)
    fun pause(completion: DjiControlCompletion)
    fun resume(completion: DjiControlCompletion)
    fun stop(name: String, completion: DjiControlCompletion)
    fun close()
}

class AndroidDjiWaylineAdapter internal constructor(
    private val files: MissionFileStore,
    private val dji: DjiWaypointMissionApi,
) : MissionUploadPort, MissionControlPort {
    private val lock = Any()
    private var generation = 0L
    private var closed = false
    private var uploadedName: String? = null
    private var activeFile: StoredMissionFile? = null

    override fun upload(metadata: MissionMetadata, bytes: ByteArray, progress: (Int) -> Unit, completion: UploadCompletion) {
        if (!metadata.fileName.isSafeKmzName()) return safeFail(completion)
        val file = runCatching { files.write(metadata.fileName, bytes) }.getOrElse { return safeFail(completion) }
        val operationGeneration = synchronized(lock) {
            if (closed) null else (++generation).also { activeFile?.delete(); activeFile = file }
        } ?: run { file.delete(); return safeFail(completion) }
        val once = OnceUpload(completion)
        try {
            dji.upload(file.path, object : DjiUploadCompletion {
                override fun progress(value: Double) {
                    if (value.isFinite() && isCurrent(operationGeneration)) runCatching { progress(value.roundToInt().coerceIn(0, 100)) }
                }
                override fun succeed() = finishUpload(operationGeneration, file, once, true)
                override fun fail() = finishUpload(operationGeneration, file, once, false)
            })
        } catch (_: Throwable) {
            finishUpload(operationGeneration, file, once, false)
        }
    }

    override fun start(completion: ControlCompletion) = withName(completion) { name, done -> dji.start(name, done) }
    override fun stop(completion: ControlCompletion) = withName(completion) { name, done -> dji.stop(name, done) }
    override fun pause(completion: ControlCompletion) = control(completion) { dji.pause(it) }
    override fun resume(completion: ControlCompletion) = control(completion) { dji.resume(it) }

    fun close() {
        val file = synchronized(lock) { if (closed) null else { closed = true; generation++; activeFile.also { activeFile = null } } }
        file?.delete(); runCatching { dji.close() }
    }

    private fun finishUpload(generation: Long, file: StoredMissionFile, completion: OnceUpload, success: Boolean) {
        if (!completion.claim()) return
        val accepted = synchronized(lock) {
            if (closed || this.generation != generation) false else {
                if (success) uploadedName = file.fileName
                if (activeFile === file) activeFile = null
                this.generation += 1
                true
            }
        }
        file.delete()
        if (accepted) completion.deliver(success)
    }

    private fun withName(completion: ControlCompletion, action: (String, DjiControlCompletion) -> Unit) {
        val name = synchronized(lock) { if (closed) null else uploadedName } ?: return safeFail(completion)
        control(completion) { action(name, it) }
    }

    private fun control(completion: ControlCompletion, action: (DjiControlCompletion) -> Unit) {
        if (synchronized(lock) { closed }) return safeFail(completion)
        val once = OnceControl(completion)
        try { action(object : DjiControlCompletion { override fun succeed()=once.succeed(); override fun fail()=once.fail() }) }
        catch (_: Throwable) { once.fail() }
    }

    private fun isCurrent(value: Long) = synchronized(lock) { !closed && generation == value }
    private fun safeFail(completion: UploadCompletion) { runCatching { completion.fail() } }
    private fun safeFail(completion: ControlCompletion) { runCatching { completion.fail() } }

    private class OnceUpload(private val delegate: UploadCompletion) { private val lock=Any();private var done=false
        fun claim():Boolean=synchronized(lock){if(done)false else{done=true;true}}
        fun deliver(success:Boolean)=runCatching{if(success)delegate.succeed()else delegate.fail()}}
    private class OnceControl(private val delegate: ControlCompletion) { private val lock=Any();private var done=false
        fun succeed()=complete{delegate.succeed()};fun fail()=complete{delegate.fail()};private fun complete(a:()->Unit){if(synchronized(lock){if(done)false else{done=true;true}})runCatching(a)}}

    companion object {
        fun create(context: Context): AndroidDjiWaylineAdapter = AndroidDjiWaylineAdapter(AndroidMissionFileStore(context.applicationContext), MsdkV5WaypointMissionApi())
    }
}

private fun String.isSafeKmzName(): Boolean = isNotBlank() && codePointCount(0, length) <= 255 &&
    none(Char::isISOControl) && !contains('/') && !contains('\\') && this != "." && this != ".." &&
    endsWith(".kmz", ignoreCase = true) && File(this).name == this

private class AndroidMissionFileStore(context: Context) : MissionFileStore {
    private val directory = File(context.cacheDir, "dji-waylines")
    override fun write(fileName: String, content: ByteArray): StoredMissionFile {
        check(directory.exists() || directory.mkdirs())
        val file = File(directory, "${UUID.randomUUID()}-$fileName")
        file.outputStream().use { it.write(content) }
        return StoredMissionFile(file.absolutePath, fileName) { file.delete() }
    }
}
