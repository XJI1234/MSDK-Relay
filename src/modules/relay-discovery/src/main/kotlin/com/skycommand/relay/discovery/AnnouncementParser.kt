package com.skycommand.relay.discovery

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object DiscoveryLimits {
    const val UDP_PORT = 39082
    const val MAX_DATAGRAM_BYTES = 512
    const val OBSERVATION_MILLIS = 3000L
    const val EXPIRY_MILLIS = 8000L
    const val MAX_CANDIDATES = 32
    const val MAX_ENDPOINTS_PER_CANDIDATE = 8
}

data class CandidateIdentity(val serverId: String, val instanceId: String)

/** Constructor is internal: only a successfully parsed datagram can create one. */
class RelayAnnouncement internal constructor(
    val identity: CandidateIdentity,
    val sourceIpv4: String,
    val relayPort: Int,
) {
    val endpoint: String get() = "ws://$sourceIpv4:$relayPort/relay"
}

enum class AnnouncementRejection { SIZE, UTF8, JSON, FIELDS, SOURCE }

sealed interface AnnouncementParseResult {
    data class Accepted(val announcement: RelayAnnouncement) : AnnouncementParseResult
    data class Rejected(val reason: AnnouncementRejection) : AnnouncementParseResult
}

class Ipv4Subnet private constructor(private val network: Long, private val mask: Long) {
    internal fun isNonHost(address: Long): Boolean =
        address == network || address == (network or (mask xor 0xffffffffL))

    companion object {
        fun create(localIpv4: String, prefixLength: Int): Ipv4Subnet? {
            val address = parseIpv4(localIpv4) ?: return null
            if (prefixLength !in 1..30 || !isUnicast(address)) return null
            val mask = (0xffffffffL shl (32 - prefixLength)) and 0xffffffffL
            return Ipv4Subnet(address and mask, mask)
        }
    }
}

private val dottedIpv4 = Regex("(?:0|[1-9][0-9]{0,2})(?:\\.(?:0|[1-9][0-9]{0,2})){3}")

internal fun parseIpv4(text: String): Long? {
    if (!dottedIpv4.matches(text)) return null
    val parts = text.split('.').map(String::toInt)
    if (parts.any { it > 255 }) return null
    return parts.fold(0L) { value, part -> (value shl 8) or part.toLong() }
}

private fun isUnicast(address: Long): Boolean =
    (address shr 24).let { it != 0L && it != 127L && it < 224L }

object AnnouncementParser {
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val mapper = ObjectMapper(
        JsonFactory.builder().streamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(16)
                .maxDocumentLength(DiscoveryLimits.MAX_DATAGRAM_BYTES.toLong())
                .maxNumberLength(8)
                .maxStringLength(DiscoveryLimits.MAX_DATAGRAM_BYTES)
                .build(),
        ).build(),
    ).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun parse(bytes: ByteArray, sourceIpv4: String, receiverSubnet: Ipv4Subnet? = null): AnnouncementParseResult {
        if (bytes.isEmpty() || bytes.size > DiscoveryLimits.MAX_DATAGRAM_BYTES) return rejected(AnnouncementRejection.SIZE)
        val source = parseIpv4(sourceIpv4)
        if (source == null || !isUnicast(source) || receiverSubnet?.isNonHost(source) == true) {
            return rejected(AnnouncementRejection.SOURCE)
        }
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { return rejected(AnnouncementRejection.UTF8) }
        val root = try { mapper.readTree(text) }
        catch (_: Exception) { return rejected(AnnouncementRejection.JSON) }
        if (root == null || !root.isObject) return rejected(AnnouncementRejection.JSON)
        val server = root.get("serverId")?.textValue()
        val instance = root.get("instanceId")?.textValue()
        val port = integer(root.get("relayPort"))
        if (root.get("kind")?.textValue() != "SKY_COMMAND_RELAY_ANNOUNCEMENT" ||
            integer(root.get("discoveryVersion")) != 1 || root.get("protocolVersion")?.textValue() != "1" ||
            root.get("relayPath")?.textValue() != "/relay" || server == null || !uuid.matches(server) ||
            instance == null || !uuid.matches(instance) || port == null || port !in 1..65535
        ) return rejected(AnnouncementRejection.FIELDS)
        return AnnouncementParseResult.Accepted(RelayAnnouncement(CandidateIdentity(server, instance), sourceIpv4, port))
    }

    private fun integer(node: JsonNode?): Int? =
        if (node != null && node.isIntegralNumber && node.canConvertToInt()) node.intValue() else null

    private fun rejected(reason: AnnouncementRejection) = AnnouncementParseResult.Rejected(reason)
}
