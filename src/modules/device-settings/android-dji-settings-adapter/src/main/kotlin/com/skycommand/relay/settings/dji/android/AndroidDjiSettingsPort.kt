package com.skycommand.relay.settings.dji.android

import com.skycommand.relay.settings.command.SettingsRequest
import com.skycommand.relay.settings.command.SettingsSnapshot
import com.skycommand.relay.settings.executor.DjiSettingsPort
import com.skycommand.relay.settings.executor.SettingsDjiCompletion
import java.util.concurrent.atomic.AtomicBoolean

internal interface DjiSettingsCompletion {
    fun succeed(snapshot: SettingsSnapshot)
    fun fail()
}

internal interface DjiSettingsApi {
    fun execute(request: SettingsRequest, completion: DjiSettingsCompletion)
}

class AndroidDjiSettingsPort internal constructor(
    private val platform: DjiSettingsApi,
) : DjiSettingsPort {
    private val lock = Any()
    private var closed = false
    private var active: Active? = null

    override fun execute(request: SettingsRequest, completion: SettingsDjiCompletion) {
        val operation = synchronized(lock) {
            if (closed) null else Active(completion).also { active = it }
        }
        if (operation == null) {
            runCatching { completion.fail() }
            return
        }
        try {
            platform.execute(request, object : DjiSettingsCompletion {
                override fun succeed(snapshot: SettingsSnapshot) = finish(operation, snapshot)
                override fun fail() = fail(operation)
            })
        } catch (_: Throwable) {
            fail(operation)
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            active = null
        }
    }

    private fun finish(operation: Active, snapshot: SettingsSnapshot) {
        if (!operation.completeOnce()) return
        val deliver = synchronized(lock) {
            if (active === operation) active = null
            !closed
        }
        if (deliver) runCatching { operation.completion.succeed(snapshot) }
    }

    private fun fail(operation: Active) {
        if (!operation.completeOnce()) return
        val deliver = synchronized(lock) {
            if (active === operation) active = null
            !closed
        }
        if (deliver) runCatching { operation.completion.fail() }
    }

    private class Active(val completion: SettingsDjiCompletion) {
        private val completed = AtomicBoolean(false)
        fun completeOnce(): Boolean = completed.compareAndSet(false, true)
    }

    companion object {
        fun create(): DjiSettingsPort = AndroidDjiSettingsPort(MsdkV5SettingsApi())
    }
}
