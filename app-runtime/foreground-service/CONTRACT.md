# foreground-service module contract

Status: approved for implementation
Version: 1.0.0
Parent module: app-runtime
Gradle path: :app-runtime:foreground-service

## Single responsibility

Own the lifecycle state of the Android foreground service that keeps the relay process alive. It translates start/stop requests to a platform port and accepts only terminal callbacks for the current operation.

It does not create or stop WebSocket sessions, call DJI, own settings or business state, request permissions, create notification text, or read Activity/Service globals. The Android adapter owns notification channels, service intents, and actual `startForeground` calls.

## Interface

```text
ForegroundServiceController.create(port, diagnosticSink?) -> controller
controller.start() -> Accepted | Rejected(ALREADY_RUNNING | TRANSITION_IN_PROGRESS | PORT_FAILURE)
controller.stop()  -> Accepted | Rejected(ALREADY_STOPPED | TRANSITION_IN_PROGRESS | PORT_FAILURE)
controller.snapshot() -> STOPPED | STARTING | RUNNING | STOPPING | FAILED
controller.onChanged(listener) -> Registration
```

`ForegroundServicePort.start(callback)` and `stop(callback)` are the only platform seam. The port must call a callback at most once in normal operation, but the controller defensively ignores duplicate, late, and cross-operation callbacks. Start/stop acceptance means only that the platform request was submitted; `RUNNING` or `STOPPED` is reported only after the matching terminal callback.

The controller is synchronous and thread-safe. One transition can exist at a time. A port throw maps to `PORT_FAILURE` and moves the controller to `FAILED`; a later `start` may retry. Listener exceptions are isolated and never roll back a committed state.

## Tests

Cover every state, duplicate start/stop, accepted transitions, port failures, successful and failed callbacks, late callbacks from an old operation, duplicate callbacks, concurrent calls, listener failures and registration.
