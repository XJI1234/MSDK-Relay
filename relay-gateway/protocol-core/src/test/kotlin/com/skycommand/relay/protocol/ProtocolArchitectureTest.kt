package com.skycommand.relay.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProtocolArchitectureTest {

    @Test
    fun runtimeStateMachinesAreNotPartOfProtocolCore() {
        listOf(
            "com.skycommand.relay.protocol.RelaySessionStateMachine",
            "com.skycommand.relay.protocol.MissionTransferState",
        ).forEach { className ->
            assertFailsWith<ClassNotFoundException> {
                Class.forName(className)
            }
        }
    }

    @Test
    fun protocolErrorsContainNoRuntimeStateFailures() {
        val forbidden = setOf(
            "INVALID_SESSION_STATE",
            "HANDSHAKE_REQUIRED",
            "DUPLICATE_HANDSHAKE",
            "FRAME_NOT_ALLOWED",
            "TRANSFER_NOT_ACTIVE",
            "TRANSFER_ALREADY_ACTIVE",
            "TRANSFER_SUPERSEDED",
            "TRANSFER_SIZE_MISMATCH",
            "TRANSFER_CHECKSUM_MISMATCH",
        )

        val present = ProtocolErrorCode.entries.map(Enum<*>::name).toSet().intersect(forbidden)

        assertEquals(emptySet(), present)
    }

    @Test
    fun protocolErrorMessagesEnforceTheirContractBoundary() {
        listOf("", "x".repeat(257), "line\nbreak").forEach { detail ->
            assertFailsWith<IllegalArgumentException> {
                ProtocolError(ProtocolErrorCode.INVALID_JSON, detail)
            }
        }
    }
}
