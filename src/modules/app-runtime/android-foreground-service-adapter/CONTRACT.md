# android-foreground-service-adapter module contract

Status: implemented and verified
Version: 1.0.0
Parent module: app-runtime
Logical Gradle path: :app-runtime:android-foreground-service-adapter

## Single responsibility

This module is the Android implementation of the `ForegroundServicePort` seam.
It starts and stops one relay foreground service, creates its notification
channel and notification, and reports whether that service actually entered or
left foreground execution.

It does not start relay-gateway, DJI, telemetry, live stream, mission, or
settings modules. It does not decide when the relay should run. The pure
`ForegroundServiceController` owns transition rules; `AppRuntime` owns the
startup order; the Android application supplies only notification resources.

## Public interface

```text
AndroidForegroundServicePort.create(applicationContext, notificationSpec)
  -> AndroidForegroundServicePort

port implements ForegroundServicePort
port.start(callback) -> Unit
port.stop(callback) -> Unit
port.close() -> Unit
```

`notificationSpec` contains a stable channel id, channel name resource id,
notification text resource id, notification id, and small-icon resource id.
It contains no business state and never changes while the service is running.
`create` registers a non-exported, package-scoped receiver before the first
start. `close` unregisters that receiver; the composition root calls it only
after the controller has stopped the service.

## Start and stop rules

1. `start` generates one opaque operation id, stores its callback, and uses
   `ContextCompat.startForegroundService` on Android O and newer.
2. `RelayForegroundService` creates the channel and calls `startForeground`
   before it reports `started`. A failure before that point reports `failed`.
3. The port accepts only the matching operation id. Duplicate, late, foreign,
   or post-close broadcasts are ignored.
4. `stop` completes immediately when this adapter has not observed a running
   service. Otherwise it sends an explicit stop command to the relay service;
   the service publishes `stopped` from its destruction path. The command is
   never sent through an implicit intent.
5. Only one port operation is active. A second direct caller receives
   `IllegalStateException`; the pure controller prevents this in normal use.
6. `close` is idempotent and prevents all later callbacks. It never starts,
   stops, or restarts business modules.

## Notification and security rules

- The service creates exactly one notification channel, with low importance,
  on Android O and newer.
- The service notification is ongoing and has the configured small icon.
- All control and status intents are explicit to this application package.
- The status receiver is registered with `RECEIVER_NOT_EXPORTED` where the
  platform supports it; no exported receiver or bindable service is added.
- Android exceptions, intent extras, notification data, and stack traces never
  cross `ForegroundServicePort`.

## Verification coverage

- start, stop, and failure terminal callbacks at the port boundary;
- exact operation-id matching, unexpected direction, duplicate, late, foreign,
  and post-close callbacks;
- repeated direct calls, callback isolation, synchronous platform throws, and
  platform resource release;
- notification specification validation;
- The Android build verifies the service declaration, required foreground
  service permissions, and compilation of the `startForeground` path. A
  device-level instrumentation test remains an application integration test,
  because this library does not own the host application's resources or test
  runner.
