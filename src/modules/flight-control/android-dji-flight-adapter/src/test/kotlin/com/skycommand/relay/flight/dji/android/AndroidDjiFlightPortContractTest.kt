package com.skycommand.relay.flight.dji.android

import com.skycommand.relay.flight.command.FlightAction
import com.skycommand.relay.flight.dji.FlightDjiCompletion
import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidDjiFlightPortContractTest {
    @Test
    fun mapsEachActionToItsDedicatedPlatformMethodAndDeliversOnlyOneCompletion() {
        val api = Api()
        val port = AndroidDjiFlightPort(api)
        val events = mutableListOf<String>()

        port.execute(FlightAction.TAKEOFF, completion(events))
        api.succeed(); api.fail()
        port.execute(FlightAction.LAND, completion(events)); api.succeed()
        port.execute(FlightAction.RETURN_HOME, completion(events)); api.fail()

        assertEquals(listOf("takeoff", "land", "return-home"), api.calls)
        assertEquals(listOf("ok", "ok", "fail"), events)
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
        override fun returnHome(completion: DjiFlightCompletion) = call("return-home", completion)
        private fun call(name: String, completion: DjiFlightCompletion) {
            if (throwOnCall) error("platform failure")
            calls += name; this.completion = completion
        }
        fun succeed() = checkNotNull(completion).succeed()
        fun fail() = checkNotNull(completion).fail()
    }
}
