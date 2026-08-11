package com.skycommand.relay.device.pairing.status.android

import com.skycommand.relay.device.pairing.status.PairingStatusListener
import com.skycommand.relay.device.pairing.status.PairingStatusPort
import com.skycommand.relay.device.pairing.status.PairingStatusSignal
import com.skycommand.relay.device.pairing.status.PairingStatusSubscription
import com.skycommand.relay.device.state.PairingState

internal data class DjiPairingStatusFact(val name: String)
internal fun interface DjiPairingStatusListener { fun onChanged(fact: DjiPairingStatusFact) }
internal fun interface DjiPairingStatusObservation { fun close() }
internal interface DjiPairingStatusApi { fun observe(listener: DjiPairingStatusListener): DjiPairingStatusObservation }

class AndroidPairingStatusPort internal constructor(
    private val platform: DjiPairingStatusApi,
) : PairingStatusPort {
    private val lock = Any()
    private var generation = 0L
    private var sourceRevision = 0L
    private var active: Active? = null

    override fun start(listener: PairingStatusListener): PairingStatusSubscription {
        val operation = synchronized(lock) {
            active?.let { return PairingStatusSubscription { } }
            Active(++generation, listener).also { active = it }
        }
        val observation = runCatching { platform.observe(listenerFor(operation)) }.getOrElse {
            clear(operation)
            throw IllegalStateException(UNAVAILABLE_REASON)
        }
        val mustClose = synchronized(lock) {
            if (active === operation) {
                operation.observation = observation
                false
            } else true
        }
        if (mustClose) runCatching { observation.close() }
        return PairingStatusSubscription { cancel(operation) }
    }

    override fun stop() {
        val observation = synchronized(lock) { active?.also { active = null }?.observation }
        runCatching { observation?.close() }
    }

    private fun listenerFor(operation: Active) = DjiPairingStatusListener { fact ->
        val delivery = synchronized(lock) {
            if (active !== operation || generation != operation.generation) null else {
                PairingStatusSignal(++sourceRevision, fact.toPairingState()) to operation.listener
            }
        }
        delivery?.let { (signal, listener) -> runCatching { listener.onChanged(signal) } }
    }

    private fun DjiPairingStatusFact.toPairingState(): PairingState = when (name) {
        "UNPAIRED" -> PairingState.IDLE
        "PAIRING" -> PairingState.PAIRING
        "PAIRED" -> PairingState.PAIRED
        "STOP_THEN_SWITCH" -> PairingState.STOPPING
        "STOP_FW_TYPE_NOT_MATCHED", "STOP_DEV_MISMATCH", "STOP_SUB_RC_REJECT", "STOP_TARGET_TYPE_MISMATCH", "STOP_RELAY_NOT_SUPPORT_SUB_RC" -> PairingState.FAILED
        else -> PairingState.UNKNOWN
    }

    private fun cancel(operation: Active) {
        val observation = synchronized(lock) {
            if (active !== operation) null else { active = null; operation.observation }
        }
        runCatching { observation?.close() }
    }

    private fun clear(operation: Active) = synchronized(lock) { if (active === operation) active = null }

    private data class Active(val generation: Long, val listener: PairingStatusListener, var observation: DjiPairingStatusObservation? = null)

    companion object {
        private const val UNAVAILABLE_REASON = "pairing status listener unavailable"
        fun create(): PairingStatusPort = AndroidPairingStatusPort(MsdkV5PairingStatusApi())
    }
}
