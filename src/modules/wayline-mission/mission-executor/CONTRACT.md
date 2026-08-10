# mission-executor module contract

Status: implemented and verified
Version: 1.0.0
Parent module: wayline-mission
Gradle path: :wayline-mission:mission-executor

## 1. Single responsibility

This module controls the lifecycle of an already uploaded wayline mission: start, pause, resume, and stop. It serializes each control operation through the shared DJI operation coordinator and records public execution state in mission-state-store.

It does not upload, stage, parse, plan, connect devices, or expose DJI objects or exceptions. The injected MissionControlPort is the only DJI operation seam.

## 2. Public interface

executor.start(), pause(), resume(), stop() -> Accepted(cancellation) | Rejected(reason)

Start requires a current file, upload state UPLOADED, and execution state NOT_STARTED or FAILED. Pause requires EXECUTING. Resume requires PAUSED. Stop requires STARTING, EXECUTING, or PAUSED. Only one control operation may be active.

Accepted means submitted, not confirmed. Start records STARTING before submission and becomes EXECUTING on success. Pause records PAUSED on success. Resume records EXECUTING on success. Stop records STOPPING before submission and becomes FINISHED on success. Any operation failure, timeout, cancellation, adapter exception, or coordinator rejection records FAILED, except that a rejected precondition leaves state unchanged.

## 3. Lifecycle and concurrency

The module is JVM-safe. The coordinator owns operation serialization, timeout, cancellation, duplicate completion suppression, and callback ordering. The executor additionally prevents overlapping commands from this module.

Every callback is tied to the missionRevision captured when the command starts. Replaced or cleared missions make old callbacks harmless. A command callback is terminal and idempotent. Timeout must be 1,000..60,000 ms.

## 4. Failure behavior

Public rejection reasons are NO_MISSION, NOT_UPLOADED, INVALID_STATE, ALREADY_ACTIVE, and OPERATION_REJECTED. They contain no raw exception, path, DJI object, or byte content. Invalid adapter progress is not applicable because control operations do not report progress.

## 5. Test requirements

JVM tests cover all four commands, valid transitions, every precondition rejection, adapter failure and exception, coordinator rejection, timeout, cancellation, duplicate completion, stale callbacks after mission replacement, and concurrent command calls.

## 6. Terminal completion listener

Each control request accepts an optional `ExecutionTerminalListener`. It is invoked once only for an accepted operation after its terminal coordinator outcome: `SUCCEEDED`, `FAILED`, `TIMED_OUT`, or `CANCELLED`. The matching state update is attempted before listener delivery. Precondition and submission rejections return synchronously and do not invoke the listener. Listener failures are contained.
