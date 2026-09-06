package com.skycommand.relay.settings.dji.android

import com.skycommand.relay.settings.command.CameraSettings
import com.skycommand.relay.settings.command.TransmissionSettings
import com.skycommand.relay.settings.command.SettingsDomain
import com.skycommand.relay.settings.command.SettingsDjiFailure
import com.skycommand.relay.settings.command.SettingsRequest
import com.skycommand.relay.settings.command.SettingsSnapshot
import com.skycommand.relay.settings.executor.SettingsDjiCompletion
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidDjiSettingsPortContractTest {
    @Test
    fun forwardsOneRequestAndDeliversOnlyItsFirstPlatformCompletion() {
        val api = Api()
        val port = AndroidDjiSettingsPort(api)
        val outcomes = mutableListOf<String>()

        port.execute(SettingsRequest.Read(SettingsDomain.CAMERA), completion(outcomes))
        api.succeed(camera())
        api.fail()

        assertEquals(listOf<SettingsRequest>(SettingsRequest.Read(SettingsDomain.CAMERA)), api.requests)
        assertEquals(listOf("ok"), outcomes)
    }

    @Test
    fun closeDoesNotLeakLatePlatformCallbacks() {
        val api = Api()
        val port = AndroidDjiSettingsPort(api)
        val outcomes = mutableListOf<String>()
        port.execute(SettingsRequest.Read(SettingsDomain.CAMERA), completion(outcomes))
        port.close()
        api.succeed(camera())

        assertEquals(emptyList(), outcomes)
    }

    @Test
    fun propagatesSynchronousPlatformInvocationFailureWithoutFabricatingADjiFailure() {
        val api = Api().apply { throwOnExecute = true }
        val port = AndroidDjiSettingsPort(api)
        val outcomes = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            port.execute(SettingsRequest.Read(SettingsDomain.CAMERA), completion(outcomes))
        }

        assertEquals(emptyList(), outcomes)
    }

    @Test
    fun readsTheOfficialDjiFailureCodeAndDescriptionAtTheAndroidBoundary() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/settings/dji/android/MsdkV5SettingsApi.kt"),
            Path("src/modules/device-settings/android-dji-settings-adapter/src/main/kotlin/com/skycommand/relay/settings/dji/android/MsdkV5SettingsApi.kt"),
        ).first { it.exists() }.readText()

        assertTrue(source.contains("error.errorCode()"))
        assertTrue(source.contains("error.description()"))
    }

    @Test
    fun forwardsTheNormalizedDjiFailureWithoutChangingItsFields() {
        val api = Api()
        val port = AndroidDjiSettingsPort(api)
        val failure = SettingsDjiFailure.fromDjiError("COMMON_SYSTEM_BUSY", "The camera is busy")
        var received: SettingsDjiFailure? = null

        port.execute(SettingsRequest.Read(SettingsDomain.CAMERA), object : SettingsDjiCompletion {
            override fun succeed(snapshot: SettingsSnapshot) = Unit
            override fun fail() = Unit
            override fun fail(failure: SettingsDjiFailure?) { received = failure }
        })
        api.fail(failure)

        assertEquals(failure, received)
    }

    @Test
    fun doesNotSynthesizeConfirmedSettingsWhenDjiCacheIsMissingOrUnknown() {
        assertNull(verifiedCameraSnapshot(null, "AUTO", "LEFT_OR_MAIN"))
        assertNull(verifiedCameraSnapshot(false, "UNKNOWN", "LEFT_OR_MAIN"))
        assertNull(verifiedTransmissionSnapshot(
            "UNKNOWN",
            "AUTO",
            "BANDWIDTH_20MHZ",
            null,
        ))
        assertNull(verifiedTransmissionSnapshot("BAND_2_DOT_4G", "AUTO", "BANDWIDTH_20MHZ", -1.0))
        assertNull(verifiedTransmissionSnapshot("BAND_2_DOT_4G", "AUTO", "BANDWIDTH_20MHZ", Double.NaN))

        assertEquals(
            SettingsSnapshot.Camera(CameraSettings(false, "AUTO", "LEFT_OR_MAIN")),
            verifiedCameraSnapshot(false, "AUTO", "LEFT_OR_MAIN"),
        )
        assertEquals(
            SettingsSnapshot.Transmission(TransmissionSettings("BAND_2_DOT_4G", "AUTO", "BANDWIDTH_20MHZ", null)),
            verifiedTransmissionSnapshot(
                "BAND_2_DOT_4G",
                "AUTO",
                "BANDWIDTH_20MHZ",
                null,
            ),
        )
    }

    private fun completion(outcomes: MutableList<String>) = object : SettingsDjiCompletion {
        override fun succeed(snapshot: SettingsSnapshot) { outcomes += "ok" }
        override fun fail() { outcomes += "fail" }
    }
    private fun camera() = SettingsSnapshot.Camera(CameraSettings(false, "AUTO", "LEFT_OR_MAIN"))
    private class Api : DjiSettingsApi {
        val requests = mutableListOf<SettingsRequest>(); var throwOnExecute = false; private var completion: DjiSettingsCompletion? = null
        override fun execute(request: SettingsRequest, completion: DjiSettingsCompletion) {
            if (throwOnExecute) error("platform failure")
            requests += request; this.completion = completion
        }
        fun succeed(snapshot: SettingsSnapshot) = checkNotNull(completion).succeed(snapshot)
        fun fail(failure: SettingsDjiFailure? = null) = checkNotNull(completion).fail(failure)
    }
}
