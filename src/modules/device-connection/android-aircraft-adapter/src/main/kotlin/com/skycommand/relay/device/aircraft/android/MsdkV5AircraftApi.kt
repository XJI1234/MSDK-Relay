package com.skycommand.relay.device.aircraft.android

import android.util.Log
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.product.ProductType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
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
    private val airLinkKey = KeyTools.createKey(AirLinkKey.KeyConnection)
    private val cameraKey = KeyTools.createKey(CameraKey.KeyConnection, ComponentIndexType.LEFT_OR_MAIN)
    private val flightControllerKey = KeyTools.createKey(FlightControllerKey.KeyConnection)
    private val productTypeKey = KeyTools.createKey(ProductKey.KeyProductType)
    private var active = true
    private var aircraftConnected: Boolean? = null
    private var airLinkConnected: Boolean? = null
    private var cameraConnected: Boolean? = null
    private var flightControllerConnected: Boolean? = null
    private var productType = ProductType.UNKNOWN
    private val connectionEventRevisions = mutableMapOf<ConnectionKey, Long>()
    private var productTypeEventRevision = 0L
    private var productTypeReadGeneration = 0L

    fun start() {
        try {
            manager.listen(aircraftKey, owner) { previous, next ->
                publishConnection(
                    key = ConnectionKey.PRODUCT,
                    previousValue = previous,
                    nextValue = next,
                )
            }
            manager.listen(airLinkKey, owner) { previous, next ->
                publishConnection(
                    key = ConnectionKey.AIR_LINK,
                    previousValue = previous,
                    nextValue = next,
                )
            }
            manager.listen(cameraKey, owner) { previous, next ->
                publishConnection(
                    key = ConnectionKey.CAMERA,
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
            requestInitialConnection(aircraftKey, ConnectionKey.PRODUCT)
            requestInitialConnection(airLinkKey, ConnectionKey.AIR_LINK)
            requestInitialConnection(cameraKey, ConnectionKey.CAMERA)
            requestInitialConnection(flightControllerKey, ConnectionKey.FLIGHT_CONTROLLER)
            requestInitialProductType()
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
        val update = synchronized(lock) {
            if (!active) {
                null
            } else {
                val wasConnected = connectionValue(key)
                connectionEventRevisions[key] = (connectionEventRevisions[key] ?: 0L) + 1L
                setConnection(key, nextValue)
                val productTypeMustBeRefreshed = key == ConnectionKey.PRODUCT && wasConnected != true && nextValue == true
                if (key == ConnectionKey.PRODUCT && wasConnected != nextValue) {
                    productTypeReadGeneration += 1L
                    productType = ProductType.UNKNOWN
                }
                ConnectionUpdate(currentFact(), productTypeMustBeRefreshed)
            }
        }
        recordLinkDiagnostic(
            "$LINK_DIAGNOSTIC_PREFIX event=key-change key=${key.diagnosticName} old=$previousValue new=$nextValue",
        )
        update?.fact?.let(listener::onChanged)
        if (update?.productTypeMustBeRefreshed == true) requestInitialProductType()
    }

    private fun publishProductType(nextProductType: ProductType) {
        val fact = update {
            productTypeEventRevision += 1L
            productType = if (aircraftConnected == true) nextProductType else ProductType.UNKNOWN
        }
        fact?.let(listener::onChanged)
    }

    private fun requestInitialConnection(djiKey: DJIKey<Boolean>, key: ConnectionKey) {
        val initialEventRevision = synchronized(lock) {
            if (!active) return
            connectionEventRevisions[key] ?: 0L
        }
        requestInitialValue(djiKey, key.diagnosticName) { initialValue ->
            val update = synchronized(lock) {
                if (!active || connectionEventRevisions[key] != initialEventRevision) {
                    null
                } else {
                    val wasConnected = connectionValue(key)
                    setConnection(key, initialValue)
                    val productTypeMustBeRefreshed = key == ConnectionKey.PRODUCT && wasConnected != true && initialValue == true
                    if (key == ConnectionKey.PRODUCT && wasConnected != initialValue) {
                        productTypeReadGeneration += 1L
                        productType = ProductType.UNKNOWN
                    }
                    ConnectionUpdate(currentFact(), productTypeMustBeRefreshed)
                }
            }
            update?.fact?.let(listener::onChanged)
            if (update?.productTypeMustBeRefreshed == true) requestInitialProductType()
        }
    }

    private fun requestInitialProductType() {
        val request = synchronized(lock) {
            if (!active) return
            productTypeReadGeneration += 1L
            ProductTypeReadRequest(productTypeEventRevision, productTypeReadGeneration)
        }
        requestInitialValue(productTypeKey, "ProductKey.KeyProductType") { initialValue ->
            val fact = synchronized(lock) {
                if (
                    !active ||
                    productTypeEventRevision != request.initialEventRevision ||
                    productTypeReadGeneration != request.initialReadGeneration
                ) {
                    null
                } else {
                    productType = if (aircraftConnected == true) {
                        initialValue ?: ProductType.UNKNOWN
                    } else {
                        ProductType.UNKNOWN
                    }
                    currentFact()
                }
            }
            fact?.let(listener::onChanged)
        }
    }

    private fun <T> requestInitialValue(
        key: DJIKey<T>,
        diagnosticName: String,
        onSuccess: (T?) -> Unit,
    ) {
        manager.getValue(key, object : CommonCallbacks.CompletionCallbackWithParam<T> {
            override fun onSuccess(value: T) {
                onSuccess(value)
            }

            override fun onFailure(error: IDJIError) {
                recordLinkDiagnostic("$LINK_DIAGNOSTIC_PREFIX event=hardware-read-failure key=$diagnosticName")
            }
        })
    }

    private fun setConnection(key: ConnectionKey, value: Boolean?) {
        when (key) {
            ConnectionKey.PRODUCT -> aircraftConnected = value
            ConnectionKey.AIR_LINK -> airLinkConnected = value
            ConnectionKey.CAMERA -> cameraConnected = value
            ConnectionKey.FLIGHT_CONTROLLER -> flightControllerConnected = value
        }
    }

    private fun connectionValue(key: ConnectionKey): Boolean? = when (key) {
        ConnectionKey.PRODUCT -> aircraftConnected
        ConnectionKey.AIR_LINK -> airLinkConnected
        ConnectionKey.CAMERA -> cameraConnected
        ConnectionKey.FLIGHT_CONTROLLER -> flightControllerConnected
    }

    private fun currentFact(): DjiAircraftFact = DjiAircraftFact(
        aircraftConnected = aircraftConnected,
        airLinkConnected = airLinkConnected,
        cameraConnected = cameraConnected,
        flightControllerConnected = flightControllerConnected,
        displayModel = if (aircraftConnected == true) productType.toDisplayModel() else null,
    )

    private fun update(transform: KeyManagerObservation.() -> Unit): DjiAircraftFact? = synchronized(lock) {
        if (!active) {
            null
        } else {
            transform(this)
            currentFact()
        }
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
        AIR_LINK("AirLinkKey.KeyConnection"),
        CAMERA("CameraKey.KeyConnection(LEFT_OR_MAIN)"),
        FLIGHT_CONTROLLER("FlightControllerKey.KeyConnection"),
    }

    private fun recordLinkDiagnostic(message: String) {
        runCatching { Log.i(LINK_DIAGNOSTIC_TAG, message) }
    }

    private data class ConnectionUpdate(
        val fact: DjiAircraftFact,
        val productTypeMustBeRefreshed: Boolean,
    )

    private data class ProductTypeReadRequest(
        val initialEventRevision: Long,
        val initialReadGeneration: Long,
    )

    private companion object {
        const val LINK_DIAGNOSTIC_TAG = "SCLinkDiag"
        const val LINK_DIAGNOSTIC_PREFIX = "[DEBUG-link-order]"
    }
}
