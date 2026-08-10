package com.skycommand.relay.settings.identity

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeviceIdentityContractTest {
    @Test fun createsCachesAndReturnsAProtocolValidIdentity() {
        val storage = RecordingStorage()
        val generator = CountingGenerator("phone-1")
        val identity = DeviceIdentity.create(storage, generator)

        assertEquals("phone-1", available(identity).value)
        assertEquals("phone-1", available(identity).value)
        assertEquals(1, generator.calls.get())
        assertEquals(listOf("phone-1"), storage.candidates)
    }

    @Test fun acceptsAnExistingAtomicStorageWinnerWithoutChangingIt() {
        val storage = RecordingStorage(stored = "other-process-device")
        val identity = DeviceIdentity.create(storage, CountingGenerator("phone-1"))

        assertEquals("other-process-device", available(identity).value)
        assertEquals(listOf("phone-1"), storage.candidates)
    }

    @Test fun rejectsInvalidGeneratedAndStoredValuesWithoutCachingOrOverwriting() {
        val invalidGeneratedStorage = RecordingStorage()
        val invalidGenerated = DeviceIdentity.create(invalidGeneratedStorage, CountingGenerator(" "))
        assertEquals(
            DeviceIdentityFailure.GENERATED_VALUE_INVALID,
            assertIs<DeviceIdentityResult.Unavailable>(invalidGenerated.identity()).reason,
        )
        assertTrue(invalidGeneratedStorage.candidates.isEmpty())

        val invalidStoredStorage = RecordingStorage(stored = "bad\u0000id")
        val invalidStored = DeviceIdentity.create(invalidStoredStorage, CountingGenerator("phone-1"))
        assertEquals(
            DeviceIdentityFailure.STORED_VALUE_INVALID,
            assertIs<DeviceIdentityResult.Unavailable>(invalidStored.identity()).reason,
        )
        assertEquals(
            DeviceIdentityFailure.STORED_VALUE_INVALID,
            assertIs<DeviceIdentityResult.Unavailable>(invalidStored.identity()).reason,
        )
        assertEquals(listOf("phone-1", "phone-1"), invalidStoredStorage.candidates)
        assertEquals(2, invalidStoredStorage.candidates.size)
    }

    @Test fun retriesAfterStorageAndGeneratorFailures() {
        val storage = RecordingStorage(failuresBeforeSuccess = 1)
        val identity = DeviceIdentity.create(storage, CountingGenerator("phone-1", failuresBeforeSuccess = 1))

        assertEquals(
            DeviceIdentityFailure.STORAGE_FAILURE,
            assertIs<DeviceIdentityResult.Unavailable>(identity.identity()).reason,
        )
        assertEquals(
            DeviceIdentityFailure.STORAGE_FAILURE,
            assertIs<DeviceIdentityResult.Unavailable>(identity.identity()).reason,
        )
        assertEquals("phone-1", available(identity).value)
        assertEquals(2, storage.candidates.size)
    }

    @Test fun defaultGeneratorCreatesAProtocolValidIdentity() {
        val result = assertIs<DeviceIdentityResult.Available>(DeviceIdentity.create(RecordingStorage()).identity())

        assertTrue(result.deviceId.value.isNotBlank())
        assertTrue(result.deviceId.value.codePointCount(0, result.deviceId.value.length) in 1..128)
        assertTrue(result.deviceId.value.none(Char::isISOControl))
    }

    @Test fun enforcesProtocolIdentifierBoundaries() {
        val maximum = "x".repeat(128)
        assertEquals(maximum, available(DeviceIdentity.create(RecordingStorage(), CountingGenerator(maximum))).value)

        listOf("", "   ", "x".repeat(129), "device\n1").forEach { invalid ->
            assertEquals(
                DeviceIdentityFailure.GENERATED_VALUE_INVALID,
                assertIs<DeviceIdentityResult.Unavailable>(
                    DeviceIdentity.create(RecordingStorage(), CountingGenerator(invalid)).identity(),
                ).reason,
            )
        }
    }

    @Test fun concurrentCallersShareOneResolvedIdentity() {
        val storage = RecordingStorage(delayMillis = 30)
        val generator = CountingGenerator("phone-1")
        val identity = DeviceIdentity.create(storage, generator)
        val results = ConcurrentLinkedQueue<DeviceIdentityResult>()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(40) { pool.submit { start.await(); results += identity.identity() } }
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(40, results.size)
        assertEquals(40, results.count { it is DeviceIdentityResult.Available && it.deviceId.value == "phone-1" })
        assertEquals(1, storage.candidates.size)
        assertEquals(1, generator.calls.get())
    }

    @Test fun storageMaySynchronouslyReenterIdentityResolution() {
        lateinit var identity: DeviceIdentity
        var reentered = false
        val storage = DeviceIdentityStorage { candidate ->
            if (!reentered) {
                reentered = true
                assertEquals("phone-1", available(identity).value)
            }
            candidate
        }
        identity = DeviceIdentity.create(storage, CountingGenerator("phone-1"))

        assertEquals("phone-1", available(identity).value)
    }

    private fun available(identity: DeviceIdentity): DeviceId =
        assertIs<DeviceIdentityResult.Available>(identity.identity()).deviceId

    private class CountingGenerator(
        private val value: String,
        private var failuresBeforeSuccess: Int = 0,
    ) : DeviceIdentityGenerator {
        val calls = AtomicInteger()

        override fun generate(): String {
            calls.incrementAndGet()
            if (failuresBeforeSuccess-- > 0) error("generator failure")
            return value
        }
    }

    private class RecordingStorage(
        private var stored: String? = null,
        private var failuresBeforeSuccess: Int = 0,
        private val delayMillis: Long = 0,
    ) : DeviceIdentityStorage {
        val candidates = mutableListOf<String>()

        @Synchronized
        override fun readOrCreate(candidate: String): String {
            candidates += candidate
            if (failuresBeforeSuccess-- > 0) error("storage failure")
            if (delayMillis > 0) Thread.sleep(delayMillis)
            return stored ?: candidate.also { stored = it }
        }
    }
}
