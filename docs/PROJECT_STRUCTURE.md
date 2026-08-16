# Mobile Relay Project Structure

This contract defines the only valid source layout for the Android relay. It follows the same source-oriented organisation used by the Sky Command desktop project: business code is grouped beneath `src/modules`, while repository-root files are limited to build configuration, the cross-application contract, documentation, and tooling.

## Repository layout

```text
CONTRACT.md                         # shared desktop/mobile behavioural contract
build.gradle.kts                    # shared Gradle conventions
settings.gradle.kts                 # stable Gradle paths -> physical module directories
docs/                               # design, contracts, and implementation history
gradle/                             # Gradle wrapper support files
src/
  app/                              # Android application module (:app); composition and UI only
  modules/
    <first-level-module>/
      CONTRACT.md                   # responsibility and public collaboration contract
      build.gradle.kts
      src/
        main/kotlin/                # facade or composition source only
        test/kotlin/                # facade behaviour tests only
      <second-level-module>/
        CONTRACT.md
        build.gradle.kts
        src/main/kotlin/
        src/test/kotlin/
```

`tests/` is reserved for future black-box, cross-module tests. It is created only when such a test exists. Unit tests stay next to the module whose contract they verify.

## First-level modules

The tree below matches `settings.gradle.kts`. A directory that exists on disk but is absent here is not compiled.

```text
src/modules/
  app-runtime/                      Android composition root
    app-bootstrap/                  start/stop ordering and dependency wiring
    foreground-service/             foreground-service lifecycle boundary
    permission-coordinator/         Android permission and USB authorisation state
    android-permission-adapter/     Android implementation of the PermissionPort seam
    android-foreground-service-adapter/ Android implementation of the ForegroundServicePort seam

  relay-settings/                   durable, validated relay configuration facade
    endpoint-settings/              desktop endpoint validation and normalisation
    device-identity/                stable device identity lifecycle
    settings-store/                 typed persistence semantics
    android-settings-adapter/       SharedPreferences implementation of the store port

  relay-gateway/                    desktop relay session facade
    protocol-core/                  wire model, codec, and protocol validation
    connection-session/             one desktop-session lifecycle and stale-callback isolation
    outbound-publisher/             ordered outbound delivery
    command-dispatcher/             command-to-handler routing
    mission-transfer/               ordered, verified mission-byte transfer
    transport-adapter/              network-library adaptation boundary

  device-connection/                DJI connection facade and device facts
    sdk-lifecycle/                  DJI SDK registration and availability lifecycle
    android-dji-sdk-adapter/        Android MSDK v5 registration adapter
    android-remote-controller-adapter/ Android MSDK v5 remote-controller facts adapter
    android-aircraft-adapter/       Android MSDK v5 aircraft facts adapter
    dji-operation-coordinator/      serialised DJI operation execution policy
    device-state-store/             single source of immutable device-state snapshots
    remote-controller-link/         remote-controller facts
    aircraft-link/                  aircraft facts
    pairing-controller/             pairing request lifecycle
    pairing-status-link/            pairing status facts
    android-pairing-status-adapter/ Android pairing status adapter
    android-pairing-command-adapter/ Android pairing command adapter
    device-capability-reader/       capability derivation from device facts

  telemetry/                        relay telemetry facade
    snapshot-assembler/             immutable telemetry snapshot construction
    capability-calculator/          relay capability representation
    telemetry-command-handler/      on-demand telemetry command handling
    telemetry-publisher/            continuous telemetry scheduling and publication
    flight-telemetry-port/          flight telemetry port
    android-flight-telemetry-adapter/ Android flight telemetry adapter

  wayline-mission/                  KMZ/WPMZ mission facade
    mission-staging/                safe local mission-byte staging
    mission-state-store/            mission metadata and execution state
    mission-uploader/               DJI upload orchestration
    mission-executor/               DJI mission execution controls
    mission-flight-phase/           entry and execution phase facts from DJI wayline state
    wayline-command-handler/        wayline command interpretation
    android-dji-wayline-adapter/    DJI MSDK v5 wayline adapter
    android-mission-staging-adapter/ private-directory staging adapter

  live-stream/                      RTMP live-stream facade
    stream-config-validator/        live-stream configuration validation
    stream-state-store/             live-stream state snapshots
    dji-stream-adapter/             DJI live-stream API adaptation
    stream-command-handler/         live-stream command interpretation
    android-dji-stream-adapter/     Android DJI live-stream adapter

  flight-control/                   manual flight-command facade
    flight-command-handler/         flight command interpretation
    dji-flight-adapter/             DJI flight API adaptation
    android-dji-flight-adapter/     Android DJI flight adapter

  device-settings/                  device settings facade
    settings-command-handler/       settings command interpretation
    settings-executor/              settings execution
    android-dji-settings-adapter/   Android DJI settings adapter

  runtime-diagnostics/              restricted diagnostic publication
    diagnostic-core/                diagnostic value model
    gateway-diagnostic-publisher/   gateway diagnostic publication
    android-diagnostic-adapter/     Android diagnostic adapter

  cross-runtime-e2e/                desktop/mobile verification harness (not operator APK behaviour)
    simulation-dji-adapter/         simulated DJI adapter for cross-runtime tests
    relay-test-harness/             relay test harness
```

`wayline-mission/wpmz-generator/` still exists on disk as historical source. It is not registered in `settings.gradle.kts`, is not a production dependency, and is not compiled into the APK. Do not treat it as an active module.

## Structural rules

1. A first-level module owns one complete business responsibility and exposes only its facade. Its facade may compose approved secondary-module ports, but may not absorb their implementation concerns.
2. A second-level module owns one independently understandable and testable responsibility. It must declare its inputs, outputs, lifecycle, error behaviour, and explicit non-responsibilities in `CONTRACT.md` before implementation changes.
3. A Gradle project path is a stable collaboration interface. Physical locations are mapped in `settings.gradle.kts`; moving a module must not rename its Gradle path, Kotlin packages, public APIs, or dependency coordinates without an explicit compatibility contract.
4. Production and unit-test sources belong inside the Gradle module that owns their behaviour. No business source may be placed in the repository root, `docs`, or generated output.
5. Modules communicate only through documented public ports, immutable values, command registration, or event publication. They may not read a sibling module's internal state or depend on Android/DJI/network globals.
6. Root-level `src/modules` contains business modules only. It contains neither generated output nor a second copy of an existing module.

## Generated files

`.gradle/`, `.kotlin/`, and every `build/` directory are generated locally and ignored by Git. They are not source of truth and must not contain manually maintained source, contracts, or tests.

## Change procedure

For a new first- or second-level module:

1. Add or amend the relevant detailed `CONTRACT.md`.
2. Create the module below `src/modules` in the layout above.
3. Register its unchanged logical Gradle path in `settings.gradle.kts` through `includeRelayModule`.
4. Implement against only documented ports.
5. Add deterministic tests for normal behaviour, invalid input, failure, cancellation, duplicate/late callbacks, and concurrent access where the contract permits concurrency.
6. Run the affected test suite before declaring the module complete.
