# Android Remote Controller Adapter Design

## Goal

Add one Android/DJI adapter that supplies the existing
`RemoteControllerPort` seam. It converts DJI MSDK v5 remote-controller facts
into `RemoteControllerSignal` values for `remote-controller-link`.

The adapter is deliberately not a device-connection facade. It does not
persist state, decide whether the SDK is ready, observe the aircraft or flight
controller, perform pairing, execute commands, publish telemetry, manage a
relay session, or render a user interface.

## Chosen Shape

The new logical Gradle module is:

```text
:device-connection:android-remote-controller-adapter
```

It has one public factory:

```text
AndroidRemoteControllerPort.create() -> RemoteControllerPort
```

The returned port retains no Android UI object, DJI product object, serial
number, app key, network endpoint, or desktop protocol value. It owns only the
MSDK listener registrations required to produce remote-controller facts.

Three alternatives were considered:

1. One adapter for the remote controller only. This is chosen because its
   interface maps exactly to `RemoteControllerPort`, so changes to aircraft,
   pairing, telemetry, and operations stay local to their own modules.
2. One combined remote-controller and aircraft adapter. This reduces a small
   amount of listener setup but makes two independent fact sources share a
   lifecycle and failure path.
3. A large Android device adapter covering all DJI product state. This would
   make it easy for unrelated responsibilities to leak into one module and is
   rejected.

## Interface And Data Flow

```text
DJI MSDK KeyManager
  -> AndroidRemoteControllerPort
  -> RemoteControllerPort
  -> RemoteControllerLink
  -> DeviceStateStore
```

`RemoteControllerLink` remains the only consumer that applies a
`RemoteControllerSignal` to `DeviceStateStore`. The Android adapter never
imports `DeviceStateStore`, `LinkState`, pairing types, command types, or
relay-protocol types.

The port interface already defines the complete external surface:

```text
port.start(listener) -> PortSubscription
port.stop() -> Unit
```

`start` establishes at most one active MSDK observation. It returns a
subscription whose `cancel` releases only that active observation. Calling
`stop` is idempotent and also releases the active observation. A redundant
`start` must not register another MSDK listener or replace the original
listener; it receives a no-op subscription.

The adapter publishes `RemoteControllerSignal` with:

- a strictly positive `sourceRevision` that increases for every published
  signal and never resets during the process lifetime;
- `connected` copied only from the MSDK remote-controller connection fact;
- `displayModel` containing a non-sensitive platform display name only when
  MSDK v5.17 exposes a stable remote-controller model. When no such value is
  available, it is `null`. The adapter never derives a model from an aircraft
  type, serial number, firmware string, product ID, or exception text.

The first available snapshot may be delivered synchronously during `start`.
`RemoteControllerLink` is designed to queue that signal until its own listener
registration succeeds.

## Internal Design

Only the implementation has an internal `DjiRemoteControllerApi` seam. It
hides `KeyManager`, DJI keys, listener-owner tokens, key value types, and DJI
errors behind two operations: establish an observation that supplies a
normalized connection/model snapshot, and release that observation.

`AndroidRemoteControllerPort` owns an active generation and a monotonically
increasing signal revision. Every MSDK callback carries the generation that
created it. A callback is delivered only when its generation is still active;
callbacks after `cancel`, `stop`, or a later start are discarded. User listener
calls occur outside the adapter lock, and a listener exception is contained.

The MSDK wrapper may reconcile separate connection and model changes into one
immutable snapshot, but callers see only complete `RemoteControllerSignal`
values. It must never report a fabricated connected state. A disconnected
snapshot normalizes the optional model to `null`.

MSDK listener registration failure is converted to one stable,
non-sensitive `RemoteControllerPort` startup failure for the existing
`RemoteControllerLink` to map into its documented rejection. Listener removal
failures are contained, because stopping must still invalidate the generation.
Raw DJI error types, exception messages, and stack traces do not cross the
port seam.

## Dependencies

The module depends on `:device-connection:remote-controller-link` for the
port/value seam and carries the direct DJI MSDK v5 dependency needed by its
private wrapper. No pure Kotlin device-connection module gains a DJI or Android
dependency.

It is composed only after `android-dji-sdk-adapter` has completed SDK
registration. The adapter does not enforce or infer that ordering; composition
and start ordering remain the responsibility of the future Android composition
root.

## Verification

JVM contract tests will cover:

- initial connected and disconnected snapshots;
- optional model normalization and disconnect model clearing;
- one MSDK registration for repeated `start` calls;
- idempotent `cancel` and `stop`;
- increasing revisions across connection/model changes and restart;
- synchronous callback delivery during registration;
- stale callbacks after cancel, stop, and a new generation;
- MSDK registration/removal failures and user-listener exceptions;
- proof that only `RemoteControllerSignal` crosses the public seam.

The Android Debug build verifies that the MSDK v5.17 key wrapper compiles.
Real-device verification is still required for USB/remote-controller attach and
detach, controller-model availability across supported devices, application
recreation, and MSDK listener cleanup.

## Non-Goals

This work does not implement aircraft observation, flight-controller
observation, pairing status observation, pairing operations, telemetry, live
streaming, wayline operations, relay transport, permissions, foreground
service behaviour, or final application composition. Each requires its own
contract and module.
