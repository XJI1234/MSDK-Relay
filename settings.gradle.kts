import org.gradle.api.initialization.resolve.RepositoriesMode

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "MSDKRelay"
include(":relay-gateway")
include(":relay-gateway:protocol-core")
include(":relay-gateway:connection-session")
include(":relay-gateway:outbound-publisher")
include(":relay-gateway:command-dispatcher")
include(":relay-gateway:mission-transfer")
include(":relay-gateway:transport-adapter")
include(":device-connection")
include(":device-connection:device-state-store")
include(":device-connection:sdk-lifecycle")
include(":device-connection:dji-operation-coordinator")
include(":device-connection:pairing-controller")
include(":device-connection:remote-controller-link")
include(":device-connection:aircraft-link")
include(":device-connection:device-capability-reader")
include(":telemetry")
include(":telemetry:snapshot-assembler")
include(":telemetry:capability-calculator")
include(":telemetry:telemetry-command-handler")
include(":telemetry:telemetry-publisher")
include(":wayline-mission")
include(":wayline-mission:mission-staging")
include(":wayline-mission:mission-state-store")
include(":wayline-mission:wpmz-generator")
include(":wayline-mission:mission-uploader")
include(":wayline-mission:mission-executor")
include(":wayline-mission:wayline-command-handler")
include(":live-stream")
include(":live-stream:stream-config-validator")
include(":live-stream:stream-state-store")
include(":live-stream:dji-stream-adapter")
