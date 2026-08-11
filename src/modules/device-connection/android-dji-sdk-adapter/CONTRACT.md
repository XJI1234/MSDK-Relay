# android-dji-sdk-adapter module contract

Status: implemented and verified
Version: 1.0.0
Parent module: device-connection
Logical Gradle path: :device-connection:android-dji-sdk-adapter

## Single responsibility

This module is the Android DJI MSDK v5 implementation of the `DjiSdkPort`
seam owned by `sdk-lifecycle`. It initializes DJI MSDK, requests application
registration after MSDK initialization completes, and reports only whether
that registration became usable or failed.

It does not observe remote-controller, aircraft, flight-controller, camera,
or battery state. It does not start pairing, execute DJI operations, publish
telemetry, control live streaming or missions, open relay connections, persist
settings, own an Activity, or render user interface.

## Public interface

```text
AndroidDjiSdkPort.create(applicationContext) -> DjiSdkPort

port.initialize(callbacks) -> PortStartResult
port.close() -> Unit
```

`create` requires an application context. It returns the existing
platform-neutral `DjiSdkPort`; no Android context, `SDKManager`, DJI error,
product identifier, exception, or MSDK callback is exposed to the caller.

The adapter has one direct collaborator, an internal MSDK manager bridge. The
bridge is not public API and may be replaced when DJI changes its API without
requiring changes to `sdk-lifecycle` or other business modules.

## Host application prerequisites

Before `create` or `initialize` is used, the final Android application must:

1. Declare `com.dji.sdk.API_KEY` in its merged application manifest, with the
   application-specific DJI App Key as its value.
2. Invoke DJI's required runtime installer from `Application.attachBaseContext`
   before any DJI MSDK API is accessed.
3. Package the native libraries and ABI configuration required by the chosen
   DJI MSDK v5 distribution.
4. Request network and device permissions through the separate app-runtime
   permission flow. This adapter neither requests nor interprets permissions.

The adapter does not store an App Key, log it, or create a default key. A
missing, invalid, or unavailable host configuration becomes a safe
initialization failure, never a fabricated ready state.

## Lifecycle rules

1. Each `initialize` creates one callback generation and delegates once to the
   MSDK manager bridge.
2. MSDK initialization completion triggers `registerApp`; only an MSDK
   registration success calls `DjiSdkCallbacks.onReady`.
3. Any MSDK initialization or registration failure calls
   `DjiSdkCallbacks.onFailure` at most once for the active generation.
4. A bridge rejection or synchronous bridge exception becomes
   `PortStartResult.Rejected` with a stable safe reason. Raw DJI errors,
   exception messages, and stack traces never cross `DjiSdkPort`.
5. Duplicate MSDK callbacks, callbacks for an older generation, and callbacks
   after `close` are ignored.
6. `close` is idempotent. It invalidates the active callback generation and
   releases adapter-local listener references. It does not attempt to globally
   shut down DJI MSDK, because MSDK is process-wide and may be shared by later
   application work.
7. A new `initialize` after `close` uses a new generation. It must not accept
   a registration result captured by a previous generation.
8. User callbacks run outside adapter locks. A user callback exception is
   contained and cannot prevent cleanup or affect another generation.

## Security and dependency rules

- Direct DJI MSDK dependencies exist only in this Android module.
- The module uses the application context only and retains no Activity,
  Fragment, View, Intent, product ID, serial number, App Key, or device model.
- The module reports only `ready`, `failure`, and safe rejection reasons.
- No network endpoint, desktop protocol, telemetry payload, mission content,
  video frame, or pairing identity may enter this module.
- `sdk-lifecycle`, `device-connection`, telemetry, live-stream, wayline,
  relay-gateway, and app-runtime must not depend on DJI classes.

## Verification requirements

JVM tests must cover initial acceptance, bridge rejection and throws,
synchronous and asynchronous success/failure, duplicate callbacks, stale
callbacks after close, repeated close, callback isolation, and reinitializing
with a new generation.

The Android Debug build must compile the MSDK v5 bridge and merged manifest.
Real-device integration verification is required for valid App Key
registration, missing/invalid App Key, unavailable network, delayed MSDK
registration, app recreation, and physical remote-controller/aircraft
connection. Product-state verification belongs to the later device-observer
adapter, not this module.
