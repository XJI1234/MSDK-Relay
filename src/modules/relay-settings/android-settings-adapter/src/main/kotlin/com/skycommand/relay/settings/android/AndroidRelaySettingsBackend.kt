package com.skycommand.relay.settings.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.skycommand.relay.settings.store.RelaySettingsBackend
import com.skycommand.relay.settings.store.RelaySettingsRecord

class AndroidRelaySettingsBackend private constructor(
    private val preferences: SharedPreferences,
) : RelaySettingsBackend {
    @SuppressLint("ApplySharedPref")
    @Synchronized
    override fun update(change: (RelaySettingsRecord?) -> RelaySettingsRecord?): RelaySettingsRecord? {
        val current = try {
            readRecord()
        } catch (_: Exception) {
            throw AndroidSettingsStorageException()
        }
        val next = try {
            change(current)
        } catch (_: Exception) {
            throw AndroidSettingsStorageException()
        }
        val committed = try {
            preferences.edit().apply {
                if (next == null) {
                    clear()
                } else {
                    val encoded = RelaySettingsRecordCodec.encode(next)
                    putBoolean(KEY_PRESENT, true)
                    putInt(KEY_SCHEMA_VERSION, encoded.schemaVersion)
                    putNullableString(KEY_ENDPOINT, encoded.endpoint)
                    putNullableString(KEY_DEVICE_ID, encoded.deviceId)
                }
            }.commit()
        } catch (_: Exception) {
            false
        }
        if (!committed) throw AndroidSettingsStorageException()
        return next
    }

    private fun readRecord(): RelaySettingsRecord? {
        return RelaySettingsRecordCodec.decode(
            present = preferences.getBoolean(KEY_PRESENT, false),
            schemaVersion = preferences.getInt(KEY_SCHEMA_VERSION, 0),
            endpoint = preferences.getString(KEY_ENDPOINT, null),
            deviceId = preferences.getString(KEY_DEVICE_ID, null),
        )
    }

    private fun SharedPreferences.Editor.putNullableString(key: String, value: String?) {
        if (value == null) remove(key) else putString(key, value)
    }

    companion object {
        private const val PREFERENCES_NAME = "skycommand.relay.settings"
        private const val KEY_PRESENT = "present"
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_DEVICE_ID = "device_id"

        fun create(context: Context): AndroidRelaySettingsBackend = AndroidRelaySettingsBackend(
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
        )
    }
}

class AndroidSettingsStorageException : IllegalStateException("Relay settings storage is unavailable")

internal data class PersistedRelaySettingsRecord(
    val schemaVersion: Int,
    val endpoint: String?,
    val deviceId: String?,
)

internal object RelaySettingsRecordCodec {
    fun encode(record: RelaySettingsRecord): PersistedRelaySettingsRecord = PersistedRelaySettingsRecord(
        schemaVersion = record.schemaVersion,
        endpoint = record.endpoint,
        deviceId = record.deviceId,
    )

    fun decode(
        present: Boolean,
        schemaVersion: Int,
        endpoint: String?,
        deviceId: String?,
    ): RelaySettingsRecord? = if (present) {
        RelaySettingsRecord(schemaVersion, endpoint, deviceId)
    } else {
        null
    }
}
