# relay-gateway.mission-transfer module contract

Status: Approved and implemented
Version: 1.0.0
Parent module: `relay-gateway`
Gradle path: `:relay-gateway:mission-transfer`

This is the only contract, usage guide, public interface description, behavior specification, and acceptance basis for this module. The implementation must not introduce a second design document that changes the rules below.

## 1. Purpose

`mission-transfer` owns the integrity of one mission file transfer within one active desktop session. It accepts the three mission transfer frames, stages their bytes through an injected `MissionSink`, verifies the declared size and SHA-256, and reports the terminal result to the desktop through an injected result publisher.

The module is a deep module: callers provide a session, a frame, a sink, and a publisher. They do not implement chunk ordering, byte counting, digest calculation, replacement, cancellation, or exception redaction themselves.

## 2. One responsibility

The module is responsible for:

- accepting `mission-begin`, `mission-chunk`, and `mission-complete`;
- allowing at most one active transfer per session generation;
- appending chunks in arrival order;
- counting bytes without retaining the whole mission in module memory;
- checking the declared byte count and SHA-256;
- handing a verified `StagedMission` to the injected sink;
- aborting incomplete staging when a transfer fails, is replaced, or its session ends;
- publishing a redacted `MissionResultFrame` for protocol-visible terminal outcomes.

The module is not responsible for:

- interpreting WPMZ, KMZ, wayline, or DJI business semantics;
- uploading, starting, pausing, resuming, or stopping a DJI mission;
- creating or exposing an absolute phone file path;
- owning WebSocket, OkHttp, Android, DJI SDK, database, or UI objects;
- maintaining connection state or creating session generations;
- retaining all mission bytes in memory;
- deciding the order of unrelated commands or telemetry.

`MissionSink` is the seam to the future `wayline-mission` module. A production sink may stream to private storage, but its public result must expose only an abstract readable handle.

## 3. Public interface

### 3.1 Construction

```text
MissionTransfer(sink, resultPublisher) -> MissionTransfer
```

The constructor accepts dependencies. It does not create a sink, publisher, executor, file, network object, or Android object.

### 3.2 Frame entry point

```text
accept(activeSession, frame) -> MissionTransferResult
```

`frame` must be one of:

- `MissionBeginFrame`;
- `MissionChunkFrame`;
- `MissionCompleteFrame`.

The surrounding gateway routes only these three frame types here. A non-mission frame is a caller error and must not alter transfer state. The module may return `UnsupportedFrame` for this programming error; it must not throw.

Every call is associated with `ActiveSession.generation`. State from one generation can never be used by another generation.

### 3.3 Session cleanup

`MissionTransfer` implements `MissionSessionCleanup`:

```text
abort(generation, reason) -> void
```

This method is idempotent. It removes the generation's active transfer and calls `MissionSink.abort`. It does not publish a result because the session is ending and its outbound publisher must not be used to revive an old session.

### 3.4 Results

```text
Accepted
Completed(stagedMission)
Rejected(kind)
UnsupportedFrame
```

`Accepted` means the frame was accepted and, for `mission-begin`, staging is open. `Completed` means the sink accepted the complete staged mission and the success result was submitted to the publisher. `Rejected` means the frame was not accepted; the named kind is stable and safe to show.

The rejection kinds are exactly:

```text
TRANSFER_NOT_ACTIVE
TRANSFER_ALREADY_ACTIVE
TRANSFER_SUPERSEDED
TRANSFER_SIZE_MISMATCH
TRANSFER_CHECKSUM_MISMATCH
TRANSFER_FAILED
```

`TRANSFER_SUPERSEDED` is a terminal result for the old transfer when a different ID begins. The new begin then proceeds independently. The old sink is aborted before the new sink begins.

## 4. Sink seam

```text
MissionSink.begin(metadata) -> Accepted | Rejected
MissionSink.append(bytes) -> Accepted | Rejected
MissionSink.complete() -> StagedMission | Rejected
MissionSink.abort(reason) -> void
```

`MissionMetadata` contains only:

```text
transferId
fileName
size
sha256
```

`StagedMission` contains:

```text
transferId
fileName
size
sha256
readableByMissionModule
```

`readableByMissionModule` is an abstract `MissionReadable` handle. It is not a `String`, `File`, `Path`, URI, or Android-specific type. The phone's storage implementation remains private to the sink.

The module passes defensive byte copies to the sink. Sink rejection is treated as `TRANSFER_FAILED`; the exception or sink detail is never sent to the desktop.

## 5. Transfer rules

1. `mission-begin` creates a transfer after `MissionSink.begin` accepts it.
2. A second begin with the same ID preserves the current transfer and returns `TRANSFER_ALREADY_ACTIVE`.
3. A begin with a different ID aborts the old transfer, publishes `TRANSFER_SUPERSEDED` for the old ID, then attempts the new begin.
4. A chunk without a matching active transfer returns `TRANSFER_NOT_ACTIVE` and does not call the sink.
5. A matching chunk is appended exactly once and increases the running byte count by its raw byte length.
6. A chunk that would exceed the declared size aborts the transfer and returns `TRANSFER_SIZE_MISMATCH`.
7. `mission-complete` requires the running byte count to equal the declared size.
8. Completion then compares the lower-case SHA-256 digest of all appended raw bytes with the declared digest.
9. A size mismatch aborts the transfer and returns `TRANSFER_SIZE_MISMATCH`.
10. A digest mismatch aborts the transfer and returns `TRANSFER_CHECKSUM_MISMATCH`.
11. A successful sink completion produces one `MissionResultFrame(id, true, ...)` and one `Completed` result.
12. Every terminal rejection produces at most one `MissionResultFrame(id, false, safeDetail)` for that transfer ID.
13. A publisher failure never changes transfer state and never throws through the module interface.
14. A sink exception is converted to `TRANSFER_FAILED`, the active transfer is removed, and a best-effort abort is attempted.
15. `abort(generation, reason)` prevents late chunks and completion frames from writing to that generation's sink.
16. Concurrent calls are serialized per module instance. A transfer cannot be appended, completed, replaced, and cancelled in an interleaved state.

The protocol-core module has already validated frame fields, file name, declared size, chunk size, and SHA-256 format before this module is called. This module owns transfer-level validation only; it does not duplicate JSON or protocol parsing.

## 6. Result publication

The publisher seam is:

```text
publish(activeSession, MissionResultFrame) -> PublishResult
```

The module never holds a writer or transport. Publication is best effort and must be attempted with the same `ActiveSession` that supplied the frame. A stale-session publisher rejection is ignored; it must not route the result to a newer session.

Safe details are fixed strings:

| Kind | Detail |
| --- | --- |
| `TRANSFER_NOT_ACTIVE` | `Mission transfer is not active` |
| `TRANSFER_ALREADY_ACTIVE` | `Mission transfer is already active` |
| `TRANSFER_SUPERSEDED` | `Mission transfer was superseded` |
| `TRANSFER_SIZE_MISMATCH` | `Mission transfer size does not match` |
| `TRANSFER_CHECKSUM_MISMATCH` | `Mission transfer checksum does not match` |
| `TRANSFER_FAILED` | `Mission transfer failed` |

No exception class, exception message, phone path, temporary file name, stack trace, or sink-specific detail may be published.

## 7. Acceptance tests

Tests must cover, at minimum:

- complete begin/chunk/complete flow and exact sink bytes;
- success result publication and returned staged handle;
- no active transfer;
- same-ID duplicate begin;
- different-ID replacement and old-transfer abort;
- chunk ID mismatch;
- overrun, underrun, and checksum mismatch;
- sink rejection and sink exception at every operation;
- cleanup cancellation and late frames;
- generation isolation;
- publisher rejection and publisher exception;
- concurrent delivery without duplicate append or completion;
- architecture restrictions: no Android, DJI, network, transport, path, or file dependency in the implementation.

## 8. Change rule

The implementation cannot expand this module's responsibility silently. Any new frame, error kind, sink field, or externally observable ordering rule requires an explicit contract update and corresponding tests before implementation.
