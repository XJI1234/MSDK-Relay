package com.skycommand.relay.protocol

object ProtocolLimits {
    const val maxIdCodePoints = 128
    const val maxCommandNameCodePoints = 64
    const val maxFileNameCodePoints = 128
    const val maxResultDetailCodePoints = 1024
    const val maxMissionBytes = 100 * 1024 * 1024L
    const val maxMissionChunkBytes = 48 * 1024
    const val protocolVersion = "1"
}
