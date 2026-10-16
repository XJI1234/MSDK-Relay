package com.skycommand.relay.discovery

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnnouncementParserTest {
    private fun accepted(text: String = fixture, ip: String = source, subnet: Ipv4Subnet? = null): RelayAnnouncement =
        assertIs<AnnouncementParseResult.Accepted>(AnnouncementParser.parse(text.toByteArray(), ip, subnet)).announcement

    private fun rejected(bytes: ByteArray, reason: AnnouncementRejection) {
        assertEquals(AnnouncementParseResult.Rejected(reason), AnnouncementParser.parse(bytes, source))
    }

    @Test fun decodesSharedDesktopFixtureAndBuildsEndpointOnlyFromSource() {
        val parsed = accepted()
        assertEquals(CandidateIdentity(serverId, instanceId), parsed.identity)
        assertEquals("ws://$source:8080/relay", parsed.endpoint)
        assertEquals("ws://10.20.1.12:8080/relay", accepted(ip = "10.20.1.12").endpoint)
        assertEquals(39082, DiscoveryLimits.UDP_PORT)
    }

    @Test fun ignoresUnknownFieldsIncludingSuppliedHostAndUrl() {
        val text = fixture.dropLast(1) + ",\"host\":\"evil.invalid\",\"url\":\"wss://evil.invalid/relay\",\"extra\":{\"x\":[true,null]}}"
        assertEquals("ws://$source:8080/relay", accepted(text).endpoint)
    }

    @Test fun enforcesByteSizeAt512And513WithoutParsingOversizedContent() {
        rejected(byteArrayOf(), AnnouncementRejection.SIZE)
        val exact = fixture.toByteArray() + ByteArray(512 - fixture.toByteArray().size) { 32 }
        assertIs<AnnouncementParseResult.Accepted>(AnnouncementParser.parse(exact, source))
        rejected(exact + byteArrayOf(32), AnnouncementRejection.SIZE)
        rejected(ByteArray(65536), AnnouncementRejection.SIZE)
        val unicode = fixture.dropLast(1) + ",\"extension\":\"" + "中".repeat(100) + "\"}"
        assertTrue(unicode.length < 512)
        rejected(unicode.toByteArray(), AnnouncementRejection.SIZE)
    }

    @Test fun rejectsMalformedUtf8WithoutReplacementOrUtf16Autodetection() {
        for (invalid in listOf(
            byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0x80.toByte()),
            byteArrayOf(0xf0.toByte(), 0x9f.toByte()), byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()),
        )) rejected(invalid, AnnouncementRejection.UTF8)
        assertIs<AnnouncementParseResult.Rejected>(AnnouncementParser.parse(fixture.toByteArray(Charsets.UTF_16LE), source))
    }

    @Test fun rejectsInvalidJsonDuplicateFieldsTrailingValuesAndNonObjects() {
        for (text in listOf(
            "", "{", "[]", "null", "1", "true", "\"string\"", fixture + "{}", fixture + " null",
            fixture.dropLast(1) + ",\"relayPort\":8080}", "/* comment */$fixture", fixture.dropLast(1) + ",}",
        ).filter(String::isNotEmpty)) rejected(text.toByteArray(), AnnouncementRejection.JSON)
    }

    @Test fun rejectsDeepExtensionsBeforeBuildingUnboundedTrees() {
        val text = fixture.dropLast(1) + ",\"extra\":" + "[".repeat(20) + "0" + "]".repeat(20) + "}"
        rejected(text.toByteArray(), AnnouncementRejection.JSON)
    }

    @Test fun rejectsMissingFieldsAndNullsWithoutCoercion() {
        val fields = listOf(
            "\"kind\":\"SKY_COMMAND_RELAY_ANNOUNCEMENT\"", "\"discoveryVersion\":1", "\"serverId\":\"$serverId\"",
            "\"instanceId\":\"$instanceId\"", "\"protocolVersion\":\"1\"", "\"relayPort\":8080", "\"relayPath\":\"/relay\"",
        )
        for (field in fields) {
            val without = fixture.replace(field, "\"ignored\":null")
            rejected(without.toByteArray(), AnnouncementRejection.FIELDS)
            rejected(fixture.replace(field, field.substringBefore(':') + ":null").toByteArray(), AnnouncementRejection.FIELDS)
        }
    }

    @Test fun requiresExactMessageVersionsPathAndLowercaseUuid() {
        for ((from, to) in listOf(
            "SKY_COMMAND_RELAY_ANNOUNCEMENT" to "SKY_COMMAND_NETWORK_PROBE",
            "\"discoveryVersion\":1" to "\"discoveryVersion\":2",
            "\"discoveryVersion\":1" to "\"discoveryVersion\":1.0",
            "\"discoveryVersion\":1" to "\"discoveryVersion\":\"1\"",
            "\"protocolVersion\":\"1\"" to "\"protocolVersion\":1",
            "\"protocolVersion\":\"1\"" to "\"protocolVersion\":\"2\"",
            "/relay" to "/relay?token=x", "/relay" to "/other",
            serverId to "not-a-uuid", instanceId to "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA",
        )) rejected(fixture.replace(from, to).toByteArray(), AnnouncementRejection.FIELDS)
    }

    @Test fun validatesIntegerPortBoundaries() {
        for (port in listOf("0", "-1", "65536", "8080.0", "1e3", "\"8080\"", "true", "[]", "{}", "2147483648")) {
            assertIs<AnnouncementParseResult.Rejected>(AnnouncementParser.parse(fixture.replace("8080", port).toByteArray(), source))
        }
        for (port in listOf(1, 65535)) assertEquals(port, accepted(fixture.replace("8080", port.toString())).relayPort)
    }

    @Test fun acceptsCanonicalUnicastAndRejectsLoopbackReservedBroadcastAndHostnames() {
        for (ip in listOf("0.0.0.0", "0.1.2.3", "127.0.0.1", "224.0.0.1", "239.2.3.4", "240.1.2.3", "255.255.255.255",
            "localhost", "::1", "10.01.2.3", "10.2.3.256", "10.2.3", "10.2.3.4 ", "10.2.3.-1", "１.2.3.4")) {
            assertEquals(AnnouncementParseResult.Rejected(AnnouncementRejection.SOURCE), AnnouncementParser.parse(fixture.toByteArray(), ip))
        }
        for (ip in listOf("10.20.1.2", "172.16.0.1", "192.168.1.1", "1.2.3.255")) assertEquals(ip, accepted(ip = ip).sourceIpv4)
    }

    @Test fun usesReceiverSubnetToRejectDirectedBroadcastAndNetworkAddress() {
        val subnet = requireNotNull(Ipv4Subnet.create(source, 18))
        for (ip in listOf("10.20.191.255", "10.20.128.0")) {
            assertEquals(AnnouncementParseResult.Rejected(AnnouncementRejection.SOURCE), AnnouncementParser.parse(fixture.toByteArray(), ip, subnet))
        }
        assertEquals("10.20.168.255", accepted(ip = "10.20.168.255", subnet = subnet).sourceIpv4)
        for (prefix in listOf(-1, 0, 31, 32, 33)) assertNull(Ipv4Subnet.create(source, prefix))
        assertNull(Ipv4Subnet.create("invalid", 24)); assertNull(Ipv4Subnet.create("127.0.0.1", 24))
    }

    @Test fun randomDatagramsNeverEscapeWithThirdPartyErrors() {
        val random = Random(20261013)
        repeat(2000) {
            assertIs<AnnouncementParseResult.Rejected>(AnnouncementParser.parse(random.nextBytes(random.nextInt(0, 700)), source))
        }
    }
}
