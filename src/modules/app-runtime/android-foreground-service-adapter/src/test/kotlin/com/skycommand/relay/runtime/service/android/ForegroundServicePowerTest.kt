package com.skycommand.relay.runtime.service.android

import org.junit.Test
import kotlin.test.*

class ForegroundServicePowerTest {
    @Test fun ownsBothLocksOnceAndReleasesInReverseOrder() {
        val calls = mutableListOf<String>()
        val power = ForegroundServicePower(lock("cpu", calls), lock("wifi", calls))
        power.acquire(); power.acquire()
        power.release(); power.release()
        assertEquals(listOf("cpu+", "wifi+", "wifi-", "cpu-"), calls)
        power.acquire(); power.release()
        assertEquals(8, calls.size)
    }

    @Test fun rollsBackCpuWhenWifiCannotBeAcquiredAndAllowsRetry() {
        val calls = mutableListOf<String>()
        var failWifi = true
        val wifi = object : ServicePowerLock {
            override fun acquire() { calls += "wifi+"; if (failWifi) error("wifi unavailable") }
            override fun release() { calls += "wifi-" }
        }
        val power = ForegroundServicePower(lock("cpu", calls), wifi)
        assertFailsWith<IllegalStateException> { power.acquire() }
        power.release()
        assertEquals(listOf("cpu+", "wifi+", "cpu-"), calls)
        failWifi = false
        power.acquire(); power.release()
        assertEquals(listOf("cpu+", "wifi+", "cpu-", "cpu+", "wifi+", "wifi-", "cpu-"), calls)
    }

    @Test fun failedCpuDoesNotAcquireWifi() {
        val calls = mutableListOf<String>()
        val cpu = object : ServicePowerLock {
            override fun acquire() { error("cpu unavailable") }
            override fun release() { fail("unowned cpu lock") }
        }
        val power = ForegroundServicePower(cpu, lock("wifi", calls))
        assertFailsWith<IllegalStateException> { power.acquire() }
        power.release()
        assertTrue(calls.isEmpty())
    }

    @Test fun wifiReleaseFailureStillReleasesCpuAndRetriesOnlyTheRemainingLock() {
        val calls = mutableListOf<String>()
        var failRelease = true
        val wifi = object : ServicePowerLock {
            override fun acquire() { calls += "wifi+" }
            override fun release() { calls += "wifi-"; if (failRelease) error("release failed") }
        }
        val power = ForegroundServicePower(lock("cpu", calls), wifi)
        power.acquire()
        assertFailsWith<IllegalStateException> { power.release() }
        failRelease = false
        power.release()
        power.release()
        assertEquals(listOf("cpu+", "wifi+", "wifi-", "cpu-", "wifi-"), calls)
    }

    @Test fun failedAcquisitionAndFailedRollbackRetainCpuForDestroyCleanup() {
        val calls = mutableListOf<String>()
        var failRelease = true
        val cpu = object : ServicePowerLock {
            override fun acquire() { calls += "cpu+" }
            override fun release() { calls += "cpu-"; if (failRelease) error("cpu release failed") }
        }
        val wifi = object : ServicePowerLock {
            override fun acquire() { error("wifi acquire failed") }
            override fun release() { fail("wifi not owned") }
        }
        val power = ForegroundServicePower(cpu, wifi)
        val failure = assertFailsWith<IllegalStateException> { power.acquire() }
        assertEquals("wifi acquire failed", failure.message)
        assertEquals(1, failure.suppressed.size)
        failRelease = false
        power.release(); power.release()
        assertEquals(listOf("cpu+", "cpu-", "cpu-"), calls)
    }

    @Test fun bothReleaseFailuresRemainOwnedAndKeepBothErrors() {
        var failRelease = true
        val calls = mutableListOf<String>()
        fun failingLock(name: String) = object : ServicePowerLock {
            override fun acquire() { calls += "$name+" }
            override fun release() { calls += "$name-"; if (failRelease) error(name) }
        }
        val power = ForegroundServicePower(failingLock("cpu"), failingLock("wifi"))
        power.acquire()
        val failure = assertFailsWith<IllegalStateException> { power.release() }
        assertEquals("wifi", failure.message)
        assertEquals("cpu", failure.suppressed.single().message)
        failRelease = false
        power.release(); power.release()
        assertEquals(listOf("cpu+", "wifi+", "wifi-", "cpu-", "wifi-", "cpu-"), calls)
    }

    private fun lock(name: String, calls: MutableList<String>) = object : ServicePowerLock {
        override fun acquire() { calls += "$name+" }
        override fun release() { calls += "$name-" }
    }
}
