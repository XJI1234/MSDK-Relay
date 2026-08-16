package com.skycommand.relay.runtime.permission.android

import com.skycommand.relay.runtime.permission.PermissionCancellation
import com.skycommand.relay.runtime.permission.PermissionKind
import com.skycommand.relay.runtime.permission.PermissionPortCallback
import com.skycommand.relay.runtime.permission.PermissionRequestResult
import com.skycommand.relay.runtime.permission.PermissionSnapshot
import com.skycommand.relay.runtime.permission.PermissionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class AndroidPermissionAdapterContractTest {
    @Test
    fun skipsAndroidRequestsWhenEveryRequiredKindIsAlreadyGranted() {
        val platform = FakePlatform(
            PermissionSnapshot.of(
                mapOf(
                    PermissionKind.RUNTIME to PermissionState.GRANTED,
                    PermissionKind.USB_ACCESS to PermissionState.GRANTED,
                ),
            ),
        )
        val adapter = AndroidPermissionAdapter(platform)
        val results = mutableListOf<PermissionSnapshot>()

        adapter.request(
            setOf(PermissionKind.RUNTIME, PermissionKind.USB_ACCESS),
            callback { results += it },
        )

        assertEquals(0, platform.runtimeRequests)
        assertEquals(0, platform.usbRequests)
        assertEquals(listOf(platform.current()), results)
    }

    @Test
    fun waitsForUsbAfterRuntimeSucceedsAndCompletesOnlyWhenAllKindsAreGranted() {
        val platform = FakePlatform(
            PermissionSnapshot.of(
                mapOf(
                    PermissionKind.RUNTIME to PermissionState.DENIED,
                    PermissionKind.USB_ACCESS to PermissionState.UNKNOWN,
                ),
            ),
        )
        val adapter = AndroidPermissionAdapter(platform)
        val results = mutableListOf<PermissionSnapshot>()
        adapter.request(
            setOf(PermissionKind.RUNTIME, PermissionKind.USB_ACCESS),
            callback { results += it },
        )

        platform.set(PermissionKind.RUNTIME, PermissionState.GRANTED)
        platform.emitRuntimeResult()
        assertEquals(emptyList(), results)

        platform.set(PermissionKind.USB_ACCESS, PermissionState.GRANTED)
        platform.emitUsbResult()

        assertEquals(listOf(platform.current()), results)
        assertEquals(1, platform.runtimeRequests)
        assertEquals(1, platform.usbRequests)
    }

    @Test
    fun reportsDenialAndIgnoresDuplicatePlatformCallbacks() {
        val platform = FakePlatform(
            PermissionSnapshot.of(mapOf(PermissionKind.RUNTIME to PermissionState.DENIED)),
        )
        val adapter = AndroidPermissionAdapter(platform)
        val results = mutableListOf<PermissionSnapshot>()
        adapter.request(
            setOf(PermissionKind.RUNTIME),
            callback { results += it },
        )

        platform.emitRuntimeResult()
        platform.set(PermissionKind.RUNTIME, PermissionState.GRANTED)
        platform.emitRuntimeResult()

        assertEquals(1, results.size)
        assertEquals(PermissionState.DENIED, results.single().stateOf(PermissionKind.RUNTIME))
    }

    @Test
    fun cancellationMakesLateRuntimeAndUsbCallbacksHarmless() {
        val platform = FakePlatform(
            PermissionSnapshot.of(
                mapOf(
                    PermissionKind.RUNTIME to PermissionState.DENIED,
                    PermissionKind.USB_ACCESS to PermissionState.UNKNOWN,
                ),
            ),
        )
        val adapter = AndroidPermissionAdapter(platform)
        val results = mutableListOf<PermissionSnapshot>()
        val cancellation = adapter.request(
            setOf(PermissionKind.RUNTIME, PermissionKind.USB_ACCESS),
            callback { results += it },
        )

        cancellation.cancel()
        platform.set(PermissionKind.RUNTIME, PermissionState.GRANTED)
        platform.set(PermissionKind.USB_ACCESS, PermissionState.GRANTED)
        platform.emitRuntimeResult()
        platform.emitUsbResult()
        cancellation.cancel()

        assertEquals(emptyList(), results)
        assertEquals(2, platform.cancellations)
    }

    @Test
    fun closeCancelsActiveRequestAndRejectsFutureRequests() {
        val platform = FakePlatform(
            PermissionSnapshot.of(mapOf(PermissionKind.RUNTIME to PermissionState.DENIED)),
        )
        val adapter = AndroidPermissionAdapter(platform)
        adapter.request(setOf(PermissionKind.RUNTIME), callback { error("late callback") })

        adapter.close()
        adapter.close()

        assertFailsWith<IllegalStateException> {
            adapter.request(setOf(PermissionKind.RUNTIME), callback { })
        }
        assertEquals(1, platform.cancellations)
    }

    @Test
    fun rejectsASecondActiveRequestWithoutDisturbingTheFirst() {
        val platform = FakePlatform(
            PermissionSnapshot.of(mapOf(PermissionKind.RUNTIME to PermissionState.DENIED)),
        )
        val adapter = AndroidPermissionAdapter(platform)
        adapter.request(setOf(PermissionKind.RUNTIME), callback { })

        assertFailsWith<IllegalStateException> {
            adapter.request(setOf(PermissionKind.RUNTIME), callback { })
        }
        assertEquals(1, platform.runtimeRequests)
    }

    @Test
    fun reportsPlatformFailureAsFailedAndIgnoresLaterSuccess() {
        val platform = FakePlatform(
            PermissionSnapshot.of(mapOf(PermissionKind.RUNTIME to PermissionState.DENIED)),
        )
        val adapter = AndroidPermissionAdapter(platform)
        var failures = 0
        val results = mutableListOf<PermissionSnapshot>()
        adapter.request(
            setOf(PermissionKind.RUNTIME),
            callback({ results += it }) { failures += 1 },
        )

        platform.failRuntime()
        platform.set(PermissionKind.RUNTIME, PermissionState.GRANTED)
        platform.emitRuntimeResult()

        assertEquals(1, failures)
        assertEquals(emptyList(), results)
    }

    @Test
    fun forwardsUsbPresenceChangesUntilCancelled() {
        val platform = FakePlatform(
            PermissionSnapshot.of(mapOf(PermissionKind.USB_ACCESS to PermissionState.UNKNOWN)),
        )
        val adapter = AndroidPermissionAdapter(platform)
        var changes = 0
        val cancellation = adapter.onUsbPresenceChanged { changes += 1 }

        platform.emitPresence()
        cancellation.cancel()
        platform.emitPresence()

        assertEquals(1, changes)
    }

    private fun callback(onCompleted: (PermissionSnapshot) -> Unit): PermissionPortCallback =
        callback(onCompleted, {})

    private fun callback(
        onCompleted: (PermissionSnapshot) -> Unit,
        onFailed: () -> Unit = {},
    ): PermissionPortCallback =
        object : PermissionPortCallback {
            override fun completed(snapshot: PermissionSnapshot) {
                onCompleted(snapshot)
            }

            override fun failed() = onFailed()
        }

    private class FakePlatform(initial: PermissionSnapshot) : PermissionAdapterPlatform {
        private var snapshot = initial
        private var runtimeCallback: (() -> Unit)? = null
        private var usbCallback: (() -> Unit)? = null
        private var runtimeFailure: (() -> Unit)? = null
        private var usbFailure: (() -> Unit)? = null
        var runtimeRequests = 0
        var usbRequests = 0
        var cancellations = 0
        private val presence = mutableListOf<() -> Unit>()

        fun current(): PermissionSnapshot = snapshot

        fun set(kind: PermissionKind, state: PermissionState) {
            val next = mapOf(
                PermissionKind.RUNTIME to snapshot.stateOf(PermissionKind.RUNTIME),
                PermissionKind.USB_ACCESS to snapshot.stateOf(PermissionKind.USB_ACCESS),
                kind to state,
            )
            snapshot = PermissionSnapshot.of(next)
        }

        fun emitRuntimeResult() {
            runtimeCallback?.invoke()
        }

        fun emitUsbResult() {
            usbCallback?.invoke()
        }

        fun failRuntime() {
            runtimeFailure?.invoke()
        }

        fun emitPresence() {
            presence.toList().forEach { it() }
        }

        override fun snapshot(): PermissionSnapshot = snapshot

        override fun requestRuntimePermissions(callback: () -> Unit, failure: () -> Unit): PermissionCancellation {
            runtimeRequests += 1
            runtimeCallback = callback
            runtimeFailure = failure
            return PermissionCancellation { cancellations += 1 }
        }

        override fun requestUsbPermission(callback: () -> Unit, failure: () -> Unit): PermissionCancellation {
            usbRequests += 1
            usbCallback = callback
            usbFailure = failure
            return PermissionCancellation { cancellations += 1 }
        }

        override fun onUsbPresenceChanged(listener: () -> Unit): PermissionCancellation {
            presence += listener
            return PermissionCancellation { presence -= listener }
        }
    }
}
