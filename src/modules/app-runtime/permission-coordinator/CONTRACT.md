# permission-coordinator module contract

Status: approved for implementation
Version: 1.0.0
Parent module: app-runtime
Gradle path: :app-runtime:permission-coordinator

## Single responsibility

Coordinate Android runtime-permission and USB-access authorization requests. It exposes a stable, platform-neutral snapshot and request lifecycle to `app-bootstrap` and `foreground-service`.

It does not decide whether a business command is allowed, call DJI, start a service, read Activity or Service objects, persist settings, or translate permission results into user-interface text.

## Interface

```text
PermissionCoordinator.create(port) -> PermissionCoordinator
coordinator.snapshot() -> PermissionSnapshot
coordinator.request(required, listener)
  -> Started(cancellation)
  | AlreadySatisfied(snapshot)
  | Rejected(EMPTY_REQUEST | ALREADY_IN_PROGRESS)
coordinator.onChanged(listener) -> Registration

PermissionPortCallback.completed(snapshot) -> terminal platform result
PermissionPortCallback.failed() -> terminal platform failure
```

`PermissionKind` is the stable set `RUNTIME` and `USB_ACCESS`. `PermissionState` is `UNKNOWN`, `GRANTED`, `DENIED`, or `PERMANENTLY_DENIED`. The Android adapter implements `PermissionPort`; it owns actual permission strings, Activity Result APIs, USB broadcasts, and lifecycle attachment.

Only one request may be active. A request completes exactly once with `Completed(snapshot)`, `Denied(snapshot)`, or `Failed`. Cancellation is idempotent and causes `Cancelled`; callbacks from the cancelled operation or any older operation are ignored. A request for only already-granted permissions does not call the port.

Port callbacks are terminal and may arrive from any thread, more than once, or after cancellation. `completed(snapshot)` becomes `Completed` only when every requested kind is granted; otherwise it becomes `Denied`. `failed()` becomes `Failed`. The coordinator serializes state changes, ignores stale/duplicate callbacks, and isolates listener exceptions. No callback or failure exposes Android objects, permission strings, exception messages, or stack traces.

## Tests

Cover initial and every permission state, empty requests, already-granted requests, accepted requests, duplicate requests, completion/denial/failure, cancellation and late callbacks, duplicate callbacks, concurrent requests, port throws, listener throws, registration, and snapshot immutability.
