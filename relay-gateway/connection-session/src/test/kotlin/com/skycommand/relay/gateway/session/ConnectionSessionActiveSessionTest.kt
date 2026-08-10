package com.skycommand.relay.gateway.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConnectionSessionActiveSessionTest {

    @Test
    fun exposesTheCurrentActiveSessionOnlyWhileTheGenerationIsActive() {
        val fixture = SessionFixture.create()

        assertNull(fixture.session.activeSession())
        fixture.becomeActive()

        val active = assertNotNull(fixture.session.activeSession())
        assertEquals(SessionState.ACTIVE, fixture.session.snapshot().state)
        assertEquals(fixture.session.snapshot().generation, active.generation)
        assertEquals("desktop-session", active.sessionId)

        fixture.session.stop()

        assertNull(fixture.session.activeSession())
    }
}
