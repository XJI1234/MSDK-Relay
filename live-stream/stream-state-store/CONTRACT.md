# stream-state-store module contract

Status: approved for implementation
Version: 1.0.0
Parent module: live-stream
Gradle path: :live-stream:stream-state-store

## 1. Single responsibility

This module is the sole owner of live-stream lifecycle facts and metrics. It validates state transitions, isolates callbacks belonging to an older operation, and exposes immutable snapshots and safe change notifications.

It does not validate RTMP syntax, call DJI, send WebSocket frames, read video, persist passwords or tokens, or decide command results.

## 2. Public interface

```text
StreamStateStore.create(diagnosticSink?) -> StreamStateStore
store.requestStart(validatedConfig) -> Accepted(operationId) | Rejected(reason)
store.requestStop() -> Accepted(operationId) | Rejected(reason)
store.markStarted(operationId, metrics) -> Applied | IgnoredStale
store.markStopped(operationId, notice) -> Applied | IgnoredStale
store.markFailed(operationId, notice) -> Applied | IgnoredStale
store.markDeviceUnavailable(notice) -> Applied(snapshot)
store.updateMetrics(operationId, metrics) -> Applied | IgnoredStale
store.snapshot() -> StreamSnapshot
store.onChanged(listener) -> Registration
```

`StreamSnapshot` contains revision, lifecycle (`STOPPED`, `STARTING`, `STREAMING`, `STOPPING`, `FAILED`), whether a target is configured, safe notice text, and optional metrics. It never contains the RTMP URL, query, token, or password. `StreamOperationId` is an opaque numeric generation used only to reject late callbacks.

Metrics use resolution up to 64 code points, FPS `0..240`, video bitrate `0..1,000,000 kbps`, and RTT `0..60,000 ms`; absent metrics are allowed.

## 3. State and failure behavior

Start is accepted from STOPPED or FAILED and enters STARTING. Stop is accepted from STARTING or STREAMING and enters STOPPING. A matching successful completion enters STREAMING or STOPPED. A matching failure enters FAILED. A stale or duplicate operation callback returns `IgnoredStale`, changes nothing, and notifies nobody. Invalid metrics throw `IllegalArgumentException` before state changes. Invalid state requests return stable enum reasons.

The store is synchronous, thread-safe, and does not block its state lock while calling listeners. Listener exceptions are recorded through the diagnostic seam and cannot stop other listeners or roll back state. Unregistration waits for an in-flight callback and prevents queued callbacks after it returns, including reentrant registration/unregistration cases.

## 4. Tests

JVM tests cover initial state, every legal transition, every invalid request, stale and duplicate callbacks, metric boundaries, device-unavailable reset, immutable snapshots, listener order, listener failure, unregistration, reentrant callbacks, concurrent requests, and concurrent reads during writes.
