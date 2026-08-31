package com.skycommand.relay.telemetry.flight.android

import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.flightcontroller.FCFlightMode
import dji.sdk.keyvalue.value.flightcontroller.LowBatteryRTHInfo
import dji.sdk.keyvalue.value.flightcontroller.LowBatteryRTHState
import dji.v5.manager.KeyManager
import com.skycommand.relay.telemetry.snapshot.LowBatteryRthState

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
        ComponentIndexType.LEFT_OR_MAIN,
    )
    private val remainingFlightTimeKey = KeyTools.createKey(FlightControllerKey.KeyLowBatteryRTHInfo)
    private val altitudeKey = KeyTools.createKey(FlightControllerKey.KeyAltitude)
    private val locationKey = KeyTools.createKey(FlightControllerKey.KeyAircraftLocation)
    private var active = true
    private var initializing = true
    private var fact = FlightTelemetryFact()
    private val pendingInitialUpdates = mutableListOf<FlightTelemetryFact.() -> FlightTelemetryFact>()

    fun start() {
        try {
            manager.listen(isFlyingKey, owner, false) { _, next -> update { copy(isFlying = next) } }
            manager.listen(motorsOnKey, owner, false) { _, next -> update { copy(motorsOn = next) } }
            manager.listen(flightModeKey, owner, false) { _, next -> update { copy(flightMode = next.toStableName()) } }
            manager.listen(batteryKey, owner, false) { _, next -> update { copy(batteryPercent = next) } }
            manager.listen(remainingFlightTimeKey, owner, false) { _, next ->
                val rth = next.toRthFact()
                update { copy(lowBatteryRthState = rth.state, remainingFlightTimeSeconds = rth.remainingFlightTimeSeconds) }
            }
            manager.listen(altitudeKey, owner, false) { _, next -> update { copy(altitudeMeters = next) } }
            manager.listen(locationKey, owner, false) { _, next -> update { withLocation(next) } }
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
        val rth = manager.getValue<LowBatteryRTHInfo>(remainingFlightTimeKey).toRthFact()
        val initial = FlightTelemetryFact(
            isFlying = manager.getValue(isFlyingKey),
            motorsOn = manager.getValue(motorsOnKey),
            flightMode = manager.getValue<FCFlightMode>(flightModeKey).toStableName(),
            batteryPercent = manager.getValue(batteryKey),
            remainingFlightTimeSeconds = rth.remainingFlightTimeSeconds,
            altitudeMeters = manager.getValue(altitudeKey),
            lowBatteryRthState = rth.state,
        ).withLocation(manager.getValue(locationKey))
        val next = synchronized(lock) {
            if (!active) null else {
                fact = pendingInitialUpdates.fold(initial) { current, transform -> current.transform() }
                pendingInitialUpdates.clear()
                initializing = false
                fact
            }
        }
        next?.let(listener::onChanged)
    }

    private fun update(transform: FlightTelemetryFact.() -> FlightTelemetryFact) {
        val next = synchronized(lock) {
            when {
                !active -> null
                initializing -> {
                    pendingInitialUpdates += transform
                    null
                }
                else -> fact.transform().also { fact = it }
            }
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

    private fun LowBatteryRTHInfo?.toRthFact(): LowBatteryRthFact {
        val state = when (this?.lowBatteryRTHStatus) {
            LowBatteryRTHState.IDLE -> LowBatteryRthState.IDLE
            LowBatteryRTHState.COUNTING_DOWN -> LowBatteryRthState.COUNTING_DOWN
            LowBatteryRTHState.EXECUTED -> LowBatteryRthState.EXECUTED
            LowBatteryRTHState.CANCELLED -> LowBatteryRthState.CANCELLED
            else -> null
        }
        return LowBatteryRthFact(
            state = state,
            remainingFlightTimeSeconds = this?.remainingFlightTime?.takeIf { state != null && it in 1..86_400 },
        )
    }

    private data class LowBatteryRthFact(
        val state: LowBatteryRthState?,
        val remainingFlightTimeSeconds: Int?,
    )
}
