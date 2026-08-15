package com.skycommand.relay.protocol

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.math.BigDecimal
import java.util.Base64

sealed interface DecodeResult {
    data class Decoded(val frame: RelayFrame) : DecodeResult

    data class Rejected(val error: ProtocolError) : DecodeResult

    data class Ignored(val type: String) : DecodeResult
}

object RelayFrameCodec {
    private val mapper = ObjectMapper(
        JsonFactory.builder()
            .streamReadConstraints(
                StreamReadConstraints.builder()
                    .maxNestingDepth(ProtocolLimits.maxJsonNestingDepth)
                    .maxDocumentLength(ProtocolLimits.maxFrameBytes.toLong())
                    .maxTokenCount(ProtocolLimits.maxJsonTokens)
                    .maxNumberLength(ProtocolLimits.maxJsonNumberChars)
                    .maxStringLength(ProtocolLimits.maxParserStringChars)
                    .maxNameLength(ProtocolLimits.maxParserFieldNameChars)
                    .build()
            )
            .build()
    )
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun encode(frame: RelayFrame): ProtocolResult<ByteArray> {
        val validation = validate(frame)
        if (validation is Rejected) {
            return validation
        }
        return runCatching { mapper.writeValueAsBytes(toNode(frame)) }
            .fold(
                onSuccess = { bytes ->
                    if (bytes.size > ProtocolLimits.maxFrameBytes) {
                        Rejected(ProtocolError(ProtocolErrorCode.FRAME_TOO_LARGE, "Frame exceeds the allowed size"))
                    } else {
                        Accepted(bytes)
                    }
                },
                onFailure = {
                    Rejected(ProtocolError(ProtocolErrorCode.INVALID_JSON, "Frame cannot be encoded"))
                },
            )
    }

    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.isEmpty()) {
            return rejected(ProtocolErrorCode.INVALID_JSON, "Frame is empty")
        }
        if (bytes.size > ProtocolLimits.maxFrameBytes) {
            return rejected(ProtocolErrorCode.FRAME_TOO_LARGE, "Frame exceeds the allowed size")
        }
        val json = try {
            decodeUtf8(bytes)
        } catch (_: CharacterCodingException) {
            return rejected(ProtocolErrorCode.INVALID_UTF8, "Frame is not valid UTF-8")
        }

        val root = try {
            mapper.readTree(json)
        } catch (_: Exception) {
            return rejected(ProtocolErrorCode.INVALID_JSON, "Frame is not valid JSON")
        }
        if (root == null || !root.isObject) {
            return rejected(ProtocolErrorCode.INVALID_JSON, "Frame must be a JSON object")
        }

        return try {
            val missionChunkData = if (root.get("type")?.textValue() == "mission-chunk") {
                root.get("data")
            } else {
                null
            }
            validateJsonTree(root, missionChunkData)
            decodeKnownFrame(root)
        } catch (error: CodecFailure) {
            DecodeResult.Rejected(error.error)
        } catch (_: Exception) {
            rejected(ProtocolErrorCode.INVALID_FIELD, "Frame contains an invalid field")
        }
    }

    private fun validateJsonTree(node: JsonNode, stringLimitExemption: JsonNode?) {
        when {
            node.isTextual -> {
                val value = node.textValue()
                if (
                    node !== stringLimitExemption &&
                    value.codePointCount(0, value.length) > ProtocolLimits.maxJsonStringCodePoints
                ) {
                    throw CodecFailure(ProtocolError(ProtocolErrorCode.INVALID_JSON, "JSON string is too long"))
                }
            }

            node.isArray -> node.elements().forEachRemaining { child ->
                validateJsonTree(child, stringLimitExemption)
            }
            node.isObject -> node.fields().forEachRemaining { (name, child) ->
                if (
                    name.isBlank() ||
                    name.codePointCount(0, name.length) > ProtocolLimits.maxJsonFieldNameCodePoints ||
                    name.any(Char::isISOControl)
                ) {
                    throw CodecFailure(ProtocolError(ProtocolErrorCode.INVALID_FIELD, "JSON field name is invalid"))
                }
                validateJsonTree(child, stringLimitExemption)
            }
        }
    }

    private fun decodeKnownFrame(root: JsonNode): DecodeResult {
        val type = requiredMessageType(root)
        return when (type) {
            "hello" -> decoded(
                HelloFrame(
                    deviceId = requiredText(root, "deviceId"),
                    protocolVersion = requiredText(root, "protocolVersion"),
                )
            )

            "paired" -> decoded(
                PairedFrame(
                    sessionId = requiredText(root, "sessionId"),
                    protocolVersion = optionalText(root, "protocolVersion"),
                )
            )

            "telemetry" -> decoded(
                TelemetryFrame(
                    payload = requiredObject(root, "payload").toJsonObject(),
                    capabilities = requiredObject(root, "capabilities").toJsonObject(),
                )
            )

            "command" -> {
                val command = requiredObject(root, "command")
                val name = requiredText(command, "name")
                val fields = command.fields().asSequence()
                    .filter { it.key != "name" }
                    .associate { it.key to it.value.toJsonValue() }
                decoded(CommandFrame(requiredText(root, "id"), name, JsonObject(fields)))
            }

            "command-result" -> decoded(
                CommandResultFrame(
                    id = requiredText(root, "id"),
                    ok = requiredBoolean(root, "ok"),
                    detail = optionalText(root, "detail") ?: "",
                    result = optionalObject(root, "result")?.toJsonObject(),
                )
            )

            "mission-begin" -> decoded(
                MissionBeginFrame(
                    id = requiredText(root, "id"),
                    fileName = requiredText(root, "fileName"),
                    size = requiredLong(root, "size"),
                    sha256 = requiredText(root, "sha256"),
                )
            )

            "mission-chunk" -> decoded(
                MissionChunkFrame(
                    id = requiredText(root, "id"),
                    bytes = decodeBase64(requiredText(root, "data")),
                )
            )

            "mission-complete" -> decoded(MissionCompleteFrame(requiredText(root, "id")))

            "mission-result" -> decoded(
                MissionResultFrame(
                    id = requiredText(root, "id"),
                    ok = requiredBoolean(root, "ok"),
                    detail = optionalText(root, "detail") ?: "",
                )
            )

            "mission-phase" -> decoded(
                MissionPhaseFrame(
                    missionRevision = requiredLong(root, "missionRevision"),
                    deviceGeneration = requiredLong(root, "deviceGeneration"),
                    sequence = requiredLong(root, "sequence"),
                    phase = requiredMissionPhase(requiredText(root, "phase")),
                    fileName = requiredText(root, "fileName"),
                )
            )

            "diagnostic-report" -> decoded(
                DiagnosticReportFrame(
                    runId = requiredText(root, "runId"),
                    events = requiredArray(root, "events").map { eventNode ->
                        val event = requireObject(eventNode, "Diagnostic event must be an object")
                        DiagnosticEventFrame(
                            sequence = requiredLong(event, "sequence"),
                            timestampMillis = requiredLong(event, "timestampMillis"),
                            level = requiredText(event, "level"),
                            module = requiredText(event, "module"),
                            eventCode = requiredText(event, "eventCode"),
                            operationId = optionalText(event, "operationId"),
                            safeDetail = requiredText(event, "safeDetail"),
                        )
                    },
                ),
            )

            "diagnostic-ack" -> decoded(
                DiagnosticAcknowledgementFrame(
                    runId = requiredText(root, "runId"),
                    acknowledgedSequence = requiredLong(root, "acknowledgedSequence"),
                ),
            )

            else -> DecodeResult.Ignored(type)
        }
    }

    private fun decoded(frame: RelayFrame): DecodeResult {
        return when (val result = validate(frame)) {
            is Accepted -> DecodeResult.Decoded(result.value)
            is Rejected -> DecodeResult.Rejected(result.error)
        }
    }

    private fun toNode(frame: RelayFrame): JsonNode {
        val root = mapper.createObjectNode()
        when (frame) {
            is HelloFrame -> {
                root.put("type", "hello")
                root.put("deviceId", frame.deviceId)
                root.put("protocolVersion", frame.protocolVersion)
            }

            is PairedFrame -> {
                root.put("type", "paired")
                root.put("sessionId", frame.sessionId)
                frame.protocolVersion?.let { root.put("protocolVersion", it) }
            }

            is TelemetryFrame -> {
                root.put("type", "telemetry")
                root.set<JsonNode>("payload", frame.payload.toNode())
                root.set<JsonNode>("capabilities", frame.capabilities.toNode())
            }

            is CommandFrame -> {
                root.put("type", "command")
                root.put("id", frame.id)
                val command = frame.fields.toNode().deepCopy<JsonNode>()
                command as com.fasterxml.jackson.databind.node.ObjectNode
                command.put("name", frame.name)
                root.set<JsonNode>("command", command)
            }

            is CommandResultFrame -> {
                root.put("type", "command-result")
                root.put("id", frame.id)
                root.put("ok", frame.ok)
                root.put("detail", frame.detail)
                frame.result?.let { root.set<JsonNode>("result", it.toNode()) }
            }

            is MissionBeginFrame -> {
                root.put("type", "mission-begin")
                root.put("id", frame.id)
                root.put("fileName", frame.fileName)
                root.put("size", frame.size)
                root.put("sha256", frame.sha256)
            }

            is MissionChunkFrame -> {
                root.put("type", "mission-chunk")
                root.put("id", frame.id)
                root.put("data", Base64.getEncoder().encodeToString(frame.bytes))
            }

            is MissionCompleteFrame -> {
                root.put("type", "mission-complete")
                root.put("id", frame.id)
            }

            is MissionResultFrame -> {
                root.put("type", "mission-result")
                root.put("id", frame.id)
                root.put("ok", frame.ok)
                root.put("detail", frame.detail)
            }

            is MissionPhaseFrame -> {
                root.put("type", "mission-phase")
                root.put("missionRevision", frame.missionRevision)
                root.put("deviceGeneration", frame.deviceGeneration)
                root.put("sequence", frame.sequence)
                root.put("phase", frame.phase.name)
                root.put("fileName", frame.fileName)
            }

            is DiagnosticReportFrame -> {
                root.put("type", "diagnostic-report")
                root.put("runId", frame.runId)
                val events = mapper.createArrayNode()
                frame.events.forEach { event ->
                    val node = mapper.createObjectNode()
                    node.put("sequence", event.sequence)
                    node.put("timestampMillis", event.timestampMillis)
                    node.put("level", event.level)
                    node.put("module", event.module)
                    node.put("eventCode", event.eventCode)
                    event.operationId?.let { node.put("operationId", it) }
                    node.put("safeDetail", event.safeDetail)
                    events.add(node)
                }
                root.set<JsonNode>("events", events)
            }

            is DiagnosticAcknowledgementFrame -> {
                root.put("type", "diagnostic-ack")
                root.put("runId", frame.runId)
                root.put("acknowledgedSequence", frame.acknowledgedSequence)
            }
        }
        return root
    }

    private fun requiredText(node: JsonNode, name: String): String {
        val value = node.get(name)
        if (value == null || !value.isTextual) {
            throw CodecFailure(ProtocolError(ProtocolErrorCode.INVALID_FIELD, "Field $name must be text"))
        }
        return value.textValue()
    }


    private fun requiredMessageType(node: JsonNode): String {
        val type = requiredText(node, "type")
        if (
            type.isBlank() ||
            type.codePointCount(0, type.length) > ProtocolLimits.maxMessageTypeCodePoints ||
            type.any(Char::isISOControl)
        ) {
            throw CodecFailure(ProtocolError(ProtocolErrorCode.INVALID_MESSAGE_TYPE, "Message type is invalid"))
        }
        return type
    }

    private fun optionalText(node: JsonNode, name: String): String? {
        val value = node.get(name) ?: return null
        if (!value.isTextual) {
            throw CodecFailure(ProtocolError(ProtocolErrorCode.INVALID_FIELD, "Field $name must be text"))
        }
        return value.textValue()
    }

    private fun requiredBoolean(node: JsonNode, name: String): Boolean {
        val value = node.get(name)
        if (value == null || !value.isBoolean) {
            throw CodecFailure(ProtocolError(ProtocolErrorCode.INVALID_FIELD, "Field $name must be boolean"))
        }
        return value.booleanValue()
    }

    private fun requiredLong(node: JsonNode, name: String): Long {
        val value = node.get(name)
        if (value == null || !value.isIntegralNumber || !value.canConvertToLong()) {
            throw CodecFailure(ProtocolError(ProtocolErrorCode.INVALID_FIELD, "Field $name must be an integer"))
        }
        return value.longValue()
    }

    private fun requiredMissionPhase(value: String): MissionPhase =
        runCatching { MissionPhase.valueOf(value) }.getOrElse {
            throw CodecFailure(ProtocolError(ProtocolErrorCode.INVALID_FIELD, "Mission phase is invalid"))
        }

    private fun requiredObject(node: JsonNode, name: String): JsonNode {
        val value = node.get(name)
        if (value == null || !value.isObject) {
            throw CodecFailure(ProtocolError(ProtocolErrorCode.INVALID_FIELD, "Field $name must be an object"))
        }
        return value
    }

    private fun optionalObject(node: JsonNode, name: String): JsonNode? {
        val value = node.get(name) ?: return null
        if (!value.isObject) {
            throw CodecFailure(ProtocolError(ProtocolErrorCode.INVALID_FIELD, "Field $name must be an object"))
        }
        return value
    }

    private fun requiredArray(node: JsonNode, name: String): List<JsonNode> {
        val value = node.get(name)
        if (value == null || !value.isArray) {
            throw CodecFailure(ProtocolError(ProtocolErrorCode.INVALID_FIELD, "Field $name must be an array"))
        }
        return value.elements().asSequence().toList()
    }

    private fun requireObject(node: JsonNode, message: String): JsonNode {
        if (!node.isObject) {
            throw CodecFailure(ProtocolError(ProtocolErrorCode.INVALID_FIELD, message))
        }
        return node
    }

    private fun decodeBase64(value: String): ByteArray {
        if (value.length > ProtocolLimits.maxMissionChunkBase64Chars) {
            throw CodecFailure(ProtocolError(ProtocolErrorCode.CHUNK_TOO_LARGE, "Mission chunk is too large"))
        }
        if (value.length % 4 != 0) {
            throw CodecFailure(ProtocolError(ProtocolErrorCode.INVALID_BASE64, "Mission chunk is not valid Base64"))
        }
        val decoded = try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            throw CodecFailure(ProtocolError(ProtocolErrorCode.INVALID_BASE64, "Mission chunk is not valid Base64"))
        }
        if (Base64.getEncoder().encodeToString(decoded) != value) {
            throw CodecFailure(ProtocolError(ProtocolErrorCode.INVALID_BASE64, "Mission chunk is not valid Base64"))
        }
        return decoded
    }

    private fun JsonNode.toJsonObject(): JsonObject =
        JsonObject(fields().asSequence().associate { it.key to it.value.toJsonValue() })

    private fun JsonNode.toJsonValue(): JsonValue {
        return when {
            isNull -> JsonNull
            isTextual -> JsonString(textValue())
            isBoolean -> JsonBoolean(booleanValue())
            isNumber -> JsonNumber(asText())
            isArray -> JsonArray(elements().asSequence().map { it.toJsonValue() }.toList())
            isObject -> toJsonObject()
            else -> throw CodecFailure(ProtocolError(ProtocolErrorCode.INVALID_JSON, "Unsupported JSON value"))
        }
    }

    private fun JsonValue.toNode(): JsonNode {
        return when (this) {
            JsonNull -> mapper.nullNode()
            is JsonString -> mapper.nodeFactory.textNode(value)
            is JsonNumber -> runCatching { mapper.nodeFactory.numberNode(BigDecimal(value)) }
                .getOrElse { throw CodecFailure(ProtocolError(ProtocolErrorCode.INVALID_JSON, "JSON number is invalid")) }
            is JsonBoolean -> mapper.nodeFactory.booleanNode(value)
            is JsonArray -> mapper.createArrayNode().also { array -> values.forEach { array.add(it.toNode()) } }
            is JsonObject -> mapper.createObjectNode().also { objectNode -> fields.forEach { (key, value) -> objectNode.set<JsonNode>(key, value.toNode()) } }
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun rejected(code: ProtocolErrorCode, message: String): DecodeResult.Rejected =
        DecodeResult.Rejected(ProtocolError(code, message))

    private class CodecFailure(val error: ProtocolError) : RuntimeException()
}
