package com.skycommand.relay.wayline.android

import com.skycommand.relay.wayline.executor.ControlCompletion
import com.skycommand.relay.wayline.executor.MissionControlFailure
import com.skycommand.relay.wayline.phase.MissionExecutionObservation
import com.skycommand.relay.wayline.phase.MissionExecutionRawState
import com.skycommand.relay.wayline.uploader.MissionUploadFailure
import com.skycommand.relay.wayline.phase.MissionExecutionSignal
import com.skycommand.relay.wayline.phase.WaylineLiveActionPhase
import com.skycommand.relay.wayline.phase.WaylineLiveProgress
import com.skycommand.relay.wayline.staging.MissionMetadata
import com.skycommand.relay.wayline.uploader.MissionUploadPreparation
import com.skycommand.relay.wayline.uploader.UploadCompletion
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.nio.file.Files

private fun AndroidDjiWaylineAdapter.upload(
    metadata: MissionMetadata,
    content: ByteArray,
    progress: (Int) -> Unit,
    completion: UploadCompletion,
) {
    when (val preparation = prepare(metadata, content.inputStream())) {
        is MissionUploadPreparation.Prepared -> preparation.upload.start(progress, completion)
        MissionUploadPreparation.Rejected -> completion.fail()
    }
}

class AndroidDjiWaylineAdapterContractTest {
    @Test fun preparesThePrivateUploadFileBeforeCallingDji() {
        val files = FakeFiles()
        val dji = FakeDji()
        val adapter = AndroidDjiWaylineAdapter(files, dji)

        val prepared = kotlin.test.assertIs<MissionUploadPreparation.Prepared>(
            adapter.prepare(metadata("route.kmz"), singleWaylineKmz().inputStream()),
        )

        assertEquals(1, files.writes)
        assertTrue(dji.uploadCompletions.isEmpty())

        prepared.upload.start({}, UploadDone())

        assertEquals(1, dji.uploadCompletions.size)
    }

    @Test fun preservesTheRawDjiExecutionStateAlongsideItsNormalizedTaskSignal() {
        val dji = FakeDji()
        val adapter = AndroidDjiWaylineAdapter(FakeFiles(), dji)
        val observations = mutableListOf<MissionExecutionObservation>()

        adapter.onObservation { observations += it }
        adapter.beginStartAttempt()
        adapter.upload(metadata("route.kmz"), singleWaylineKmz(), {}, UploadDone())
        requireNotNull(dji.uploadCompletion).succeed()
        adapter.start(ControlDone())
        requireNotNull(dji.controlCompletion).succeed()
        adapter.confirmStartAttempt()
        dji.emit(DjiMissionExecutionState.RETURN_TO_START_POINT)

        assertEquals(
            listOf(MissionExecutionObservation(MissionExecutionSignal.EXECUTING, MissionExecutionRawState.RETURN_TO_START_POINT)),
            observations,
        )
    }

    @Test fun deliversLiveWaylineProgressAfterTheCurrentStartIsConfirmed() {
        val dji = FakeDji()
        val adapter = AndroidDjiWaylineAdapter(FakeFiles(), dji)
        val progress = mutableListOf<WaylineLiveProgress>()

        adapter.onLiveProgress { progress += it }
        adapter.beginStartAttempt()
        adapter.upload(metadata("route.kmz"), singleWaylineKmz(), {}, UploadDone())
        requireNotNull(dji.uploadCompletion).succeed()
        adapter.start(ControlDone())
        dji.emitExecutingInfo(DjiWaylineExecutingInfo("默认", 0, 46))
        assertEquals(emptyList(), progress)
        requireNotNull(dji.controlCompletion).succeed()
        adapter.confirmStartAttempt()

        assertEquals(
            listOf(WaylineLiveProgress(executingMissionFileName = "默认", waylineId = 0, currentWaypointIndex = 46)),
            progress,
        )
        dji.emitWaypointAction(DjiWaypointActionEvent(2, 3, WaylineLiveActionPhase.START))
        assertEquals(2, progress.size)
        assertEquals(2, progress.last().waypointActionGroup)
        assertEquals(3, progress.last().waypointActionId)
        assertEquals(WaylineLiveActionPhase.START, progress.last().waypointActionPhase)
        adapter.close()
        dji.emitExecutingInfo(DjiWaylineExecutingInfo("默认", 0, 47))
        assertEquals(2, progress.size)
    }

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

    @Test fun acceptsAWaylineDocumentAfterAnUnrelatedKmzEntry() {
        val files = FakeFiles()
        val dji = FakeDji()
        val adapter = AndroidDjiWaylineAdapter(files, dji)

        adapter.upload(metadata("route.kmz"), wpmlMissionWithLeadingTemplate(0), {}, UploadDone())

        assertEquals(1, files.writes)
        assertEquals(1, dji.uploadCompletions.size)
    }

    @Test fun acceptsAStagedWaylineDocumentAfterAnUnrelatedKmzEntry() {
        val root = Files.createTempDirectory("wayline-kmz-guard").toFile()
        try {
            val kmz = File(root, "route.kmz")
            kmz.writeBytes(wpmlMissionWithLeadingTemplate(0))

            assertTrue(SingleWaylineKmzGuard.inspect(kmz).accepted)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun forwardsDjiControlFailureWithoutDroppingItsNormalizedDetails() {
        val dji = FakeDji()
        val adapter = AndroidDjiWaylineAdapter(FakeFiles(), dji)
        adapter.upload(metadata("route.kmz"), singleWaylineKmz(), {}, UploadDone())
        requireNotNull(dji.uploadCompletion).succeed()

        val completion = ControlDone()
        adapter.start(completion)
        val failure = MissionControlFailure.fromDjiError("WAYPOINT_MISSION_BUSY", "The mission manager is busy")
        requireNotNull(dji.controlCompletion).fail(failure)

        assertEquals(listOf("failure"), completion.events)
        assertEquals(failure, completion.failure)
    }

    @Test fun forwardsDjiUploadFailureWithoutDroppingItsNormalizedDetails() {
        val dji = FakeDji()
        val adapter = AndroidDjiWaylineAdapter(FakeFiles(), dji)
        val completion = UploadDone()

        adapter.upload(metadata("route.kmz"), singleWaylineKmz(), {}, completion)
        val failure = MissionUploadFailure.fromDjiError("WAYPOINT_MISSION_BUSY", "The mission manager is busy")
        requireNotNull(dji.uploadCompletion).fail(failure)

        assertEquals(listOf("failure"), completion.events)
        assertEquals(failure, completion.failure)
    }

    @Test fun recordsDjiUploadCallbackRejectionWithTheSameDetailsItForwards() {
        val diagnostics = mutableListOf<WaylineAdapterDiagnostic>()
        val dji = FakeDji()
        val adapter = AndroidDjiWaylineAdapter(FakeFiles(), dji, WaylineAdapterDiagnosticSink { diagnostics += it })
        val completion = UploadDone()

        adapter.upload(metadata("route.kmz"), singleWaylineKmz(), {}, completion)
        val failure = MissionUploadFailure.fromDjiError("WAYPOINT_MISSION_BUSY", "The mission manager is busy")
        requireNotNull(dji.uploadCompletion).fail(failure)

        assertEquals(listOf("failure"), completion.events)
        assertEquals(failure, completion.failure)
        assertEquals(1, diagnostics.size)
        assertEquals(WaylineAdapterDiagnosticKind.UPLOAD_DJI_REJECTED, diagnostics.single().kind)
        assertEquals("WAYPOINT_MISSION_BUSY", diagnostics.single().djiErrorCode)
        assertEquals("The mission manager is busy", diagnostics.single().djiErrorDescription)
    }

    @Test fun propagatesSynchronousDjiUploadInvocationFailureWithoutFabricatingADjiCallback() {
        val diagnostics = mutableListOf<WaylineAdapterDiagnostic>()
        val dji = FakeDji().apply { uploadFailure = IllegalStateException("Waypoint manager is unavailable") }
        val files = FakeFiles()
        val adapter = AndroidDjiWaylineAdapter(files, dji, WaylineAdapterDiagnosticSink { diagnostics += it })
        val completion = UploadDone()

        assertFailsWith<IllegalStateException> {
            adapter.upload(metadata("route.kmz"), singleWaylineKmz(), {}, completion)
        }

        assertEquals(emptyList(), completion.events)
        assertNull(completion.failure)
        assertEquals(1, files.writes)
        assertEquals(0, files.deletes)
        assertEquals(
            listOf(WaylineAdapterDiagnosticKind.UPLOAD_DJI_INVOCATION_FAILED),
            diagnostics.map(WaylineAdapterDiagnostic::kind),
        )
        assertEquals("IllegalStateException", diagnostics.single().exceptionType)

        adapter.close()
        assertEquals(1, files.deletes)
    }

    @Test fun propagatesSynchronousDjiControlInvocationFailureWithoutFabricatingADjiCallback() {
        val diagnostics = mutableListOf<WaylineAdapterDiagnostic>()
        val dji = FakeDji().apply { controlFailure = IllegalStateException("Waypoint manager is unavailable") }
        val adapter = AndroidDjiWaylineAdapter(FakeFiles(), dji, WaylineAdapterDiagnosticSink { diagnostics += it })
        adapter.upload(metadata("route.kmz"), singleWaylineKmz(), {}, UploadDone())
        requireNotNull(dji.uploadCompletion).succeed()
        val completion = ControlDone()

        assertFailsWith<IllegalStateException> {
            adapter.start(completion)
        }

        assertEquals(emptyList(), completion.events)
        assertEquals(
            listOf(WaylineAdapterDiagnosticKind.CONTROL_DJI_INVOCATION_FAILED),
            diagnostics.map(WaylineAdapterDiagnostic::kind),
        )
        assertEquals("IllegalStateException", diagnostics.single().exceptionType)
    }

    @Test fun recordsInputRejectionWithoutCallingDjiOrWritingTheUploadFile() {
        val diagnostics = mutableListOf<WaylineAdapterDiagnostic>()
        val files = FakeFiles()
        val dji = FakeDji()
        val adapter = AndroidDjiWaylineAdapter(files, dji, WaylineAdapterDiagnosticSink { diagnostics += it })

        adapter.upload(metadata("../unsafe.kmz"), singleWaylineKmz(), {}, UploadDone())

        assertEquals(0, files.writes)
        assertTrue(dji.uploadCompletions.isEmpty())
        assertEquals(
            listOf(WaylineAdapterDiagnosticKind.UPLOAD_INPUT_REJECTED),
            diagnostics.map(WaylineAdapterDiagnostic::kind),
        )
        assertEquals(WaylineUploadInputRejection.UNSAFE_FILE_NAME, diagnostics.single().inputRejection)
    }

    @Test fun recordsTheKmzGuardRejectionWithoutCallingDjiAndRemovesTheStagedFile() {
        val diagnostics = mutableListOf<WaylineAdapterDiagnostic>()
        val files = FakeFiles()
        val dji = FakeDji()
        val adapter = AndroidDjiWaylineAdapter(files, dji, WaylineAdapterDiagnosticSink { diagnostics += it })

        adapter.upload(metadata("route.kmz"), byteArrayOf(1), {}, UploadDone())

        assertEquals(1, files.writes)
        assertEquals(1, files.deletes)
        assertTrue(dji.uploadCompletions.isEmpty())
        assertEquals(
            listOf(WaylineAdapterDiagnosticKind.UPLOAD_INPUT_REJECTED),
            diagnostics.map(WaylineAdapterDiagnostic::kind),
        )
        assertEquals(WaylineUploadInputRejection.KMZ_GUARD_REJECTED, diagnostics.single().inputRejection)
    }

    @Test fun identifiesAMissingWaylinesDocumentWithoutTreatingItAsADjiFailure() {
        val emptyKmz = Files.createTempFile("empty-wayline", ".kmz").toFile()
        try {
            emptyKmz.writeBytes(kmzWithoutWaylinesWpml())
            assertEquals(
                SingleWaylineKmzRejection.MISSING_OR_DUPLICATE_WAYLINES_WPML,
                SingleWaylineKmzGuard.inspect(emptyKmz).rejection,
            )
        } finally {
            emptyKmz.delete()
        }
    }

    @Test fun identifiesAnUnreadableKmzWithoutTreatingItAsADjiFailure() {
        val invalidKmz = Files.createTempFile("invalid-wayline", ".kmz").toFile()
        try {
            invalidKmz.writeBytes(byteArrayOf(1))
            assertEquals(
                SingleWaylineKmzRejection.UNREADABLE_OR_OVERSIZED_KMZ,
                SingleWaylineKmzGuard.inspect(invalidKmz).rejection,
            )
        } finally {
            invalidKmz.delete()
        }
    }

    @Test fun recordsCacheWriteFailureWithoutCallingDji() {
        val diagnostics = mutableListOf<WaylineAdapterDiagnostic>()
        val files = FakeFiles().apply { writeFailure = java.io.IOException("Cache volume is unavailable") }
        val dji = FakeDji()
        val completion = UploadDone()
        val adapter = AndroidDjiWaylineAdapter(files, dji, WaylineAdapterDiagnosticSink { diagnostics += it })

        adapter.upload(metadata("route.kmz"), singleWaylineKmz(), {}, completion)

        assertEquals(listOf("failure"), completion.events)
        assertTrue(dji.uploadCompletions.isEmpty())
        assertEquals(
            listOf(WaylineAdapterDiagnosticKind.UPLOAD_FILE_WRITE_FAILED),
            diagnostics.map(WaylineAdapterDiagnostic::kind),
        )
        assertEquals("IOException", diagnostics.single().exceptionType)
    }

    @Test fun rejectsMultipleWaylinesBeforeCallingDjiAndRemovesTheStagedFile() {
        val files = FakeFiles()
        val dji = FakeDji()
        val adapter = AndroidDjiWaylineAdapter(files, dji)
        val done = UploadDone()

        adapter.upload(metadata("two-routes.kmz"), wpmlMission(0, 1), {}, done)

        assertEquals(listOf("failure"), done.events)
        assertEquals(1, files.writes)
        assertEquals(1, files.deletes)
        assertTrue(dji.uploadCompletions.isEmpty())
    }

    @Test fun rejectsKmzWithoutAWaylineBeforeCallingDjiAndRemovesTheStagedFile() {
        val files = FakeFiles()
        val dji = FakeDji()
        val adapter = AndroidDjiWaylineAdapter(files, dji)
        val done = UploadDone()

        adapter.upload(metadata("empty-route.kmz"), wpmlMission(), {}, done)

        assertEquals(listOf("failure"), done.events)
        assertEquals(1, files.writes)
        assertEquals(1, files.deletes)
        assertTrue(dji.uploadCompletions.isEmpty())
    }

    @Test fun rejectsWpmlWithADoctypeBeforeCallingDjiAndRemovesTheStagedFile() {
        val files = FakeFiles()
        val dji = FakeDji()
        val adapter = AndroidDjiWaylineAdapter(files, dji)
        val done = UploadDone()

        adapter.upload(metadata("unsafe-xml.kmz"), wpmlMissionWithDoctype(), {}, done)

        assertEquals(listOf("failure"), done.events)
        assertEquals(1, files.writes)
        assertEquals(1, files.deletes)
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

        assertEquals(listOf(MissionExecutionSignal.EXECUTING), signals)
    }

    @Test fun dropsStatesFromBeforeThisStartCallAndReplaysOnlyTheArmedAttempt() {
        val dji = FakeDji()
        val adapter = AndroidDjiWaylineAdapter(FakeFiles(), dji)
        val observations = mutableListOf<MissionExecutionObservation>()
        adapter.onObservation { observations += it }
        adapter.beginStartAttempt()
        adapter.upload(metadata("route.kmz"), singleWaylineKmz(), {}, UploadDone())
        requireNotNull(dji.uploadCompletion).succeed()

        adapter.start(ControlDone())
        adapter.beginStartAttempt()
        dji.emit(DjiMissionExecutionState.EXECUTING)
        adapter.start(ControlDone())
        dji.emit(DjiMissionExecutionState.ENTER_WAYLINE)
        dji.emit(DjiMissionExecutionState.EXECUTING)
        requireNotNull(dji.controlCompletion).succeed()
        adapter.confirmStartAttempt()

        assertEquals(
            listOf(
                MissionExecutionObservation(MissionExecutionSignal.ENTER_WAYLINE, MissionExecutionRawState.ENTER_WAYLINE),
                MissionExecutionObservation(MissionExecutionSignal.EXECUTING, MissionExecutionRawState.EXECUTING),
            ),
            observations,
        )
    }

    @Test fun discardsArmedStartStatesWhenTheAttemptIsInvalidated() {
        val dji = FakeDji()
        val adapter = AndroidDjiWaylineAdapter(FakeFiles(), dji)
        val signals = mutableListOf<MissionExecutionSignal>()
        adapter.onSignal { signals += it }
        adapter.beginStartAttempt()
        adapter.upload(metadata("route.kmz"), singleWaylineKmz(), {}, UploadDone())
        requireNotNull(dji.uploadCompletion).succeed()
        adapter.start(ControlDone())
        dji.emit(DjiMissionExecutionState.ENTER_WAYLINE)
        adapter.invalidateStartAttempt()
        adapter.confirmStartAttempt()

        assertEquals(emptyList(), signals)
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
            val stored = writeMissionFile(root, "mission.kmz", byteArrayOf(1, 2, 3).inputStream())
            assertEquals("mission.kmz", java.io.File(stored.path).name)
            assertTrue(java.io.File(stored.path).isFile)
            stored.delete()
            assertFalse(requireNotNull(java.io.File(stored.path).parentFile).exists())

            val failedRoot = java.io.File(root, "failed")
            runCatching {
                writeMissionFile(failedRoot, "broken.kmz", byteArrayOf(1).inputStream()) { _, _ -> error("write failed") }
            }
            assertFalse(failedRoot.walkTopDown().drop(1).any())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun metadata(name:String)=MissionMetadata(name,1,"a".repeat(64))

    private fun singleWaylineKmz(): ByteArray = wpmlMission(0)

    private fun wpmlMissionWithLeadingTemplate(vararg ids: Int): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("wpmz/template.kml"))
            zip.write("<kml/>".encodeToByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("wpmz/waylines.wpml"))
            val folders = ids.joinToString("") { id -> "<Folder><wpml:waylineId>$id</wpml:waylineId></Folder>" }
            zip.write(("<kml xmlns=\"http://www.opengis.net/kml/2.2\" xmlns:wpml=\"http://www.dji.com/wpmz/1.0.2\"><Document>$folders</Document></kml>").encodeToByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    @Test fun physicalStoreCopiesMissionInputsInBoundedChunks() {
        val root = Files.createTempDirectory("wayline-stream-store").toFile()
        val source = MaximumReadSizeInputStream((64 * 1024 * 3 + 1).toLong())
        try {
            val stored = writeMissionFile(root, "mission.kmz", source)

            assertTrue(source.maximumRequestedBytes <= 64 * 1024)
            assertEquals(64 * 1024 * 3 + 1L, File(stored.path).length())
            stored.delete()
        } finally {
            root.deleteRecursively()
        }
    }

    private fun kmzWithoutWaylinesWpml(): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("wpmz/template.kml"))
            zip.write("<kml/>".encodeToByteArray())
            zip.closeEntry()
        }
        return output.toByteArray()
    }

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
    private class UploadDone:UploadCompletion{
        val events=mutableListOf<String>()
        var failure: MissionUploadFailure? = null
        override fun succeed(){events+="success"}
        override fun fail(){fail(null)}
        override fun fail(value: MissionUploadFailure?){failure=value;events+="failure"}
    }
    private class ControlDone:ControlCompletion{
        val events=mutableListOf<String>()
        var failure: MissionControlFailure? = null
        override fun succeed(){events+="success"}
        override fun fail(){fail(null)}
        override fun fail(value: MissionControlFailure?){failure=value;events+="failure"}
    }
    private class FakeFiles:MissionFileStore{var writes=0;var deletes=0;var writeFailure:Throwable?=null;val deleteCounts=mutableListOf<Int>();val paths=mutableListOf<String>();private val root=Files.createTempDirectory("wayline-fake-files").toFile().apply{deleteOnExit()}
        override fun write(fileName:String,content:java.io.InputStream):StoredMissionFile{writes++;writeFailure?.let { throw it };val index=deleteCounts.size;deleteCounts+=0;val directory=File(root,index.toString()).apply{check(mkdirs())};val file=File(directory,fileName).apply{outputStream().use { content.copyTo(it) }};val path=file.absolutePath;paths+=path;return StoredMissionFile(path,fileName){deleteCounts[index]++;deletes++;directory.deleteRecursively()}}}
    private class FakeDji:DjiWaypointMissionApi{var uploadPath:String?=null;var uploadCompletion:DjiUploadCompletion?=null;val uploadCompletions=mutableListOf<DjiUploadCompletion>();var uploadFailure:Throwable?=null;var controlFailure:Throwable?=null;var controlCompletion:DjiControlCompletion?=null;var controlName:String?=null;var command:String?=null;var closeCalls=0;var executionListenerRegistrations=0;val calls=mutableListOf<String>();private var executionListener:((DjiMissionExecutionState)->Unit)?=null;private var executingInfoListener:((DjiWaylineExecutingInfo)->Unit)?=null;private var executingInterruptListener:((String?,String?)->Unit)?=null;private var waypointActionListener:((DjiWaypointActionEvent)->Unit)?=null
        override fun upload(path:String,completion:DjiUploadCompletion){uploadFailure?.let { throw it };uploadPath=path;uploadCompletion=completion;uploadCompletions+=completion}
        override fun start(name:String,completion:DjiControlCompletion){controlFailure?.let { throw it };calls+="start";command="start";controlName=name;controlCompletion=completion}
        override fun pause(completion:DjiControlCompletion){controlFailure?.let { throw it };command="pause";controlCompletion=completion}
        override fun resume(completion:DjiControlCompletion){controlFailure?.let { throw it };command="resume";controlCompletion=completion}
        override fun stop(name:String,completion:DjiControlCompletion){controlFailure?.let { throw it };command="stop";controlName=name;controlCompletion=completion}
        override fun onExecutionState(listener:(DjiMissionExecutionState)->Unit):DjiExecutionStateRegistration { calls+="listener"; executionListenerRegistrations++; executionListener=listener; return DjiExecutionStateRegistration { executionListener=null } }
        override fun onExecutingInfo(onInfo:(DjiWaylineExecutingInfo)->Unit,onInterrupt:(String?,String?)->Unit):DjiExecutionStateRegistration { executingInfoListener=onInfo; executingInterruptListener=onInterrupt; return DjiExecutionStateRegistration { executingInfoListener=null; executingInterruptListener=null } }
        override fun onWaypointAction(listener:(DjiWaypointActionEvent)->Unit):DjiExecutionStateRegistration { waypointActionListener=listener; return DjiExecutionStateRegistration { waypointActionListener=null } }
        fun emit(state:DjiMissionExecutionState) { executionListener?.invoke(state) }
        fun emitExecutingInfo(info:DjiWaylineExecutingInfo) { executingInfoListener?.invoke(info) }
        fun emitWaypointAction(event:DjiWaypointActionEvent) { waypointActionListener?.invoke(event) }
        override fun close(){closeCalls++} }

    private class MaximumReadSizeInputStream(
        private var remaining: Long,
    ) : InputStream() {
        var maximumRequestedBytes = 0

        override fun read(): Int = if (remaining == 0L) -1 else {
            remaining -= 1
            0
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            maximumRequestedBytes = maxOf(maximumRequestedBytes, length)
            if (remaining == 0L) return -1
            val copied = minOf(remaining, length.toLong()).toInt()
            remaining -= copied.toLong()
            return copied
        }
    }
}
