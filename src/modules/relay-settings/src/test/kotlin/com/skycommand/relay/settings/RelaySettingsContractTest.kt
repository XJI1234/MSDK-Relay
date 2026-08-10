package com.skycommand.relay.settings

import com.skycommand.relay.settings.identity.DeviceIdentityGenerator
import com.skycommand.relay.settings.store.RelaySettingsBackend
import com.skycommand.relay.settings.store.RelaySettingsRecord
import com.skycommand.relay.settings.store.SettingsStoreFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RelaySettingsContractTest {
    @Test fun composesValidatedEndpointAndStableDeviceIdentity() {
        val backend = Backend()
        val settings = RelaySettings.create(backend, DeviceIdentityGenerator { "phone-1" })

        settings.saveEndpoint("wss://desktop/relay")
        val result = assertIs<RelayConnectionSettingsResult.Available>(settings.connectionSettings())

        assertEquals("wss://desktop/relay", result.settings.endpoint?.value)
        assertEquals("phone-1", result.settings.deviceId.value)
    }

    @Test fun permitsAnEmptyEndpointButMapsStoreAndIdentityFailures() {
        val noEndpoint = RelaySettings.create(Backend(), DeviceIdentityGenerator { "phone-1" })
        assertNull(assertIs<RelayConnectionSettingsResult.Available>(noEndpoint.connectionSettings()).settings.endpoint)

        val failedStore = RelaySettings.create(Backend(failuresBeforeSuccess = 1), DeviceIdentityGenerator { error("must not run") })
        assertEquals(
            SettingsStoreFailure.BACKEND_FAILURE,
            assertIs<RelayConnectionSettingsResult.StoreUnavailable>(failedStore.connectionSettings()).reason,
        )

        val failedIdentity = RelaySettings.create(Backend(), DeviceIdentityGenerator { " " })
        assertIs<RelayConnectionSettingsResult.IdentityUnavailable>(failedIdentity.connectionSettings())
    }

    private class Backend(
        private var record: RelaySettingsRecord? = null,
        private var failuresBeforeSuccess: Int = 0,
    ) : RelaySettingsBackend {
        override fun update(change: (RelaySettingsRecord?) -> RelaySettingsRecord?): RelaySettingsRecord? {
            if (failuresBeforeSuccess-- > 0) error("backend failure")
            return change(record).also { record = it }
        }
    }
}
