# Mobile Relay Project Structure

This repository is a Gradle multi-project build. The repository root contains only shared Gradle configuration, the cross-module mobile relay contract, documentation, and generated caches that are ignored by Git. It is not an application module and deliberately has no `src` directory.

## Module layout

Every first-level mobile responsibility is a Gradle module at the repository root:

```text
<first-level-module>/
  CONTRACT.md                 # responsibility and collaboration contract
  FACADE_CONTRACT.md          # only when a separate facade contract is needed
  build.gradle.kts
  src/
    main/kotlin/com/skycommand/relay/<area>/
    test/kotlin/com/skycommand/relay/<area>/
  <second-level-module>/
    CONTRACT.md
    build.gradle.kts
    src/main/kotlin/com/skycommand/relay/<area>/<responsibility>/
    src/test/kotlin/com/skycommand/relay/<area>/<responsibility>/
```

The first-level module's source directory contains only its facade or composition code. A second-level module contains the implementation for one responsibility and its tests. No source file may be placed directly in the repository root, `docs`, or a generated directory.

## Current Gradle modules

```text
app-runtime/                 Android composition root
  permission-coordinator/    permission and USB authorization lifecycle
  foreground-service/        planned
  app-bootstrap/             planned

relay-settings/              durable relay configuration facade
  endpoint-settings/
  device-identity/
  settings-store/

relay-gateway/               desktop relay session facade
device-connection/           DJI connection facade
telemetry/                   telemetry facade
wayline-mission/             mission facade
live-stream/                 live-stream facade
```

`src` is created only when a module has real source or tests. An aggregate module such as the not-yet-composed `app-runtime` therefore has no empty placeholder `src` directory. Its facade will be created in `app-runtime/src` together with `app-bootstrap`, rather than adding a file whose only purpose is to make a directory appear.

## Generated files

`.gradle/`, `.kotlin/`, and every `build/` directory are generated locally and ignored by Git. They are never a source of truth and may be regenerated with Gradle. A directory that is neither a Gradle project in `settings.gradle.kts` nor one of the documented root directories is invalid and must not contain source files.

When adding a module, update `settings.gradle.kts`, create its `CONTRACT.md` first, then create its `build.gradle.kts`, source, and tests in the layout above.
