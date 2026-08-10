# dji-stream-adapter module contract

Status: approved for implementation
Version: 1.0.0
Parent module: live-stream
Gradle path: :live-stream:dji-stream-adapter

## 1. Single responsibility

This module adapts validated live-stream start/stop requests to the DJI stream SDK through the shared `DjiOperationCoordinator`, and translates terminal DJI outcomes into `stream-state-store` transitions.

It does not validate URLs, interpret relay commands, own live-stream facts, publish telemetry, manage WebSocket, or create an executor or scheduler.

## 2. Public interface

```text
DjiStreamAdapter.create(stateStore, djiPort, coordinator, timeoutMillis = 30000)
adapter.start(validatedConfig) -> Accepted(cancellation) | Rejected(reason)
adapter.stop() -> Accepted(cancellation) | Rejected(reason)
```

`DjiStreamPort` is the only DJI seam. Its `start` receives a validated configuration, metric callback, and terminal completion callback; its `stop` receives terminal completion. `Accepted` means the operation was submitted, not that DJI succeeded. State becomes active/inactive only when the coordinator reports the terminal result.

## 3. Failure and concurrency

State-store precondition failures and coordinator submission rejection return stable enum reasons. Adapter exceptions, DJI failure, timeout, cancellation, duplicate completion, late metrics, and late callbacks are converted to safe state transitions. The coordinator serializes all DJI calls and supplies cancellation and timeout. Every callback carries the operation generation returned by the state store; callbacks from an older start/stop cannot affect a newer operation.

The adapter is JVM-thread-safe and owns no mutable business state. The injected coordinator and DJI port must remain available while an accepted operation is running. No public result exposes a URL credential, DJI object, raw exception, or stack trace.

## 4. Tests

Tests cover successful start/stop, metric forwarding, duplicate completion, start/stop precondition rejection, adapter exception, coordinator rejection, timeout, cancellation, late callbacks, and serialized operations through the shared coordinator.
