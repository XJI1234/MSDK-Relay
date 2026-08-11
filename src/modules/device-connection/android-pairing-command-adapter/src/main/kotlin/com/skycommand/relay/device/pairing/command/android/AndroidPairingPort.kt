package com.skycommand.relay.device.pairing.command.android

import com.skycommand.relay.device.operation.DjiOperation
import com.skycommand.relay.device.operation.OperationCompletion
import com.skycommand.relay.device.pairing.PairingPort

internal enum class PairingCommand { START, STOP }
internal interface DjiCommandCompletion {
    fun succeed()

    fun fail()
}
internal interface DjiPairingCommandApi { fun perform(action: PairingCommand, completion: DjiCommandCompletion) }

class AndroidPairingPort internal constructor(
    private val platform: DjiPairingCommandApi,
) : PairingPort {
    override fun startPairing(): DjiOperation = operation(PairingCommand.START)
    override fun stopPairing(): DjiOperation = operation(PairingCommand.STOP)

    private fun operation(command: PairingCommand) = DjiOperation { completion ->
        val once = OnceCompletion(completion)
        runCatching { platform.perform(command, once) }.onFailure { once.fail() }
    }

    private class OnceCompletion(private val delegate: OperationCompletion) : DjiCommandCompletion {
        private val lock = Any(); private var completed = false
        override fun succeed() = finish { delegate.succeed() }
        override fun fail() = finish { delegate.fail() }
        private fun finish(callback: () -> Unit) {
            val deliver = synchronized(lock) { if (completed) false else { completed = true; true } }
            if (deliver) callback()
        }
    }

    companion object { fun create(): PairingPort = AndroidPairingPort(MsdkV5PairingCommandApi()) }
}
