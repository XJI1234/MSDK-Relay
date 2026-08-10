# device-identity module contract

Status: approved for implementation
Version: 1.0.0
Parent module: relay-settings
Gradle path: :relay-settings:device-identity

## Single responsibility

Create and return the stable identity of this mobile installation. It validates both restored and newly generated values against the relay protocol identifier rules, caches only a successfully resolved value, and coordinates concurrent callers in one process.

It does not persist endpoint settings, choose a computer, open a connection, authenticate the user, log an identity, access Android APIs, repair corrupt durable storage, or expose a storage exception.

## Interface

```text
DeviceIdentity.create(storage, generator?) -> DeviceIdentity

identity.identity()
  -> Available(DeviceId)
  | Unavailable(STORAGE_FAILURE | STORED_VALUE_INVALID | GENERATED_VALUE_INVALID)

DeviceIdentityStorage.readOrCreate(candidate) -> stored value
DeviceIdentityGenerator.generate() -> candidate value
```

`DeviceIdentityStorage.readOrCreate` is the module's only persistence boundary. Its implementation belongs to `settings-store`. It must be atomic and linearizable across all readers and writers using the same installation storage: it returns the already stored value, or stores and returns the supplied candidate exactly once when empty. A missing/corrupt record is a storage concern; `settings-store` must recover it before this method returns. It may throw on an I/O or transaction failure; the identity module maps that failure to `STORAGE_FAILURE` without exposing the exception.

`DeviceIdentityGenerator` exists solely to make generation deterministic in tests. The default uses a random UUID string. Production callers must not supply a predictable generator.

`DeviceId.value` is nonblank, contains no ISO control character, and has 1 through 128 Unicode code points. This is exactly the current `protocol-core` `deviceId` constraint. The value is opaque: callers may pass it to `relay-gateway` as `SessionConfig.deviceId`, but must not infer device metadata from it or use it as a credential.

## Resolution rules

1. The first successful call creates one candidate through the generator and asks storage to resolve it atomically.
2. A returned value is validated before becoming visible or cached. A stored winner from another process is valid and is returned unchanged.
3. Once a valid value is cached, every later call returns the same `DeviceId` without calling the generator or storage.
4. Concurrent calls have one in-process resolution. Every successful concurrent caller receives the same value. A failure is not cached, so a later call may retry.
5. A failed generated candidate must not be offered to storage. An invalid value returned by storage must never be replaced by this module.

## Failure and concurrency behavior

| Situation | Result | Storage/generator calls | Cached value |
| --- | --- | --- | --- |
| Valid existing or newly stored value | `Available` | one resolution at most | stored |
| Storage throws | `Unavailable(STORAGE_FAILURE)` | no implicit retry | unchanged |
| Generator throws | `Unavailable(STORAGE_FAILURE)` | storage not called | unchanged |
| Generated candidate violates ID rules | `Unavailable(GENERATED_VALUE_INVALID)` | storage not called | unchanged |
| Storage returns invalid value | `Unavailable(STORED_VALUE_INVALID)` | no overwrite | unchanged |

All public calls are synchronous and thread-safe. The module has no callbacks, threads, executors, timeouts, or cancellation. Its internal lock is never held while the generator or storage implementation runs, so an implementation may synchronously re-enter `identity()` without deadlocking. Re-entrant resolution before the original call completes is treated as an independent contender and resolves through the same storage atomicity; it is never cached until valid.

No full device identifier, candidate, returned storage value, exception message, or stack trace is returned through a failure result.

## Tests

JVM tests must cover default validity, restored value, one-time creation and caching, competing storage winner, generator failures, storage failures, invalid generated and stored values, all boundary constraints, retry after every failure, concurrent callers, and re-entrant generator or storage behavior. Tests must confirm that no invalid candidate reaches storage and that no failure is cached.

## Compatibility rules

`DeviceId` and the failure enum are public, stable protocol boundaries. Adding a failure reason, changing identifier constraints, changing atomic storage semantics, or exposing storage implementation details requires updating this contract, the parent contract, consumers, and tests first.
