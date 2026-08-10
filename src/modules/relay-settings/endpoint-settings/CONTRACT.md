# endpoint-settings module contract

Status: approved for implementation
Version: 1.0.0
Parent module: relay-settings
Gradle path: :relay-settings:endpoint-settings

## Single responsibility

Validate one computer relay WebSocket destination. This module does not persist settings, resolve DNS, connect sockets, log endpoint data, or know device identity.

## Interface

`EndpointSettings.validate(value) -> Valid(ValidatedRelayEndpoint) | Invalid(reason)`.

A valid endpoint is at most 2048 Unicode code points, uses `ws` or `wss`, has a host, has no user information or fragment, and has either no port or a port from 1 through 65535. The path may be empty or begin with `/`; query parameters are preserved for future compatible authentication but never appear in failure data. The valid result retains the original value unchanged.

Failures are only `EMPTY`, `TOO_LONG`, `MALFORMED`, `INVALID_SCHEME`, `MISSING_HOST`, `INVALID_PORT`, `USER_INFO_NOT_ALLOWED`, `FRAGMENT_NOT_ALLOWED`, and `CONTROL_CHARACTER`. Validation is synchronous, pure, deterministic, and thread-safe.

## Tests

Cover ws/wss, DNS/IPv4/IPv6, optional path/query and port boundaries; empty, wrong scheme, missing host, malformed port/percent escape, credentials, fragment, controls, length boundary, and concurrent calls.
