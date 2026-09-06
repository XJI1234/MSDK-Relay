# Lifecycle Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Close the confirmed lifecycle races and failure-path gaps without changing the normal RTMP, telemetry, wayline, or flight command behavior.

**Architecture:** Keep `DjiOperationCoordinator` as the single DJI write gate. Add explicit invocation boundaries and link-availability gates at the business facades, so only post-invocation authoritative observations can release an isolated operation and no hardware command is accepted on a known unavailable path. Preserve generation-based stale callback rejection.

**Tech Stack:** Kotlin/JVM and Android modules, Gradle, kotlin.test.

## Global Constraints

- Contract changes precede implementation changes.
- Existing command names, successful-path state transitions, and WebSocket payloads remain compatible.
- No DJI API is called from tests; all regressions use in-memory ports and deterministic schedulers.
- Unknown or unavailable hardware states fail closed.

### Task 1: Fix mission failure classification and invocation correlation

**Files:**
- Modify: `src/modules/wayline-mission/mission-executor/CONTRACT.md`
- Modify: `src/modules/wayline-mission/mission-executor/src/main/kotlin/com/skycommand/relay/wayline/executor/MissionExecutor.kt`
- Test: `src/modules/wayline-mission/mission-executor/src/test/kotlin/com/skycommand/relay/wayline/executor/MissionExecutorContractTest.kt`

- [ ] Add tests proving explicit pause/resume failure restores the prior state and does not block the next command, and that a target observation before the actual control invocation cannot settle a timeout.
- [ ] Add an invocation marker/revision to `ActiveCommand`; only observations after that marker may confirm timeout/cancellation.
- [ ] Keep timeout/cancellation isolation unchanged for genuinely unconfirmed calls.

### Task 2: Harden flight-action recovery correlation

**Files:**
- Modify: `src/modules/flight-control/dji-flight-adapter/CONTRACT.md`
- Modify: `src/modules/flight-control/dji-flight-adapter/src/main/kotlin/com/skycommand/relay/flight/dji/DjiFlightAdapter.kt`
- Test: `src/modules/flight-control/dji-flight-adapter/src/test/kotlin/com/skycommand/relay/flight/dji/DjiFlightAdapterContractTest.kt`

- [ ] Add a deterministic test that emits a matching telemetry fact between recovery baseline and invocation and verifies the coordinator remains isolated.
- [ ] Mark the recovery invocation boundary immediately before `port.execute` and reject facts observed before that boundary.

### Task 3: Enforce hardware-link gates and propagate link loss

**Files:**
- Modify: `src/app/CONTRACT.md`
- Modify: `src/modules/flight-control/CONTRACT.md`
- Modify: `src/modules/wayline-mission/CONTRACT.md`
- Modify: `src/modules/device-settings/CONTRACT.md`
- Modify: `src/app/src/main/kotlin/com/skycommand/relay/app/RelayBootstrapModule.kt`
- Modify: `src/app/src/main/kotlin/com/skycommand/relay/app/MobileRelayGraph.kt`
- Modify: `src/modules/flight-control/src/main/kotlin/com/skycommand/relay/flight/FlightControl.kt`
- Modify: `src/modules/wayline-mission/src/main/kotlin/com/skycommand/relay/wayline/WaylineMission.kt`
- Modify: `src/modules/device-settings/src/main/kotlin/com/skycommand/relay/settings/DeviceSettings.kt`
- Tests: corresponding contract tests.

- [ ] Define one read-only availability predicate supplied by the composition root for each hardware-dependent facade.
- [ ] Reject new flight, wayline upload/control, and device-settings operations when required SDK/link states are not explicitly connected.
- [ ] Invalidate active operations when relevant RC/aircraft/flight-controller/AirLink/camera facts become unavailable, while leaving telemetry and RTMP lifecycle separation intact.

### Task 4: Close stream, SDK, startup, and listener cleanup gaps

**Files:**
- Modify: `src/modules/live-stream/CONTRACT.md`
- Modify: `src/modules/live-stream/dji-stream-adapter/CONTRACT.md`
- Modify: `src/modules/live-stream/dji-stream-adapter/src/main/kotlin/com/skycommand/relay/stream/dji/DjiStreamAdapter.kt`
- Modify: `src/modules/device-connection/android-dji-sdk-adapter/CONTRACT.md`
- Modify: `src/modules/device-connection/android-dji-sdk-adapter/src/main/kotlin/com/skycommand/relay/device/sdk/android/MsdkV5ManagerBridge.kt`
- Modify: `src/app/src/main/kotlin/com/skycommand/relay/app/RelayBootstrapModule.kt`
- Modify: Android KeyManager observation close paths.
- Tests: stream adapter, SDK bridge, bootstrap, and observation cleanup tests.

- [ ] Add a deferred recovery-stop path for an unconfirmed stream start; never submit a concurrent stop while the coordinator slot is held.
- [ ] Revalidate `isRegistered` before reporting an already-registered SDK.
- [ ] Make gateway startup rollback-safe and make all listener cancellation attempts independent and diagnostic-only.

### Task 5: Verification

- [ ] Run each affected module's focused tests after each task.
- [ ] Run `./gradlew.bat test` and `git diff --check`.
- [ ] Confirm no production device command is sent by the tests.
