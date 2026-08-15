plugins {
    kotlin("jvm")
    `java-library`
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":flight-control:dji-flight-adapter"))
    api(project(":wayline-mission:mission-uploader"))
    api(project(":wayline-mission:mission-executor"))
    api(project(":wayline-mission:mission-flight-phase"))
    api(project(":device-settings:settings-executor"))
    api(project(":live-stream:dji-stream-adapter"))
    api(project(":device-connection:sdk-lifecycle"))
    api(project(":device-connection:remote-controller-link"))
    api(project(":device-connection:aircraft-link"))
    api(project(":device-connection:pairing-controller"))
    api(project(":device-connection:pairing-status-link"))
    api(project(":telemetry:flight-telemetry-port"))
    api(project(":device-connection"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.test { useJUnitPlatform() }
