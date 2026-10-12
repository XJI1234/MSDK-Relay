package com.skycommand.relay.photo.dji.android

import java.util.PriorityQueue
import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureStorageCheckTest {
    private class Clock {
        data class Task(val at: Long, val sequence: Int, val run: () -> Unit, var cancelled: Boolean = false)
        var now = 0L
        private var sequence = 0
        private val tasks = PriorityQueue<Task>(compareBy<Task> { it.at }.thenBy { it.sequence })
        fun schedule(delay: Long, run: () -> Unit): () -> Unit {
            val task = Task(now + delay, sequence++, run)
            tasks.add(task)
            return { task.cancelled = true }
        }
        fun advance(to: Long) {
            while (tasks.peek()?.at?.let { it <= to } == true) {
                val task = tasks.remove()
                now = task.at
                if (!task.cancelled) task.run()
            }
            now = to
        }
    }

    private fun internal(photos: Int) = CaptureStorageSnapshot(
        "INTERNAL", listOf(CaptureStorageEntry("INTERNAL", "INSERTED", photos)), "internal photos=$photos",
    )

    @Test fun waitsForPhotoCountToUpdateAfterChangingPhotoSettings() {
        val clock = Clock()
        var reads = 0; var shutters = 0; var failures = 0
        CaptureStorageCheck(
            read = { it(internal(if (++reads == 1) 0 else 2)) }, schedule = clock::schedule,
            active = { true }, ready = { shutters++ }, failed = { failures++ },
        ).start()
        assertEquals(0, shutters)
        clock.advance(349)
        assertEquals(1, reads)
        clock.advance(350)
        clock.advance(10000)
        assertEquals(2, reads); assertEquals(1, shutters); assertEquals(0, failures)
    }

    @Test fun anotherStorageWithSpaceCannotAuthorizeTheSelectedMissingSdCard() {
        val clock = Clock()
        var shutters = 0; var failures = 0
        CaptureStorageCheck(
            read = { it(CaptureStorageSnapshot("SDCARD", listOf(
                CaptureStorageEntry("SDCARD", "NOT_INSERTED", 0), CaptureStorageEntry("INTERNAL", "INSERTED", 2),
            ), "selected SD missing")) }, schedule = clock::schedule,
            active = { true }, ready = { shutters++ }, failed = { failures++ },
        ).start()
        clock.advance(10000)
        assertEquals(0, shutters); assertEquals(1, failures)
    }

    @Test fun zeroPhotoCapacityAndMissingStorageHaveBoundedRetries() {
        for (snapshot in listOf(internal(0), CaptureStorageSnapshot(null, emptyList(), "unknown"))) {
            val clock = Clock()
            var reads = 0; var shutters = 0; var failures = 0
            CaptureStorageCheck(
                read = { reads++; it(snapshot) }, schedule = clock::schedule,
                active = { true }, ready = { shutters++ }, failed = { failures++ },
            ).start()
            clock.advance(10000)
            assertEquals(6, reads); assertEquals(0, shutters); assertEquals(1, failures)
        }
    }

    @Test fun missingSdkCallbacksCannotLeaveTheCapturePendingForever() {
        val clock = Clock()
        var reads = 0; var shutters = 0; var failures = 0
        CaptureStorageCheck(
            read = { reads++ }, schedule = clock::schedule,
            active = { true }, ready = { shutters++ }, failed = { failures++ },
        ).start()
        clock.advance(5350)
        assertEquals(6, reads); assertEquals(0, shutters); assertEquals(1, failures)
    }

    @Test fun lateAndDuplicateReadCallbacksCannotCauseExtraShutters() {
        val clock = Clock()
        val pending = mutableListOf<(CaptureStorageSnapshot?) -> Unit>()
        var shutters = 0; var failures = 0
        CaptureStorageCheck(
            read = { pending.add(it) }, schedule = clock::schedule,
            active = { true }, ready = { shutters++ }, failed = { failures++ },
        ).start()
        clock.advance(950)
        pending[0](internal(2))
        assertEquals(0, shutters)
        pending[1](internal(2)); pending[1](internal(2))
        clock.advance(10000)
        assertEquals(1, shutters); assertEquals(0, failures)
    }

    @Test fun abortPreventsBothLateCallbacksAndScheduledRetriesFromShooting() {
        val clock = Clock()
        var active = true; var reads = 0; var shutters = 0; var failures = 0
        var reply: ((CaptureStorageSnapshot?) -> Unit)? = null
        CaptureStorageCheck(
            read = { reads++; reply = it }, schedule = clock::schedule,
            active = { active }, ready = { shutters++ }, failed = { failures++ },
        ).start()
        active = false
        reply!!(internal(2)); clock.advance(10000)
        assertEquals(1, reads); assertEquals(0, shutters); assertEquals(0, failures)
    }
}
