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
import com.skycommand.relay.wayline.phase.WaylineLiveProgress
import com.skycommand.relay.wayline.phase.WaylineLiveProgressListener
import com.skycommand.relay.wayline.staging.MissionMetadata
import com.skycommand.relay.wayline.uploader.MissionUploadPreparation
import com.skycommand.relay.wayline.uploader.MissionUploadPort
import com.skycommand.relay.wayline.uploader.PreparedMissionUpload
import com.skycommand.relay.wayline.uploader.UploadCompletion
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.math.roundToInt

internal class StoredMissionFile(val path: String, val fileName: String, private val deleteAction: () -> Unit) {
    fun delete() = runCatching(deleteAction)
}

internal fun interface MissionFileStore { fun write(fileName: String, content: InputStream): StoredMissionFile }
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
internal data class DjiWaylineExecutingInfo(
    val missionFileName: String?,
    val waylineId: Int?,
    val currentWaypointIndex: Int?,
)
internal data class DjiWaypointActionEvent(
    val actionGroup: Int?,
    val actionId: Int,
    val phase: com.skycommand.relay.wayline.phase.WaylineLiveActionPhase,
    val errorCode: String? = null,
    val errorDescription: String? = null,
)
internal interface DjiWaypointMissionApi {
    fun upload(path: String, completion: DjiUploadCompletion)
    fun start(name: String, completion: DjiControlCompletion)
    fun pause(completion: DjiControlCompletion)
    fun resume(completion: DjiControlCompletion)
    fun stop(name: String, completion: DjiControlCompletion)
    fun onExecutionState(listener: (DjiMissionExecutionState) -> Unit): DjiExecutionStateRegistration
    fun onExecutingInfo(
        onInfo: (DjiWaylineExecutingInfo) -> Unit,
        onInterrupt: (String?, String?) -> Unit,
    ): DjiExecutionStateRegistration = DjiExecutionStateRegistration { }
    fun onWaypointAction(listener: (DjiWaypointActionEvent) -> Unit): DjiExecutionStateRegistration =
        DjiExecutionStateRegistration { }
    fun close()
}

/** A bounded local diagnostic for failures that occur before DJI calls an action callback. */
internal enum class WaylineObservationDelivery {
    DELIVERED,
    PENDING,
    DROPPED,
}

internal enum class WaylineAdapterDiagnosticKind {
    UPLOAD_INPUT_REJECTED,
    UPLOAD_FILE_WRITE_FAILED,
    UPLOAD_DJI_INVOCATION_FAILED,
    UPLOAD_DJI_REJECTED,
    CONTROL_DJI_INVOCATION_FAILED,
    EXECUTION_STATE_OBSERVED,
    WAYLINE_PROGRESS_OBSERVED,
    WAYLINE_INTERRUPT_OBSERVED,
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
    val observedRawState: String? = null,
    val observationDelivery: WaylineObservationDelivery? = null,
    val currentWaypointIndex: Int? = null,
    val waylineId: Int? = null,
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
    private val liveProgressListeners = mutableSetOf<LiveProgressListenerSlot>()
    private var djiExecutionRegistration: DjiExecutionStateRegistration? = null
    private var djiLiveProgressRegistrations: List<DjiExecutionStateRegistration> = emptyList()
    private var startSignalsEnabled = false
    private var startInvocationArmed = false
    private val pendingStartStates = mutableListOf<DjiMissionExecutionState>()
    private val pendingLiveProgress = mutableListOf<WaylineLiveProgress>()

    override fun prepare(metadata: MissionMetadata, content: InputStream): MissionUploadPreparation {
        if (!metadata.fileName.isSafeKmzName()) {
            record(
                WaylineAdapterDiagnosticKind.UPLOAD_INPUT_REJECTED,
                inputRejection = WaylineUploadInputRejection.UNSAFE_FILE_NAME,
            )
            return MissionUploadPreparation.Rejected
        }
        val file = runCatching { files.write(metadata.fileName, content) }.getOrElse { error ->
            record(WaylineAdapterDiagnosticKind.UPLOAD_FILE_WRITE_FAILED, error = error)
            return MissionUploadPreparation.Rejected
        }
        val kmzInspection = SingleWaylineKmzGuard.inspect(File(file.path))
        if (!kmzInspection.accepted) {
            file.delete()
            record(
                WaylineAdapterDiagnosticKind.UPLOAD_INPUT_REJECTED,
                inputRejection = WaylineUploadInputRejection.KMZ_GUARD_REJECTED,
                kmzRejection = kmzInspection.rejection,
            )
            return MissionUploadPreparation.Rejected
        }
        return MissionUploadPreparation.Prepared(PreparedUpload(file))
    }

    private fun startPreparedUpload(file: StoredMissionFile, progress: (Int) -> Unit, completion: UploadCompletion) {
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
                    throw error
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
        synchronized(lock) {
            if (!closed) startInvocationArmed = true
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

    override fun onLiveProgress(listener: WaylineLiveProgressListener): MissionExecutionSignalRegistration {
        val slot = LiveProgressListenerSlot(listener)
        synchronized(lock) {
            if (!closed) liveProgressListeners += slot
        }
        return MissionExecutionSignalRegistration {
            synchronized(lock) { liveProgressListeners.remove(slot) }
        }
    }

    override fun beginStartAttempt() {
        synchronized(lock) {
            if (closed) return
            startSignalsEnabled = false
            startInvocationArmed = false
            pendingStartStates.clear()
            pendingLiveProgress.clear()
        }
    }

    override fun confirmStartAttempt() {
        val replay: List<DjiMissionExecutionState>
        val liveReplay: List<WaylineLiveProgress>
        val signals: List<SignalListenerSlot>
        val observations: List<ObservationListenerSlot>
        val liveListeners: List<LiveProgressListenerSlot>
        synchronized(lock) {
            if (closed) return
            startSignalsEnabled = true
            startInvocationArmed = false
            replay = pendingStartStates.toList()
            pendingStartStates.clear()
            liveReplay = pendingLiveProgress.toList()
            pendingLiveProgress.clear()
            signals = signalListeners.toList()
            observations = observationListeners.toList()
            liveListeners = liveProgressListeners.toList()
        }
        replay.forEach { state -> deliverExecutionState(state, signals, observations) }
        liveReplay.forEach { progress -> deliverLiveProgress(progress, liveListeners) }
    }

    override fun invalidateStartAttempt() {
        synchronized(lock) {
            startSignalsEnabled = false
            startInvocationArmed = false
            pendingStartStates.clear()
            pendingLiveProgress.clear()
        }
    }

    fun close() {
        synchronized(submissionLock) {
            val (files, registrations) = synchronized(lock) {
                if (closed) return
                closed = true
                startSignalsEnabled = false
                startInvocationArmed = false
                pendingStartStates.clear()
                pendingLiveProgress.clear()
                uploadGeneration++
                controlGeneration++
                signalListeners.clear()
                observationListeners.clear()
                liveProgressListeners.clear()
                val allRegistrations = buildList {
                    djiExecutionRegistration?.let(::add)
                    addAll(djiLiveProgressRegistrations)
                }
                djiExecutionRegistration = null
                djiLiveProgressRegistrations = emptyList()
                uploadFiles.values.toList().also { uploadFiles.clear() } to allRegistrations
            }
            files.forEach(StoredMissionFile::delete)
            registrations.forEach { runCatching { it.unregister() } }
            runCatching { dji.close() }
        }
    }

    private fun ensureExecutionStateSubscription(): Boolean {
        synchronized(lock) {
            if (closed) return false
            if (djiExecutionRegistration != null) return true
        }
        val registration = runCatching { dji.onExecutionState(::dispatchExecutionState) }.getOrNull() ?: return false
        val executingInfo = runCatching { dji.onExecutingInfo(::dispatchExecutingInfo, ::dispatchExecutingInterrupt) }.getOrNull()
        val waypointAction = runCatching { dji.onWaypointAction(::dispatchWaypointAction) }.getOrNull()
        val retained = synchronized(lock) {
            if (closed || djiExecutionRegistration != null) false else {
                djiExecutionRegistration = registration
                djiLiveProgressRegistrations = listOfNotNull(executingInfo, waypointAction)
                true
            }
        }
        if (!retained) {
            runCatching { registration.unregister() }
            executingInfo?.let { runCatching { it.unregister() } }
            waypointAction?.let { runCatching { it.unregister() } }
        }
        return retained
    }

    private fun dispatchExecutingInfo(info: DjiWaylineExecutingInfo) {
        dispatchLiveProgress(
            WaylineLiveProgress(
                executingMissionFileName = info.missionFileName.safeExecutingName(),
                waylineId = info.waylineId?.takeIf { it >= 0 },
                currentWaypointIndex = info.currentWaypointIndex?.takeIf { it >= 0 },
            ),
        )
    }

    private fun dispatchExecutingInterrupt(errorCode: String?, errorDescription: String?) {
        if (errorCode == null && errorDescription == null) return
        dispatchLiveProgress(
            WaylineLiveProgress(
                interruptErrorCode = errorCode,
                interruptErrorDescription = errorDescription,
            ),
        )
    }

    private fun dispatchWaypointAction(event: DjiWaypointActionEvent) {
        dispatchLiveProgress(
            WaylineLiveProgress(
                waypointActionGroup = event.actionGroup?.takeIf { it >= 0 },
                waypointActionId = event.actionId.takeIf { it >= 0 },
                waypointActionPhase = event.phase,
                waypointActionErrorCode = event.errorCode,
                waypointActionErrorDescription = event.errorDescription,
            ),
        )
    }

    private fun dispatchLiveProgress(progress: WaylineLiveProgress) {
        val (listeners, delivery, closedNow) = synchronized(lock) {
            when {
                closed -> Triple(emptyList<LiveProgressListenerSlot>(), WaylineObservationDelivery.DROPPED, true)
                startSignalsEnabled -> Triple(liveProgressListeners.toList(), WaylineObservationDelivery.DELIVERED, false)
                startInvocationArmed -> {
                    if (pendingLiveProgress.size < MAX_PENDING_START_STATES) pendingLiveProgress += progress
                    Triple(emptyList(), WaylineObservationDelivery.PENDING, false)
                }
                else -> Triple(emptyList(), WaylineObservationDelivery.DROPPED, false)
            }
        }
        if (!closedNow) {
            val interrupt = progress.interruptErrorCode != null || progress.interruptErrorDescription != null
            record(
                kind = if (interrupt) WaylineAdapterDiagnosticKind.WAYLINE_INTERRUPT_OBSERVED else WaylineAdapterDiagnosticKind.WAYLINE_PROGRESS_OBSERVED,
                djiErrorCode = progress.interruptErrorCode,
                djiErrorDescription = progress.interruptErrorDescription,
                observationDelivery = delivery,
                currentWaypointIndex = progress.currentWaypointIndex,
                waylineId = progress.waylineId,
            )
        }
        deliverLiveProgress(progress, listeners)
    }

    private fun deliverLiveProgress(progress: WaylineLiveProgress, listeners: List<LiveProgressListenerSlot>) {
        if (listeners.isEmpty()) return
        listeners.forEach { runCatching { it.listener.onLiveProgress(progress) } }
    }

    private fun dispatchExecutionState(state: DjiMissionExecutionState) {
        val (signals, observations, delivery, closedNow) = synchronized(lock) {
            when {
                closed -> ExecutionDispatch(emptyList(), emptyList(), WaylineObservationDelivery.DROPPED, true)
                startSignalsEnabled -> ExecutionDispatch(signalListeners.toList(), observationListeners.toList(), WaylineObservationDelivery.DELIVERED, false)
                startInvocationArmed -> {
                    if (pendingStartStates.size < MAX_PENDING_START_STATES) pendingStartStates += state
                    ExecutionDispatch(emptyList(), emptyList(), WaylineObservationDelivery.PENDING, false)
                }
                else -> ExecutionDispatch(emptyList(), emptyList(), WaylineObservationDelivery.DROPPED, false)
            }
        }
        if (!closedNow) {
            record(
                kind = WaylineAdapterDiagnosticKind.EXECUTION_STATE_OBSERVED,
                observedRawState = state.name,
                observationDelivery = delivery,
            )
        }
        deliverExecutionState(state, signals, observations)
    }

    private fun deliverExecutionState(
        state: DjiMissionExecutionState,
        signals: List<SignalListenerSlot>,
        observations: List<ObservationListenerSlot>,
    ) {
        if (signals.isEmpty() && observations.isEmpty()) return
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
                } catch (error: Throwable) {
                    record(WaylineAdapterDiagnosticKind.CONTROL_DJI_INVOCATION_FAILED, error = error)
                    throw error
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
        djiErrorCode: String? = null,
        djiErrorDescription: String? = null,
        observedRawState: String? = null,
        observationDelivery: WaylineObservationDelivery? = null,
        currentWaypointIndex: Int? = null,
        waylineId: Int? = null,
    ) {
        runCatching {
            diagnostics.record(
                WaylineAdapterDiagnostic(
                    kind = kind,
                    inputRejection = inputRejection,
                    kmzRejection = kmzRejection,
                    exceptionType = error?.javaClass?.simpleName?.safeDiagnosticText(128),
                    exceptionDescription = error?.message.safeDiagnosticText(512),
                    djiErrorCode = (djiErrorCode ?: djiFailure?.errorCode).safeDiagnosticText(128),
                    djiErrorDescription = (djiErrorDescription ?: djiFailure?.errorDescription).safeDiagnosticText(512),
                    observedRawState = observedRawState.safeDiagnosticText(128),
                    observationDelivery = observationDelivery,
                    currentWaypointIndex = currentWaypointIndex,
                    waylineId = waylineId,
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

    private inner class PreparedUpload(
        private val file: StoredMissionFile,
    ) : PreparedMissionUpload {
        private val preparationLock = Any()
        private var consumed = false

        override fun start(progress: (Int) -> Unit, completion: UploadCompletion) {
            val canStart = synchronized(preparationLock) {
                if (consumed) false else {
                    consumed = true
                    true
                }
            }
            if (!canStart) {
                safeFail(completion)
                return
            }
            startPreparedUpload(file, progress, completion)
        }

        override fun discard() {
            val shouldDelete = synchronized(preparationLock) {
                if (consumed) false else {
                    consumed = true
                    true
                }
            }
            if (shouldDelete) file.delete()
        }
    }

    private data class SignalListenerSlot(val listener: MissionExecutionSignalListener)
    private data class ObservationListenerSlot(val listener: MissionExecutionObservationListener)
    private data class LiveProgressListenerSlot(val listener: WaylineLiveProgressListener)
    private data class ExecutionDispatch(
        val signals: List<SignalListenerSlot>,
        val observations: List<ObservationListenerSlot>,
        val delivery: WaylineObservationDelivery,
        val closedNow: Boolean,
    )

    companion object {
        fun create(
            context: Context,
            diagnosticSink: ((kind: String, detail: String) -> Unit)? = null,
        ): AndroidDjiWaylineAdapter = AndroidDjiWaylineAdapter(
            AndroidMissionFileStore(context.applicationContext),
            MsdkV5WaypointMissionApi(),
            WaylineAdapterDiagnosticSink { diagnostic ->
                recordToLogcat(diagnostic)
                diagnosticSink?.invoke(diagnostic.kind.name, diagnosticDetail(diagnostic))
            },
        )
    }
}

private const val WAYLINE_DIAGNOSTIC_TAG = "SkyCommandRelay"
private const val MAX_PENDING_START_STATES = 32

private fun diagnosticDetail(diagnostic: WaylineAdapterDiagnostic): String = listOfNotNull(
    diagnostic.observationDelivery?.let { "delivery=$it" },
    diagnostic.observedRawState?.let { "rawState=$it" },
    diagnostic.currentWaypointIndex?.let { "currentWaypointIndex=$it" },
    diagnostic.waylineId?.let { "waylineId=$it" },
    diagnostic.inputRejection?.let { "input=$it" },
    diagnostic.kmzRejection?.let { "kmz=$it" },
    diagnostic.exceptionType?.let { "exception=$it" },
    diagnostic.exceptionDescription?.let { "detail=$it" },
    diagnostic.djiErrorCode?.let { "djiErrorCode=$it" },
    diagnostic.djiErrorDescription?.let { "djiErrorDescription=$it" },
).joinToString(" ")

private fun recordToLogcat(diagnostic: WaylineAdapterDiagnostic) {
    val detail = diagnosticDetail(diagnostic)
    val observation = diagnostic.kind == WaylineAdapterDiagnosticKind.EXECUTION_STATE_OBSERVED ||
        diagnostic.kind == WaylineAdapterDiagnosticKind.WAYLINE_PROGRESS_OBSERVED
    val message = "wayline-mission/${diagnostic.kind}${if (detail.isBlank()) "" else " $detail"}"
    if (observation) Log.i(WAYLINE_DIAGNOSTIC_TAG, message) else Log.w(WAYLINE_DIAGNOSTIC_TAG, message)
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

private fun String?.safeExecutingName(): String? {
    val sanitized = this
        ?.codePoints()
        ?.filter { !Character.isISOControl(it) }
        ?.limit(MAX_RELAY_FILE_NAME_CODE_POINTS.toLong())
        ?.collect(
            { StringBuilder() },
            { builder, codePoint -> builder.appendCodePoint(codePoint) },
            { left, right -> left.append(right) },
        )
        ?.toString()
        ?.trim()
        ?.takeUnless { it.isEmpty() || it.contains('/') || it.contains('\\') || it == "." || it == ".." }
    return sanitized
}

private class AndroidMissionFileStore(context: Context) : MissionFileStore {
    private val directory = File(context.cacheDir, "dji-waylines")
    override fun write(fileName: String, content: InputStream): StoredMissionFile = writeMissionFile(directory, fileName, content)
}

internal fun writeMissionFile(
    directory: File,
    fileName: String,
    content: InputStream,
    writer: (File, InputStream) -> Unit = { file, source -> file.outputStream().use { source.copyTo(it, MISSION_FILE_COPY_BUFFER_BYTES) } },
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

private const val MISSION_FILE_COPY_BUFFER_BYTES = 64 * 1024
