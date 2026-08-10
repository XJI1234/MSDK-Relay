# WaylineMission facade contract

Status: approved for implementation
Version: 1.0.0
Parent module: wayline-mission
Gradle path: :wayline-mission

## 1. Single responsibility

`WaylineMission` is the sole composition facade for the mobile wayline workflow. It turns relay commands and completed relay file transfers into one staged mission, upload operations, flight-control operations, and observable public mission state.

It composes existing second-level modules. It does not parse WebSocket frames, manage a relay session, connect DJI devices, choose a flight path, expose a file path or DJI object, or implement DJI SDK calls. DJI and storage behavior enter only through injected adapters.

## 2. Public interface

```text
WaylineMission.create(dependencies) -> WaylineMission
mission.commandHandler() -> relay-gateway CommandHandler
mission.missionSink() -> relay-gateway MissionSink
mission.snapshot() -> MissionSnapshot
mission.onChanged(listener) -> Registration
```

`commandHandler()` accepts only the six documented `wayline.*` command names. It completes each relay command exactly once. `wayline.generate` succeeds only after a valid generated KMZ is safely staged and its state is recorded. Upload and control commands succeed only after the matching DJI operation completes successfully; accepted submission is not reported as success.

`missionSink()` accepts a completed KMZ transfer from `relay-gateway`. The transfer is staged atomically using `mission-staging`. A successful completion records the staged mission state before the gateway is told that the transfer completed. Its returned readable content is a lazy adapter over the injected staged-content reader and owns no bytes.

`snapshot()` and `onChanged()` are the public read-only state interface. Listener registration has the delivery and unregistration guarantees documented by `mission-state-store`.

## 3. Dependencies and ownership

`WaylineMissionDependencies` supplies these seams:

- `StagingStorage`: durable temporary/current KMZ storage.
- `StagedMissionContentReader`: reads the current staged KMZ for upload or for the gateway transfer result.
- `MissionUploadPort`: adapts DJI waypoint upload.
- `MissionControlPort`: adapts DJI start, pause, resume, and stop.
- `DjiOperationCoordinator`: the shared serialized DJI-operation owner from `device-connection`.
- optional bounded upload/control timeouts and state diagnostics.

The facade creates and exclusively owns `MissionStaging`, `MissionStateStore`, `MissionUploader`, `MissionExecutor`, and `WaylineCommandHandler`. Callers must retain every supplied adapter for the facade lifetime. The facade never closes or owns the injected coordinator.

## 4. State, ordering, and concurrency

- A successful generated or transferred file is recorded as `FileStaged` before its success reaches the relay gateway.
- All staging paths share one facade staging lock. Therefore a completed replacement cannot be observed in storage without its corresponding state transition, and concurrent generate/transfer paths cannot associate state with the wrong file.
- Upload/control completion is tied to the captured mission revision by lower modules. A late, duplicate, timed-out, cancelled, or replaced-mission callback cannot mutate newer mission state or complete a relay command twice.
- The facade is JVM-thread-safe. It serializes only staging-to-state handoff; DJI serialization remains exclusively with `DjiOperationCoordinator`.

## 5. Stable failure behavior

Invalid command fields, missing confirmation, staging failure, unavailable content, invalid state, DJI adapter failure, timeout, cancellation, and stale/duplicate callbacks result in a single safe relay rejection or an existing stable state transition. No public result contains a filesystem path, raw exception, DJI object, or KMZ bytes.

## 6. Required tests

Integration tests cover generated and transferred staging, state synchronization before gateway success, successful and failed upload/control terminal command completion, precondition rejection, timeout/cancellation propagation, stale callbacks after mission replacement, duplicate callbacks, transfer abort, concurrent generation/transfer contention, and safe state listener use.
