package com.skycommand.relay.settings.dji.android

import com.skycommand.relay.settings.command.CameraSettings
import com.skycommand.relay.settings.command.SettingsDomain
import com.skycommand.relay.settings.command.SettingsRequest
import com.skycommand.relay.settings.command.SettingsSnapshot
import com.skycommand.relay.settings.executor.SettingsDjiCompletion
import kotlin.test.Test
import kotlin.test.assertEquals

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
