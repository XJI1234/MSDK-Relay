package com.skycommand.relay.wayline.android

import com.skycommand.relay.wayline.executor.ControlCompletion
import com.skycommand.relay.wayline.staging.MissionMetadata
import com.skycommand.relay.wayline.uploader.UploadCompletion
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidDjiWaylineAdapterContractTest {
    @Test fun uploadsTemporaryFileAndControlsTheSuccessfulName() {
        val files=FakeFiles(); val dji=FakeDji(); val adapter=AndroidDjiWaylineAdapter(files,dji); val progress=mutableListOf<Int>(); val done=UploadDone()
        adapter.upload(metadata("one.kmz"), byteArrayOf(1,2), { progress+=it }, done)
        assertEquals(files.path,dji.uploadPath); requireNotNull(dji.uploadCompletion).progress(45.4); requireNotNull(dji.uploadCompletion).succeed(); requireNotNull(dji.uploadCompletion).succeed()
        assertEquals(listOf(45),progress); assertEquals(listOf("success"),done.events); assertEquals(1,files.deletes)
        val start=ControlDone(); adapter.start(start); assertEquals("one.kmz",dji.controlName); requireNotNull(dji.controlCompletion).succeed(); assertEquals(listOf("success"),start.events)
    }

    @Test fun failedUploadDoesNotReplacePriorSuccessfulMission() {
        val dji=FakeDji(); val adapter=AndroidDjiWaylineAdapter(FakeFiles(),dji)
        adapter.upload(metadata("good.kmz"), byteArrayOf(1), {}, UploadDone()); requireNotNull(dji.uploadCompletion).succeed()
        adapter.upload(metadata("bad.kmz"), byteArrayOf(2), {}, UploadDone()); requireNotNull(dji.uploadCompletion).fail()
        adapter.stop(ControlDone()); assertEquals("good.kmz",dji.controlName)
    }

    @Test fun rejectsUnsafeNameAndDelegatesPauseResume() {
        val files=FakeFiles(); val dji=FakeDji(); val adapter=AndroidDjiWaylineAdapter(files,dji); val failed=UploadDone()
        adapter.upload(metadata("../bad.kmz"), byteArrayOf(1), {}, failed)
        assertEquals(listOf("failure"),failed.events); assertEquals(0,files.writes)
        val missing=ControlDone(); adapter.start(missing); assertEquals(listOf("failure"),missing.events)
        adapter.pause(ControlDone()); assertEquals("pause",dji.command); adapter.resume(ControlDone()); assertEquals("resume",dji.command)
    }

    private fun metadata(name:String)=MissionMetadata(name,1,"a".repeat(64))
    private class UploadDone:UploadCompletion{val events=mutableListOf<String>();override fun succeed(){events+="success"};override fun fail(){events+="failure"}}
    private class ControlDone:ControlCompletion{val events=mutableListOf<String>();override fun succeed(){events+="success"};override fun fail(){events+="failure"}}
    private class FakeFiles:MissionFileStore{val path="C:/cache/file.kmz";var writes=0;var deletes=0
        override fun write(fileName:String,content:ByteArray):StoredMissionFile{writes++;return StoredMissionFile(path,fileName){deletes++}}}
    private class FakeDji:DjiWaypointMissionApi{var uploadPath:String?=null;var uploadCompletion:DjiUploadCompletion?=null;var controlCompletion:DjiControlCompletion?=null;var controlName:String?=null;var command:String?=null
        override fun upload(path:String,completion:DjiUploadCompletion){uploadPath=path;uploadCompletion=completion}
        override fun start(name:String,completion:DjiControlCompletion){command="start";controlName=name;controlCompletion=completion}
        override fun pause(completion:DjiControlCompletion){command="pause";controlCompletion=completion}
        override fun resume(completion:DjiControlCompletion){command="resume";controlCompletion=completion}
        override fun stop(name:String,completion:DjiControlCompletion){command="stop";controlName=name;controlCompletion=completion}
        override fun close(){} }
}
