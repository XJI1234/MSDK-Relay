package com.skycommand.relay.settings.dji.android

import com.skycommand.relay.settings.command.CameraSettings
import com.skycommand.relay.settings.command.CameraSettingsPatch
import com.skycommand.relay.settings.command.SettingsDomain
import com.skycommand.relay.settings.command.SettingsRequest
import com.skycommand.relay.settings.command.SettingsSnapshot
import com.skycommand.relay.settings.command.TransmissionSettings
import com.skycommand.relay.settings.command.TransmissionSettingsPatch
import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.airlink.Bandwidth
import dji.sdk.keyvalue.value.airlink.ChannelSelectionMode
import dji.sdk.keyvalue.value.airlink.FrequencyBand
import dji.sdk.keyvalue.value.camera.CameraFocusMode
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager

internal class MsdkV5SettingsApi(
    private val manager: KeyManager = KeyManager.getInstance(),
) : DjiSettingsApi {
    private val cameraIndex = ComponentIndexType.LEFT_OR_MAIN
    private val aeLockKey = KeyTools.createKey(CameraKey.KeyAELockEnabled, cameraIndex)
    private val focusModeKey = KeyTools.createKey(CameraKey.KeyCameraFocusMode, cameraIndex)
    private val frequencyBandKey = KeyTools.createKey(AirLinkKey.KeyFrequencyBand)
    private val channelModeKey = KeyTools.createKey(AirLinkKey.KeyChannelSelectionMode)
    private val bandwidthKey = KeyTools.createKey(AirLinkKey.KeyBandwidth)
    private val dataRateKey = KeyTools.createKey(AirLinkKey.KeyDynamicDataRate)

    private interface DjiWriteCompletion {
        fun succeed()
        fun fail()
    }

    override fun execute(request: SettingsRequest, completion: DjiSettingsCompletion) {
        when (request) {
            is SettingsRequest.Read -> completion.succeed(read(request.domain))
            is SettingsRequest.WriteCamera -> writeCamera(request.patch, completion)
            is SettingsRequest.WriteTransmission -> writeTransmission(request.patch, completion)
        }
    }

    private fun writeCamera(patch: CameraSettingsPatch, completion: DjiSettingsCompletion) {
        val writes = buildList<(DjiWriteCompletion) -> Unit> {
            patch.autoExposureLockEnabled?.let { value -> add { next -> set(aeLockKey, value, next) } }
            patch.focusMode?.let { value -> add { next -> set(focusModeKey, CameraFocusMode.valueOf(value), next) } }
        }
        sequence(writes, SettingsDomain.CAMERA, completion)
    }

    private fun writeTransmission(patch: TransmissionSettingsPatch, completion: DjiSettingsCompletion) {
        val writes = buildList<(DjiWriteCompletion) -> Unit> {
            patch.frequencyBand?.let { value -> add { next -> set(frequencyBandKey, FrequencyBand.valueOf(value), next) } }
            patch.channelSelectionMode?.let { value -> add { next -> set(channelModeKey, ChannelSelectionMode.valueOf(value), next) } }
            patch.bandwidth?.let { value -> add { next -> set(bandwidthKey, Bandwidth.valueOf(value), next) } }
        }
        sequence(writes, SettingsDomain.TRANSMISSION, completion)
    }

    private fun sequence(
        writes: List<(DjiWriteCompletion) -> Unit>,
        domain: SettingsDomain,
        completion: DjiSettingsCompletion,
    ) {
        fun next(index: Int) {
            if (index == writes.size) {
                completion.succeed(read(domain))
                return
            }
            writes[index](object : DjiWriteCompletion {
                override fun succeed() = next(index + 1)
                override fun fail() = completion.fail()
            })
        }
        next(0)
    }

    private fun read(domain: SettingsDomain): SettingsSnapshot = when (domain) {
        SettingsDomain.CAMERA -> SettingsSnapshot.Camera(
            CameraSettings(
                autoExposureLockEnabled = manager.getValue(aeLockKey) ?: false,
                focusMode = (manager.getValue<CameraFocusMode>(focusModeKey) ?: CameraFocusMode.UNKNOWN).name,
                cameraIndex = cameraIndex.name,
            ),
        )
        SettingsDomain.TRANSMISSION -> SettingsSnapshot.Transmission(
            TransmissionSettings(
                frequencyBand = (manager.getValue<FrequencyBand>(frequencyBandKey) ?: FrequencyBand.UNKNOWN).name,
                channelSelectionMode = (manager.getValue<ChannelSelectionMode>(channelModeKey) ?: ChannelSelectionMode.UNKNOWN).name,
                bandwidth = (manager.getValue<Bandwidth>(bandwidthKey) ?: Bandwidth.UNKNOWN).name,
                dynamicDataRateMbps = (manager.getValue(dataRateKey) as? Number)?.toDouble(),
            ),
        )
    }

    private fun <T> set(key: DJIKey<T>, value: T, completion: DjiWriteCompletion) {
        manager.setValue(key, value, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = completion.succeed()
            override fun onFailure(error: IDJIError) = completion.fail()
        })
    }
}
