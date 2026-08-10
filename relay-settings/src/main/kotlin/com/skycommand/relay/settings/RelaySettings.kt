package com.skycommand.relay.settings

import com.skycommand.relay.settings.endpoint.ValidatedRelayEndpoint
import com.skycommand.relay.settings.identity.DeviceIdentity
import com.skycommand.relay.settings.identity.DeviceIdentityFailure
import com.skycommand.relay.settings.identity.DeviceIdentityGenerator
import com.skycommand.relay.settings.identity.DeviceIdentityResult
import com.skycommand.relay.settings.identity.DeviceId
import com.skycommand.relay.settings.store.EndpointSaveResult
import com.skycommand.relay.settings.store.RelaySettingsBackend
import com.skycommand.relay.settings.store.RelaySettingsSnapshot
import com.skycommand.relay.settings.store.RelaySettingsStore
import com.skycommand.relay.settings.store.SettingsLoadResult
import com.skycommand.relay.settings.store.SettingsStoreFailure

data class RelayConnectionSettings(
    val endpoint: ValidatedRelayEndpoint?,
    val deviceId: DeviceId,
)

sealed interface RelayConnectionSettingsResult {
    data class Available(val settings: RelayConnectionSettings) : RelayConnectionSettingsResult

    data class StoreUnavailable(val reason: SettingsStoreFailure) : RelayConnectionSettingsResult

    data class IdentityUnavailable(val reason: DeviceIdentityFailure) : RelayConnectionSettingsResult
}

class RelaySettings private constructor(
    private val store: RelaySettingsStore,
    private val identity: DeviceIdentity,
) {
    fun loadEndpoint(): SettingsLoadResult = store.load()

    fun saveEndpoint(value: String): EndpointSaveResult = store.setEndpoint(value)

    fun clearEndpoint(): EndpointSaveResult = store.clearEndpoint()

    fun deviceIdentity(): DeviceIdentityResult = identity.identity()

    fun connectionSettings(): RelayConnectionSettingsResult {
        val endpoint = when (val result = store.load()) {
            is SettingsLoadResult.Available -> result.snapshot
            is SettingsLoadResult.Unavailable -> return RelayConnectionSettingsResult.StoreUnavailable(result.reason)
        }
        return when (val result = identity.identity()) {
            is DeviceIdentityResult.Available -> RelayConnectionSettingsResult.Available(
                RelayConnectionSettings(endpoint.endpoint, result.deviceId),
            )

            is DeviceIdentityResult.Unavailable -> RelayConnectionSettingsResult.IdentityUnavailable(result.reason)
        }
    }

    companion object {
        fun create(
            backend: RelaySettingsBackend,
            generator: DeviceIdentityGenerator = DeviceIdentityGenerator { java.util.UUID.randomUUID().toString() },
        ): RelaySettings {
            val store = RelaySettingsStore.create(backend)
            return RelaySettings(store, DeviceIdentity.create(store, generator))
        }
    }
}
