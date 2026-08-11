plugins { kotlin("jvm"); `java-library` }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":device-connection:device-state-store"))
    api(project(":device-connection:device-capability-reader"))
    api(project(":telemetry:capability-calculator"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
}
tasks.test { useJUnitPlatform() }
