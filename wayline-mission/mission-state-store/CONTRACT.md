# mission-state-store module contract

Status: implemented and verified
Version: 1.0.0
Parent module: `wayline-mission`
Gradle path: `:wayline-mission:mission-state-store`

## 1. Single responsibility

This module is the sole owner of the public facts for the current wayline mission: safe file metadata, upload state, and execution state. It combines those facts into immutable snapshots and notifies read-only consumers in order.

This module does not store KMZ bytes, filesystem paths, file handles, DJI objects, or raw exceptions. It does not write files, upload, execute missions, parse commands, or send network traffic. File ownership belongs to `mission-staging`; DJI operations belong to `mission-uploader` and `mission-executor`.

## 2. Public interface

```text
MissionStateStore.create(diagnosticSink = no-op) -> MissionStateStore
store.snapshot() -> MissionSnapshot
store.apply(event) -> Applied(snapshot) | IgnoredStale(sourceRevision)
store.onChanged(listener) -> Registration
```

`MissionSnapshot` contains:

```text
revision: Long                         // strictly increases after every accepted event
missionRevision: Long?                 // current staged-file generation; null without a file
file: MissionMetadata?                 // filename, expectedSize, SHA-256 only
upload: NOT_UPLOADED | UPLOADING(0..100) | UPLOADED | FAILED
execution: NOT_STARTED | STARTING | EXECUTING | PAUSED | STOPPING | FINISHED | FAILED
```

`MissionStateEvent` is a closed event set:

```text
FileStaged(sourceRevision, metadata)                       // source STAGING
FileCleared(sourceRevision)                                // source STAGING
UploadChanged(sourceRevision, missionRevision, state)      // source UPLOAD
ExecutionChanged(sourceRevision, missionRevision, state)   // source EXECUTION
```

`missionRevision` is read from `snapshot()` before an uploader or executor starts work. It is not a path, byte content, or DJI task id.

## 3. Commit rules and invariants

- Every event has a positive `sourceRevision`. Versions are compared independently for `STAGING`, `UPLOAD`, and `EXECUTION`. A duplicate or older event from the same source returns `IgnoredStale`, changes nothing, and sends no notification.
- Every accepted `FileStaged` creates a strictly increasing `missionRevision`, atomically replaces old metadata, and resets upload to `NOT_UPLOADED` and execution to `NOT_STARTED`.
- `FileCleared` clears the file and mission revision and resets both states. Clearing an already-empty store still consumes the STAGING source revision but does not create a snapshot or notification.
- Upload and execution events can affect only the currently matching `missionRevision`. A callback for a replaced or cleared mission returns `IgnoredStale`, even when its source revision is newer.
- Without a current file, upload and execution events cannot change state. `UPLOADED`, and execution states `STARTING`, `EXECUTING`, `PAUSED`, `STOPPING`, and `FINISHED`, require an existing file whose upload state is `UPLOADED`.
- `FAILED` is a safe public fact and carries no DJI exception, path, or implementation detail. Upload and execution failures may be recorded while a current file exists.
- Upload progress must be in `0..100`. Metadata is defensively validated for a safe basename, positive size, and 64-character hexadecimal SHA-256.
- `apply` commits before returning `Applied`. Listener callbacks happen outside the lock and in committed snapshot revision order. A listener failure cannot prevent later listeners or alter committed state.
- `Registration.unregister()` is idempotent. Before it returns, callbacks already running on other threads for that listener have finished; unregistering from the listener's own callback never waits for itself. After it returns, no queued callback for that listener starts.
- The diagnostic sink receives only `LISTENER_FAILURE`, and exceptions thrown by the sink are swallowed. Public results never expose internal exceptions.

## 4. Call sequence

1. After `mission-staging` succeeds, the composition layer submits `FileStaged`.
2. `mission-uploader` reads `missionRevision` from the snapshot and submits progress and final result for that generation.
3. Only after the snapshot shows `UPLOADED` may `mission-executor` submit start, execute, pause, stop, finish, or failure for that generation.
4. Any new file or clear operation invalidates generations held by older uploader or executor callbacks.

## 5. Test requirements

Cover the initial state, staging and replacement, clearing, progress boundaries, upload and execution preconditions, failure state, old and duplicate versions for each source, invalid mission generations, input validation, notification order, listener failures, concurrent unregister, and self-unregister.
