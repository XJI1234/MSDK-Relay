package com.skycommand.relay.runtime.bootstrap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AppBootstrapContractTest {
    @Test fun startsInOrderAndStopsInReverseOrder() {
        val events = mutableListOf<String>()
        val bootstrap = AppBootstrap.create(listOf(Module("settings", events), Module("gateway", events)))

        assertIs<BootstrapResult.Started>(bootstrap.start())
        assertEquals(BootstrapState.RUNNING, bootstrap.snapshot())
        assertIs<BootstrapResult.Stopped>(bootstrap.stop())
        assertEquals(listOf("start:settings", "start:gateway", "stop:gateway", "stop:settings"), events)
    }

    @Test fun rollsBackStartedModulesWhenAStartFails() {
        val events = mutableListOf<String>()
        val bootstrap = AppBootstrap.create(listOf(Module("settings", events), Module("gateway", events, failStart = true)))

        val result = assertIs<BootstrapResult.Rejected>(bootstrap.start())

        assertEquals(BootstrapFailurePhase.START, result.failure.phase)
        assertEquals("gateway", result.failure.moduleName)
        assertEquals(BootstrapState.FAILED, bootstrap.snapshot())
        assertEquals(listOf("start:settings", "start:gateway", "stop:settings"), events)
    }

    @Test fun continuesCleanupWhenAStopFailsAndAllowsRetryStart() {
        val events = mutableListOf<String>()
        val gateway = Module("gateway", events, failStop = true)
        val bootstrap = AppBootstrap.create(listOf(Module("settings", events), gateway))
        bootstrap.start()

        val result = assertIs<BootstrapResult.Rejected>(bootstrap.stop())

        assertEquals(BootstrapFailurePhase.STOP, result.failure.phase)
        assertEquals(BootstrapState.FAILED, bootstrap.snapshot())
        assertEquals(listOf("start:settings", "start:gateway", "stop:gateway", "stop:settings"), events)
    }

    private class Module(
        override val name: String,
        private val events: MutableList<String>,
        private val failStart: Boolean = false,
        private val failStop: Boolean = false,
    ) : BootstrapModule {
        override fun start() {
            events += "start:$name"
            if (failStart) error("start failure")
        }

        override fun stop() {
            events += "stop:$name"
            if (failStop) error("stop failure")
        }
    }
}
