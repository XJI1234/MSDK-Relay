package com.skycommand.relay.wayline.android

import android.content.Context
import com.skycommand.relay.wayline.executor.ControlCompletion
import com.skycommand.relay.wayline.executor.MissionControlPort
import com.skycommand.relay.wayline.phase.MissionExecutionSignal
import com.skycommand.relay.wayline.phase.MissionExecutionSignalListener
import com.skycommand.relay.wayline.phase.MissionExecutionSignalRegistration
import com.skycommand.relay.wayline.phase.MissionExecutionSignalSource
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
internal enum class DjiMissionExecutionState {
    PREPARING,
    ENTER_WAYLINE,
    EXECUTING,
    PAUSED,
    COMPLETED,
    INTERRUPTED,
    IDLE,
    DISCONNECTED,
    UNKNOWN,
}
internal fun interface DjiExecutionStateRegistration { fun unregister() }
internal interface DjiWaypointMissionApi {
    fun upload(path: String, completion: DjiUploadCompletion)
    fun start(name: String, completion: DjiControlCompletion)
    fun pause(completion: DjiControlCompletion)
    fun resume(completion: DjiControlCompletion)
    fun stop(name: String, completion: DjiControlCompletion)
    fun onExecutionState(listener: (DjiMissionExecutionState) -> Unit): DjiExecutionStateRegistration
    fun close()
}

class AndroidDjiWaylineAdapter internal constructor(
    private val files: MissionFileStore,
    private val dji: DjiWaypointMissionApi,
) : MissionUploadPort, MissionControlPort, MissionExecutionSignalSource {
    private val lock = Any()
    private val submissionLock = Any()
    private var uploadGeneration = 0L
    private var controlGeneration = 0L
    private var closed = false
    private var uploadedName: String? = null
    private val uploadFiles = mutableMapOf<Long, StoredMissionFile>()
    private val signalListeners = mutableSetOf<SignalListenerSlot>()
    private var djiExecutionRegistration: DjiExecutionStateRegistration? = null
    private var startSignalsEnabled = false

    override fun upload(metadata: MissionMetadata, bytes: ByteArray, progress: (Int) -> Unit, completion: UploadCompletion) {
        if (!metadata.fileName.isSafeKmzName()) return safeFail(completion)
        val file = runCatching { files.write(metadata.fileName, bytes) }.getOrElse { return safeFail(completion) }
        val once = OnceUpload(completion)
        val operationGeneration = synchronized(lock) {
            if (closed) null else (++uploadGeneration).also { uploadFiles[it] = file }
        }
        if (operationGeneration == null) {
            file.delete()
            safeFail(completion)
            return
        }
        val callback = object : DjiUploadCompletion {
            override fun progress(value: Double) {
                if (value.isFinite() && isCurrentUpload(operationGeneration)) {
                    runCatching { progress(value.roundToInt().coerceIn(0, 100)) }
                }
            }
            override fun succeed() = finishUpload(operationGeneration, file, once, true)
            override fun fail() = finishUpload(operationGeneration, file, once, false)
        }
        synchronized(submissionLock) {
            if (isCurrentUpload(operationGeneration)) {
                try {
                    dji.upload(file.path, callback)
                } catch (_: Throwable) {
                    finishUpload(operationGeneration, file, once, false)
                }
            }
        }
    }

    override fun start(completion: ControlCompletion) {
        val subscribed = synchronized(submissionLock) { ensureExecutionStateSubscription() }
        if (!subscribed) {
            safeFail(completion)
            return
        }
        withName(completion) { name, done -> dji.start(name, done) }
    }
    override fun stop(completion: ControlCompletion) = withName(completion) { name, done -> dji.stop(name, done) }
    override fun pause(completion: ControlCompletion) = control(completion) { _, done -> dji.pause(done) }
    override fun resume(completion: ControlCompletion) = control(completion) { _, done -> dji.resume(done) }

    override fun onSignal(listener: MissionExecutionSignalListener): MissionExecutionSignalRegistration {
        val slot = SignalListenerSlot(listener)
        synchronized(lock) {
            if (!closed) signalListeners += slot
        }
        return MissionExecutionSignalRegistration {
            synchronized(lock) { signalListeners.remove(slot) }
        }
    }

    override fun beginStartAttempt() {
        synchronized(lock) {
            if (!closed) startSignalsEnabled = false
        }
    }

    override fun confirmStartAttempt() {
        synchronized(lock) {
            if (!closed) startSignalsEnabled = true
        }
    }

    override fun invalidateStartAttempt() {
        synchronized(lock) {
            startSignalsEnabled = false
        }
    }

    fun close() {
        synchronized(submissionLock) {
            val (files, registration) = synchronized(lock) {
                if (closed) return
                closed = true
                uploadGeneration++
                controlGeneration++
                signalListeners.clear()
                uploadFiles.values.toList().also { uploadFiles.clear() } to
                    djiExecutionRegistration.also { djiExecutionRegistration = null }
            }
            files.forEach(StoredMissionFile::delete)
            registration?.let { runCatching { it.unregister() } }
            runCatching { dji.close() }
        }
    }

    private fun ensureExecutionStateSubscription(): Boolean {
        synchronized(lock) {
            if (closed) return false
            if (djiExecutionRegistration != null) return true
        }
        val registration = runCatching { dji.onExecutionState(::dispatchExecutionState) }.getOrNull() ?: return false
        val retained = synchronized(lock) {
            if (closed || djiExecutionRegistration != null) false else {
                djiExecutionRegistration = registration
                true
            }
        }
        if (!retained) runCatching { registration.unregister() }
        return retained
    }

    private fun dispatchExecutionState(state: DjiMissionExecutionState) {
        val listeners = synchronized(lock) {
            if (closed || !startSignalsEnabled) emptyList() else signalListeners.toList()
        }
        val signal = state.toMissionExecutionSignal()
        listeners.forEach { runCatching { it.listener.onSignal(signal) } }
    }

    private fun finishUpload(generation: Long, file: StoredMissionFile, completion: OnceUpload, success: Boolean) {
        val (shouldDelete, accepted) = synchronized(lock) {
            val ownedFile = uploadFiles.remove(generation) === file
            if (!ownedFile || !completion.claim() || closed || uploadGeneration != generation) ownedFile to false else {
                if (success) uploadedName = file.fileName
                uploadGeneration++
                true to true
            }
        }
        if (shouldDelete) file.delete()
        if (accepted) completion.deliver(success)
    }

    private fun withName(completion: ControlCompletion, action: (String, DjiControlCompletion) -> Unit) {
        control(completion, true) { name, done -> action(requireNotNull(name), done) }
    }

    private fun control(completion: ControlCompletion, requireName: Boolean = false, action: (String?, DjiControlCompletion) -> Unit) {
        val once = OnceControl(completion)
        val prepared = synchronized(lock) {
            val name = uploadedName
            if (closed || (requireName && name == null)) {
                null
            } else {
                val operationGeneration = ++controlGeneration
                val callback = object : DjiControlCompletion {
                    override fun succeed() = finishControl(operationGeneration, once, true)
                    override fun fail() = finishControl(operationGeneration, once, false)
                }
                PreparedControl(operationGeneration, name, callback)
            }
        }
        if (prepared == null) {
            once.fail()
            return
        }
        synchronized(submissionLock) {
            if (isCurrentControl(prepared.generation)) {
                try {
                    action(prepared.name, prepared.callback)
                } catch (_: Throwable) {
                    finishControl(prepared.generation, once, false)
                }
            }
        }
    }

    private fun finishControl(generation: Long, completion: OnceControl, success: Boolean) {
        val accepted = synchronized(lock) {
            if (closed || controlGeneration != generation || !completion.claim()) false
            else { controlGeneration++; true }
        }
        if (accepted) completion.deliver(success)
    }

    private fun isCurrentUpload(value: Long) = synchronized(lock) { !closed && uploadGeneration == value }
    private fun isCurrentControl(value: Long) = synchronized(lock) { !closed && controlGeneration == value }
    private fun safeFail(completion: UploadCompletion) { runCatching { completion.fail() } }
    private fun safeFail(completion: ControlCompletion) { runCatching { completion.fail() } }

    private class OnceUpload(private val delegate: UploadCompletion) { private val lock=Any();private var done=false
        fun claim():Boolean=synchronized(lock){if(done)false else{done=true;true}}
        fun deliver(success:Boolean)=runCatching{if(success)delegate.succeed()else delegate.fail()}}
    private class OnceControl(private val delegate: ControlCompletion) { private val lock=Any();private var done=false
        fun claim():Boolean=synchronized(lock){if(done)false else{done=true;true}}
        fun deliver(success:Boolean)=runCatching{if(success)delegate.succeed()else delegate.fail()}
        fun fail(){if(claim())deliver(false)}}

    private data class PreparedControl(
        val generation: Long,
        val name: String?,
        val callback: DjiControlCompletion,
    )

    private data class SignalListenerSlot(val listener: MissionExecutionSignalListener)

    companion object {
        fun create(context: Context): AndroidDjiWaylineAdapter = AndroidDjiWaylineAdapter(AndroidMissionFileStore(context.applicationContext), MsdkV5WaypointMissionApi())
    }
}

private fun DjiMissionExecutionState.toMissionExecutionSignal(): MissionExecutionSignal = when (this) {
    DjiMissionExecutionState.PREPARING -> MissionExecutionSignal.PREPARING
    DjiMissionExecutionState.ENTER_WAYLINE -> MissionExecutionSignal.ENTER_WAYLINE
    DjiMissionExecutionState.EXECUTING -> MissionExecutionSignal.EXECUTING
    DjiMissionExecutionState.PAUSED -> MissionExecutionSignal.PAUSED
    DjiMissionExecutionState.COMPLETED -> MissionExecutionSignal.COMPLETED
    DjiMissionExecutionState.INTERRUPTED -> MissionExecutionSignal.INTERRUPTED
    DjiMissionExecutionState.IDLE -> MissionExecutionSignal.IDLE
    DjiMissionExecutionState.DISCONNECTED -> MissionExecutionSignal.DISCONNECTED
    DjiMissionExecutionState.UNKNOWN -> MissionExecutionSignal.UNKNOWN
}

private const val MAX_RELAY_FILE_NAME_CODE_POINTS = 128

private fun String.isSafeKmzName(): Boolean = isNotBlank() && codePointCount(0, length) <= MAX_RELAY_FILE_NAME_CODE_POINTS &&
    none(Char::isISOControl) && !contains('/') && !contains('\\') && this != "." && this != ".." &&
    endsWith(".kmz", ignoreCase = true) && File(this).name == this

private class AndroidMissionFileStore(context: Context) : MissionFileStore {
    private val directory = File(context.cacheDir, "dji-waylines")
    override fun write(fileName: String, content: ByteArray): StoredMissionFile = writeMissionFile(directory, fileName, content)
}

internal fun writeMissionFile(
    directory: File,
    fileName: String,
    content: ByteArray,
    writer: (File, ByteArray) -> Unit = { file, bytes -> file.outputStream().use { it.write(bytes) } },
): StoredMissionFile {
    check(directory.exists() || directory.mkdirs())
    val operationDirectory = File(directory, UUID.randomUUID().toString())
    check(operationDirectory.mkdir())
    val file = File(operationDirectory, fileName)
    return try {
        writer(file, content)
        StoredMissionFile(file.absolutePath, fileName) { operationDirectory.deleteRecursively() }
    } catch (failure: Throwable) {
        operationDirectory.deleteRecursively()
        throw failure
    }
}
