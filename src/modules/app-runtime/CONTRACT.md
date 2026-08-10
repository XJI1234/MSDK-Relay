# app-runtime module contract

Status: facade implementation in progress
Version: 1.0.0
Gradle path: :app-runtime

## Single responsibility

Own the Android relay runtime lifecycle and compose runtime responsibilities in a fixed order: permissions, foreground service, then injected business modules. `android-permission-adapter` is supplied by the Android application composition layer as the concrete `PermissionPort`; it is not a dependency of this platform-neutral facade. This is the only module allowed to combine those runtime responsibilities.

It does not implement Android permission APIs, notification channels, WebSocket sessions, DJI operations, settings, telemetry, live stream, or missions. Those concerns enter through the public interfaces of their owning modules or through adapters supplied by the application.

## Interface

```text
AppRuntime.create(permissionCoordinator, foregroundService, bootstrap) -> AppRuntime
runtime.start(requiredPermissions)
  -> Accepted(cancellation)
  | AlreadyRunning
  | TransitionInProgress
  | Rejected(PERMISSION_REQUEST | FOREGROUND_SERVICE | MODULES)
runtime.stop() -> Accepted | AlreadyStopped | TransitionInProgress | Rejected(STOP_FAILURE)
runtime.snapshot() -> STOPPED | WAITING_PERMISSIONS | STARTING_SERVICE | STARTING_MODULES | RUNNING | STOPPING | FAILED
runtime.onChanged(listener) -> Registration
```

`start` is accepted when work has begun, not when the service or business modules are ready. It requests the supplied permission set, starts the foreground service only after permissions complete successfully, then starts `AppBootstrap`. `stop` stops business modules before the foreground service. A permission denial/cancellation, service failure, or module failure leaves `FAILED` or `STOPPED` according to the terminal result and never reports `RUNNING`.

Only one lifecycle transition is active. Repeated and concurrent calls are deterministic. Cancellation is effective while waiting for permissions; late permission/service callbacks are ignored after the runtime leaves their operation. Listener failures are isolated. No result exposes Android objects, permission names, exception messages, notification data, or business state.

## Tests

Cover startup with already-granted permissions, asynchronous permission completion, denial/cancellation, service failure and synchronous callbacks, module failure with service cleanup, normal reverse shutdown, duplicate/concurrent calls, late callbacks, listener failure, and registration.
