package com.skycommand.relay.protocol

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ProtocolRobustnessContractTest {

    @Test
    fun jsonContainersSnapshotConstructorInputsAndExposeReadOnlyCollections() {
        val sourceValues = mutableListOf<JsonValue>(JsonString("original"))
        val array = JsonArray(sourceValues)
        val sourceFields = mutableMapOf<String, JsonValue>("array" to array)
        val json = JsonObject(sourceFields)

        sourceValues[0] = JsonString("changed")
        sourceFields["later"] = JsonBoolean(true)

        assertEquals(JsonArray(listOf(JsonString("original"))), json["array"])
        assertEquals(setOf("array"), json.fields.keys)
        assertFailsWith<UnsupportedOperationException> {
            (array.values as MutableList<JsonValue>).add(JsonNull)
        }
        assertFailsWith<UnsupportedOperationException> {
            (json.fields as MutableMap<String, JsonValue>)["later"] = JsonNull
        }
    }

    @Test
    fun concurrentValidationEncodingAndDecodingHaveNoCrossCallState() {
        val frame = TelemetryFrame(
            payload = JsonObject(mapOf("altitude" to JsonNumber("12.5"))),
            capabilities = JsonObject(mapOf("video" to JsonBoolean(true))),
        )
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)

        try {
            val futures = List(32) {
                executor.submit {
                    start.await()
                    repeat(250) {
                        assertIs<Accepted<RelayFrame>>(validate(frame))
                        val encoded = assertIs<Accepted<ByteArray>>(RelayFrameCodec.encode(frame)).value
                        assertEquals(frame, assertIs<DecodeResult.Decoded>(RelayFrameCodec.decode(encoded)).frame)
                    }
                }
            }

            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }
}
