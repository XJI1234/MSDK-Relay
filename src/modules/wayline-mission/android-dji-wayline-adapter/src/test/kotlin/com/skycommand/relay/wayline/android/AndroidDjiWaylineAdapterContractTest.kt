package com.skycommand.relay.wayline.android

import com.skycommand.relay.wayline.executor.ControlCompletion
import com.skycommand.relay.wayline.phase.MissionExecutionSignal
import com.skycommand.relay.wayline.staging.MissionMetadata
import com.skycommand.relay.wayline.uploader.UploadCompletion
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.nio.file.Files

class AndroidDjiWaylineAdapterContractTest {
    @Test fun registersForDjiStateBeforeStartAndStopsDeliveringAfterClose() {
        val dji = FakeDji()
        val adapter = AndroidDjiWaylineAdapter(FakeFiles(), dji)
        val signals = mutableListOf<MissionExecutionSignal>()

        adapter.onSignal { signals += it }
        assertEquals(0, dji.executionListenerRegistrations)

        adapter.beginStartAttempt()
        adapter.upload(metadata("route.kmz"), singleWaylineKmz(), {}, UploadDone())
        requireNotNull(dji.uploadCompletion).succeed()
        adapter.start(ControlDone())
        assertEquals(1, dji.executionListenerRegistrations)
        assertEquals(listOf("listener", "start"), dji.calls)
        assertEquals("start", dji.command)
        dji.emit(DjiMissionExecutionState.ENTER_WAYLINE)
        requireNotNull(dji.controlCompletion).succeed()
        assertEquals(emptyList(), signals)
        adapter.confirmStartAttempt()
        dji.emit(DjiMissionExecutionState.ENTER_WAYLINE)

        assertEquals(listOf(MissionExecutionSignal.ENTER_WAYLINE), signals)
        adapter.close()
        dji.emit(DjiMissionExecutionState.EXECUTING)
        assertEquals(listOf(MissionExecutionSignal.ENTER_WAYLINE), signals)
    }

    @Test fun uploadsTemporaryFileAndControlsTheSuccessfulName() {
        val files=FakeFiles(); val dji=FakeDji(); val adapter=AndroidDjiWaylineAdapter(files,dji); val progress=mutableListOf<Int>(); val done=UploadDone()
        adapter.upload(metadata("one.kmz"), singleWaylineKmz(), { progress+=it }, done)
        assertEquals(files.paths.single(),dji.uploadPath); requireNotNull(dji.uploadCompletion).progress(45.4); requireNotNull(dji.uploadCompletion).succeed(); requireNotNull(dji.uploadCompletion).succeed()
        assertEquals(listOf(45),progress); assertEquals(listOf("success"),done.events); assertEquals(1,files.deletes)
        val start=ControlDone(); adapter.start(start); assertEquals("one.kmz",dji.controlName); requireNotNull(dji.controlCompletion).succeed(); assertEquals(listOf("success"),start.events)
    }

    @Test fun rejectsMultipleWaylinesBeforeWritingOrCallingDji() {
        val files = FakeFiles()
        val dji = FakeDji()
        val adapter = AndroidDjiWaylineAdapter(files, dji)
        val done = UploadDone()

        adapter.upload(metadata("two-routes.kmz"), wpmlMission(0, 1), {}, done)

        assertEquals(listOf("failure"), done.events)
        assertEquals(0, files.writes)
        assertTrue(dji.uploadCompletions.isEmpty())
    }

    @Test fun rejectsKmzWithoutAWaylineBeforeWritingOrCallingDji() {
        val files = FakeFiles()
        val dji = FakeDji()
        val adapter = AndroidDjiWaylineAdapter(files, dji)
        val done = UploadDone()

        adapter.upload(metadata("empty-route.kmz"), wpmlMission(), {}, done)

        assertEquals(listOf("failure"), done.events)
        assertEquals(0, files.writes)
        assertTrue(dji.uploadCompletions.isEmpty())
    }

    @Test fun rejectsWpmlWithADoctypeBeforeWritingOrCallingDji() {
        val files = FakeFiles()
        val dji = FakeDji()
        val adapter = AndroidDjiWaylineAdapter(files, dji)
        val done = UploadDone()

        adapter.upload(metadata("unsafe-xml.kmz"), wpmlMissionWithDoctype(), {}, done)

        assertEquals(listOf("failure"), done.events)
        assertEquals(0, files.writes)
        assertTrue(dji.uploadCompletions.isEmpty())
    }

    @Test fun doesNotDeliverExecutionStateUntilTheCurrentStartReceiptIsConfirmed() {
        val files = FakeFiles()
        val dji = FakeDji()
        val adapter = AndroidDjiWaylineAdapter(files, dji)
        val signals = mutableListOf<MissionExecutionSignal>()
        adapter.onSignal { signals += it }
        adapter.beginStartAttempt()

        adapter.upload(metadata("route.kmz"), singleWaylineKmz(), {}, UploadDone())
        requireNotNull(dji.uploadCompletion).succeed()
        adapter.start(ControlDone())
        dji.emit(DjiMissionExecutionState.EXECUTING)

        assertEquals(emptyList(), signals)

        requireNotNull(dji.controlCompletion).succeed()
        adapter.confirmStartAttempt()
        dji.emit(DjiMissionExecutionState.EXECUTING)

        assertEquals(listOf(MissionExecutionSignal.EXECUTING), signals)
    }

    @Test fun failedUploadDoesNotReplacePriorSuccessfulMission() {
        val dji=FakeDji(); val adapter=AndroidDjiWaylineAdapter(FakeFiles(),dji)
        adapter.upload(metadata("good.kmz"), singleWaylineKmz(), {}, UploadDone()); requireNotNull(dji.uploadCompletion).succeed()
        adapter.upload(metadata("bad.kmz"), singleWaylineKmz(), {}, UploadDone()); requireNotNull(dji.uploadCompletion).fail()
        adapter.stop(ControlDone()); assertEquals("good.kmz",dji.controlName)
    }

    @Test fun rejectsUnsafeNameAndDelegatesPauseResume() {
        val files=FakeFiles(); val dji=FakeDji(); val adapter=AndroidDjiWaylineAdapter(files,dji); val failed=UploadDone()
        adapter.upload(metadata("../bad.kmz"), byteArrayOf(1), {}, failed)
        assertEquals(listOf("failure"),failed.events); assertEquals(0,files.writes)
        val tooLong = UploadDone()
        adapter.upload(metadata("a".repeat(125) + ".kmz"), byteArrayOf(1), {}, tooLong)
        assertEquals(listOf("failure"),tooLong.events); assertEquals(0,files.writes)
        val missing=ControlDone(); adapter.start(missing); assertEquals(listOf("failure"),missing.events)
        adapter.pause(ControlDone()); assertEquals("pause",dji.command); adapter.resume(ControlDone()); assertEquals("resume",dji.command)
    }

    @Test fun retryKeepsPreviousUploadInputUntilItsOwnTerminalCallback() {
        val files=FakeFiles(); val dji=FakeDji(); val adapter=AndroidDjiWaylineAdapter(files,dji)
        adapter.upload(metadata("first.kmz"), singleWaylineKmz(), {}, UploadDone())
        val first = dji.uploadCompletions.single()
        adapter.upload(metadata("second.kmz"), singleWaylineKmz(), {}, UploadDone())

        assertEquals(0, files.deleteCounts[0])
        assertEquals(0, files.deleteCounts[1])
        first.succeed()
        assertEquals(1, files.deleteCounts[0])
        assertEquals(0, files.deleteCounts[1])

        dji.uploadCompletions.last().succeed()
        val start=ControlDone(); adapter.start(start)
        assertEquals("second.kmz", dji.controlName)
    }

    @Test fun closeInvalidatesSubmittedControlAndCleansEveryUploadInput() {
        val files=FakeFiles(); val dji=FakeDji(); val adapter=AndroidDjiWaylineAdapter(files,dji)
        adapter.upload(metadata("one.kmz"), singleWaylineKmz(), {}, UploadDone())
        adapter.upload(metadata("two.kmz"), singleWaylineKmz(), {}, UploadDone())
        dji.uploadCompletions.last().succeed()
        val control=ControlDone(); adapter.start(control)
        val late = requireNotNull(dji.controlCompletion)

        adapter.close()
        late.succeed()

        assertEquals(emptyList(), control.events)
        assertEquals(listOf(1, 1), files.deleteCounts)
        assertEquals(1, dji.closeCalls)
    }

    @Test fun physicalStorePreservesOriginalBasenameAndRemovesPartialWrites() {
        val root = Files.createTempDirectory("wayline-store").toFile()
        try {
            val stored = writeMissionFile(root, "mission.kmz", byteArrayOf(1, 2, 3))
            assertEquals("mission.kmz", java.io.File(stored.path).name)
            assertTrue(java.io.File(stored.path).isFile)
            stored.delete()
            assertFalse(requireNotNull(java.io.File(stored.path).parentFile).exists())

            val failedRoot = java.io.File(root, "failed")
            runCatching {
                writeMissionFile(failedRoot, "broken.kmz", byteArrayOf(1)) { _, _ -> error("write failed") }
            }
            assertFalse(failedRoot.walkTopDown().drop(1).any())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun metadata(name:String)=MissionMetadata(name,1,"a".repeat(64))
    private fun singleWaylineKmz(): ByteArray = wpmlMission(0)
    private fun wpmlMission(vararg ids: Int): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("wpmz/waylines.wpml"))
            val folders = ids.joinToString("") { id -> "<Folder><wpml:waylineId>$id</wpml:waylineId></Folder>" }
            zip.write(("<kml xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:wpml=\"http://www.dji.com/wpmz/1.0.6\"><Document>$folders</Document></kml>").encodeToByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }
    private fun wpmlMissionWithDoctype(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("wpmz/waylines.wpml"))
            zip.write(("<!DOCTYPE kml [<!ENTITY ignored \"ignored\">]>" +
                "<kml xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:wpml=\"http://www.dji.com/wpmz/1.0.6\">" +
                "<Document><Folder><wpml:waylineId>0</wpml:waylineId></Folder></Document></kml>").encodeToByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }
    private class UploadDone:UploadCompletion{val events=mutableListOf<String>();override fun succeed(){events+="success"};override fun fail(){events+="failure"}}
    private class ControlDone:ControlCompletion{val events=mutableListOf<String>();override fun succeed(){events+="success"};override fun fail(){events+="failure"}}
    private class FakeFiles:MissionFileStore{var writes=0;var deletes=0;val deleteCounts=mutableListOf<Int>();val paths=mutableListOf<String>()
        override fun write(fileName:String,content:ByteArray):StoredMissionFile{writes++;val index=deleteCounts.size;deleteCounts+=0;val path="C:/cache/$index/$fileName";paths+=path;return StoredMissionFile(path,fileName){deleteCounts[index]++;deletes++}}}
    private class FakeDji:DjiWaypointMissionApi{var uploadPath:String?=null;var uploadCompletion:DjiUploadCompletion?=null;val uploadCompletions=mutableListOf<DjiUploadCompletion>();var controlCompletion:DjiControlCompletion?=null;var controlName:String?=null;var command:String?=null;var closeCalls=0;var executionListenerRegistrations=0;val calls=mutableListOf<String>();private var executionListener:((DjiMissionExecutionState)->Unit)?=null
        override fun upload(path:String,completion:DjiUploadCompletion){uploadPath=path;uploadCompletion=completion;uploadCompletions+=completion}
        override fun start(name:String,completion:DjiControlCompletion){calls+="start";command="start";controlName=name;controlCompletion=completion}
        override fun pause(completion:DjiControlCompletion){command="pause";controlCompletion=completion}
        override fun resume(completion:DjiControlCompletion){command="resume";controlCompletion=completion}
        override fun stop(name:String,completion:DjiControlCompletion){command="stop";controlName=name;controlCompletion=completion}
        override fun onExecutionState(listener:(DjiMissionExecutionState)->Unit):DjiExecutionStateRegistration { calls+="listener"; executionListenerRegistrations++; executionListener=listener; return DjiExecutionStateRegistration { executionListener=null } }
        fun emit(state:DjiMissionExecutionState) { executionListener?.invoke(state) }
        override fun close(){closeCalls++} }
}
