# android-aircraft-adapter module contract

Status: implemented and verified
Version: 1.0.0
Parent module: device-connection
Logical Gradle path: :device-connection:android-aircraft-adapter

## Single responsibility

This module is the Android DJI MSDK v5 implementation of the existing
`AircraftPort` seam. It observes only aircraft connection, flight-controller
connection, and an optional non-sensitive aircraft display model, then
publishes normalized `AircraftSignal` values.

It does not own DJI SDK registration, persist device state, observe the remote
controller, infer pairing, execute DJI operations, publish telemetry, manage
streaming or missions, open relay connections, request permissions, or render
user interface.

## Public interface

```text
AndroidAircraftPort.create() -> AircraftPort

port.start(listener) -> AircraftPortSubscription
port.stop() -> Unit
subscription.cancel() -> Unit
```

The factory returns the existing platform-neutral `AircraftPort`. No DJI key,
manager, callback, error, Android object, serial number, product ID, or raw
exception detail is exposed through the port or its signals.

`start` establishes at most one active MSDK observation. A repeated start
creates no extra MSDK listener, does not replace the original listener, and
returns a no-op subscription. `stop` and `subscription.cancel` are idempotent;
both invalidate the active callback generation and release its platform
listeners.

## Signal rules

Each accepted platform fact becomes:

```text
AircraftSignal(sourceRevision, aircraftConnected, flightControllerConnected, displayModel?)
```

1. `sourceRevision` is strictly positive, increases for every published
   signal, and never resets while the process is alive.
2. `aircraftConnected` reflects only the MSDK product connection fact.
3. `flightControllerConnected` reflects only the MSDK flight-controller
   connection fact when the aircraft is connected. It is forcibly `false` when
   `aircraftConnected` is `false`; contradictory signals are forbidden.
4. `displayModel` is present only for a stable non-sensitive MSDK product-type
   value. It is never derived from a serial number, firmware version, remote
   controller type, product ID, or exception. It is `null` when unavailable
   and always `null` when the aircraft is disconnected.
5. The initial snapshot may arrive synchronously inside `start`; callers must
   treat it as a normal signal.
6. A duplicate platform value may be published with a newer revision. Cross-
   source ordering and deduplication remain the responsibility of the state
   store.

## Lifecycle and failure rules

1. Every successful platform registration receives one callback generation.
   Callbacks from a cancelled, stopped, superseded, or failed generation are
   ignored.
2. User listeners run outside adapter locks. A listener exception is contained
   and cannot prevent cleanup or later signals.
3. If platform registration cannot be established, `start` fails with only the
   stable reason `aircraft listener unavailable`. Raw DJI exceptions, messages,
   and stack traces never cross the seam. The existing `AircraftLink` converts
   this into its documented rejection.
4. Platform listener-release failures are contained. They never keep a
   generation active or permit late callbacks to reach a caller.
5. The adapter uses no Activity, Fragment, View, Context, network endpoint,
   desktop protocol data, App Key, or process-wide SDK shutdown operation.

## Dependency rules

- Direct DJI MSDK dependencies exist only in this Android adapter.
- The adapter depends on `:device-connection:aircraft-link` only for
  `AircraftPort`, `AircraftSignal`, and `AircraftPortSubscription`.
- It must not depend on `device-state-store`, `remote-controller-link`,
  `pairing-controller`, `sdk-lifecycle`, telemetry, live-stream,
  wayline-mission, relay-gateway, app-runtime, or Android UI types.
- The final Android composition root starts this port only after DJI SDK
  registration is usable. Ordering is not inferred or enforced here.

## Verification requirements

JVM tests must cover initial connected/disconnected facts, model
normalization, flight-controller normalization when the aircraft disconnects,
increasing revisions across changes and restart, duplicate start, cancel,
stop, duplicate stop, synchronous registration callbacks, stale callbacks
after cancel/stop/new generation, platform registration and release failures,
and user-listener exception isolation.

The Android Debug build must compile the MSDK v5.17 listener wrapper. A real
device must verify aircraft and flight-controller attach/detach, product-model
availability, app recreation, and listener cleanup. Remote-controller,
pairing, telemetry, streaming, and mission verification are outside this
module.
