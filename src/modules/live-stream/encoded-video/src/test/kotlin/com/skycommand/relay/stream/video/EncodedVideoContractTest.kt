package com.skycommand.relay.stream.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class EncodedVideoContractTest {
    @Test
    fun acceptsAValidH264FrameAndPreservesTheCallerBuffer() {
        val data = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0, 0, 0, 1, 0x68)
        val frame = EncodedVideoFrame(data, 4, 7, 1920, 1080, 30, 1234, true)

        assertEquals(EncodedVideoCodec.H264, frame.codec)
        assertEquals(data, frame.data)
        assertEquals(4, frame.offset)
        assertEquals(7, frame.length)
        assertEquals(1234, frame.presentationTimeMs)
        assertEquals(true, frame.isKeyFrame)
    }

    @Test
    fun rejectsEveryInvalidFrameBoundaryAndMetadata() {
        val data = ByteArray(8)
        listOf(
            { EncodedVideoFrame(data, -1, 1, 1, 1, 30, 0, false) },
            { EncodedVideoFrame(data, 0, 0, 1, 1, 30, 0, false) },
            { EncodedVideoFrame(data, 7, 2, 1, 1, 30, 0, false) },
            { EncodedVideoFrame(ByteArray(0), 0, 1, 1, 1, 30, 0, false) },
            { EncodedVideoFrame(data, 0, 1, 0, 1, 30, 0, false) },
            { EncodedVideoFrame(data, 0, 1, 1, 1, 0, 0, false) },
            { EncodedVideoFrame(data, 0, 1, 1, 1, 30, -1, false) },
        ).forEach { factory -> assertFailsWith<IllegalArgumentException> { factory() } }
    }

    @Test
    fun exposesOnlyH264AndStableSourceLifecycleTypes() {
        assertEquals(listOf(EncodedVideoCodec.H264), EncodedVideoCodec.entries)
        assertIs<SourceStartResult.Started>(SourceStartResult.Started)
        assertIs<SourceStartResult.AlreadyStarted>(SourceStartResult.AlreadyStarted)
        assertIs<SourceStartResult.Failed>(SourceStartResult.Failed(SourceFailure.PLATFORM_FAILURE))
        assertIs<SourceStopResult.Stopped>(SourceStopResult.Stopped)
        assertIs<SourceStopResult.AlreadyStopped>(SourceStopResult.AlreadyStopped)
        assertIs<SourceStopResult.Failed>(SourceStopResult.Failed(SourceFailure.PLATFORM_FAILURE))
    }
}
