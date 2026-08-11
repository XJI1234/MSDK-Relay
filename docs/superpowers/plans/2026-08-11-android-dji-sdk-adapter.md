# Android DJI SDK Adapter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an Android DJI MSDK v5 adapter that implements the existing `DjiSdkPort` without exposing Android or DJI types to the device-connection domain.

**Architecture:** `AndroidDjiSdkPort` owns one callback generation and implements the domain port. An internal bridge owns direct calls to `SDKManager`; it maps MSDK initialization and registration events to a small Android-free event interface. The adapter does not observe products, execute DJI operations, own application configuration, or shut down the process-wide DJI SDK.

**Tech Stack:** Kotlin 2.1, Android library, DJI MSDK v5 Aircraft 5.17.0, JUnit 4 Android local unit tests.

## Global Constraints

- Module path: `:device-connection:android-dji-sdk-adapter` under `src/modules/device-connection/android-dji-sdk-adapter`.
- The host application declares `com.dji.sdk.API_KEY` and calls DJI's required runtime installer before any MSDK API is touched.
- The public boundary exposes only `DjiSdkPort`, `PortStartResult`, and safe callbacks from `:device-connection:sdk-lifecycle`.
- The module never reads remote-controller or aircraft state, and never implements pairing, telemetry, live stream, missions, relay transport, or user interface.
- Every production behavior is introduced by a focused failing test before implementation.

---

### Task 1: Module Contract And Build Boundary

**Files:**
- Create: `src/modules/device-connection/android-dji-sdk-adapter/CONTRACT.md`
- Create: `src/modules/device-connection/android-dji-sdk-adapter/build.gradle.kts`
- Create: `src/modules/device-connection/android-dji-sdk-adapter/src/main/AndroidManifest.xml`
- Modify: `settings.gradle.kts`
- Modify: `CONTRACT.md`
- Modify: `docs/PROJECT_STRUCTURE.md`

**Consumes:** `DjiSdkPort` from `:device-connection:sdk-lifecycle`.

**Produces:** an Android library module with the direct DJI dependency isolated from all domain modules.

- [x] **Step 1: Write the module contract before source code**

Document `AndroidDjiSdkPort.create(applicationContext): DjiSdkPort`, host prerequisites, callback generation rules, safe failures, close semantics, security rules, and JVM/device test boundary.

- [x] **Step 2: Add the Gradle and manifest boundary**

Use an Android library with `api(project(":device-connection:sdk-lifecycle"))`, `implementation("com.dji:dji-sdk-v5-aircraft:5.17.0")`, and `compileOnly("com.dji:dji-sdk-v5-aircraft-provided:5.17.0")`. Add only the `com.dji.sdk.API_KEY` metadata placeholder to the library manifest; the application supplies its value.

- [x] **Step 3: Register the logical module and project documentation**

Add `includeRelayModule(":device-connection:android-dji-sdk-adapter")` and describe the module as the Android MSDK registration adapter, not a device-observation or operation module.

### Task 2: Callback Generation Port

**Files:**
- Create: `src/modules/device-connection/android-dji-sdk-adapter/src/main/kotlin/com/skycommand/relay/device/sdk/android/AndroidDjiSdkPort.kt`
- Create: `src/modules/device-connection/android-dji-sdk-adapter/src/test/kotlin/com/skycommand/relay/device/sdk/android/AndroidDjiSdkPortContractTest.kt`

**Consumes:** `DjiSdkPort`, `DjiSdkCallbacks`, and `PortStartResult`.

**Produces:** `internal class AndroidDjiSdkPort(bridge: DjiSdkManagerBridge) : DjiSdkPort` and an internal bridge event model.

- [x] **Step 1: Write failing tests for port behavior**

Cover initial acceptance, bridge rejection, bridge throw, synchronous ready/failure, duplicate bridge callbacks, a stale callback after `close`, repeated `close`, and a new initialization after a closed generation.

```kotlin
@Test
fun ignoresARegistrationCallbackFromTheClosedGeneration() {
    val bridge = FakeBridge()
    val port = AndroidDjiSdkPort(bridge)
    var ready = 0
    port.initialize(DjiSdkCallbacks { ready += 1 })
    val old = bridge.callback!!
    port.close()
    old.onRegistered()
    assertEquals(0, ready)
}
```

- [x] **Step 2: Run the test to verify it fails**

Run: `./gradlew.bat :device-connection:android-dji-sdk-adapter:testDebugUnitTest --no-daemon --console=plain`

Expected: compilation failure because `AndroidDjiSdkPort` is not yet defined.

- [x] **Step 3: Implement the smallest callback-generation adapter**

Define `DjiSdkManagerBridge.initialize(listener): BridgeStartResult`, `DjiSdkManagerBridge.close()`, `DjiSdkManagerListener.onRegistered()`, and `DjiSdkManagerListener.onFailure()`. Increment a generation on every initialize and close, accept only the active generation, invoke user callbacks outside locks, and suppress callback exceptions.

- [x] **Step 4: Run the focused tests to verify they pass**

Run: `./gradlew.bat :device-connection:android-dji-sdk-adapter:testDebugUnitTest --no-daemon --console=plain`

Expected: all adapter contract tests pass.

### Task 3: MSDK v5 Bridge

**Files:**
- Create: `src/modules/device-connection/android-dji-sdk-adapter/src/main/kotlin/com/skycommand/relay/device/sdk/android/MsdkV5ManagerBridge.kt`
- Modify: `src/modules/device-connection/android-dji-sdk-adapter/src/main/kotlin/com/skycommand/relay/device/sdk/android/AndroidDjiSdkPort.kt`
- Test: `src/modules/device-connection/android-dji-sdk-adapter/src/test/kotlin/com/skycommand/relay/device/sdk/android/AndroidDjiSdkPortContractTest.kt`

**Consumes:** `DjiSdkManagerBridge` and MSDK v5 `SDKManager` callbacks.

**Produces:** `AndroidDjiSdkPort.create(applicationContext): DjiSdkPort`.

- [x] **Step 1: Write a failing factory-visibility test or compile check**

Confirm the public factory returns the domain interface rather than the internal bridge types.

```kotlin
val port: DjiSdkPort = AndroidDjiSdkPort.create(applicationContext)
```

- [x] **Step 2: Run the Android Debug build to verify the factory is unavailable**

Run: `./gradlew.bat :device-connection:android-dji-sdk-adapter:assembleDebug --no-daemon --console=plain`

Expected: compilation failure until the factory and bridge are added.

- [x] **Step 3: Implement the MSDK bridge**

Call `SDKManager.getInstance().init(applicationContext, callback)`. On `DJISDKInitEvent.INITIALIZE_COMPLETE`, call `registerApp()`. Forward only `onRegisterSuccess` and `onRegisterFailure`; contain thrown platform errors as safe rejection/failure. `close()` invalidates the listener but never attempts a process-wide MSDK shutdown.

- [x] **Step 4: Verify Debug assembly and focused unit tests**

Run: `./gradlew.bat :device-connection:android-dji-sdk-adapter:testDebugUnitTest :device-connection:android-dji-sdk-adapter:assembleDebug --no-daemon --console=plain`

Expected: `BUILD SUCCESSFUL`.

### Task 4: Contract Completion And Regression

**Files:**
- Modify: `src/modules/device-connection/android-dji-sdk-adapter/CONTRACT.md`

**Consumes:** completed adapter and test evidence.

**Produces:** an implemented-and-verified module contract and a standalone commit.

- [x] **Step 1: Update the contract verification status**

Change its status only after tests and assembly succeed. Record the host-side device-test cases: valid App Key registration, invalid/missing App Key, no network, late registration after activity recreation, and physical aircraft connection.

- [x] **Step 2: Run complete regression**

Run: `./gradlew.bat cleanTest test :device-connection:android-dji-sdk-adapter:testDebugUnitTest :device-connection:android-dji-sdk-adapter:assembleDebug --no-daemon --console=plain --quiet`

Expected: exit code `0` and no test failures.

- [x] **Step 3: Check and commit**

Run: `git diff --check`

Run:

```powershell
git add -A
git commit -m "feat: add android dji sdk adapter"
```
