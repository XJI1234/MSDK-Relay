package com.skycommand.relay.wayline.staging.android

import com.skycommand.relay.wayline.staging.MissionMetadata
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class AndroidMissionStagingStorageTest {
    @Test fun atomicallyReplacesAndReadsOnlyTheCurrentMission() {
        val storage=AndroidMissionStagingStorage(createTempDirectory().toFile()); val first=metadata("first.kmz")
        storage.beginTemporary(first);storage.append(byteArrayOf(1));storage.append(byteArrayOf(2));storage.flush();storage.replaceCurrent()
        assertContentEquals(byteArrayOf(1,2),storage.read(first))
        assertFailsWith<IllegalStateException>{storage.read(metadata("other.kmz"))}
    }

    @Test fun cancellingTemporaryWritePreservesCurrentMission() {
        val storage=AndroidMissionStagingStorage(createTempDirectory().toFile());val first=metadata("first.kmz")
        storage.beginTemporary(first);storage.append(byteArrayOf(1));storage.replaceCurrent()
        storage.beginTemporary(metadata("second.kmz"));storage.append(byteArrayOf(2));storage.deleteTemporary()
        assertContentEquals(byteArrayOf(1),storage.read(first));assertFailsWith<IllegalStateException>{storage.append(byteArrayOf(3))}
    }

    private fun metadata(name:String)=MissionMetadata(name,1,"a".repeat(64))
}
