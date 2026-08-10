plugins {
    kotlin("jvm")
    `java-library`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":device-connection:device-state-store"))
    api(project(":device-connection:sdk-lifecycle"))
    api(project(":device-connection:dji-operation-coordinator"))
    api(project(":device-connection:remote-controller-link"))
    api(project(":device-connection:aircraft-link"))
    api(project(":device-connection:pairing-controller"))
    api(project(":device-connection:device-capability-reader"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}

tasks.test { useJUnitPlatform() }
