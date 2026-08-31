package com.skycommand.relay.device.aircraft.android

import android.util.Log
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
    private var initializing = true
    private var aircraftConnected: Boolean? = null
    private var flightControllerConnected: Boolean? = null
    private var productType = ProductType.UNKNOWN
    private val pendingInitialUpdates = mutableListOf<KeyManagerObservation.() -> Unit>()

    fun start() {
        try {
            manager.listen(aircraftKey, owner) { previous, next ->
                publishConnection(
                    key = ConnectionKey.PRODUCT,
                    previousValue = previous,
                    nextValue = next,
                )
            }
            manager.listen(flightControllerKey, owner) { previous, next ->
                publishConnection(
                    key = ConnectionKey.FLIGHT_CONTROLLER,
                    previousValue = previous,
                    nextValue = next,
                )
            }
            manager.listen(productTypeKey, owner) { _, next ->
                publishProductType(next ?: ProductType.UNKNOWN)
            }
            publishInitialFact()
        } catch (failure: Throwable) {
            runCatching { manager.cancelListen(owner) }
            throw failure
        }
    }

    override fun close() {
        val shouldClose = synchronized(lock) { active.also { active = false } }
        if (shouldClose) manager.cancelListen(owner)
    }

    private fun publishConnection(
        key: ConnectionKey,
        previousValue: Boolean?,
        nextValue: Boolean?,
    ) {
        val fact = update {
            when (key) {
                ConnectionKey.PRODUCT -> aircraftConnected = nextValue
                ConnectionKey.FLIGHT_CONTROLLER -> flightControllerConnected = nextValue
            }
        }
        recordLinkDiagnostic(
            "$LINK_DIAGNOSTIC_PREFIX event=key-change key=${key.diagnosticName} old=$previousValue new=$nextValue",
        )
        fact?.let(listener::onChanged)
    }

    private fun publishProductType(nextProductType: ProductType) {
        val fact = update { productType = nextProductType }
        fact?.let(listener::onChanged)
    }

    private fun currentFact(): DjiAircraftFact = DjiAircraftFact(
        aircraftConnected = aircraftConnected,
        flightControllerConnected = normalizedFlightControllerConnection(
            aircraftConnected,
            flightControllerConnected,
        ),
        displayModel = if (aircraftConnected == true) productType.toDisplayModel() else null,
    )

    private fun update(transform: KeyManagerObservation.() -> Unit): DjiAircraftFact? = synchronized(lock) {
        when {
            !active -> null
            initializing -> {
                pendingInitialUpdates += transform
                null
            }
            else -> {
                transform(this)
                currentFact()
            }
        }
    }

    private fun publishInitialFact() {
        val product = runCatching { manager.getValue<Boolean>(aircraftKey) }.getOrNull()
        val flightController = runCatching { manager.getValue<Boolean>(flightControllerKey) }.getOrNull()
        val initialProductType = runCatching { manager.getValue<ProductType>(productTypeKey) }.getOrNull()
            ?: ProductType.UNKNOWN
        val fact = synchronized(lock) {
            if (!active) null else {
                aircraftConnected = product
                flightControllerConnected = flightController
                productType = initialProductType
                pendingInitialUpdates.forEach { update -> update(this) }
                pendingInitialUpdates.clear()
                initializing = false
                currentFact()
            }
        }
        recordLinkDiagnostic(
            "$LINK_DIAGNOSTIC_PREFIX event=initial-read product=$product flightController=$flightController",
        )
        fact?.let(listener::onChanged)
    }

    private fun ProductType.toDisplayModel(): String? = when (this) {
        ProductType.UNKNOWN,
        ProductType.UNRECOGNIZED,
        -> null

        else -> name
            .takeUnless { it.startsWith("NOT_SUPPORTED") }
            ?.replace('_', ' ')
    }

    private enum class ConnectionKey(val diagnosticName: String) {
        PRODUCT("ProductKey.KeyConnection"),
        FLIGHT_CONTROLLER("FlightControllerKey.KeyConnection"),
    }

    private fun recordLinkDiagnostic(message: String) {
        runCatching { Log.i(LINK_DIAGNOSTIC_TAG, message) }
    }

    private companion object {
        const val LINK_DIAGNOSTIC_TAG = "SCLinkDiag"
        const val LINK_DIAGNOSTIC_PREFIX = "[DEBUG-link-order]"
    }
}
