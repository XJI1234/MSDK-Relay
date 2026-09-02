package com.skycommand.relay.device.pairing.command.android

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidPairingPortContractTest {
    @Test fun operationsAreLazyAndUseTheRequestedActionOnce() {
        val api = FakeApi(); val port = AndroidPairingPort(api); var success = 0
        val operation = port.startPairing()
        assertEquals(emptyList(), api.calls)
        operation.run(object : com.skycommand.relay.device.operation.OperationCompletion {
            override fun succeed() { success += 1 }
            override fun fail() = Unit
            override fun confirmHardwareSettled() = false
        })
        api.completeSuccess(); api.completeSuccess()
        assertEquals(listOf(PairingCommand.START), api.calls); assertEquals(1, success)
    }

    @Test fun mapsFailureAndSynchronousFailureWithoutThrowing() {
        val api = FakeApi(); val port = AndroidPairingPort(api); var failures = 0
        port.stopPairing().run(FakeCompletion(onFail = { failures += 1 })); api.completeFailure()
        api.throwOnCall = true; port.startPairing().run(FakeCompletion(onFail = { failures += 1 }))
        assertEquals(listOf(PairingCommand.STOP, PairingCommand.START), api.calls); assertEquals(2, failures)
    }

    private class FakeApi : DjiPairingCommandApi {
        val calls = mutableListOf<PairingCommand>(); var throwOnCall = false; private var completion: DjiCommandCompletion? = null
        override fun perform(action: PairingCommand, completion: DjiCommandCompletion) { calls += action; if (throwOnCall) error("dji"); this.completion = completion }
        fun completeSuccess() = completion?.succeed(); fun completeFailure() = completion?.fail()
    }
    private class FakeCompletion(private val onSuccess: () -> Unit = {}, private val onFail: () -> Unit = {}) : com.skycommand.relay.device.operation.OperationCompletion {
        override fun succeed() = onSuccess(); override fun fail() = onFail()
        override fun confirmHardwareSettled() = false
    }
}
