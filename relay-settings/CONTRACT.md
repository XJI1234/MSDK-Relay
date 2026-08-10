# relay-settings module contract

Status: second-level implementation in progress
Version: 1.0.0
Gradle path: :relay-settings

`relay-settings` owns durable local relay configuration: validated computer endpoint and stable mobile device identity. It does not create network sessions, treat identity as authentication, or own DJI/Android business facts.

Second-level modules are `endpoint-settings` (endpoint validation), `device-identity` (stable generated identity), and `settings-store` (durable read/write, migration, and recovery). Gateway consumes only validated values produced by this module.
