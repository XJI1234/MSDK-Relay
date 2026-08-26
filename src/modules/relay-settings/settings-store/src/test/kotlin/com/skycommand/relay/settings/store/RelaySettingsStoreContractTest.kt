package com.skycommand.relay.settings.store

import com.skycommand.relay.settings.endpoint.EndpointRejection
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelaySettingsStoreContractTest {
    @Test fun savesClearsAndReloadsAValidatedEndpoint() {
        val backend = FakeBackend()
        val store = RelaySettingsStore.create(backend)

        assertNull(available(store.load()).endpoint)
        assertEquals("wss://desktop/relay?token=private", saved(store.setEndpoint("wss://desktop/relay?token=private")).endpoint?.value)
        assertEquals("wss://desktop/relay?token=private", available(store.load()).endpoint?.value)
        assertNull(saved(store.clearEndpoint()).endpoint)
        assertNull(available(store.load()).endpoint)
    }

    @Test fun rejectsInvalidEndpointBeforeCallingBackend() {
        val backend = FakeBackend()
        val store = RelaySettingsStore.create(backend)

        val result = assertIs<EndpointSaveResult.Rejected>(store.setEndpoint("http://desktop"))

        assertEquals(EndpointRejection.INVALID_SCHEME, result.reason)
        assertEquals(0, backend.updateCalls)
    }

    @Test fun migratesLegacySettingsAndClearsAnInvalidEndpoint() {
        val backend = FakeBackend(RelaySettingsRecord(schemaVersion = 0, endpoint = "http://desktop", deviceId = "phone-1"))

        assertNull(available(RelaySettingsStore.create(backend).load()).endpoint)
        assertEquals(RelaySettingsStore.CURRENT_SCHEMA_VERSION, backend.record?.schemaVersion)
        assertNull(backend.record?.endpoint)
        assertEquals("phone-1", backend.record?.deviceId)
    }

    @Test fun protectsUnknownSchemasAndMapsBackendFailuresWithoutCaching() {
        val future = FakeBackend(RelaySettingsRecord(schemaVersion = 2, endpoint = null, deviceId = null))
        assertEquals(
            SettingsStoreFailure.UNSUPPORTED_SCHEMA,
            assertIs<SettingsLoadResult.Unavailable>(RelaySettingsStore.create(future).load()).reason,
        )
        assertEquals(2, future.record?.schemaVersion)

        val failing = FakeBackend(failuresBeforeSuccess = 1)
        val store = RelaySettingsStore.create(failing)
        assertEquals(
            SettingsStoreFailure.BACKEND_FAILURE,
            assertIs<EndpointSaveResult.Unavailable>(store.setEndpoint("ws://desktop")).reason,
        )
        assertEquals("ws://desktop/relay", saved(store.setEndpoint("ws://desktop")).endpoint?.value)
    }

    @Test fun readOrCreatePreservesAValidIdAndRecoversAInvalidOneWithoutChangingEndpoint() {
        val valid = FakeBackend(RelaySettingsRecord(1, "ws://desktop", "other-phone"))
        assertEquals("other-phone", RelaySettingsStore.create(valid).readOrCreate("phone-1"))

        val corrupt = FakeBackend(RelaySettingsRecord(1, "ws://desktop", "bad\u0000id"))
        val recovered = RelaySettingsStore.create(corrupt)
        assertEquals("phone-1", recovered.readOrCreate("phone-1"))
        assertEquals("phone-1", corrupt.record?.deviceId)
        assertEquals("ws://desktop/relay", corrupt.record?.endpoint)
        assertFailsWith<IllegalArgumentException> { recovered.readOrCreate(" ") }
    }

    @Test fun concurrentUpdatesRemainAtomic() {
        val backend = FakeBackend()
        val store = RelaySettingsStore.create(backend)
        val endpoints = (1..40).map { "ws://desktop/relay?n=$it" }
        val results = ConcurrentLinkedQueue<EndpointSaveResult>()
        val pool = Executors.newFixedThreadPool(8)
        try {
            endpoints.forEach { endpoint -> pool.submit { results += store.setEndpoint(endpoint) } }
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }

        assertEquals(40, results.count { it is EndpointSaveResult.Saved })
        assertTrue(available(store.load()).endpoint?.value in endpoints)
    }

    private fun available(result: SettingsLoadResult): RelaySettingsSnapshot =
        assertIs<SettingsLoadResult.Available>(result).snapshot

    private fun saved(result: EndpointSaveResult): RelaySettingsSnapshot =
        assertIs<EndpointSaveResult.Saved>(result).snapshot

    private class FakeBackend(
        var record: RelaySettingsRecord? = null,
        private var failuresBeforeSuccess: Int = 0,
    ) : RelaySettingsBackend {
        var updateCalls = 0

        @Synchronized
        override fun update(change: (RelaySettingsRecord?) -> RelaySettingsRecord?): RelaySettingsRecord? {
            updateCalls += 1
            if (failuresBeforeSuccess-- > 0) error("backend failure")
            return change(record).also { record = it }
        }
    }
}
