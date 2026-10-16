package com.skycommand.relay.discovery

internal val fixture = requireNotNull(object {}.javaClass.getResource("/announcement-v1.json")).readText().trim()
internal const val source = "10.20.168.217"
internal const val serverId = "11111111-1111-4111-8111-111111111111"
internal const val instanceId = "22222222-2222-4222-8222-222222222222"

internal fun announcement(index: Int = 0, port: Int = 8080): ByteArray {
    val id = "11111111-1111-4111-8111-${index.toString().padStart(12, '0')}"
    return fixture.replace(serverId, id).replace("8080", port.toString()).toByteArray()
}

internal class DiscoveryFixture {
    var now = 0L
    val coordinator = DiscoveryCoordinator { now }
    var network = coordinator.networkGeneration()
    fun receive(index: Int = 0, ip: String = source, port: Int = 8080): DiscoverySnapshot =
        coordinator.receive(network, announcement(index, port), ip)
    fun advance(millis: Long): DiscoverySnapshot { now += millis; return coordinator.snapshot() }
    fun select(): DiscoveryTarget { receive(); return requireNotNull(advance(3000).target) }
}
