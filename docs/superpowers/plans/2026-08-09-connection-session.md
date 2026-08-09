# Connection Session Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the approved `relay-gateway/connection-session` contract as a pure Kotlin/JVM module with deterministic tests for lifecycle, handshake, reconnection, cleanup, stale callback isolation, and concurrency.

**Architecture:** The module owns one in-memory session state machine and serializes every public operation, transport callback, and timer callback through an internal event loop. Network, outbound sending, scheduling, cleanup, diagnostics, and state notification remain injected interfaces; `protocol-core` remains the only frame decoder and field validator.

**Tech Stack:** Kotlin 2.1.0, JVM 17, Gradle Kotlin DSL, JUnit 5, `kotlin.test`, existing `:protocol-core` module.

## Global Constraints

- The implementation must satisfy `relay-gateway/connection-session/CONTRACT.md` version 0.1.0.
- The module must not depend on Android, DJI SDK, OkHttp, Ktor, files, or databases, and must not directly depend on or expose a concrete JSON library type; JSON remains encapsulated by `protocol-core`.
- The module must not instantiate or own mission transfer state.
- `connection-session` is the only creator and invalidator of `SessionGeneration`.
- Existing uncommitted changes in `protocol-core` belong to another agent and must not be staged or edited.
- Tests must run on the JVM without real network access or wall-clock sleeping.

---

## File Structure

- Modify: `settings.gradle.kts` to include `:relay-gateway:connection-session`.
- Modify: `relay-gateway/connection-session/CONTRACT.md` status from pending review to approved after implementation verification.
- Create: `relay-gateway/connection-session/build.gradle.kts` for the pure JVM subproject.
- Create: `relay-gateway/connection-session/src/main/kotlin/com/skycommand/relay/gateway/session/SessionModel.kt` for immutable public values and operation results.
- Create: `relay-gateway/connection-session/src/main/kotlin/com/skycommand/relay/gateway/session/SessionPorts.kt` for transport, outbound, cleanup, scheduler, notifier, and diagnostic interfaces.
- Create: `relay-gateway/connection-session/src/main/kotlin/com/skycommand/relay/gateway/session/SerialEventLoop.kt` for in-process linearization without owning a permanent thread.
- Create: `relay-gateway/connection-session/src/main/kotlin/com/skycommand/relay/gateway/session/ExecutorOrderedStateNotifier.kt` for ordered, listener-isolated notification on an injected executor.
- Create: `relay-gateway/connection-session/src/main/kotlin/com/skycommand/relay/gateway/session/ConnectionSession.kt` for lifecycle, handshake, generation, retry, and cleanup behavior.
- Create: `relay-gateway/connection-session/src/test/kotlin/com/skycommand/relay/gateway/session/SessionTestDoubles.kt` for deterministic adapters.
- Create: `relay-gateway/connection-session/src/test/kotlin/com/skycommand/relay/gateway/session/SessionConfigurationTest.kt`.
- Create: `relay-gateway/connection-session/src/test/kotlin/com/skycommand/relay/gateway/session/ConnectionSessionLifecycleTest.kt`.
- Create: `relay-gateway/connection-session/src/test/kotlin/com/skycommand/relay/gateway/session/ConnectionSessionHandshakeTest.kt`.
- Create: `relay-gateway/connection-session/src/test/kotlin/com/skycommand/relay/gateway/session/ConnectionSessionReconnectTest.kt`.
- Create: `relay-gateway/connection-session/src/test/kotlin/com/skycommand/relay/gateway/session/ConnectionSessionConcurrencyTest.kt`.
- Create: `relay-gateway/connection-session/src/test/kotlin/com/skycommand/relay/gateway/session/ExecutorOrderedStateNotifierTest.kt`.

---

### Task 1: Module Setup and Immutable Model

**Files:**
- Modify: `settings.gradle.kts`
- Create: `relay-gateway/connection-session/build.gradle.kts`
- Create: `relay-gateway/connection-session/src/main/kotlin/com/skycommand/relay/gateway/session/SessionPorts.kt`
- Create: `relay-gateway/connection-session/src/main/kotlin/com/skycommand/relay/gateway/session/ConnectionSession.kt` with only creation and initial snapshot behavior in this task
- Create: `relay-gateway/connection-session/src/test/kotlin/com/skycommand/relay/gateway/session/SessionTestDoubles.kt` with the minimal dependencies needed by creation tests
- Test: `relay-gateway/connection-session/src/test/kotlin/com/skycommand/relay/gateway/session/SessionConfigurationTest.kt`
- Create: `relay-gateway/connection-session/src/main/kotlin/com/skycommand/relay/gateway/session/SessionModel.kt`

**Interfaces:**
- Produces: `SessionConfig`, `SessionState`, `SessionGeneration`, `ActiveSession`, `SessionSnapshot`, `SessionEndReason`, `SessionStateEvent`, `StartResult`, `StopResult`, and `SessionCreationResult`.
- Consumes: `protocol-core.validate(HelloFrame)` for `deviceId` validation.

- [x] **Step 1: Add the Gradle module and write configuration tests before model code**

```kotlin
@Test
fun validConfigurationCreatesStoppedSessionWithoutOpeningTransport() {
    val fixture = SessionFixture.create()
    val session = fixture.session
    assertEquals(SessionSnapshot(SessionState.STOPPED, null, null), session.snapshot())
    assertEquals(0, fixture.connector.openCount)
}

@Test
fun rejectsHandshakeTimeoutOutsideContractRange() {
    assertIs<ConfigurationRejected>(SessionFixture.createResult(handshakeTimeoutMillis = 999))
    assertIs<ConfigurationRejected>(SessionFixture.createResult(handshakeTimeoutMillis = 60_001))
}
```

- [x] **Step 2: Run the focused test and verify RED**

Run: `./gradlew :relay-gateway:connection-session:test --tests '*SessionConfigurationTest'`

Expected: compilation fails because the session model and factory do not exist.

- [x] **Step 3: Implement immutable model values and configuration validation**

```kotlin
enum class SessionState { STOPPED, CONNECTING, AWAITING_PAIRING, ACTIVE, RECONNECT_WAIT }

sealed interface StartResult {
    data object StartAccepted : StartResult
    data class AlreadyRunning(val snapshot: SessionSnapshot) : StartResult
}

sealed interface StopResult {
    data object Stopped : StopResult
    data object AlreadyStopped : StopResult
}
```

Use private/internal constructors for generations, active sessions, and end reasons so callers cannot forge current authority or invalid error details.

- [x] **Step 4: Run the focused test and verify GREEN**

Run: `./gradlew :relay-gateway:connection-session:test --tests '*SessionConfigurationTest'`

Expected: all configuration tests pass.

---

### Task 2: Dependency Interfaces and Normal Lifecycle

**Files:**
- Create: `relay-gateway/connection-session/src/main/kotlin/com/skycommand/relay/gateway/session/SerialEventLoop.kt`
- Modify: `relay-gateway/connection-session/src/main/kotlin/com/skycommand/relay/gateway/session/ConnectionSession.kt`
- Modify: `relay-gateway/connection-session/src/test/kotlin/com/skycommand/relay/gateway/session/SessionTestDoubles.kt`
- Test: `relay-gateway/connection-session/src/test/kotlin/com/skycommand/relay/gateway/session/ConnectionSessionLifecycleTest.kt`

**Interfaces:**
- Consumes: `TransportConnector`, `SessionOutbound`, `ActiveFrameConsumer`, cleanup ports, scheduler, notifier, and diagnostics.
- Produces: synchronous `start`, `stop`, `snapshot`, and listener registration behavior.

- [x] **Step 1: Write normal lifecycle and cleanup-order tests**

```kotlin
@Test
fun completesHelloPairedHandshakeAndForwardsOnlyActiveFrames() {
    val fixture = SessionFixture.create()
    assertEquals(StartAccepted, fixture.session.start())
    fixture.connector.current.open()
    assertEquals(AWAITING_PAIRING, fixture.session.snapshot().state)
    fixture.connector.current.receive(encoded(PairedFrame("desktop-session", null)))
    assertEquals(ACTIVE, fixture.session.snapshot().state)
}

@Test
fun explicitStopUsesContractCleanupOrderExactlyOnce() {
    val fixture = SessionFixture.active()
    assertEquals(Stopped, fixture.session.stop())
    assertEquals(listOf("close", "commands", "mission", "outbound", "notify"), fixture.order)
}
```

- [x] **Step 2: Run and verify RED**

Run: `./gradlew :relay-gateway:connection-session:test --tests '*ConnectionSessionLifecycleTest'`

Expected: compilation fails because the ports and lifecycle controller do not exist.

- [x] **Step 3: Implement ports, serial event loop, start, stop, snapshots, and listener registration**

The event loop must queue reentrant transport callbacks, never hold a session lock while invoking dependencies, and make concurrent public calls linearizable.

- [x] **Step 4: Run and verify GREEN**

Run: `./gradlew :relay-gateway:connection-session:test --tests '*ConnectionSessionLifecycleTest'`

Expected: lifecycle tests pass.

---

### Task 3: Handshake Validation and Active Frame Gate

**Files:**
- Test: `relay-gateway/connection-session/src/test/kotlin/com/skycommand/relay/gateway/session/ConnectionSessionHandshakeTest.kt`
- Modify: `relay-gateway/connection-session/src/main/kotlin/com/skycommand/relay/gateway/session/ConnectionSession.kt`

**Interfaces:**
- Consumes: `RelayFrameCodec.decode`, `HelloFrame`, and `PairedFrame`.
- Produces: exactly one hello, v1 compatibility, strict pre-active gate, duplicate-handshake rejection, and active frame delivery.

- [x] **Step 1: Write handshake success and rejection tests**

Cover missing `paired.protocolVersion`, explicit v1, unsupported versions, malformed frames, unknown frames, business frames before pairing, duplicate `onOpened`, duplicate handshake, attach failure, and hello-send failure.

- [x] **Step 2: Run and verify RED**

Run: `./gradlew :relay-gateway:connection-session:test --tests '*ConnectionSessionHandshakeTest'`

Expected: new behavioral assertions fail.

- [x] **Step 3: Implement protocol result mapping and frame gating**

Use only structured `Decoded`, `Rejected`, and `Ignored` results. Never parse raw JSON or instantiate protocol mission state.

- [x] **Step 4: Run and verify GREEN**

Run: `./gradlew :relay-gateway:connection-session:test --tests '*ConnectionSessionHandshakeTest'`

Expected: handshake tests pass.

---

### Task 4: Timeout, Retry, Generation Isolation, and Cleanup Failure Tolerance

**Files:**
- Test: `relay-gateway/connection-session/src/test/kotlin/com/skycommand/relay/gateway/session/ConnectionSessionReconnectTest.kt`
- Modify: `relay-gateway/connection-session/src/main/kotlin/com/skycommand/relay/gateway/session/ConnectionSession.kt`

**Interfaces:**
- Produces: deterministic timeout start point, exponential retry, retry cap, reset after active, manual retry, stale generation rejection, and full cleanup despite dependency exceptions.

- [x] **Step 1: Write deterministic scheduler and reconnect tests**

```kotlin
assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), observedDelays)
```

Also verify that timeout starts only after `SendAccepted`, stop cancels retries, manual start replaces one pending retry, old opened/bytes/closed/failure/timeout callbacks cannot affect the new generation, and every cleanup collaborator is attempted after earlier failures.

- [x] **Step 2: Run and verify RED**

Run: `./gradlew :relay-gateway:connection-session:test --tests '*ConnectionSessionReconnectTest'`

Expected: retry and stale-callback assertions fail.

- [x] **Step 3: Implement retry tokens, backoff calculation, stale callback checks, and safe cleanup calls**

Retry delay is `min(initial * 2^(n - 1), max)` without jitter. Use opaque unique tokens and never relabel old callbacks.

- [x] **Step 4: Run and verify GREEN**

Run: `./gradlew :relay-gateway:connection-session:test --tests '*ConnectionSessionReconnectTest'`

Expected: reconnect tests pass.

---

### Task 5: Ordered Notifications and Concurrency

**Files:**
- Create: `relay-gateway/connection-session/src/main/kotlin/com/skycommand/relay/gateway/session/ExecutorOrderedStateNotifier.kt`
- Test: `relay-gateway/connection-session/src/test/kotlin/com/skycommand/relay/gateway/session/ExecutorOrderedStateNotifierTest.kt`
- Test: `relay-gateway/connection-session/src/test/kotlin/com/skycommand/relay/gateway/session/ConnectionSessionConcurrencyTest.kt`

**Interfaces:**
- Produces: asynchronous ordered listener delivery, listener exception isolation, listener reentrant stop without deadlock, and linearizable concurrent start/stop.

- [x] **Step 1: Write notifier and multithreaded tests**

Use latches and bounded waits only for thread coordination; do not use sleeps for session time. Verify exactly one connection attempt under concurrent start and exactly one cleanup under concurrent stop.

- [x] **Step 2: Run and verify RED**

Run: `./gradlew :relay-gateway:connection-session:test --tests '*ConcurrencyTest' --tests '*OrderedStateNotifierTest'`

Expected: notifier and concurrency behavior is missing or failing.

- [x] **Step 3: Implement ordered notifier and finish event-loop safeguards**

Listener work must execute on an injected executor, preserve per-listener event order, skip unregistered listeners, and keep processing after listener exceptions.

- [x] **Step 4: Run and verify GREEN repeatedly**

Run: `./gradlew :relay-gateway:connection-session:test --rerun-tasks`

Expected: all module tests pass on at least three consecutive runs.

---

### Task 6: Contract Approval, Full Verification, Review, and Commit

**Files:**
- Modify: `relay-gateway/connection-session/CONTRACT.md`
- Include all files listed above.

**Interfaces:**
- Produces: an approved, independently reviewed module implementation and focused commit.

- [x] **Step 1: Mark the implemented contract approved**

Change only `状态：待审阅` to `状态：已批准并实现` after all focused tests pass.

- [x] **Step 2: Run complete verification**

Run:

```powershell
.\gradlew.bat test --rerun-tasks
git diff --check
```

Expected: `BUILD SUCCESSFUL`, zero test failures, and no diff errors.

- [x] **Step 3: Review against the contract**

Check every public type, state transition, error mapping, cleanup step, retry rule, stale callback case, forbidden dependency, and test requirement. Fix all Critical and Important findings.

- [x] **Step 4: Stage only connection-session implementation files, plan, contract status, and `settings.gradle.kts`**

Verify `git diff --cached --name-only` excludes the three unrelated `protocol-core` files.

- [x] **Step 5: Commit**

```powershell
git commit -m "feat: implement connection session lifecycle"
```

Expected: one focused commit; unrelated working-tree changes remain unstaged.

---

## Self-Review

- Spec coverage: all contract sections map to Tasks 1-6, including configuration, five-state lifecycle, handshake, generation, cleanup, retry, concurrency, privacy, and JVM-only tests.
- Placeholder scan: no TBD, TODO, or deferred implementation steps.
- Type consistency: the same `SessionGeneration`, `SessionSnapshot`, `ActiveSession`, `SessionEndReason`, and port names are used throughout.
- Scope: no Android, DJI, mission file, business command, or real WebSocket implementation is included.
