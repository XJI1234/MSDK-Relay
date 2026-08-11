package com.skycommand.relay.telemetry.flight.android

import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.flightcontroller.FCFlightMode
import dji.sdk.keyvalue.value.flightcontroller.LowBatteryRTHInfo
import dji.v5.manager.KeyManager

internal class MsdkV5FlightTelemetryApi(
    private val manager: KeyManager = KeyManager.getInstance(),
) : DjiFlightTelemetryApi {
    override fun observe(listener: DjiFlightTelemetryListener): DjiFlightTelemetryObservation {
        val observation = KeyManagerFlightTelemetryObservation(manager, listener)
        observation.start()
        return observation
    }
}

private class KeyManagerFlightTelemetryObservation(
    private val manager: KeyManager,
    private val listener: DjiFlightTelemetryListener,
) : DjiFlightTelemetryObservation {
    private val lock = Any()
    private val owner = Any()
    private val isFlyingKey = KeyTools.createKey(FlightControllerKey.KeyIsFlying)
    private val motorsOnKey = KeyTools.createKey(FlightControllerKey.KeyAreMotorsOn)
    private val flightModeKey = KeyTools.createKey(FlightControllerKey.KeyFCFlightMode)
    private val batteryKey = KeyTools.createKey(
        BatteryKey.KeyChargeRemainingInPercent,
        ComponentIndexType.AGGREGATION,
    )
    private val remainingFlightTimeKey = KeyTools.createKey(FlightControllerKey.KeyLowBatteryRTHInfo)
    private val altitudeKey = KeyTools.createKey(FlightControllerKey.KeyAltitude)
    private val locationKey = KeyTools.createKey(FlightControllerKey.KeyAircraftLocation)
    private var active = true
    private var fact = FlightTelemetryFact()

    fun start() {
        try {
            manager.listen(isFlyingKey, owner) { _, next -> update { copy(isFlying = next) } }
            manager.listen(motorsOnKey, owner) { _, next -> update { copy(motorsOn = next) } }
            manager.listen(flightModeKey, owner) { _, next -> update { copy(flightMode = next.toStableName()) } }
            manager.listen(batteryKey, owner) { _, next -> update { copy(batteryPercent = next) } }
            manager.listen(remainingFlightTimeKey, owner) { _, next ->
                update { copy(remainingFlightTimeSeconds = next?.remainingFlightTime) }
            }
            manager.listen(altitudeKey, owner) { _, next -> update { copy(altitudeMeters = next) } }
            manager.listen(locationKey, owner) { _, next -> update { withLocation(next) } }
            publishInitial()
        } catch (failure: Throwable) {
            runCatching { manager.cancelListen(owner) }
            synchronized(lock) { active = false }
            throw failure
        }
    }

    override fun close() {
        val shouldClose = synchronized(lock) { active.also { active = false } }
        if (shouldClose) manager.cancelListen(owner)
    }

    private fun publishInitial() {
        update {
            FlightTelemetryFact(
                isFlying = manager.getValue(isFlyingKey),
                motorsOn = manager.getValue(motorsOnKey),
                flightMode = manager.getValue<FCFlightMode>(flightModeKey).toStableName(),
                batteryPercent = manager.getValue(batteryKey),
                remainingFlightTimeSeconds = manager.getValue<LowBatteryRTHInfo>(remainingFlightTimeKey)?.remainingFlightTime,
                altitudeMeters = manager.getValue(altitudeKey),
            ).withLocation(manager.getValue(locationKey))
        }
    }

    private fun update(transform: FlightTelemetryFact.() -> FlightTelemetryFact) {
        val next = synchronized(lock) {
            if (!active) null else fact.transform().also { fact = it }
        }
        next?.let(listener::onChanged)
    }

    private fun FlightTelemetryFact.withLocation(location: LocationCoordinate2D?): FlightTelemetryFact = copy(
        latitude = location?.latitude,
        longitude = location?.longitude,
    )

    private fun FCFlightMode?.toStableName(): String? = this
        ?.takeUnless { it == FCFlightMode.UNKNOWN }
        ?.name
}
