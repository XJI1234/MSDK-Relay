package com.skycommand.relay.protocol

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MissionTransferStateTest {

    @Test
    fun acceptsCompleteMissionTransfer() {
        val bytes = byteArrayOf(1, 2, 3)
        val id = "mission-1"
        val state = MissionTransferState()
        state.begin(MissionBeginFrame(id, "route.kmz", bytes.size.toLong(), sha256(bytes)))
        state.append(MissionChunkFrame(id, bytes))

        val result = state.complete(MissionCompleteFrame(id))

        assertEquals(sha256(bytes), assertIs<Accepted<CompletedMission>>(result).value.sha256)
    }

    @Test
    fun rejectsChunkBeforeBegin() {
        val result = MissionTransferState().append(MissionChunkFrame("mission-1", byteArrayOf(1)))

        assertEquals(ProtocolErrorCode.TRANSFER_NOT_ACTIVE, assertIs<Rejected>(result).error.code)
    }

    @Test
    fun rejectsWrongIdAndDeclaredSizeOverflow() {
        val state = MissionTransferState()
        state.begin(MissionBeginFrame("mission-1", "route.kmz", 1, sha256(byteArrayOf(1))))

        val wrongId = state.append(MissionChunkFrame("mission-2", byteArrayOf(1)))
        val overflow = state.append(MissionChunkFrame("mission-1", byteArrayOf(1, 2)))

        assertEquals(ProtocolErrorCode.TRANSFER_NOT_ACTIVE, assertIs<Rejected>(wrongId).error.code)
        assertEquals(ProtocolErrorCode.TRANSFER_SIZE_MISMATCH, assertIs<Rejected>(overflow).error.code)
    }

    @Test
    fun rejectsIncompleteAndChecksumMismatch() {
        val incomplete = MissionTransferState()
        incomplete.begin(MissionBeginFrame("mission-1", "route.kmz", 2, sha256(byteArrayOf(1, 2))))
        incomplete.append(MissionChunkFrame("mission-1", byteArrayOf(1)))
        val incompleteResult = incomplete.complete(MissionCompleteFrame("mission-1"))

        val mismatch = MissionTransferState()
        mismatch.begin(MissionBeginFrame("mission-2", "route.kmz", 1, sha256(byteArrayOf(2))))
        mismatch.append(MissionChunkFrame("mission-2", byteArrayOf(1)))
        val mismatchResult = mismatch.complete(MissionCompleteFrame("mission-2"))

        assertEquals(ProtocolErrorCode.TRANSFER_SIZE_MISMATCH, assertIs<Rejected>(incompleteResult).error.code)
        assertEquals(ProtocolErrorCode.TRANSFER_CHECKSUM_MISMATCH, assertIs<Rejected>(mismatchResult).error.code)
    }

    @Test
    fun newMissionSupersedesDifferentActiveMission() {
        val state = MissionTransferState()
        state.begin(MissionBeginFrame("mission-1", "one.kmz", 1, sha256(byteArrayOf(1))))

        val result = state.begin(MissionBeginFrame("mission-2", "two.kmz", 1, sha256(byteArrayOf(2))))

        assertEquals("mission-1", assertIs<Accepted<MissionTransferStarted>>(result).value.supersededId)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
