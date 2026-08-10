package com.skycommand.relay.settings.android

import com.skycommand.relay.settings.store.RelaySettingsRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RelaySettingsRecordCodecTest {
    @Test fun preservesEveryOpaqueRecordField() {
        val record = RelaySettingsRecord(1, "wss://desktop/relay?token=private", "phone-1")

        val encoded = RelaySettingsRecordCodec.encode(record)
        val decoded = RelaySettingsRecordCodec.decode(true, encoded.schemaVersion, encoded.endpoint, encoded.deviceId)

        assertEquals(record, decoded)
    }

    @Test fun decodesAnAbsentRecordAsNull() {
        assertNull(RelaySettingsRecordCodec.decode(false, 99, "opaque", "opaque"))
    }
}
