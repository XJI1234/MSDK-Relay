# mission-uploader module contract

Status: implemented and verified
Version: 1.0.0
Parent module: wayline-mission
Gradle path: :wayline-mission:mission-uploader

## 1. Single responsibility

This module uploads the currently staged KMZ mission to the aircraft through the shared DJI operation coordinator, and records only upload progress and the terminal public result in mission-state-store.

It does not stage or delete files, parse KMZ, execute or control a mission, own device connection facts, or expose DJI exceptions. The staged byte reader and DJI upload adapter are injected seams.

## 2. Public interface

MissionUploader.create(stateStore, contentReader, uploadPort, operationCoordinator, timeoutMillis = 30000) -> MissionUploader
uploader.start() -> Accepted(cancellation) | Rejected(reason)

The content reader receives safe MissionMetadata and returns bytes only for the currently staged file. The upload port receives metadata, bytes, a progress callback, and an operation completion callback. It must call completion exactly once in normal operation; duplicate or late calls are ignored by the coordinator and uploader.

Accepted means the upload operation was submitted. It does not mean the aircraft confirmed success. A successful terminal callback changes state to UPLOADED; failure, timeout, cancellation, reader failure, adapter failure, or submission rejection changes state to FAILED.

## 3. Preconditions and state

- start requires a current file and mission-state-store upload state NOT_UPLOADED or FAILED.
- Only one upload owned by this uploader may be active. A second start returns ALREADY_ACTIVE and does not read bytes or call DJI.
- The uploader reads file metadata and missionRevision from one snapshot before reading content.
- Before submitting the operation it records UPLOADING(0).
- Progress values must be 0..100. Invalid adapter progress is ignored and cannot escape the module.
- Every progress and terminal update carries the uploader source revision and missionRevision. A replaced or cleared mission therefore makes old callbacks harmless.
- A new staged mission is independent; callbacks for the old mission cannot change the new mission state.

## 4. Lifecycle, cancellation, and concurrency

The module is JVM-safe and has no Android lifecycle. The injected coordinator serializes DJI operations and supplies timeout and cancellation semantics. The timeout must be 1,000..60,000 ms.

start is thread-safe. Calls race through one lock; at most one accepted upload is active. Cancellation is delegated to the coordinator. After cancellation, callbacks from the old adapter are ignored. Completion is terminal and idempotent.

The caller must keep the injected reader and upload port available while an accepted operation runs. The uploader does not retain file bytes after the operation finishes.

## 5. Failure behavior

| Situation | Result | State |
| --- | --- | --- |
| no staged file | NO_MISSION | unchanged |
| active upload | ALREADY_ACTIVE | unchanged |
| content unavailable or reader throws | CONTENT_UNAVAILABLE | FAILED |
| invalid timeout or coordinator rejection | OPERATION_REJECTED | FAILED |
| upload adapter failure or exception | FAILED | FAILED |
| coordinator timeout | TIMED_OUT | FAILED |
| cancellation | CANCELLED | FAILED |

Public failures contain only stable enum values. No raw exception, path, DJI object, or byte content is returned.

## 6. Test requirements

JVM tests cover successful upload, progress 0 and 100, no mission, duplicate start, reader failure, adapter failure, adapter exception, coordinator rejection, timeout, queued cancellation, running cancellation, duplicate completion, late progress after cancellation, replacement of the staged mission, and concurrent start calls.
