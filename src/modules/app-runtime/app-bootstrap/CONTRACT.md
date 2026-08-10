# app-bootstrap module contract

Status: approved for implementation
Version: 1.0.0
Parent module: app-runtime
Gradle path: :app-runtime:app-bootstrap

## Single responsibility

Create no business modules itself; instead, own the ordered startup and reverse-order shutdown of injected runtime modules. It is the composition seam used by the eventual `app-runtime` facade.

It does not know Android `Activity` or `Service`, request permissions, create notifications, connect the computer, call DJI, or interpret business state. Each injected module owns its own platform adapter and contract.

## Interface

```text
AppBootstrap.create(modules) -> AppBootstrap
bootstrap.start() -> Started | Rejected(ALREADY_RUNNING | TRANSITION_IN_PROGRESS | MODULE_FAILURE)
bootstrap.stop() -> Stopped | Rejected(ALREADY_STOPPED | TRANSITION_IN_PROGRESS | MODULE_FAILURE)
bootstrap.snapshot() -> STOPPED | STARTING | RUNNING | STOPPING | FAILED
```

`BootstrapModule` has a stable name, `start()`, and `stop()` operation. Modules are started in declaration order and stopped in exact reverse order. A start failure stops every module already started, in reverse order, before returning `MODULE_FAILURE`. Stop attempts all started modules even when one fails. Failure results contain only the stable module name and phase, never exception details.

Calls are synchronous and thread-safe. Only one transition is active. A duplicate request is rejected without invoking a module. A later `start` may retry after failure; no partial started set is reused. Listener callbacks are deliberately not part of this module; the facade reads the snapshot.

## Tests

Cover empty modules, normal order, reverse stop order, duplicate and concurrent calls, startup failure rollback, stop failure with continued cleanup, retry after failure, module exceptions, and state transitions.
