package com.skycommand.relay.settings.dji.android

import com.skycommand.relay.settings.command.CameraSettings
import com.skycommand.relay.settings.command.TransmissionSettings
import com.skycommand.relay.settings.command.SettingsDomain
import com.skycommand.relay.settings.command.SettingsRequest
import com.skycommand.relay.settings.command.SettingsSnapshot
import com.skycommand.relay.settings.executor.SettingsDjiCompletion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun synchronousFailureAndCloseDoNotLeakLatePlatformCallbacks() {
        val api = Api()
        val port = AndroidDjiSettingsPort(api)
        val outcomes = mutableListOf<String>()
        api.throwOnExecute = true
        port.execute(SettingsRequest.Read(SettingsDomain.CAMERA), completion(outcomes))
        api.throwOnExecute = false
        port.execute(SettingsRequest.Read(SettingsDomain.CAMERA), completion(outcomes))
        port.close()
        api.succeed(camera())

        assertEquals(listOf("fail"), outcomes)
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
        fun fail() = checkNotNull(completion).fail()
    }
}
