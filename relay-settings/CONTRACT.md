# relay-settings module contract

Status: facade implementation in progress
Version: 1.0.0
Gradle path: :relay-settings

`relay-settings` owns durable local relay configuration: validated computer endpoint and stable mobile device identity. It does not create network sessions, treat identity as authentication, or own DJI/Android business facts.

Second-level modules are `endpoint-settings` (endpoint validation), `device-identity` (stable generated identity), and `settings-store` (durable read/write, migration, and recovery). Gateway consumes only validated values produced by this module.

## Facade

`RelaySettings.create(backend, generator?)` is the only production construction point. It wires `settings-store` as the durable `DeviceIdentityStorage`; Android supplies the atomic `RelaySettingsBackend`, while the default generator remains inside `device-identity`.

```text
settings.loadEndpoint() -> SettingsLoadResult
settings.saveEndpoint(value) -> EndpointSaveResult
settings.clearEndpoint() -> EndpointSaveResult
settings.deviceIdentity() -> DeviceIdentityResult
settings.connectionSettings()
  -> Available(RelayConnectionSettings)
  | StoreUnavailable(SettingsStoreFailure)
  | IdentityUnavailable(DeviceIdentityFailure)
```

`RelayConnectionSettings` contains only a validated optional endpoint and a valid stable `DeviceId`. `connectionSettings` reads settings before resolving identity: a store failure does not invoke identity generation/storage. It is a consistent composition at the method boundary, not a transaction that locks future settings changes; a caller that retains it owns its own snapshot.

Endpoint mutations report only their durable endpoint outcome. They do not generate an identity, so a successfully saved endpoint can never be obscured by an unrelated identity failure. All lower-module contracts, thread-safety, recovery, and failure privacy rules remain in force through the facade.

## Tests

Facade tests cover construction, endpoint delegation, composed connection settings with and without an endpoint, store failure short-circuiting identity resolution, identity failure mapping, and concurrent composition with endpoint mutation.
