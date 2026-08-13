package com.skycommand.relay.protocol

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RelayFrameInteropVectorsTest {

    private val mapper = ObjectMapper()

    @Test
    fun decodesAndCanonicallyReencodesEverySharedAcceptedFrame() {
        val document = vectors()

        assertEquals("sky-command-relay-interop-v1", document.requiredText("format"))
        assertEquals(1, document.requiredInt("revision"))

        document.requiredArray("vectors").filter { it.requiredText("expected", "kind") == "decoded" }.forEach { vector ->
            val decoded = RelayFrameCodec.decode(vector.requiredText("wire").encodeToByteArray())
            val frame = assertIs<DecodeResult.Decoded>(decoded, vector.requiredText("id")).frame
            val encoded = assertIs<Accepted<ByteArray>>(RelayFrameCodec.encode(frame), vector.requiredText("id")).value

            assertEquals(vector.requiredText("expected", "canonicalWire"), encoded.decodeToString(), vector.requiredText("id"))
        }
    }

    @Test
    fun returnsTheVectorsStableDispositionForEveryRejectedOrIgnoredFrame() {
        val document = vectors()

        document.requiredArray("vectors").filter { it.requiredText("expected", "kind") != "decoded" }.forEach { vector ->
            val expected = vector.requiredObject("expected")
            val id = vector.requiredText("id")
            when (expected.requiredText("kind")) {
                "rejected" -> assertEquals(
                    expected.requiredText("code"),
                    assertIs<DecodeResult.Rejected>(RelayFrameCodec.decode(vector.requiredText("wire").encodeToByteArray()), id).error.code.name,
                    id,
                )

                "ignored" -> assertEquals(
                    expected.requiredText("type"),
                    assertIs<DecodeResult.Ignored>(RelayFrameCodec.decode(vector.requiredText("wire").encodeToByteArray()), id).type,
                    id,
                )

                else -> error("Unexpected vector disposition for $id")
            }
        }
    }

    private fun vectors(): JsonNode = mapper.readTree(
        checkNotNull(javaClass.getResourceAsStream("/relay-v1-interop-vectors.json")) {
            "Missing relay-v1 interoperability vectors"
        }
    )

    private fun JsonNode.requiredText(name: String): String = requiredObject().get(name)?.takeIf(JsonNode::isTextual)?.textValue()
        ?: error("Missing text field $name")

    private fun JsonNode.requiredText(parent: String, name: String): String = requiredObject(parent).requiredText(name)

    private fun JsonNode.requiredInt(name: String): Int = requiredObject().get(name)?.takeIf(JsonNode::isInt)?.intValue()
        ?: error("Missing integer field $name")

    private fun JsonNode.requiredArray(name: String): List<JsonNode> = requiredObject().get(name)?.takeIf(JsonNode::isArray)?.toList()
        ?: error("Missing array field $name")

    private fun JsonNode.requiredObject(name: String): JsonNode = requiredObject().get(name)?.takeIf(JsonNode::isObject)
        ?: error("Missing object field $name")

    private fun JsonNode.requiredObject(): JsonNode = takeIf(JsonNode::isObject)
        ?: error("Expected object")
}
