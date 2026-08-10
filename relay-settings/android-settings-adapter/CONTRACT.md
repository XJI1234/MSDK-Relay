# android-settings-adapter module contract

Status: approved for implementation
Version: 1.0.0
Parent module: relay-settings
Gradle path: :relay-settings:android-settings-adapter

## Single responsibility

Provide the Android implementation of `RelaySettingsBackend` using one private `SharedPreferences` file. It is the only adapter allowed to convert the opaque relay-settings record to Android key/value storage.

It does not validate endpoints, generate device IDs, decide settings recovery, expose preferences to callers, connect the desktop, request permissions, or access DJI.

## Interface and storage ownership

```text
AndroidRelaySettingsBackend.create(context) -> RelaySettingsBackend
backend.update(change) -> committed RelaySettingsRecord?
```

The adapter stores only schema version, endpoint, and device ID under private application storage. `RelaySettingsStore` remains owner of schema migration and recovery rules; the adapter preserves nullable raw values exactly and does not inspect their contents.

The application must run relay settings in one Android process. `SharedPreferences` does not provide cross-process transactions, so declaring a second process for the app, its service, or a receiver that writes relay settings is forbidden. Within the app process, `update` is serialized, transforms one complete immutable record, and uses synchronous `commit()` so a successful return means the replacement is durable.

Malformed preference types, unavailable storage, a failed commit, or a transform exception are represented by a generic adapter exception with no raw value or Android exception text. `RelaySettingsStore` maps it to its stable failure result.

## Tests

Pure JVM tests cover record encode/decode, null handling, preservation of opaque values, atomic update serialization, failed commit mapping, malformed storage mapping, and no raw-value leakage. Android instrumentation tests cover real `SharedPreferences` durability and process configuration before release.
