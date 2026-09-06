package com.skycommand.relay.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LatestStatusDispatcherTest {
    @Test
    fun keepsOnlyTheLatestPendingStatusAndRendersNothingAfterClose() {
        val scheduled = mutableListOf<() -> Unit>()
        val rendered = mutableListOf<String>()
        val dispatcher = newDispatcher(
            schedule = { scheduled += it },
            render = { rendered += it },
        )

        offer(dispatcher, "first")
        offer(dispatcher, "latest")

        assertEquals(1, scheduled.size)
        scheduled.removeAt(0).invoke()
        assertEquals(listOf("latest"), rendered)

        offer(dispatcher, "will-not-render")
        close(dispatcher)
        scheduled.removeAt(0).invoke()

        assertEquals(listOf("latest"), rendered)
    }

    private fun newDispatcher(
        schedule: ((() -> Unit) -> Unit),
        render: (String) -> Unit,
    ): Any {
        val type = runCatching {
            Class.forName("com.skycommand.relay.app.LatestStatusDispatcher")
        }.getOrNull()
        assertNotNull(type, "LatestStatusDispatcher must bound pending MainActivity status work")
        val constructor = type.declaredConstructors.single()
        constructor.isAccessible = true
        return constructor.newInstance(schedule, render)
    }

    private fun offer(dispatcher: Any, value: String) {
        dispatcher.javaClass.getDeclaredMethod("offer", Any::class.java).apply { isAccessible = true }
            .invoke(dispatcher, value)
    }

    private fun close(dispatcher: Any) {
        dispatcher.javaClass.getDeclaredMethod("close").apply { isAccessible = true }.invoke(dispatcher)
    }
}
