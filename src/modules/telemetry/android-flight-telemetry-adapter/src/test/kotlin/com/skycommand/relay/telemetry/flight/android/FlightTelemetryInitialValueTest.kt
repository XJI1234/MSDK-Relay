package com.skycommand.relay.telemetry.flight.android

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlightTelemetryInitialValueTest {
    @Test
    fun firstHardwareReadPublishesAConfirmationRequestWithoutAnEarlierListenEvent() {
        val fixture = Fixture()
        fixture.read().onSuccess(true)

        assertEquals(true, fixture.fact.landingConfirmationNeeded)
        assertEquals(listOf(true), fixture.published.map { it.landingConfirmationNeeded })
        assertEquals(listOf<Boolean?>(true), fixture.accepted)
    }

    @Test
    fun firstHardwareReadPreservesFalseInsteadOfLeavingItUnknown() {
        val fixture = Fixture()
        fixture.read().onSuccess(false)

        assertEquals(false, fixture.fact.landingConfirmationNeeded)
        assertEquals(listOf<Boolean?>(false), fixture.accepted)
    }

    @Test
    fun firstNullValueRemainsUnknown() {
        val fixture = Fixture()
        fixture.read().onSuccess(null)

        assertEquals(null, fixture.fact.landingConfirmationNeeded)
        assertEquals(1, fixture.published.size)
    }

    @Test
    fun newerFalseEventWinsOverASlowTrueRead() {
        val fixture = Fixture()
        val read = fixture.read()
        fixture.event(false)
        read.onSuccess(true)

        assertEquals(false, fixture.fact.landingConfirmationNeeded)
        assertTrue(fixture.published.isEmpty())
        assertTrue(fixture.accepted.isEmpty())
    }

    @Test
    fun newerTrueEventWinsOverASlowFalseRead() {
        val fixture = Fixture()
        val read = fixture.read()
        fixture.event(true)
        read.onSuccess(false)

        assertEquals(true, fixture.fact.landingConfirmationNeeded)
        assertTrue(fixture.accepted.isEmpty())
    }

    @Test
    fun anUnrelatedKeyEventDoesNotSuppressTheFirstConfirmationRead() {
        val fixture = Fixture()
        val read = fixture.read()
        fixture.revisions["motorsOn"] = 1L
        fixture.fact = fixture.fact.copy(motorsOn = true)
        read.onSuccess(true)

        assertEquals(true, fixture.fact.landingConfirmationNeeded)
        assertEquals(true, fixture.fact.motorsOn)
    }

    @Test
    fun readingAfterAnEarlierEventIsAcceptedIfNoNewerEventArrives() {
        val fixture = Fixture()
        fixture.event(false)
        fixture.read().onSuccess(true)

        assertEquals(true, fixture.fact.landingConfirmationNeeded)
    }

    @Test
    fun invalidationAndCloseDiscardLateHardwareReads() {
        val fixture = Fixture()
        val read = fixture.read()
        fixture.current = false
        read.onSuccess(true)

        assertEquals(null, fixture.fact.landingConfirmationNeeded)
        assertTrue(fixture.published.isEmpty())
        assertTrue(fixture.accepted.isEmpty())
    }

    @Test
    fun refreshedGenerationAcceptsItsFirstReadAndIgnoresThePreviousGeneration() {
        val fixture = Fixture()
        val oldRead = fixture.read()
        fixture.generation += 1
        fixture.read().onSuccess(true)
        oldRead.onSuccess(false)

        assertEquals(true, fixture.fact.landingConfirmationNeeded)
        assertEquals(listOf<Boolean?>(true), fixture.accepted)
    }

    private class Fixture {
        val revisions = mutableMapOf<String, Long>()
        var fact = FlightTelemetryFact()
        var current = true
        var generation = 1
        val published = mutableListOf<FlightTelemetryFact>()
        val accepted = mutableListOf<Boolean?>()
        private val lock = Any()

        fun read(): FlightTelemetryInitialValue<String, Boolean?> {
            val requestGeneration = generation
            return FlightTelemetryInitialValue(
                lock, revisions, "confirmation",
                isCurrent = { current && requestGeneration == generation },
                transform = { value: Boolean? -> fact.copy(landingConfirmationNeeded = value).also { fact = it } },
                publish = { published.add(it) },
                afterAccepted = { accepted.add(it) },
            )
        }

        fun event(value: Boolean) {
            revisions["confirmation"] = (revisions["confirmation"] ?: 0L) + 1
            fact = fact.copy(landingConfirmationNeeded = value)
        }
    }
}
