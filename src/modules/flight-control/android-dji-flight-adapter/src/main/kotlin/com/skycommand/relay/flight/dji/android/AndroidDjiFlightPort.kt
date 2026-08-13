package com.skycommand.relay.flight.dji.android

import com.skycommand.relay.flight.command.FlightAction
import com.skycommand.relay.flight.dji.DjiFlightPort
import com.skycommand.relay.flight.dji.FlightDjiCompletion
import java.util.concurrent.atomic.AtomicBoolean

internal interface DjiFlightCompletion {
    fun succeed()
    fun fail()
}

internal interface DjiFlightApi {
    fun takeoff(completion: DjiFlightCompletion)
    fun land(completion: DjiFlightCompletion)
    fun returnHome(completion: DjiFlightCompletion)
}

class AndroidDjiFlightPort internal constructor(
    private val platform: DjiFlightApi,
) : DjiFlightPort {
    private val lock = Any()
    private var active: Active? = null
    private var closed = false

    override fun execute(action: FlightAction, completion: FlightDjiCompletion) {
        val operation = synchronized(lock) {
            if (closed) null else Active(completion).also { active = it }
        }
        if (operation == null) {
            runCatching { completion.fail() }
            return
        }
        try {
            when (action) {
                FlightAction.TAKEOFF -> platform.takeoff(callbackFor(operation))
                FlightAction.LAND -> platform.land(callbackFor(operation))
                FlightAction.RETURN_HOME -> platform.returnHome(callbackFor(operation))
            }
        } catch (_: Throwable) {
            finish(operation, succeeded = false)
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            active = null
        }
    }

    private fun callbackFor(operation: Active) = object : DjiFlightCompletion {
        override fun succeed() = finish(operation, succeeded = true)
        override fun fail() = finish(operation, succeeded = false)
    }

    private fun finish(operation: Active, succeeded: Boolean) {
        if (!operation.finishOnce()) return
        val deliver = synchronized(lock) {
            if (active === operation) active = null
            !closed
        }
        if (deliver) runCatching { if (succeeded) operation.completion.succeed() else operation.completion.fail() }
    }

    private class Active(val completion: FlightDjiCompletion) {
        private val completed = AtomicBoolean(false)
        fun finishOnce(): Boolean = completed.compareAndSet(false, true)
    }

    companion object {
        fun create(): DjiFlightPort = AndroidDjiFlightPort(MsdkV5FlightApi())
    }
}
