# stream-config-validator module contract

Status: approved for implementation
Version: 1.0.0
Parent module: live-stream
Gradle path: :live-stream:stream-config-validator

## 1. Single responsibility

This module validates one RTMP destination configuration. It does not start or stop streaming, access DJI or Android, resolve DNS, open sockets, persist the URL, or publish telemetry.

## 2. Public interface

```text
StreamConfigValidator.validate(rtmpUrl) -> Valid(ValidatedStreamConfig) | Invalid(reason)
```

`rtmpUrl` must be a nonblank string of at most 2048 Unicode code points. A valid value uses the `rtmp` scheme, has a host, has no user information, has an optional port from 1 through 65535, and has a nonblank path beginning with `/`. Query parameters are preserved for DJI authentication compatibility; URL fragments are rejected. The validator does not normalize or log secrets.

`ValidatedStreamConfig` is immutable and contains the original URL only. It is the only value accepted by the future DJI stream adapter.

## 3. Stable failures

Invalid inputs return only one of these reasons: `EMPTY`, `TOO_LONG`, `MALFORMED`, `INVALID_SCHEME`, `MISSING_HOST`, `INVALID_PORT`, `MISSING_PATH`, `USER_INFO_NOT_ALLOWED`, `FRAGMENT_NOT_ALLOWED`, or `CONTROL_CHARACTER`. The original URL and parser exception are never included in a failure.

Validation is synchronous, stateless, thread-safe, and deterministic. Calling it repeatedly has no side effects. No Android or network runtime is required.

## 4. Tests

Tests cover valid hostnames, IPv4, IPv6, ports, paths, query parameters, empty input, whitespace, length boundary, wrong schemes, missing host/path, invalid ports, credentials, fragments, malformed percent escapes, control characters, and concurrent calls.
