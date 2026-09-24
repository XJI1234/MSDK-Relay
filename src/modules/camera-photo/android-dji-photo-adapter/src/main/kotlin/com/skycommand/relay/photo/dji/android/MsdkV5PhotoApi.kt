package com.skycommand.relay.photo.dji.android

import com.skycommand.relay.photo.command.PhotoCaptureIdentity
import com.skycommand.relay.photo.command.PhotoDjiFailure
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.camera.CameraPhotoQuality
import dji.sdk.keyvalue.value.camera.CameraStorageInfos
import dji.sdk.keyvalue.value.camera.CameraStorageLocation
import dji.sdk.keyvalue.value.camera.GeneratedMediaFileInfo
import dji.sdk.keyvalue.value.camera.MediaFileType
import dji.sdk.keyvalue.value.camera.PhotoFileFormat
import dji.sdk.keyvalue.value.camera.PhotoRatio
import dji.sdk.keyvalue.value.camera.PhotoSize
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.file.FileListRequestTimeOrderType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.media.MediaFile
import dji.v5.manager.datacenter.media.MediaFileDownloadListener
import dji.v5.manager.datacenter.media.MediaFileFilter
import dji.v5.manager.datacenter.media.MediaFileListDataSource
import dji.v5.manager.datacenter.media.PullMediaFileListParam
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class MsdkV5PhotoApi(
    private val manager: KeyManager = KeyManager.getInstance(),
) : DjiPhotoApi {
    private val cameraIndex = ComponentIndexType.LEFT_OR_MAIN
    private val modeKey = KeyTools.createKey(CameraKey.KeyCameraMode, cameraIndex)
    private val storageKey = KeyTools.createKey(CameraKey.KeyCameraStorageInfos, cameraIndex)
    private val generatedKey = KeyTools.createKey(CameraKey.KeyNewlyGeneratedMediaFile, cameraIndex)
    private val playbackKey = KeyTools.createKey(CameraKey.KeyIsPlayingBack, cameraIndex)
    private val aborted = AtomicBoolean(false)
    private val generated = AtomicReference<GeneratedMediaFileInfo?>(null)
    private val observedMode = AtomicReference<CameraMode?>(null)
    private val modeSwitchRequested = AtomicBoolean(false)
    private val shootStarted = AtomicBoolean(false)
    private val shutterSucceeded = AtomicBoolean(false)
    private val captureCompletion = AtomicReference<DjiPhotoCaptureCompletion?>(null)
    private val modeToRestore = AtomicReference<CameraMode?>(null)
    private val playingBack = AtomicBoolean(false)
    private val enableSucceeded = AtomicBoolean(false)
    private val listPulled = AtomicBoolean(false)
    private val downloadIdentity = AtomicReference<PhotoCaptureIdentity?>(null)
    private val downloadDest = AtomicReference<File?>(null)
    private val downloadCompletion = AtomicReference<DjiPhotoDownloadCompletion?>(null)
    private val modeWatchTask = AtomicReference<ScheduledFuture<*>?>(null)
    private var downloadFile: MediaFile? = null

    override fun capture(completion: DjiPhotoCaptureCompletion) {
        aborted.set(false)
        generated.set(null)
        observedMode.set(null)
        modeSwitchRequested.set(false)
        shootStarted.set(false)
        shutterSucceeded.set(false)
        captureCompletion.set(completion)
        modeToRestore.set(null)
        armModeWatch(completion)
        manager.listen(generatedKey, this) { _, next ->
            generated.set(next)
            completeCaptureIfReady()
        }
        manager.listen(modeKey, this) { _, next ->
            observedMode.set(next)
            rememberModeToRestore(next)
            if (modeSwitchRequested.get() && next == CameraMode.PHOTO_NORMAL) onPhotoModeReady(completion)
        }
        manager.getValue(modeKey, object : CommonCallbacks.CompletionCallbackWithParam<CameraMode> {
            override fun onSuccess(value: CameraMode) {
                if (aborted.get()) return
                observedMode.set(value)
                rememberModeToRestore(value)
                if (value == CameraMode.PHOTO_NORMAL) {
                    onPhotoModeReady(completion)
                    return
                }
                requestPhotoMode(completion)
            }
            override fun onFailure(error: IDJIError) {
                if (aborted.get()) return
                requestPhotoMode(completion)
            }
        })
    }

    private fun requestPhotoMode(completion: DjiPhotoCaptureCompletion) {
        modeSwitchRequested.set(true)
        manager.setValue(modeKey, CameraMode.PHOTO_NORMAL, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                if (observedMode.get() == CameraMode.PHOTO_NORMAL) onPhotoModeReady(completion)
            }
            override fun onFailure(error: IDJIError) {
                failCapture(completion, failureOf(error))
            }
        })
    }

    private fun onPhotoModeReady(completion: DjiPhotoCaptureCompletion) {
        cancelModeWatch()
        if (aborted.get() || !shootStarted.compareAndSet(false, true)) return
        applyHighestPhotoQuality(0) { onPhotoQualityReady(completion) }
    }

    override fun download(
        identity: PhotoCaptureIdentity,
        destFile: File,
        completion: DjiPhotoDownloadCompletion,
    ) {
        aborted.set(false)
        playingBack.set(false)
        enableSucceeded.set(false)
        listPulled.set(false)
        downloadIdentity.set(identity)
        downloadDest.set(destFile)
        downloadCompletion.set(completion)
        destFile.parentFile?.mkdirs()
        manager.listen(playbackKey, this) { _, next ->
            if (next == true) {
                playingBack.set(true)
                pullWhenPlaybackReady()
            }
        }
        manager.getValue(storageKey, object : CommonCallbacks.CompletionCallbackWithParam<CameraStorageInfos> {
            override fun onSuccess(value: CameraStorageInfos) = startPlayback(locationOf(value))
            override fun onFailure(error: IDJIError) = startPlayback(CameraStorageLocation.INTERNAL)
        })
    }

    override fun abort() = abort { }

    override fun abort(onHardwareReleased: () -> Unit) {
        aborted.set(true)
        cancelModeWatch()
        captureCompletion.set(null)
        downloadCompletion.getAndSet(null)
        stopGeneratedListen()
        runCatching { downloadFile?.stopPullOriginalMediaFileFromCamera(ignoredCallback()) }
        runCatching { MediaDataCenter.getInstance().mediaManager.stopPullMediaFileListFromCamera() }
        leavePlaybackThen(
            continueWhenAborted = true,
            done = onHardwareReleased,
        )
    }

    override fun recoverVideoInput(completion: (Boolean) -> Unit) {
        val completed = AtomicBoolean(false)
        val done = { recovered: Boolean ->
            if (completed.compareAndSet(false, true)) completion(recovered)
        }
        aborted.set(true)
        cancelModeWatch()
        captureCompletion.set(null)
        downloadCompletion.getAndSet(null)
        stopGeneratedListen()
        runCatching { downloadFile?.stopPullOriginalMediaFileFromCamera(ignoredCallback()) }
        runCatching { MediaDataCenter.getInstance().mediaManager.stopPullMediaFileListFromCamera() }
        MediaDataCenter.getInstance().mediaManager.disable(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                playingBack.set(false)
                confirmPlaybackExited { exited ->
                    if (exited) restoreVideoInput(done) else done(false)
                }
            }
            override fun onFailure(error: IDJIError) {
                playingBack.set(false)
                confirmPlaybackExited { exited ->
                    if (exited) restoreVideoInput(done) else done(false)
                }
            }
        })
    }

    private fun startPlayback(
        location: CameraStorageLocation,
    ) {
        if (aborted.get()) return
        val media = MediaDataCenter.getInstance().mediaManager
        media.setMediaFileDataSource(
            MediaFileListDataSource.Builder()
                .setLocation(location)
                .setIndexType(cameraIndex)
                .build(),
        )
        if (playingBack.get()) {
            enableSucceeded.set(true)
            pullWhenPlaybackReady()
            return
        }
        media.enable(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                if (aborted.get()) return
                enableSucceeded.set(true)
                confirmPlaybackThenPull()
            }
            override fun onFailure(error: IDJIError) {
                if (aborted.get()) return
                if (playingBack.get()) {
                    enableSucceeded.set(true)
                    pullWhenPlaybackReady()
                    return
                }
                failDownload(failureOf(error))
            }
        })
    }

    private fun confirmPlaybackThenPull() {
        manager.getValue(playbackKey, object : CommonCallbacks.CompletionCallbackWithParam<Boolean> {
            override fun onSuccess(value: Boolean) {
                if (value) {
                    playingBack.set(true)
                    pullWhenPlaybackReady()
                }
            }
            override fun onFailure(error: IDJIError) = Unit
        })
        pullWhenPlaybackReady()
    }

    private fun pullWhenPlaybackReady() {
        if (aborted.get() || !playingBack.get() || !enableSucceeded.get()) return
        if (!listPulled.compareAndSet(false, true)) return
        val identity = downloadIdentity.get() ?: return
        val dest = downloadDest.get() ?: return
        val pending = downloadCompletion.get() ?: return
        pullThenDownload(identity, dest, pending)
    }

    private fun failDownload(failure: PhotoDjiFailure? = null) {
        val pending = downloadCompletion.getAndSet(null) ?: return
        stopGeneratedListen()
        leavePlaybackThen { pending.fail(failure) }
    }

    private fun failCapture(completion: DjiPhotoCaptureCompletion, failure: PhotoDjiFailure) {
        if (!captureCompletion.compareAndSet(completion, null)) return
        aborted.set(true)
        cancelModeWatch()
        stopGeneratedListen()
        restoreLiveCameraMode { completion.fail(failure) }
    }

    private fun armModeWatch(completion: DjiPhotoCaptureCompletion) {
        cancelModeWatch()
        val task = modeWatch.schedule({
            if (aborted.get() || shootStarted.get()) return@schedule
            failCapture(
                completion,
                PhotoDjiFailure.fromDjiError("CAMERA_MODE_NOT_PHOTO", "相机没有进入拍照模式"),
            )
        }, 6, TimeUnit.SECONDS)
        modeWatchTask.set(task)
    }

    private fun cancelModeWatch() {
        modeWatchTask.getAndSet(null)?.cancel(false)
    }

    private fun locationOf(infos: CameraStorageInfos): CameraStorageLocation {
        val name = infos.currentStorageType?.toString().orEmpty()
        return if (name.contains("INTERNAL")) CameraStorageLocation.INTERNAL else CameraStorageLocation.SDCARD
    }

    private fun applyHighestPhotoQuality(index: Int, done: () -> Unit) {
        if (aborted.get()) return
        when (index) {
            0 -> setPhotoQualityKey(
                KeyTools.createKey(CameraKey.KeyPhotoRatio, cameraIndex),
                PhotoRatio.RATIO_4COLON3,
            ) { applyHighestPhotoQuality(1, done) }
            1 -> setPhotoQualityKey(
                mini4ProPhotoSizeKey(),
                PhotoSize.SIZE_LARGE,
            ) { applyHighestPhotoQuality(2, done) }
            2 -> setPhotoQualityKey(
                KeyTools.createKey(CameraKey.KeyPhotoQuality, cameraIndex),
                CameraPhotoQuality.SFINE,
            ) { applyHighestPhotoQuality(3, done) }
            3 -> setPhotoQualityKey(
                KeyTools.createKey(CameraKey.KeyPhotoFileFormat, cameraIndex),
                PhotoFileFormat.JPEG,
            ) { applyHighestPhotoQuality(4, done) }
            else -> done()
        }
    }

    private fun mini4ProPhotoSizeKey(): DJIKey<PhotoSize> = KeyTools.createKey(
        CameraKey.KeyPhotoSize,
        0,
        ComponentIndexType.LEFT_OR_MAIN.value(),
        CameraLensType.CAMERA_LENS_DEFAULT.value(),
        0,
    )

    private fun <T> setPhotoQualityKey(key: DJIKey<T>, value: T, done: () -> Unit) {
        if (aborted.get()) return
        val continued = AtomicBoolean(false)
        val proceed = {
            if (!aborted.get() && continued.compareAndSet(false, true)) done()
        }
        val task = modeWatch.schedule({ proceed() }, 2, TimeUnit.SECONDS)
        manager.setValue(key, value, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                task.cancel(false)
                proceed()
            }
            override fun onFailure(error: IDJIError) {
                task.cancel(false)
                proceed()
            }
        })
    }

    private fun onPhotoQualityReady(completion: DjiPhotoCaptureCompletion) {
        if (aborted.get()) return
        shoot(completion)
    }

    private fun shoot(completion: DjiPhotoCaptureCompletion) {
        if (aborted.get()) {
            stopGeneratedListen()
            return
        }
        manager.getValue(storageKey, object : CommonCallbacks.CompletionCallbackWithParam<CameraStorageInfos> {
            override fun onSuccess(value: CameraStorageInfos) {
                val storage = storageSummary(value)
                if (unusableStorage(value)) {
                    failCapture(
                        completion,
                        PhotoDjiFailure.fromDjiError(
                            "STORAGE_NOT_READY",
                            "cameraMode=${observedMode.get()}; lens=DEFAULT_CAMERA; $storage",
                        ),
                    )
                    return
                }
                performShoot(storage, completion)
            }
            override fun onFailure(error: IDJIError) = performShoot(
                "storage=${errorText { error.errorCode() }}",
                completion,
            )
        })
    }

    private fun performShoot(
        storage: String,
        completion: DjiPhotoCaptureCompletion,
    ) {
        if (aborted.get()) {
            stopGeneratedListen()
            return
        }
        manager.performAction(
            KeyTools.createKey(CameraKey.KeyStartShootPhoto, cameraIndex),
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(result: EmptyMsg) {
                    if (aborted.get()) {
                        stopGeneratedListen()
                        captureCompletion.set(null)
                        return
                    }
                    generated.set(null)
                    shutterSucceeded.set(true)
                    resolveCaptureIdentity()
                }
                override fun onFailure(error: IDJIError) {
                    failCapture(
                        completion,
                        failureOf(error, "cameraMode=${observedMode.get()}; lens=DEFAULT_CAMERA; $storage"),
                    )
                }
            },
        )
    }

    private fun unusableStorage(infos: CameraStorageInfos): Boolean {
        val items = infos.cameraStorageInfoList.orEmpty()
        return items.none { info ->
            val state = info.storageState?.toString().orEmpty()
            val photos = info.availablePhotoCount ?: 0
            photos > 0 &&
                !state.contains("NOT_INSERTED") &&
                !state.contains("FULL") &&
                !state.contains("INVALID") &&
                !state.contains("FORMAT") &&
                !state.contains("READ_ONLY")
        }
    }

    private fun storageSummary(infos: CameraStorageInfos): String {
        val current = infos.currentStorageType
        val items = infos.cameraStorageInfoList.orEmpty().joinToString(",") { info ->
            "${info.storageType}:${info.storageState}:left=${info.storageLeftCapacity}:photos=${info.availablePhotoCount}"
        }
        return "storage=$current[$items]"
    }

    private fun resolveCaptureIdentity() {
        completeCaptureIfReady()
    }

    private fun rememberModeToRestore(mode: CameraMode?) {
        if (mode == null || mode == CameraMode.PHOTO_NORMAL || modeSwitchRequested.get()) return
        modeToRestore.compareAndSet(null, mode)
    }

    private fun restoreLiveCameraMode(done: () -> Unit) {
        val remembered = modeToRestore.getAndSet(null)
        val previous = if (remembered == null || remembered == CameraMode.PHOTO_NORMAL) {
            CameraMode.VIDEO_NORMAL
        } else {
            remembered
        }
        val finish = {
            done()
        }
        if (observedMode.get() == previous) {
            finish()
            return
        }
        manager.setValue(modeKey, previous, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = finish()
            override fun onFailure(error: IDJIError) = finish()
        })
    }

    private fun restoreVideoInput(done: (Boolean) -> Unit) {
        fun confirmVideoMode() {
            manager.getValue(modeKey, object : CommonCallbacks.CompletionCallbackWithParam<CameraMode> {
                override fun onSuccess(value: CameraMode) {
                    observedMode.set(value)
                    done(value == CameraMode.VIDEO_NORMAL)
                }
                override fun onFailure(error: IDJIError) = done(false)
            })
        }
        manager.setValue(modeKey, CameraMode.VIDEO_NORMAL, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = confirmVideoMode()
            override fun onFailure(error: IDJIError) = confirmVideoMode()
        })
    }

    private fun confirmPlaybackExited(done: (Boolean) -> Unit) {
        manager.getValue(playbackKey, object : CommonCallbacks.CompletionCallbackWithParam<Boolean> {
            override fun onSuccess(value: Boolean) = done(!value)
            override fun onFailure(error: IDJIError) = done(false)
        })
    }

    private fun leavePlaybackThen(
        continueWhenAborted: Boolean = false,
        done: () -> Unit,
    ) {
        val media = MediaDataCenter.getInstance().mediaManager
        media.disable(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                if (aborted.get() && !continueWhenAborted) return
                playingBack.set(false)
                restoreLiveCameraMode(done)
            }
            override fun onFailure(error: IDJIError) {
                if (aborted.get() && !continueWhenAborted) return
                playingBack.set(false)
                restoreLiveCameraMode(done)
            }
        })
    }

    private fun completeCaptureIfReady() {
        if (aborted.get() || !shutterSucceeded.get()) return
        val identity = identityOf(generated.get()) ?: return
        val pending = captureCompletion.getAndSet(null) ?: return
        stopGeneratedListen()
        restoreLiveCameraMode { pending.succeed(identity) }
    }

    private fun pullThenDownload(
        identity: PhotoCaptureIdentity,
        destFile: File,
        completion: DjiPhotoDownloadCompletion,
    ) {
        val media = MediaDataCenter.getInstance().mediaManager
        media.pullMediaFileListFromCamera(
            PullMediaFileListParam.Builder()
                .filter(MediaFileFilter.ALL)
                .count(64)
                .orderType(FileListRequestTimeOrderType.NEW_FIRST)
                .build(),
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    if (aborted.get()) return
                    val match = media.mediaFileListData.data.firstOrNull { file ->
                        file.fileName == identity.fileName || file.fileIndex.toLong() == identity.index
                    }
                    if (match == null) {
                        failDownload()
                        return
                    }
                    writeOriginal(match, destFile, completion)
                }
                override fun onFailure(error: IDJIError) {
                    failDownload(failureOf(error))
                }
            },
        )
    }

    private fun writeOriginal(file: MediaFile, destFile: File, completion: DjiPhotoDownloadCompletion) {
        downloadFile = file
        val output = FileOutputStream(destFile)
        file.pullOriginalMediaFileFromCamera(
            0L,
            object : MediaFileDownloadListener {
                override fun onStart() = Unit
                override fun onProgress(total: Long, current: Long) = Unit
                override fun onRealtimeDataUpdate(data: ByteArray, position: Long) {
                    if (!aborted.get()) output.write(data)
                }
                override fun onFinish() {
                    runCatching { output.close() }
                    downloadFile = null
                    if (aborted.get()) {
                        destFile.delete()
                        return
                    }
                    leavePlaybackThen {
                        if (aborted.get() || !destFile.isFile || destFile.length() <= 0) {
                            destFile.delete()
                            if (!aborted.get()) completion.fail()
                        } else {
                            completion.succeed(destFile)
                        }
                    }
                }
                override fun onFailure(error: IDJIError) {
                    runCatching { output.close() }
                    destFile.delete()
                    downloadFile = null
                    if (aborted.get()) return
                    leavePlaybackThen {
                        if (!aborted.get()) completion.fail(failureOf(error))
                    }
                }
            },
        )
    }

    private fun identityOf(info: GeneratedMediaFileInfo?): PhotoCaptureIdentity? {
        val index = info?.index?.toLong() ?: return null
        if (index < 0) return null
        val extension = when (info.type) {
            MediaFileType.JPEG -> "jpg"
            MediaFileType.DNG -> "dng"
            else -> return null
        }
        return PhotoCaptureIdentity("DJI_$index.$extension", index)
    }

    private fun isSafePhotoName(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return fileName.isNotBlank() &&
            !fileName.contains("..") &&
            !fileName.contains('/') &&
            !fileName.contains('\\') &&
            (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".dng"))
    }

    private fun stopGeneratedListen() {
        runCatching { manager.cancelListen(this) }
    }

    private fun ignoredCallback(): CommonCallbacks.CompletionCallback =
        object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() = Unit
            override fun onFailure(error: IDJIError) = Unit
        }

    private fun failureOf(error: IDJIError, extra: String? = null): PhotoDjiFailure {
        val inner = errorText { error.innerCode() }
        val type = errorText { error.errorType() }
        val description = errorText { error.description() }
        val detail = listOfNotNull(
            description,
            type?.let { "errorType=$it" },
            inner?.let { "inner=$it" },
            extra?.takeIf { it.isNotBlank() },
        ).joinToString("; ").ifBlank { null }
        return PhotoDjiFailure.fromDjiError(errorText { error.errorCode() }, detail)
    }

    private fun errorText(read: () -> Any?): String? =
        runCatching { read()?.toString()?.trim() }.getOrNull()?.takeIf { it.isNotEmpty() && it != "null" }

    companion object {
        private val modeWatch = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "photo-mode-watch").apply { isDaemon = true }
        }
    }
}
