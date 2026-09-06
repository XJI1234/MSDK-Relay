package com.skycommand.relay.diagnostics.android

import android.content.Context
import android.util.Log
import com.skycommand.relay.diagnostics.DiagnosticEvent
import com.skycommand.relay.diagnostics.DiagnosticLevel
import com.skycommand.relay.diagnostics.DiagnosticPersistence
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors

internal fun interface DiagnosticBackgroundExecutor {
    fun execute(task: () -> Unit)
}

internal fun interface DiagnosticLogWriter {
    fun append(event: DiagnosticEvent)
}

class AndroidDiagnosticStore private constructor(
    private val file: File,
    private val executor: DiagnosticBackgroundExecutor,
    private val log: DiagnosticLogWriter,
) {
    private val lock = Any()
    private var lastLogged: Pair<String, Long>? = null
    private var queued: PendingPersistence? = null
    private var writerRunning = false

    private data class PendingPersistence(
        val events: List<DiagnosticEvent>,
        val onFailure: () -> Unit,
    )

    fun restore(): List<DiagnosticEvent> = synchronized(lock) {
        runCatching { DiagnosticEventFileCodec.decode(file.takeIf(File::isFile)?.readText(Charsets.UTF_8).orEmpty()) }
            .getOrDefault(emptyList())
    }

    fun persistence(): DiagnosticPersistence = DiagnosticPersistence { events, onFailure ->
        val shouldStart = synchronized(lock) {
            queued = PendingPersistence(events.toList(), onFailure)
            if (writerRunning) {
                false
            } else {
                writerRunning = true
                true
            }
        }
        if (shouldStart) {
            runCatching { executor.execute(::drain) }
                .onFailure {
                    val failed = synchronized(lock) {
                        writerRunning = false
                        queued.also { queued = null }
                    }
                    failed?.let { pending -> runCatching { pending.onFailure() } }
                }
        }
    }

    private fun drain() {
        while (true) {
            val pending = synchronized(lock) {
                queued?.also { queued = null } ?: run {
                    writerRunning = false
                    return
                }
            }
            runCatching { write(pending.events) }
                .onFailure { runCatching { pending.onFailure() } }
        }
    }

    private fun write(events: List<DiagnosticEvent>) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(DiagnosticEventFileCodec.encode(events), Charsets.UTF_8)
        if (!temporary.renameTo(file)) {
            temporary.delete()
            throw IllegalStateException("Diagnostic log cannot be replaced")
        }
        events.lastOrNull()?.let { event -> runCatching { writeLogcatIfNew(event) } }
    }

    private fun writeLogcatIfNew(event: DiagnosticEvent) {
        val key = event.runId to event.sequence
        if (key == lastLogged) return
        lastLogged = key
        log.append(event)
    }

    companion object {
        private val backgroundExecutor: Executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "sky-command-diagnostics").apply { isDaemon = true }
        }

        fun create(context: Context): AndroidDiagnosticStore = AndroidDiagnosticStore(
            File(context.applicationContext.filesDir, "diagnostics/pending-events.v1"),
            DiagnosticBackgroundExecutor { task -> backgroundExecutor.execute(task) },
            DiagnosticLogWriter { event ->
                Log.println(
                    event.level.toAndroidPriority(),
                    "SkyCommandRelay",
                    "${event.runId}#${event.sequence} ${event.module}/${event.eventCode} ${event.safeDetail}",
                )
            },
        )

        internal fun createForTesting(
            file: File,
            executor: DiagnosticBackgroundExecutor,
            log: DiagnosticLogWriter,
        ): AndroidDiagnosticStore = AndroidDiagnosticStore(file, executor, log)
    }
}

internal object DiagnosticEventFileCodec {
    fun encode(events: List<DiagnosticEvent>): String = buildString {
        events.forEach { event ->
            append(event.timestampMillis).append('|')
            append(event.level.name).append('|')
            append(encodeText(event.module)).append('|')
            append(encodeText(event.eventCode)).append('|')
            append(encodeText(event.runId)).append('|')
            append(event.sequence).append('|')
            append(event.operationId?.let(::encodeText).orEmpty()).append('|')
            append(encodeText(event.safeDetail)).append('\n')
        }
    }

    fun decode(content: String): List<DiagnosticEvent> = content.lineSequence().mapNotNull(::decodeLine).toList()

    private fun decodeLine(line: String): DiagnosticEvent? = runCatching {
        val parts = line.split('|')
        if (parts.size != 8) return@runCatching null
        DiagnosticEvent(
            timestampMillis = parts[0].toLong(),
            level = DiagnosticLevel.valueOf(parts[1]),
            module = decodeText(parts[2]),
            eventCode = decodeText(parts[3]),
            runId = decodeText(parts[4]),
            sequence = parts[5].toLong(),
            operationId = parts[6].takeIf(String::isNotEmpty)?.let(::decodeText),
            safeDetail = decodeText(parts[7]),
        )
    }.getOrNull()

    private fun encodeText(value: String): String = value.encodeToByteArray().joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private fun decodeText(value: String): String {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }.decodeToString()
    }
}

private fun DiagnosticLevel.toAndroidPriority(): Int = when (this) {
    DiagnosticLevel.DEBUG -> Log.DEBUG
    DiagnosticLevel.INFO -> Log.INFO
    DiagnosticLevel.WARN -> Log.WARN
    DiagnosticLevel.ERROR -> Log.ERROR
}
