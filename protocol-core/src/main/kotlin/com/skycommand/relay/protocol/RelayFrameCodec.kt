package com.skycommand.relay.protocol

import com.fasterxml.jackson.core.JsonParser
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
    private val mapper = ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun encode(frame: RelayFrame): ProtocolResult<ByteArray> {
        val validation = validate(frame)
        if (validation is Rejected) {
            return validation
        }
        return runCatching { Accepted(mapper.writeValueAsBytes(toNode(frame))) }
            .getOrElse { Rejected(ProtocolError(ProtocolErrorCode.INVALID_JSON, "Frame cannot be encoded")) }
    }

    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.isEmpty()) {
            return rejected(ProtocolErrorCode.INVALID_JSON, "Frame is empty")
        }
        try {
            decodeUtf8(bytes)
        } catch (_: CharacterCodingException) {
            return rejected(ProtocolErrorCode.INVALID_UTF8, "Frame is not valid UTF-8")
        }

        val root = try {
            mapper.readTree(bytes)
        } catch (_: Exception) {
            return rejected(ProtocolErrorCode.INVALID_JSON, "Frame is not valid JSON")
        }
        if (root == null || !root.isObject) {
            return rejected(ProtocolErrorCode.INVALID_JSON, "Frame must be a JSON object")
        }

        return try {
            decodeKnownFrame(root)
        } catch (error: CodecFailure) {
            DecodeResult.Rejected(error.error)
        } catch (_: Exception) {
            rejected(ProtocolErrorCode.INVALID_FIELD, "Frame contains an invalid field")
        }
    }

    private fun decodeKnownFrame(root: JsonNode): DecodeResult {
        val type = requiredText(root, "type")
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
        if (value == null || !value.isIntegralNumber) {
            throw CodecFailure(ProtocolError(ProtocolErrorCode.INVALID_FIELD, "Field $name must be an integer"))
        }
        return value.longValue()
    }

    private fun requiredObject(node: JsonNode, name: String): JsonNode {
        val value = node.get(name)
        if (value == null || !value.isObject) {
            throw CodecFailure(ProtocolError(ProtocolErrorCode.INVALID_FIELD, "Field $name must be an object"))
        }
        return value
    }

    private fun decodeBase64(value: String): ByteArray {
        return try {
            Base64.getDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            throw CodecFailure(ProtocolError(ProtocolErrorCode.INVALID_BASE64, "Mission chunk is not valid Base64"))
        }
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
