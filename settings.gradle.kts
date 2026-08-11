import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MSDKRelay"

/**
 * Business modules are physically grouped below src/modules, while their
 * Gradle paths remain stable collaboration interfaces for every consumer.
 */
fun includeRelayModule(path: String) {
    include(path)
    project(path).projectDir = file(
        "src/modules/${path.removePrefix(":").replace(':', '/')}",
    )
}

includeRelayModule(":relay-gateway")
includeRelayModule(":relay-gateway:protocol-core")
includeRelayModule(":relay-gateway:connection-session")
includeRelayModule(":relay-gateway:outbound-publisher")
includeRelayModule(":relay-gateway:command-dispatcher")
includeRelayModule(":relay-gateway:mission-transfer")
includeRelayModule(":relay-gateway:transport-adapter")

includeRelayModule(":device-connection")
includeRelayModule(":device-connection:device-state-store")
includeRelayModule(":device-connection:sdk-lifecycle")
includeRelayModule(":device-connection:android-dji-sdk-adapter")
includeRelayModule(":device-connection:dji-operation-coordinator")
includeRelayModule(":device-connection:pairing-controller")
includeRelayModule(":device-connection:remote-controller-link")
includeRelayModule(":device-connection:aircraft-link")
includeRelayModule(":device-connection:device-capability-reader")

includeRelayModule(":telemetry")
includeRelayModule(":telemetry:snapshot-assembler")
includeRelayModule(":telemetry:capability-calculator")
includeRelayModule(":telemetry:telemetry-command-handler")
includeRelayModule(":telemetry:telemetry-publisher")

includeRelayModule(":wayline-mission")
includeRelayModule(":wayline-mission:mission-staging")
includeRelayModule(":wayline-mission:mission-state-store")
includeRelayModule(":wayline-mission:wpmz-generator")
includeRelayModule(":wayline-mission:mission-uploader")
includeRelayModule(":wayline-mission:mission-executor")
includeRelayModule(":wayline-mission:wayline-command-handler")

includeRelayModule(":live-stream")
includeRelayModule(":live-stream:stream-config-validator")
includeRelayModule(":live-stream:stream-state-store")
includeRelayModule(":live-stream:dji-stream-adapter")
includeRelayModule(":live-stream:stream-command-handler")

includeRelayModule(":relay-settings")
includeRelayModule(":relay-settings:endpoint-settings")
includeRelayModule(":relay-settings:device-identity")
includeRelayModule(":relay-settings:settings-store")
includeRelayModule(":relay-settings:android-settings-adapter")

includeRelayModule(":app-runtime")
includeRelayModule(":app-runtime:permission-coordinator")
includeRelayModule(":app-runtime:android-permission-adapter")
includeRelayModule(":app-runtime:android-foreground-service-adapter")
includeRelayModule(":app-runtime:foreground-service")
includeRelayModule(":app-runtime:app-bootstrap")
