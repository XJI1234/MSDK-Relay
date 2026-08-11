# android-remote-controller-adapter module contract

Status: implemented and verified
Version: 1.0.0
Parent module: device-connection
Logical Gradle path: :device-connection:android-remote-controller-adapter

## Single responsibility

This module is the Android DJI MSDK v5 implementation of the existing
`RemoteControllerPort` seam. It observes only remote-controller connection
facts and an optional non-sensitive display model, then publishes normalized
`RemoteControllerSignal` values.

It does not own SDK registration, persist device state, observe an aircraft or
flight controller, infer pairing, execute DJI operations, publish telemetry,
manage streaming or missions, open relay connections, request permissions, or
render user interface.

## Public interface

```text
AndroidRemoteControllerPort.create() -> RemoteControllerPort

port.start(listener) -> PortSubscription
port.stop() -> Unit
subscription.cancel() -> Unit
```

The factory returns the existing platform-neutral `RemoteControllerPort`. No
DJI key, manager, callback, error, Android object, serial number, product ID,
or exception detail is exposed through the port or its signals.

`start` establishes at most one active platform observation. Calling it again
while active creates no extra DJI listener and returns a no-op subscription.
`stop` and `subscription.cancel` are idempotent. They release the active
platform observation and invalidate its callback generation.

## Signal rules

Each accepted platform fact becomes:

```text
RemoteControllerSignal(sourceRevision, connected, displayModel?)
```

1. `sourceRevision` is strictly positive, increases for every published
   signal, and never resets while the process is alive.
2. `connected` reflects only the MSDK remote-controller connection fact. The
   adapter never infers it from aircraft state, USB state, a previous value, or
   a product identifier.
3. `displayModel` is present only when MSDK provides a stable non-sensitive
   remote-controller display name. It is never derived from a serial number,
   firmware version, aircraft model, product ID, or exception. It is `null`
   when unavailable and always `null` when disconnected.
4. The initial snapshot may arrive synchronously inside `start`; callers must
   treat it as a normal signal.
5. A duplicate platform value may be published with a newer revision. The
   state store, not this adapter, owns cross-source ordering and deduplication.

## Lifecycle and failure rules

1. Every successful platform registration receives one callback generation.
   Callbacks from a cancelled, stopped, superseded, or failed generation are
   ignored.
2. User listeners run outside adapter locks. A listener exception is contained
   and cannot prevent cleanup or later signals.
3. If platform registration cannot be established, `start` fails with only the
   stable reason `remote controller listener unavailable`. Raw DJI exceptions,
   messages, and stack traces never cross the seam. The existing
   `RemoteControllerLink` converts this into its documented rejection.
4. Listener-release failures are contained. They never keep a generation
   active or permit late callbacks to reach a caller.
5. The adapter uses no Activity, Fragment, View, Context, network endpoint,
   desktop protocol data, App Key, or process-wide SDK shutdown operation.

## Dependency rules

- Direct DJI MSDK dependencies exist only in this Android adapter.
- The adapter depends on `:device-connection:remote-controller-link` only for
  `RemoteControllerPort`, `RemoteControllerSignal`, and `PortSubscription`.
- It must not depend on `device-state-store`, `aircraft-link`,
  `pairing-controller`, `sdk-lifecycle`, telemetry, live-stream,
  wayline-mission, relay-gateway, app-runtime, or Android UI types.
- The final Android composition root starts this port only after DJI SDK
  registration is usable. Ordering is not inferred or enforced here.

## Verification requirements

JVM tests must cover initial connected/disconnected signals, optional-model
normalization, increasing revisions across changes and restart, duplicate
start, cancel, stop, duplicate stop, synchronous registration callbacks,
stale callbacks after cancel/stop/new generation, platform registration and
release failures, and user-listener exception isolation.

The Android Debug build must compile the MSDK v5.17 listener wrapper. A real
device must verify remote-controller USB attach/detach, controller-model
availability, app recreation, and listener cleanup. Aircraft, pairing, and
telemetry verification are explicitly outside this module.
