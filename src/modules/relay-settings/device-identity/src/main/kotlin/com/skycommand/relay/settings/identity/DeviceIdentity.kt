package com.skycommand.relay.settings.identity

import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@JvmInline
value class DeviceId private constructor(val value: String) {
    companion object {
        internal fun validated(value: String): DeviceId = DeviceId(value)
    }
}

fun interface DeviceIdentityStorage {
    fun readOrCreate(candidate: String): String
}

fun interface DeviceIdentityGenerator {
    fun generate(): String
}

sealed interface DeviceIdentityResult {
    data class Available(val deviceId: DeviceId) : DeviceIdentityResult

    data class Unavailable(val reason: DeviceIdentityFailure) : DeviceIdentityResult
}

enum class DeviceIdentityFailure {
    STORAGE_FAILURE,
    STORED_VALUE_INVALID,
    GENERATED_VALUE_INVALID,
}

class DeviceIdentity private constructor(
    private val storage: DeviceIdentityStorage,
    private val generator: DeviceIdentityGenerator,
) {
    private val lock = ReentrantLock()
    private val resolutionComplete = lock.newCondition()
    private var cached: DeviceId? = null
    private var resolving = false
    private var resolvingThread: Thread? = null

    fun identity(): DeviceIdentityResult {
        var resolveIndependently = false
        lock.lock()
        try {
            cached?.let { return DeviceIdentityResult.Available(it) }

            if (resolving && resolvingThread !== Thread.currentThread()) {
                var interrupted = false
                while (resolving && cached == null) {
                    try {
                        resolutionComplete.await()
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
                if (interrupted) Thread.currentThread().interrupt()
                cached?.let { return DeviceIdentityResult.Available(it) }
            }

            if (resolving) {
                resolveIndependently = true
            } else {
                resolving = true
                resolvingThread = Thread.currentThread()
            }
        } finally {
            lock.unlock()
        }
        if (resolveIndependently) return resolveWithoutCoordination()

        val result = resolveWithoutCoordination()
        lock.withLock {
            if (result is DeviceIdentityResult.Available && cached == null) {
                cached = result.deviceId
            }
            resolving = false
            resolvingThread = null
            resolutionComplete.signalAll()
        }
        return cachedResultOr(result)
    }

    private fun cachedResultOr(result: DeviceIdentityResult): DeviceIdentityResult = lock.withLock {
        cached?.let { DeviceIdentityResult.Available(it) } ?: result
    }

    private fun resolveWithoutCoordination(): DeviceIdentityResult {
        val candidate = try {
            generator.generate()
        } catch (_: Exception) {
            return DeviceIdentityResult.Unavailable(DeviceIdentityFailure.STORAGE_FAILURE)
        }
        if (!isValid(candidate)) {
            return DeviceIdentityResult.Unavailable(DeviceIdentityFailure.GENERATED_VALUE_INVALID)
        }

        val stored = try {
            storage.readOrCreate(candidate)
        } catch (_: Exception) {
            return DeviceIdentityResult.Unavailable(DeviceIdentityFailure.STORAGE_FAILURE)
        }
        if (!isValid(stored)) {
            return DeviceIdentityResult.Unavailable(DeviceIdentityFailure.STORED_VALUE_INVALID)
        }
        return DeviceIdentityResult.Available(DeviceId.validated(stored))
    }

    companion object {
        fun create(
            storage: DeviceIdentityStorage,
            generator: DeviceIdentityGenerator = DeviceIdentityGenerator { UUID.randomUUID().toString() },
        ): DeviceIdentity = DeviceIdentity(storage, generator)

        private fun isValid(value: String): Boolean =
            value.isNotBlank() &&
                value.codePointCount(0, value.length) in 1..128 &&
                value.none(Char::isISOControl)
    }
}
