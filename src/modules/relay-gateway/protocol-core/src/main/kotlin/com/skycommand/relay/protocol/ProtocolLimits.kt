package com.skycommand.relay.protocol

object ProtocolLimits {
    const val maxFrameBytes = 96 * 1024
    const val maxJsonNestingDepth = 32
    const val maxJsonTokens = 8_192L
    const val maxJsonNumberChars = 128
    const val maxJsonStringCodePoints = 65_536
    const val maxJsonFieldNameCodePoints = 128
    internal const val maxParserStringChars = maxFrameBytes
    internal const val maxParserFieldNameChars = maxJsonFieldNameCodePoints * 2
    const val maxMessageTypeCodePoints = 64
    const val maxIdCodePoints = 128
    const val maxCommandNameCodePoints = 64
    const val maxFileNameCodePoints = 128
    const val maxResultDetailCodePoints = 1024
    const val maxDiagnosticEventsPerReport = 32
    const val maxDiagnosticModuleCodePoints = 64
    const val maxDiagnosticEventCodeCodePoints = 64
    const val maxDiagnosticDetailCodePoints = 512
    const val maxErrorMessageCodePoints = 256
    const val maxMissionBytes = 100 * 1024 * 1024L
    const val maxMissionChunkBytes = 48 * 1024
    const val maxMissionChunkBase64Chars = 65_536
    const val protocolVersion = "1"
}
