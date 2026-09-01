package com.skycommand.relay.telemetry.flight.android

import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.LocationCoordinate2D
import dji.sdk.keyvalue.value.flightcontroller.FCFlightMode
import dji.sdk.keyvalue.value.flightcontroller.LowBatteryRTHInfo
import dji.sdk.keyvalue.value.flightcontroller.LowBatteryRTHState
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
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
    private var fact = FlightTelemetryFact()
    private val eventRevisions = mutableMapOf<ObservedKey, Long>()

    fun start() {
        try {
            manager.listen(isFlyingKey, owner) { _, next ->
                publishEvent(ObservedKey.IS_FLYING) { copy(isFlying = next) }
            }
            manager.listen(motorsOnKey, owner) { _, next ->
                publishEvent(ObservedKey.MOTORS_ON) { copy(motorsOn = next) }
            }
            manager.listen(flightModeKey, owner) { _, next ->
                publishEvent(ObservedKey.FLIGHT_MODE) { copy(flightMode = next.toStableName()) }
            }
            manager.listen(batteryKey, owner) { _, next ->
                publishEvent(ObservedKey.BATTERY) { copy(batteryPercent = next) }
            }
            manager.listen(remainingFlightTimeKey, owner) { _, next ->
                val rth = next.toRthFact()
                publishEvent(ObservedKey.LOW_BATTERY_RTH) {
                    copy(lowBatteryRthState = rth.state, remainingFlightTimeSeconds = rth.remainingFlightTimeSeconds)
                }
            }
            manager.listen(altitudeKey, owner) { _, next ->
                publishEvent(ObservedKey.ALTITUDE) { copy(altitudeMeters = next) }
            }
            manager.listen(locationKey, owner) { _, next ->
                publishEvent(ObservedKey.LOCATION) { withLocation(next) }
            }
            requestInitialValue(isFlyingKey, ObservedKey.IS_FLYING) { current, value ->
                current.copy(isFlying = value)
            }
            requestInitialValue(motorsOnKey, ObservedKey.MOTORS_ON) { current, value ->
                current.copy(motorsOn = value)
            }
            requestInitialValue(flightModeKey, ObservedKey.FLIGHT_MODE) { current, value ->
                current.copy(flightMode = value.toStableName())
            }
            requestInitialValue(batteryKey, ObservedKey.BATTERY) { current, value ->
                current.copy(batteryPercent = value)
            }
            requestInitialValue(remainingFlightTimeKey, ObservedKey.LOW_BATTERY_RTH) { current, value ->
                val rth = value.toRthFact()
                current.copy(lowBatteryRthState = rth.state, remainingFlightTimeSeconds = rth.remainingFlightTimeSeconds)
            }
            requestInitialValue(altitudeKey, ObservedKey.ALTITUDE) { current, value ->
                current.copy(altitudeMeters = value)
            }
            requestInitialValue(locationKey, ObservedKey.LOCATION) { current, value ->
                current.withLocation(value)
            }
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

    private fun publishEvent(
        observedKey: ObservedKey,
        transform: FlightTelemetryFact.() -> FlightTelemetryFact,
    ) {
        val next = synchronized(lock) {
            if (!active) {
                null
            } else {
                eventRevisions[observedKey] = (eventRevisions[observedKey] ?: 0L) + 1L
                fact.transform().also { fact = it }
            }
        }
        next?.let(listener::onChanged)
    }

    private fun <T> requestInitialValue(
        key: DJIKey<T>,
        observedKey: ObservedKey,
        transform: (FlightTelemetryFact, T?) -> FlightTelemetryFact,
    ) {
        val initialEventRevision = synchronized(lock) {
            if (!active) return
            eventRevisions[observedKey] ?: 0L
        }
        manager.getValue(key, object : CommonCallbacks.CompletionCallbackWithParam<T> {
            override fun onSuccess(value: T) {
                val next = synchronized(lock) {
                    if (!active || eventRevisions[observedKey] != initialEventRevision) {
                        null
                    } else {
                        transform(fact, value).also { fact = it }
                    }
                }
                next?.let(listener::onChanged)
            }

            override fun onFailure(error: IDJIError) = Unit
        })
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

    private enum class ObservedKey {
        IS_FLYING,
        MOTORS_ON,
        FLIGHT_MODE,
        BATTERY,
        LOW_BATTERY_RTH,
        ALTITUDE,
        LOCATION,
    }
}
