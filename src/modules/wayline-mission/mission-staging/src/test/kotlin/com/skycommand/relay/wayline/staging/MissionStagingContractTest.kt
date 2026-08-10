package com.skycommand.relay.wayline.staging

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MissionStagingContractTest {
    @Test
    fun writesOnlyTheExpectedBytesAndAtomicallyPublishesOnMatchingDigest() {
        val storage = MemoryStorage()
        val staging = MissionStaging.create(storage)
        val bytes = "kmz-content".encodeToByteArray()

        assertIs<StagingRequestResult.Accepted>(staging.begin(MissionMetadata("survey.kmz", bytes.size.toLong(), sha256(bytes))))
        assertIs<StagingRequestResult.Accepted>(staging.write(bytes))
        val result = assertIs<StagingCompleteResult.Staged>(staging.complete())

        assertEquals("survey.kmz", result.metadata.fileName)
        assertEquals(bytes.toList(), storage.current.toList())
        assertEquals(1, storage.replaceCalls)
    }

    @Test
    fun rejectsDigestMismatchAndPreservesThePreviousCurrentFile() {
        val storage = MemoryStorage().apply { current = "old".encodeToByteArray() }
        val staging = MissionStaging.create(storage)
        val bytes = "new".encodeToByteArray()

        staging.begin(MissionMetadata("new.kmz", bytes.size.toLong(), "00".repeat(32)))
        staging.write(bytes)

        assertIs<StagingCompleteResult.Rejected>(staging.complete())
        assertEquals("old".encodeToByteArray().toList(), storage.current.toList())
        assertEquals(0, storage.replaceCalls)
    }

    @Test
    fun rejectsUnsafeMetadataAndCleansUpAnOversizedWrite() {
        val storage = MemoryStorage()
        val staging = MissionStaging.create(storage)

        assertIs<StagingRequestResult.Rejected>(
            staging.begin(MissionMetadata("../unsafe.kmz", 1, "00".repeat(32))),
        )
        staging.begin(MissionMetadata("valid.kmz", 1, sha256(byteArrayOf(1))))
        assertIs<StagingRequestResult.Rejected>(staging.write(byteArrayOf(1, 2)))

        assertEquals(ByteArray(0).toList(), storage.temporary.toList())
        assertIs<StagingRequestResult.Rejected>(staging.write(byteArrayOf(1)))
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private class MemoryStorage : StagingStorage {
        var current = ByteArray(0)
        var temporary = ByteArray(0)
        var replaceCalls = 0
        override fun beginTemporary(metadata: MissionMetadata) { temporary = ByteArray(0) }
        override fun append(bytes: ByteArray) { temporary += bytes }
        override fun flush() = Unit
        override fun replaceCurrent() { current = temporary; replaceCalls += 1 }
        override fun deleteTemporary() { temporary = ByteArray(0) }
    }
}
