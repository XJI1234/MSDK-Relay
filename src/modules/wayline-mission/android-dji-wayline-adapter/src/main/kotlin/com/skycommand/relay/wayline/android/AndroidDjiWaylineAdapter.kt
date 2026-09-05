package com.skycommand.relay.wayline.android

import android.content.Context
import android.util.Log
import com.skycommand.relay.wayline.executor.ControlCompletion
import com.skycommand.relay.wayline.executor.MissionControlFailure
import com.skycommand.relay.wayline.executor.MissionControlPort
import com.skycommand.relay.wayline.uploader.MissionUploadFailure
import com.skycommand.relay.wayline.phase.MissionExecutionSignal
import com.skycommand.relay.wayline.phase.MissionExecutionSignalListener
import com.skycommand.relay.wayline.phase.MissionExecutionSignalRegistration
import com.skycommand.relay.wayline.phase.MissionExecutionSignalSource
import com.skycommand.relay.wayline.phase.MissionExecutionObservation
import com.skycommand.relay.wayline.phase.MissionExecutionObservationListener
import com.skycommand.relay.wayline.phase.MissionExecutionRawState
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
internal interface DjiUploadCompletion { fun progress(value: Double); fun succeed(); fun fail(failure: MissionUploadFailure? = null) }
internal interface DjiControlCompletion { fun succeed(); fun fail(failure: MissionControlFailure? = null) }
internal enum class DjiMissionExecutionState {
    IDLE,
    READY,
    UPLOADING,
    PREPARING,
    RECOVERING,
    ENTER_WAYLINE,
    EXECUTING,
    PAUSED,
    INTERRUPTED,
    COMPLETED,
    FINISHED,
    RETURN_TO_START_POINT,
    DISCONNECTED,
    NOT_SUPPORTED,
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

/** A bounded local diagnostic for failures that occur before DJI calls an action callback. */
internal enum class WaylineAdapterDiagnosticKind {
    UPLOAD_INPUT_REJECTED,
    UPLOAD_FILE_WRITE_FAILED,
    UPLOAD_DJI_INVOCATION_FAILED,
    UPLOAD_DJI_REJECTED,
}

internal enum class WaylineUploadInputRejection {
    UNSAFE_FILE_NAME,
    KMZ_GUARD_REJECTED,
}

internal data class WaylineAdapterDiagnostic(
    val kind: WaylineAdapterDiagnosticKind,
    val inputRejection: WaylineUploadInputRejection? = null,
    val kmzRejection: SingleWaylineKmzRejection? = null,
    val exceptionType: String? = null,
    val exceptionDescription: String? = null,
    val djiErrorCode: String? = null,
    val djiErrorDescription: String? = null,
)

internal fun interface WaylineAdapterDiagnosticSink {
    fun record(diagnostic: WaylineAdapterDiagnostic)
}

class AndroidDjiWaylineAdapter internal constructor(
    private val files: MissionFileStore,
    private val dji: DjiWaypointMissionApi,
    private val diagnostics: WaylineAdapterDiagnosticSink = WaylineAdapterDiagnosticSink(::recordToLogcat),
) : MissionUploadPort, MissionControlPort, MissionExecutionSignalSource {
    private val lock = Any()
    private val submissionLock = Any()
    private var uploadGeneration = 0L
    private var controlGeneration = 0L
    private var closed = false
    private var uploadedName: String? = null
    private val uploadFiles = mutableMapOf<Long, StoredMissionFile>()
    private val signalListeners = mutableSetOf<SignalListenerSlot>()
    private val observationListeners = mutableSetOf<ObservationListenerSlot>()
    private var djiExecutionRegistration: DjiExecutionStateRegistration? = null
    private var startSignalsEnabled = false

    override fun upload(metadata: MissionMetadata, bytes: ByteArray, progress: (Int) -> Unit, completion: UploadCompletion) {
        if (!metadata.fileName.isSafeKmzName()) {
            record(
                WaylineAdapterDiagnosticKind.UPLOAD_INPUT_REJECTED,
                inputRejection = WaylineUploadInputRejection.UNSAFE_FILE_NAME,
            )
            return safeFail(completion)
        }
        val file = runCatching { files.write(metadata.fileName, bytes) }.getOrElse { error ->
            record(WaylineAdapterDiagnosticKind.UPLOAD_FILE_WRITE_FAILED, error = error)
            return safeFail(completion)
        }
        val kmzInspection = SingleWaylineKmzGuard.inspect(File(file.path))
        if (!kmzInspection.accepted) {
            file.delete()
            record(
                WaylineAdapterDiagnosticKind.UPLOAD_INPUT_REJECTED,
                inputRejection = WaylineUploadInputRejection.KMZ_GUARD_REJECTED,
                kmzRejection = kmzInspection.rejection,
            )
            return safeFail(completion)
        }
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
            override fun fail(failure: MissionUploadFailure?) = finishUpload(operationGeneration, file, once, false, failure)
        }
        synchronized(submissionLock) {
            if (isCurrentUpload(operationGeneration)) {
                try {
                    dji.upload(file.path, callback)
                } catch (error: Throwable) {
                    record(WaylineAdapterDiagnosticKind.UPLOAD_DJI_INVOCATION_FAILED, error = error)
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

    override fun onObservation(listener: MissionExecutionObservationListener): MissionExecutionSignalRegistration {
        val slot = ObservationListenerSlot(listener)
        synchronized(lock) {
            if (!closed) observationListeners += slot
        }
        return MissionExecutionSignalRegistration {
            synchronized(lock) { observationListeners.remove(slot) }
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
                observationListeners.clear()
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
        val (signals, observations) = synchronized(lock) {
            if (closed || !startSignalsEnabled) {
                emptyList<SignalListenerSlot>() to emptyList<ObservationListenerSlot>()
            } else {
                signalListeners.toList() to observationListeners.toList()
            }
        }
        val signal = state.toMissionExecutionSignal()
        val observation = MissionExecutionObservation(signal, state.toMissionExecutionRawState())
        signals.forEach { runCatching { it.listener.onSignal(signal) } }
        observations.forEach { runCatching { it.listener.onObservation(observation) } }
    }

    private fun finishUpload(generation: Long, file: StoredMissionFile, completion: OnceUpload, success: Boolean, failure: MissionUploadFailure? = null) {
        val (shouldDelete, accepted) = synchronized(lock) {
            val ownedFile = uploadFiles.remove(generation) === file
            if (!ownedFile || !completion.claim() || closed || uploadGeneration != generation) ownedFile to false else {
                if (success) uploadedName = file.fileName
                uploadGeneration++
                true to true
            }
        }
        if (shouldDelete) file.delete()
        if (accepted) {
            failure?.let { record(WaylineAdapterDiagnosticKind.UPLOAD_DJI_REJECTED, djiFailure = it) }
            completion.deliver(success, failure)
        }
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
                    override fun fail(failure: MissionControlFailure?) = finishControl(operationGeneration, once, false, failure)
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

    private fun finishControl(generation: Long, completion: OnceControl, success: Boolean, failure: MissionControlFailure? = null) {
        val accepted = synchronized(lock) {
            if (closed || controlGeneration != generation || !completion.claim()) false
            else { controlGeneration++; true }
        }
        if (accepted) completion.deliver(success, failure)
    }

    private fun isCurrentUpload(value: Long) = synchronized(lock) { !closed && uploadGeneration == value }
    private fun isCurrentControl(value: Long) = synchronized(lock) { !closed && controlGeneration == value }
    private fun safeFail(completion: UploadCompletion) { runCatching { completion.fail() } }
    private fun safeFail(completion: ControlCompletion) { runCatching { completion.fail() } }

    private fun record(
        kind: WaylineAdapterDiagnosticKind,
        inputRejection: WaylineUploadInputRejection? = null,
        kmzRejection: SingleWaylineKmzRejection? = null,
        error: Throwable? = null,
        djiFailure: MissionUploadFailure? = null,
    ) {
        runCatching {
            diagnostics.record(
                WaylineAdapterDiagnostic(
                    kind = kind,
                    inputRejection = inputRejection,
                    kmzRejection = kmzRejection,
                    exceptionType = error?.javaClass?.simpleName?.safeDiagnosticText(128),
                    exceptionDescription = error?.message.safeDiagnosticText(512),
                    djiErrorCode = djiFailure?.errorCode,
                    djiErrorDescription = djiFailure?.errorDescription,
                ),
            )
        }
    }

    private class OnceUpload(private val delegate: UploadCompletion) { private val lock=Any();private var done=false
        fun claim():Boolean=synchronized(lock){if(done)false else{done=true;true}}
        fun deliver(success:Boolean, failure: MissionUploadFailure? = null)=runCatching{if(success)delegate.succeed()else delegate.fail(failure)}}
    private class OnceControl(private val delegate: ControlCompletion) { private val lock=Any();private var done=false
        fun claim():Boolean=synchronized(lock){if(done)false else{done=true;true}}
        fun deliver(success:Boolean, failure: MissionControlFailure? = null)=runCatching{if(success)delegate.succeed()else delegate.fail(failure)}
        fun fail(){if(claim())deliver(false)}}

    private data class PreparedControl(
        val generation: Long,
        val name: String?,
        val callback: DjiControlCompletion,
    )

    private data class SignalListenerSlot(val listener: MissionExecutionSignalListener)
    private data class ObservationListenerSlot(val listener: MissionExecutionObservationListener)

    companion object {
        fun create(context: Context): AndroidDjiWaylineAdapter = AndroidDjiWaylineAdapter(AndroidMissionFileStore(context.applicationContext), MsdkV5WaypointMissionApi())
    }
}

private const val WAYLINE_DIAGNOSTIC_TAG = "SkyCommandRelay"

private fun recordToLogcat(diagnostic: WaylineAdapterDiagnostic) {
    val detail = listOfNotNull(
        diagnostic.inputRejection?.let { "input=$it" },
        diagnostic.kmzRejection?.let { "kmz=$it" },
        diagnostic.exceptionType?.let { "exception=$it" },
        diagnostic.exceptionDescription?.let { "detail=$it" },
        diagnostic.djiErrorCode?.let { "djiErrorCode=$it" },
        diagnostic.djiErrorDescription?.let { "djiErrorDescription=$it" },
    ).joinToString(" ")
    Log.w(WAYLINE_DIAGNOSTIC_TAG, "wayline-mission/${diagnostic.kind}${if (detail.isBlank()) "" else " $detail"}")
}

private fun String?.safeDiagnosticText(limit: Int): String? {
    val sanitized = this
        ?.codePoints()
        ?.filter { !Character.isISOControl(it) }
        ?.limit(limit.toLong())
        ?.collect(
            { StringBuilder() },
            { builder, codePoint -> builder.appendCodePoint(codePoint) },
            { left, right -> left.append(right) },
        )
        ?.toString()
        ?.trim()
        ?.takeUnless { it.isEmpty() || it.contains('/') || it.contains('\\') }
    return sanitized
}

private fun DjiMissionExecutionState.toMissionExecutionSignal(): MissionExecutionSignal = when (this) {
    DjiMissionExecutionState.UPLOADING,
    DjiMissionExecutionState.PREPARING,
    DjiMissionExecutionState.RECOVERING,
    -> MissionExecutionSignal.PREPARING
    DjiMissionExecutionState.ENTER_WAYLINE -> MissionExecutionSignal.ENTER_WAYLINE
    DjiMissionExecutionState.EXECUTING,
    DjiMissionExecutionState.RETURN_TO_START_POINT,
    -> MissionExecutionSignal.EXECUTING
    DjiMissionExecutionState.PAUSED -> MissionExecutionSignal.PAUSED
    DjiMissionExecutionState.COMPLETED,
    DjiMissionExecutionState.FINISHED,
    -> MissionExecutionSignal.COMPLETED
    DjiMissionExecutionState.INTERRUPTED -> MissionExecutionSignal.INTERRUPTED
    DjiMissionExecutionState.IDLE,
    DjiMissionExecutionState.READY,
    -> MissionExecutionSignal.IDLE
    DjiMissionExecutionState.DISCONNECTED -> MissionExecutionSignal.DISCONNECTED
    DjiMissionExecutionState.NOT_SUPPORTED,
    DjiMissionExecutionState.UNKNOWN,
    -> MissionExecutionSignal.UNKNOWN
}

private fun DjiMissionExecutionState.toMissionExecutionRawState(): MissionExecutionRawState = when (this) {
    DjiMissionExecutionState.IDLE -> MissionExecutionRawState.IDLE
    DjiMissionExecutionState.READY -> MissionExecutionRawState.READY
    DjiMissionExecutionState.UPLOADING -> MissionExecutionRawState.UPLOADING
    DjiMissionExecutionState.PREPARING -> MissionExecutionRawState.PREPARING
    DjiMissionExecutionState.RECOVERING -> MissionExecutionRawState.RECOVERING
    DjiMissionExecutionState.ENTER_WAYLINE -> MissionExecutionRawState.ENTER_WAYLINE
    DjiMissionExecutionState.EXECUTING -> MissionExecutionRawState.EXECUTING
    DjiMissionExecutionState.PAUSED -> MissionExecutionRawState.PAUSED
    DjiMissionExecutionState.INTERRUPTED -> MissionExecutionRawState.INTERRUPTED
    DjiMissionExecutionState.COMPLETED,
    DjiMissionExecutionState.FINISHED,
    -> MissionExecutionRawState.FINISHED
    DjiMissionExecutionState.RETURN_TO_START_POINT -> MissionExecutionRawState.RETURN_TO_START_POINT
    DjiMissionExecutionState.DISCONNECTED -> MissionExecutionRawState.DISCONNECTED
    DjiMissionExecutionState.NOT_SUPPORTED -> MissionExecutionRawState.NOT_SUPPORTED
    DjiMissionExecutionState.UNKNOWN -> MissionExecutionRawState.UNKNOWN
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
