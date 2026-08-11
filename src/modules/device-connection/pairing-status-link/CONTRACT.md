# pairing-status-link module contract

Status: implemented and verified
Version: 1.0.0
Parent module: device-connection
Logical Gradle path: :device-connection:pairing-status-link

## Single responsibility

This module accepts already-normalized observations of the remote controller's
actual aircraft-pairing state and applies them to the pairing field of the
single `DeviceStateStore` snapshot.

It does not request pairing, stop pairing, call DJI SDK APIs, schedule or
cancel operations, decide whether a request is allowed, infer a pairing state
from aircraft or remote-controller connection, persist state, publish
telemetry, manage streaming or missions, open relay connections, request
permissions, or render user interface.

## Public interface

```text
PairingStatusPort.start(listener) -> PairingStatusSubscription
PairingStatusPort.stop() -> Unit
subscription.cancel() -> Unit

PairingStatusLink.create(store, port) -> PairingStatusLink

link.start() -> PairingStatusStartResult
link.stop() -> PairingStatusStopResult
```

The port reports only:

```text
PairingStatusSignal(sourceRevision, state)
```

`sourceRevision` is strictly positive and increases for every signal emitted
by one port instance during the process lifetime. `state` is the existing
platform-neutral `PairingState` enum. The port must never expose DJI keys,
callbacks, errors, Android objects, serial numbers, product IDs, or raw
exception details.

`start` may synchronously call the listener before it returns its subscription.
Callers must treat that initial signal exactly like every later signal.

## Lifecycle rules

1. `PairingStatusLink.start` creates at most one active port observation. A
   repeated call while active returns `AlreadyStarted` and does not call the
   port again.
2. `PairingStatusLink.stop` cancels the stored subscription and asks the port
   to stop. It is idempotent: the first call returns `Stopped`, later calls
   return `AlreadyStopped`.
3. A signal is accepted only for the currently active run. Signals delivered
   while `start` is still obtaining its subscription are buffered, then applied
   in arrival order once the subscription is installed.
4. Signals delivered after stop, after failed start, or from a previous run
   are ignored. Restarting creates a new run and accepts only that run's
   signals.
5. Failure to create an observation makes `start` return
   `Rejected("pairing status listener unavailable")`. The failure does not
   leave an active run or a partially installed subscription.
6. Subscription cancellation and `port.stop` failures are contained. They do
   not keep the run active and do not let late signals reach the state store.

## State rules

1. The link maps each accepted signal directly to
   `DeviceStatePatch.pairing(signal.sourceRevision, signal.state)`.
2. The link does not translate, synthesize, or reject valid `PairingState`
   values. In particular, it does not decide that `PAIRING` is `PAIRED`, that
   `UNKNOWN` is `IDLE`, or that a disconnected aircraft changes the pairing
   state.
3. The `DeviceStateStore` is the sole authority for cross-source ordering.
   Its stale-revision result is normal and must not cause a retry, state
   rewrite, or diagnostic.
4. Invalid signals, including a non-positive revision or any failure while
   applying a signal, are contained and reported only as
   `PairingStatusDiagnosticKind.INVALID_SIGNAL`. They do not terminate the
   active observation or stop later valid signals.
5. A port failure during start, stop, or cancellation is reported only as
   `PairingStatusDiagnosticKind.PORT_FAILURE`. Diagnostics are best effort;
   a diagnostic-sink failure is contained.

## Dependency rules

- The module depends only on `:device-connection:device-state-store`.
- It must not depend on `pairing-controller`, `dji-operation-coordinator`,
  `sdk-lifecycle`, `remote-controller-link`, `aircraft-link`, an Android
  adapter, DJI MSDK, telemetry, live-stream, wayline-mission, relay-gateway,
  app-runtime, or Android UI types.
- A separate Android adapter will satisfy `PairingStatusPort`. It alone may
  know DJI's pairing-status key and its vendor-specific values.
- The final composition root starts this link alongside the other device
  observers after DJI SDK registration is usable. The link itself does not
  enforce SDK ordering.

## Verification requirements

JVM contract tests must cover initial synchronous signals, all
`PairingState` values, positive and stale revisions, duplicate start, stop,
duplicate stop, signals buffered during subscription creation, restart,
stale callbacks after stop and restart, port-start failure, subscription and
stop failures, invalid signals, and diagnostic-sink exception isolation.

The module has no Android dependency. Android-device verification belongs only
to the later `android-pairing-status-adapter` module.
