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
import com.skycommand.relay.device.state.LinkState
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
    private val flightControllerOwner = Any()
    private val batteryOwner = Any()
    private val isFlyingKey = KeyTools.createKey(FlightControllerKey.KeyIsFlying)
    private val motorsOnKey = KeyTools.createKey(FlightControllerKey.KeyAreMotorsOn)
    private val flightModeKey = KeyTools.createKey(FlightControllerKey.KeyFCFlightMode)
    private val batteryKey = KeyTools.createKey(
        BatteryKey.KeyChargeRemainingInPercent,
        ComponentIndexType.LEFT_OR_MAIN,
    )
    private val batteryConnectionKey = KeyTools.createKey(
        BatteryKey.KeyConnection,
        ComponentIndexType.LEFT_OR_MAIN,
    )
    private val remainingFlightTimeKey = KeyTools.createKey(FlightControllerKey.KeyLowBatteryRTHInfo)
    private val altitudeKey = KeyTools.createKey(FlightControllerKey.KeyAltitude)
    private val locationKey = KeyTools.createKey(FlightControllerKey.KeyAircraftLocation)
    private var active = true
    private var fact = FlightTelemetryFact()
    private var flightControllerGeneration = 0L
    private val eventRevisions = mutableMapOf<ObservedKey, Long>()

    fun start() {
        try {
            startBatteryObservation()
            startFlightControllerObservation(beginFlightControllerObservationGeneration())
        } catch (failure: Throwable) {
            runCatching { manager.cancelListen(flightControllerOwner) }
            runCatching { manager.cancelListen(batteryOwner) }
            synchronized(lock) { active = false }
            throw failure
        }
    }

    override fun invalidateFlightControllerFacts() {
        val next = synchronized(lock) {
            if (!active) null else {
                flightControllerGeneration += 1L
                fact.withoutFlightControllerFacts().also { fact = it }
            }
        }
        runCatching { manager.cancelListen(flightControllerOwner) }
        next?.let(listener::onChanged)
    }

    override fun refreshFlightControllerFacts() {
        val generation = beginFlightControllerObservationGeneration()
        runCatching { manager.cancelListen(flightControllerOwner) }
        factForCurrentGeneration()?.let(listener::onChanged)
        requestBatteryFacts()
        runCatching { startFlightControllerObservation(generation) }
    }

    private fun beginFlightControllerObservationGeneration(): Long {
        return synchronized(lock) {
            check(active) { "Flight telemetry observation is closed" }
            flightControllerGeneration += 1L
            fact.withoutFlightControllerFacts().also { fact = it }
            flightControllerGeneration
        }
    }

    override fun close() {
        val shouldClose = synchronized(lock) { active.also { active = false } }
        if (shouldClose) {
            manager.cancelListen(flightControllerOwner)
            manager.cancelListen(batteryOwner)
        }
    }

    private fun startBatteryObservation() {
        manager.listen(batteryConnectionKey, batteryOwner) { _, next ->
            val connection = next.toLinkState()
            publishEvent(ObservedKey.BATTERY_CONNECTION) {
                copy(battery = connection, batteryPercent = if (connection == LinkState.CONNECTED) batteryPercent else null)
            }
            if (connection == LinkState.CONNECTED) requestInitialBatteryPercent()
        }
        manager.listen(batteryKey, batteryOwner) { _, next ->
            publishEvent(ObservedKey.BATTERY) {
                copy(batteryPercent = if (battery == LinkState.CONNECTED) next else null)
            }
        }
        requestBatteryFacts()
    }

    private fun requestBatteryFacts() {
        requestInitialValue(
            batteryConnectionKey,
            ObservedKey.BATTERY_CONNECTION,
            afterAccepted = { connection -> if (connection == true) requestInitialBatteryPercent() },
        ) { current, value ->
            val connection = value.toLinkState()
            current.copy(battery = connection, batteryPercent = if (connection == LinkState.CONNECTED) current.batteryPercent else null)
        }
        requestInitialBatteryPercent()
    }

    private fun requestInitialBatteryPercent() {
        val connectionRevision = synchronized(lock) {
            if (!active || fact.battery != LinkState.CONNECTED) return
            eventRevisions[ObservedKey.BATTERY_CONNECTION] ?: 0L
        }
        requestInitialValue(
            batteryKey,
            ObservedKey.BATTERY,
            isCurrent = {
                fact.battery == LinkState.CONNECTED &&
                    (eventRevisions[ObservedKey.BATTERY_CONNECTION] ?: 0L) == connectionRevision
            },
        ) { current, value ->
            current.copy(batteryPercent = if (current.battery == LinkState.CONNECTED) value else null)
        }
    }

    private fun startFlightControllerObservation(generation: Long) {
        try {
            manager.listen(isFlyingKey, flightControllerOwner) { _, next ->
                publishEvent(ObservedKey.IS_FLYING, generation) { copy(isFlying = next) }
            }
            manager.listen(motorsOnKey, flightControllerOwner) { _, next ->
                publishEvent(ObservedKey.MOTORS_ON, generation) { copy(motorsOn = next) }
            }
            manager.listen(flightModeKey, flightControllerOwner) { _, next ->
                publishEvent(ObservedKey.FLIGHT_MODE, generation) { copy(flightMode = next.toStableName()) }
            }
            manager.listen(remainingFlightTimeKey, flightControllerOwner) { _, next ->
                val rth = next.toRthFact()
                publishEvent(ObservedKey.LOW_BATTERY_RTH, generation) {
                    copy(lowBatteryRthState = rth.state, remainingFlightTimeSeconds = rth.remainingFlightTimeSeconds)
                }
            }
            manager.listen(altitudeKey, flightControllerOwner) { _, next ->
                publishEvent(ObservedKey.ALTITUDE, generation) { copy(altitudeMeters = next) }
            }
            manager.listen(locationKey, flightControllerOwner) { _, next ->
                publishEvent(ObservedKey.LOCATION, generation) { withLocation(next) }
            }
            requestInitialValue(isFlyingKey, ObservedKey.IS_FLYING, isCurrent = { flightControllerGeneration == generation }) { current, value ->
                current.copy(isFlying = value)
            }
            requestInitialValue(motorsOnKey, ObservedKey.MOTORS_ON, isCurrent = { flightControllerGeneration == generation }) { current, value ->
                current.copy(motorsOn = value)
            }
            requestInitialValue(flightModeKey, ObservedKey.FLIGHT_MODE, isCurrent = { flightControllerGeneration == generation }) { current, value ->
                current.copy(flightMode = value.toStableName())
            }
            requestInitialValue(remainingFlightTimeKey, ObservedKey.LOW_BATTERY_RTH, isCurrent = { flightControllerGeneration == generation }) { current, value ->
                val rth = value.toRthFact()
                current.copy(lowBatteryRthState = rth.state, remainingFlightTimeSeconds = rth.remainingFlightTimeSeconds)
            }
            requestInitialValue(altitudeKey, ObservedKey.ALTITUDE, isCurrent = { flightControllerGeneration == generation }) { current, value ->
                current.copy(altitudeMeters = value)
            }
            requestInitialValue(locationKey, ObservedKey.LOCATION, isCurrent = { flightControllerGeneration == generation }) { current, value ->
                current.withLocation(value)
            }
        } catch (failure: Throwable) {
            runCatching { manager.cancelListen(flightControllerOwner) }
            throw failure
        }
    }

    private fun publishEvent(
        observedKey: ObservedKey,
        flightGeneration: Long? = null,
        transform: FlightTelemetryFact.() -> FlightTelemetryFact,
    ) {
        val next = synchronized(lock) {
            if (!active || (flightGeneration != null && flightControllerGeneration != flightGeneration)) {
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
        isCurrent: () -> Boolean = { true },
        afterAccepted: ((T?) -> Unit)? = null,
        transform: (FlightTelemetryFact, T?) -> FlightTelemetryFact,
    ) {
        val initialEventRevision = synchronized(lock) {
            if (!active) return
            eventRevisions[observedKey] ?: 0L
        }
        manager.getValue(key, object : CommonCallbacks.CompletionCallbackWithParam<T> {
            override fun onSuccess(value: T) {
                val next = synchronized(lock) {
                    if (!active || !isCurrent() || eventRevisions[observedKey] != initialEventRevision) {
                        null
                    } else {
                        transform(fact, value).also { fact = it }
                    }
                }
                next?.let(listener::onChanged)
                if (next != null) afterAccepted?.invoke(value)
            }

            override fun onFailure(error: IDJIError) = Unit
        })
    }

    private fun FlightTelemetryFact.withLocation(location: LocationCoordinate2D?): FlightTelemetryFact = copy(
        latitude = location?.latitude,
        longitude = location?.longitude,
    )

    private fun FlightTelemetryFact.withoutFlightControllerFacts(): FlightTelemetryFact = copy(
        isFlying = null,
        motorsOn = null,
        flightMode = null,
        remainingFlightTimeSeconds = null,
        altitudeMeters = null,
        latitude = null,
        longitude = null,
        lowBatteryRthState = null,
    )

    private fun Boolean?.toLinkState(): LinkState = when (this) {
        true -> LinkState.CONNECTED
        false -> LinkState.DISCONNECTED
        null -> LinkState.UNKNOWN
    }

    private fun factForCurrentGeneration(): FlightTelemetryFact? = synchronized(lock) {
        fact.takeIf { active }
    }

    private fun FCFlightMode?.toStableName(): String? = this?.name

    private fun LowBatteryRTHInfo?.toRthFact(): LowBatteryRthFact {
        val state = when (this?.lowBatteryRTHStatus) {
            LowBatteryRTHState.IDLE -> LowBatteryRthState.IDLE
            LowBatteryRTHState.COUNTING_DOWN -> LowBatteryRthState.COUNTING_DOWN
            LowBatteryRTHState.EXECUTED -> LowBatteryRthState.EXECUTED
            LowBatteryRTHState.CANCELLED -> LowBatteryRthState.CANCELLED
            LowBatteryRTHState.UNKNOWN -> LowBatteryRthState.UNKNOWN
            else -> null
        }
        return LowBatteryRthFact(
            state = state,
            remainingFlightTimeSeconds = this?.remainingFlightTime?.takeIf { state != null && state != LowBatteryRthState.UNKNOWN && it in 1..86_400 },
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
        BATTERY_CONNECTION,
        BATTERY,
        LOW_BATTERY_RTH,
        ALTITUDE,
        LOCATION,
    }

}
