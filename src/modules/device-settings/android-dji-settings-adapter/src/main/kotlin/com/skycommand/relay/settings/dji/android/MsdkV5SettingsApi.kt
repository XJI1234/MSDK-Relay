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
            is SettingsRequest.Read -> read(request.domain)?.let(completion::succeed) ?: completion.fail()
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
                read(domain)?.let(completion::succeed) ?: completion.fail()
                return
            }
            writes[index](object : DjiWriteCompletion {
                override fun succeed() = next(index + 1)
                override fun fail() = completion.fail()
            })
        }
        next(0)
    }

    private fun read(domain: SettingsDomain): SettingsSnapshot? = when (domain) {
        SettingsDomain.CAMERA -> verifiedCameraSnapshot(
            autoExposureLockEnabled = manager.getValue(aeLockKey),
            focusMode = manager.getValue<CameraFocusMode>(focusModeKey)?.name,
            cameraIndex = cameraIndex.name,
        )
        SettingsDomain.TRANSMISSION -> verifiedTransmissionSnapshot(
            frequencyBand = manager.getValue<FrequencyBand>(frequencyBandKey)?.name,
            channelSelectionMode = manager.getValue<ChannelSelectionMode>(channelModeKey)?.name,
            bandwidth = manager.getValue<Bandwidth>(bandwidthKey)?.name,
            dynamicDataRateMbps = manager.getValue(dataRateKey) as? Number,
        )
    }

    private fun <T> set(key: DJIKey<T>, value: T, completion: DjiWriteCompletion) {
        manager.setValue(key, value, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = completion.succeed()
            override fun onFailure(error: IDJIError) = completion.fail()
        })
    }
}

/** Only values present in DJI's cache and within the relay wire contract become success snapshots. */
internal fun verifiedCameraSnapshot(
    autoExposureLockEnabled: Boolean?,
    focusMode: String?,
    cameraIndex: String?,
): SettingsSnapshot.Camera? {
    val confirmedFocusMode = focusMode.takeIf(::isConfirmedToken) ?: return null
    val confirmedCameraIndex = cameraIndex.takeIf(::isConfirmedToken) ?: return null
    val confirmedAutoExposureLock = autoExposureLockEnabled ?: return null
    return SettingsSnapshot.Camera(CameraSettings(confirmedAutoExposureLock, confirmedFocusMode, confirmedCameraIndex))
}

internal fun verifiedTransmissionSnapshot(
    frequencyBand: String?,
    channelSelectionMode: String?,
    bandwidth: String?,
    dynamicDataRateMbps: Number?,
): SettingsSnapshot.Transmission? {
    val rate = dynamicDataRateMbps?.toDouble()
    val confirmedFrequencyBand = frequencyBand.takeIf(::isConfirmedToken) ?: return null
    val confirmedChannelSelectionMode = channelSelectionMode.takeIf(::isConfirmedToken) ?: return null
    val confirmedBandwidth = bandwidth.takeIf(::isConfirmedToken) ?: return null
    if (rate != null && (!rate.isFinite() || rate < 0.0)) return null
    return SettingsSnapshot.Transmission(
        TransmissionSettings(confirmedFrequencyBand, confirmedChannelSelectionMode, confirmedBandwidth, rate),
    )
}

private fun isConfirmedToken(value: String?): Boolean =
    value != null && value != "UNKNOWN" && value.matches(Regex("[A-Z][A-Z0-9_]{0,63}"))
