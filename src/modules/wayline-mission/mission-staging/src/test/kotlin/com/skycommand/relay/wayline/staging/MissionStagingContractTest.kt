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

    @Test
    fun rejectsAFileNameThatCannotBeCarriedByTheRelayProtocol() {
        val staging = MissionStaging.create(MemoryStorage())

        val result = staging.begin(MissionMetadata("a".repeat(125) + ".kmz", 1, sha256(byteArrayOf(1))))

        assertEquals(StagingRejection.INVALID_METADATA, assertIs<StagingRequestResult.Rejected>(result).reason)
    }

    @Test
    fun cleansUpTemporaryStorageWhenBeginningTheTransferFails() {
        val storage = MemoryStorage().apply { throwOnBegin = true }
        val staging = MissionStaging.create(storage)

        val result = staging.begin(MissionMetadata("survey.kmz", 1, sha256(byteArrayOf(1))))

        assertEquals(StagingRejection.STORAGE_FAILURE, assertIs<StagingRequestResult.Rejected>(result).reason)
        assertEquals(1, storage.deleteCalls)
        assertEquals(null, staging.current())
    }

    @Test
    fun cancellationCleansTemporaryDataAndPreservesTheCurrentMission() {
        val storage = MemoryStorage().apply { current = "old".encodeToByteArray() }
        val staging = MissionStaging.create(storage)

        staging.begin(MissionMetadata("new.kmz", 1, sha256(byteArrayOf(1))))
        staging.write(byteArrayOf(1))

        assertEquals(StagingCancelResult.Cancelled, staging.cancel())
        assertEquals("old".encodeToByteArray().toList(), storage.current.toList())
        assertEquals(ByteArray(0).toList(), storage.temporary.toList())
        assertEquals(StagingCancelResult.AlreadyFinished, staging.cancel())
    }

    @Test
    fun atomicReplacementFailureKeepsThePreviousMissionAndCleansTemporaryData() {
        val storage = MemoryStorage().apply {
            current = "old".encodeToByteArray()
            throwOnReplace = true
        }
        val staging = MissionStaging.create(storage)
        val bytes = "new".encodeToByteArray()

        staging.begin(MissionMetadata("new.kmz", bytes.size.toLong(), sha256(bytes)))
        staging.write(bytes)

        assertEquals(StagingRejection.STORAGE_FAILURE, assertIs<StagingCompleteResult.Rejected>(staging.complete()).reason)
        assertEquals("old".encodeToByteArray().toList(), storage.current.toList())
        assertEquals(ByteArray(0).toList(), storage.temporary.toList())
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private class MemoryStorage : StagingStorage {
        var current = ByteArray(0)
        var temporary = ByteArray(0)
        var replaceCalls = 0
        var deleteCalls = 0
        var throwOnBegin = false
        var throwOnReplace = false
        override fun beginTemporary(metadata: MissionMetadata) {
            temporary = ByteArray(0)
            if (throwOnBegin) error("storage failure")
        }
        override fun append(bytes: ByteArray) { temporary += bytes }
        override fun flush() = Unit
        override fun replaceCurrent() {
            if (throwOnReplace) error("replacement failure")
            current = temporary
            replaceCalls += 1
        }
        override fun deleteTemporary() {
            deleteCalls += 1
            temporary = ByteArray(0)
        }
    }
}
