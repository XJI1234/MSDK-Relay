# android-permission-adapter module contract

Status: implemented and verified
Version: 1.0.0
Parent module: app-runtime
Logical Gradle path: :app-runtime:android-permission-adapter

## Single responsibility

`android-permission-adapter` is the Android implementation of the
`PermissionPort` seam owned by `app-runtime:permission-coordinator`. It
translates the platform-neutral request for `RUNTIME` and `USB_ACCESS` into
Android runtime permission checks, Activity Result requests, USB accessory
broadcasts, and USB permission requests.

It owns no permission policy beyond the fixed mapping documented below. It
does not coordinate application startup, start or stop a foreground service,
initialize DJI, open a WebSocket, persist relay settings, or produce user
interface text.

## Public interface

The adapter exposes the existing `PermissionPort` interface to the runtime
composition root and one Android-only lifecycle operation:

```text
AndroidPermissionAdapter.attach(
    activity,
    activityResultRegistry,
    lifecycleOwner,
) -> AndroidPermissionAdapter

adapter implements PermissionPort
adapter.snapshot() -> PermissionSnapshot
adapter.request(required, callback) -> PermissionCancellation
adapter.close() -> Unit
```

`attach` must be called before `lifecycleOwner` reaches `STARTED`. The
`activity` must be the same window that owns `activityResultRegistry`, and the
registry must remain alive until `close`. The adapter registers one stable
Activity Result launcher and one non-exported USB broadcast receiver. The
caller owns the adapter instance and calls `close` before destroying the
owning Android lifecycle.

The adapter never exposes `Activity`, `Intent`, `UsbAccessory`, permission
strings, `Exception`, or Android callback objects through `PermissionPort`.

## Fixed Android mapping

`PermissionKind.RUNTIME` maps to the permissions already required by the
existing mobile application:

| Android version | Requested permissions |
| --- | --- |
| API 33 and newer | `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `READ_PHONE_STATE`, `RECORD_AUDIO`, `POST_NOTIFICATIONS` |
| API 29 through 32 | the four permissions above except `POST_NOTIFICATIONS`, plus `READ_EXTERNAL_STORAGE` |
| API 24 through 28 | the four permissions above except `POST_NOTIFICATIONS`, plus `READ_EXTERNAL_STORAGE` |

The adapter removes permissions that are not declared or not applicable to
the current SDK before launching the request. It never requests a permission
that is already granted.

`PermissionKind.USB_ACCESS` maps to the currently attached USB accessory.
`GRANTED` means an accessory exists and `UsbManager.hasPermission` is true.
`DENIED` means an accessory exists and Android has not granted access.
`UNKNOWN` means no accessory is currently attached. The adapter does not
pretend that a missing accessory is authorized.

## Request and state rules

1. `snapshot` is side-effect free and returns a new immutable view of runtime
   permission state and USB accessory state.
2. A request for only already-granted kinds is not sent to Android. The
   coordinator normally handles this shortcut, but the adapter preserves the
   same result if called directly.
3. At most one request is active. A second request is rejected by the
   `PermissionCoordinator`; the adapter itself may throw
   `IllegalStateException` if an integration bypasses that coordinator.
4. A runtime request launches exactly once. Its result is reconciled with a
   fresh permission check, so incomplete, reordered, or duplicate result maps
   cannot grant a permission that Android did not grant.
5. A USB request uses an explicit, package-scoped `PendingIntent`. If no
   accessory exists, the request waits for the next attach broadcast or until
   its cancellation is called. If the accessory is detached while waiting,
   the state becomes `UNKNOWN` and the request remains cancellable.
6. The callback is terminal and is delivered at most once. It is delivered
   when every required kind is `GRANTED`, or when a required kind is
   `DENIED`/`PERMANENTLY_DENIED`. An `UNKNOWN` USB state alone does not report
   success or failure.
7. Cancellation is idempotent. It unregisters the active operation from the
   adapter, cancels Android delivery where possible, and guarantees that
   later Activity Result or USB callbacks cannot reach the caller.
8. `close` is idempotent. It unregisters Android listeners and cancels the
   active request. No callback is delivered after `close` returns.
9. Broadcasts and Activity Result callbacks may arrive on any Android
   callback turn, but all adapter state transitions are serialized on the
   adapter lock. Listener and platform cleanup failures do not escape as
   Android crashes.

The adapter records that a runtime permission was requested so it can classify
the Android case where `shouldShowRequestPermissionRationale` is false after
an earlier denial as `PERMANENTLY_DENIED`. A first-time denial is classified
as `DENIED`. This history is private adapter metadata and is not relay
configuration.

## Lifecycle and security

- Registration occurs only while the supplied lifecycle is active.
- The USB receiver is not exported and accepts only the adapter's generated
  permission action plus Android accessory attach/detach actions.
- The USB permission `PendingIntent` is explicit to this application package,
  immutable, and uses a unique action derived from the package name.
- `close` must be called from the composition root; the adapter does not own
  the Activity or process lifecycle.
- The adapter does not log permission names, accessory identities, intent
  contents, or exception messages.

## Failure behaviour

- A platform check failure is represented by the coordinator's `PORT_FAILURE`
  rejection or terminal `Failed` result; Android exceptions do not cross the
  seam.
- A denied runtime request produces a snapshot with `DENIED` or
  `PERMANENTLY_DENIED`; it is not represented as a successful callback.
- A missing USB accessory produces `UNKNOWN`, never `GRANTED`.
- Duplicate and late callbacks are ignored after terminal completion,
  cancellation, or close.

## Tests required before implementation is complete

The module tests must cover:

- API 24, API 32, and API 33 runtime permission mapping;
- already-granted permissions being skipped;
- partial grant, full grant, ordinary denial, and permanent denial;
- incomplete and duplicate Activity Result maps;
- USB attached, granted, denied, detached, and attach-after-request states;
- cancellation before and after each platform callback;
- duplicate and late callbacks from both Android request mechanisms;
- close during an active request and repeated close;
- platform exceptions, listener isolation, and serialized state transitions;
- the non-exported receiver and explicit PendingIntent configuration where
  Android instrumentation coverage is available.
