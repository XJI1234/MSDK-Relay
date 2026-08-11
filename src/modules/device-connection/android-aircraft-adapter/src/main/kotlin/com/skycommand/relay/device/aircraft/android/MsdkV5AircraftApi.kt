package com.skycommand.relay.device.aircraft.android

import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.product.ProductType
import dji.v5.manager.KeyManager

internal class MsdkV5AircraftApi(
    private val manager: KeyManager = KeyManager.getInstance(),
) : DjiAircraftApi {
    override fun observe(listener: DjiAircraftListener): DjiAircraftObservation {
        val observation = KeyManagerObservation(manager, listener)
        observation.start()
        return observation
    }
}

private class KeyManagerObservation(
    private val manager: KeyManager,
    private val listener: DjiAircraftListener,
) : DjiAircraftObservation {
    private val lock = Any()
    private val owner = Any()
    private val aircraftKey = KeyTools.createKey(ProductKey.KeyConnection)
    private val flightControllerKey = KeyTools.createKey(FlightControllerKey.KeyConnection)
    private val productTypeKey = KeyTools.createKey(ProductKey.KeyProductType)
    private var active = true
    private var aircraftConnected = false
    private var flightControllerConnected = false
    private var productType = ProductType.UNKNOWN

    fun start() {
        try {
            manager.listen(aircraftKey, owner) { _, next -> publish(next == true, null, null) }
            manager.listen(flightControllerKey, owner) { _, next -> publish(null, next == true, null) }
            manager.listen(productTypeKey, owner) { _, next ->
                publish(null, null, next ?: ProductType.UNKNOWN)
            }
            publish(
                manager.getValue(aircraftKey, false),
                manager.getValue(flightControllerKey, false),
                manager.getValue(productTypeKey, ProductType.UNKNOWN),
            )
        } catch (failure: Throwable) {
            runCatching { manager.cancelListen(owner) }
            throw failure
        }
    }

    override fun close() {
        val shouldClose = synchronized(lock) { active.also { active = false } }
        if (shouldClose) manager.cancelListen(owner)
    }

    private fun publish(
        nextAircraftConnected: Boolean?,
        nextFlightControllerConnected: Boolean?,
        nextProductType: ProductType?,
    ) {
        val fact = synchronized(lock) {
            if (!active) {
                null
            } else {
                nextAircraftConnected?.let { aircraftConnected = it }
                nextFlightControllerConnected?.let { flightControllerConnected = it }
                nextProductType?.let { productType = it }
                DjiAircraftFact(
                    aircraftConnected = aircraftConnected,
                    flightControllerConnected = aircraftConnected && flightControllerConnected,
                    displayModel = if (aircraftConnected) productType.toDisplayModel() else null,
                )
            }
        }
        fact?.let { listener.onChanged(it) }
    }

    private fun ProductType.toDisplayModel(): String? = when (this) {
        ProductType.UNKNOWN,
        ProductType.UNRECOGNIZED,
        -> null

        else -> name
            .takeUnless { it.startsWith("NOT_SUPPORTED") }
            ?.replace('_', ' ')
    }
}
