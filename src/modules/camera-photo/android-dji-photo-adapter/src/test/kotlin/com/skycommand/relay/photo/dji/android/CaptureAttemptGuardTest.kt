package com.skycommand.relay.photo.dji.android

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptureAttemptGuardTest {
    @Test
    fun staleAttemptCannotFinishANewerAttempt() {
        val guard = CaptureAttemptGuard()
        val stale = guard.begin()
        val current = guard.begin()

        assertEquals(stale.generation + 1, current.generation)
        assertFalse(guard.finish(stale))
        assertTrue(guard.isCurrent(current))
        assertTrue(guard.finish(current))
    }

    @Test
    fun staleAttemptCannotInvalidateANewerAttempt() {
        val guard = CaptureAttemptGuard()
        val stale = guard.begin()
        val current = guard.begin()

        assertFalse(guard.invalidate(stale))
        assertTrue(guard.isCurrent(current))
        assertTrue(guard.invalidate(current))
    }
}
