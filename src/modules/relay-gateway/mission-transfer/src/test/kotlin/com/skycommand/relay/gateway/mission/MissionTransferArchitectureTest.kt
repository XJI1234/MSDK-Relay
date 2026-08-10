package com.skycommand.relay.gateway.mission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MissionTransferArchitectureTest {

    @Test
    fun exposesOnlyTheContractualRejectionKinds() {
        assertEquals(
            setOf(
                TransferRejectionKind.TRANSFER_NOT_ACTIVE,
                TransferRejectionKind.TRANSFER_ALREADY_ACTIVE,
                TransferRejectionKind.TRANSFER_SUPERSEDED,
                TransferRejectionKind.TRANSFER_SIZE_MISMATCH,
                TransferRejectionKind.TRANSFER_CHECKSUM_MISMATCH,
                TransferRejectionKind.TRANSFER_FAILED,
            ),
            TransferRejectionKind.entries.toSet(),
        )
    }

    @Test
    fun exposesTheSessionCleanupSeamWithoutOwningTransportOrPaths() {
        assertTrue(com.skycommand.relay.gateway.session.MissionSessionCleanup::class.java.isAssignableFrom(MissionTransfer::class.java))
        assertEquals(
            emptySet(),
            MissionTransfer::class.java.declaredFields
                .map { it.type.name }
                .filter { it.contains("Transport") || it.contains("WebSocket") || it.contains("Path") || it.contains("File") }
                .toSet(),
        )
    }
}
