package com.skycommand.relay.photo.dji.android

import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MsdkV5PhotoApiContractTest {
    @Test
    fun cameraRecoveryOnlyDisablesAfterPlaybackWasConfirmedAndReadsBackTheExit() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/photo/dji/android/MsdkV5PhotoApi.kt"),
            Path("src/modules/camera-photo/android-dji-photo-adapter/src/main/kotlin/com/skycommand/relay/photo/dji/android/MsdkV5PhotoApi.kt"),
        ).first { it.exists() }.readText()
        val recovery = source.substringAfter("override fun recoverVideoInput").substringBefore("private fun startPlayback")

        assertTrue(recovery.contains("confirmPlaybackExited"))
        assertTrue(source.contains("KeyIsPlayingBack"))
        assertFalse(recovery.contains("restoreVideoInput"))
        assertTrue(source.contains("override fun readPlaybackActive"))
    }

    @Test
    fun abortedSessionDoesNotContinueMediaOrModeCleanupFromLateCallbacks() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/photo/dji/android/MsdkV5PhotoApi.kt"),
            Path("src/modules/camera-photo/android-dji-photo-adapter/src/main/kotlin/com/skycommand/relay/photo/dji/android/MsdkV5PhotoApi.kt"),
        ).first { it.exists() }.readText()
        val failCapture = source.substringAfter("private fun failCapture").substringBefore("private fun armModeWatch")
        assertTrue(failCapture.contains("captureCompletion.compareAndSet(completion, null)"))
        val pull = source.substringAfter("private fun pullThenDownload").substringBefore("private fun writeOriginal")
        assertTrue(pull.contains("if (aborted.get()) return"))
        val finish = source.substringAfter("override fun onFinish").substringBefore("override fun onFailure")
        assertTrue(finish.contains("if (aborted.get())"))
        val leave = source.substringAfter("private fun leavePlaybackThen").substringBefore("private fun completeCaptureIfReady")
        assertTrue(leave.contains("if (aborted.get() && !continueWhenAborted) return"))
        val abort = source.substringAfter("override fun abort").substringBefore("override fun close")
        assertTrue(abort.contains("continueWhenAborted = true"))
        assertTrue(abort.contains("done = onHardwareReleased"))
        assertFalse(source.contains("modeWatch.shutdownNow()"))
        assertTrue(source.contains("override fun close()"))
        assertTrue(source.contains("manager.cancelListen(this)"))
    }

    @Test
    fun onlyShootsAfterAsyncPhotoModeIsConfirmedAndNeverOnStaleListenOrFailedModeSwitch() {
        val source = listOf(
            Path("src/main/kotlin/com/skycommand/relay/photo/dji/android/MsdkV5PhotoApi.kt"),
            Path("src/modules/camera-photo/android-dji-photo-adapter/src/main/kotlin/com/skycommand/relay/photo/dji/android/MsdkV5PhotoApi.kt"),
        ).first { it.exists() }.readText()
        val capture = source.substringAfter("override fun capture").substringBefore("override fun download")
        val requestPhotoMode = source.substringAfter("private fun requestPhotoMode").substringBefore("private fun onPhotoModeReady")
        val shoot = source.substringAfter("private fun shoot").substringBefore("private fun resolveCaptureIdentity")

        assertTrue(capture.contains("manager.listen(modeKey"))
        assertTrue(capture.contains("manager.getValue(modeKey,"))
        assertFalse(capture.contains("val current = manager.getValue(modeKey)"))
        assertFalse(capture.contains("override fun onSuccess() = shoot(completion)"))
        assertTrue(capture.contains("modeSwitchRequested"))
        assertTrue(requestPhotoMode.contains("modeSwitchRequested.set(true)"))
        assertTrue(requestPhotoMode.contains("failCapture(completion, failureOf(error))"))
        val setValueFailure = requestPhotoMode.substringAfter("override fun onFailure").substringBefore("}")
        assertTrue(setValueFailure.contains("failCapture"))
        assertFalse(setValueFailure.contains("onPhotoModeReady"))
        assertTrue(shoot.contains("cameraMode="))
        assertTrue(shoot.contains("createKey(CameraKey.KeyStartShootPhoto, cameraIndex)"))
        assertFalse(shoot.contains("createCameraKey"))
        assertFalse(shoot.contains("CAMERA_LENS_ZOOM"))
        assertFalse(shoot.contains("CAMERA_LENS_WIDE"))
        assertFalse(source.contains("KeyCameraVideoStreamSource"))
        assertTrue(source.contains("KeyCameraStorageInfos"))
        assertTrue(source.contains("innerCode()"))
        assertTrue(source.contains("unusableStorage"))
        assertTrue(source.contains("STORAGE_NOT_READY"))
        assertTrue(source.contains("availablePhotoCount"))
        assertTrue(source.contains("NOT_INSERTED"))
        assertFalse(source.contains("left == 0"))
        val storageCheck = source.substringAfter("private fun unusableStorage").substringBefore("private fun storageSummary")
        assertFalse(storageCheck.contains("if (items.isEmpty()) return false"))
        val download = source.substringAfter("override fun download")
        assertTrue(download.contains("setMediaFileDataSource"))
        assertTrue(download.contains("MediaFileFilter.ALL"))
        assertFalse(download.contains("MediaFileFilter.PHOTO"))
        assertTrue(download.contains("CameraStorageLocation"))
        assertTrue(source.contains("KeyIsPlayingBack"))
        assertTrue(download.contains("manager.listen(playbackKey"))
        val startPlayback = source.substringAfter("private fun startPlayback").substringBefore("private fun confirmPlaybackThenPull")
        assertFalse(startPlayback.contains("media.disable"))
        assertTrue(startPlayback.contains("media.enable"))
        val enableFailure = startPlayback.substringAfter("override fun onFailure").substringBefore("override fun")
        assertTrue(enableFailure.contains("completion.fail") || enableFailure.contains("failDownload"))
        assertFalse(enableFailure.contains("pullThenDownload"))
        assertFalse(startPlayback.contains("pullThenDownload"))
        assertTrue(source.contains("playingBack.get()"))
        assertTrue(source.contains("pullWhenPlaybackReady"))
        val pullWhenReady = source.substringAfter("private fun pullWhenPlaybackReady").substringBefore("private fun pullThenDownload")
        assertTrue(pullWhenReady.contains("playingBack.get()"))
        assertTrue(pullWhenReady.contains("pullThenDownload"))
        val abort = source.substringAfter("override fun abort")
        assertTrue(abort.contains("stopPullMediaFileListFromCamera") || abort.contains("mediaManager.stopPullMediaFileListFromCamera"))
        val resolve = source.substringAfter("private fun resolveCaptureIdentity").substringBefore("private fun pullThenDownload")
        assertFalse(resolve.contains("pullMediaFileListFromCamera"))
        assertTrue(source.contains("KeyNewlyGeneratedMediaFile"))
        assertTrue(source.contains("shutterSucceeded"))
        val complete = source.substringAfter("private fun completeCaptureIfReady").substringBefore("private fun pullThenDownload")
        assertTrue(complete.contains("shutterSucceeded.get()"))
        assertTrue(shoot.contains("shutterSucceeded.set(true)"))
        assertTrue(shoot.contains("generated.set(null)"))
        val ready = source.substringAfter("private fun onPhotoModeReady").substringBefore("override fun download")
        assertTrue(ready.contains("applyHighestPhotoQuality"))
        assertTrue(ready.contains("cancelModeWatch()"))
        assertTrue(ready.indexOf("cancelModeWatch()") < ready.indexOf("applyHighestPhotoQuality"))
        assertFalse(ready.contains("shoot("))
        val applyQuality = source.substringAfter("private fun applyHighestPhotoQuality")
            .substringBefore("private fun shoot")
        assertTrue(applyQuality.contains("schedule("))
        assertTrue(applyQuality.contains("AtomicBoolean"))
        assertTrue(applyQuality.contains("compareAndSet(false, true)"))
        assertTrue(applyQuality.contains("CAMERA_LENS_DEFAULT"))
        assertTrue(applyQuality.contains("PhotoSize.SIZE_LARGE"))
        assertFalse(applyQuality.contains("SIZE_EXTRA_LARGE"))
        assertFalse(applyQuality.contains("KeyPhotoResolution"))
        assertFalse(applyQuality.contains("RESOLUTION_48MP"))
        assertFalse(applyQuality.contains("createCameraKey"))
        assertTrue(applyQuality.contains("KeyPhotoSize"))
        assertTrue(applyQuality.contains("KeyPhotoRatio"))
        assertTrue(applyQuality.contains("RATIO_4COLON3"))
        assertTrue(applyQuality.contains("KeyPhotoQuality"))
        assertTrue(applyQuality.contains("CameraPhotoQuality.SFINE"))
        assertTrue(applyQuality.contains("KeyPhotoFileFormat"))
        assertTrue(applyQuality.contains("PhotoFileFormat.JPEG"))
        assertTrue(applyQuality.contains("onFailure"))
        assertTrue(applyQuality.contains("onPhotoQualityReady") || applyQuality.contains("applyNext"))
        assertFalse(source.contains("ILiveStreamManager"))
        assertFalse(source.contains("stopStream"))
        assertFalse(source.contains("startStream"))
        assertTrue(source.contains("modeToRestore"))
        assertTrue(complete.contains("restoreLiveCameraMode"))
        val restore = source.substringAfter("private fun restoreLiveCameraMode").substringBefore("private fun leavePlaybackThen")
        assertTrue(restore.contains("CameraMode.VIDEO_NORMAL"))
        assertFalse(capture.contains("decodeHold"))
        assertFalse(source.contains("decodeHold"))
        assertTrue(capture.contains("armModeWatch(completion)"))
        assertTrue(capture.indexOf("armModeWatch(completion)") < capture.indexOf("manager.getValue(modeKey"))
        val modeWatch = source.substringAfter("private fun armModeWatch").substringBefore("private fun cancelModeWatch")
        assertTrue(modeWatch.contains("6"))
        assertTrue(modeWatch.contains("TimeUnit.SECONDS"))
        assertTrue(modeWatch.contains("shootStarted.get()"))
        assertTrue(modeWatch.contains("CAMERA_MODE_NOT_PHOTO"))
        assertTrue(modeWatch.contains("相机没有进入拍照模式"))
        assertFalse(modeWatch.contains("stopStream"))
        assertFalse(restore.contains("decodeHold"))
        assertTrue(restore.contains("done()"))
        val finish = source.substringAfter("override fun onFinish").substringBefore("override fun onFailure")
        assertTrue(finish.contains("leavePlaybackThen"))
        assertFalse(finish.contains("ignoredCallback"))
        assertFalse(finish.substringBefore("leavePlaybackThen").contains("completion.succeed"))
        assertTrue(finish.substringAfter("leavePlaybackThen").contains("completion.succeed(destFile, file.fileName)"))
    }
}
