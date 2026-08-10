# settings-store module contract

Status: approved for implementation
Version: 1.0.0
Parent module: relay-settings
Gradle path: :relay-settings:settings-store

## Single responsibility

Own the durable relay-settings record: its schema version, atomic updates, migration, endpoint recovery, and the persistence port used by `device-identity`. It is the only module allowed to know the durable record layout.

It does not open a relay connection, generate a device identity, validate an endpoint itself, use Android persistence APIs, observe settings changes, log raw settings, or decide application lifecycle.

## Interface

```text
RelaySettingsStore.create(backend) -> RelaySettingsStore

store.load() -> Available(RelaySettingsSnapshot) | Unavailable(SettingsStoreFailure)
store.setEndpoint(value)
  -> Saved(snapshot)
  | Rejected(EndpointRejection)
  | Unavailable(SettingsStoreFailure)
store.clearEndpoint() -> Saved(snapshot) | Unavailable(SettingsStoreFailure)

store.readOrCreate(candidate) -> stored device ID
```

`RelaySettingsStore` implements `DeviceIdentityStorage`; only `device-identity` should call `readOrCreate` in production. `RelaySettingsSnapshot` exposes a validated endpoint or `null`; it never exposes the device identity. The root facade will compose the snapshot with `DeviceIdentity.identity()`.

`RelaySettingsBackend` is the sole Android/persistence boundary. Its `update` operation must be atomic and linearizable across every process using the same storage. It supplies the current nullable record to a transformation, durably commits the returned record once, and returns that committed record. Backends may throw only to signal an unsuccessful read/write/transaction; the store maps the error to `BACKEND_FAILURE` and never exposes its details.

The durable record is `RelaySettingsRecord(schemaVersion, endpoint, deviceId)`. Current schema is version `1`; version `0` is the supported legacy form with the same fields and is migrated to version `1`. Unknown, negative, or future versions return `UNSUPPORTED_SCHEMA` and are never overwritten.

## Data and recovery rules

1. A missing record represents empty settings. `load` returns an empty snapshot without creating a record.
2. Every mutation first normalizes the existing record. Version `0` becomes version `1` in the same atomic write.
3. A stored endpoint is validated through `endpoint-settings`. An invalid, unsafe, or malformed endpoint is cleared during normalization and never reaches a snapshot.
4. `setEndpoint` validates its argument before opening a backend transaction. Invalid input returns its exact `EndpointRejection` and changes nothing.
5. `clearEndpoint` persists `endpoint = null` while preserving any device identity.
6. `readOrCreate` requires a protocol-valid candidate. It atomically returns the existing valid device ID, or replaces a missing/invalid stored ID with the candidate. This is the required corruption recovery for `device-identity`.
7. A device ID is valid exactly when it is nonblank, control-character-free, and 1 through 128 Unicode code points. It is not included in snapshots or failures.

All public methods are synchronous, deterministic for a given backend result, and safe for concurrent calls. The store does not cache reads or write failures: another process's update is visible to the next call, and a later caller may retry after a backend failure. Last successfully committed endpoint mutation wins according to backend linearization order.

## Failure handling

| Condition | Result | Durable state |
| --- | --- | --- |
| Invalid submitted endpoint | `Rejected(reason)` | unchanged; backend not called |
| Backend exception | `Unavailable(BACKEND_FAILURE)` | no local state/caching |
| Unsupported schema | `Unavailable(UNSUPPORTED_SCHEMA)` | unchanged; no destructive recovery |
| Missing record | empty successful snapshot | no record created by `load` |
| Invalid saved endpoint | cleared by successful normalization | recovered version-1 record |
| Missing/invalid saved device ID on `readOrCreate` | candidate stored atomically | recovered version-1 record |

No result, thrown validation exception, or diagnostic may contain the endpoint, query, device ID, raw record, backend exception text, or stack trace.

## Tests

JVM tests cover missing data; write, clear, and reload; valid endpoint preservation; all validation rejections without backend access; schema-0 migration; invalid endpoint recovery; unknown schema protection; backend read/write errors and retries; identity read-or-create behavior including corrupt records; endpoint preservation during identity recovery; concurrent endpoint writes and concurrent identity contenders.

## Compatibility rules

`RelaySettingsSnapshot`, result/failure enums, backend atomicity, schema-version meanings, and the opaque-record boundary are stable. Any schema change must add a migration and tests before it is written. Removing a supported migration, exposing `deviceId` through a snapshot, or allowing a raw endpoint outside this module requires a contract and consumer update first.
