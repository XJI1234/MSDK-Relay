# Relay Protocol Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a pure Kotlin/JVM `relay-gateway.protocol-core` module that validates, encodes, decodes, and tracks the Sky Command to MSDK Relay protocol without depending on Android, DJI, WebSocket, or UI code.

**Architecture:** The module exposes immutable protocol data types, stable validation errors, a strict UTF-8 JSON codec, and a small mission-transfer state machine. JSON parsing is hidden behind the codec; the rest of the application sees only protocol types and results. The current desktop peer remains compatible because a v1 `paired` frame may omit `protocolVersion`.

**Tech Stack:** Gradle 8.12, Kotlin/JVM 2.1.0, JDK 17, Jackson JSON parser with strict duplicate-field detection, JUnit 5, Kotlin test.

## Global Constraints

- Project root: `D:\Desktop\MSDK-relay`.
- `protocol-core` is a JVM library module, not an Android library module.
- Main and test code must not import Android, DJI, OkHttp, WebSocket, Activity, Context, or third-party JSON types.
- All public data types are immutable and do not expose mutable collections or byte-array aliases.
- Protocol version is v1. New mobile code sends `protocolVersion: "1"`; v1 accepts the current desktop `paired` frame without that field.
- Mission size is 1 through `104857600` bytes; one decoded mission chunk is 1 through `49152` bytes.
- Mission file names are safe basenames ending in `.kmz`; path traversal and control characters are rejected.
- Unknown frame types are ignored; known frame validation failures are rejected without throwing third-party exceptions.
- Direct flight commands are outside this module and must not be added: `flight.takeoff`, `flight.land`, `flight.return-home`, and `virtual-stick.*`.

---

### Task 1: Create the JVM module skeleton

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `protocol-core/build.gradle.kts`
- Create: `protocol-core/src/main/kotlin/com/skycommand/relay/protocol/.gitkeep`
- Create: `protocol-core/src/test/kotlin/com/skycommand/relay/protocol/.gitkeep`
- Copy: `gradle/wrapper/gradle-wrapper.jar` and `gradle/wrapper/gradle-wrapper.properties` from `D:\Desktop\MSDK-main`
- Create: `gradlew.bat` and `gradlew` using the Gradle 8.12 wrapper from `D:\Desktop\MSDK-main`

**Interfaces:**
- Produces the `protocol-core` Gradle module and the command `.\gradlew.bat :protocol-core:test`.

- [ ] **Step 1: Write the Gradle settings**

```kotlin
pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { mavenCentral() }
}
rootProject.name = "MSDKRelay"
include(":protocol-core")
```

- [ ] **Step 2: Write the root and module build files**

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.1.0" apply false
}

// protocol-core/build.gradle.kts
plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.test { useJUnitPlatform() }
```

- [ ] **Step 3: Copy the Gradle 8.12 wrapper from the existing Android repository**

Run from `D:\Desktop\MSDK-relay`:

```powershell
Copy-Item D:\Desktop\MSDK-main\gradlew .
Copy-Item D:\Desktop\MSDK-main\gradlew.bat .
New-Item -ItemType Directory -Force gradle\wrapper | Out-Null
Copy-Item D:\Desktop\MSDK-main\gradle\wrapper\gradle-wrapper.jar gradle\wrapper\
Copy-Item D:\Desktop\MSDK-main\gradle\wrapper\gradle-wrapper.properties gradle\wrapper\
```

- [ ] **Step 4: Run the empty test task**

Run: `.\gradlew.bat :protocol-core:test`

Expected: exit code `0`; Gradle resolves Kotlin/JVM and reports no failing tests.

- [ ] **Step 5: Commit the skeleton**

```powershell
git add settings.gradle.kts build.gradle.kts gradle.properties protocol-core gradle gradlew gradlew.bat
git commit -m "build: scaffold relay protocol core"
```

### Task 2: Define immutable protocol frames and validation errors

**Files:**
- Create: `protocol-core/src/main/kotlin/com/skycommand/relay/protocol/RelayFrame.kt`
- Create: `protocol-core/src/main/kotlin/com/skycommand/relay/protocol/ProtocolError.kt`
- Create: `protocol-core/src/main/kotlin/com/skycommand/relay/protocol/ProtocolLimits.kt`
- Create: `protocol-core/src/test/kotlin/com/skycommand/relay/protocol/RelayFrameValidationTest.kt`

**Interfaces:**
- Produces immutable `RelayFrame` variants for `hello`, `paired`, `telemetry`, `command`, `command-result`, and mission transfer frames.
- Produces `ProtocolResult<T> = Accepted<T> | Rejected(ProtocolError)`.
- Later codec and gateway tasks consume these types without importing Jackson.

- [ ] **Step 1: Write failing tests for boundaries**

Tests must assert:

```kotlin
assertIs<Accepted<HelloFrame>>(validate(HelloFrame("android-device", "1")))
assertIs<Rejected>(validate(HelloFrame("", "1")))
assertIs<Rejected>(validate(MissionBeginFrame("id", "../mission.kmz", 1, "0".repeat(64))))
assertIs<Rejected>(validate(MissionBeginFrame("id", "mission.kmz", 104857601, "0".repeat(64))))
assertIs<Rejected>(validate(MissionChunkFrame("id", ByteArray(49153))))
```

Also test defensive copying: mutating a source byte array after frame construction must not change the frame; returned bytes must be a copy.

- [ ] **Step 2: Run the focused tests and verify they fail**

Run: `.\gradlew.bat :protocol-core:test --tests '*RelayFrameValidationTest'`

Expected: compilation failure because the protocol types do not yet exist.

- [ ] **Step 3: Implement the frame model and validators**

Use sealed interfaces and data classes with private byte-array copies. Keep validation rules in focused functions: ID length, protocol version, safe basename, SHA-256, mission size, chunk size, and bounded result detail.

- [ ] **Step 4: Run the focused tests and verify they pass**

Run: `.\gradlew.bat :protocol-core:test --tests '*RelayFrameValidationTest'`

Expected: all focused tests pass.

- [ ] **Step 5: Commit the frame model**

```powershell
git add protocol-core/src/main protocol-core/src/test
git commit -m "feat: define relay protocol frame model"
```

### Task 3: Implement strict UTF-8 JSON encoding and decoding

**Files:**
- Create: `protocol-core/src/main/kotlin/com/skycommand/relay/protocol/RelayFrameCodec.kt`
- Create: `protocol-core/src/test/kotlin/com/skycommand/relay/protocol/RelayFrameCodecTest.kt`

**Interfaces:**
- Consumes: immutable `RelayFrame` types from Task 2.
- Produces: `encode(frame): ProtocolResult<ByteArray>` and `decode(bytes): DecodeResult`.
- `DecodeResult` is `Decoded(frame)`, `Rejected(error)`, or `Ignored(type)`.

- [ ] **Step 1: Write failing codec tests**

Cover one valid round trip for every frame type and these rejection cases:

```kotlin
assertIs<Rejected>(decode(byteArrayOf(0xC3.toByte(), 0x28)))
assertIs<Rejected>(decode("{}".toByteArray()))
assertIs<Ignored>(decode("{\"type\":\"future-event\"}".toByteArray()))
assertIs<Rejected>(decode("{\"type\":\"command\",\"id\":1}".toByteArray()))
```

Configure Jackson with strict duplicate detection, no permissive coercion, and bounded input handling. Verify that third-party parser exceptions never escape.

- [ ] **Step 2: Run the focused tests and verify they fail**

Run: `.\gradlew.bat :protocol-core:test --tests '*RelayFrameCodecTest'`

Expected: compilation failure because the codec does not yet exist.

- [ ] **Step 3: Implement the codec behind the protocol types**

Parse raw bytes as UTF-8, inspect the `type` field, validate required fields, construct a protocol frame, and return stable rejection codes. Encode only the canonical field names defined in `relay-gateway/protocol-core/CONTRACT.md`.

- [ ] **Step 4: Run codec tests and the full module test suite**

Run: `.\gradlew.bat :protocol-core:test --tests '*RelayFrameCodecTest'`

Expected: all codec tests pass.

Run: `.\gradlew.bat :protocol-core:test`

Expected: all tests pass with exit code `0`.

- [ ] **Step 5: Commit the codec**

```powershell
git add protocol-core/src/main protocol-core/src/test
git commit -m "feat: add strict relay frame codec"
```

### Task 4: Implement protocol session and mission-transfer state

**Files:**
- Create: `protocol-core/src/main/kotlin/com/skycommand/relay/protocol/RelaySessionState.kt`
- Create: `protocol-core/src/main/kotlin/com/skycommand/relay/protocol/MissionTransferState.kt`
- Create: `protocol-core/src/test/kotlin/com/skycommand/relay/protocol/RelaySessionStateTest.kt`
- Create: `protocol-core/src/test/kotlin/com/skycommand/relay/protocol/MissionTransferStateTest.kt`

**Interfaces:**
- Consumes: decoded frames from Task 3.
- Produces: deterministic state transitions and accepted mission bytes/digest inputs for the later Android gateway.

- [ ] **Step 1: Write failing session tests**

Cover:

```kotlin
assertEquals(SessionState.HELLO_SENT, session.onConnected())
assertEquals(SessionState.ACTIVE, session.onFrame(PairedFrame(sessionId = "session", protocolVersion = null)).state)
assertIs<Rejected>(session.onFrame(CommandFrame(id = "id", name = "telemetry.read", fields = emptyMap())))
```

Also cover pairing before hello, duplicate pairing, unsupported version, and frames received after disconnect.

- [ ] **Step 2: Write failing mission-transfer tests**

Cover correct begin/chunk/complete, wrong ID, chunk before begin, duplicate begin, declared-size overflow, incomplete completion, checksum mismatch, and reset on disconnect. Use a deterministic SHA-256 fixture and never write to disk.

- [ ] **Step 3: Run focused tests and verify failure**

Run: `.\gradlew.bat :protocol-core:test --tests '*RelaySessionStateTest' --tests '*MissionTransferStateTest'`

Expected: compilation failure because the state types do not yet exist.

- [ ] **Step 4: Implement deterministic state machines**

The session state machine must accept only legal frame order. The mission state machine must keep only the active transfer metadata and digest input; file storage remains outside `protocol-core`.

- [ ] **Step 5: Run all module tests**

Run: `.\gradlew.bat :protocol-core:test`

Expected: all tests pass.

- [ ] **Step 6: Commit state machines**

```powershell
git add protocol-core/src/main protocol-core/src/test
git commit -m "feat: add relay protocol state machines"
```

### Task 5: Verify the completed protocol-core module

**Files:**
- Modify: `relay-gateway/protocol-core/CONTRACT.md` only if verification exposes a contract mismatch
- Create: `protocol-core/src/test/kotlin/com/skycommand/relay/protocol/ProtocolFuzzTest.kt`

**Interfaces:**
- Consumes all public protocol-core interfaces from Tasks 2-4.
- Produces a repeatable, device-free verification result for the first module.

- [ ] **Step 1: Add deterministic malformed-input tests**

Generate a fixed list of malformed byte arrays covering truncation, invalid UTF-8, oversized strings, invalid Base64, path traversal, invalid IDs, and unknown fields. Assert that every input returns a protocol result and never throws.

- [ ] **Step 2: Run the complete JVM verification**

Run: `.\gradlew.bat :protocol-core:test`

Expected: all tests pass.

Run: `.\gradlew.bat :protocol-core:check`

Expected: exit code `0` with no compilation or test failures.

- [ ] **Step 3: Review public dependency boundaries**

Run: `rg -n "android\\.|dji\\.|okhttp|WebSocket|JSONObject" protocol-core/src/main`

Expected: no output. Jackson parser names may appear inside the private codec implementation, but no Jackson type may appear in public signatures.

- [ ] **Step 4: Commit the verified module**

```powershell
git add protocol-core relay-gateway/protocol-core/CONTRACT.md
git commit -m "test: verify relay protocol core contract"
```

## Coverage Check

- Frame definitions and every limit in `protocol-core/CONTRACT.md` are covered by Task 2.
- UTF-8 handling, strict parsing, unknown frames, and stable rejection are covered by Task 3.
- Handshake ordering and mission transfer ordering are covered by Task 4.
- No-disk behavior, no-Android behavior, and no-third-party-exception behavior are covered by Tasks 4 and 5.
- The later `relay-gateway` WebSocket adapter is intentionally outside this plan; it starts only after `protocol-core` is verified.

## Self-Review Checklist

- [ ] No task depends on an implementation file not created by an earlier task.
- [ ] Every public type used by a later task is introduced in Task 2 or Task 4.
- [ ] Existing desktop `paired` compatibility is explicit.
- [ ] Direct flight commands are excluded.
- [ ] All tests run without an Android device or DJI SDK.
