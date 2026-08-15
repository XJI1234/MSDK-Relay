package com.skycommand.relay.wayline.state

import com.skycommand.relay.wayline.staging.MissionMetadata
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class MissionStateSource {
    STAGING,
    UPLOAD,
    EXECUTION,
}

sealed interface UploadState {
    data object NOT_UPLOADED : UploadState
    data class Uploading(val progress: Int) : UploadState
    data object UPLOADED : UploadState
    data object FAILED : UploadState
}

enum class ExecutionState {
    NOT_STARTED,
    STARTING,
    EXECUTING,
    PAUSED,
    STOPPING,
    FINISHED,
    FAILED,
}

data class MissionSnapshot(
    val revision: Long,
    val missionRevision: Long?,
    val deviceGeneration: Long,
    val file: MissionMetadata?,
    val upload: UploadState,
    val execution: ExecutionState,
)

sealed interface MissionStateEvent {
    val sourceRevision: Long

    data class FileStaged(
        override val sourceRevision: Long,
        val metadata: MissionMetadata,
    ) : MissionStateEvent

    data class FileCleared(
        override val sourceRevision: Long,
    ) : MissionStateEvent

    data class UploadChanged(
        override val sourceRevision: Long,
        val missionRevision: Long,
        val deviceGeneration: Long,
        val state: UploadState,
    ) : MissionStateEvent

    data class ExecutionChanged(
        override val sourceRevision: Long,
        val missionRevision: Long,
        val deviceGeneration: Long,
        val state: ExecutionState,
    ) : MissionStateEvent
}

data class MissionStateEventRecord(
    val previous: MissionSnapshot,
    val current: MissionSnapshot,
)

fun interface MissionStateListener {
    fun onChanged(event: MissionStateEventRecord)
}

fun interface Registration {
    fun unregister()
}

fun interface MissionStateDiagnosticSink {
    fun record(diagnostic: MissionStateDiagnostic)
}

data class MissionStateDiagnostic(
    val kind: MissionStateDiagnosticKind,
)

enum class MissionStateDiagnosticKind {
    LISTENER_FAILURE,
}

sealed interface ApplyResult {
    data class Applied(val snapshot: MissionSnapshot) : ApplyResult
    data class IgnoredStale(val sourceRevision: Long) : ApplyResult
}

class MissionStateStore private constructor(
    private val diagnosticSink: MissionStateDiagnosticSink,
) {
    private val lock = ReentrantLock()
    private var current = initialSnapshot()
    private var nextMissionRevision = 0L
    private val sourceRevisions = mutableMapOf<MissionStateSource, Long>()
    private val listeners = mutableListOf<ListenerSlot>()
    private val pendingEvents = ArrayDeque<PendingEvent>()
    private var draining = false

    fun snapshot(): MissionSnapshot = lock.withLock { current }

    fun apply(event: MissionStateEvent): ApplyResult {
        validate(event)
        var appliedSnapshot: MissionSnapshot? = null
        val shouldDrain = lock.withLock {
            val source = event.source()
            val previousSourceRevision = sourceRevisions[source] ?: 0L
            if (event.sourceRevision <= previousSourceRevision) {
                return ApplyResult.IgnoredStale(event.sourceRevision)
            }
            sourceRevisions[source] = event.sourceRevision

            val next = when (event) {
                is MissionStateEvent.FileStaged -> {
                    nextMissionRevision += 1
                    current.copy(
                        revision = current.revision + 1,
                        missionRevision = nextMissionRevision,
                        file = event.metadata,
                        upload = UploadState.NOT_UPLOADED,
                        execution = ExecutionState.NOT_STARTED,
                    )
                }
                is MissionStateEvent.FileCleared -> {
                    if (current.file == null) {
                        return ApplyResult.IgnoredStale(event.sourceRevision)
                    }
                    current.copy(
                        revision = current.revision + 1,
                        missionRevision = null,
                        file = null,
                        upload = UploadState.NOT_UPLOADED,
                        execution = ExecutionState.NOT_STARTED,
                    )
                }
                is MissionStateEvent.UploadChanged -> {
                    if (event.missionRevision != current.missionRevision || event.deviceGeneration != current.deviceGeneration) {
                        return ApplyResult.IgnoredStale(event.sourceRevision)
                    }
                    current.copy(revision = current.revision + 1, upload = event.state)
                }
                is MissionStateEvent.ExecutionChanged -> {
                    if (
                        event.missionRevision != current.missionRevision ||
                        event.deviceGeneration != current.deviceGeneration ||
                        !canApplyExecution(event.state)
                    ) {
                        return ApplyResult.IgnoredStale(event.sourceRevision)
                    }
                    current.copy(revision = current.revision + 1, execution = event.state)
                }
            }
            val previous = current
            current = next
            appliedSnapshot = next
            pendingEvents.addLast(PendingEvent(MissionStateEventRecord(previous, next), listeners.toList()))
            if (draining) false else {
                draining = true
                true
            }
        }
        if (shouldDrain) drain()
        return ApplyResult.Applied(requireNotNull(appliedSnapshot))
    }

    fun markDeviceUnavailable(): ApplyResult.Applied {
        var appliedSnapshot: MissionSnapshot? = null
        val shouldDrain = lock.withLock {
            val previous = current
            val hasMission = current.file != null
            val next = current.copy(
                revision = current.revision + 1,
                deviceGeneration = current.deviceGeneration + 1,
                upload = if (hasMission) UploadState.FAILED else UploadState.NOT_UPLOADED,
                execution = if (hasMission) ExecutionState.FAILED else ExecutionState.NOT_STARTED,
            )
            current = next
            appliedSnapshot = next
            pendingEvents.addLast(PendingEvent(MissionStateEventRecord(previous, next), listeners.toList()))
            if (draining) false else {
                draining = true
                true
            }
        }
        if (shouldDrain) drain()
        return ApplyResult.Applied(requireNotNull(appliedSnapshot))
    }

    fun onChanged(listener: MissionStateListener): Registration {
        val slot = ListenerSlot(listener)
        lock.withLock { listeners += slot }
        return Registration {
            if (slot.deactivate()) {
                lock.withLock { listeners.remove(slot) }
            }
        }
    }

    private fun canApplyExecution(state: ExecutionState): Boolean =
        state == ExecutionState.FAILED ||
            (current.file != null && current.upload == UploadState.UPLOADED)

    private fun drain() {
        while (true) {
            val pending = lock.withLock {
                if (pendingEvents.isEmpty()) {
                    draining = false
                    null
                } else {
                    pendingEvents.removeFirst()
                }
            } ?: return
            pending.listeners.forEach { listener ->
                try {
                    listener.onChanged(pending.event)
                } catch (_: Throwable) {
                    runCatching {
                        diagnosticSink.record(MissionStateDiagnostic(MissionStateDiagnosticKind.LISTENER_FAILURE))
                    }
                }
            }
        }
    }

    private class ListenerSlot(
        private val delegate: MissionStateListener,
    ) : MissionStateListener {
        private val lock = ReentrantLock()
        private val idle = lock.newCondition()
        private val callbackDepth = ThreadLocal.withInitial { 0 }
        private var active = true
        private var inFlight = 0

        fun deactivate(): Boolean {
            var restoreInterrupt = false
            lock.lock()
            try {
                val changed = active
                active = false
                if (callbackDepth.get() == 0) {
                    while (inFlight > 0) {
                        try {
                            idle.await()
                        } catch (_: InterruptedException) {
                            restoreInterrupt = true
                        }
                    }
                }
                return changed
            } finally {
                lock.unlock()
                if (restoreInterrupt) Thread.currentThread().interrupt()
            }
        }

        override fun onChanged(event: MissionStateEventRecord) {
            val deliver = lock.withLock {
                if (active) {
                    inFlight += 1
                    true
                } else {
                    false
                }
            }
            if (!deliver) return

            val previousDepth = callbackDepth.get()
            callbackDepth.set(previousDepth + 1)
            try {
                delegate.onChanged(event)
            } finally {
                if (previousDepth == 0) callbackDepth.remove() else callbackDepth.set(previousDepth)
                lock.withLock {
                    inFlight -= 1
                    if (inFlight == 0) idle.signalAll()
                }
            }
        }
    }

    private data class PendingEvent(
        val event: MissionStateEventRecord,
        val listeners: List<ListenerSlot>,
    )

    companion object {
        private const val MAX_RELAY_FILE_NAME_CODE_POINTS = 128

        fun create(diagnosticSink: MissionStateDiagnosticSink = MissionStateDiagnosticSink { }): MissionStateStore =
            MissionStateStore(diagnosticSink)

        private fun initialSnapshot() = MissionSnapshot(
            revision = 0,
            missionRevision = null,
            deviceGeneration = 0,
            file = null,
            upload = UploadState.NOT_UPLOADED,
            execution = ExecutionState.NOT_STARTED,
        )

        private fun validate(event: MissionStateEvent) {
            require(event.sourceRevision > 0) { "Source revision must be positive" }
            when (event) {
                is MissionStateEvent.FileStaged -> validateMetadata(event.metadata)
                is MissionStateEvent.FileCleared -> Unit
                is MissionStateEvent.UploadChanged -> {
                    require(event.missionRevision > 0) { "Mission revision must be positive" }
                    require(event.deviceGeneration >= 0) { "Device generation must not be negative" }
                    val progress = (event.state as? UploadState.Uploading)?.progress
                    require(progress == null || progress in 0..100) { "Upload progress must be between 0 and 100" }
                }
                is MissionStateEvent.ExecutionChanged -> {
                    require(event.missionRevision > 0) { "Mission revision must be positive" }
                    require(event.deviceGeneration >= 0) { "Device generation must not be negative" }
                }
            }
        }

        private fun validateMetadata(metadata: MissionMetadata) {
            require(metadata.fileName.isNotBlank()) { "Mission filename must not be blank" }
            require(metadata.fileName.endsWith(".kmz", ignoreCase = true)) { "Mission filename must use .kmz" }
            require(metadata.fileName.codePointCount(0, metadata.fileName.length) <= MAX_RELAY_FILE_NAME_CODE_POINTS) { "Mission filename is too long" }
            require(metadata.fileName.none { it == '/' || it == '\\' || it.isISOControl() }) {
                "Mission filename must be a basename"
            }
            require(metadata.expectedSize > 0) { "Mission size must be positive" }
            require(metadata.sha256.length == 64 && metadata.sha256.all { it in "0123456789abcdefABCDEF" }) {
                "Mission SHA-256 is invalid"
            }
        }

        private fun MissionStateEvent.source(): MissionStateSource = when (this) {
            is MissionStateEvent.FileStaged, is MissionStateEvent.FileCleared -> MissionStateSource.STAGING
            is MissionStateEvent.UploadChanged -> MissionStateSource.UPLOAD
            is MissionStateEvent.ExecutionChanged -> MissionStateSource.EXECUTION
        }
    }
}
