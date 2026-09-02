package com.skycommand.relay.flight.dji.android

import com.skycommand.relay.flight.command.FlightAction
import com.skycommand.relay.flight.dji.FlightDjiCompletion
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidDjiFlightPortContractTest {
    @Test
    fun mapsEachActionToItsDedicatedPlatformMethodAndDeliversOnlyOneCompletion() {
        val api = Api()
        val port = AndroidDjiFlightPort(api)
        val events = mutableListOf<String>()

        port.execute(FlightAction.TAKEOFF, completion(events))
        api.succeed(); api.fail()
        port.execute(FlightAction.LAND, completion(events)); api.succeed()
        port.execute(FlightAction.CONFIRM_LANDING, completion(events)); api.succeed()
        port.execute(FlightAction.RETURN_HOME, completion(events)); api.fail()
        port.execute(FlightAction.STOP_TAKEOFF, completion(events)); api.succeed()
        port.execute(FlightAction.STOP_AUTO_LANDING, completion(events)); api.fail()

        assertEquals(listOf("takeoff", "land", "confirm-landing", "return-home", "stop-takeoff", "stop-auto-landing"), api.calls)
        assertEquals(listOf("ok", "ok", "ok", "fail", "ok", "fail"), events)
    }

    @Test
    fun failsSynchronouslyThrownPlatformCallsAndDropsCallbacksAfterClose() {
        val api = Api()
        val port = AndroidDjiFlightPort(api)
        val events = mutableListOf<String>()
        api.throwOnCall = true
        port.execute(FlightAction.TAKEOFF, completion(events))
        api.throwOnCall = false
        port.execute(FlightAction.LAND, completion(events))
        port.close()
        api.succeed()

        assertEquals(listOf("fail"), events)
    }

    @Test
    fun bindsRecoveryActionsToTheirDedicatedMsdkActionKeys() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/flight/dji/android/MsdkV5FlightApi.kt"),
            Path("src/modules/flight-control/android-dji-flight-adapter/src/main/kotlin/com/skycommand/relay/flight/dji/android/MsdkV5FlightApi.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("perform(FlightControllerKey.KeyStopTakeoff, completion)"))
        assertTrue(source.contains("perform(FlightControllerKey.KeyStopAutoLanding, completion)"))
        assertTrue(source.contains("perform(FlightControllerKey.KeyConfirmLanding, completion)"))
    }

    private fun completion(events: MutableList<String>) = object : FlightDjiCompletion {
        override fun succeed() { events += "ok" }
        override fun fail() { events += "fail" }
    }

    private class Api : DjiFlightApi {
        val calls = mutableListOf<String>()
        var throwOnCall = false
        private var completion: DjiFlightCompletion? = null
        override fun takeoff(completion: DjiFlightCompletion) = call("takeoff", completion)
        override fun land(completion: DjiFlightCompletion) = call("land", completion)
        override fun confirmLanding(completion: DjiFlightCompletion) = call("confirm-landing", completion)
        override fun returnHome(completion: DjiFlightCompletion) = call("return-home", completion)
        override fun stopTakeoff(completion: DjiFlightCompletion) = call("stop-takeoff", completion)
        override fun stopAutoLanding(completion: DjiFlightCompletion) = call("stop-auto-landing", completion)
        private fun call(name: String, completion: DjiFlightCompletion) {
            if (throwOnCall) error("platform failure")
            calls += name; this.completion = completion
        }
        fun succeed() = checkNotNull(completion).succeed()
        fun fail() = checkNotNull(completion).fail()
    }
}
