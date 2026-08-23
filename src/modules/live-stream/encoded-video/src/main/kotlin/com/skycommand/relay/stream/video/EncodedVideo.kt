package com.skycommand.relay.stream.video

enum class EncodedVideoCodec {
    H264,
}

class EncodedVideoFrame(
    val data: ByteArray,
    val offset: Int,
    val length: Int,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val presentationTimeMs: Long,
    val isKeyFrame: Boolean,
    val codec: EncodedVideoCodec = EncodedVideoCodec.H264,
) {
    init {
        require(codec == EncodedVideoCodec.H264) { "Only H264 frames are supported" }
        require(data.isNotEmpty()) { "Frame data must not be empty" }
        require(offset >= 0 && length > 0 && offset <= data.size - length) { "Frame range is invalid" }
        require(width > 0 && height > 0) { "Frame dimensions are invalid" }
        require(frameRate > 0) { "Frame rate is invalid" }
        require(presentationTimeMs >= 0) { "Presentation time is invalid" }
    }
}

fun interface EncodedVideoListener {
    fun onFrame(frame: EncodedVideoFrame)
}

sealed interface SourceStartResult {
    data object Started : SourceStartResult

    data object AlreadyStarted : SourceStartResult

    data class Failed(val reason: SourceFailure) : SourceStartResult
}

sealed interface SourceStopResult {
    data object Stopped : SourceStopResult

    data object AlreadyStopped : SourceStopResult

    data class Failed(val reason: SourceFailure) : SourceStopResult
}

enum class SourceFailure {
    INVALID_LISTENER,
    PLATFORM_FAILURE,
    UNSUPPORTED_CODEC,
}

interface EncodedVideoSource {
    fun start(listener: EncodedVideoListener): SourceStartResult

    fun start(listener: EncodedVideoListener, onFailure: (SourceFailure) -> Unit): SourceStartResult = start(listener)

    fun stop(): SourceStopResult
}
