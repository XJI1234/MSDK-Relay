package com.skycommand.relay.runtime.bootstrap

enum class BootstrapState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED,
}

enum class BootstrapFailurePhase {
    START,
    STOP,
}

data class BootstrapFailure(
    val moduleName: String,
    val phase: BootstrapFailurePhase,
)

interface BootstrapModule {
    val name: String
    fun start()
    fun stop()
}

sealed interface BootstrapResult {
    data object Started : BootstrapResult

    data object Stopped : BootstrapResult

    data class Rejected(val failure: BootstrapFailure) : BootstrapResult

    data object AlreadyRunning : BootstrapResult

    data object AlreadyStopped : BootstrapResult

    data object TransitionInProgress : BootstrapResult
}

class AppBootstrap private constructor(
    private val modules: List<BootstrapModule>,
) {
    private val lock = Any()
    private var state = BootstrapState.STOPPED
    private var startedModules = emptyList<BootstrapModule>()

    init {
        require(modules.map { it.name }.distinct().size == modules.size) { "Bootstrap module names must be unique" }
        require(modules.all { it.name.isNotBlank() }) { "Bootstrap module names must not be blank" }
    }

    fun start(): BootstrapResult {
        synchronized(lock) {
            when (state) {
                BootstrapState.RUNNING -> return BootstrapResult.AlreadyRunning
                BootstrapState.STARTING,
                BootstrapState.STOPPING,
                -> return BootstrapResult.TransitionInProgress

                BootstrapState.STOPPED,
                BootstrapState.FAILED,
                -> state = BootstrapState.STARTING
            }
        }

        val started = mutableListOf<BootstrapModule>()
        for (module in modules) {
            try {
                module.start()
                started += module
            } catch (_: Exception) {
                started.asReversed().forEach { runCatching { it.stop() } }
                synchronized(lock) {
                    startedModules = emptyList()
                    state = BootstrapState.FAILED
                }
                return BootstrapResult.Rejected(BootstrapFailure(module.name, BootstrapFailurePhase.START))
            }
        }
        synchronized(lock) {
            startedModules = started.toList()
            state = BootstrapState.RUNNING
        }
        return BootstrapResult.Started
    }

    fun stop(): BootstrapResult {
        val modulesToStop: List<BootstrapModule>
        synchronized(lock) {
            when (state) {
                BootstrapState.STOPPED -> return BootstrapResult.AlreadyStopped
                BootstrapState.STARTING,
                BootstrapState.STOPPING,
                -> return BootstrapResult.TransitionInProgress

                BootstrapState.RUNNING,
                BootstrapState.FAILED,
                -> Unit
            }
            modulesToStop = startedModules
            state = BootstrapState.STOPPING
        }

        var failure: BootstrapFailure? = null
        modulesToStop.asReversed().forEach { module ->
            try {
                module.stop()
            } catch (_: Exception) {
                if (failure == null) failure = BootstrapFailure(module.name, BootstrapFailurePhase.STOP)
            }
        }
        synchronized(lock) {
            startedModules = emptyList()
            state = if (failure == null) BootstrapState.STOPPED else BootstrapState.FAILED
        }
        return failure?.let { BootstrapResult.Rejected(it) } ?: BootstrapResult.Stopped
    }

    fun snapshot(): BootstrapState = synchronized(lock) { state }

    companion object {
        fun create(modules: List<BootstrapModule>): AppBootstrap = AppBootstrap(modules.toList())
    }
}
