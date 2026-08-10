package com.skycommand.relay.settings.store

import com.skycommand.relay.settings.endpoint.EndpointRejection
import com.skycommand.relay.settings.endpoint.EndpointSettings
import com.skycommand.relay.settings.endpoint.ValidatedRelayEndpoint
import com.skycommand.relay.settings.identity.DeviceIdentityStorage

data class RelaySettingsRecord(
    val schemaVersion: Int,
    val endpoint: String?,
    val deviceId: String?,
)

fun interface RelaySettingsBackend {
    fun update(change: (RelaySettingsRecord?) -> RelaySettingsRecord?): RelaySettingsRecord?
}

data class RelaySettingsSnapshot(
    val endpoint: ValidatedRelayEndpoint?,
)

sealed interface SettingsLoadResult {
    data class Available(val snapshot: RelaySettingsSnapshot) : SettingsLoadResult

    data class Unavailable(val reason: SettingsStoreFailure) : SettingsLoadResult
}

sealed interface EndpointSaveResult {
    data class Saved(val snapshot: RelaySettingsSnapshot) : EndpointSaveResult

    data class Rejected(val reason: EndpointRejection) : EndpointSaveResult

    data class Unavailable(val reason: SettingsStoreFailure) : EndpointSaveResult
}

enum class SettingsStoreFailure {
    BACKEND_FAILURE,
    UNSUPPORTED_SCHEMA,
}

class RelaySettingsStore private constructor(
    private val backend: RelaySettingsBackend,
) : DeviceIdentityStorage {
    fun load(): SettingsLoadResult = try {
        val record = backend.update { current -> normalize(current).record }
        SettingsLoadResult.Available(snapshotOf(record))
    } catch (_: UnsupportedSchemaException) {
        SettingsLoadResult.Unavailable(SettingsStoreFailure.UNSUPPORTED_SCHEMA)
    } catch (_: Exception) {
        SettingsLoadResult.Unavailable(SettingsStoreFailure.BACKEND_FAILURE)
    }

    fun setEndpoint(value: String): EndpointSaveResult {
        val endpoint = when (val result = EndpointSettings.validate(value)) {
            is com.skycommand.relay.settings.endpoint.EndpointValidationResult.Valid -> result.endpoint
            is com.skycommand.relay.settings.endpoint.EndpointValidationResult.Invalid -> {
                return EndpointSaveResult.Rejected(result.reason)
            }
        }
        return try {
            val record = backend.update { current ->
                normalize(current).recordOrEmpty().copy(endpoint = endpoint.value)
            }
            EndpointSaveResult.Saved(snapshotOf(record))
        } catch (_: UnsupportedSchemaException) {
            EndpointSaveResult.Unavailable(SettingsStoreFailure.UNSUPPORTED_SCHEMA)
        } catch (_: Exception) {
            EndpointSaveResult.Unavailable(SettingsStoreFailure.BACKEND_FAILURE)
        }
    }

    fun clearEndpoint(): EndpointSaveResult = try {
        val record = backend.update { current -> normalize(current).recordOrEmpty().copy(endpoint = null) }
        EndpointSaveResult.Saved(snapshotOf(record))
    } catch (_: UnsupportedSchemaException) {
        EndpointSaveResult.Unavailable(SettingsStoreFailure.UNSUPPORTED_SCHEMA)
    } catch (_: Exception) {
        EndpointSaveResult.Unavailable(SettingsStoreFailure.BACKEND_FAILURE)
    }

    override fun readOrCreate(candidate: String): String {
        require(isValidDeviceId(candidate)) { "Device identity candidate is invalid" }
        return try {
            val record = backend.update { current ->
                val normalized = normalize(current).recordOrEmpty()
                if (isValidDeviceId(normalized.deviceId)) normalized else normalized.copy(deviceId = candidate)
            } ?: throw IllegalStateException("Settings record is unavailable")
            requireNotNull(record.deviceId)
        } catch (_: UnsupportedSchemaException) {
            throw IllegalStateException("Settings schema is unsupported")
        } catch (_: IllegalArgumentException) {
            throw IllegalStateException("Settings record is unavailable")
        } catch (_: Exception) {
            throw IllegalStateException("Settings record is unavailable")
        }
    }

    private fun snapshotOf(record: RelaySettingsRecord?): RelaySettingsSnapshot {
        val endpoint = record?.endpoint?.let { value ->
            (EndpointSettings.validate(value) as? com.skycommand.relay.settings.endpoint.EndpointValidationResult.Valid)?.endpoint
        }
        return RelaySettingsSnapshot(endpoint)
    }

    private fun normalize(record: RelaySettingsRecord?): NormalizedRecord {
        if (record == null) return NormalizedRecord(null)
        if (record.schemaVersion !in 0..CURRENT_SCHEMA_VERSION) throw UnsupportedSchemaException()
        val endpoint = record.endpoint?.let { value ->
            (EndpointSettings.validate(value) as? com.skycommand.relay.settings.endpoint.EndpointValidationResult.Valid)?.endpoint?.value
        }
        return NormalizedRecord(record.copy(schemaVersion = CURRENT_SCHEMA_VERSION, endpoint = endpoint))
    }

    private data class NormalizedRecord(val record: RelaySettingsRecord?) {
        fun recordOrEmpty(): RelaySettingsRecord = record ?: RelaySettingsRecord(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            endpoint = null,
            deviceId = null,
        )
    }

    private class UnsupportedSchemaException : RuntimeException()

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        fun create(backend: RelaySettingsBackend): RelaySettingsStore = RelaySettingsStore(backend)

        private fun isValidDeviceId(value: String?): Boolean =
            !value.isNullOrBlank() &&
                value.codePointCount(0, value.length) in 1..128 &&
                value.none(Char::isISOControl)
    }
}
