# Camera Session Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make one physical primary camera use one serial control domain for production RTMP, photo commands and camera settings, and remove the Mini 4 Pro photo-profile hang without changing external commands.

**Architecture:** Keep `live-stream`, `camera-photo` and `device-settings` as the caller-facing modules. Replace their separate primary-camera `DjiOperationCoordinator` instances with one camera control domain from `device-connection`; it serializes camera mode, photo configuration, shutter, playback download, decoder Surface, RTMP start/stop and camera-setting reads/writes. Transmission-setting requests move to a distinct transmission control domain, so AirLink writes do not wait behind a photo download. Photo capability selection is a pure deep module that chooses supported writes before the Android adapter issues a Key call. Desktop media is deliberately deferred to phase 2.

**Tech Stack:** Kotlin/JVM 17, Gradle, JUnit 5, Android DJI MSDK 5.17.

## Global Constraints

- RTMP start gate remains `SDK READY && AirLink CONNECTED && primary camera CONNECTED`; never require flight-controller connection.
- Existing external commands and result models remain `live-stream.start`, `live-stream.stop`, `camera.photo.capture`, and `camera.photo.fetch`.
- No Android or DJI type crosses a public module interface.
- Only the shared camera domain serializes camera settings, photo and RTMP; flight, mission, pairing and transmission settings keep separate domains.
- Do not install an APK or restart the mobile app during this phase.
- This workspace is already dirty; do not commit, reset, clean or overwrite unrelated work.

---

### Task 1: Establish the shared-camera contract

**Files:**
- Modify: `CONTRACT.md`
- Modify: `src/modules/device-connection/CONTRACT.md`
- Modify: `src/modules/live-stream/CONTRACT.md`
- Modify: `src/modules/camera-photo/CONTRACT.md`
- Modify: `src/modules/camera-photo/android-dji-photo-adapter/CONTRACT.md`
- Test: `src/modules/device-connection/src/test/kotlin/com/skycommand/relay/device/DeviceConnectionContractTest.kt`

**Interfaces:**
- Produces: `DeviceConnection.cameraOperations(): DjiOperationCoordinator` and `DeviceConnection.transmissionSettingsOperations(): DjiOperationCoordinator`.
- Removes: `DeviceConnection.streamOperations()`, `DeviceConnection.photoOperations()` and the mixed `settingsOperations()` from the production composition root.

- [ ] **Step 1: Write the failing ownership test**

```kotlin
@Test
fun cameraOperationsAreSharedByPhotoRtmpAndCameraSettingsButSeparateFromOtherDeviceDomains() {
    val connection = connection()
    assertSame(connection.cameraOperations(), connection.cameraOperations())
    assertNotSame(connection.cameraOperations(), connection.flightOperations())
    assertNotSame(connection.cameraOperations(), connection.waylineOperations())
    assertNotSame(connection.cameraOperations(), connection.transmissionSettingsOperations())
}
```

- [ ] **Step 2: Run the red test**

Run: `./gradlew.bat :device-connection:test --tests com.skycommand.relay.device.DeviceConnectionContractTest.cameraOperationsAreSharedByPhotoRtmpAndCameraSettingsButSeparateFromOtherDeviceDomains`

Expected: compilation failure because `cameraOperations()` does not exist.

- [ ] **Step 3: Update contracts before production code**

State that the camera control domain owns serial ordering for RTMP Surface/manager calls, photo mode/profile/shutter/playback calls and camera-setting reads/writes. State that the distinct transmission control domain owns AirLink settings. Neither domain is a video readiness assertion, neither adds flight-controller gating, and neither serializes flight/mission/pairing.

- [ ] **Step 4: Verify the edited contract is internally consistent**

Run: `rg -n "streamOperations|photoOperations|settingsOperations|cameraOperations|transmissionSettingsOperations" CONTRACT.md src/modules/device-connection/CONTRACT.md src/modules/live-stream/CONTRACT.md src/modules/camera-photo/CONTRACT.md src/modules/device-settings/CONTRACT.md`

Expected: the only documented production control interface for the two businesses is `cameraOperations()`.

### Task 2: Migrate production RTMP, photo and camera settings to the shared domain

**Files:**
- Modify: `src/modules/device-connection/src/main/kotlin/com/skycommand/relay/device/DeviceConnection.kt`
- Modify: `src/app/src/main/kotlin/com/skycommand/relay/app/MobileRelayGraph.kt`
- Modify: `src/modules/device-settings/src/main/kotlin/com/skycommand/relay/settings/DeviceSettings.kt`
- Modify: `src/modules/device-settings/settings-executor/src/main/kotlin/com/skycommand/relay/settings/executor/SettingsExecutor.kt`
- Modify: `src/modules/device-connection/src/test/kotlin/com/skycommand/relay/device/DeviceConnectionContractTest.kt`
- Test: `src/app/src/test/kotlin/com/skycommand/relay/app/MobileRelayGraphContractTest.kt`

**Interfaces:**
- Consumes: `DeviceConnection.cameraOperations()`.
- Produces: `LiveStreamDependencies.operationCoordinator`, `CameraPhotoDependencies.operationCoordinator` and camera `SettingsRequest`s use the same instance; transmission `SettingsRequest`s use `transmissionSettingsOperations()`.

- [ ] **Step 1: Write the failing composition test**

```kotlin
@Test
fun productionWiringSharesTheCameraOperationDomainBetweenRtmpPhotoAndCameraSettings() {
    val source = productionGraphSource()
    assertTrue(source.contains("device.cameraOperations()"))
    assertFalse(source.contains("device.streamOperations()"))
    assertFalse(source.contains("device.photoOperations()"))
}
```

- [ ] **Step 2: Run the red test**

Run: `./gradlew.bat :app:testDebugUnitTest --tests com.skycommand.relay.app.MobileRelayGraphContractTest.productionWiringSharesTheCameraOperationDomainBetweenRtmpPhotoAndCameraSettings`

Expected: failure because production wiring still asks for separate domains.

- [ ] **Step 3: Write the minimal migration**

```kotlin
private val cameraOperations = DjiOperationCoordinator.create(dependencies.executor, dependencies.scheduler)
private val transmissionSettingsOperations = DjiOperationCoordinator.create(dependencies.executor, dependencies.scheduler)

fun cameraOperations(): DjiOperationCoordinator = cameraOperations
fun transmissionSettingsOperations(): DjiOperationCoordinator = transmissionSettingsOperations
```

Replace the RTMP and photo composition-root uses with `device.cameraOperations()`. Change `DeviceSettingsDependencies` and `SettingsExecutor` to select the camera coordinator for `SettingsDomain.CAMERA` and the transmission coordinator for `SettingsDomain.TRANSMISSION`. Remove the retired stream/photo/mixed-settings coordinator fields and accessors. Do not change flight, mission or pairing coordinators.

- [ ] **Step 4: Run focused tests**

Run: `./gradlew.bat :device-connection:test :device-settings:test :device-settings:settings-executor:test :app:testDebugUnitTest --tests com.skycommand.relay.app.MobileRelayGraphContractTest`

Expected: both modules succeed with zero test failures.

### Task 3: Add a capability-driven photo profile module

**Files:**
- Create: `src/modules/camera-photo/photo-profile/build.gradle.kts`
- Create: `src/modules/camera-photo/photo-profile/CONTRACT.md`
- Create: `src/modules/camera-photo/photo-profile/src/main/kotlin/com/skycommand/relay/photo/profile/PhotoProfileResolver.kt`
- Create: `src/modules/camera-photo/photo-profile/src/test/kotlin/com/skycommand/relay/photo/profile/PhotoProfileResolverTest.kt`
- Modify: `settings.gradle.kts`
- Modify: `src/modules/camera-photo/build.gradle.kts`

**Interfaces:**
- Produces: `PhotoProfileResolver.resolve(capabilities): PhotoProfile`.
- `PhotoProfile` contains only requested values whose support is explicitly `SUPPORTED`; `UNKNOWN` and `UNSUPPORTED` produce no write.

- [ ] **Step 1: Write failing resolver tests**

```kotlin
@Test
fun omitsExtraLargeWhenPhotoSizeIsUnsupported() {
    val profile = PhotoProfileResolver.resolve(
        PhotoCapabilities(photoSize = PhotoSettingSupport.UNSUPPORTED)
    )
    assertFalse(profile.writes.any { it.key == PhotoSettingKey.SIZE })
}

@Test
fun preservesSupportedRequestedValuesInStableOrder() {
    val profile = PhotoProfileResolver.resolve(PhotoCapabilities.allSupported())
    assertEquals(
        listOf(PhotoSettingKey.RATIO, PhotoSettingKey.RESOLUTION, PhotoSettingKey.SIZE,
            PhotoSettingKey.QUALITY, PhotoSettingKey.FILE_FORMAT),
        profile.writes.map { it.key },
    )
}
```

- [ ] **Step 2: Run the red test**

Run: `./gradlew.bat :camera-photo:photo-profile:test`

Expected: Gradle reports that project `:camera-photo:photo-profile` is not found.

- [ ] **Step 3: Implement the pure resolver**

```kotlin
enum class PhotoSettingSupport { SUPPORTED, UNSUPPORTED, UNKNOWN }
enum class PhotoSettingKey { RATIO, RESOLUTION, SIZE, QUALITY, FILE_FORMAT }

object PhotoProfileResolver {
    fun resolve(capabilities: PhotoCapabilities): PhotoProfile = PhotoProfile(
        listOfNotNull(
            capabilities.ratio.takeIf { it == SUPPORTED }?.let { PhotoSettingRequest.RATIO_4_3 },
            capabilities.resolution.takeIf { it == SUPPORTED }?.let { PhotoSettingRequest.RESOLUTION_48_MP },
            capabilities.photoSize.takeIf { it == SUPPORTED }?.let { PhotoSettingRequest.SIZE_EXTRA_LARGE },
            capabilities.quality.takeIf { it == SUPPORTED }?.let { PhotoSettingRequest.QUALITY_FINE },
            capabilities.fileFormat.takeIf { it == SUPPORTED }?.let { PhotoSettingRequest.FILE_FORMAT_JPEG },
        ),
    )
}
```

- [ ] **Step 4: Run the green test**

Run: `./gradlew.bat :camera-photo:photo-profile:test`

Expected: all resolver tests pass.

### Task 4: Apply the resolved profile in the Android adapter

**Files:**
- Modify: `src/modules/camera-photo/android-dji-photo-adapter/build.gradle.kts`
- Modify: `src/modules/camera-photo/android-dji-photo-adapter/src/main/kotlin/com/skycommand/relay/photo/dji/android/MsdkV5PhotoApi.kt`
- Modify: `src/modules/camera-photo/android-dji-photo-adapter/src/test/kotlin/com/skycommand/relay/photo/dji/android/MsdkV5PhotoApiContractTest.kt`
- Test: `src/modules/camera-photo/android-dji-photo-adapter/src/test/kotlin/com/skycommand/relay/photo/dji/android/MsdkV5PhotoApiContractTest.kt`

**Interfaces:**
- Consumes: `PhotoProfileResolver` and MSDK `isKeySupported` facts.
- Produces: no `setValue` call for unsupported/unknown quality Keys; every dispatched Key has a per-Key callback deadline and completes capture with a stable profile failure rather than leaving the outer 15-second timeout to fire.

- [ ] **Step 1: Write the failing adapter contract test**

```kotlin
assertTrue(source.contains("PhotoProfileResolver"))
assertTrue(source.contains("isKeySupported"))
assertFalse(source.contains("PhotoSize.SIZE_EXTRA_LARGE,\n            ) { applyHighestPhotoQuality(3"))
```

- [ ] **Step 2: Run the red test**

Run: `./gradlew.bat :camera-photo:android-dji-photo-adapter:test --tests com.skycommand.relay.photo.dji.android.MsdkV5PhotoApiContractTest`

Expected: the new resolver assertions fail against the hard-coded setting chain.

- [ ] **Step 3: Implement the minimal adapter mapping**

For each profile setting, construct the matching `DJIKey`, call `manager.isKeySupported(key)`, and dispatch only `SUPPORTED` settings. A thrown support lookup or an unknown answer skips the write and records a bounded diagnostic. A dispatched Key receives one completion guard and a short per-Key deadline; either success, DJI failure, or deadline proceeds to the next selected setting. The shutter path remains unchanged after the profile sequence completes.

- [ ] **Step 4: Run adapter and debug compilation verification**

Run: `./gradlew.bat :camera-photo:android-dji-photo-adapter:test :app:compileDebugKotlin`

Expected: adapter tests and Android debug compilation succeed.

### Task 5: Verify the phase and record remaining scope

**Files:**
- Modify: `src/modules/camera-photo/CONTRACT.md`
- Modify: `src/modules/live-stream/CONTRACT.md`
- Modify: `docs/DJI_MSDK_V5_17_REFERENCE.md`

- [ ] **Step 1: Add the phase-1 verification matrix to contracts**

Include: simultaneous RTMP-start/photo-capture requests serialize; unsupported PhotoSize is skipped; a silent supported-Key callback produces an explicit profile diagnostic; no flight-controller gate; no APK install in CI.

- [ ] **Step 2: Run full JVM verification**

Run: `./gradlew.bat test`

Expected: all JVM modules succeed.

- [ ] **Step 3: Run Android compilation verification**

Run: `./gradlew.bat :app:compileDebugKotlin`

Expected: exit code 0.

- [ ] **Step 4: Inspect the diff against the phase scope**

Run: `git diff --check; git diff --stat`

Expected: no whitespace errors; changes limited to contracts, the shared camera domain, photo profile and their tests.

## Plan Self-Review

- Scope coverage: Tasks 1-2 remove the active RTMP/photo/camera-settings race without coupling AirLink settings; Tasks 3-4 remove the observed Mini 4 Pro hard-coded quality hang; Task 5 verifies behavior and documents the remaining desktop work.
- Excluded on purpose: desktop first-frame reporting, media transfer writer and renderer IPC are phase 2; no task claims they are solved here.
- Type consistency: the shared `cameraOperations()` remains an existing `DjiOperationCoordinator`, so LiveStream and CameraPhoto dependency types do not change in this phase.
- Placeholder scan: no deferred action is hidden in a task; all later-phase work is explicitly excluded.
